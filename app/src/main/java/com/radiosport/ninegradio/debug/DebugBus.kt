package com.radiosport.ninegradio.debug

import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight singleton telemetry bus for the RTL-SDR → Audio/Waterfall pipeline.
 *
 * Every stage in the chain calls into DebugBus via non-blocking atomic writes.
 * DebugPanelActivity polls this bus every 500 ms to build the live debug view.
 *
 * Pipeline order (for display):
 *   USB_OPEN → DEVICE_INIT → IQ_STREAM → FFT_ENGINE → DSP_PROCESS
 *   → DEMODULATOR → AUDIO_RESAMPLE → AUDIO_TRACK → SPECTRUM_VIEW → WATERFALL_VIEW
 */
object DebugBus {

    // ── Stage IDs ────────────────────────────────────────────────────────────
    const val STAGE_USB_OPEN        = 0
    const val STAGE_DEVICE_INIT     = 1
    const val STAGE_IQ_STREAM       = 2
    const val STAGE_FFT_ENGINE      = 3
    const val STAGE_DSP_PROCESS     = 4
    const val STAGE_DEMODULATOR     = 5
    const val STAGE_AUDIO_RESAMPLE  = 6
    const val STAGE_AUDIO_TRACK     = 7
    const val STAGE_SPECTRUM_VIEW   = 8
    const val STAGE_WATERFALL_VIEW  = 9

    const val NUM_STAGES = 10

    // ── Global metric keys (use with setGlobal / getGlobals) ─────────────────
    /** AudioManager.STREAM_MUSIC volume as "N/MAX" */
    const val KEY_SYS_MEDIA_VOL    = "SYS_MEDIA_VOL"
    /** Current audio output routing: "Speaker", "Wired Headset", "Bluetooth A2DP" */
    const val KEY_AUDIO_OUTPUT     = "AUDIO_OUTPUT"
    /** Audio focus state: "GAIN", "LOSS", "LOSS_TRANSIENT", "NONE" */
    const val KEY_AUDIO_FOCUS      = "AUDIO_FOCUS"
    /** Whether Bluetooth A2DP is reported as connected */
    const val KEY_BT_A2DP_CONNECTED = "BT_A2DP"
    /** spectrumFlow subscriber count */
    const val KEY_SPECTRUM_SUBS    = "SPECTRUM_SUBS"
    /** Protocol Filter (IF bandwidth): shown in Debug Panel in real time as slider moves */
    const val KEY_PROTOCOL_FILTER  = "PROTOCOL_FILTER"

    // ── Stage extra keys (use with setExtra / getExtras) ─────────────────────
    // AudioTrack stage
    const val EXTRA_AT_INIT        = "AT_INIT"    // "STATE_INITIALIZED" or "FAIL (state=N)"
    const val EXTRA_AT_PLAY        = "AT_PLAY"    // "PLAYING" | "PAUSED" | "STOPPED"
    const val EXTRA_AT_WRITE_FAIL  = "AT_WRITE_FAIL" // cumulative count of write() errors
    const val EXTRA_AT_LAST_WRITE  = "AT_LAST_WRITE" // "OK (N samples)" or "FAIL: -N"
    const val EXTRA_AT_BUF_SIZE    = "AT_BUF_SIZE"   // allocated buffer in bytes
    // SpectrumView stage
    const val EXTRA_SPEC_VIEW_WH   = "VIEW_WH"    // "WxH px"
    const val EXTRA_SPEC_DB_RANGE  = "DB_RANGE"   // "min..max dBFS"
    const val EXTRA_SPEC_DATA_RANGE = "DATA_RANGE" // "min..max dBFS (last block)"
    // WaterfallView stage
    const val EXTRA_WF_VIEW_WH     = "VIEW_WH"    // "WxH px"
    const val EXTRA_WF_BITMAP_WH   = "BITMAP_WH"  // "WxH px" or "NOT_CREATED"

