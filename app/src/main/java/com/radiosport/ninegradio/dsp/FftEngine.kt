package com.radiosport.ninegradio.dsp

import kotlin.math.*

/**
 * Power-of-2 FFT engine with windowing and magnitude calculation.
 * Used for spectrum display and waterfall.
 *
 * Advanced features added:
 *  - Frame averaging (N-power-spectrum average for SNR improvement)
 *  - Per-bin noise floor estimation (minimum-statistics tracker)
 *  - Per-bin SNR map (smoothed − noiseFloor)
 *  - Configurable peak decay rate
 *  - Adjustable Kaiser beta parameter
 *  - Nuttall 4-term window
 *  - Top-N peak finder with minimum bin separation
 *
 * Thread safety: public entry points (processUint8, processFloat, setFftSize,
 * setWindowType) are all @Synchronized so the DSP coroutine and the main thread
 * can safely call them concurrently without data races on the internal buffers.
 */
class FftEngine(fftSize: Int = 2048) {

    var fftSize: Int = fftSize
        private set

    /**
     * FFT decimation factor — must be a power of 2 in [1, 64].
     * 1 = off (full bandwidth), 2/4/8/16/32/64 = reduced bandwidth.
     */
    @Volatile var decimationFactor: Int = 1
        set(value) {
            field = when (value) {
                1, 2, 4, 8, 16, 32, 64 -> value
                else -> 1
            }
        }

    enum class WindowType {
        RECTANGULAR, HANN, HAMMING, BLACKMAN, BLACKMAN_HARRIS, FLAT_TOP, KAISER, NUTTALL
    }

    private var windowType = WindowType.BLACKMAN_HARRIS
    private var window = buildWindow(fftSize, windowType)
    private var windowPower = window.sumOf { it * it.toDouble() }.toFloat()
    private var windowSum   = window.sumOf { it.toDouble() }.toFloat()

    /** Beta parameter for Kaiser window (higher = more sidelobe suppression). Range 2..20. */
    @Volatile var kaiserBeta: Double = 6.0
        set(value) { field = value.coerceIn(1.0, 20.0) }

    // Output magnitude in dBFS
    private var smoothed = FloatArray(fftSize) { -100f }

    // Scratch buffers reused every processUint8/processFloat call
    private var scratchRe     = DoubleArray(fftSize)
    private var scratchIm     = DoubleArray(fftSize)
    private var scratchResult = FloatArray(fftSize)

    // Smoothing factor (0 = instant / all-new, 1 = max smooth / all-old)
    @Volatile var smoothingAlpha = 0.3f

    @Volatile var lastPeakDb: Float = -200f
        private set

    // Peak hold
    private var peakHold = FloatArray(fftSize) { -200f }

    /**
     * Per-frame peak decay in dB (applied each frame when no new maximum is found).
     * Default 0.002 dB/frame ≈ 6 dB/sec at 30 fps.  Increase for faster fall.
     */
    @Volatile var peakDecayRate: Float = 0.002f

    private val peakHoldWarmupBlocks = 5
    private var peakHoldWarmupRemaining = peakHoldWarmupBlocks

    @Volatile var showPeakHold = true

    // ── Frame averaging ────────────────────────────────────────────────────────
    // Accumulates N power spectra (in linear domain) before smoothing, which
    // reduces the noise floor by √N dB.  frameAveragingCount = 1 = disabled.
    private var frameAvgBuffer = DoubleArray(fftSize) { 0.0 }
    private var frameAvgAccum  = 0

    @Volatile var frameAveragingCount: Int = 1
        set(value) {
            field = value.coerceIn(1, 64)
            resetFrameAvg()
        }

    private fun resetFrameAvg() {
        synchronized(this) {
            frameAvgBuffer.fill(0.0)
            frameAvgAccum = 0
        }
    }

    // ── Per-bin noise floor estimation ────────────────────────────────────────
    // Minimum-statistics tracker: falls instantly to a new minimum, rises
    // very slowly back up.  Gives a reliable per-bin noise floor estimate
    // that hews to the true noise even when strong signals are present.
    private var noiseFloorEst  = FloatArray(fftSize) { -150f }
    /** Noise floor rise rate in dB/frame (how fast it recovers after a signal disappears). */
    @Volatile var noiseFloorRiseRate: Float = 0.05f

    // ── Twiddle factors ───────────────────────────────────────────────────────
    private var twiddleRe = DoubleArray(0)
    private var twiddleIm = DoubleArray(0)

    init {
        buildTwiddleTable(fftSize)
    }

