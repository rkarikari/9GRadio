package com.radiosport.ninegradio.dsp

import android.util.Log

/**
 * JNI bridge to the native NEON-accelerated DSP library.
 * All functions gracefully fall back to Kotlin implementations
 * if the native library fails to load.
 */
object NativeDsp {

    private const val TAG = "NativeDsp"
    private var nativeAvailable = false

    init {
        try {
            System.loadLibrary("ninegradio_dsp")
            nativeAvailable = true
            // Log which VOLK machine was selected (e.g. "neon_vfpv4", "generic").
            // volkGetMachine() returns "volk_unavailable" when compiled without VOLK
            // so this string distinguishes gnuradio-android builds from fallback builds.
            try {
                val machine = volkGetMachine()
                Log.i(TAG, "Native DSP library loaded — VOLK machine: $machine")
            } catch (_: UnsatisfiedLinkError) {
                Log.i(TAG, "Native DSP library loaded (custom NEON, no VOLK)")
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native DSP library unavailable, using Kotlin fallback: ${e.message}")
        }
    }

    fun isNativeAvailable(): Boolean = nativeAvailable

    // ─── Native declarations ──────────────────────────────────────────────────

    @JvmStatic
    private external fun uint8ToFloat(input: ByteArray, output: FloatArray, length: Int)

    @JvmStatic
    private external fun fmDiscriminator(
        iq: FloatArray, out: FloatArray, samples: Int,
        prevI: Float, prevQ: Float, gain: Float
    )

    /**
     * Stateful FM discriminator — [state] is float[2] = [prevI, prevQ], updated in-place.
     * Preserves inter-block continuity so there are no clicks at USB-block seams.
     */    @JvmStatic
    private external fun firFilter(
        input: FloatArray, taps: FloatArray, output: FloatArray,
        inputLen: Int, tapLen: Int
    )

    @JvmStatic
    private external fun amEnvelope(iq: FloatArray, out: FloatArray, samples: Int)

    @JvmStatic
    private external fun computeRms(iq: FloatArray, samples: Int): Float

    @JvmStatic
    private external fun resampleLinear(
        input: FloatArray, inLen: Int,
        output: FloatArray, outLen: Int,
        inRate: Int, outRate: Int
    ): Int

    /**
     * Native DC-blocking IIR filter (in-place).
     * [state] is a float[2] = [dcI, dcQ], read and updated on each call.
     */
    @JvmStatic
    private external fun dcRemoveInPlace(
        iq: FloatArray, samples: Int, alpha: Float, state: FloatArray
    )

    /**
     * Returns the VOLK machine string selected at runtime (e.g. "neon_vfpv4").
     * Returns "volk_unavailable" when the library was compiled without VOLK.
     * Used for diagnostic logging only.
     */
    @JvmStatic @JvmName("volkGetMachine")
    internal external fun volkGetMachine(): String

    // ─── Public API with fallback ─────────────────────────────────────────────

    /**
     * Convert raw RTL-SDR uint8 IQ bytes to normalised float IQ.
     * Uses NEON vectorisation when available.
     */
    fun convertUint8ToFloat(input: ByteArray, length: Int): FloatArray {
        val output = FloatArray(length)
        if (nativeAvailable) {
            uint8ToFloat(input, output, length)
        } else {
            for (i in 0 until length) {
                output[i] = ((input[i].toInt() and 0xFF) - 127.5f) / 128.0f
            }
        }
        return output
    }

    /**
     * In-place variant of [convertUint8ToFloat] that writes into a caller-supplied
     * [output] buffer instead of allocating a new one.  The buffer must have
     * length ≥ [length].  Used by DspEngine to eliminate the 512 KB per-block
     * FloatArray allocation from the real-time IQ processing hot path.
     */
    fun convertUint8ToFloatInto(input: ByteArray, output: FloatArray, length: Int) {
        if (nativeAvailable) {
            uint8ToFloat(input, output, length)
        } else {
            for (i in 0 until length) {
                output[i] = ((input[i].toInt() and 0xFF) - 127.5f) / 128.0f
            }
        }
    }

    /**
     * FM discriminator: d/dt(phase) for each complex sample pair.
     */
    fun fmDemodulate(
        iq: FloatArray, sampleRate: Int,
        prevI: Float = 0f, prevQ: Float = 0f
    ): FloatArray {
        val n = iq.size / 2
        val out = FloatArray(n)
        val gain = sampleRate / (2f * Math.PI.toFloat() * 75_000f)
        if (nativeAvailable) {
            fmDiscriminator(iq, out, n, prevI, prevQ, gain)
        } else {
            // atan2-based discriminator (matches native fm_discriminator()):
            // avoids dividing by I²+Q², which spikes near carrier nulls and
            // causes watery/jittery audio at non-integer resample ratios.
            var prevPhase = Math.atan2(prevQ.toDouble(), prevI.toDouble()).toFloat()
            for (i in 0 until n) {
                val ci = iq[2 * i]; val cq = iq[2 * i + 1]
                val phase = Math.atan2(cq.toDouble(), ci.toDouble()).toFloat()
                var diff = phase - prevPhase
                if (diff > Math.PI.toFloat()) diff -= (2f * Math.PI.toFloat())
                if (diff < -Math.PI.toFloat()) diff += (2f * Math.PI.toFloat())
                out[i] = diff * gain
                prevPhase = phase
            }
        }
        return out
    }

    /**
     * Stateful FM discriminator — preserves [prevI, prevQ] across block boundaries.
     *
     * [state] must be a FloatArray(2) = [prevI, prevQ], initialised to [0f, 0f].
     * Pass the same array on every call; it is updated in-place after each block so
     * the phase estimate is continuous and there are no clicks at USB-block seams.
     *
     * [gainHz] is the deviation normalisation: fs / (2π × maxDevHz).
     *   NFM:  gainHz = fs / (2π × 5 000)
     *   WFM:  gainHz = fs / (2π × 75 000)
     */
    fun fmDemodulateStateful(
        iq: FloatArray,
        state: FloatArray,          // float[2]: [prevI, prevQ] — updated in-place
        gainHz: Float
    ): FloatArray {
        val n = iq.size / 2
        val out = FloatArray(n)
        if (nativeAvailable) {
            // Use the existing fmDiscriminator native — it takes prevI/prevQ by value
            // and processes the full block in NEON/VOLK.  We then extract the last IQ
            // sample from the buffer to update state for the next block, giving us the
            // same inter-block continuity as a truly stateful native call at zero cost
            // (one extra read per block, not per sample).
            fmDiscriminator(iq, out, n, state[0], state[1], gainHz)
            if (n > 0) {
                state[0] = iq[2 * (n - 1)]
                state[1] = iq[2 * (n - 1) + 1]
            }
        } else {
            var prevPhase = Math.atan2(state[1].toDouble(), state[0].toDouble()).toFloat()
            for (i in 0 until n) {
                val ci = iq[2 * i]; val cq = iq[2 * i + 1]
                val phase = Math.atan2(cq.toDouble(), ci.toDouble()).toFloat()
                var diff = phase - prevPhase
                if (diff > Math.PI.toFloat()) diff -= (2f * Math.PI.toFloat())
                if (diff < -Math.PI.toFloat()) diff += (2f * Math.PI.toFloat())
                out[i] = diff * gainHz
                prevPhase = phase
            }
            if (n > 0) {
                state[0] = iq[2 * (n - 1)]
                state[1] = iq[2 * (n - 1) + 1]
            }
        }
        return out
    }

    /**
     * In-place variant of [fmDemodulateStateful] that writes into a caller-supplied
     * [out] buffer instead of allocating a new FloatArray.  [out] must have size ≥ n/2.
     * Used by WfmDemodulator and NfmDemodulator to eliminate the per-block
     * FloatArray(n) allocation from the real-time DSP hot path.
     */
    fun fmDemodulateStatefulInto(
        iq: FloatArray,
        state: FloatArray,
        gainHz: Float,
        out: FloatArray
    ) {
        val n = iq.size / 2
        if (nativeAvailable) {
            fmDiscriminator(iq, out, n, state[0], state[1], gainHz)
            if (n > 0) {
                state[0] = iq[2 * (n - 1)]
                state[1] = iq[2 * (n - 1) + 1]
            }
        } else {
            var prevPhase = Math.atan2(state[1].toDouble(), state[0].toDouble()).toFloat()
            for (i in 0 until n) {
                val ci = iq[2 * i]; val cq = iq[2 * i + 1]
                val phase = Math.atan2(cq.toDouble(), ci.toDouble()).toFloat()
                var diff = phase - prevPhase
                if (diff > Math.PI.toFloat()) diff -= (2f * Math.PI.toFloat())
                if (diff < -Math.PI.toFloat()) diff += (2f * Math.PI.toFloat())
                out[i] = diff * gainHz
                prevPhase = phase
            }
            if (n > 0) {
                state[0] = iq[2 * (n - 1)]
                state[1] = iq[2 * (n - 1) + 1]
            }
        }
    }

    /**
     * Apply the same FIR kernel independently to the I branch and Q branch of an
     * interleaved IQ buffer.  Used by SsbDemodulator to run its 63-tap Hamming LPF
     * on both branches without de-interleaving into separate arrays on the Kotlin heap.
     *
     * The caller owns the delay-line state ([bufI] and [bufQ], each [taps.size]) and
     * [bufPos] (a single-element IntArray so it can be updated by reference).  Pass
     * the same arrays on every call to keep the filter continuous across blocks.
     *
     * Returns an interleaved FloatArray of the same length as [iq].
     */
    fun applyFirDual(
        iq: FloatArray,
        taps: FloatArray,
        bufI: FloatArray, bufQ: FloatArray,
        bufPos: IntArray                    // bufPos[0] — updated in-place
    ): FloatArray {
        val n  = iq.size / 2
        val out = FloatArray(iq.size)       // interleaved filtered I/Q
        val tapLen = taps.size
        var pos = bufPos[0]
        for (i in 0 until n) {
            bufI[pos] = iq[2 * i]
            bufQ[pos] = iq[2 * i + 1]
            var fI = 0f; var fQ = 0f
            for (k in 0 until tapLen) {
                val p = (pos - k + tapLen) % tapLen
                fI += taps[k] * bufI[p]
                fQ += taps[k] * bufQ[p]
            }
            out[2 * i]     = fI
            out[2 * i + 1] = fQ
            pos = (pos + 1) % tapLen
        }
        bufPos[0] = pos
        return out
    }

    /**
     * AM envelope detector: magnitude of each IQ sample.
     */
    fun amDetect(iq: FloatArray): FloatArray {
        val n = iq.size / 2
        val out = FloatArray(n)
        if (nativeAvailable) {
            amEnvelope(iq, out, n)
        } else {
            for (i in 0 until n) {
                val I = iq[2 * i]; val Q = iq[2 * i + 1]
                out[i] = kotlin.math.sqrt(I * I + Q * Q)
            }
        }
        return out
    }

    /**
     * Apply FIR filter to input signal.
     */
    fun applyFir(input: FloatArray, taps: FloatArray): FloatArray {
        val out = FloatArray(input.size)
        if (nativeAvailable) {
            firFilter(input, taps, out, input.size, taps.size)
        } else {
            for (i in input.indices) {
                var acc = 0f
                for (k in taps.indices) {
                    if (i - k >= 0) acc += taps[k] * input[i - k]
                }
                out[i] = acc
            }
        }
        return out
    }

    /**
     * Compute mean power of IQ signal (returns power, not dB).
     */
    fun computeRmsPower(iq: FloatArray): Float {
        val n = iq.size / 2
        if (n == 0) return 0f
        return if (nativeAvailable) {
            computeRms(iq, n)
        } else {
            var power = 0.0
            for (i in 0 until n) {
                val I = iq[2 * i].toDouble()
                val Q = iq[2 * i + 1].toDouble()
                power += I * I + Q * Q
            }
            (power / n).toFloat()
        }
    }

    /**
     * Linear interpolation resampler.
     */
    fun resample(input: FloatArray, inRate: Int, outRate: Int): FloatArray {
        if (inRate == outRate) return input
        val outLen = (input.size.toLong() * outRate / inRate).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        if (nativeAvailable) {
            resampleLinear(input, input.size, out, outLen, inRate, outRate)
        } else {
            val ratio = inRate.toDouble() / outRate
            for (i in 0 until outLen) {
                val srcPos = i * ratio
                val idx = srcPos.toInt()
                val frac = (srcPos - idx).toFloat()
                val s0 = input.getOrElse(idx) { 0f }
                val s1 = input.getOrElse(idx + 1) { 0f }
                out[i] = s0 + frac * (s1 - s0)
            }
        }
        return out
    }

    /**
     * Remove the RTL-SDR hardware DC bias from an interleaved float IQ buffer
     * using a leaky-integrator IIR high-pass filter.  The buffer is modified
     * in-place.
     *
     * [state] must be a FloatArray(2) = [dcI, dcQ], initialised to [0f, 0f].
     * Pass the same array on every call so the filter remains continuous across
     * USB bulk-transfer blocks.  Without this continuity, each new block would
     * restart the estimate from zero, leaving a residual step at every boundary.
     *
     * [alpha] controls the time constant:
     *   τ (samples) ≈ 1 / (1 − alpha)
     *   At 2 MS/s, alpha = 0.9999 → τ ≈ 10 000 samples ≈ 5 ms.
     * This is slow enough to leave FM audio content intact after demodulation
     * and fast enough to converge within a handful of USB blocks on startup.
     */
    fun removeDc(iq: FloatArray, state: FloatArray, alpha: Float = 0.9999f) {
        val n = iq.size / 2
        if (n == 0) return
        if (nativeAvailable) {
            dcRemoveInPlace(iq, n, alpha, state)
        } else {
            val beta = 1.0f - alpha
            var di = state[0]; var dq = state[1]
            for (i in 0 until n) {
                val xi = iq[2 * i]; val xq = iq[2 * i + 1]
                di = alpha * di + beta * xi
                dq = alpha * dq + beta * xq
                iq[2 * i]     = xi - di
                iq[2 * i + 1] = xq - dq
            }
            state[0] = di; state[1] = dq
        }
    }

    /**
     * Convert RMS power to dBFS.
     */
    fun powerToDb(power: Float): Float =
        if (power > 0f) 10f * kotlin.math.log10(power) else -200f
}