    // IQ Stream stage — bulk-transfer performance extras
    const val EXTRA_IQ_XFER_AVG    = "XFER_AVG"    // average bulkTransfer() duration (ms)
    const val EXTRA_IQ_XFER_MAX    = "XFER_MAX"    // worst-case bulkTransfer() duration (ms)
    const val EXTRA_IQ_JITTER      = "JITTER"      // stddev of inter-arrival time (ms)
    const val EXTRA_IQ_DROPS       = "USB_DROPS"   // cumulative bulkTransfer() error/zero returns
    const val EXTRA_IQ_SHORT_READS = "SHORT_READS" // cumulative short (partial-buffer) reads
    const val EXTRA_IQ_DROP_RATE   = "DROP_RATE"   // (drops+short)/total reads, as %
    const val EXTRA_IQ_RATING      = "PERF_RATING" // EXCELLENT | GOOD | FAIR | POOR
    // IQ Stream stage — buffer pool health extras
    const val EXTRA_IQ_POOL_SIZE   = "POOL_SIZE"   // total pool slot count (NUM_POOL_BUFS)
    const val EXTRA_IQ_POOL_IDX    = "POOL_IDX"    // producer's current slot (0-based)
    const val EXTRA_IQ_FLOW_DEPTH  = "FLOW_DEPTH"  // replay + extraBufferCapacity of _iqFlow
    const val EXTRA_IQ_IN_FLIGHT   = "IN_FLIGHT"   // current _iqFlow subscriber count (consumers)
    const val EXTRA_IQ_FLOW_DROPS  = "FLOW_DROPS"  // IQ blocks dropped by SharedFlow DROP_OLDEST (DSP too slow)

    // Demodulator stage — APRS frame diagnostics
    // Full 5-stage decode funnel: proves which layer is failing.
    const val EXTRA_APRS_SAMPLES_IN  = "aprs_samples"   // audio samples fed to decoder — 0 means decoder not called
    const val EXTRA_APRS_FLAG_SYNCS  = "aprs_flags"     // AX.25 0x7E flags found in bit-stream
    const val EXTRA_APRS_SIZE_REJ    = "aprs_size_rej"  // frames rejected as too short (noise)
    const val EXTRA_APRS_RCVD        = "aprs_rcvd"      // decode attempts (passed size gate)
    const val EXTRA_APRS_FCS_FAIL    = "aprs_fcs_fail"  // failed CRC check
    const val EXTRA_APRS_AX25_FAIL   = "aprs_ax25_fail" // passed CRC but failed AX.25 parse
    const val EXTRA_APRS_DECODED     = "aprs_decoded"   // fully decoded → AprsPacket emitted

    // Demodulator stage — Digital Voice (DMR/P25/NXDN/D-STAR/YSF/M17) funnel.
    // Mirrors the APRS funnel above: each counter proves a different layer is
    // alive, so a zero (or stuck) value at any stage points straight at the
    // bug layer instead of requiring a guess:
    //   samples=0        → feed() never called (mode/wiring problem)
    //   symbols=0        → discriminator audio arriving but TED/slicer never
    //                       producing symbols (gain/AGC/sps problem)
    //   sync_best        → current best (min-error) correlation against any
    //                       sync word for the active protocol, "err/threshold".
    //                       Hovering at ~half the sync length (chance level)
    //                       indicates a symbol-mapping/polarity bug, not noise.
    //   sync_hits=0      → symbols flowing but sync word never matches within
    //                       tolerance (clock/mapping/threshold problem)
    //   native_avail     → whether libdsd_neo.so loaded (false = JNI/.so problem)
    //   vocoder_ready    → whether the downloaded AMBE plugin is loaded
    //   frames_total     → sync hits that were actually handed to the native
    //                       decoder (should track sync_hits 1:1)
    //   frames_pcm_empty → native decode returned ZERO pcm samples (frame
    //                       unpacking/vocoder call itself failing)
    //   frames_pcm_silent→ native decode returned pcm samples but their RMS is
    //                       ~0 (vocoder ran but produced silence — e.g. AMBE
    //                       params all-zero, gain=0, or comfort-noise path)
    //   frames_pcm_audible→ pcm present with real signal — if this is >0 and
    //                       there is STILL no sound, the bug is downstream of
    //                       DigitalVoiceDecoder (DspEngine upsample/AudioTrack)
    const val EXTRA_DV_SAMPLES_IN       = "dv_samples"
    const val EXTRA_DV_FEED_CALLS       = "dv_feed_calls"
    const val EXTRA_DV_FEED_RATE        = "dv_feed_rate"
    const val EXTRA_DV_FEED_BLOCK_SIZE  = "dv_feed_block_size"
    const val EXTRA_DV_SYMBOLS          = "dv_symbols"
    const val EXTRA_DV_SYNC_BEST        = "dv_sync_best"
    const val EXTRA_DV_SYNC_HITS        = "dv_sync_hits"
    const val EXTRA_DV_NATIVE_AVAIL     = "dv_native_avail"
    const val EXTRA_DV_VOCODER_READY    = "dv_vocoder_ready"
    const val EXTRA_DV_FRAMES_TOTAL     = "dv_frames_total"
    const val EXTRA_DV_FRAMES_PCM_EMPTY = "dv_frames_pcm_empty"
    const val EXTRA_DV_FRAMES_PCM_SILENT  = "dv_frames_pcm_silent"
    const val EXTRA_DV_FRAMES_PCM_AUDIBLE = "dv_frames_pcm_audible"
    const val EXTRA_DV_LAST_PCM_RMS     = "dv_last_pcm_rms"
    const val EXTRA_DV_AGC_ENV          = "dv_agc_env"
    const val EXTRA_DV_RSSI             = "dv_rssi"
    const val EXTRA_DV_SNR              = "dv_snr"

