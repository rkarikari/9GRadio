package com.radiosport.ninegradio.source

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable

/**
 * Common abstraction over IQ sample sources.
 *
 * Both [com.radiosport.ninegradio.usb.RtlSdrDevice] (direct USB) and
 * [RtlTcpSource] (network via rtl_tcp / rtl_tcp_andro) implement this interface.
 *
 * The DspEngine and RtlSdrService work exclusively through IqSource so that
 * the rest of the pipeline is entirely source-agnostic.
 */
interface IqSource : Closeable {

    // ── Raw IQ stream ─────────────────────────────────────────────────────────

    /** Raw interleaved unsigned-8 IQ bytes.  Same format as RTL-SDR USB output. */
    val iqFlow: SharedFlow<ByteArray>

    /** Current subscriber count on [iqFlow]. */
    val iqSubscriberCount: Int

    /** Observable device/connection status. */
    val statusFlow: StateFlow<SourceStatus>

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Open/connect to the source.
     * @return true on success, false if the connection could not be established.
     */
    fun open(): Boolean

    /** Start emitting IQ data on [iqFlow]. */
    fun startStreaming(bufferSize: Int = com.radiosport.ninegradio.dsp.DspEngine.USB_STREAMING_BUF)

    /** Stop streaming; the source remains open (tunable). */
    fun stopStreaming()

    /** Stop streaming and close/disconnect the source. */
    override fun close()

    // ── Tuning & hardware control ─────────────────────────────────────────────

    fun setCenterFrequency(freqHz: Long): Boolean
    fun getCenterFrequency(): Long

    fun setSampleRate(rateHz: Int): Boolean
    fun getSampleRate(): Int

    /** Set gain mode: 0 = AGC, 1 = manual. */
    fun setGainMode(mode: Int)
    /** Set manual gain by index into the gain table. */
    fun setGain(gainIndex: Int)
    fun getGainIndex(): Int
    fun getGainDb(): Float
    fun getGainCount(): Int

    fun setTunerAgcEnabled(enable: Boolean)
    fun setHardwareAgcEnabled(enable: Boolean)

    /** Bias-tee power on antenna port (not all sources support this). */
    fun setBiasTee(enable: Boolean)

    /** Direct-sampling mode: 0 = off, 1 = I, 2 = Q. */
    fun setDirectSampling(mode: Int)
    fun getDirectSampling(): Int

    fun setPpmCorrection(ppm: Int)
    fun getPpmCorrection(): Int

    // ── Informational ─────────────────────────────────────────────────────────

    /** Human-readable source name shown in the notification and status bar. */
    fun getSourceName(): String

    /** True once [open] has succeeded and [close] has not been called. */
    fun isOpen(): Boolean

    // ── Status data class ─────────────────────────────────────────────────────

    data class SourceStatus(
        val connected: Boolean = false,
        val centerFreqHz: Long = 0L,
        val sampleRate: Int = 0,
        val gainDb10: Int = 0,
        val gainMode: Int = 0,
        val tunerAgcEnabled: Boolean = true,
        val hardwareAgcEnabled: Boolean = true,
        val biasTee: Boolean = false,
        val directSampling: Int = 0,
        val ppmCorrection: Int = 0,
        val signalStrengthDb: Float = -120f,
        val overload: Boolean = false,
        val error: String? = null,
        val streamRestartRequired: Boolean = false
    )
}
