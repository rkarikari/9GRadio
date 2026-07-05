package com.radiosport.ninegradio.dsp

import kotlin.math.*

/**
 * Polyphase FIR decimation/interpolation filter.
 *
 * Replaces the simple linear interpolation resampler with a proper
 * windowed-sinc design for much better alias rejection.
 *
 * Usage:
 *   val decim = PolyphaseResampler(inputRate = 1_920_000, outputRate = 48_000)
 *   val audio = decim.process(floatIqOrMono)
 */
class PolyphaseResampler(
    val inputRate: Int,
    val outputRate: Int,
    numTaps: Int = 127
) {
    private val decimFactor: Int
    private val interpFactor: Int
    private val numPhases: Int
    private val phaseLen: Int
    private val filterBank: Array<FloatArray>
    private val delayLine: FloatArray
    private var dlPos = 0
    private var inputCounter = 0L
    private var outputCounter = 0L

    // Cached output buffer — reused across process() calls to eliminate per-block
    // FloatArray allocation.  Sized to the maximum output we've ever produced plus
    // the +2 rounding margin; grown (but never shrunk) whenever a larger block arrives.
    // Thread-safety: PolyphaseResampler is always called from a single DSP coroutine.
    private var cachedOutput = FloatArray(0)

    companion object {
        /**
         * Minimum taps per polyphase branch.
         *
         * A polyphase bank with [numPhases] branches splits the prototype FIR
         * into sub-filters of length phaseLen = totalTaps / numPhases.  Each
         * branch is effectively the FIR running at the *output* rate, so
         * phaseLen determines the filter quality seen by the demodulator.
         *
         * With the default 127-tap prototype, high-interpFactor cases collapse
         * to very few taps per branch:
         *
         *   2 048 kS/s → 240 kHz WFM pre-decimation: interpFactor = 15
         *     → 127 / 15 ≈ 9 taps/branch.
         *
         * Nine taps at 2 048 kHz gives a transition band of ≈ 8/135 × 2 048 kHz
         * = 121 kHz.  Energy from adjacent FM broadcast stations 121–241 kHz
         * outside the 120 kHz IF passband folds back in, degrading the stereo
         * pilot PLL and adding inter-station noise at all main-band sample rates
         * whose GCD with 240 kHz yields a large interpFactor.
         *
         * Fix: guarantee at least MIN_TAPS_PER_PHASE taps per branch.  For the
         * 2048k→240k case the count rises from 135 to 480 taps (32 per branch),
         * narrowing the transition band from ~121 kHz to ~34 kHz and pushing
         * alias attenuation to ≥ 92 dB for any interferer more than 34 kHz
         * outside the passband.
         *
         * Rates with numPhases ≤ 4 (250k, 300k, 900k, 1024k narrow pre-dec)
         * already have phaseLen ≥ 31 with 127 taps, so the scaling has no
         * effect for those lower sample rates.
         */
        private const val MIN_TAPS_PER_PHASE = 32

        /**
         * Per-output-sample MAC cost is [phaseLen] (one polyphase branch is
         * evaluated per output sample) — NOT numPhases × phaseLen. Cap
         * phaseLen directly so CPU cost stays bounded regardless of
         * numPhases.
         *
         * Previously the cap was applied to `numPhases * phaseLen` (total
         * prototype-filter taps) via MAX_TOTAL_TAPS=1023. For most rates
         * numPhases is small (≤ 64) so this incidentally bounded phaseLen
         * too. But for sink rates whose GCD with the input rate leaves a
         * large interpFactor — e.g. 44.1 kHz / 22.05 kHz sinks against a
         * 204.8 kHz WFM IF give interpFactor = numPhases = 441 — the old
         * formula collapsed phaseLen to ceil(1023/441) = 3 taps/branch.
         * A 3-tap filter is a near-useless anti-alias/reconstruction filter,
         * producing severe aliasing/imaging artifacts ("watery"/gritty audio)
         * on WFM — and 44.1 kHz is [DEFAULT_AUDIO_SINK_RATE], so this hit
         * every fresh install by default.
         *
         * MAX_PHASE_LEN=128 keeps the worst case (numPhases=441) at
         * 441 × 128 ≈ 56 k prototype taps (one-time FIR design + filter-bank
         * memory, ~226 KB of floats) while the actual real-time cost — 128
         * MACs/output-sample × 44.1 kHz × 2 (I+Q) ≈ 11.3 M MACs/sec — remains
         * trivial. 128 also preserves the previous 127-tap behaviour for
         * numPhases=1 (pure-decimation) cases, which were already adequate.
         */
        private const val MAX_PHASE_LEN = 128
    }

    init {
        val g = gcd(inputRate, outputRate)
        interpFactor = outputRate / g
        decimFactor  = inputRate  / g
        numPhases = interpFactor

        // Auto-scale tap count: each polyphase branch gets at least
        // MIN_TAPS_PER_PHASE taps for adequate alias rejection, capped at
        // MAX_PHASE_LEN to bound real-time MAC cost. numTaps (constructor
        // default 127) sets the floor for small-numPhases cases where
        // ceil(numTaps/numPhases) would otherwise fall below MIN_TAPS_PER_PHASE.
        val minPhaseLenFromProto = (numTaps + numPhases - 1) / numPhases
        phaseLen = maxOf(MIN_TAPS_PER_PHASE, minPhaseLenFromProto)
            .coerceAtMost(MAX_PHASE_LEN)

        // Build prototype low-pass FIR.
        //
        // The anti-aliasing cutoff must be the lower of the two Nyquist limits,
        // normalised to the input sampling rate:
        //
        //   fc = min(inputRate, outputRate) / (2 * inputRate)
        //      = min(1, outputRate / inputRate) / 2
        //      = interpFactor / (2 * decimFactor)        [after GCD reduction]
        //
        // The old formula, 0.5 / max(decimFactor, interpFactor), only equals
        // this when the two factors happen to be equal (or nearly so).  When
        // decimFactor ≫ interpFactor — as with 2.048 MS/s → 240 kHz where
        // decimFactor = 128 and interpFactor = 15 — max() returns decimFactor
        // and the computed cutoff collapses to 0.5/128 × 2.048 MHz = 8 kHz,
        // far below the 120 kHz needed to pass a WFM broadcast signal.  The
        // symptom was silence (or severe distortion) for all WFM channels at
        // sample rates whose reduced decimFactor is much larger than interpFactor.
        //
        // Cap at 0.45 (90 % of Nyquist) to preserve a small guard band and
        // keep the windowed-sinc design well away from the folding frequency.
        val fc = minOf(0.45, interpFactor.toDouble() / (2.0 * decimFactor))
        val totalTaps = numPhases * phaseLen
        val proto = buildSincFir(totalTaps, fc)

        // Polyphase decomposition
        filterBank = Array(numPhases) { FloatArray(phaseLen) }
        for (i in 0 until totalTaps) {
            filterBank[i % numPhases][i / numPhases] = proto[i] * numPhases
        }

        delayLine = FloatArray(phaseLen)
    }

    /**
     * Process a block of input samples (mono or already demodulated audio).
     * Returns a view into a reused internal buffer — valid until the next call.
     *
     * The output buffer is cached and grown only when a larger block arrives.
     * This eliminates the per-block FloatArray allocation (and the occasional
     * copyOf() trim) that previously caused GC pressure in the real-time DSP
     * hot path (~15–20 calls/sec per resampler instance at WFM rates).
     *
     * Callers that need to retain the data across the next process() call must
     * copy it themselves (e.g. via copyOf(outCount)), but in the DspEngine
     * pipeline every result is consumed immediately (passed on to the next stage
     * or written to AudioTrack), so no copy is needed.
     *
     * Returns a Pair(buffer, count) — only buffer[0 until count] is valid.
     * Use processToSlice() when a plain FloatArray is required by a downstream
     * stage that does not accept a count parameter.
     */
    fun process(input: FloatArray): FloatArray {
        // Estimate maximum output size and grow the cache if needed.
        // +2 absorbs the one-sample start-up latency and any rounding at block edges.
        val estimatedOut = (input.size.toLong() * interpFactor / decimFactor + 2)
            .toInt().coerceAtLeast(1)
        if (estimatedOut > cachedOutput.size) {
            cachedOutput = FloatArray(estimatedOut)
        }
        val output = cachedOutput
        var outIdx = 0

        for (sample in input) {
            // Write to circular delay line.
            // dlPos is kept in [0, phaseLen) so the index arithmetic below
            // never produces a negative value regardless of block size or how
            // long the resampler has been running.
            delayLine[dlPos] = sample
            dlPos = (dlPos + 1) % phaseLen

            // Generate all output samples whose time-stamps fall within this input slot
            while (outputCounter * decimFactor < inputCounter * interpFactor) {
                val phase = (outputCounter * decimFactor % interpFactor).toInt()
                val taps = filterBank[phase]
                var acc = 0f
                // (dlPos - 1) is the most-recently written position; walk back
                // through the delay line by k for each tap.
                //
                // dlPos is always in [0, phaseLen), and k ranges over
                // [0, phaseLen) (taps.size == phaseLen), so
                // (dlPos - 1 - k) ranges over [-phaseLen, phaseLen) — i.e. it
                // can be negative by AT MOST one full wrap. A single
                // conditional += phaseLen is therefore sufficient and avoids
                // calling Math.floorMod() (division + branch) once per tap
                // per output sample — this loop runs taps × outputSamples
                // times per IQ block.
                val base = dlPos - 1
                for (k in taps.indices) {
                    var idx = base - k
                    if (idx < 0) idx += phaseLen
                    acc += taps[k] * delayLine[idx]
                }
                output[outIdx++] = acc
                outputCounter++
            }
            inputCounter++
        }

        // Return a trimmed view: copyOf only when the estimate was off (first block
        // start-up latency or last-block phase slip — typically 0 or 1 sample).
        // After the first block the estimate is exact and this branch never fires.
        return if (outIdx == output.size) output else output.copyOf(outIdx)
    }

    fun reset() {
        delayLine.fill(0f)
        dlPos = 0
        inputCounter = 0L
        outputCounter = 0L
    }

    private fun buildSincFir(taps: Int, fc: Double): FloatArray {
        val h = FloatArray(taps)
        val m = (taps - 1) / 2.0
        for (i in 0 until taps) {
            val x = 2.0 * PI * fc * (i - m)
            val sinc = if (abs(x) < 1e-9) 1.0 else sin(x) / x
            // Blackman-Harris window
            val w = 0.35875 - 0.48829 * cos(2 * PI * i / (taps - 1)) +
                    0.14128 * cos(4 * PI * i / (taps - 1)) -
                    0.01168 * cos(6 * PI * i / (taps - 1))
            h[i] = (sinc * w).toFloat()
        }
        // Normalise to unit DC gain
        val sum = h.sum()
        if (sum > 0) for (i in h.indices) h[i] /= sum
        return h
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    val ratio: Double get() = outputRate.toDouble() / inputRate.toDouble()
    val isDecimating: Boolean get() = outputRate < inputRate
}

/**
 * Complex (IQ) polyphase decimator — works on interleaved I,Q pairs.
 * Used to decimate the full RF bandwidth to the audio/demod bandwidth.
 */
class ComplexDecimator(inputRate: Int, outputRate: Int, numTaps: Int = 127) {
    private val iFilter = PolyphaseResampler(inputRate, outputRate, numTaps)
    private val qFilter = PolyphaseResampler(inputRate, outputRate, numTaps)

    // Scratch buffers — resized lazily when block size changes, but not
    // re-allocated on every call.  Eliminates 4 FloatArray heap allocations
    // (iSamples, qSamples, iOut, qOut) per IQ block (~14–20/sec).
    private var scratchIn  = 0               // tracks last n so we only reallocate on size change
    private var iScratch   = FloatArray(0)
    private var qScratch   = FloatArray(0)
    // Cached interleaved output — grown only when output size increases.
    private var outScratch = FloatArray(0)

    /**
     * Process interleaved IQ float array, return decimated interleaved IQ.
     * Returns a view into a reused internal buffer — valid until the next call.
     */
    fun process(iq: FloatArray): FloatArray {
        val n = iq.size / 2
        if (n != scratchIn) {
            iScratch  = FloatArray(n)
            qScratch  = FloatArray(n)
            scratchIn = n
        }
        for (i in 0 until n) {
            iScratch[i] = iq[2 * i]
            qScratch[i] = iq[2 * i + 1]
        }
        val iOut = iFilter.process(iScratch)
        val qOut = qFilter.process(qScratch)
        val outLen = minOf(iOut.size, qOut.size)
        val needed = outLen * 2
        if (needed > outScratch.size) outScratch = FloatArray(needed)
        val out = outScratch
        for (i in 0 until outLen) {
            out[2 * i]     = iOut[i]
            out[2 * i + 1] = qOut[i]
        }
        // Return a correctly-sized slice.  After the first block the estimate
        // is exact and no copy is made; on the very first block (start-up
        // latency) we may trim by 1–2 samples.
        return if (needed == out.size) out else out.copyOf(needed)
    }

    fun reset() { iFilter.reset(); qFilter.reset() }
}