    // Vocoder-level diagnostics (mbelib-neo path; DMR TDMA slot lock + per-frame
    // TONE/ERASURE/REPEAT/MUTE flags). Added to chase down a DMR-only audible
    // "beat" that survives clean sync/RSSI/SNR: these numbers show directly
    // whether the vocoder's tone-frame path or slot handling is misfiring,
    // instead of inferring it from the decoded audio.
    //   dv_active_slot   — which DMR slot is currently locked: "0 (none)" / "1" / "2"
    //   dv_vocoder_flags — "T1=n E1=n R1=n M1=n | T2=n E2=n R2=n M2=n" cumulative
    //                      Tone/Erasure/Repeat/Mute counts per slot since the
    //                      decoder handle was created. If T1/T2 climbs in step
    //                      with an audible beat, ambe3600x2400.c's tone-frame
    //                      synthesis is the cause.
    //   dv_frame_errors  — last frame's corrected error counts per slot,
    //                      "C0=n/Tot=n" — nonzero-but-not-muted values here
    //                      show marginal decodes that RSSI/SNR alone won't reveal.
    const val EXTRA_DV_ACTIVE_SLOT      = "dv_active_slot"
    const val EXTRA_DV_VOCODER_FLAGS    = "dv_vocoder_flags"
    const val EXTRA_DV_FRAME_ERRORS     = "dv_frame_errors"

    enum class StageStatus { IDLE, OK, WARN, ERROR }

    data class StageSnapshot(
        val name: String,
        val status: StageStatus,
        val detail: String,          // one-line summary
        val counter: Long,           // cumulative event counter
        val ratePerSec: Float,       // events/second over the last window
        val lastEventMs: Long,       // System.currentTimeMillis() of last event
        val errorMsg: String,        // last error string (empty if none)
        val extras: Map<String, String> = emptyMap()  // per-stage key-value diagnostics
    )

    // ── Internal per-stage state ─────────────────────────────────────────────

    private val stageNames = arrayOf(
        "USB Open", "Device Init", "IQ Stream", "FFT Engine",
        "DSP Process", "Demodulator", "Audio Resample", "AudioTrack",
        "Spectrum View", "Waterfall View"
    )

    /**
     * Stages that are initialised once and then stay OK permanently.
     * They should not be demoted to WARN/STALE just because they stop ticking —
     * USB Open and Device Init fire exactly once during device setup and never
     * produce periodic events again.
     */
    private val staleExemptStages = setOf(STAGE_USB_OPEN, STAGE_DEVICE_INIT)

    private val statuses    = Array(NUM_STAGES) { StageStatus.IDLE }
    private val details     = Array(NUM_STAGES) { "" }
    private val counters    = Array(NUM_STAGES) { AtomicLong(0) }
    private val lastEventMs = Array(NUM_STAGES) { AtomicLong(0) }
    private val errors      = Array(NUM_STAGES) { "" }