    private fun buildTwiddleTable(n: Int) {
        val total = if (n >= 2) n - 1 else 0
        val re = DoubleArray(total)
        val im = DoubleArray(total)
        var len = 2
        while (len <= n) {
            val half = len / 2
            val off  = half - 1
            for (k in 0 until half) {
                val angle = -2.0 * PI * k / len
                re[off + k] = cos(angle)
                im[off + k] = sin(angle)
            }
            len = len shl 1
        }
        twiddleRe = re
        twiddleIm = im
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    @Synchronized
    fun setWindowType(type: WindowType) {
        windowType  = type
        window      = buildWindow(fftSize, type)
        windowPower = window.sumOf { it * it.toDouble() }.toFloat()
        windowSum   = window.sumOf { it.toDouble() }.toFloat()
    }

    @Synchronized
    fun setFftSize(size: Int) {
        val n = size.coerceIn(256, 65536)
        if (n == fftSize) return
        fftSize = n
        window      = buildWindow(n, windowType)
        windowPower = window.sumOf { it * it.toDouble() }.toFloat()
        windowSum   = window.sumOf { it.toDouble() }.toFloat()
        smoothed      = FloatArray(n) { -100f }
        peakHold      = FloatArray(n) { -200f }
        peakHoldWarmupRemaining = peakHoldWarmupBlocks
        scratchRe     = DoubleArray(n)
        scratchIm     = DoubleArray(n)
        scratchResult = FloatArray(n)
        frameAvgBuffer = DoubleArray(n) { 0.0 }
        frameAvgAccum  = 0
        noiseFloorEst  = FloatArray(n) { -150f }
        buildTwiddleTable(n)
    }

    /**
     * Compute FFT magnitude spectrum from uint8 IQ data (RTL-SDR format).
     * Returns FftEngine's internal `smoothed` buffer — NOT a copy.
     * Callers that pass this to another thread MUST call `.copyOf()` first.
     */
    @Synchronized
    fun processUint8(rawIq: ByteArray): FloatArray {
        val n = fftSize
        val re = scratchRe
        val im = scratchIm
        val totalSamples = rawIq.size / 2
        val dec = decimationFactor.coerceAtLeast(1)

        val dcSamples = totalSamples.coerceAtLeast(1)
        var sumI = 0.0; var sumQ = 0.0
        for (i in 0 until dcSamples) {
            sumI += (rawIq[2 * i].toInt() and 0xFF)
            sumQ += (rawIq[2 * i + 1].toInt() and 0xFF)
        }
        val meanI = sumI / dcSamples
        val meanQ = sumQ / dcSamples

        val effectiveSamples = totalSamples / dec
        val samples  = minOf(n, effectiveSamples.coerceAtLeast(0))
        val decOffset = effectiveSamples - samples

        for (i in 0 until samples) {
            val rawStart = (decOffset + i) * dec
            var avgI = 0.0; var avgQ = 0.0; var count = 0
            for (d in 0 until dec) {
                val src = rawStart + d
                if (src < totalSamples) {
                    avgI += (rawIq[2 * src].toInt() and 0xFF) - meanI
                    avgQ += (rawIq[2 * src + 1].toInt() and 0xFF) - meanQ
                    count++
                }
            }
            if (count > 0) {
                re[i] = (avgI / count / 128.0) * window[i]
                im[i] = (avgQ / count / 128.0) * window[i]
            }
        }
        if (samples < n) { re.fill(0.0, samples, n); im.fill(0.0, samples, n) }
        return computeFft(re, im)
    }

    /**
     * Compute FFT from float IQ array (interleaved I, Q pairs).
     */
    @Synchronized
    fun processFloat(floatIq: FloatArray): FloatArray {
        val n = fftSize
        val re = scratchRe
        val im = scratchIm
        val totalSamples = floatIq.size / 2
        val dec = decimationFactor.coerceAtLeast(1)

        var meanI = 0.0; var meanQ = 0.0
        for (i in 0 until totalSamples) { meanI += floatIq[2 * i]; meanQ += floatIq[2 * i + 1] }
        if (totalSamples > 0) { meanI /= totalSamples; meanQ /= totalSamples }

        val effectiveSamples = totalSamples / dec
        val samples  = minOf(n, effectiveSamples.coerceAtLeast(0))
        val decOffset = effectiveSamples - samples

        for (i in 0 until samples) {
            val rawStart = (decOffset + i) * dec
            var avgI = 0.0; var avgQ = 0.0; var count = 0
            for (d in 0 until dec) {
                val src = rawStart + d
                if (src < totalSamples) {
                    avgI += floatIq[2 * src]     - meanI
                    avgQ += floatIq[2 * src + 1] - meanQ
                    count++
                }
            }
            if (count > 0) {
                re[i] = (avgI / count) * window[i]
                im[i] = (avgQ / count) * window[i]
            }
        }
        if (samples < n) { re.fill(0.0, samples, n); im.fill(0.0, samples, n) }
        return computeFft(re, im)
    }

    private fun computeFft(re: DoubleArray, im: DoubleArray): FloatArray {
        val n = fftSize
        fftCooleyTukey(re, im, n)

        val result = scratchResult
        val scale  = 1.0 / windowSum
        for (i in 0 until n) {
            val shifted = (i + n / 2) % n
            val mag = sqrt(re[shifted] * re[shifted] + im[shifted] * im[shifted]) * scale
            result[i] = (20.0 * log10(mag + 1e-15)).toFloat()
        }

        // Always update lastPeakDb from the current raw frame for squelch
        var rawPeak = Float.NEGATIVE_INFINITY
        for (i in 0 until n) { if (result[i] > rawPeak) rawPeak = result[i] }
        lastPeakDb = rawPeak

        // ── Frame averaging (linear-domain power accumulation) ────────────────
        if (frameAveragingCount > 1) {
            for (i in 0 until n) {
                frameAvgBuffer[i] += 10.0.pow(result[i] / 10.0)
            }
            frameAvgAccum++
            if (frameAvgAccum < frameAveragingCount) {
                // Accumulating — return last good smoothed without updating display
                return smoothed
            }
            // Enough frames: convert averaged power back to dBFS
            for (i in 0 until n) {
                result[i] = (10.0 * log10(frameAvgBuffer[i] / frameAveragingCount + 1e-30)).toFloat()
                frameAvgBuffer[i] = 0.0
            }
            frameAvgAccum = 0
        }

        // ── Exponential smoothing ─────────────────────────────────────────────
        val alpha = smoothingAlpha
        var maxVal = Float.NEGATIVE_INFINITY
        for (i in 0 until n) {
            val s = alpha * smoothed[i] + (1f - alpha) * result[i]
            smoothed[i] = s
            if (s > maxVal) maxVal = s
        }
        lastPeakDb = maxVal

        // ── Per-bin noise floor estimation (minimum-statistics) ───────────────
        val riseRate = noiseFloorRiseRate
        for (i in 0 until n) {
            noiseFloorEst[i] = if (smoothed[i] < noiseFloorEst[i])
                smoothed[i]
            else
                noiseFloorEst[i] + riseRate
        }

        // ── Peak hold ─────────────────────────────────────────────────────────
        if (showPeakHold) {
            if (peakHoldWarmupRemaining > 0) {
                peakHoldWarmupRemaining--
                peakHold.fill(-200f)
            } else {
                val decay = peakDecayRate
                for (i in 0 until n) {
                    if (smoothed[i] > peakHold[i]) peakHold[i] = smoothed[i]
                    else peakHold[i] -= decay
                }
            }
        }

        return smoothed
    }

    /** Returns a snapshot of the peak-hold trace. */
    @Synchronized fun getPeakHold(): FloatArray = peakHold.copyOf()

    /**
     * Returns a copy of the per-bin noise floor estimate in dBFS.
     * Each element is the estimated noise level at that FFT bin.
     */
    @Synchronized fun getNoiseFloor(): FloatArray = noiseFloorEst.copyOf()

    /**
     * Returns a per-bin SNR map: smoothed[i] − noiseFloor[i] in dB.
     * Values > 0 indicate signal above the estimated noise floor.
     */
    @Synchronized fun getSnrMap(): FloatArray {
        val n = fftSize
        val snr = FloatArray(n)
        for (i in 0 until n) snr[i] = smoothed[i] - noiseFloorEst[i]
        return snr
    }

    /**
     * Find the top-N local spectral peaks, with a minimum separation of
     * [minSepBins] bins between peaks to avoid returning multiple bins of the
     * same signal.  Returns a list of (binIndex, dBFS) pairs sorted by
     * descending amplitude.  Bins within [edgeBins] of either edge are excluded
     * (they often contain spectral leakage artefacts).
     */
    @Synchronized
    fun findTopPeaks(
        maxPeaks:   Int = 5,
        minSepBins: Int = 8,
        edgeBins:   Int = 3,
        minDb:      Float = -120f
    ): List<Pair<Int, Float>> {
        val n = fftSize
        data class Candidate(val bin: Int, val db: Float)
        val candidates = mutableListOf<Candidate>()

        // Collect all local maxima above minDb that exceed both neighbours
        for (i in edgeBins until n - edgeBins) {
            val v = smoothed[i]
            if (v < minDb) continue
            if (v >= smoothed[i - 1] && v > smoothed[i + 1]) {
                candidates += Candidate(i, v)
            }
        }

        // Sort by descending amplitude, then greedily pick with min separation
        candidates.sortByDescending { it.db }
        val selected = mutableListOf<Pair<Int, Float>>()
        for (c in candidates) {
            if (selected.size >= maxPeaks) break
            val tooClose = selected.any { abs(it.first - c.bin) < minSepBins }
            if (!tooClose) selected += Pair(c.bin, c.db)
        }
        return selected
    }

    @Synchronized
    fun resetPeakHold() {
        peakHold.fill(-200f)
        peakHoldWarmupRemaining = peakHoldWarmupBlocks
    }

    /** Reset the noise floor estimator to the default floor value. */
    @Synchronized
    fun resetNoiseFloor() {
        noiseFloorEst.fill(-150f)
    }

    // ─── Cooley-Tukey radix-2 DIT FFT ────────────────────────────────────────

    private fun fftCooleyTukey(re: DoubleArray, im: DoubleArray, n: Int) {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                tmp = im[i]; im[i] = im[j]; im[j] = tmp
            }
        }
        val tRe = twiddleRe
        val tIm = twiddleIm
        var len = 2
        while (len <= n) {
            val half = len / 2
            val off  = half - 1
            var i = 0
            while (i < n) {
                for (k in 0 until half) {
                    val uRe  = tRe[off + k]
                    val uIm  = tIm[off + k]
                    val tRe1 = uRe * re[i + k + half] - uIm * im[i + k + half]
                    val tIm1 = uRe * im[i + k + half] + uIm * re[i + k + half]
                    re[i + k + half] = re[i + k] - tRe1
                    im[i + k + half] = im[i + k] - tIm1
                    re[i + k] += tRe1
                    im[i + k] += tIm1
                }
                i += len
            }
            len = len shl 1
        }
    }

    // ─── Window functions ─────────────────────────────────────────────────────

    private fun buildWindow(size: Int, type: WindowType): FloatArray {
        val w = FloatArray(size)
        val m = size - 1.0
        for (i in 0 until size) {
            w[i] = when (type) {
                WindowType.RECTANGULAR     -> 1f
                WindowType.HANN            -> (0.5 * (1.0 - cos(2.0 * PI * i / m))).toFloat()
                WindowType.HAMMING         -> (0.54 - 0.46 * cos(2.0 * PI * i / m)).toFloat()
                WindowType.BLACKMAN        -> (0.42 - 0.5  * cos(2.0 * PI * i / m)
                                              + 0.08 * cos(4.0 * PI * i / m)).toFloat()
                WindowType.BLACKMAN_HARRIS -> (0.35875
                                              - 0.48829 * cos(2.0 * PI * i / m)
                                              + 0.14128 * cos(4.0 * PI * i / m)
                                              - 0.01168 * cos(6.0 * PI * i / m)).toFloat()
                WindowType.FLAT_TOP        -> (0.21557895
                                              - 0.41663158 * cos(2.0 * PI * i / m)
                                              + 0.27726316 * cos(4.0 * PI * i / m)
                                              - 0.08357895 * cos(6.0 * PI * i / m)
                                              + 0.00694737 * cos(8.0 * PI * i / m)).toFloat()
                WindowType.KAISER          -> kaiserWindow(i, m, kaiserBeta)
                WindowType.NUTTALL         -> (0.355768
                                              - 0.487396 * cos(2.0 * PI * i / m)
                                              + 0.144232 * cos(4.0 * PI * i / m)
                                              - 0.012604 * cos(6.0 * PI * i / m)).toFloat()
            }
        }
        return w
    }

    private fun kaiserWindow(i: Int, m: Double, alpha: Double): Float {
        val x = 2.0 * i / m - 1.0
        return (i0(PI * alpha * sqrt(1.0 - x * x)) / i0(PI * alpha)).toFloat()
    }

    private fun i0(x: Double): Double {
        var sum = 1.0; var term = 1.0
        for (k in 1..20) { term *= (x / 2) / k; sum += term * term }
        return sum
    }
}

// ─── Extension: peak-frequency offset in a spectrum array ────────────────────

fun FloatArray.peakFrequencyOffset(sampleRate: Int): Long {
    val maxIdx    = indices.maxByOrNull { this[it] } ?: return 0
    val centerBin = size / 2
    return ((maxIdx - centerBin).toLong() * sampleRate / size)
}
