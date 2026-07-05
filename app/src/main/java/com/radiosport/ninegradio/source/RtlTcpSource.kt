package com.radiosport.ninegradio.source

import android.util.Log
import com.radiosport.ninegradio.debug.DebugBus
import com.radiosport.ninegradio.usb.RtlSdrDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * IQ source that connects to an rtl_tcp server (including rtl_tcp_andro by signalwareltd).
 *
 * The rtl_tcp protocol (original by Hoernchen / librtlsdr):
 *   • On connect the server sends a 12-byte magic header:
 *       "RTL0" (4 bytes) | tuner_type (4 bytes BE) | gain_count (4 bytes BE)
 *   • The client sends 5-byte commands:
 *       command_byte (1) | argument (4 bytes BE uint32)
 *
 * Command codes
 *   0x01  SET_FREQ          — centre frequency (Hz)
 *   0x02  SET_SAMPLE_RATE   — sample rate (Hz)
 *   0x03  SET_GAIN_MODE     — 0 = AGC, 1 = manual
 *   0x04  SET_GAIN          — gain in tenths of dB
 *   0x05  SET_FREQ_CORRECTION — PPM
 *   0x06  SET_IF_STAGE_GAIN (ignored here)
 *   0x07  SET_TEST_MODE     (ignored)
 *   0x08  SET_AGC_MODE      — hardware AGC: 0 = off, 1 = on
 *   0x09  SET_DIRECT_SAMPLING — 0/1/2
 *   0x0A  SET_OFFSET_TUNING  (not used)
 *   0x0B  SET_RTL_CRYSTAL   (not used)
 *   0x0C  SET_TUNER_CRYSTAL (not used)
 *   0x0D  SET_TUNER_GAIN_BY_INDEX — gain by index (rtl_tcp_andro extension)
 *   0x0E  SET_BIAS_TEE      — rtl_tcp_andro / rtl_tcp ≥0.6 extension
 *
 * After the magic handshake the server streams raw unsigned-8 interleaved IQ
 * bytes at the negotiated sample rate — identical to the USB bulk endpoint output.
 */
