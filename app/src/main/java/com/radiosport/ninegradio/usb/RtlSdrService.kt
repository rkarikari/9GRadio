@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.radiosport.ninegradio.usb

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.*
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.radiosport.ninegradio.debug.DebugBus
import androidx.core.app.NotificationCompat
import com.radiosport.ninegradio.R
import com.radiosport.ninegradio.RtlSdrApplication
import com.radiosport.ninegradio.dsp.DspEngine
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.source.IqSource
import com.radiosport.ninegradio.source.RtlSdrDeviceSource
import com.radiosport.ninegradio.source.RtlTcpSource
import com.radiosport.ninegradio.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex

/**
 * Foreground service that owns the IQ source connection (USB RTL-SDR or TCP rtl_tcp)
 * and the DSP engine. Survives activity lifecycle changes.
 *
 * Source selection:
 *   • USB  — start with ACTION_START + EXTRA_DEVICE (UsbDevice)
 *   • TCP  — start with ACTION_START_TCP + EXTRA_TCP_HOST + EXTRA_TCP_PORT
 */
class RtlSdrService : Service() {

    companion object {
        private const val TAG = "RtlSdrService"
        private const val NOTIF_ID = 1001

        const val ACTION_START     = "com.radiosport.ninegradio.START"
        const val ACTION_START_TCP = "com.radiosport.ninegradio.START_TCP"
        const val ACTION_STOP      = "com.radiosport.ninegradio.STOP"

        const val EXTRA_DEVICE   = "usb_device"
        const val EXTRA_TCP_HOST = "tcp_host"
        const val EXTRA_TCP_PORT = "tcp_port"
        const val DEFAULT_TCP_PORT = 1234
    }

    inner class LocalBinder : Binder() {
        fun getService(): RtlSdrService = this@RtlSdrService
    }

    private val binder = LocalBinder()
    private val scope  = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** Prevents concurrent connectSource() calls racing. */
    private val connectMutex = Mutex()

    @Volatile var source: IqSource? = null
        private set
    @Volatile var dspEngine: DspEngine? = null

    // ── Persisted pending settings (applied on every reconnect) ──────────────

    @Volatile private var pendingFftSize: Int       = 0
    @Volatile private var pendingFftDecimation: Int = 1
    @Volatile private var pendingSampleRate: Int    = 1_920_000
    @Volatile private var pendingNoiseBlanker: Boolean  = false
    @Volatile private var pendingNoiseReducer: Boolean  = false
    @Volatile private var pendingIfBandwidth: Int   = 0
    @Volatile private var pendingAudioSinkRate: Int = DspEngine.DEFAULT_AUDIO_SINK_RATE