    // Per-stage extras (ordered for deterministic display)
    private val extras = Array(NUM_STAGES) { LinkedHashMap<String, String>() }

    // Global cross-stage metrics (audio focus, output routing, etc.)
    private val globalMetrics = LinkedHashMap<String, String>()

    // Rate computation: bucket counts over a 2-second sliding window
    private const val RATE_WINDOW_MS = 2000L
    private val rateCount   = Array(NUM_STAGES) { AtomicLong(0) }
    private val rateWindowStart = Array(NUM_STAGES) { AtomicLong(System.currentTimeMillis()) }
    private val lastRatePerSec  = FloatArray(NUM_STAGES) { 0f }

    // ── IQ → Audio decimation chain snapshot ─────────────────────────────────
    //
    // Updated by DspEngine whenever the decimation chain is (re)built.
    // Provides the four rates the user sees in the Debug Panel:
    //   Source (device rate) → Quadrature IF (ComplexDecimator output)
    //     → Rational Resampler output → Sink (AudioTrack rate)
    //
    // resampleRatio is the reduced p/q of the PolyphaseResampler step; when
    // ifRate == sinkRate the resampler is bypassed and ratio is "1/1 (direct)".

    data class ChainSnapshot(
        val sourceHz:      Int,      // RTL-SDR device sample rate
        val ifHz:          Int,      // ComplexDecimator output (quadrature IF)
        val decimFactor:   Int,      // sourceHz / ifHz, truncated to Int — for
                                      // narrow modes (always integer) this is exact;
                                      // for WFM with a rational ratio (see decimP/decimQ)
                                      // this is the floor and is informational only.
        val sinkHz:        Int,      // AudioTrack sample rate
        val resampleP:     Int,      // rational resampler numerator   (p/q)
        val resampleQ:     Int,      // rational resampler denominator
        val direct:        Boolean,  // true when ifHz == sinkHz (no resampler)
        val demodMode:     String,   // "NFM", "WFM", "AM", etc.
        val wfmIfHz:       Int,      // WFM IF rate (0 when not in WFM mode)
        val timestampMs:   Long,     // System.currentTimeMillis() when set
        val decimP:        Int = 0,  // pre-demod decimation ratio numerator   (sourceHz side, GCD-reduced)
        val decimQ:        Int = 0,  // pre-demod decimation ratio denominator (ifHz side, GCD-reduced)
                                      // For WFM, sourceHz:ifHz may be a RATIONAL ratio
                                      // (PolyphaseResampler), e.g. 272000:200000 = 34:25.
                                      // decimP/decimQ == 0 means "use decimFactor" (integer chains).
        val protocolFilterHz: Int = 0,   // ifBandwidthHz in DspEngine: user-set IF filter width (0 = auto / mode default)
        val protocolFilterAuto: Boolean = true  // true when using mode-default bandwidth (slider at "Auto")
    ) {
        /** Human-readable one-line summary of the chain. */
        fun summary(): String {
            val decimStr = if (decimP > 0 && decimQ > 0 && decimQ != 1) "$decimP:$decimQ" else "÷$decimFactor"
            return if (direct)
                "$sourceHz → IF $ifHz ($decimStr) → $sinkHz  [direct, no resamp]  $demodMode"
            else
                "$sourceHz → IF $ifHz ($decimStr) → $sinkHz ($resampleP/$resampleQ)  $demodMode"
        }
    }

    @Volatile private var chainSnapshot: ChainSnapshot? = null

    /** Called by DspEngine whenever the narrow or WFM decimation chain is established. */
    fun setChain(snap: ChainSnapshot) {
        chainSnapshot = snap
        log("IQ-Chain",
            "${snap.sourceHz} → IF ${snap.ifHz} (÷${snap.decimFactor}) " +
            "→ ${snap.sinkHz} Hz  resample=${snap.resampleP}/${snap.resampleQ}" +
            if (snap.direct) "  [direct]" else "" +
            "  mode=${snap.demodMode}")
    }

    fun getChain(): ChainSnapshot? = chainSnapshot

    /**
     * Called by DspEngine.setDemodMode() immediately when the protocol changes.
     * Nulls the chain snapshot so the debug panel shows "—" rather than the
     * previous mode's rates until the first IQ block has been processed under
     * the new mode and a fresh ChainSnapshot has been pushed via setChain().
     */
    fun clearChain() { chainSnapshot = null }

