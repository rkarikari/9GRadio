package com.radiosport.ninegradio.scanner

import android.util.Log
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.dsp.FftEngine
import com.radiosport.ninegradio.source.IqSource
import com.radiosport.ninegradio.source.RtlSdrDeviceSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.TreeMap
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Frequency scanner, reimplemented faithfully after the scanning engine used
 * by https://github.com/shajen/rtl-sdr-scanner-cpp.
 *
 * The defining trait of that project (and the thing the previous rewrite of
 * this class had lost) is that it does **not** scan by re-tuning to one
 * narrow channel at a time and reading a single, already-stale "signal
 * strength" value. Instead it:
 *
 *  1. Captures a block of IQ samples covering the *entire* instantaneous
 *     bandwidth the dongle can deliver in one go (its current sample rate).
 *  2. Runs an FFT over that block to get a full power spectrum.
 *  3. Tracks a per-bin noise floor and looks for every contiguous run of
 *     bins sitting above it — each run is one "transmission" — so **several
 *     simultaneous signals inside the same capture are found and reported
 *     in a single pass**, exactly like the upstream project's headline
 *     feature of recording multiple simultaneous transmissions.
 *  4. Hops the capture's centre frequency across the requested range by
 *     (approximately) one bandwidth per hop, so a wide range is covered by
 *     a handful of wideband captures rather than thousands of one-channel
 *     dwells.
 *
 * This also happens to fix the previous implementation's real bug: it read
 * `IqSource.statusFlow.value.signalStrengthDb` (or a caller-supplied
 * provider backed by the *live demod* pipeline's last computed stats) right
 * after retuning the device. Because that value is produced by a separate,
 * asynchronously-running consumer of `iqFlow`, there was no guarantee a
 * fresh sample corresponding to the *new* frequency had actually been
 * processed yet by the time the scanner read it — so scans could dwell on,
 * hit, or skip channels based on a stale reading left over from the
 * previous tuned frequency. This class now owns its own FFT and samples
 * `IqSource.iqFlow` directly after every retune, discarding one buffer to
 * clear the pipe before reading a fresh one, so every measurement is
 * guaranteed to reflect the frequency it claims to.
 *
 * The public shape (ScanConfig / ScanStatus / ScanHit / start-stop-pause)
 * is unchanged so existing callers (MainActivity's scan tab) keep working.
 */
class FrequencyScanner(
    private val device: IqSource,
    private val signalLevelProvider: (() -> Float)? = null,
    /**
     * Invoked with the new frequency immediately after every hardware retune
     * this scanner performs (in [captureBlock] and [sampleSignal]). Wired by
     * the caller to [com.radiosport.ninegradio.dsp.FftEngine.resetSpectrumAveraging]
     * on the *live* spectrum's FFT engine (not this class's own private
     * [fftEngine], which is a separate, scan-only instance) so that engine's
     * frame averaging / smoothing never blends frames captured at two
     * different frequencies together across a hop -- see that method's doc
     * comment for why that matters for Auto dB Range.
     */
    private val onRetune: ((Long) -> Unit)? = null
) {

    companion object {
        private const val TAG = "FrequencyScanner"

        /** FFT size used for the scanner's own spectrum captures. */
        private const val SCAN_FFT_SIZE = 2048

        /** Time to let the tuner PLL settle after a retune before any capture
         *  is trusted (matches the settle delay real scanners use between a
         *  frequency change and the first sample they act on). */
        private const val RETUNE_SETTLE_MS = 8L

        /** Max time to wait for a fresh IQ buffer before giving up on a
         *  capture (guards against a stalled/disconnected source hanging the
         *  scan loop forever). This bounds the *entire* [captureBlock] call,
         *  which now waits for [SCAN_BLOCK_AVERAGES] + 1 buffers (one
         *  discarded post-retune buffer plus the averaged frames), so it must
         *  scale with that count rather than assuming a single buffer. */
        private const val IQ_CAPTURE_TIMEOUT_MS = 250L

        /** Channel bandwidth assumed when averaging bins around a single
         *  tuned frequency (memory scan / priority poll / search). */
        private const val NARROWBAND_HZ = 12_500.0

        /** Number of rapid samples taken during the settle dwell; the max is used. */
        private const val SETTLE_SAMPLES = 3

        /** Fraction of the configured dwell spent on the fast "sniff" pass. */
        private const val SNIFF_DWELL_FRACTION = 0.35

        /** Minimum sniff dwell so very low dwellTimeMs configs still get a real sample. */
        private const val MIN_SNIFF_DWELL_MS = 15L

        /** How far above the tracked noise floor counts as a hit when using
         *  adaptive-squelch mode (see [ScanConfig.adaptiveSquelch]). */
        private const val DEFAULT_ADAPTIVE_MARGIN_DB = 12f

        /** Exponential-moving-average weight for the noise floor tracker.
         *  Small alpha => slow-moving, resistant to being dragged up by
         *  genuine signals; large alpha => reacts quickly to real band
         *  condition changes (e.g. moving the dongle to a different band). */
        private const val NOISE_FLOOR_ALPHA = 0.05

        /** How often the hit frequency is re-sampled while [ScanConfig.holdOnSignal]
         *  is holding the sweep on an active transmission, waiting for carrier loss. */
        private const val HOLD_POLL_INTERVAL_MS = 150L

        /** Consecutive below-threshold polls required before [holdUntilCarrierLoss]
         *  actually releases the hold. A single noisy/faded sample dipping under
         *  threshold is normal mid-transmission (syllabic fading, momentary
         *  deviation dropout) and must not be mistaken for the carrier actually
         *  going away -- releasing on one dip is what let the sweep restart and
         *  re-detect the *same* transmitter's spectral skirt as fresh "new" hits
         *  on neighbouring channels a moment later. Real scanners debounce carrier
         *  loss for roughly this many hundred ms before dropping hold. */
        private const val HOLD_RELEASE_DEBOUNCE_POLLS = 3

        /** Number of wideband FFT frames averaged (in linear power domain)
         *  together into one [FftBlock] before detection runs. A single raw
         *  frame lets bin-to-bin noise wobble on a real carrier's skirt cross
         *  threshold intermittently, which [detectSignals] then reports as
         *  several separate "hits" spread across neighbouring bins/channels
         *  instead of the one real transmission. Averaging several frames in
         *  linear power (not dB) before thresholding smooths that wobble out
         *  the same way real noise-floor averaging does, without blurring a
         *  genuine signal's own centre frequency. */
        private const val SCAN_BLOCK_AVERAGES = 3

        /** Bins immediately around DC (the tuned centre frequency) excluded from
         *  detection, noise-floor estimation, and narrowband power readings.
         *  RTL-SDR (and most direct-conversion/zero-IF) dongles leave a DC
         *  offset / LO-leakage spike sitting exactly at the centre bin of every
         *  capture regardless of whether a real signal is present there -- this
         *  is a hardware artefact, not RF. Without a guard, that spike is
         *  itself detected as a phantom "signal" parked on whatever channel
         *  happens to fall on the tuned centre frequency every single capture,
         *  and it also drags a naive noise-floor estimate upward if it's not
         *  excluded from that calculation too. */
        private const val DC_GUARD_BINS = 6

        /** Bound on the per-frequency hit-counter map to avoid unbounded growth
         *  during very long scanning sessions. */
        private const val MAX_TRACKED_FREQS = 4096

        /** Cap on the number of rows the tabulated channel view can hold, so a
         *  wide range with a tiny step (or an unbounded search) can't build
         *  an unbounded table. */
        private const val MAX_CHANNEL_TABLE_ROWS = 2048

        /** How often (in channels visited) the priority list is polled during
         *  a normal sweep/memory scan. A value of 5 means "check priority
         *  channels once every 5 channels visited". */
        private const val DEFAULT_PRIORITY_INTERVAL = 5

        /** Fraction of the capture bandwidth to hop by on each wideband
         *  sweep step. <1.0 so blocks overlap slightly and nothing near the
         *  edge of one capture is missed because it fell in the discarded
         *  edge margin of both neighbouring captures. */
        private const val HOP_FRACTION = 0.9

        /** Fraction of bins at each edge of a capture that are ignored
         *  (spectral leakage / filter roll-off artefacts live there). */
        private const val EDGE_FRACTION = 0.03
    }

    enum class ScanState { IDLE, SCANNING, PAUSED, STOPPED }

    /** Why a given status update / hit occurred — surfaced to the UI so it can
     *  visually distinguish a normal sweep hit from a priority interrupt. */
    enum class HitSource { SWEEP, MEMORY, PRIORITY, SEARCH }

    data class ScanConfig(
        val startFreqHz: Long,
        val stopFreqHz: Long,
        val stepHz: Long = 12_500L,
        /** Absolute dBFS threshold. Ignored when [adaptiveSquelch] is true —
         *  kept for backward compatibility with callers that already tuned an
         *  absolute value. */
        val squelchDb: Float = -100f,
        /** When true, hits are declared [adaptiveMarginDb] above a live-tracked
         *  local noise floor instead of the fixed [squelchDb]. Recommended for
         *  most use since RF noise floor varies a lot band to band. */
        val adaptiveSquelch: Boolean = false,
        val adaptiveMarginDb: Float = DEFAULT_ADAPTIVE_MARGIN_DB,
        val dwellTimeMs: Long = 200L,
        val resumeTimeMs: Long = 3000L,
        /** Standard scanner "priority/signal hold" behaviour (see e.g.
         *  SDRangel's channel demodulators, which stay locked onto an active
         *  signal rather than timing out on a fixed dwell): when true, a hit
         *  keeps the capture parked on that frequency for as long as the
         *  carrier stays above threshold, and the sweep only resumes once the
         *  transmission actually ends. When false, falls back to the old
         *  behaviour of always waiting a fixed [resumeTimeMs] regardless of
         *  whether the signal is still present. Defaults to true — this is
         *  the default/expected behaviour on virtually every consumer
         *  scanner (Uniden, Whistler, etc.). */
        val holdOnSignal: Boolean = true,
        val skipActiveMs: Long = 500L,
        val mode: DemodMode = DemodMode.NFM,
        val scanUp: Boolean = true,
        /** Extra frequencies checked on a duty cycle during the sweep — e.g. a
         *  known repeater output or emergency channel that must never be
         *  missed for long, even mid-sweep of an unrelated band. */
        val priorityFreqsHz: List<Long> = emptyList(),
        val priorityIntervalChannels: Int = DEFAULT_PRIORITY_INTERVAL,
        /** Frequencies to always skip (persistent lockout — e.g. a birdie or a
         *  known noisy always-on carrier). */
        val lockoutFreqsHz: Set<Long> = emptySet(),
        /** Optional tag/name for this segment, surfaced in [ScanHit.label]. */
        val label: String = ""
    )

    data class ScanStatus(
        val state: ScanState = ScanState.IDLE,
        val currentFreqHz: Long = 0L,
        val activeFreqHz: Long = 0L,
        val signalDb: Float = -120f,
        val noiseFloorDb: Float = -120f,
        val hitsFound: Int = 0,
        val progress: Float = 0f,
        val channelsPerSecond: Float = 0f,
        val segmentIndex: Int = 0,
        val segmentCount: Int = 1,
        val lastHitSource: HitSource? = null
    )

    /** A single confirmed hit — richer than a bare Long so the UI/log can show
     *  useful context without re-deriving it. */
    data class ScanHit(
        val freqHz: Long,
        val signalDb: Float,
        val noiseFloorDb: Float,
        val mode: DemodMode,
        val source: HitSource,
        val timestampMs: Long = System.currentTimeMillis(),
        val label: String = ""
    )

    /** One wideband FFT capture, centred on [centerFreqHz], spanning
     *  [sampleRate] Hz of instantaneous bandwidth. [noiseFloorDb] is a single
     *  robust estimate for the whole block (see [estimateBlockNoiseFloor]) —
     *  not a per-bin trace. */
    private data class FftBlock(
        val centerFreqHz: Long,
        val sampleRate: Int,
        val spectrumDb: FloatArray,
        val noiseFloorDb: Float
    )

    /** A signal detected inside a single [FftBlock] — one contiguous run of
     *  bins above threshold, reduced to a single centre frequency + power. */
    private data class DetectedSignal(
        val freqHz: Long,
        val signalDb: Float,
        val noiseFloorDb: Float
    )

    /**
     * One row of the tabulated channel view — modeled on the persistent,
     * continuously-updated channel table SDRangel's Frequency Scanner
     * plugin shows (https://github.com/f4exb/sdrangel): one row per
     * configured channel, refreshed in place with its latest measured
     * power/floor and running hit history every time that channel is
     * revisited, rather than an append-only log of past hits.
     */
    data class ChannelEntry(
        val freqHz: Long,
        val label: String = "",
        val lastPowerDb: Float = -200f,
        val lastNoiseFloorDb: Float = -200f,
        /** True if the most recent measurement of this channel was above
         *  the scan's threshold (i.e. currently "active"/squelch-open). */
        val active: Boolean = false,
        /** Total number of times this channel has measured above threshold
         *  since the table was (re)built. */
        val hitCount: Int = 0,
        /** Epoch-ms of the last time this channel was above threshold, or 0
         *  if it never has been. */
        val lastActiveMs: Long = 0L,
        /** Epoch-ms of the last time this channel was measured at all
         *  (hit or not) — lets a UI show how fresh a "silent" reading is. */
        val lastUpdatedMs: Long = 0L
    )

    private val _status = MutableStateFlow(ScanStatus())
    val status: StateFlow<ScanStatus> = _status.asStateFlow()

    private val _activeFrequencies = MutableSharedFlow<Long>(extraBufferCapacity = 32)
    val activeFrequencies: SharedFlow<Long> = _activeFrequencies.asSharedFlow()

    /** Richer hit stream — prefer this over [activeFrequencies] in new code. */
    private val _hits = MutableSharedFlow<ScanHit>(extraBufferCapacity = 64)
    val hits: SharedFlow<ScanHit> = _hits.asSharedFlow()

    /**
     * The tabulated channel view: one row per channel, continuously updated
     * in place as the scan revisits it — SDRangel's Frequency Scanner shows
     * exactly this (a persistent channel list with live power/activity
     * columns) rather than only a scrolling hit log. For a channelized scan
     * or memory scan, the table is pre-populated with every configured
     * channel up front (so silent channels show up too, at -200 dB, until
     * first measured); for search mode (no fixed channel plan) rows are
     * added dynamically as signals are actually found.
     */
    private val _channelTable = MutableStateFlow<List<ChannelEntry>>(emptyList())
    val channelTable: StateFlow<List<ChannelEntry>> = _channelTable.asStateFlow()

    /** freqHz -> row, kept alongside [_channelTable] so single-row updates
     *  don't require rescanning/rebuilding the whole emitted list's lookup
     *  each time. Only ever touched from the scanner's own coroutine. */
    private var channelRows = LinkedHashMap<Long, ChannelEntry>()

    private fun publishChannelTable() {
        _channelTable.value = channelRows.values.sortedBy { it.freqHz }
    }

    /** (Re)initialize the table with every channel on [freqs], all starting
     *  in the "never measured" state. Used when a channel plan (sweep
     *  segment or memory list) is known ahead of time. */
    private fun initChannelTable(freqs: List<Long>, label: String = "") {
        channelRows = LinkedHashMap()
        for (f in freqs.distinct().take(MAX_CHANNEL_TABLE_ROWS)) {
            channelRows[f] = ChannelEntry(freqHz = f, label = label)
        }
        publishChannelTable()
    }

    /** Enumerate every channel from [config]'s startFreqHz to stopFreqHz at
     *  its stepHz spacing, capped at [MAX_CHANNEL_TABLE_ROWS] total so a
     *  huge range with a tiny step can't build an unbounded table. */
    private fun channelGrid(config: ScanConfig, budget: Int): List<Long> {
        if (config.stepHz <= 0 || budget <= 0) return emptyList()
        val freqs = mutableListOf<Long>()
        var f = config.startFreqHz
        while (f <= config.stopFreqHz && freqs.size < budget) {
            freqs += f
            f += config.stepHz
        }
        return freqs
    }

    /** Insert-or-update a single row after actually measuring [freqHz].
     *  [isHit] marks whether this measurement was above threshold — used to
     *  bump the running hit counter and "last active" time, matching how
     *  SDRangel's table distinguishes a channel that's currently open from
     *  one that's merely been measured. New rows are added (up to the cap)
     *  when the channel wasn't already in the table, which is how search
     *  mode's table grows as unknown signals are found. */
    private fun upsertChannelRow(freqHz: Long, powerDb: Float, floorDb: Float, isHit: Boolean, label: String = "") {
        val now = System.currentTimeMillis()
        val existing = channelRows[freqHz]
        if (existing == null && channelRows.size >= MAX_CHANNEL_TABLE_ROWS) return
        channelRows[freqHz] = ChannelEntry(
            freqHz = freqHz,
            label = if (existing != null && existing.label.isNotEmpty()) existing.label else label,
            lastPowerDb = powerDb,
            lastNoiseFloorDb = floorDb,
            active = isHit,
            hitCount = (existing?.hitCount ?: 0) + if (isHit) 1 else 0,
            lastActiveMs = if (isHit) now else (existing?.lastActiveMs ?: 0L),
            lastUpdatedMs = now
        )
        publishChannelTable()
    }

    /** After a wideband capture, refresh every pre-populated channel row
     *  that falls inside this block's captured bandwidth with its current
     *  reading (hit or not) — the channelized-scan equivalent of
     *  [upsertChannelRow], done in bulk from one FFT rather than one retune
     *  per channel. Channels outside this particular block are left as they
     *  were until a later hop covers them. */
    private fun updateChannelTableFromBlock(block: FftBlock, detectedFreqs: Set<Long>) {
        if (channelRows.isEmpty()) return
        val n = block.spectrumDb.size
        if (n == 0) return
        val hzPerBin = block.sampleRate.toDouble() / n
        val halfSpan = block.sampleRate / 2
        val loFreq = block.centerFreqHz - halfSpan
        val hiFreq = block.centerFreqHz + halfSpan
        val now = System.currentTimeMillis()
        var changed = false
        for ((freq, row) in channelRows) {
            if (freq < loFreq || freq > hiFreq) continue
            val binIdx = (n / 2 + ((freq - block.centerFreqHz) / hzPerBin)).roundToInt().coerceIn(0, n - 1)
            val db = block.spectrumDb[binIdx]
            val isHit = freq in detectedFreqs
            channelRows[freq] = row.copy(
                lastPowerDb = db,
                lastNoiseFloorDb = block.noiseFloorDb,
                active = isHit,
                hitCount = row.hitCount + if (isHit) 1 else 0,
                lastActiveMs = if (isHit) now else row.lastActiveMs,
                lastUpdatedMs = now
            )
            changed = true
        }
        if (changed) publishChannelTable()
    }

    /** Per-frequency hit counters, most-recently-seen ordering, bounded size.
     *  Exposed read-only so a "busiest channels" view can be built without
     *  the caller having to maintain its own aggregation. */
    private val hitCounts = object : LinkedHashMap<Long, Int>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Int>?): Boolean =
            size > MAX_TRACKED_FREQS
    }
    private val hitCountsLock = Any()

    fun hitCountFor(freqHz: Long): Int = synchronized(hitCountsLock) { hitCounts[freqHz] ?: 0 }

    fun topFrequencies(limit: Int = 10): List<Pair<Long, Int>> = synchronized(hitCountsLock) {
        hitCounts.entries.sortedByDescending { it.value }.take(limit).map { it.key to it.value }
    }

    /** Temporary lockout: freq -> epoch-ms until which it should be skipped. */
    private val temporaryLockout = TreeMap<Long, Long>()

    /** Adaptive noise-floor estimate, tracked independently since it is only
     *  meaningful once several samples have been taken. */
    @Volatile private var noiseFloorEstimate: Float = -120f
    @Volatile private var noiseFloorInitialized = false

    /** The scanner's own FFT engine — captures and analyzes its own IQ blocks
     *  rather than trusting an external, possibly stale signal-level reading. */
    private val fftEngine = FftEngine(SCAN_FFT_SIZE)

    private fun updateNoiseFloor(sampleDb: Float, looksLikeHit: Boolean) {
        // Don't let genuine signals drag the floor estimate upward — only
        // adapt on samples that don't already look like a hit.
        if (looksLikeHit) return
        noiseFloorEstimate = if (!noiseFloorInitialized) {
            noiseFloorInitialized = true
            sampleDb
        } else {
            ((1 - NOISE_FLOOR_ALPHA) * noiseFloorEstimate + NOISE_FLOOR_ALPHA * sampleDb).toFloat()
        }
    }

    private fun effectiveThreshold(config: ScanConfig): Float =
        if (config.adaptiveSquelch) noiseFloorEstimate + config.adaptiveMarginDb
        else config.squelchDb

    /** Fallback dBFS reading when a fresh capture isn't available (e.g. the
     *  source stalled) — better to show *something* than to freeze the UI. */
    private fun fallbackSignalLevel(): Float =
        signalLevelProvider?.invoke()
            ?: (device as? RtlSdrDeviceSource)?.device?.readSignalStrength()
            ?: device.statusFlow.value.signalStrengthDb

    /**
     * Retune to [centerFreqHz], let the PLL settle, then capture
     * [SCAN_BLOCK_AVERAGES] fresh wideband IQ frames and average their power
     * spectra (in linear domain) into a single [FftBlock].
     *
     * The first buffer observed after a retune is discarded: it may have
     * been captured by the source mid-retune (partially at the old
     * frequency) or simply be sitting queued from before the retune took
     * effect, so it cannot be trusted to reflect [centerFreqHz]. Only the
     * buffers after that are analyzed.
     *
     * Averaging several frames matters just as much for *detection* as it
     * does for the noise floor: a single raw frame lets ordinary bin-to-bin
     * noise wobble on a real carrier's spectral skirt dip below and back
     * above threshold from one bin to the next, which [detectSignals] would
     * otherwise report as several separate broken-up "signals" scattered
     * across neighbouring channels instead of the one real transmission
     * (this is what produced spurious extra hits on channels adjacent to a
     * genuine strong carrier). Averaging in linear power -- not dB -- before
     * thresholding smooths that wobble the same way real noise averaging
     * does, without blurring a genuine signal's own centre frequency, since
     * every frame here is captured at the same, unchanging [centerFreqHz].
     */
    private suspend fun captureBlock(centerFreqHz: Long): FftBlock? {
        device.setCenterFrequency(centerFreqHz)
        onRetune?.invoke(centerFreqHz)
        delay(RETUNE_SETTLE_MS)
        return try {
            withTimeoutOrNull(IQ_CAPTURE_TIMEOUT_MS * (SCAN_BLOCK_AVERAGES + 1)) {
                device.iqFlow.first() // discard: may predate the retune settling
                var accum: DoubleArray? = null
                var framesAveraged = 0
                var sampleRate = device.getSampleRate()
                repeat(SCAN_BLOCK_AVERAGES) {
                    val raw = device.iqFlow.first()
                    val spectrum = fftEngine.processUint8(raw)
                    if (accum == null) accum = DoubleArray(spectrum.size)
                    val acc = accum!!
                    for (i in spectrum.indices) acc[i] += 10.0.pow(spectrum[i] / 10.0)
                    framesAveraged++
                    sampleRate = device.getSampleRate()
                }
                val acc = accum ?: return@withTimeoutOrNull null
                val n = acc.size
                val averaged = FloatArray(n) { i -> (10.0 * log10(acc[i] / framesAveraged + 1e-30)).toFloat() }
                val floor = estimateBlockNoiseFloor(averaged)
                FftBlock(centerFreqHz, sampleRate, averaged, floor)
            }
        } catch (e: Exception) {
            Log.w(TAG, "capture failed @ $centerFreqHz Hz: ${e.message}")
            null
        }
    }

    /**
     * Estimate the noise floor for a single wideband capture from the
     * capture itself, rather than from a tracker built up across many
     * frames at the same frequency.
     *
     * [FftEngine.getNoiseFloor] is a minimum-statistics tracker: it falls
     * instantly to a new per-bin minimum and only creeps back up a fraction
     * of a dB per *frame*. That works well for something sitting on one
     * frequency continuously (a live spectrum display), but a hopping
     * scanner only ever calls it once per frequency before moving on — so
     * it never converges and stays pinned near its post-reset floor for the
     * whole scan, making every bin look like a hit.
     *
     * Instead, use the *median* power across the block's usable bins. Real
     * transmissions occupy a minority of the spectrum even in a busy band,
     * so the median tracks the true noise floor and is unaffected by however
     * many strong signals happen to be present in this particular capture —
     * unlike a mean, which a single strong signal can drag upward.
     */
    private fun estimateBlockNoiseFloor(spectrumDb: FloatArray): Float {
        val n = spectrumDb.size
        val edgeBins = (n * EDGE_FRACTION).toInt().coerceAtLeast(1)
        if (n <= edgeBins * 2) return spectrumDb.minOrNull() ?: -120f
        val center = n / 2
        // Exclude both the capture's edges (spectral leakage / roll-off) and
        // the DC guard band (hardware DC-offset/LO-leakage spike) from the
        // floor estimate -- neither reflects real RF noise, and left in, the
        // DC spike in particular biases the median upward every single
        // capture regardless of actual band conditions, which is exactly the
        // kind of "noise floor magnified" symptom Auto dB Range surfaced.
        val usable = ArrayList<Float>(n)
        for (i in edgeBins until n - edgeBins) {
            if (isDcGuardBin(i, center)) continue
            usable += spectrumDb[i]
        }
        if (usable.isEmpty()) return spectrumDb.minOrNull() ?: -120f
        usable.sort()
        return usable[usable.size / 2]
    }

    /** True if bin [i] falls within [DC_GUARD_BINS] of the capture's centre
     *  bin ([center]) -- i.e. inside the DC offset / LO-leakage guard band
     *  described on [DC_GUARD_BINS]. Shared by every place that reads bins
     *  near the tuned frequency (noise floor estimate, narrowband power,
     *  and multi-signal detection) so none of them can mistake the hardware
     *  DC spike for a real signal or fold it into a noise estimate. */
    private fun isDcGuardBin(i: Int, center: Int): Boolean =
        i in (center - DC_GUARD_BINS)..(center + DC_GUARD_BINS)

    /** Average power (dBFS) over a ~[NARROWBAND_HZ]-wide window of bins
     *  straddling the tuned (DC) centre bin of a capture — i.e. "how strong
     *  is whatever is on the frequency we're actually tuned to right now",
     *  as opposed to the peak anywhere across the whole captured window. */
    private fun narrowbandDb(spectrumDb: FloatArray, sampleRate: Int): Float {
        val n = spectrumDb.size
        if (n == 0 || sampleRate <= 0) return -200f
        val hzPerBin = sampleRate.toDouble() / n
        val halfBins = max(1, (NARROWBAND_HZ / hzPerBin / 2.0).toInt())
        val center = n / 2
        val lo = (center - halfBins).coerceAtLeast(0)
        val hi = (center + halfBins).coerceAtMost(n - 1)
        var sumLin = 0.0
        var count = 0
        for (i in lo..hi) {
            // Skip the DC guard band -- see DC_GUARD_BINS. A narrowband
            // reading centred exactly on the tuned frequency is precisely
            // where the hardware DC spike sits, so without this guard this
            // reading (used by memory scan / priority poll / search / hold)
            // reports the spike's power instead of the real channel's,
            // producing a "hit" on whatever channel happens to be tuned
            // regardless of whether anything is actually transmitting there.
            if (isDcGuardBin(i, center)) continue
            sumLin += 10.0.pow(spectrumDb[i] / 10.0)
            count++
        }
        if (count == 0) {
            // The whole narrowband window was inside the DC guard (very wide
            // guard relative to channel spacing) -- fall back to the single
            // nearest bin just outside the guard so we still return a real
            // reading instead of silently reporting nothing.
            val fallbackBin = (center + DC_GUARD_BINS + 1).coerceAtMost(n - 1)
            return spectrumDb[fallbackBin]
        }
        return (10.0 * log10(sumLin / count)).toFloat()
    }

    /** Tune to a single frequency and take [samples] rapid narrowband
     *  readings, returning the max (peak-hold). Used by the memory scan,
     *  priority poll, and search mode, where a single known channel — not a
     *  whole capture's worth of bandwidth — is what's being evaluated. */
    private suspend fun sampleSignal(freqHz: Long, totalDwellMs: Long, samples: Int): Float {
        device.setCenterFrequency(freqHz)
        onRetune?.invoke(freqHz)
        delay(RETUNE_SETTLE_MS)
        val iterations = max(1, samples)
        val perIterationMs = max(20L, totalDwellMs / iterations)
        var peak = -200f
        repeat(iterations) {
            val raw = withTimeoutOrNull(perIterationMs) { device.iqFlow.first() }
            if (raw != null) {
                val spectrum = fftEngine.processUint8(raw)
                val db = narrowbandDb(spectrum, device.getSampleRate())
                if (db > peak) peak = db
            } else {
                delay(perIterationMs)
            }
        }
        // Only fall back to a caller-supplied / status-based reading if we
        // couldn't get a single real capture (source stalled/disconnected).
        if (peak <= -199f) peak = fallbackSignalLevel()
        return peak
    }

    /**
     * Standard scanner "hold on signal" behaviour, learned from how
     * SDRangel's channel demodulators work: once a channel is demodulating a
     * real signal, it stays locked to that channel rather than being torn
     * down on a fixed timer — the channel only releases when the signal
     * itself goes away. Applied here to the scan sweep: park the capture on
     * [freqHz] and keep re-sampling its level (respecting pause) until it
     * drops back to/under [config]'s threshold, i.e. carrier loss, then
     * return so the sweep can resume. Runs indefinitely while the carrier is
     * present -- exactly the "stay until the transmission ends" behaviour a
     * physical scanner's Hold/Priority mode gives you, as opposed to always
     * hopping off after a fixed dwell regardless of whether the conversation
     * is still going.
     */
    private suspend fun holdUntilCarrierLoss(freqHz: Long, config: ScanConfig) {
        var belowThresholdStreak = 0
        while (currentCoroutineContext().isActive) {
            if (_status.value.state == ScanState.PAUSED) { delay(100); continue }
            val db = sampleSignal(freqHz, HOLD_POLL_INTERVAL_MS, samples = 1)
            val aboveThreshold = db > effectiveThreshold(config)
            updateNoiseFloor(db, looksLikeHit = aboveThreshold)
            _status.value = _status.value.copy(
                activeFreqHz = freqHz, signalDb = db, noiseFloorDb = noiseFloorEstimate
            )
            if (aboveThreshold) {
                belowThresholdStreak = 0
            } else {
                belowThresholdStreak++
                // Require several consecutive below-threshold polls, not just
                // one, before treating this as real carrier loss -- a single
                // dip is normal mid-transmission (fading, momentary deviation
                // dropout on FM) and releasing on it let the sweep restart
                // and re-detect the *same* still-active transmitter's
                // spectral skirt moments later as spurious "new" hits on
                // neighbouring channels.
                if (belowThresholdStreak >= HOLD_RELEASE_DEBOUNCE_POLLS) return
            }
        }
    }

    private var scanJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private fun isLockedOut(freqHz: Long, config: ScanConfig): Boolean {
        if (freqHz in config.lockoutFreqsHz) return true
        val until = temporaryLockout[freqHz] ?: return false
        return if (System.currentTimeMillis() < until) true else {
            temporaryLockout.remove(freqHz); false
        }
    }

    private fun registerHit(hit: ScanHit) {
        synchronized(hitCountsLock) { hitCounts[hit.freqHz] = (hitCounts[hit.freqHz] ?: 0) + 1 }
        _activeFrequencies.tryEmit(hit.freqHz)
        _hits.tryEmit(hit)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Single-segment sweep (backward-compatible entry point)
    // ─────────────────────────────────────────────────────────────────────────

    fun startScan(config: ScanConfig) = startMultiScan(listOf(config))

    /**
     * Sweep several band segments back-to-back as one logical scan, each with
     * its own step size / mode / squelch — e.g. "2m Ham" then "70cm Ham" in a
     * single Start press, matching how dedicated scanners let you combine
     * multiple banks into one scan pass.
     *
     * Each segment is covered by a series of wideband FFT captures (see
     * [captureBlock] / [detectSignals]) hopped across the segment's range,
     * so multiple simultaneous transmissions inside the same capture are all
     * detected and reported in one pass, faithfully matching upstream
     * rtl-sdr-scanner-cpp's core scanning behaviour.
     */
    fun startMultiScan(configs: List<ScanConfig>) {
        require(configs.isNotEmpty()) { "startMultiScan requires at least one ScanConfig" }
        if (scanJob?.isActive == true) stopScan()
        noiseFloorInitialized = false
        fftEngine.resetNoiseFloor()
        // Pre-populate the channel table from the configured plan(s), split
        // evenly across segments so one huge segment can't starve the
        // others out of the row budget.
        channelRows = LinkedHashMap()
        val perSegmentBudget = (MAX_CHANNEL_TABLE_ROWS / configs.size).coerceAtLeast(1)
        for (config in configs) {
            for (f in channelGrid(config, perSegmentBudget)) {
                channelRows[f] = ChannelEntry(freqHz = f, label = config.label)
            }
        }
        publishChannelTable()
        scanJob = scope.launch { runMultiScan(configs) }
        Log.i(TAG, "Scan started: ${configs.size} segment(s)")
    }

    fun stopScan() {
        scanJob?.cancel()
        _status.value = _status.value.copy(state = ScanState.STOPPED)
        Log.i(TAG, "Scan stopped")
    }

    fun pauseScan() {
        _status.value = _status.value.copy(state = ScanState.PAUSED)
    }

    fun resumeScan() {
        _status.value = _status.value.copy(state = ScanState.SCANNING)
    }

    /** Explicitly lock out a frequency for [durationMs] (default: effectively
     *  "until [clearLockout] is called"). */
    fun lockout(freqHz: Long, durationMs: Long = Long.MAX_VALUE / 2) {
        temporaryLockout[freqHz] = System.currentTimeMillis() + durationMs
    }

    fun clearLockout(freqHz: Long) { temporaryLockout.remove(freqHz) }
    fun clearAllLockouts() = temporaryLockout.clear()

    /**
     * Group every contiguous run of FFT bins sitting above the effective
     * threshold into a single [DetectedSignal], the same way upstream's
     * scanner reduces a block of spectrum bins to a set of discrete
     * transmissions. The frequency reported for each run is the
     * power-weighted centroid of its bins (more accurate than just the peak
     * bin when a signal spans several bins), snapped to the nearest
     * [ScanConfig.stepHz] multiple so repeated detections of the same real
     * transmission (bin-to-bin wobble from noise) consistently map to the
     * same key for lockout / hit-counting purposes.
     */
    private fun detectSignals(block: FftBlock, config: ScanConfig): List<DetectedSignal> {
        val spectrum = block.spectrumDb
        val floor = block.noiseFloorDb
        val n = spectrum.size
        if (n == 0) return emptyList()
        val hzPerBin = block.sampleRate.toDouble() / n
        val edgeBins = (n * EDGE_FRACTION).toInt().coerceAtLeast(1)
        val marginDb = config.adaptiveMarginDb
        // Absolute-threshold mode still respects the user's squelchDb
        // directly; adaptive mode is floor + margin, using this block's own
        // robust noise-floor estimate (see estimateBlockNoiseFloor) rather
        // than a tracker that can't keep up with a hopping scan.
        val threshold = if (config.adaptiveSquelch) floor + marginDb else config.squelchDb

        val results = mutableListOf<DetectedSignal>()
        val center = n / 2
        var i = edgeBins
        val hi = n - edgeBins
        while (i < hi) {
            // Never let the DC guard band start (or continue) a run -- see
            // DC_GUARD_BINS. Without this, the hardware DC spike sitting
            // exactly at the tuned centre frequency gets grouped as its own
            // "signal" on whatever channel the scanner happens to be
            // centred on for that capture, reported as a hit every time
            // regardless of real RF activity.
            if (isDcGuardBin(i, center) || spectrum[i] <= threshold) { i++; continue }
            var sumLin = 0.0
            var sumFreqWeighted = 0.0
            var peakDb = -200f
            while (i < hi && spectrum[i] > threshold && !isDcGuardBin(i, center)) {
                val p = 10.0.pow(spectrum[i] / 10.0)
                val freqOffsetHz = (i - n / 2) * hzPerBin
                sumLin += p
                sumFreqWeighted += p * freqOffsetHz
                if (spectrum[i] > peakDb) peakDb = spectrum[i]
                i++
            }
            val centroidOffsetHz = if (sumLin > 0) sumFreqWeighted / sumLin else 0.0
            val rawFreqHz = block.centerFreqHz + centroidOffsetHz.roundToLong()

            // Snap to the channel PLAN's own grid, not to multiples of
            // stepHz from absolute 0 Hz. A plan like PMR446 (channels at
            // 446.00625 MHz + n*12.5 kHz) is not aligned to 12.5 kHz from
            // 0 Hz, so rounding raw frequencies to the nearest global
            // multiple of stepHz put hits at the wrong frequencies entirely
            // — outside the plan's actual channels. Anchoring the rounding
            // at config.startFreqHz (the plan's own origin) puts every
            // detection on one of the plan's real channel centres instead.
            val stepHz = config.stepHz
            if (stepHz <= 0) {
                results += DetectedSignal(rawFreqHz, peakDb, floor)
                continue
            }
            val channelIndex = ((rawFreqHz - config.startFreqHz).toDouble() / stepHz).roundToLong()
            val snappedFreqHz = config.startFreqHz + channelIndex * stepHz
            // A genuine channel hit should land within half a channel of a
            // grid point; anything further off (e.g. a birdie or an
            // out-of-plan broadcast signal caught at the edge of the
            // capture) is not actually one of this plan's channels, so
            // don't misreport it as one.
            if (kotlin.math.abs(rawFreqHz - snappedFreqHz) > stepHz / 2) { continue }
            results += DetectedSignal(snappedFreqHz, peakDb, floor)
        }
        return results
    }

    private suspend fun runMultiScan(configs: List<ScanConfig>) {
        var hitsFound = 0
        var blocksVisited = 0
        var priorityCounter = 0
        var windowStart = System.currentTimeMillis()
        var windowCount = 0
        var cps = 0f

        _status.value = ScanStatus(
            state = ScanState.SCANNING,
            currentFreqHz = configs.first().startFreqHz,
            segmentCount = configs.size
        )

        // A scanner scans continuously: keep cycling through every segment,
        // each swept start-to-stop, forever — until stopScan() cancels this
        // coroutine. There is no "done" state short of that; the previous
        // version incorrectly stopped after a single pass of one segment.
        while (currentCoroutineContext().isActive) {
        for ((segIdx, config) in configs.withIndex()) {
            val range = (config.stopFreqHz - config.startFreqHz).coerceAtLeast(1)
            val sampleRate = device.getSampleRate().takeIf { it > 0 } ?: 2_048_000
            val hopHz = max(config.stepHz, (sampleRate * HOP_FRACTION).toLong())

            // Start the first capture centred half a bandwidth into the
            // range so the low edge of the range is still inside the block.
            var centerFreq = min(config.startFreqHz + sampleRate / 2, config.stopFreqHz)
            var wrapped = false

            while (currentCoroutineContext().isActive && !wrapped) {
                if (_status.value.state == ScanState.PAUSED) { delay(100); continue }

                val block = captureBlock(centerFreq)
                var currentSignalDb = fallbackSignalLevel()

                if (block != null) {
                    // Track the overall noise floor from this block's own
                    // robust estimate so the UI's "floor" reading and any
                    // adaptive-squelch decisions stay grounded in real data.
                    val blockFloorAvg = block.noiseFloorDb
                    updateNoiseFloor(blockFloorAvg, looksLikeHit = false)

                    val detections = detectSignals(block, config)
                        .filter { it.freqHz in config.startFreqHz..config.stopFreqHz }
                        .filter { !isLockedOut(it.freqHz, config) }

                    updateChannelTableFromBlock(block, detections.map { it.freqHz }.toSet())

                    currentSignalDb = detections.maxOfOrNull { it.signalDb } ?: blockFloorAvg

                    for (d in detections) {
                        hitsFound++
                        val hit = ScanHit(
                            freqHz = d.freqHz, signalDb = d.signalDb,
                            noiseFloorDb = d.noiseFloorDb, mode = config.mode,
                            source = HitSource.SWEEP, label = config.label
                        )
                        registerHit(hit)
                        _status.value = _status.value.copy(
                            activeFreqHz = d.freqHz, hitsFound = hitsFound,
                            lastHitSource = HitSource.SWEEP
                        )
                        Log.d(TAG, "Hit @ ${d.freqHz / 1e6} MHz: ${d.signalDb} dB (floor ${d.noiseFloorDb})")
                        temporaryLockout[d.freqHz] = System.currentTimeMillis() + config.skipActiveMs
                    }

                    if (detections.isNotEmpty()) {
                        if (config.holdOnSignal) {
                            // Standard scanner behaviour: stay parked on the
                            // strongest detection in this capture until its
                            // carrier actually drops, rather than always
                            // waiting a fixed resumeTimeMs.
                            holdUntilCarrierLoss(
                                detections.maxByOrNull { it.signalDb }!!.freqHz, config
                            )
                        } else {
                            // Give the user time to hear/see the hit(s) before the
                            // sweep moves the capture window on, same intent as
                            // the old per-channel resumeTimeMs pause.
                            delay(config.resumeTimeMs)
                        }
                    } else {
                        delay(max(0L, config.dwellTimeMs - RETUNE_SETTLE_MS))
                    }
                } else {
                    delay(config.dwellTimeMs)
                }

                blocksVisited++
                windowCount++
                val now = System.currentTimeMillis()
                if (now - windowStart >= 500) {
                    cps = windowCount * 1000f / (now - windowStart)
                    windowStart = now; windowCount = 0
                }

                val progress = ((centerFreq - config.startFreqHz).toFloat() / range).coerceIn(0f, 1f)
                _status.value = _status.value.copy(
                    currentFreqHz = centerFreq,
                    signalDb = currentSignalDb,
                    noiseFloorDb = noiseFloorEstimate,
                    progress = progress,
                    hitsFound = hitsFound,
                    channelsPerSecond = cps,
                    segmentIndex = segIdx
                )

                // Priority channel interrupt — polled on a duty cycle
                // regardless of what this capture found.
                if (config.priorityFreqsHz.isNotEmpty() &&
                    ++priorityCounter % max(1, config.priorityIntervalChannels) == 0
                ) {
                    pollPriority(config)?.let { hitsFound++ }
                }

                val next = if (config.scanUp) centerFreq + hopHz else centerFreq - hopHz
                if (config.scanUp) {
                    if (centerFreq >= config.stopFreqHz) { wrapped = true } else {
                        centerFreq = min(next, config.stopFreqHz)
                    }
                } else {
                    if (centerFreq <= config.startFreqHz) { wrapped = true } else {
                        centerFreq = max(next, config.startFreqHz)
                    }
                }
            }
        }
        } // outer continuous-cycle while
        // Only reached if the coroutine was cancelled (stopScan()); the
        // cancellation itself already unwinds us out of the delay()/loop
        // above, so this simply reflects the final state for anyone still
        // observing `status` after stop.
        _status.value = _status.value.copy(state = ScanState.STOPPED)
    }

    /** Quick peek at every priority frequency; returns non-null if any produced
     *  a hit (used only so the caller can bump its counter). */
    private suspend fun pollPriority(config: ScanConfig): Unit? {
        var any = false
        for (pf in config.priorityFreqsHz) {
            if (isLockedOut(pf, config)) continue
            val db = sampleSignal(pf, min(config.dwellTimeMs, 120L), samples = 1)
            val isHit = db > effectiveThreshold(config)
            upsertChannelRow(pf, db, noiseFloorEstimate, isHit, label = "priority")
            if (isHit) {
                any = true
                registerHit(
                    ScanHit(
                        freqHz = pf, signalDb = db, noiseFloorDb = noiseFloorEstimate,
                        mode = config.mode, source = HitSource.PRIORITY, label = "priority"
                    )
                )
                _status.value = _status.value.copy(
                    activeFreqHz = pf, lastHitSource = HitSource.PRIORITY
                )
                temporaryLockout[pf] = System.currentTimeMillis() + config.skipActiveMs
            }
        }
        return if (any) Unit else null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Memory / channel-list scan
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Scan a list of specific frequencies (memory scan) with the same
     * adaptive-squelch / peak-hold / priority-interrupt engine used for
     * sweeps. Each frequency is individually tuned and measured (a memory
     * list is, by definition, a set of specific known channels rather than a
     * contiguous band), using the same real, freshly-captured narrowband
     * reading as everything else in this class.
     */
    fun startMemoryScan(
        frequencies: List<Long>,
        squelchDb: Float,
        dwellTimeMs: Long,
        resumeTimeMs: Long,
        adaptiveSquelch: Boolean = false,
        adaptiveMarginDb: Float = DEFAULT_ADAPTIVE_MARGIN_DB,
        priorityFreqsHz: List<Long> = emptyList(),
        lockoutFreqsHz: Set<Long> = emptySet(),
        mode: DemodMode = DemodMode.NFM
    ) {
        if (frequencies.isEmpty()) return
        scanJob?.cancel()
        noiseFloorInitialized = false
        fftEngine.resetNoiseFloor()
        initChannelTable(frequencies, label = "memory")
        val pseudoConfig = ScanConfig(
            startFreqHz = frequencies.min(), stopFreqHz = frequencies.max(),
            squelchDb = squelchDb, adaptiveSquelch = adaptiveSquelch,
            adaptiveMarginDb = adaptiveMarginDb, dwellTimeMs = dwellTimeMs,
            resumeTimeMs = resumeTimeMs, mode = mode,
            priorityFreqsHz = priorityFreqsHz, lockoutFreqsHz = lockoutFreqsHz
        )
        scanJob = scope.launch {
            var idx = 0
            var hitsFound = 0
            var channelsVisited = 0
            _status.value = ScanStatus(state = ScanState.SCANNING, segmentCount = 1)

            while (isActive) {
                if (_status.value.state == ScanState.PAUSED) { delay(100); continue }

                val freq = frequencies[idx % frequencies.size]
                idx = (idx + 1) % frequencies.size

                if (isLockedOut(freq, pseudoConfig)) continue

                val sniffMs = max(MIN_SNIFF_DWELL_MS, (dwellTimeMs * SNIFF_DWELL_FRACTION).toLong())
                val sniffDb = sampleSignal(freq, sniffMs, samples = 1)
                val looksActive = sniffDb > effectiveThreshold(pseudoConfig)
                val finalDb = if (looksActive)
                    sampleSignal(freq, max(0L, dwellTimeMs - sniffMs), samples = SETTLE_SAMPLES)
                else sniffDb
                updateNoiseFloor(finalDb, looksLikeHit = looksActive)

                val isHit = finalDb > effectiveThreshold(pseudoConfig)
                upsertChannelRow(freq, finalDb, noiseFloorEstimate, isHit, label = "memory")

                channelsVisited++
                _status.value = _status.value.copy(
                    currentFreqHz = freq,
                    signalDb = finalDb,
                    noiseFloorDb = noiseFloorEstimate,
                    progress = (idx.toFloat() / frequencies.size),
                    hitsFound = hitsFound
                )

                if (finalDb > effectiveThreshold(pseudoConfig)) {
                    hitsFound++
                    registerHit(
                        ScanHit(
                            freqHz = freq, signalDb = finalDb, noiseFloorDb = noiseFloorEstimate,
                            mode = mode, source = HitSource.MEMORY
                        )
                    )
                    _status.value = _status.value.copy(
                        activeFreqHz = freq, hitsFound = hitsFound, lastHitSource = HitSource.MEMORY
                    )
                    if (pseudoConfig.holdOnSignal) {
                        holdUntilCarrierLoss(freq, pseudoConfig)
                    } else {
                        delay(resumeTimeMs)
                    }
                    temporaryLockout[freq] = System.currentTimeMillis() + pseudoConfig.skipActiveMs
                }

                if (priorityFreqsHz.isNotEmpty() && channelsVisited % DEFAULT_PRIORITY_INTERVAL == 0) {
                    pollPriority(pseudoConfig)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Wideband search mode — fast exhaustive sweep for finding unknown signals
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * A fast, wideband-block search across a range with no channelization
     * assumptions — useful for "what's actually on the air here" discovery
     * before setting up a channelized scan list. Reuses the same wideband
     * capture + multi-signal detection as [startScan].
     *
     * Respects the same squelch controls as [startScan]/[ScanConfig]: pass
     * [adaptiveSquelch] = false and a real [squelchDb] to use a fixed
     * absolute threshold (e.g. whatever the user typed into the scan tab's
     * Squelch field), or leave it true to gate on this capture's own
     * noise-floor + [marginDb] instead. Previously this always forced
     * adaptive squelch and ignored any squelch value the caller had, which
     * is why the search picked up noise regardless of the squelch setting.
     */
    fun startSearch(
        startFreqHz: Long,
        stopFreqHz: Long,
        stepHz: Long = 5_000L,
        dwellTimeMs: Long = 40L,
        squelchDb: Float = -100f,
        adaptiveSquelch: Boolean = false,
        marginDb: Float = DEFAULT_ADAPTIVE_MARGIN_DB,
        mode: DemodMode = DemodMode.NFM
    ) {
        if (scanJob?.isActive == true) stopScan()
        noiseFloorInitialized = false
        fftEngine.resetNoiseFloor()
        // Search has no fixed channel plan, so the table starts empty and
        // grows dynamically as real signals are found (see upsertChannelRow),
        // rather than being pre-populated like a channelized scan's table.
        channelRows = LinkedHashMap()
        publishChannelTable()
        val config = ScanConfig(
            startFreqHz = startFreqHz, stopFreqHz = stopFreqHz, stepHz = stepHz,
            squelchDb = squelchDb, adaptiveSquelch = adaptiveSquelch, adaptiveMarginDb = marginDb,
            dwellTimeMs = dwellTimeMs, resumeTimeMs = 400L, mode = mode, label = "search"
        )
        scanJob = scope.launch {
            val range = (config.stopFreqHz - config.startFreqHz).coerceAtLeast(1)
            val sampleRate = device.getSampleRate().takeIf { it > 0 } ?: 2_048_000
            val hopHz = max(config.stepHz, (sampleRate * HOP_FRACTION).toLong())
            var currentFreq = min(config.startFreqHz + sampleRate / 2, config.stopFreqHz)
            var hitsFound = 0
            _status.value = ScanStatus(state = ScanState.SCANNING, segmentCount = 1)

            while (isActive) {
                if (_status.value.state == ScanState.PAUSED) { delay(100); continue }

                val block = captureBlock(currentFreq)
                var signalDb = fallbackSignalLevel()

                if (block != null) {
                    val blockFloorAvg = block.noiseFloorDb
                    updateNoiseFloor(blockFloorAvg, looksLikeHit = false)

                    val detections = detectSignals(block, config)
                        .filter { it.freqHz in config.startFreqHz..config.stopFreqHz }
                    signalDb = detections.maxOfOrNull { it.signalDb } ?: blockFloorAvg

                    for (d in detections) {
                        hitsFound++
                        upsertChannelRow(d.freqHz, d.signalDb, d.noiseFloorDb, isHit = true, label = "search")
                        registerHit(
                            ScanHit(
                                freqHz = d.freqHz, signalDb = d.signalDb,
                                noiseFloorDb = d.noiseFloorDb, mode = mode, source = HitSource.SEARCH
                            )
                        )
                        _status.value = _status.value.copy(
                            activeFreqHz = d.freqHz, hitsFound = hitsFound, lastHitSource = HitSource.SEARCH
                        )
                    }
                }

                val progress = ((currentFreq - config.startFreqHz).toFloat() / range).coerceIn(0f, 1f)
                _status.value = _status.value.copy(
                    currentFreqHz = currentFreq, signalDb = signalDb,
                    noiseFloorDb = noiseFloorEstimate, progress = progress, hitsFound = hitsFound
                )

                delay(dwellTimeMs)

                // Continuous wrap-around — search mode runs until stopped.
                currentFreq += hopHz
                if (currentFreq > config.stopFreqHz) {
                    currentFreq = min(config.startFreqHz + sampleRate / 2, config.stopFreqHz)
                }
            }
        }
    }

    fun isScanning(): Boolean = scanJob?.isActive == true

    fun destroy() {
        scanJob?.cancel()
        scope.cancel()
    }
}
