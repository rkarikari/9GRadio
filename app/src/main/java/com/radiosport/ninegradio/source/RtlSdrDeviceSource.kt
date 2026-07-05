package com.radiosport.ninegradio.source

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.radiosport.ninegradio.usb.RtlSdrDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [IqSource] adapter that wraps the existing [RtlSdrDevice] (direct USB).
 *
 * Keeps [RtlSdrDevice] entirely unchanged while allowing [DspEngine] and
 * [com.radiosport.ninegradio.usb.RtlSdrService] to work through the unified
 * [IqSource] interface.
 */
class RtlSdrDeviceSource(
    usbManager: UsbManager,
    val usbDevice: UsbDevice   // public so the service can pass it to RtlSdrDevice.getDeviceName()
) : IqSource {

    val device = RtlSdrDevice(usbManager, usbDevice)

    @Volatile private var _isOpen = false

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Expose a SourceStatus StateFlow that mirrors RtlSdrDevice.DeviceStatus.
    private val _statusFlow = MutableStateFlow(IqSource.SourceStatus())
    override val statusFlow: StateFlow<IqSource.SourceStatus> = _statusFlow.asStateFlow()

    init {
        // Keep _statusFlow in sync with the underlying DeviceStatus.
        scope.launch {
            device.statusFlow.collect { ds ->
                _statusFlow.value = IqSource.SourceStatus(
                    connected             = ds.connected,
                    centerFreqHz          = ds.centerFreqHz,
                    sampleRate            = ds.sampleRate,
                    gainDb10              = ds.gainDb10,
                    gainMode              = ds.gainMode,
                    tunerAgcEnabled       = ds.tunerAgcEnabled,
                    hardwareAgcEnabled    = ds.hardwareAgcEnabled,
                    biasTee               = ds.biasTee,
                    directSampling        = ds.directSampling,
                    ppmCorrection         = ds.ppmCorrection,
                    signalStrengthDb      = ds.signalStrengthDb,
                    overload              = ds.overload,
                    error                 = ds.error,
                    streamRestartRequired = ds.streamRestartRequired
                )
            }
        }
    }

    // ── IqSource: flow proxies ─────────────────────────────────────────────────

    override val iqFlow: SharedFlow<ByteArray> get() = device.iqFlow
    override val iqSubscriberCount: Int        get() = device.iqSubscriberCount

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun open(): Boolean {
        val ok = device.open()
        if (ok) _isOpen = true
        return ok
    }
    override fun startStreaming(bufferSize: Int) = device.startStreaming(bufferSize)
    override fun stopStreaming()                 = device.stopStreaming()
    override fun close() {
        _isOpen = false
        scope.cancel()
        device.close()
    }

    // ── Tuning ─────────────────────────────────────────────────────────────────

    override fun setCenterFrequency(freqHz: Long): Boolean = device.setCenterFrequency(freqHz)
    override fun getCenterFrequency(): Long                 = device.getCenterFrequency()

    override fun setSampleRate(rateHz: Int): Boolean = device.setSampleRate(rateHz)
    override fun getSampleRate(): Int                 = device.getSampleRate()

    override fun setGainMode(mode: Int)            = device.setGainMode(mode)
    override fun setGain(gainIndex: Int)           = device.setGain(gainIndex)
    override fun getGainIndex(): Int               = device.getGainIndex()
    override fun getGainDb(): Float                = device.getGainDb()
    override fun getGainCount(): Int               = device.getGainCount()

    override fun setTunerAgcEnabled(enable: Boolean)    = device.setTunerAgcEnabled(enable)
    override fun setHardwareAgcEnabled(enable: Boolean) = device.setHardwareAgcEnabled(enable)
    override fun setBiasTee(enable: Boolean)            = device.setBiasTee(enable)
    override fun setDirectSampling(mode: Int)           = device.setDirectSampling(mode)
    override fun getDirectSampling(): Int               = device.getDirectSampling()
    override fun setPpmCorrection(ppm: Int)             = device.setPpmCorrection(ppm)
    override fun getPpmCorrection(): Int                = device.getPpmCorrection()

    // ── Informational ──────────────────────────────────────────────────────────

    override fun getSourceName(): String = RtlSdrDevice.getDeviceName(usbDevice)
    override fun isOpen(): Boolean       = _isOpen
}
