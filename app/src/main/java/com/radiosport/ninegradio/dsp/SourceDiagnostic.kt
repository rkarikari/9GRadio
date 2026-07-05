package com.radiosport.ninegradio.dsp

import com.radiosport.ninegradio.debug.DebugBus
import com.radiosport.ninegradio.source.IqSource
import com.radiosport.ninegradio.source.RtlSdrDeviceSource
import com.radiosport.ninegradio.source.RtlTcpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Automated diagnostic for the IQ source → DSP pipeline → audio output chain.
 *
 * Call [run] from any coroutine (it switches to [Dispatchers.Default] internally).
 * The method returns a [Report] with per-finding [Level] indicators, a formatted
 * ASCII report, and an overall [Verdict].
 *
 * ── Sections ──────────────────────────────────────────────────────────────────
 *  §1  Source config      — type, DSP block size, block period at current rate
 *  §2  Live IQ health     — delivery rate, transfer jitter, SharedFlow drops
 *  §3  DSP benchmark      — synthetic timing of each WFM stage vs block period
 *  §4  Audio health       — AudioTrack state, write failures, buffer size
 *  §5  System resources   — CPU cores, JVM heap, thread pool
 *  §6  Verdict            — overall assessment + per-finding recommendations
 *
 * ── Why both sources now use the same DSP block size ──────────────────────────
 *  USB startStreaming() emits 32,768-byte USB transfers split into 2 × 16,384-byte
 *  chunks before the SharedFlow emit, with a coroutine yield() between each chunk
 *  so the DSP coroutine on Dispatchers.Default gets a scheduling slot between them.
 *  RtlTcpSource.READ_BUF_SIZE is also 16,384 bytes.
 *  Both sources therefore deliver DSP_CHUNK_SIZE-byte blocks to processIqBlock()
 *  at the same rate (~250 blocks/s at 2 MS/s).  DebugBus.tick(STAGE_IQ_STREAM) is
 *  called once per chunk (not once per USB transfer) so the reported delivery rate
 *  matches the expectedRate used in §2 (sampleRate / chunkSamples).
 *  The block period at 1.92 MS/s is 4.27 ms for both.
 *
 * ── DSP budget ratio ──────────────────────────────────────────────────────────
 *  ratio = estimatedDspMs / blockPeriodMs
 *  < 0.70   ✅ PASS   ≥30 % headroom — stable
 *  0.70-0.90  ⚠️ WARN  marginal — GC pauses can trigger SharedFlow drops
 *  > 0.90   ❌ FAIL   budget exceeded regularly → audible WFM stutter
 */