    // ── IQ Stream bulk-transfer performance tracking ─────────────────────────
    //
    // Fed by RtlSdrDevice's streaming loop on every helper.read() call (the
    // synchronous bulkTransfer() / ioctl / fd read that fills one IQ buffer).
    //
    //  • avgXferMs / maxXferMs — how long each read() call took to return.
    //    Large or wildly varying values indicate the USB host controller or
    //    OS scheduler is stalling the transfer (often the #1 cause of
    //    choppy audio / gaps in the waterfall).
    //  • jitterMs — standard deviation of the time *between* successive
    //    successful reads. A healthy stream has a near-constant cadence
    //    (buffer_size / sample_rate); high jitter means buffers are arriving
    //    in bursts rather than steadily, which the downstream DSP ring
    //    buffers must absorb.
    //  • dropCount — bulkTransfer() returned <= 0 (USB stall / disconnect /
    //    timeout). Each occurrence is a lost IQ buffer.
    //  • shortReadCount — bulkTransfer() returned fewer bytes than requested
    //    (a partial USB transfer). Not fatal but a sign of marginal USB
    //    bandwidth/power.
    //  • rating — overall EXCELLENT/GOOD/FAIR/POOR verdict derived from the
    //    above, shown directly in the Debug Panel so problems are obvious
    //    at a glance without reading raw numbers.

    private const val IQ_PERF_WINDOW = 50

    private val iqXferTimesMs   = ArrayDeque<Long>(IQ_PERF_WINDOW)
    private val iqArrivalGapsMs = ArrayDeque<Long>(IQ_PERF_WINDOW)
    private var iqLastArrivalMs = 0L
    private val iqTotalReads      = AtomicLong(0)
    private val iqDropCount       = AtomicLong(0)
    private val iqShortReadCount  = AtomicLong(0)
    private val iqFlowDropCount   = AtomicLong(0)  // blocks dropped by SharedFlow DROP_OLDEST

    data class IqPerfSnapshot(
        val avgXferMs: Double,
        val maxXferMs: Long,
        val jitterMs: Double,
        val totalReads: Long,
        val dropCount: Long,
        val shortReadCount: Long,
        val dropRatePct: Double,
        val rating: String,         // "EXCELLENT" | "GOOD" | "FAIR" | "POOR" | "—"
        val flowDropCount: Long = 0  // blocks silently dropped by SharedFlow DROP_OLDEST (DSP overload)
    )

    /**
     * Record the outcome of one helper.read() (bulkTransfer / ioctl / fd) call.
     *
     * @param bytesRead     return value of the read (<=0 means error/timeout)
     * @param expectedBytes size of the buffer that was requested
     * @param xferMs        wall-clock duration of the read() call itself
     */
    @Synchronized
    fun recordIqTransfer(bytesRead: Int, expectedBytes: Int, xferMs: Long) {
        iqTotalReads.incrementAndGet()

        if (iqXferTimesMs.size >= IQ_PERF_WINDOW) iqXferTimesMs.removeFirst()
        iqXferTimesMs.addLast(xferMs)

        val now = System.currentTimeMillis()
        if (bytesRead > 0) {
            if (iqLastArrivalMs != 0L) {
                if (iqArrivalGapsMs.size >= IQ_PERF_WINDOW) iqArrivalGapsMs.removeFirst()
                iqArrivalGapsMs.addLast(now - iqLastArrivalMs)
            }
            iqLastArrivalMs = now
            if (bytesRead < expectedBytes) iqShortReadCount.incrementAndGet()
        } else {
            iqDropCount.incrementAndGet()
        }
    }