    // ── Connection state ──────────────────────────────────────────────────────

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting   : ConnectionState()
        data class Connected(val deviceName: String) : ConnectionState()
        data class Error(val message: String)        : ConnectionState()
    }

    // ── Service lifecycle ─────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "RtlSdrService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val usbDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(EXTRA_DEVICE, UsbDevice::class.java)
                else
                    @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DEVICE)
                startForegroundNotification()
                if (usbDevice != null) {
                    val usbManager = getSystemService(USB_SERVICE) as UsbManager
                    scope.launch { connectSource(RtlSdrDeviceSource(usbManager, usbDevice)) }
                }
            }
            ACTION_START_TCP -> {
                val host = intent.getStringExtra(EXTRA_TCP_HOST) ?: "localhost"
                val port = intent.getIntExtra(EXTRA_TCP_PORT, DEFAULT_TCP_PORT)
                startForegroundNotification()
                scope.launch { connectSource(RtlTcpSource(host, port)) }
            }
            ACTION_STOP -> {
                disconnectSource()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        disconnectSource()
        scope.cancel()
        Log.i(TAG, "RtlSdrService destroyed")
    }

    // ── Connection logic ──────────────────────────────────────────────────────

    private suspend fun connectSource(newSource: IqSource) {
        if (!connectMutex.tryLock()) {
            Log.w(TAG, "connectSource() called while already connecting — ignoring duplicate")
            return
        }
        try {
            _connectionState.value = ConnectionState.Connecting
            withContext(Dispatchers.IO) {
                try {
                    if (!newSource.open()) {
                        DebugBus.setError(DebugBus.STAGE_USB_OPEN, "source.open() returned false")
                        _connectionState.value = ConnectionState.Error("Failed to open source: ${newSource.getSourceName()}")
                        return@withContext
                    }

                    // Apply user-saved sample rate BEFORE streaming starts
                    val savedRate = pendingSampleRate
                    if (savedRate != newSource.getSampleRate()) {
                        newSource.setSampleRate(savedRate)
                    }

                    source = newSource
                    val engine = DspEngine(newSource)
                    dspEngine = engine

                    if (pendingFftSize > 0)      engine.setFftSize(pendingFftSize)
                    if (pendingFftDecimation > 1) engine.setFftDecimation(pendingFftDecimation)
                    if (pendingNoiseBlanker)      engine.setNoiseBlankerEnabled(true)
                    if (pendingNoiseReducer)      engine.setNoiseReducerEnabled(true)
                    val savedIfBw = pendingIfBandwidth
                    if (savedIfBw > 0)            engine.setIfBandwidth(savedIfBw)
                    val savedSinkRate = pendingAudioSinkRate
                    if (savedSinkRate != DspEngine.DEFAULT_AUDIO_SINK_RATE) {
                        engine.setAudioSinkRate(savedSinkRate)
                    }

                    engine.start()

                    val sourceName = newSource.getSourceName()
                    _connectionState.value = ConnectionState.Connected(sourceName)
                    updateNotification("Connected: $sourceName")

                    // Monitor IQ stream health — reconnect on persistent failures
                    scope.launch {
                        var consecutiveFailures = 0
                        val MAX_RESTART_ATTEMPTS = 5
                        val STABLE_STREAM_MS     = 10_000L
                        var stabilityJob: Job?   = null

                        newSource.statusFlow
                            .filter { it.streamRestartRequired }
                            .collect { _ ->
                                stabilityJob?.cancel()
                                stabilityJob = null

                                consecutiveFailures++
                                Log.w(TAG, "Stream restart requested (attempt $consecutiveFailures/$MAX_RESTART_ATTEMPTS)")
                                DebugBus.setStatus(DebugBus.STAGE_IQ_STREAM, DebugBus.StageStatus.OK,
                                    "Restarting stream (attempt $consecutiveFailures/$MAX_RESTART_ATTEMPTS)…")
                                delay(500L * consecutiveFailures.coerceAtMost(5))

                                // For USB sources delegate to RtlSdrDevice.restartStreaming();
                                // for TCP sources a full reconnect is the only recovery path.
                                val recovered = when (val src = source) {
                                    is RtlSdrDeviceSource -> src.device.restartStreaming()
                                    is RtlTcpSource -> {
                                        // TCP has no stream-restart; close and reconnect
                                        val host = src.host
                                        val port = src.port
                                        disconnectSource()
                                        scope.launch { connectSource(RtlTcpSource(host, port)) }
                                        return@collect
                                    }
                                    else -> false
                                }

                                if (!recovered) {
                                    val msg = "Source no longer available"
                                    Log.e(TAG, msg)
                                    DebugBus.setError(DebugBus.STAGE_IQ_STREAM, msg)
                                    disconnectSource()
                                    _connectionState.value = ConnectionState.Error(msg)
                                    return@collect
                                }
                                if (consecutiveFailures >= MAX_RESTART_ATTEMPTS) {
                                    val msg = "Stream could not be recovered after $consecutiveFailures attempts"
                                    Log.e(TAG, msg)
                                    DebugBus.setError(DebugBus.STAGE_IQ_STREAM, msg)
                                    disconnectSource()
                                    _connectionState.value = ConnectionState.Error(msg)
                                    return@collect
                                }
                                stabilityJob = scope.launch {
                                    delay(STABLE_STREAM_MS)
                                    Log.i(TAG, "Stream stable for ${STABLE_STREAM_MS}ms — resetting counter")
                                    consecutiveFailures = 0
                                }
                            }
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Connection error", e)
                    DebugBus.setError(DebugBus.STAGE_USB_OPEN, "Exception: ${e.javaClass.simpleName}: ${e.message}")
                    _connectionState.value = ConnectionState.Error(e.message ?: "Unknown error")
                }
            }
        } finally {
            connectMutex.unlock()
        }
    }

    fun disconnectSource() {
        dspEngine?.stop()
        dspEngine = null
        source?.close()
        source = null
        _connectionState.value = ConnectionState.Disconnected
        DebugBus.resetAll()
        updateNotification("Disconnected")
    }

    /** Reconnect the same USB device (e.g. after a USB permission / re-attach). */
    fun reconnect(usbDevice: UsbDevice) {
        disconnectSource()
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        scope.launch { connectSource(RtlSdrDeviceSource(usbManager, usbDevice)) }
    }

    /** Connect (or reconnect) to an rtl_tcp server. */
    fun connectTcp(host: String, port: Int = DEFAULT_TCP_PORT) {
        disconnectSource()
        scope.launch { connectSource(RtlTcpSource(host, port)) }
    }

    // ── Delegated flows ───────────────────────────────────────────────────────

    val spectrumFlow: Flow<FloatArray> = connectionState.flatMapLatest { state ->
        if (state is ConnectionState.Connected) dspEngine?.spectrumFlow ?: emptyFlow()
        else emptyFlow()
    }

    val statsFlow: Flow<DspEngine.DspStats> = connectionState.flatMapLatest { state ->
        if (state is ConnectionState.Connected) dspEngine?.statsFlow ?: emptyFlow()
        else emptyFlow()
    }

    /** Unified source status (covers both USB DeviceStatus and TCP SourceStatus). */
    val sourceStatusFlow: Flow<IqSource.SourceStatus> = connectionState.flatMapLatest { state ->
        if (state is ConnectionState.Connected) source?.statusFlow ?: emptyFlow()
        else emptyFlow()
    }

    /**
     * Legacy alias for [sourceStatusFlow] typed to [RtlSdrDevice.DeviceStatus].
     *
     * Callers that still use [RtlSdrDevice.DeviceStatus] (e.g. existing ViewModel observers)
     * can collect this flow and get a mapped value; new code should prefer [sourceStatusFlow].
     */
    val statusFlow: Flow<IqSource.SourceStatus> get() = sourceStatusFlow

    val audioFlow: Flow<FloatArray> = connectionState.flatMapLatest { state ->
        if (state is ConnectionState.Connected) dspEngine?.audioFlow ?: emptyFlow()
        else emptyFlow()
    }

    val dualAprsFlow: Flow<Pair<FloatArray, FloatArray>> = connectionState.flatMapLatest { state ->
        if (state is ConnectionState.Connected) dspEngine?.dualAprsFlow ?: emptyFlow()
        else emptyFlow()
    }

    // ── Convenience wrappers ──────────────────────────────────────────────────

    /**
     * Tune to [hz] (the user's dial frequency).
     *
     * Routing goes through [DspEngine.setCarrierFrequency] rather than directly to
     * [source] so the engine can transparently apply the ±1500 Hz SSB BFO offset for
     * USB and LSB modes.  When [dspEngine] is null (device not yet connected) we fall
     * back to [source] directly — this matches prior behaviour and is safe because
     * the engine will apply the offset via [DspEngine.setDemodMode] on the next connect.
     */
    fun setFrequency(hz: Long) {
        val engine = dspEngine
        if (engine != null) engine.setCarrierFrequency(hz)
        else source?.setCenterFrequency(hz)
    }
    fun setSampleRate(rate: Int)   {
        pendingSampleRate = rate
        source?.setSampleRate(rate)
    }
    fun setGain(idx: Int)          { source?.setGain(idx) }
    fun setGainMode(mode: Int)     { source?.setGainMode(mode) }
    fun setTunerAgc(on: Boolean)   { source?.setTunerAgcEnabled(on) }
    fun setHardwareAgc(on: Boolean){ source?.setHardwareAgcEnabled(on) }
    fun setBiasTee(on: Boolean)    { source?.setBiasTee(on) }
    fun setDirectSampling(mode: Int){ source?.setDirectSampling(mode) }
    fun setPpm(ppm: Int)           { source?.setPpmCorrection(ppm) }

    fun setFftSize(size: Int) {
        pendingFftSize = size
        dspEngine?.setFftSize(size)
    }
    fun setFftDecimation(factor: Int) {
        pendingFftDecimation = factor
        dspEngine?.setFftDecimation(factor)
    }
    fun setDemodMode(mode: DemodMode)     { dspEngine?.setDemodMode(mode) }
    fun setSquelch(dbLevel: Float)        { dspEngine?.setSquelchLevel(dbLevel) }
    fun setAudioVolume(vol: Float)        { dspEngine?.setVolume(vol) }
    fun setDcBlock(enabled: Boolean)      { dspEngine?.setDcBlockEnabled(enabled) }
    fun setFreqOffset(hz: Int)            { dspEngine?.setFreqOffset(hz) }
    fun enableDualAprs(centreHz: Long)    { dspEngine?.enableDualAprs(centreHz) }
    fun disableDualAprs()                 { dspEngine?.disableDualAprs() }
    fun setIfBandwidth(hz: Int) {
        pendingIfBandwidth = hz
        dspEngine?.setIfBandwidth(hz)
    }
    fun getIfBandwidth(): Int = dspEngine?.getIfBandwidth() ?: pendingIfBandwidth
    fun setNoiseBlanker(enabled: Boolean) {
        pendingNoiseBlanker = enabled
        dspEngine?.setNoiseBlankerEnabled(enabled)
    }
    fun setNoiseReducer(enabled: Boolean) {
        pendingNoiseReducer = enabled
        dspEngine?.setNoiseReducerEnabled(enabled)
    }
    fun setAudioSinkRate(rate: Int) {
        pendingAudioSinkRate = rate
        dspEngine?.setAudioSinkRate(rate)
    }
    fun getAudioSinkRate(): Int    = dspEngine?.getAudioSinkRate() ?: pendingAudioSinkRate
    fun setExternalDecoderEnabled(enabled: Boolean) { dspEngine?.setExternalDecoderEnabled(enabled) }
    fun setExternalDecoderTarget(host: String, port: Int) { dspEngine?.setExternalDecoderTarget(host, port) }
    fun startRecording(path: String)      { dspEngine?.startIqRecording(path) }
    fun stopRecording()                   { dspEngine?.stopIqRecording() }
    fun startAudioRecording(path: String) { dspEngine?.startAudioRecording(path) }
    fun stopAudioRecording()              { dspEngine?.stopAudioRecording() }

    // ── Legacy device accessor (null when using TCP source) ───────────────────

    /** The underlying [RtlSdrDevice], or null when connected via TCP. */
    val device: RtlSdrDevice? get() = (source as? RtlSdrDeviceSource)?.device

    /** True when the connected USB device is an RTL-SDR Blog V4. False for TCP or non-V4. */
    val isV4: Boolean get() = device?.isV4 ?: false

    // ── Foreground notification ───────────────────────────────────────────────

    private fun startForegroundNotification() {
        val notif = buildNotification("Initializing…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(status))
    }

    private fun buildNotification(status: String): Notification {
        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, RtlSdrService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, RtlSdrApplication.CHANNEL_SDR_SERVICE)
            .setContentTitle("9GRadio")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentIntent(intent)
            .addAction(R.drawable.ic_stop, "Disconnect", stopIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