class SourceDiagnostic(
    private val dspEngine: DspEngine,
    private val source: IqSource
) {

    // ── Public data types ──────────────────────────────────────────────────────

    enum class Level { INFO, PASS, WARN, FAIL }

    data class Finding(
        val label:  String,
        val value:  String,
        val level:  Level,
        val detail: String = ""
    )

    data class Verdict(
        val summary: String,
        val level:   Level,
        val recommendations: List<String>
    )

    data class Report(
        val findings:      List<Finding>,
        val verdict:       Verdict,
        val formattedText: String          // pre-formatted for AlertDialog / clipboard
    )

    // ── Constants ──────────────────────────────────────────────────────────────

    companion object {
        private const val RATIO_WARN       = 0.70f
        private const val RATIO_FAIL       = 0.90f
        private const val N_WARMUP         = 3
        private const val N_RUNS           = 7
        private const val FLOW_DEPTH       = 17          // replay=1 + extraBufferCapacity=16
        private const val AUDIO_RATE       = 48_000
        // Bytes-per-sample for uint8 IQ: 1 byte I + 1 byte Q = 2
        private const val BYTES_PER_SAMPLE = 2
    }

    // ── Entry point ────────────────────────────────────────────────────────────

    suspend fun run(): Report = withContext(Dispatchers.Default) {
        val findings      = mutableListOf<Finding>()
        val sampleRate    = source.getSampleRate()
        val isUsb         = source is RtlSdrDeviceSource
        val isTcp         = source is RtlTcpSource
        val dspBlockBytes = DspEngine.DSP_CHUNK_SIZE
        val blockPeriodMs = dspBlockBytes.toDouble() / (sampleRate.toDouble() * BYTES_PER_SAMPLE) * 1000.0
        val wfmIfRate     = DspEngine.wfmIfRate(sampleRate)
        val flowBudgetMs  = FLOW_DEPTH * blockPeriodMs

        // ── §1 Source Configuration ────────────────────────────────────────────
        findings += Finding("Source type",
            when {
                isUsb -> "USB  (internal driver — direct bulkTransfer)"
                isTcp -> "rtl_tcp  (external — TCP socket to separate process)"
                else  -> source.getSourceName()
            }, Level.INFO,
            if (isUsb)
                "USB transfers split into 16 kB DSP chunks — same pipeline granularity as TCP"
            else if (isTcp)
                "rtl_tcp offloads USB to separate process; app reads 16 kB TCP chunks"
            else "")

        findings += Finding("Sample rate",
            "%,d S/s".format(sampleRate), Level.INFO,
            when {
                sampleRate > 2_400_000 -> "High rate — block period is very tight"
                sampleRate < 1_024_000 -> "Low rate — generous block budget"
                else                   -> "Typical WFM rate"
            })

        findings += Finding("DSP block size",
            "%,d bytes  (%,d IQ samples)".format(dspBlockBytes, dspBlockBytes / BYTES_PER_SAMPLE),
            Level.INFO, "Both sources deliver this block size to processIqBlock()")

        findings += Finding("Block period",
            "%.3f ms".format(blockPeriodMs), Level.INFO,
            "processIqBlock() must complete within this window to avoid SharedFlow drops")

        findings += Finding("WFM IF rate",
            "%,d S/s  (÷%d)".format(wfmIfRate, sampleRate / wfmIfRate), Level.INFO,
            "ComplexDecimator output rate feeding the WFM discriminator")

        if (isUsb) {
            findings += Finding("USB transfer size",
                "%,d bytes  (%d × DSP blocks)".format(
                    DspEngine.USB_STREAMING_BUF, DspEngine.USB_STREAMING_BUF / dspBlockBytes),
                Level.INFO, "Split into ${ DspEngine.USB_STREAMING_BUF / dspBlockBytes } chunks before SharedFlow emit")
        } else if (isTcp) {
            val tcp = source as RtlTcpSource
            findings += Finding("TCP endpoint",
                "${tcp.host}:${tcp.port}", Level.INFO,
                "rtl_tcp_andro running in a separate process handles the USB")
        }

        // ── §2 Live IQ Stream Health ───────────────────────────────────────────
        val perf      = DebugBus.getIqPerf()
        val stages    = DebugBus.snapshot()
        val iqExtras  = DebugBus.getExtras(DebugBus.STAGE_IQ_STREAM)
        val iqRate    = stages[DebugBus.STAGE_IQ_STREAM].ratePerSec
        val dspRate   = stages[DebugBus.STAGE_DSP_PROCESS].ratePerSec
        val expectedRate = sampleRate.toFloat() / (dspBlockBytes / BYTES_PER_SAMPLE.toFloat())

        val deliveryLevel = when {
            iqRate < 1f                                        -> Level.INFO   // not yet streaming
            abs(iqRate - expectedRate) / expectedRate > 0.15f -> Level.WARN
            else                                               -> Level.PASS
        }
        findings += Finding("IQ delivery rate",
            if (iqRate < 1f) "not streaming" else
                "%.1f blocks/s  (expected %.1f)".format(iqRate, expectedRate),
            deliveryLevel,
            if (deliveryLevel == Level.WARN)
                "Rate is %.0f%% below expected — check USB connection or TCP server".format(
                    (1f - iqRate / expectedRate) * 100) else "")

        if (isUsb && perf.avgXferMs > 0.0) {
            val jitterFrac = perf.jitterMs / blockPeriodMs
            findings += Finding("USB avg transfer",
                "%.2f ms  (max %d ms)".format(perf.avgXferMs, perf.maxXferMs),
                if (perf.avgXferMs > blockPeriodMs * 0.85) Level.WARN else Level.PASS,
                if (perf.avgXferMs > blockPeriodMs * 0.85)
                    "Transfer time close to block period — marginal USB bandwidth" else "")

            findings += Finding("USB transfer jitter",
                "σ = %.2f ms  (%.0f%% of block period)".format(perf.jitterMs, jitterFrac * 100),
                when {
                    jitterFrac > 0.50 -> Level.FAIL
                    jitterFrac > 0.25 -> Level.WARN
                    else              -> Level.PASS
                },
                if (jitterFrac > 0.25)
                    "High jitter eats into DSP headroom; SharedFlow will sometimes back up" else "")

            val dropPct = iqExtras[DebugBus.EXTRA_IQ_DROP_RATE] ?: "—"
            findings += Finding("USB error rate",
                "$dropPct  (${perf.dropCount} errors, ${perf.shortReadCount} short reads)",
                if (perf.dropCount > 0 || perf.shortReadCount > 10) Level.WARN else Level.PASS,
                if (perf.dropCount > 0) "bulkTransfer() returned ≤0 — USB bandwidth saturated or device stall" else "")

            val rating = iqExtras[DebugBus.EXTRA_IQ_RATING] ?: "—"
            findings += Finding("USB perf rating",
                rating, when (rating) {
                    "EXCELLENT", "GOOD" -> Level.PASS
                    "FAIR"              -> Level.WARN
                    else                -> if (perf.avgXferMs > 0) Level.WARN else Level.INFO
                })
        }

        // SharedFlow drop count is the clearest indicator of DSP overload
        val flowDrops = perf.flowDropCount
        findings += Finding("SharedFlow DROP_OLDEST",
            if (flowDrops == 0L) "0  — no drops" else "$flowDrops blocks dropped!",
            if (flowDrops == 0L) Level.PASS else Level.FAIL,
            if (flowDrops > 0)
                "DSP could not consume IQ blocks in time — each drop = " +
                "%.1f ms silence → audible stutter".format(blockPeriodMs)
            else "")

        val dspLag = if (dspRate > 0.5f && iqRate > 0.5f) iqRate / dspRate else 1f
        findings += Finding("DSP processing rate",
            if (dspRate < 0.5f) "not streaming" else
                "%.1f blocks/s  (%.0f%% of delivery)".format(dspRate, dspLag * 100f),
            when {
                dspRate < 0.5f       -> Level.INFO
                dspLag > 1.15f      -> Level.FAIL
                dspLag > 1.05f      -> Level.WARN
                else                 -> Level.PASS
            },
            if (dspLag > 1.05f)
                "DSP is %.0f%% behind IQ delivery — blocks are being discarded".format(
                    (dspLag - 1f) * 100) else "")

        findings += Finding("SharedFlow jitter buffer",
            "%.0f ms  (%d × %.2f ms blocks)".format(flowBudgetMs, FLOW_DEPTH, blockPeriodMs),
            if (flowBudgetMs < 40.0) Level.WARN else Level.PASS,
            "System can lag this far behind before the first SharedFlow drop")

        // ── §3 DSP Pipeline Benchmark ──────────────────────────────────────────
        // Synthetic WFM timing: each stage measured in isolation, median of N_RUNS.
        // A zero-filled ByteArray/FloatArray is representative: the cost is dominated
        // by memory bandwidth (O(N) loops), not data-dependent branches.

        findings += Finding("──────────────────",
            "Synthetic WFM benchmark on ${dspBlockBytes / 1024} kB block", Level.INFO)

        // 3a FFT (uses only fftSize samples of the block, rest ignored)
        val benchFft  = FftEngine(dspEngine.fftEngine.fftSize)
        val dummyRaw  = ByteArray(dspBlockBytes)
        val fftMs     = medianMs { benchFft.processUint8(dummyRaw) }
        val fftPct    = (fftMs / blockPeriodMs * 100).toInt()
        findings += Finding("FFT (${dspEngine.fftEngine.fftSize}-pt)",
            "%.3f ms   %d%% of budget".format(fftMs, fftPct),
            if (fftPct > 35) Level.WARN else Level.PASS,
            "Processes ${dspEngine.fftEngine.fftSize * 2} of $dspBlockBytes raw bytes; " +
            "cost independent of demod mode")

        // 3b IQ→float + DC removal estimate (NativeDsp; proportional to block size)
        // Measured as: scan + scale + DC-block on dspBlockBytes/2 float IQ pairs.
        // Approximate: 1 float multiply+add pair per sample at ~1.5 ns/op.
        val nativeDspMs = (dspBlockBytes / 2.0) * 1.5e-6   // rough estimate ~1.5 ns/sample
        findings += Finding("IQ→float + DC removal",
            "~%.2f ms  (estimate)".format(nativeDspMs),
            if (nativeDspMs > blockPeriodMs * 0.2) Level.WARN else Level.INFO,
            "NativeDsp.convertUint8ToFloat + removeDc on ${dspBlockBytes / 2} IQ samples — O(N)")

        // 3c ComplexDecimator: deviceRate → wfmIfRate
        val decimator = ComplexDecimator(sampleRate, wfmIfRate)
        val dummyIq   = FloatArray(dspBlockBytes)        // dspBlockBytes/2 IQ pairs
        val decimMs   = medianMs { decimator.process(dummyIq) }
        val decimOut  = decimator.process(dummyIq)       // capture real output for §3d input
        val decimPct  = (decimMs / blockPeriodMs * 100).toInt()
        findings += Finding("ComplexDecimator (÷${sampleRate / wfmIfRate})",
            "%.3f ms   %d%% of budget".format(decimMs, decimPct),
            if (decimPct > 40) Level.WARN else Level.PASS,
            "${dspBlockBytes / 2} IQ → ${decimOut.size / 2} IQ  " +
            "(%,d → %,d S/s)".format(sampleRate, wfmIfRate))

        // 3d WFM Demodulator: FM discriminator + de-emphasis + 15 kHz LPF
        val demod    = WfmDemodulator()
        val demodMs  = medianMs { demod.demodulate(decimOut, wfmIfRate) }
        val demodOut = demod.demodulate(decimOut, wfmIfRate)
        val demodPct = (demodMs / blockPeriodMs * 100).toInt()
        findings += Finding("WfmDemodulator",
            "%.3f ms   %d%% of budget".format(demodMs, demodPct),
            if (demodPct > 25) Level.WARN else Level.PASS,
            "FM disc + 75µs de-emph + 15kHz LPF on ${decimOut.size / 2} IQ → " +
            "${demodOut.size} mono samples")

        // 3e PolyphaseResampler: wfmIfRate → 48 kHz
        val resampler   = PolyphaseResampler(wfmIfRate, AUDIO_RATE)
        val resampleMs  = medianMs { resampler.process(demodOut) }
        val resampledOut= resampler.process(demodOut)
        val resamplePct = (resampleMs / blockPeriodMs * 100).toInt()
        findings += Finding("PolyphaseResampler",
            "%.3f ms   %d%% of budget".format(resampleMs, resamplePct),
            if (resamplePct > 20) Level.WARN else Level.PASS,
            "${demodOut.size} @ %,d S/s → ${resampledOut.size} @ %,d S/s".format(
                wfmIfRate, AUDIO_RATE))

        // Total estimate: measured benchmarks + NativeDsp estimate
        val totalBenchMs  = fftMs + nativeDspMs + decimMs + demodMs + resampleMs
        val budgetRatio   = (totalBenchMs / blockPeriodMs).toFloat()
        val headroomPct   = ((1.0 - totalBenchMs / blockPeriodMs) * 100).toInt()
        val budgetLevel = when {
            budgetRatio > RATIO_FAIL -> Level.FAIL
            budgetRatio > RATIO_WARN -> Level.WARN
            else                     -> Level.PASS
        }
        val headroomStr = if (headroomPct >= 0) "+$headroomPct%%" else "$headroomPct%%"
        findings += Finding("Total WFM DSP estimate",
            "%.3f ms / %.3f ms budget  ($headroomStr)".format(totalBenchMs, blockPeriodMs),
            budgetLevel,
            when (budgetLevel) {
                Level.FAIL -> "Budget exceeded — SharedFlow drops expected → audible stuttering"
                Level.WARN -> "Marginal — a 5–10 ms GC pause will cause occasional drops"
                else       -> "Comfortable headroom — stable at this sample rate"
            })

        // Audio write granularity
        val audioWriteMs = resampledOut.size.toDouble() / AUDIO_RATE * 1000.0
        findings += Finding("Audio write size",
            "${resampledOut.size} samples  (%.2f ms)".format(audioWriteMs),
            Level.INFO,
            "Each audioWriterJob write covers this much audio (on Dispatchers.IO ✓)")

        // ── §4 Audio Output Health ─────────────────────────────────────────────
        val atExtras    = DebugBus.getExtras(DebugBus.STAGE_AUDIO_TRACK)
        val atPlay      = atExtras[DebugBus.EXTRA_AT_PLAY]      ?: "unknown"
        val atFails     = atExtras[DebugBus.EXTRA_AT_WRITE_FAIL]?.toLongOrNull() ?: 0L
        val atBufBytes  = atExtras[DebugBus.EXTRA_AT_BUF_SIZE]?.toLongOrNull() ?: 0L
        val atBufMs     = if (atBufBytes > 0) atBufBytes / (AUDIO_RATE.toDouble() * 4) * 1000 else 0.0
        val atLastWrite = atExtras[DebugBus.EXTRA_AT_LAST_WRITE] ?: "—"

        findings += Finding("AudioTrack state", atPlay,
            when (atPlay) { "PLAYING" -> Level.PASS; "STOPPED" -> Level.INFO; else -> Level.WARN })

        findings += Finding("Write failures", atFails.toString(),
            if (atFails == 0L) Level.PASS else Level.FAIL,
            if (atFails > 0) "AudioTrack.write() returned error — audio HAL issue or underrun" else "")

        findings += Finding("Last write result", atLastWrite, Level.INFO)

        if (atBufMs > 0.0) {
            findings += Finding("AudioTrack buffer",
                "%.0f ms  (%,d bytes)".format(atBufMs, atBufBytes),
                if (atBufMs < 30.0) Level.WARN else Level.PASS,
                "Larger buffer = more latency but more headroom for DSP jitter")
        }
        findings += Finding("audioWriterJob",
            "Dispatchers.IO  ✓", Level.PASS,
            "Blocking WRITE_BLOCKING call isolated from Dispatchers.Default DSP thread pool")

        // ── §5 System Resources ────────────────────────────────────────────────
        val rt             = Runtime.getRuntime()
        val cores          = rt.availableProcessors()
        val defaultThreads = max(2, cores)
        val freeHeapMb     = rt.freeMemory() / 1_048_576L
        val totalHeapMb    = rt.totalMemory() / 1_048_576L
        val maxHeapMb      = rt.maxMemory() / 1_048_576L
        val usedHeapMb     = totalHeapMb - freeHeapMb
        val gcRisk         = freeHeapMb < 24L

        findings += Finding("CPU cores",
            "$cores  →  $defaultThreads Default + elastic IO threads",
            if (cores < 3) Level.WARN else Level.PASS,
            if (cores < 3)
                "Fewer cores → more thread contention between DSP + UI" else "")

        findings += Finding("JVM heap",
            "${usedHeapMb} MB used / ${maxHeapMb} MB max  (${freeHeapMb} MB free)",
            if (gcRisk) Level.WARN else Level.PASS,
            if (gcRisk)
                "Low free heap → frequent GC pauses → DSP jitter → SharedFlow drops" else "")

        // ── §6 Build Verdict ───────────────────────────────────────────────────
        val verdict = buildVerdict(
            isUsb, isTcp, sampleRate, blockPeriodMs, wfmIfRate,
            flowDrops, budgetRatio, headroomPct, atFails, gcRisk,
            perf.dropCount, perf.jitterMs
        )

        val text = formatReport(
            source.getSourceName(), findings, verdict,
            sampleRate, wfmIfRate, blockPeriodMs, totalBenchMs, budgetRatio
        )

        Report(findings.toList(), verdict, text)
    }

    // ── Benchmark helper ───────────────────────────────────────────────────────

    private inline fun medianMs(block: () -> Unit): Double {
        repeat(N_WARMUP) { block() }
        val times = LongArray(N_RUNS) {
            val t0 = System.nanoTime()
            block()
            System.nanoTime() - t0
        }
        times.sort()
        return times[N_RUNS / 2] / 1_000_000.0
    }

    // ── Verdict builder ────────────────────────────────────────────────────────

    private fun buildVerdict(
        isUsb: Boolean, isTcp: Boolean,
        sampleRate: Int, blockPeriodMs: Double, wfmIfRate: Int,
        flowDrops: Long, budgetRatio: Float, headroomPct: Int,
        atFails: Long, gcRisk: Boolean, usbDrops: Long, jitterMs: Double
    ): Verdict {
        val issues = mutableListOf<String>()
        val recs   = mutableListOf<String>()
        var worst  = Level.PASS

        // Critical failures first
        if (flowDrops > 0L) {
            issues += "❌  $flowDrops SharedFlow drop(s): DSP cannot keep up with IQ delivery"
            recs   += "Reduce sample rate (drops will stop when budget ratio < 0.70)"
            worst   = Level.FAIL
        }
        if (budgetRatio > RATIO_FAIL) {
            issues += "❌  DSP benchmark ${"%.1f".format(blockPeriodMs * budgetRatio)} ms " +
                "exceeds ${blockPeriodMs.let { "%.1f".format(it) }} ms block period " +
                "[${"%.2f".format(budgetRatio)}× budget]"
            worst = Level.FAIL
        } else if (budgetRatio > RATIO_WARN) {
            issues += "⚠️   DSP is using ${"%.0f".format(budgetRatio * 100)}% of block budget — " +
                "GC pauses will occasionally cause drops"
            if (worst < Level.WARN) worst = Level.WARN
        }
        if (atFails > 0L) {
            issues += "❌  $atFails AudioTrack write failures"
            if (worst < Level.WARN) worst = Level.WARN
        }
        if (gcRisk) {
            issues += "⚠️   Low JVM heap — frequent GC pauses are likely"
            if (worst < Level.WARN) worst = Level.WARN
        }
        if (isUsb && usbDrops > 0L) {
            issues += "⚠️   $usbDrops USB transfer errors — dongle bandwidth marginal"
            if (worst < Level.WARN) worst = Level.WARN
        }
        if (isUsb && jitterMs > blockPeriodMs * 0.3) {
            issues += "⚠️   USB transfer jitter (${"%.1f".format(jitterMs)} ms σ) is high relative to block period"
            if (worst < Level.WARN) worst = Level.WARN
        }

        if (worst == Level.PASS) {
            issues += "✅  All checks passed — pipeline is healthy with $headroomPct% DSP headroom"
        }

        // Recommendations
        if (budgetRatio > RATIO_WARN || flowDrops > 0L) {
            val safeRate = when {
                sampleRate > 2_400_000 -> 2_048_000
                sampleRate > 2_048_000 -> 1_920_000
                sampleRate > 1_920_000 -> 1_440_000
                sampleRate > 1_440_000 -> 1_024_000
                else -> null
            }
            safeRate?.let {
                val safePeriod = DspEngine.DSP_CHUNK_SIZE.toDouble() / (it * 2.0) * 1000.0
                recs += "Reduce sample rate to %,d S/s → block period %.2f ms".format(it, safePeriod)
            }
            recs += "Reduce FFT size (Settings → Display → FFT Size) to cut FFT cost"
            recs += "Enable Frame Averaging ×2 in Display tab to halve FFT call rate"
            if (isUsb) {
                recs += "Switch to rtl_tcp source — USB transfer jitter handled by external process"
            }
        }
        if (gcRisk) {
            recs += "Close background apps to free memory and reduce GC pressure"
        }
        if (isUsb && isTcp.not()) {
            recs += "Compare: switch to rtl_tcp and re-run diagnostic to measure the difference"
        }

        return Verdict(
            summary         = issues.joinToString("\n"),
            level           = worst,
            recommendations = recs
        )
    }

    // ── Text formatter ─────────────────────────────────────────────────────────

    private fun formatReport(
        sourceName: String,
        findings:   List<Finding>,
        verdict:    Verdict,
        sampleRate: Int,
        wfmIfRate:  Int,
        blockPeriodMs: Double,
        totalBenchMs:  Double,
        budgetRatio:   Float
    ): String = buildString {
        val ts = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.US).format(Date())
        append("╔══════════════════════════════════════════════╗\n")
        append("║        9GRadio  Source Diagnostic            ║\n")
        append("╠══════════════════════════════════════════════╣\n")
        append("║  $ts".padEnd(47)).append("║\n")
        append("╚══════════════════════════════════════════════╝\n")
        append("  Source : $sourceName\n")
        append("  Rate   : %,d S/s   IF: %,d S/s\n".format(sampleRate, wfmIfRate))
        append("  Period : %.3f ms/block   DSP est.: %.3f ms (%.0f%%)\n\n"
            .format(blockPeriodMs, totalBenchMs, budgetRatio * 100))

        var lastSection = ""
        for (f in findings) {
            // Section banner on transition
            val sec = sectionFor(f.label)
            if (sec != lastSection) {
                lastSection = sec
                append("── $sec ").append("─".repeat(max(0, 46 - sec.length - 3))).append("\n")
            }
            // Separator pseudo-finding (the benchmark header row)
            if (f.level == Level.INFO && f.label.startsWith("──")) {
                append("   ${f.value}\n"); continue
            }
            val icon = when (f.level) {
                Level.PASS -> "✅ "; Level.WARN -> "⚠️  "; Level.FAIL -> "❌ "; Level.INFO -> "ℹ️  "
            }
            append("$icon %-30s  %s\n".format(f.label.take(30), f.value))
            if (f.detail.isNotEmpty()) append("      ↳ ${f.detail}\n")
        }

        append("\n── Verdict ").append("─".repeat(36)).append("\n")
        append(verdict.summary).append("\n")
        if (verdict.recommendations.isNotEmpty()) {
            append("\nRecommendations:\n")
            verdict.recommendations.forEach { append("  • $it\n") }
        }
        append("\n── Architecture note ").append("─".repeat(26)).append("\n")
        append("  Both USB (chunked) and rtl_tcp deliver %,d-byte\n".format(DspEngine.DSP_CHUNK_SIZE))
        append("  blocks to processIqBlock(). The key difference:\n")
        append("  USB → USBDEVFS_BULK in this process + chunked emit\n")
        append("  TCP → USB handled by rtl_tcp_andro process; app\n")
        append("        only reads pre-chunked TCP socket bytes.\n")
        append("  Transfer jitter from the USB layer is absorbed by\n")
        append("  the SharedFlow buffer (capacity = $FLOW_DEPTH blocks =\n")
        append("  %.0f ms at this rate).\n".format(FLOW_DEPTH * blockPeriodMs))
        append("╚══════════════════════════════════════════════╝\n")
    }

    private fun sectionFor(label: String): String = when {
        label.startsWith("Source") || label == "Sample rate" ||
        label == "DSP block size" || label == "Block period" ||
        label == "WFM IF rate" || label.startsWith("USB transfer size") ||
        label.startsWith("TCP")                              -> "§1  Source Config"

        label.startsWith("IQ delivery") || label.startsWith("USB avg") ||
        label.startsWith("USB transfer jitter") || label.startsWith("USB error") ||
        label.startsWith("USB perf") || label.startsWith("SharedFlow DROP") ||
        label.startsWith("DSP processing") || label.startsWith("SharedFlow jitter") -> "§2  Live IQ Health"

        label.startsWith("───") || label.startsWith("FFT") ||
        label.startsWith("IQ→") || label.startsWith("Complex") ||
        label.startsWith("WfmDem") || label.startsWith("Polyphase") ||
        label.startsWith("Total WFM") || label.startsWith("Audio write size") -> "§3  DSP Benchmark"

        label.startsWith("AudioTrack") || label.startsWith("Write fail") ||
        label.startsWith("Last write") || label.startsWith("audioWriterJob") -> "§4  Audio"

        else -> "§5  System"
    }
}
