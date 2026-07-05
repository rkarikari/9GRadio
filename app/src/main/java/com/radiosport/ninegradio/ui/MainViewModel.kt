package com.radiosport.ninegradio.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.*
import com.radiosport.ninegradio.data.*
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.dsp.DspEngine
import com.radiosport.ninegradio.dsp.FftEngine
import com.radiosport.ninegradio.scanner.FrequencyScanner
import com.radiosport.ninegradio.source.IqSource
import com.radiosport.ninegradio.usb.RtlSdrDevice
import com.radiosport.ninegradio.usb.RtlSdrService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
        private const val PREFS = "rtlsdr_prefs"
    }

    private val db = (application as com.radiosport.ninegradio.RtlSdrApplication).database
    private val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ─── Service binding ──────────────────────────────────────────────────────

    private var sdrService: RtlSdrService? = null
    private val _serviceConnected = MutableStateFlow(false)
    val serviceConnected: StateFlow<Boolean> = _serviceConnected

    @Volatile private var pendingDevice: UsbDevice? = null

    val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            sdrService = (binder as RtlSdrService.LocalBinder).getService()
            _serviceConnected.value = true
            // Push the saved FFT size so the service's pendingFftSize is
            // populated before the first DspEngine is created.  Without this
            // the engine always starts at the default 256 bins regardless of
            // what the user previously chose.
            sdrService?.setFftSize(_fftSize.value)
            // Push the saved decimation factor.
            sdrService?.setFftDecimation(_decimation.value)
            // Push saved noise processing state.
            sdrService?.setNoiseBlanker(_noiseBlankerEnabled.value)
            sdrService?.setNoiseReducer(_noiseReducerEnabled.value)
            // Push saved IF bandwidth (0 = auto, ignored by service).
            val savedIfBw = _ifBandwidthHz.value
            if (savedIfBw > 0) sdrService?.setIfBandwidth(savedIfBw)
            // Push saved sample rate so pendingSampleRate is populated before
            // connectDevice() calls open().  The observeServiceFlows() Connected
            // handler also applies it post-connect, but that fires only after
            // engine.start() has already begun streaming.  Pushing here ensures
            // the rate is correct from the very first IQ block.
            sdrService?.setSampleRate(_sampleRate.value)
            observeServiceFlows()
            // Connect any device that arrived before the service bound.
            pendingDevice?.let { dev ->
                pendingDevice = null
                sdrService?.reconnect(dev)
            }
            Log.i(TAG, "Service connected")
        }
        override fun onServiceDisconnected(name: ComponentName) {
            sdrService = null
            _serviceConnected.value = false
        }
    }

    private fun observeServiceFlows() {
        val svc = sdrService ?: return

        // Mirror connection state, and push all saved settings to hardware
        // the moment the device reaches Connected.  The RTL-SDR resets to its
        // hardware defaults on every open() — notably sample rate = 2 048 000 Hz —
        // so without this block any user-chosen rate other than 2.048 MS/s is never
        // programmed, causing the DSP engine to process IQ at the wrong rate and
        // producing severely garbled audio on every mode.
        viewModelScope.launch {
            svc.connectionState.collect { state ->
                _connectionState.value = state

                if (state is RtlSdrService.ConnectionState.Connected) {
                    val svc2 = sdrService ?: return@collect

                    // 1. Frequency — before demod mode so direct-sampling is
                    //    configured at the correct band.
                    svc2.setFrequency(_centerFreqHz.value)

                    // 2. Demod mode — resets DSP demodulator and IF bandwidth.
                    svc2.setDemodMode(_demodMode.value)

                    // 3. Sample rate — programs the RTL2832U fractional divider.
                    //    Without this call the hardware stays at 2 048 000 Hz.
                    svc2.setSampleRate(_sampleRate.value)

                    // 4. Gain / AGC.
                    svc2.setTunerAgc(_tunerAgcEnabled.value)
                    svc2.setHardwareAgc(_hardwareAgcEnabled.value)
                    if (!_tunerAgcEnabled.value) svc2.setGain(_gainIndex.value)

                    // 5. IF bandwidth (0 = use mode default; skip).
                    val ifBw = _ifBandwidthHz.value
                    if (ifBw > 0) svc2.setIfBandwidth(ifBw)

                    // 6. Squelch, volume, PPM, bias-tee, direct sampling.
                    svc2.setSquelch(_squelch.value)
                    svc2.setAudioVolume(_volume.value)
                    svc2.setPpm(_ppm.value)
                    svc2.setBiasTee(_biasTee.value)
                    if (_directSampling.value != 0)
                        svc2.setDirectSampling(_directSampling.value)

                    // 7. Audio-sink rate — sets AudioTrack sample rate.
                    svc2.setAudioSinkRate(_audioSinkRate.value)

                    Log.i("MainViewModel",
                        "Device connected — applied saved settings: " +
                        "rate=${_sampleRate.value} mode=${_demodMode.value.name} " +
                        "gain=${_gainIndex.value} tunerAgc=${_tunerAgcEnabled.value}")
                }
            }
        }

        // Use the service's own delegated flows which handle the connectionState
        // switching internally. This eliminates any race between dspEngine being
        // assigned and the connectionState emission being observed here.
        viewModelScope.launch {
            svc.spectrumFlow.collect { spectrum ->
                _spectrumData.value = spectrum
                svc.dspEngine?.fftEngine?.getPeakHold()?.let { _peakData.value = it }
            }
        }

        viewModelScope.launch {
            svc.statusFlow.collect { status ->
                _deviceStatus.value = status
            }
        }

        viewModelScope.launch {
            svc.statsFlow.collect { stats ->
                _dspStats.value = stats
            }
        }
    }

    // ─── Observable state ─────────────────────────────────────────────────────

    private val _spectrumData = MutableStateFlow(FloatArray(256) { -100f })
    val spectrumData: StateFlow<FloatArray> = _spectrumData

    private val _peakData = MutableStateFlow(FloatArray(256) { -100f })
    val peakData: StateFlow<FloatArray> = _peakData

    private val _deviceStatus = MutableStateFlow(IqSource.SourceStatus())
    val deviceStatus: StateFlow<IqSource.SourceStatus> = _deviceStatus

    private val _dspStats = MutableStateFlow(DspEngine.DspStats())
    val dspStats: StateFlow<DspEngine.DspStats> = _dspStats

    private val _connectionState = MutableStateFlow<RtlSdrService.ConnectionState>(
        RtlSdrService.ConnectionState.Disconnected
    )
    val connectionState: StateFlow<RtlSdrService.ConnectionState> = _connectionState

    // Persistent settings
    private val _centerFreqHz = MutableStateFlow(run {
        val startupModeName = prefs.getString("demod", "NFM") ?: "NFM"
        val modeFreqKey = "mode_${startupModeName}_freq"
        if (prefs.contains(modeFreqKey))
            prefs.getLong(modeFreqKey, 100_000_000L)
        else
            prefs.getLong("freq", 100_000_000L)
    })
    val centerFreqHz: StateFlow<Long> = _centerFreqHz

    private val _demodMode = MutableStateFlow(
        DemodMode.valueOf(prefs.getString("demod", "NFM") ?: "NFM")
    )
    val demodMode: StateFlow<DemodMode> = _demodMode

    // Migrate any previously persisted rate to the nearest valid entry.
    // The RTL-SDR has a dead zone (~300–900 kS/s) where register ratios alias;
    // nearestSampleRate() snaps within the correct band (low-rate ≤300 kS/s or
    // main ≥900 kS/s) so a dead-zone value never reaches the hardware.
    private val _sampleRate = MutableStateFlow(
        RtlSdrDevice.nearestSampleRate(prefs.getInt("rate", 1_920_000)).also { snapped ->
            val saved = prefs.getInt("rate", 1_920_000)
            if (snapped != saved) prefs.edit().putInt("rate", snapped).apply()
        }
    )
    val sampleRate: StateFlow<Int> = _sampleRate

    private val _gainIndex = MutableStateFlow(prefs.getInt("gain", 26))
    val gainIndex: StateFlow<Int> = _gainIndex

    private val _gainMode = MutableStateFlow(prefs.getInt("gainMode", RtlSdrDevice.GAIN_MODE_MANUAL))
    val gainMode: StateFlow<Int> = _gainMode

    private val _tunerAgcEnabled = MutableStateFlow(prefs.getBoolean("tunerAgc", false))
    val tunerAgcEnabled: StateFlow<Boolean> = _tunerAgcEnabled

    private val _hardwareAgcEnabled = MutableStateFlow(prefs.getBoolean("hardwareAgc", false))
    val hardwareAgcEnabled: StateFlow<Boolean> = _hardwareAgcEnabled

    private val _biasTee = MutableStateFlow(prefs.getBoolean("biastee", false))
    val biasTee: StateFlow<Boolean> = _biasTee

    private val _noiseBlankerEnabled = MutableStateFlow(prefs.getBoolean("noiseBlanker", false))
    val noiseBlankerEnabled: StateFlow<Boolean> = _noiseBlankerEnabled

    private val _noiseReducerEnabled = MutableStateFlow(prefs.getBoolean("noiseReducer", false))
    val noiseReducerEnabled: StateFlow<Boolean> = _noiseReducerEnabled

    // IF filter bandwidth — 0 means "auto (dynamic per device rate via narrowIfRate)".
    // Stored as an absolute Hz value from [DspEngine.MIN_IF_BANDWIDTH_HZ] up to
    // narrowIfRate(deviceRate) — the ceiling is device-rate-dependent (e.g. 50 kHz
    // at 250 kS/s, 48 kHz at all 48k-multiple device rates).
    private val _ifBandwidthHz = MutableStateFlow(prefs.getInt("ifBandwidth", 0))
    val ifBandwidthHz: StateFlow<Int> = _ifBandwidthHz

    // Audio-output (sink) sample rate — the sample rate presented to Android
    // AudioTrack.  Independent of the internal 48 kHz DSP/decoder rate.
    // Default is DspEngine.DEFAULT_AUDIO_SINK_RATE (44 100 Hz).
    private val _audioSinkRate = MutableStateFlow(
        prefs.getInt("audioSinkRate", DspEngine.DEFAULT_AUDIO_SINK_RATE)
    )
    val audioSinkRate: StateFlow<Int> = _audioSinkRate

    private val _directSampling = MutableStateFlow(prefs.getInt("directSampling", 0))
    val directSampling: StateFlow<Int> = _directSampling

    private val _ppm = MutableStateFlow(run {
        // "ppm" is the RF-tab slider's store (rtlsdr_prefs).
        // "pref_ppm" is the Settings screen's store (default SharedPreferences).
        // On first launch (or after a fresh install) only one of these may have
        // been written; prefer whichever was written, falling back to 0.
        val rfPanelPpm = if (prefs.contains("ppm")) prefs.getInt("ppm", 0) else null
        val settingsPpm = if (androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(application).contains("pref_ppm"))
            androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(application).getInt("pref_ppm", 0)
        else null
        rfPanelPpm ?: settingsPpm ?: 0
    })
    val ppm: StateFlow<Int> = _ppm

    private val _allowOutOfBand = MutableStateFlow(prefs.getBoolean("allowOutOfBand", false))
    val allowOutOfBand: StateFlow<Boolean> = _allowOutOfBand

    // Default −120 dB = slider minimum (fully open). The old −100 default pre-dated
    // the FFT-peak squelch; wideband RMS at 2 MS/s read ~−20..−45 dBFS so −100 was
    // effectively always open. With FFT-peak the noise floor sits at ~−65 dBFS, so
    // −100 keeps squelch permanently closed and silences NFM.  −120 = always open.
    //
    // On startup: if the current mode has ever had its squelch explicitly set, use
    // that per-mode value rather than the global settings default.  This means the
    // app resumes with the same squelch level the user last used for this protocol,
    // not a generic default.  Falls back to global "squelch" only when the mode has
    // never been configured (first use).
    private val _squelch = MutableStateFlow(run {
        val startupModeName = prefs.getString("demod", "NFM") ?: "NFM"
        val modeSquelchKey = "mode_${startupModeName}_squelch"
        if (prefs.contains(modeSquelchKey))
            prefs.getFloat(modeSquelchKey, -120f)
        else
            prefs.getFloat("squelch", -120f)
    })
    val squelch: StateFlow<Float> = _squelch

    private val _volume = MutableStateFlow(prefs.getFloat("volume", 1f))
    val volume: StateFlow<Float> = _volume

    private val _fftSize = MutableStateFlow(prefs.getInt("fftSize", 256))
    val fftSize: StateFlow<Int> = _fftSize

    /**
     * FFT decimation factor — persisted across sessions.
     * 1 = off (full bandwidth); 2/4/8/16/32/64 = narrowed bandwidth.
     * The effective displayed sample rate is sampleRate / decimation.
     */
    private val _decimation = MutableStateFlow(
        prefs.getInt("fftDecimation", 64).let { saved ->
            if (saved in intArrayOf(1, 2, 4, 8, 16, 32, 64)) saved else 1
        }
    )
    val decimation: StateFlow<Int> = _decimation

    private val _waterfallPalette = MutableStateFlow(
        WaterfallView.Companion.Palette.valueOf(
            prefs.getString("palette", "RAINBOW") ?: "RAINBOW"
        )
    )
    val waterfallPalette: StateFlow<WaterfallView.Companion.Palette> = _waterfallPalette

    private val _spectrumTheme = MutableStateFlow(prefs.getString("theme", "Futuristic") ?: "Futuristic")
    val spectrumTheme: StateFlow<String> = _spectrumTheme

    // ─── Database flows ───────────────────────────────────────────────────────

    val memoryChannels = db.memoryChannelDao().getAll()
    val bookmarks = db.bookmarkDao().getAll()
    val bookmarkLists = db.bookmarkListDao().getAll()
    val favoriteBookmarks = db.bookmarkDao().getFavorites()
    val scanEntries = db.scanEntryDao().getAll()
    val recordings = db.recordingMetaDao().getAll()

    // ─── SDR Control commands ─────────────────────────────────────────────────

    fun connectDevice(usbDevice: UsbDevice) {
        val svc = sdrService
        if (svc == null) {
            pendingDevice = usbDevice
            return
        }
        pendingDevice = null
        svc.reconnect(usbDevice)
    }

    /**
     * Connect to an rtl_tcp / rtl_tcp_andro server.
     * Saves host:port to SharedPreferences so the UI can restore them.
     */
    fun connectTcp(host: String, port: Int) {
        prefs.edit()
            .putString("tcp_host", host)
            .putInt("tcp_port", port)
            .apply()
        sdrService?.connectTcp(host, port)
    }

    /** Last-used TCP host (empty string if never set). */
    fun savedTcpHost(): String = prefs.getString("tcp_host", "") ?: ""
    /** Last-used TCP port (default 1234). */
    fun savedTcpPort(): Int = prefs.getInt("tcp_port", 1234)

    fun setFrequency(hz: Long) {
        val clamped = if (_allowOutOfBand.value) hz
                      else hz.coerceIn(RtlSdrDevice.MIN_FREQ_HZ, RtlSdrDevice.MAX_FREQ_HZ)
        _centerFreqHz.value = clamped
        sdrService?.setFrequency(clamped)
        prefs.edit().putLong("freq", clamped).apply()
    }

    // ─── Per-mode settings store ──────────────────────────────────────────────

    /**
     * RF settings that are saved and restored independently for every DemodMode.
     * When the user switches from NFM to AM (for example) the current NFM settings
     * are written to "mode_NFM_*" keys, then the AM snapshot (if any) is read back
     * and applied.  On the very first switch to a mode the global current values
     * carry forward, so there is no reset to factory defaults.
     *
     * Persisted fields per mode:
     *   freq         – center frequency in Hz
     *   gain         – gain-table index (0-28)
     *   gainMode     – GAIN_MODE_AGC / GAIN_MODE_MANUAL
     *   tunerAgc     – Boolean
     *   hardwareAgc  – Boolean
     *   rate         – sample rate in Hz
     *   fftDecimation
     *   fftSize      – FFT bin count (256..8192)
     *   frameAvg     – frame averaging count (1/2/4/8/16/32)
     *   specFloor    – spectrum floor in dBFS (negative float, stored as negative)
     *   specCeiling  – spectrum ceiling in dBFS (≤0 float)
     *   squelch      – dBFS float
     *   ifBandwidth  – Hz, 0 = mode default
     *
     * Additionally, every Drawer "RF" tab and "Display" tab control is saved
     * and restored per mode (see the dedicated blocks in [saveSettingsForMode]
     * and [restoreSettingsForMode] below). RF-tab extras: volume, biasTee,
     * noiseBlanker, noiseReducer, directSampling, ppm, audioSinkRate.
     * Display-tab extras (beyond fftSize/fftDecimation/frameAvg/specFloor/
     * specCeiling already listed above): spectrumTheme, waterfallPalette,
     * waterfallSpeed, peakHold, fillOpacity, autoRange, showNoiseFloor,
     * peakDecay, showPeakAnnotations, crosshairEnabled, waterfallBrightness,
     * waterfallContrast, waterfallAutoStretch, waterfallShowTimestamp,
     * waterfallShowTunerMarker.
     */
    private fun modeKey(mode: DemodMode, field: String) = "mode_${mode.name}_$field"

    private fun displayPrefs() =
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(getApplication())

    /** Save the current live RF state under the given mode's namespace. */
    private fun saveSettingsForMode(mode: DemodMode) {
        val dp = displayPrefs()
        prefs.edit()
            .putLong   (modeKey(mode, "freq"),          _centerFreqHz.value)
            .putInt    (modeKey(mode, "gain"),          _gainIndex.value)
            .putInt    (modeKey(mode, "gainMode"),      _gainMode.value)
            .putBoolean(modeKey(mode, "tunerAgc"),      _tunerAgcEnabled.value)
            .putBoolean(modeKey(mode, "hardwareAgc"),   _hardwareAgcEnabled.value)
            .putInt    (modeKey(mode, "rate"),          _sampleRate.value)
            .putInt    (modeKey(mode, "fftDecimation"), _decimation.value)
            .putInt    (modeKey(mode, "fftSize"),        _fftSize.value)
            .putInt    (modeKey(mode, "frameAvg"),       dp.getString("pref_frame_averaging", "1")?.toIntOrNull() ?: 1)
            .putFloat  (modeKey(mode, "specFloor"),      dp.getInt("pref_db_min", 120).toFloat() * -1f)
            .putFloat  (modeKey(mode, "specCeiling"),    dp.getInt("pref_db_max", 0).toFloat())
            .putFloat  (modeKey(mode, "squelch"),        _squelch.value)
            .putInt    (modeKey(mode, "ifBandwidth"),    _ifBandwidthHz.value)
            // ── RF tab extras ──────────────────────────────────────────────
            .putFloat  (modeKey(mode, "volume"),         _volume.value)
            .putBoolean(modeKey(mode, "biasTee"),        _biasTee.value)
            .putBoolean(modeKey(mode, "noiseBlanker"),   _noiseBlankerEnabled.value)
            .putBoolean(modeKey(mode, "noiseReducer"),   _noiseReducerEnabled.value)
            .putInt    (modeKey(mode, "directSampling"), _directSampling.value)
            .putInt    (modeKey(mode, "ppm"),            _ppm.value)
            .putInt    (modeKey(mode, "audioSinkRate"),  _audioSinkRate.value)
            // ── Display tab extras ─────────────────────────────────────────
            .putString (modeKey(mode, "spectrumTheme"),       dp.getString("pref_spectrum_theme", "Futuristic"))
            .putString (modeKey(mode, "waterfallPalette"),    dp.getString("pref_waterfall_palette", "RAINBOW"))
            .putInt    (modeKey(mode, "waterfallSpeed"),      dp.getInt("pref_waterfall_speed", 25))
            .putBoolean(modeKey(mode, "peakHold"),            dp.getBoolean("pref_peak_hold", true))
            .putInt    (modeKey(mode, "fillOpacity"),         dp.getInt("pref_fill_opacity", 73))
            .putBoolean(modeKey(mode, "autoRange"),           dp.getBoolean("pref_auto_range", false))
            .putBoolean(modeKey(mode, "showNoiseFloor"),      dp.getBoolean("pref_show_noise_floor", true))
            .putInt    (modeKey(mode, "peakDecay"),           dp.getInt("pref_peak_decay", 2))
            .putBoolean(modeKey(mode, "showPeakAnnotations"), dp.getBoolean("pref_show_peak_annotations", true))
            .putBoolean(modeKey(mode, "crosshairEnabled"),    dp.getBoolean("pref_crosshair_enabled", true))
            .putInt    (modeKey(mode, "waterfallBrightness"), dp.getInt("pref_waterfall_brightness", 50))
            .putInt    (modeKey(mode, "waterfallContrast"),   dp.getInt("pref_waterfall_contrast", 50))
            .putBoolean(modeKey(mode, "waterfallAutoStretch"),dp.getBoolean("pref_waterfall_auto_stretch", false))
            .putBoolean(modeKey(mode, "waterfallShowTimestamp"),  dp.getBoolean("pref_waterfall_show_timestamp", true))
            .putBoolean(modeKey(mode, "waterfallShowTunerMarker"), dp.getBoolean("pref_waterfall_show_tuner_marker", true))
            .apply()
    }

    /**
     * Restore RF state for [mode] from its saved snapshot.
     * Returns true if a snapshot existed and was applied; false if this is the
     * first time the mode has been used (caller may apply its own defaults).
     */
    private fun restoreSettingsForMode(mode: DemodMode): Boolean {
        val key = modeKey(mode, "gain")
        if (!prefs.contains(key)) return false   // never saved — first use

        val freq    = prefs.getLong   (modeKey(mode, "freq"),          _centerFreqHz.value)
        val gain    = prefs.getInt    (modeKey(mode, "gain"),          _gainIndex.value)
        val gMode   = prefs.getInt    (modeKey(mode, "gainMode"),      _gainMode.value)
        val tAgc    = prefs.getBoolean(modeKey(mode, "tunerAgc"),      _tunerAgcEnabled.value)
        val hAgc    = prefs.getBoolean(modeKey(mode, "hardwareAgc"),   _hardwareAgcEnabled.value)
        val rate    = prefs.getInt    (modeKey(mode, "rate"),          _sampleRate.value)
        val decim      = prefs.getInt    (modeKey(mode, "fftDecimation"), _decimation.value)
        val fftSz      = prefs.getInt    (modeKey(mode, "fftSize"),        _fftSize.value)
        val frameAvg   = prefs.getInt    (modeKey(mode, "frameAvg"),       1)
        val specFloor  = prefs.getFloat  (modeKey(mode, "specFloor"),      -120f)
        val specCeiling= prefs.getFloat  (modeKey(mode, "specCeiling"),    0f)
        val squelch    = prefs.getFloat  (modeKey(mode, "squelch"),        _squelch.value)
        val ifBw       = prefs.getInt    (modeKey(mode, "ifBandwidth"),    0)

        // RF tab extras
        val volume         = prefs.getFloat  (modeKey(mode, "volume"),         _volume.value)
        val biasTee        = prefs.getBoolean(modeKey(mode, "biasTee"),        _biasTee.value)
        val noiseBlanker   = prefs.getBoolean(modeKey(mode, "noiseBlanker"),   _noiseBlankerEnabled.value)
        val noiseReducer   = prefs.getBoolean(modeKey(mode, "noiseReducer"),   _noiseReducerEnabled.value)
        val directSampling = prefs.getInt    (modeKey(mode, "directSampling"), _directSampling.value)
        val ppmVal         = prefs.getInt    (modeKey(mode, "ppm"),            _ppm.value)
        val audioSinkRate  = prefs.getInt    (modeKey(mode, "audioSinkRate"),  _audioSinkRate.value)

        // Display tab extras
        val dp = displayPrefs()
        val spectrumTheme        = prefs.getString (modeKey(mode, "spectrumTheme"),       dp.getString("pref_spectrum_theme", "Futuristic"))
        val waterfallPalette     = prefs.getString (modeKey(mode, "waterfallPalette"),    dp.getString("pref_waterfall_palette", "RAINBOW"))
        val waterfallSpeed       = prefs.getInt    (modeKey(mode, "waterfallSpeed"),      dp.getInt("pref_waterfall_speed", 25))
        val peakHold             = prefs.getBoolean(modeKey(mode, "peakHold"),            dp.getBoolean("pref_peak_hold", true))
        val fillOpacity          = prefs.getInt    (modeKey(mode, "fillOpacity"),         dp.getInt("pref_fill_opacity", 73))
        val autoRange            = prefs.getBoolean(modeKey(mode, "autoRange"),           dp.getBoolean("pref_auto_range", false))
        val showNoiseFloor       = prefs.getBoolean(modeKey(mode, "showNoiseFloor"),      dp.getBoolean("pref_show_noise_floor", true))
        val peakDecay            = prefs.getInt    (modeKey(mode, "peakDecay"),           dp.getInt("pref_peak_decay", 2))
        val showPeakAnnotations  = prefs.getBoolean(modeKey(mode, "showPeakAnnotations"), dp.getBoolean("pref_show_peak_annotations", true))
        val crosshairEnabled     = prefs.getBoolean(modeKey(mode, "crosshairEnabled"),    dp.getBoolean("pref_crosshair_enabled", true))
        val waterfallBrightness  = prefs.getInt    (modeKey(mode, "waterfallBrightness"), dp.getInt("pref_waterfall_brightness", 50))
        val waterfallContrast    = prefs.getInt    (modeKey(mode, "waterfallContrast"),   dp.getInt("pref_waterfall_contrast", 50))
        val waterfallAutoStretch = prefs.getBoolean(modeKey(mode, "waterfallAutoStretch"),dp.getBoolean("pref_waterfall_auto_stretch", false))
        val waterfallShowTimestamp   = prefs.getBoolean(modeKey(mode, "waterfallShowTimestamp"),    dp.getBoolean("pref_waterfall_show_timestamp", true))
        val waterfallShowTunerMarker = prefs.getBoolean(modeKey(mode, "waterfallShowTunerMarker"), dp.getBoolean("pref_waterfall_show_tuner_marker", true))

        // Apply to state flows + hardware — use internal setters to avoid
        // recursive setDemodMode calls; hardware calls go through the service.
        val clamped = if (_allowOutOfBand.value) freq
                      else freq.coerceIn(RtlSdrDevice.MIN_FREQ_HZ, RtlSdrDevice.MAX_FREQ_HZ)
        _centerFreqHz.value = clamped
        sdrService?.setFrequency(clamped)

        _gainIndex.value        = gain
        _gainMode.value         = gMode
        _tunerAgcEnabled.value  = tAgc
        _hardwareAgcEnabled.value = hAgc
        // AGC and gain are pushed AFTER setDirectSampling() below.
        // setDirectSampling(OFF) calls r82xxInit() which writes the R82xx
        // initialisation register array to hardware.  That array hardcodes
        // reg 0x05/0x07 with LNA-auto=ON / Mixer-auto=ON (AGC enabled),
        // silently overwriting any AGC state pushed here before that call.
        // Deferring the hardware push until after setDirectSampling() ensures
        // the correct saved AGC state is the last thing written to the device.

        val validRate = RtlSdrDevice.nearestSampleRate(rate)
        _sampleRate.value = validRate
        sdrService?.setSampleRate(validRate)

        val validDecim = if (decim in intArrayOf(1, 2, 4, 8, 16, 32, 64)) decim else 1
        _decimation.value = validDecim
        sdrService?.setFftDecimation(validDecim)

        val validFftSz = if (fftSz in intArrayOf(256, 512, 1024, 2048, 4096, 8192)) fftSz else _fftSize.value
        if (validFftSz != _fftSize.value) {
            _fftSize.value = validFftSz
            sdrService?.setFftSize(validFftSz)
        }

        // frameAvg, specFloor, specCeiling, and all other Display-tab extras are
        // persisted in defaultSharedPreferences so the Display tab and
        // applyDisplayPreferences() pick them up automatically. The caller
        // (setDemodMode -> MainActivity's demodMode observer) MUST invoke
        // applyDisplayPreferences() after this returns, or these writes sit
        // inert until the next unrelated trigger re-reads them.
        val validFrameAvg = if (frameAvg in intArrayOf(1, 2, 4, 8, 16, 32)) frameAvg else 1
        dp.edit()
            // Write pref_decimation and pref_fft_size so applyDisplayPreferences()
            // (called immediately after this returns) finds values matching the
            // just-restored ViewModel state and its equality guards don't
            // incorrectly overwrite them with whatever was in the pref store before.
            .putString ("pref_decimation",      validDecim.toString())
            .putString ("pref_fft_size",        validFftSz.toString())
            .putString ("pref_frame_averaging", validFrameAvg.toString())
            .putInt    ("pref_db_min", (specFloor * -1f).toInt().coerceIn(0, 200))
            .putInt    ("pref_db_max", specCeiling.toInt().coerceIn(-100, 50))
            .putString ("pref_spectrum_theme", spectrumTheme)
            .putString ("pref_waterfall_palette", waterfallPalette)
            .putInt    ("pref_waterfall_speed", waterfallSpeed)
            .putBoolean("pref_peak_hold", peakHold)
            .putInt    ("pref_fill_opacity", fillOpacity)
            .putBoolean("pref_auto_range", autoRange)
            .putBoolean("pref_show_noise_floor", showNoiseFloor)
            .putInt    ("pref_peak_decay", peakDecay)
            .putBoolean("pref_show_peak_annotations", showPeakAnnotations)
            .putBoolean("pref_crosshair_enabled", crosshairEnabled)
            .putInt    ("pref_waterfall_brightness", waterfallBrightness)
            .putInt    ("pref_waterfall_contrast", waterfallContrast)
            .putBoolean("pref_waterfall_auto_stretch", waterfallAutoStretch)
            .putBoolean("pref_waterfall_show_timestamp", waterfallShowTimestamp)
            .putBoolean("pref_waterfall_show_tuner_marker", waterfallShowTunerMarker)
            .apply()

        // RF tab extras — apply through the normal setters so hardware, state
        // flows, and the RF-tab UI observer all stay in sync.
        setVolume(volume)
        setBiasTee(biasTee)
        setNoiseBlanker(noiseBlanker)
        setDirectSampling(directSampling)
        setPpm(ppmVal)
        setAudioSinkRate(audioSinkRate)

        // Apply AGC and gain HERE — after setDirectSampling() — because
        // setDirectSampling(OFF) calls r82xxInit() which resets the R82xx
        // tuner registers (including the LNA/Mixer AGC bits in reg 0x05/0x07)
        // to the factory default (AGC enabled).  Any AGC state pushed earlier
        // in this function is clobbered by that re-init; pushing it again
        // here ensures the saved per-protocol AGC state is the last thing
        // written, so the hardware ends up exactly where the snapshot says.
        // Note: setTunerAgcEnabled() already gates setGain() on !enable
        // internally, so the extra guard here is defensive-in-depth.
        sdrService?.setTunerAgc(tAgc)
        sdrService?.setHardwareAgc(hAgc)
        if (!tAgc) sdrService?.setGain(gain)

        _squelch.value = squelch
        sdrService?.setSquelch(squelch)

        // FIX 25: APRS (and USB/LSB) are forced onto the auto (-1) sentinel by
        // setDemodMode() — see DspEngine.setDemodMode() FIX 24. A stale
        // per-mode snapshot saved from BEFORE that fix (or from manually
        // dragging the RF-tab "Width" slider) can contain a literal
        // ifBandwidth=48000 for APRS, which is not an exact divisor of common
        // device rates (2.048/1.024/2.56 MS/s) and re-introduces the
        // fractional-resampler regression FIX 24 removes. Skip restoring a
        // saved ifBandwidth for these always-auto modes; the -1 sentinel
        // setDemodMode() already applied is authoritative for them.
        val forcesAuto = mode == DemodMode.USB || mode == DemodMode.LSB || mode == DemodMode.APRS
        if (!forcesAuto) {
            _ifBandwidthHz.value = ifBw
            if (ifBw > 0) sdrService?.setIfBandwidth(ifBw)
        }

        // Persist the restored values as the current global state
        prefs.edit()
            .putLong   ("freq",          clamped)
            .putInt    ("gain",          gain)
            .putInt    ("gainMode",      gMode)
            .putBoolean("tunerAgc",      tAgc)
            .putBoolean("hardwareAgc",   hAgc)
            .putInt    ("rate",          validRate)
            .putInt    ("fftDecimation", validDecim)
            .putInt    ("fftSize",       validFftSz)
            .putFloat  ("squelch",       squelch)
            .putInt    ("ifBandwidth",   ifBw)
            .apply()
        // Apply noise reducer last — after mode switch, demodulator creation,
        // sample-rate, squelch, gain, and IF bandwidth are all in their final
        // state.  setNoiseReducer triggers reset() which re-calibrates the noise
        // floor estimator; doing it last ensures calibration runs against the
        // correctly configured audio pipeline for the new mode.
        setNoiseReducer(noiseReducer)
        return true
    }

    /**
     * Returns true if a per-mode settings snapshot has ever been saved for [mode].
     * Used by protocol activities to decide whether to seed first-use defaults.
     */
    fun hasModeSnapshot(mode: DemodMode): Boolean = prefs.contains(modeKey(mode, "gain"))

    fun setDemodMode(mode: DemodMode) {
        // 1. Snapshot RF settings for the mode we are leaving
        saveSettingsForMode(_demodMode.value)

        // 2. Apply hardware/mode change and prefs (but don't emit _demodMode
        //    yet — observers react to that emission and must see the
        //    restored frequency, not the outgoing mode's frequency).
        sdrService?.setDemodMode(mode)
        prefs.edit().putString("demod", mode.name).apply()
        if (mode.requiresDirectSampling) {
            setDirectSampling(RtlSdrDevice.DIRECT_SAMPLING_Q)
        }

        // 3. Restore the incoming mode's saved snapshot (if any) — this
        //    updates _centerFreqHz to the frequency in effect for [mode].
        //    If none exists the current RF state carries forward untouched.
        val hadSnapshot = restoreSettingsForMode(mode)

        // 3b. Intelligent Auto-Configuration: the FIRST time a protocol is
        //     ever selected (no snapshot exists yet -- restoreSettingsForMode
        //     returned false, so nothing above touched FFT size/decimation/
        //     frame averaging), seed FFT size, frame averaging, and
        //     protocol-aware decimation from DemodMode's intelligent
        //     defaults rather than silently carrying forward whatever the
        //     previous mode happened to be using. This never overrides a
        //     value the user has actually chosen for this mode before --
        //     once saveSettingsForMode() has snapshotted a mode once, its
        //     restored values always win on subsequent visits.
        if (!hadSnapshot) {
            applyIntelligentAutoConfig(mode)
        }

        // 4. Reset IF bandwidth to saved value (already handled inside
        //    restoreSettingsForMode); if no snapshot existed, reset to mode default.
        if (!prefs.contains(modeKey(mode, "ifBandwidth"))) {
            _ifBandwidthHz.value = 0
            prefs.edit().putInt("ifBandwidth", 0).apply()
        }

        // 5. Commit the new mode last, so collectors of demodMode (e.g. the
        //    main frequency display) observe the already-restored frequency
        //    in effect for the new protocol.
        _demodMode.value = mode
    }

    /**
     * Seed FFT size, frame averaging, and decimation for [mode] from
     * [DemodMode]'s intelligent per-protocol defaults (2048 bins / x8
     * averaging / protocol-aware decimation -- see DemodMode.kt). Only
     * called on a mode's first-ever selection (restoreSettingsForMode()
     * found no prior snapshot), so it never clobbers a value the user has
     * previously set for this specific mode.
     */
    private fun applyIntelligentAutoConfig(mode: DemodMode) {
        val dp = displayPrefs()

        setFftSize(mode.defaultFftSize)
        setDecimation(mode.defaultDecimation)

        dp.edit()
            .putString("pref_fft_size", mode.defaultFftSize.toString())
            .putString("pref_decimation", mode.defaultDecimation.toString())
            .putString("pref_frame_averaging", mode.defaultFrameAveraging.toString())
            .apply()
        sdrService?.dspEngine?.fftEngine?.frameAveragingCount = mode.defaultFrameAveraging
    }

    fun setSampleRate(rate: Int) {
        val snapped = RtlSdrDevice.nearestSampleRate(rate)
        _sampleRate.value = snapped
        sdrService?.setSampleRate(snapped)
        prefs.edit().putInt("rate", snapped).apply()
    }

    fun setAudioSinkRate(rate: Int) {
        _audioSinkRate.value = rate
        sdrService?.setAudioSinkRate(rate)
        prefs.edit().putInt("audioSinkRate", rate).apply()
        // Keep the Settings screen's "Audio Output Sample Rate" ListPreference
        // (key "pref_audio_sink_rate", stored in the *default* SharedPreferences
        // file — separate from "rtlsdr_prefs") in sync. Without this,
        // SettingsFragment.applyAllPrefsToEngine() re-applies its own stale/
        // default value (44100) every time the Settings screen is opened,
        // silently reverting the rate chosen here and rebuilding the
        // AudioTrack + resampler chain (audible glitch/dropout).
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(getApplication())
            .edit().putString("pref_audio_sink_rate", rate.toString()).apply()
    }

    fun setGain(index: Int) {
        _gainIndex.value = index
        sdrService?.setGain(index)
        prefs.edit().putInt("gain", index).apply()
    }

    fun setGainMode(mode: Int) {
        _gainMode.value = mode
        sdrService?.setGainMode(mode)
        prefs.edit().putInt("gainMode", mode).apply()
        // Keep individual AGC flags in sync with mode
        val agc = (mode == RtlSdrDevice.GAIN_MODE_AGC)
        _tunerAgcEnabled.value    = agc
        _hardwareAgcEnabled.value = agc
        prefs.edit().putBoolean("tunerAgc", agc).putBoolean("hardwareAgc", agc).apply()
    }

    fun setTunerAgc(enabled: Boolean) {
        _tunerAgcEnabled.value = enabled
        sdrService?.setTunerAgc(enabled)
        prefs.edit().putBoolean("tunerAgc", enabled).apply()
        // Derive gainMode from combined AGC state
        val manual = !enabled && !_hardwareAgcEnabled.value
        val mode   = if (manual) RtlSdrDevice.GAIN_MODE_MANUAL else RtlSdrDevice.GAIN_MODE_AGC
        _gainMode.value = mode
        prefs.edit().putInt("gainMode", mode).apply()
    }

    fun setHardwareAgc(enabled: Boolean) {
        _hardwareAgcEnabled.value = enabled
        sdrService?.setHardwareAgc(enabled)
        prefs.edit().putBoolean("hardwareAgc", enabled).apply()
        // Derive gainMode from combined AGC state
        val manual = !_tunerAgcEnabled.value && !enabled
        val mode   = if (manual) RtlSdrDevice.GAIN_MODE_MANUAL else RtlSdrDevice.GAIN_MODE_AGC
        _gainMode.value = mode
        prefs.edit().putInt("gainMode", mode).apply()
    }

    fun setBiasTee(on: Boolean) {
        _biasTee.value = on
        sdrService?.setBiasTee(on)
        prefs.edit().putBoolean("biastee", on).apply()
    }

    fun setNoiseBlanker(enabled: Boolean) {
        _noiseBlankerEnabled.value = enabled
        sdrService?.setNoiseBlanker(enabled)
        prefs.edit().putBoolean("noiseBlanker", enabled).apply()
    }

    fun setNoiseReducer(enabled: Boolean) {
        _noiseReducerEnabled.value = enabled
        sdrService?.setNoiseReducer(enabled)
        prefs.edit().putBoolean("noiseReducer", enabled).apply()
    }

    /**
     * Set the IF filter / protocol bandwidth for narrow modes.
     * Pass 0 to revert to the mode-default bandwidth.
     * @param hz  Desired IF bandwidth in Hz; clamped by DspEngine.
     */
    fun setIfBandwidth(hz: Int) {
        _ifBandwidthHz.value = hz
        sdrService?.setIfBandwidth(hz)
        prefs.edit().putInt("ifBandwidth", hz).apply()
    }

    fun setDirectSampling(mode: Int) {
        _directSampling.value = mode
        sdrService?.setDirectSampling(mode)
        prefs.edit().putInt("directSampling", mode).apply()
    }

    fun setPpm(ppm: Int) {
        _ppm.value = ppm
        sdrService?.setPpm(ppm)
        prefs.edit().putInt("ppm", ppm).apply()
        // Keep the Settings screen's "PPM Correction" SeekBarPreference
        // (key "pref_ppm", stored in default SharedPreferences — separate from
        // "rtlsdr_prefs") in sync, so the Settings slider always reflects the
        // value last chosen on the RF tab and vice-versa.
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(getApplication())
            .edit().putInt("pref_ppm", ppm).apply()
    }

    /**
     * Enable or disable out-of-band frequency access.
     * When true, [setFrequency] skips the normal 500 kHz – 1766 MHz clamp,
     * allowing the R828D / RTL-SDR Blog V4 extended tuning range.
     * The choice is persisted so it survives app restarts.
     */
    fun setAllowOutOfBand(allow: Boolean) {
        _allowOutOfBand.value = allow
        prefs.edit().putBoolean("allowOutOfBand", allow).apply()
    }

    /** True when the currently connected USB source is an RTL-SDR Blog V4. */
    val isV4: Boolean get() = sdrService?.isV4 ?: false

    fun setSquelch(db: Float) {
        _squelch.value = db
        sdrService?.setSquelch(db)
        prefs.edit().putFloat("squelch", db).apply()
    }

    fun setVolume(vol: Float) {
        _volume.value = vol
        sdrService?.setAudioVolume(vol)
        prefs.edit().putFloat("volume", vol).apply()
    }

    fun setFftSize(size: Int) {
        _fftSize.value = size
        // Route through the service so pendingFftSize is updated and the setting
        // survives device reconnects without re-applying it manually each time.
        sdrService?.setFftSize(size) ?: run {
            // Service not yet bound; the size will be applied when the service
            // connects via the observeServiceFlows() → applyStoredSettings() path.
        }
        prefs.edit().putInt("fftSize", size).apply()
    }

    /** Set the FFT decimation factor.  Valid: 1 (off), 2, 4, 8, 16, 32, 64. */
    fun setDecimation(factor: Int) {
        val valid = intArrayOf(1, 2, 4, 8, 16, 32, 64)
        val clamped = if (factor in valid) factor else 1
        _decimation.value = clamped
        sdrService?.setFftDecimation(clamped)
        prefs.edit().putInt("fftDecimation", clamped).apply()
    }

    /**
     * Returns the effective FFT sample rate: the bandwidth visible in the
     * spectrum/waterfall display after decimation.
     * SpectrumView and WaterfallView should be initialised with this value.
     */
    fun effectiveSampleRate(): Int = sampleRate.value / decimation.value.coerceAtLeast(1)

    fun setWaterfallPalette(p: WaterfallView.Companion.Palette) {
        _waterfallPalette.value = p
        prefs.edit().putString("palette", p.name).apply()
    }

    fun setSpectrumTheme(name: String) {
        _spectrumTheme.value = name
        prefs.edit().putString("theme", name).apply()
    }

    // ─── Recording ────────────────────────────────────────────────────────────

    fun startIqRecording(path: String) {
        sdrService?.startRecording(path)
        viewModelScope.launch {
            db.recordingMetaDao().insert(RecordingMeta(
                filePath = path,
                type = "IQ",
                frequencyHz = _centerFreqHz.value,
                sampleRate = _sampleRate.value,
                demodMode = _demodMode.value.name,
                durationMs = 0L,
                fileSizeBytes = 0L
            ))
        }
    }

    fun stopIqRecording() { sdrService?.stopRecording() }

    fun startAudioRecording(path: String) {
        sdrService?.startAudioRecording(path)
        viewModelScope.launch {
            db.recordingMetaDao().insert(RecordingMeta(
                filePath = path,
                type = "AUDIO",
                frequencyHz = _centerFreqHz.value,
                // The audio pipeline always resamples to DEFAULT_AUDIO_RATE (48 000 Hz)
                // before the AudioTrack write and PcmRecorder WAV write.  Using
                // _demodMode.value.audioRateHz here was incorrect — audioRateHz is
                // 48 000 for every mode (it describes the pipeline output, not a
                // per-mode rate), but historically some modes were wrongly declared
                // at 11 025 Hz, producing broken WAV metadata.  Hard-code to
                // DspEngine.DEFAULT_AUDIO_RATE so the stored metadata always matches
                // what PcmRecorder actually writes into the WAV header.
                sampleRate = DspEngine.DEFAULT_AUDIO_RATE,
                demodMode = _demodMode.value.name,
                durationMs = 0L,
                fileSizeBytes = 0L
            ))
        }
    }

    fun stopAudioRecording() { sdrService?.stopAudioRecording() }

    // ─── Memory channels ──────────────────────────────────────────────────────

    fun saveCurrentAsMemory(name: String, group: String = "Default") {
        viewModelScope.launch {
            db.memoryChannelDao().insert(MemoryChannel(
                name = name,
                frequencyHz = _centerFreqHz.value,
                demodMode = _demodMode.value.name,
                sampleRate = _sampleRate.value,
                gain = _gainIndex.value,
                squelch = _squelch.value,
                biasTee = _biasTee.value,
                directSampling = _directSampling.value,
                ppmCorrection = _ppm.value,
                group = group
            ))
        }
    }

    fun loadMemoryChannel(channel: MemoryChannel) {
        setFrequency(channel.frequencyHz)
        setDemodMode(DemodMode.valueOf(channel.demodMode))
        setSampleRate(channel.sampleRate)
        setGain(channel.gain)
        setSquelch(channel.squelch)
        setBiasTee(channel.biasTee)
        setDirectSampling(channel.directSampling)
        setPpm(channel.ppmCorrection)
        viewModelScope.launch {
            db.memoryChannelDao().updateLastUsed(channel.id)
        }
    }

    fun deleteMemoryChannel(channel: MemoryChannel) {
        viewModelScope.launch { db.memoryChannelDao().delete(channel) }
    }

    // ─── Bookmarks ────────────────────────────────────────────────────────────

    fun addBookmark(freqHz: Long, label: String, demodMode: String = "", notes: String = "",
                    color: Int = 0xFF2196F3.toInt(), bookmarkListId: Long? = null) {
        viewModelScope.launch {
            db.bookmarkDao().insert(
                Bookmark(frequencyHz = freqHz, label = label, demodMode = demodMode,
                    notes = notes, color = color, bookmarkListId = bookmarkListId)
            )
        }
    }

    fun updateBookmark(bookmark: Bookmark) {
        viewModelScope.launch { db.bookmarkDao().update(bookmark.copy(updatedAt = System.currentTimeMillis())) }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        viewModelScope.launch { db.bookmarkDao().delete(bookmark) }
    }

    fun toggleBookmarkFavorite(bookmark: Bookmark) {
        viewModelScope.launch { db.bookmarkDao().setFavorite(bookmark.id, !bookmark.favorite) }
    }

    fun moveBookmarksToList(ids: List<Int>, listId: Long?) {
        viewModelScope.launch { db.bookmarkDao().moveToList(ids, listId) }
    }

    fun getBookmarksFiltered(search: String, onlyFavorites: Boolean, listId: Long) =
        db.bookmarkDao().getFiltered(search, onlyFavorites, listId)

    fun importBookmarks(bookmarks: List<Bookmark>) {
        viewModelScope.launch { db.bookmarkDao().insertAll(bookmarks) }
    }

    // ─── Bookmark Lists ────────────────────────────────────────────────────────

    fun addBookmarkList(name: String, notes: String = "", color: Int = 0xFF2196F3.toInt()) {
        viewModelScope.launch { db.bookmarkListDao().insert(BookmarkList(name = name, notes = notes, color = color)) }
    }

    fun updateBookmarkList(list: BookmarkList) {
        viewModelScope.launch { db.bookmarkListDao().update(list) }
    }

    fun deleteBookmarkList(list: BookmarkList, deleteContents: Boolean) {
        viewModelScope.launch {
            if (deleteContents) db.bookmarkDao().deleteByList(list.id)
            else db.bookmarkDao().moveToList(
                db.bookmarkDao().getByList(list.id).first().map { it.id },
                null
            )
            db.bookmarkListDao().delete(list)
        }
    }

    // ─── FFT window ───────────────────────────────────────────────────────────

    fun setFftWindow(w: FftEngine.WindowType) {
        sdrService?.dspEngine?.setFftWindow(w)
    }

    fun setFftSmoothing(alpha: Float) {
        sdrService?.dspEngine?.setFftSmoothing(alpha)
    }

    // ─── Vocoder ──────────────────────────────────────────────────────────────

    /**
     * Rebuild the native dsdcc decoder handle. Forwards to
     * [DspEngine.rebuildVocoderDecoder]. Safe to call when no engine is
     * running (no-op in that case).
     */
    fun rebuildVocoderDecoder() {
        sdrService?.dspEngine?.rebuildVocoderDecoder()
    }

    // ─── RDS data ─────────────────────────────────────────────────────────────

    val rdsData: StateFlow<com.radiosport.ninegradio.dsp.RdsDecoder.RdsData>
        get() = sdrService?.dspEngine?.rdsDecoder?.dataFlow
            ?: MutableStateFlow(com.radiosport.ninegradio.dsp.RdsDecoder.RdsData())

    // ─── Scan entries ─────────────────────────────────────────────────────────

    fun saveScanEntry(entry: com.radiosport.ninegradio.data.ScanEntry) {
        viewModelScope.launch { db.scanEntryDao().insert(entry) }
    }

    fun deleteScanEntry(entry: com.radiosport.ninegradio.data.ScanEntry) {
        viewModelScope.launch { db.scanEntryDao().delete(entry) }
    }

    // ─── Device discovery ─────────────────────────────────────────────────────

    fun getService(): RtlSdrService? = sdrService

    fun detectUsbDevices(): List<UsbDevice> {
        val usbManager = getApplication<Application>().getSystemService(UsbManager::class.java)
        return usbManager.deviceList.values.filter { RtlSdrDevice.isSupported(it) }
    }

    override fun onCleared() {
        super.onCleared()
    }
}