class RtlTcpSource(
    val host: String,
    val port: Int,
    private val connectTimeoutMs: Int = 3_000
) : IqSource {

    companion object {
        private const val TAG = "RtlTcpSource"

        // Magic bytes sent by rtl_tcp on connect
        private const val MAGIC = "RTL0"
        private const val MAGIC_SIZE = 12   // 4 magic + 4 tuner type + 4 gain count

        // Command bytes
        private const val CMD_SET_FREQ            = 0x01
        private const val CMD_SET_SAMPLE_RATE     = 0x02
        private const val CMD_SET_GAIN_MODE       = 0x03
        private const val CMD_SET_GAIN            = 0x04
        private const val CMD_SET_FREQ_CORRECTION = 0x05
        private const val CMD_SET_AGC_MODE        = 0x08
        private const val CMD_SET_DIRECT_SAMPLING = 0x09
        private const val CMD_SET_GAIN_BY_INDEX   = 0x0D
        private const val CMD_SET_BIAS_TEE        = 0x0E

        /** Stream read buffer — 16 384 bytes = 8 192 IQ samples per chunk. */
        private const val READ_BUF_SIZE = 16_384

        /** Tuner type values from rtl_tcp magic header. */
        private const val TUNER_UNKNOWN = 0
        private const val TUNER_E4000   = 1
        private const val TUNER_FC0012  = 2
        private const val TUNER_FC0013  = 3
        private const val TUNER_FC2580  = 4
        private const val TUNER_R820T   = 5
        private const val TUNER_R828D   = 6

        /**
         * Gain tables per tuner type (dB × 10).
         * Matches librtlsdr gain tables; rtl_tcp_andro sends gain_count in the magic header.
         */
        private val GAIN_TABLE_R820T = intArrayOf(
            0, 9, 14, 27, 37, 77, 87, 125, 144, 157,
            166, 197, 207, 229, 254, 280, 297, 328,
            338, 364, 372, 386, 402, 421, 434, 439,
            445, 480, 496
        )
        private val GAIN_TABLE_E4000 = intArrayOf(
            -10, 15, 40, 65, 90, 115, 140, 165, 190,
            215, 240, 290, 340, 420
        )
        private val GAIN_TABLE_GENERIC = intArrayOf(
            0, 10, 20, 30, 40, 50, 60, 70, 80, 90,
            100, 110, 120, 130, 140, 150
        )
    }

    // ── State ─────────────────────────────────────────────────────────────────

    @Volatile private var socket: Socket? = null
    @Volatile private var outputStream: OutputStream? = null
    @Volatile private var inputStream: InputStream? = null
    @Volatile private var _isOpen = false
    @Volatile private var _isStreaming = false

    // Tuner info from magic header
    @Volatile private var tunerType: Int = TUNER_UNKNOWN
    @Volatile private var serverGainCount: Int = 0

    // Locally-tracked state (mirrored to statusFlow)
    @Volatile private var _centerFreqHz: Long = 100_000_000L
    @Volatile private var _sampleRate: Int  = 2_048_000
    @Volatile private var _gainMode: Int    = 0   // 0=AGC
    @Volatile private var _gainIndex: Int   = 0
    @Volatile private var _tunerAgc: Boolean = true
    @Volatile private var _hardwareAgc: Boolean = false
    @Volatile private var _biasTee: Boolean = false
    @Volatile private var _directSampling: Int = 0
    @Volatile private var _ppm: Int = 0

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var streamingJob: Job? = null

    private val _iqFlow = MutableSharedFlow<ByteArray>(
        replay = 1,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val iqFlow: SharedFlow<ByteArray> = _iqFlow.asSharedFlow()
    override val iqSubscriberCount: Int get() = _iqFlow.subscriptionCount.value

    private val _statusFlow = MutableStateFlow(IqSource.SourceStatus())
    override val statusFlow: StateFlow<IqSource.SourceStatus> = _statusFlow.asStateFlow()

    // ── Gain table resolved from tuner type ──────────────────────────────────

    private val gainTable: IntArray get() = when (tunerType) {
        TUNER_E4000 -> GAIN_TABLE_E4000
        TUNER_R820T, TUNER_R828D -> GAIN_TABLE_R820T
        else -> GAIN_TABLE_GENERIC
    }

    // ── IqSource : open / close ───────────────────────────────────────────────

    override fun open(): Boolean {
        return try {
            Log.i(TAG, "Connecting to rtl_tcp at $host:$port")
            DebugBus.setStatus(DebugBus.STAGE_USB_OPEN, DebugBus.StageStatus.OK, "Connecting to $host:$port…")

            val s = Socket()
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            s.tcpNoDelay = true
            s.soTimeout = 0   // blocking reads in streaming coroutine

            val ins  = s.getInputStream()
            val outs = s.getOutputStream()

            // Read and parse the 12-byte magic header
            val magic = ByteArray(MAGIC_SIZE)
            var read = 0
            val deadline = System.currentTimeMillis() + 5_000L
            while (read < MAGIC_SIZE && System.currentTimeMillis() < deadline) {
                val n = ins.read(magic, read, MAGIC_SIZE - read)
                if (n < 0) {
                    Log.e(TAG, "Connection closed before magic received")
                    s.close()
                    return false
                }
                read += n
            }
            if (read < MAGIC_SIZE) {
                Log.e(TAG, "Timeout waiting for magic header")
                s.close()
                return false
            }

            val magicStr = String(magic, 0, 4, Charsets.US_ASCII)
            if (magicStr != MAGIC) {
                Log.e(TAG, "Bad magic: $magicStr (expected RTL0)")
                s.close()
                return false
            }

            val buf = ByteBuffer.wrap(magic).order(ByteOrder.BIG_ENDIAN)
            buf.position(4)
            tunerType       = buf.int
            serverGainCount = buf.int

            Log.i(TAG, "Connected: tunerType=$tunerType gainCount=$serverGainCount")
            DebugBus.setStatus(DebugBus.STAGE_USB_OPEN, DebugBus.StageStatus.OK,
                "Connected to $host:$port  tuner=$tunerType gains=$serverGainCount")

            socket       = s
            outputStream = outs
            inputStream  = ins
            _isOpen      = true

            _statusFlow.value = _statusFlow.value.copy(
                connected       = true,
                centerFreqHz    = _centerFreqHz,
                sampleRate      = _sampleRate,
                gainDb10        = gainTable.getOrElse(_gainIndex) { 0 },
                gainMode        = _gainMode,
                tunerAgcEnabled = _tunerAgc,
                hardwareAgcEnabled = _hardwareAgc,
                biasTee         = _biasTee,
                directSampling  = _directSampling,
                ppmCorrection   = _ppm
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "open() failed", e)
            DebugBus.setError(DebugBus.STAGE_USB_OPEN, "TCP connect failed: ${e.message}")
            _statusFlow.value = _statusFlow.value.copy(connected = false, error = e.message)
            false
        }
    }

    override fun startStreaming(bufferSize: Int) {
        if (!_isOpen) { Log.e(TAG, "startStreaming() called on closed source"); return }
        if (_isStreaming) { Log.w(TAG, "startStreaming() called while already streaming"); return }
        _isStreaming = true

        DebugBus.setStatus(DebugBus.STAGE_IQ_STREAM, DebugBus.StageStatus.OK,
            "TCP stream started  $host:$port  ${_sampleRate/1000} kS/s")

        streamingJob = scope.launch(Dispatchers.IO) {
            val ins  = inputStream ?: return@launch
            // Reusable read buffer — avoids per-chunk allocation
            val readBuf = ByteArray(READ_BUF_SIZE)
            try {
                while (isActive && _isStreaming) {
                    // Fill the buffer as fully as possible before emitting,
                    // matching the bulk-USB chunk size the DspEngine expects.
                    var filled = 0
                    while (filled < readBuf.size) {
                        val n = ins.read(readBuf, filled, readBuf.size - filled)
                        if (n < 0) {
                            Log.w(TAG, "TCP stream EOF")
                            _statusFlow.value = _statusFlow.value.copy(
                                streamRestartRequired = true,
                                error = "TCP stream closed by server"
                            )
                            return@launch
                        }
                        filled += n
                    }
                    // Emit a copy — the pool isn't needed for TCP (no DMA constraints)
                    _iqFlow.tryEmit(readBuf.copyOf())
                }
            } catch (e: SocketException) {
                if (_isStreaming) {  // not a deliberate close
                    Log.e(TAG, "TCP socket error during streaming", e)
                    DebugBus.setError(DebugBus.STAGE_IQ_STREAM, "TCP error: ${e.message}")
                    _statusFlow.value = _statusFlow.value.copy(
                        streamRestartRequired = true,
                        error = "TCP error: ${e.message}"
                    )
                }
            } catch (e: Exception) {
                if (_isStreaming) {
                    Log.e(TAG, "Stream error", e)
                    DebugBus.setError(DebugBus.STAGE_IQ_STREAM, "Stream error: ${e.message}")
                    _statusFlow.value = _statusFlow.value.copy(
                        streamRestartRequired = true,
                        error = e.message
                    )
                }
            }
        }
    }

    override fun stopStreaming() {
        _isStreaming = false
        streamingJob?.cancel()
        streamingJob = null
        DebugBus.setStatus(DebugBus.STAGE_IQ_STREAM, DebugBus.StageStatus.IDLE, "TCP stream stopped")
    }

    override fun close() {
        stopStreaming()
        _isOpen = false
        try { socket?.close() } catch (_: Exception) {}
        socket       = null
        outputStream = null
        inputStream  = null
        scope.cancel()
        _statusFlow.value = _statusFlow.value.copy(connected = false)
        Log.i(TAG, "RtlTcpSource closed")
    }

    // ── Tuning commands ───────────────────────────────────────────────────────
    //
    // All hardware-command methods update local state immediately (so callers
    // get consistent getCenterFrequency() / getSampleRate() values at once)
    // and then dispatch the actual socket write to the IO scope so they are
    // never executed on the main thread.  Concurrent writes are serialised by
    // the synchronized(outs) block inside sendCommand().

    override fun setCenterFrequency(freqHz: Long): Boolean {
        _centerFreqHz = freqHz
        _statusFlow.value = _statusFlow.value.copy(centerFreqHz = freqHz)
        // Dispatch socket write off the calling thread (may be main thread).
        scope.launch(Dispatchers.IO) { sendCommand(CMD_SET_FREQ, freqHz) }
        return true
    }

    override fun getCenterFrequency(): Long = _centerFreqHz

    override fun setSampleRate(rateHz: Int): Boolean {
        _sampleRate = rateHz
        _statusFlow.value = _statusFlow.value.copy(sampleRate = rateHz)
        scope.launch(Dispatchers.IO) { sendCommand(CMD_SET_SAMPLE_RATE, rateHz.toLong()) }
        return true
    }

    override fun getSampleRate(): Int = _sampleRate

    override fun setGainMode(mode: Int) {
        // mode 0 = AGC, mode 1 = manual  (matches RtlSdrDevice constants)
        _gainMode = mode
        _statusFlow.value = _statusFlow.value.copy(gainMode = mode)
        val arg = if (mode == RtlSdrDevice.GAIN_MODE_MANUAL) 1L else 0L
        scope.launch(Dispatchers.IO) { sendCommand(CMD_SET_GAIN_MODE, arg) }
    }

    override fun setGain(gainIndex: Int) {
        val idx = gainIndex.coerceIn(0, gainTable.lastIndex)
        _gainIndex = idx
        _statusFlow.value = _statusFlow.value.copy(gainDb10 = gainTable.getOrElse(idx) { 0 })
        scope.launch(Dispatchers.IO) {
            // Prefer the index-based command (rtl_tcp_andro extension 0x0D) so the
            // server can apply the correct hardware step directly.
            if (!sendCommand(CMD_SET_GAIN_BY_INDEX, idx.toLong())) {
                // Fallback: send gain in tenths-of-dB (original rtl_tcp command 0x04)
                sendCommand(CMD_SET_GAIN, gainTable.getOrElse(idx) { 0 }.toLong())
            }
        }
    }

    override fun getGainIndex(): Int = _gainIndex
    override fun getGainDb(): Float  = gainTable.getOrElse(_gainIndex) { 0 } / 10f
    override fun getGainCount(): Int = if (serverGainCount > 0) serverGainCount else gainTable.size

    override fun setTunerAgcEnabled(enable: Boolean) {
        _tunerAgc = enable
        _statusFlow.value = _statusFlow.value.copy(tunerAgcEnabled = enable)
        // Tuner AGC in rtl_tcp is controlled via gain_mode: 0 = AGC, 1 = manual
        val arg = if (enable) 0L else 1L
        scope.launch(Dispatchers.IO) { sendCommand(CMD_SET_GAIN_MODE, arg) }
    }

    override fun setHardwareAgcEnabled(enable: Boolean) {
        _hardwareAgc = enable
        _statusFlow.value = _statusFlow.value.copy(hardwareAgcEnabled = enable)
        val arg = if (enable) 1L else 0L
        scope.launch(Dispatchers.IO) { sendCommand(CMD_SET_AGC_MODE, arg) }
    }

    override fun setBiasTee(enable: Boolean) {
        _biasTee = enable
        _statusFlow.value = _statusFlow.value.copy(biasTee = enable)
        val arg = if (enable) 1L else 0L
        scope.launch(Dispatchers.IO) { sendCommand(CMD_SET_BIAS_TEE, arg) }
    }

    override fun setDirectSampling(mode: Int) {
        _directSampling = mode
        _statusFlow.value = _statusFlow.value.copy(directSampling = mode)
        scope.launch(Dispatchers.IO) { sendCommand(CMD_SET_DIRECT_SAMPLING, mode.toLong()) }
    }

    override fun getDirectSampling(): Int = _directSampling

    override fun setPpmCorrection(ppm: Int) {
        _ppm = ppm
        _statusFlow.value = _statusFlow.value.copy(ppmCorrection = ppm)
        scope.launch(Dispatchers.IO) { sendCommand(CMD_SET_FREQ_CORRECTION, ppm.toLong()) }
    }

    override fun getPpmCorrection(): Int = _ppm

    // ── Informational ─────────────────────────────────────────────────────────

    override fun getSourceName(): String = "rtl_tcp @ $host:$port"
    override fun isOpen(): Boolean       = _isOpen

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Send a 5-byte rtl_tcp command packet.
     *
     * The rtl_tcp protocol uses an unsigned 32-bit big-endian argument field.
     * Accepting [Long] and packing with [ushr] avoids any sign-extension issues
     * for the full 0–4 GHz tuning range supported by rtl_tcp.
     *
     * Thread-safe: synchronised on [outputStream].
     * Must be called from an IO coroutine — never from the main thread.
     *
     * @return false if the socket is unavailable or the write fails.
     */
    private fun sendCommand(cmd: Int, arg: Long): Boolean {
        val outs = outputStream ?: return false
        return try {
            val buf = ByteArray(5)
            buf[0] = cmd.toByte()
            buf[1] = (arg ushr 24 and 0xFF).toByte()
            buf[2] = (arg ushr 16 and 0xFF).toByte()
            buf[3] = (arg ushr  8 and 0xFF).toByte()
            buf[4] = (arg        and 0xFF).toByte()
            synchronized(outs) { outs.write(buf); outs.flush() }
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendCommand($cmd, $arg) failed: ${e.message}")
            false
        }
    }
}