    /** Snapshot of current IQ bulk-transfer performance, for the Debug Panel. */
    @Synchronized
    fun getIqPerf(): IqPerfSnapshot {
        val total  = iqTotalReads.get()
        val drops  = iqDropCount.get()
        val shorts = iqShortReadCount.get()

        val avgXfer = if (iqXferTimesMs.isEmpty()) 0.0 else iqXferTimesMs.average()
        val maxXfer = iqXferTimesMs.maxOrNull() ?: 0L
        val jitter  = stdDev(iqArrivalGapsMs)
        val dropRate = if (total > 0) (drops + shorts) * 100.0 / total else 0.0

        val rating = when {
            total < 5L                                    -> "—"
            dropRate > 5.0 || jitter > 100.0              -> "POOR"
            dropRate > 1.0 || shorts > 0L || jitter > 30.0 -> "FAIR"
            jitter > 10.0                                  -> "GOOD"
            else                                           -> "EXCELLENT"
        }

        return IqPerfSnapshot(
            avgXferMs = avgXfer,
            maxXferMs = maxXfer,
            jitterMs  = jitter,
            totalReads = total,
            dropCount = drops,
            shortReadCount = shorts,
            dropRatePct = dropRate,
            rating = rating,
            flowDropCount = iqFlowDropCount.get()
        )
    }

    /** Called by RtlSdrDevice when SharedFlow.tryEmit() returns false (DROP_OLDEST fired). */
    fun incrementIqFlowDrops() { iqFlowDropCount.incrementAndGet() }

    /** Reset all IQ bulk-transfer performance counters (called from resetAll()). */
    @Synchronized
    fun resetIqPerf() {
        iqXferTimesMs.clear()
        iqArrivalGapsMs.clear()
        iqLastArrivalMs = 0L
        iqTotalReads.set(0)
        iqDropCount.set(0)
        iqShortReadCount.set(0)
        iqFlowDropCount.set(0)
    }

    private fun stdDev(values: Collection<Long>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return Math.sqrt(variance)
    }

    // ── Error-only log ring buffer (last 200 error/warn events) ──────────────
    //
    // The full logBuf contains all [OK]/[WARN]/[ERROR] events — useful for
    // export but noisy for real-time monitoring.  errorLog keeps only the
    // WARN and ERROR lines for the "Diagnostic Log" panel display.

    private val errorLogBuf = ArrayDeque<String>(LOG_SIZE)

    @Synchronized
    fun logError(tag: String, msg: String) {
        val line = "[${logFmt.format(Date())}] [$tag] $msg"
        if (errorLogBuf.size >= LOG_SIZE) errorLogBuf.removeFirst()
        errorLogBuf.addLast(line)
        // Also push into the full log so export/copy captures everything.
        if (logBuf.size >= LOG_SIZE) logBuf.removeFirst()
        logBuf.addLast(line)
    }

    @Synchronized
    fun getErrorLog(): List<String> = errorLogBuf.toList()

    private const val LOG_SIZE = 200
    private val logBuf = ArrayDeque<String>(LOG_SIZE)
    private val logFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun log(tag: String, msg: String) {
        val line = "[${logFmt.format(Date())}] [$tag] $msg"
        if (logBuf.size >= LOG_SIZE) logBuf.removeFirst()
        logBuf.addLast(line)
    }

    @Synchronized
    fun getLog(): List<String> = logBuf.toList()

    // ── Per-stage extras API ─────────────────────────────────────────────────

    fun setExtra(stage: Int, key: String, value: String) {
        if (stage < 0 || stage >= NUM_STAGES) return
        synchronized(this) { extras[stage][key] = value }
    }

    fun clearExtra(stage: Int, key: String) {
        if (stage < 0 || stage >= NUM_STAGES) return
        synchronized(this) { extras[stage].remove(key) }
    }

    @Synchronized
    fun getExtras(stage: Int): Map<String, String> {
        if (stage < 0 || stage >= NUM_STAGES) return emptyMap()
        return LinkedHashMap(extras[stage])
    }

    // ── Global metrics API ────────────────────────────────────────────────────

    @Synchronized
    fun setGlobal(key: String, value: String) {
        globalMetrics[key] = value
    }

    @Synchronized
    fun getGlobals(): Map<String, String> = LinkedHashMap(globalMetrics)

    // ── Public reporting API ─────────────────────────────────────────────────

    /** Called when a stage emits one event (IQ packet, FFT frame, audio write, etc.). */
    fun tick(stage: Int) {
        if (stage < 0 || stage >= NUM_STAGES) return
        counters[stage].incrementAndGet()
        lastEventMs[stage].set(System.currentTimeMillis())
        rateCount[stage].incrementAndGet()
        updateRate(stage)
    }

    /** Set the status and detail string for a stage. */
    fun setStatus(stage: Int, status: StageStatus, detail: String = "") {
        if (stage < 0 || stage >= NUM_STAGES) return
        synchronized(this) {
            statuses[stage] = status
            details[stage] = detail
            if (status != StageStatus.ERROR) errors[stage] = ""
        }
        lastEventMs[stage].set(System.currentTimeMillis())
        val stageName = stageNames.getOrElse(stage) { "Stage$stage" }
        if (status == StageStatus.ERROR || status == StageStatus.WARN) {
            logError(stageName, "[$status] $detail")
        } else {
            log(stageName, "[$status] $detail")
        }
    }

    /** Report an error at a stage. */
    fun setError(stage: Int, msg: String) {
        if (stage < 0 || stage >= NUM_STAGES) return
        synchronized(this) {
            statuses[stage] = StageStatus.ERROR
            errors[stage] = msg
        }
        lastEventMs[stage].set(System.currentTimeMillis())
        logError(stageNames.getOrElse(stage) { "Stage$stage" }, "[ERROR] $msg")
    }

    /** Update detail string only (status unchanged). */
    fun setDetail(stage: Int, detail: String) {
        if (stage < 0 || stage >= NUM_STAGES) return
        synchronized(this) { details[stage] = detail }
    }

    /** Reset a stage back to IDLE (e.g. on disconnect). */
    fun resetStage(stage: Int) {
        if (stage < 0 || stage >= NUM_STAGES) return
        synchronized(this) {
            statuses[stage] = StageStatus.IDLE
            details[stage] = ""
            errors[stage] = ""
            extras[stage].clear()
        }
        counters[stage].set(0)
        rateCount[stage].set(0)
        rateWindowStart[stage].set(System.currentTimeMillis())
        lastRatePerSec[stage] = 0f
    }

    /** Reset all stages (call on service disconnect). The diagnostic log is preserved. */
    fun resetAll() {
        for (i in 0 until NUM_STAGES) resetStage(i)
        synchronized(this) {
            globalMetrics.clear()
            chainSnapshot = null
        }
        resetIqPerf()
        log("DebugBus", "Pipeline reset")
    }

    // ── Snapshot for display ─────────────────────────────────────────────────

    fun snapshot(): List<StageSnapshot> {
        val now = System.currentTimeMillis()
        return (0 until NUM_STAGES).map { i ->
            // Stale check: if last event > 5s ago and status was OK → WARN.
            // Init-only stages (USB_OPEN, DEVICE_INIT) are exempt because they fire
            // exactly once and never produce periodic events again — demoting them to
            // WARN/STALE after 5 s would falsely imply a problem.
            val lastMs = lastEventMs[i].get()
            val stale = lastMs > 0 && (now - lastMs) > 5000L && i !in staleExemptStages
            synchronized(this) {
                val effectiveStatus = when {
                    statuses[i] == StageStatus.ERROR -> StageStatus.ERROR
                    stale && statuses[i] == StageStatus.OK -> StageStatus.WARN
                    else -> statuses[i]
                }
                StageSnapshot(
                    name        = stageNames[i],
                    status      = effectiveStatus,
                    detail      = details[i],
                    counter     = counters[i].get(),
                    ratePerSec  = lastRatePerSec[i],
                    lastEventMs = lastMs,
                    errorMsg    = errors[i],
                    extras      = LinkedHashMap(extras[i])
                )
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun updateRate(stage: Int) {
        val now = System.currentTimeMillis()
        val windowStart = rateWindowStart[stage].get()
        val elapsed = now - windowStart
        if (elapsed >= RATE_WINDOW_MS) {
            val count = rateCount[stage].getAndSet(0)
            lastRatePerSec[stage] = count.toFloat() / (elapsed / 1000f)
            rateWindowStart[stage].set(now)
        }
    }

    fun formatElapsed(lastMs: Long): String {
        if (lastMs == 0L) return "never"
        val diff = System.currentTimeMillis() - lastMs
        return when {
            diff < 1000  -> "${diff}ms ago"
            diff < 60000 -> "${diff / 1000}s ago"
            else         -> "${diff / 60000}m ago"
        }
    }
}
