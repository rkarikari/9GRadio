package com.radiosport.ninegradio.dsp

import kotlin.math.*

// ═════════════════════════════════════════════════════════════════════════════
//  AM DEMODULATOR  — broadcast-quality pipeline
// ═════════════════════════════════════════════════════════════════════════════
//
//  Signal flow (all processing runs at the narrow intermediate rate, 48 kHz):
//
//    IQ samples
//      │
//      ▼
//    [1] Envelope detector  │IQ│ = √(I²+Q²)
//      │
//      ▼
//    [2] DC blocker (1-pole high-pass, fc ≈ 0.8 Hz)
//        Removes the large carrier-amplitude DC component.
//      │
//      ▼
//    [3] Audio LPF — 2nd-order Butterworth biquad, fc = 8 kHz
//        The detector output spans 0–24 kHz (Nyquist of 48 kHz IF).
//        Only 0–8 kHz is useful AM audio; the rest is broadband noise.
//        Without this filter the PolyphaseResampler (IF→48 kHz) aliases
//        that entire noise band into the output, producing the characteristic
//        hissy, fatiguing sound.  Cutting here gives the dramatic
//        improvement from "SDR-generic" to "broadcast-quality" audio.
//      │
//      ▼
//    [4] Input-referred AGC — peak envelope follower
//        attack  50 ms : settles fast after squelch opens or signal changes;
//                        slow enough not to pump on individual syllables.
//        release  1 s  : prevents audible "breathing" during speech pauses.
//        target   0.70 : −3 dBFS — leaves headroom for the soft limiter.
//        max gain 200× : covers carriers as weak as −49 dBFS.
//        Gain is applied to the *current* sample using the *current* gain
//        estimate (input-referred design, no output-feedback loop).
//      │
//      ▼
//    [5] Tanh soft limiter — saturation above 0.90 FS
//        Smoothly compresses rare fast transients the AGC misses.
//        Derivative = 1 at the threshold, so the transition is inaudible.
//      │
//      ▼
//    48-kHz PolyphaseResampler  (in DspEngine, not here)
//
class AmDemodulator : Demodulator() {
    override val mode = DemodMode.AM

    // ── [2] DC blocker ─────────────────────────────────────────────────────────
    // Single-pole high-pass:  y[n] = α · (y[n−1] + env[n] − env[n−1])
    // α = 0.9999 → fc ≈ 0.8 Hz at 48 kHz — removes carrier DC, passes audio.
    private var prevEnv = 0f
    private var dcAcc   = 0f

    // ── [3] Audio LPF — 2nd-order Butterworth biquad ───────────────────────────
    // Coefficients are computed in init() from the actual sample rate.
    private var lpfB0 = 1f;  private var lpfB1 = 0f;  private var lpfB2 = 0f
    private var lpfA1 = 0f;  private var lpfA2 = 0f
    private var lpfX1 = 0f;  private var lpfX2 = 0f   // input delay line
    private var lpfY1 = 0f;  private var lpfY2 = 0f   // output delay line

    // ── [4] AGC ────────────────────────────────────────────────────────────────
    private var agcGain  = 1f
    private var agcEnv   = 0f
    private var atkAlpha = 0f   // re-derived from sample rate in init()
    private var relAlpha = 0f

    private var cachedSampleRate = 0

    // ── Initialisation — called once on first use and on any sample-rate change ─
    private fun init(fs: Int) {
        // 2nd-order Butterworth LPF biquad — RBJ cookbook formulation
        // Q = 1/√2  (maximally flat, no passband ripple)
        // fc = 8 kHz — covers broadcast AM (±10 kHz) and voice AM (±3 kHz)
        // Capped at 45 % of fs: when IF bandwidth < 2×fc the biquad poles leave the
        // unit circle → exponential blow-up → saturated DC output → silence.
        val fc    = minOf(8_000.0, fs * 0.45)
        val w0    = 2.0 * PI * fc / fs
        val cosW  = cos(w0)
        val sinW  = sin(w0)
        // alpha = sin(w0) / (2·Q) = sin(w0) / (2·(1/√2)) = sin(w0)·√2/2 = sin(w0)/√2
        val alpha = sinW / sqrt(2.0)
        val a0inv = 1.0 / (1.0 + alpha)
        lpfB0 = ((1.0 - cosW) / 2.0  * a0inv).toFloat()
        lpfB1 = ( (1.0 - cosW)        * a0inv).toFloat()
        lpfB2 = lpfB0                                  // symmetric
        lpfA1 = (-2.0 * cosW           * a0inv).toFloat()
        lpfA2 = ( (1.0 - alpha)        * a0inv).toFloat()
        lpfX1 = 0f;  lpfX2 = 0f;  lpfY1 = 0f;  lpfY2 = 0f

        // AGC time constants:  αₐ = e^(−1 / (t·fs))
        atkAlpha = exp(-1.0 / (0.050 * fs)).toFloat()   // 50 ms attack
        relAlpha = exp(-1.0 / (1.000 * fs)).toFloat()   // 1 s  release

        prevEnv = 0f;  dcAcc  = 0f
        agcGain = 1f;  agcEnv = 0f
        cachedSampleRate = fs
    }

    override fun demodulate(iq: FloatArray, sampleRate: Int): FloatArray {
        if (sampleRate != cachedSampleRate) init(sampleRate)

        val n   = iq.size / 2
        val out = FloatArray(n)

        // ── [1] Envelope detection — vectorised via NativeDsp (NEON/VOLK) ──────
        val envBuf = NativeDsp.amDetect(iq)

        for (i in 0 until n) {
            val env = envBuf[i]

            // ── [2] DC blocker ─────────────────────────────────────────────────
            dcAcc   = 0.9999f * (dcAcc + env - prevEnv)
            prevEnv = env

            // ── [3] Audio LPF (direct-form II transposed biquad) ──────────────
            val x   = dcAcc
            val lpf = lpfB0 * x + lpfB1 * lpfX1 + lpfB2 * lpfX2 -
                      lpfA1 * lpfY1 - lpfA2 * lpfY2
            lpfX2 = lpfX1;  lpfX1 = x
            lpfY2 = lpfY1;  lpfY1 = lpf

            // ── [4] Input-referred AGC ─────────────────────────────────────────
            // Track the absolute-peak envelope of the filtered audio signal.
            // Gain is computed from the input level (not output), eliminating the
            // output-feedback loop that causes subtle distortion in a naive AGC.
            val absLpf = abs(lpf)
            agcEnv = if (absLpf > agcEnv)
                atkAlpha * agcEnv + (1f - atkAlpha) * absLpf   // fast attack
            else
                relAlpha * agcEnv + (1f - relAlpha) * absLpf   // slow release
            if (agcEnv > 1e-7f) {
                // Target −3 dBFS (0.70).  Max 200× (46 dB) handles the weakest
                // RTL-SDR signals; min 0.001× prevents runaway on full-scale input.
                agcGain = (0.70f / agcEnv).coerceIn(0.001f, 200f)
            }

            // ── [5] Apply gain + tanh soft-limiter ─────────────────────────────
            // Saturation above 0.90 FS: maps [0.9, ∞) → [0.9, 1.0) with a smooth
            // tanh curve.  Derivative = 1 at the threshold — completely transparent
            // unless a fast transient has outrun the AGC, in which case it is
            // far preferable to the harsh spectral splatter of hard clipping.
            val g = lpf * agcGain
            out[i] = when {
                g >  0.90f ->  0.90f + 0.10f * tanh(((g - 0.90f) * 10.0)).toFloat()
                g < -0.90f -> -0.90f - 0.10f * tanh(((-g - 0.90f) * 10.0)).toFloat()
                else       ->  g
            }
        }
        return out
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  FM (Narrow FM) DEMODULATOR
// ═════════════════════════════════════════════════════════════════════════════
//
//  Signal flow (all processing at NARROW_INTERMEDIATE_RATE, 48 kHz):
//
//    IQ samples
//      │
//      ▼
//    [1] FM discriminator — atan2-based phase differentiator (bounded, no I²+Q² divide)
//        Gain = fs / (2π × 5000) → normalises ±5 kHz deviation to ±1.0
//      │
//      ▼
//    [2] DC blocker  (1-pole high-pass, fc ≈ 0.8 Hz)
//        Removes any residual DC from the discriminator without
//        affecting audio content above ~5 Hz.
//      │
//      ▼
//    [3] Audio LPF — 2nd-order Butterworth biquad, fc = 5 kHz
//        NFM voice sits 300 Hz–3.5 kHz; the discriminator also
//        produces out-of-band noise up to the IF Nyquist (24 kHz).
//        Cutting at 5 kHz passes the full audio band while rejecting
//        ~14 dB of noise power before the AGC and resampler.
//      │
//      ▼
//    [4] Input-referred AGC — peak envelope follower
//        attack  50 ms  : fast enough to respond to a new talker.
//        release  1 s   : prevents audible gain-breathing on pauses.
//        target   0.70  : −3 dBFS, leaves headroom for the limiter.
//        max gain 200×  : handles signals as weak as −49 dBFS.
//      │
//      ▼
//    [5] Tanh soft limiter (threshold 0.90 FS)
//        Smoothly absorbs any fast transient the AGC misses.
//      │
//      ▼
//    48-kHz PolyphaseResampler (in DspEngine)
//
class NfmDemodulator : Demodulator() {
    override val mode = DemodMode.NFM

    // ── [1] FM discriminator state — prevI/prevQ across block boundaries ────────
    // float[2] = [prevI, prevQ]; passed to NativeDsp.fmDemodulateStateful() and
    // updated in-place so the phase estimate is continuous at USB-block seams.
    private val fmState = FloatArray(2)   // [0]=prevI, [1]=prevQ

    private var prevI = 0f
    private var prevQ = 0f

    // ── [2] DC blocker ─────────────────────────────────────────────────────────
    private var dcPrev = 0f
    private var dcAcc  = 0f

    // ── [3] Audio LPF — 2nd-order Butterworth biquad ───────────────────────────
    private var lpfB0 = 1f;  private var lpfB1 = 0f;  private var lpfB2 = 0f
    private var lpfA1 = 0f;  private var lpfA2 = 0f
    private var lpfX1 = 0f;  private var lpfX2 = 0f
    private var lpfY1 = 0f;  private var lpfY2 = 0f

    // ── [4] AGC ────────────────────────────────────────────────────────────────
    private var agcGain  = 1f
    private var agcEnv   = 0f
    private var atkAlpha = 0f
    private var relAlpha = 0f

    private var cachedSampleRate = 0

    // ── Initialisation — called once and on any sample-rate change ──────────────
    private fun init(fs: Int) {
        // 2nd-order Butterworth LPF biquad (RBJ cookbook, Q = 1/√2)
        // fc = 5 kHz — passes full NFM voice deviation; rejects discriminator noise.
        // Capped at 45 % of fs: when IF bandwidth < 2×fc the biquad poles leave the
        // unit circle → exponential blow-up → saturated DC output → silence.
        val fc    = minOf(5_000.0, fs * 0.45)
        val w0    = 2.0 * PI * fc / fs
        val cosW  = cos(w0)
        val sinW  = sin(w0)
        val alpha = sinW / sqrt(2.0)
        val a0inv = 1.0 / (1.0 + alpha)
        lpfB0 = ((1.0 - cosW) / 2.0 * a0inv).toFloat()
        lpfB1 = ((1.0 - cosW)        * a0inv).toFloat()
        lpfB2 = lpfB0
        lpfA1 = (-2.0 * cosW         * a0inv).toFloat()
        lpfA2 = ((1.0 - alpha)       * a0inv).toFloat()
        lpfX1 = 0f;  lpfX2 = 0f;  lpfY1 = 0f;  lpfY2 = 0f

        // AGC time constants: α = e^(−1/(t·fs))
        atkAlpha = exp(-1.0 / (0.050 * fs)).toFloat()   // 50 ms attack
        relAlpha = exp(-1.0 / (1.000 * fs)).toFloat()   //  1 s  release

        dcPrev = 0f;  dcAcc  = 0f
        agcGain = 1f; agcEnv = 0f
        cachedSampleRate = fs
        fmState[0] = 0f; fmState[1] = 0f
    }

    override fun demodulate(iq: FloatArray, sampleRate: Int): FloatArray {
        if (sampleRate != cachedSampleRate) init(sampleRate)

        // NFM max deviation is ±5 kHz.  Correct normalisation: gain = fs / (2π × 5000).
        // Note: NFM (amateur / land-mobile radio) does NOT use pre/de-emphasis.
        // De-emphasis is a WFM broadcast standard (75 µs in Americas/Japan, 50 µs Europe).
        // Applying 75 µs de-emphasis rolls off audio above ~2.1 kHz, making voices
        // muffled and suppressing CTCSS/DCS tones.  WfmDemodulator handles its own
        // de-emphasis correctly; NFM output is left flat after the LPF.
        val gainHz = sampleRate / (2f * PI.toFloat() * 5_000f)
        val out  = FloatArray(iq.size / 2)

        // ── [1] FM discriminator — stateful NEON/VOLK path ─────────────────────
        // fmDemodulateStateful() updates fmState[0/1] (prevI/prevQ) in-place so
        // the phase estimate is continuous across USB-bulk-transfer block seams.
        val disc = NativeDsp.fmDemodulateStateful(iq, fmState, gainHz)

        for (i in out.indices) {
            // ── [2] DC blocker ─────────────────────────────────────────────────
            dcAcc  = 0.9999f * (dcAcc + disc[i] - dcPrev)
            dcPrev = disc[i]

            // ── [3] Audio LPF (direct-form II transposed biquad) ──────────────
            val x   = dcAcc
            val lpf = lpfB0 * x + lpfB1 * lpfX1 + lpfB2 * lpfX2 -
                      lpfA1 * lpfY1 - lpfA2 * lpfY2
            lpfX2 = lpfX1;  lpfX1 = x
            lpfY2 = lpfY1;  lpfY1 = lpf

            // ── [4] Input-referred AGC ─────────────────────────────────────────
            val absLpf = abs(lpf)
            agcEnv = if (absLpf > agcEnv)
                atkAlpha * agcEnv + (1f - atkAlpha) * absLpf   // fast attack
            else
                relAlpha * agcEnv + (1f - relAlpha) * absLpf   // slow release
            if (agcEnv > 1e-7f) {
                agcGain = (0.70f / agcEnv).coerceIn(0.001f, 200f)
            }

            // ── [5] Apply gain + tanh soft-limiter ─────────────────────────────
            val g = lpf * agcGain
            out[i] = when {
                g >  0.90f ->  0.90f + 0.10f * tanh(((g - 0.90f) * 10.0)).toFloat()
                g < -0.90f -> -0.90f - 0.10f * tanh(((-g - 0.90f) * 10.0)).toFloat()
                else       ->  g
            }
        }
        return out
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  APRS DEMODULATOR  (Bell 202 AFSK, 1200 baud)
// ═════════════════════════════════════════════════════════════════════════════
//
//  FIX 17: APRS must NOT use NfmDemodulator.
//
//  NfmDemodulator applies three post-discriminator stages that are correct
//  for voice but catastrophic for AFSK tone decoding:
//
//    [3] 5 kHz Butterworth LPF  — passes both APRS tones (1200 / 2200 Hz) but
//        introduces group-delay dispersion between them, distorting the mark↔
//        space transition edges that the AprsDecoder PLL uses for clock sync.
//
//    [4] Input-referred AGC (50 ms attack / 1 s release, target 0.70 FS) —
//        normalises the audio amplitude to a fixed RMS.  This destroys the
//        per-channel amplitude information that AfskBandpassFilter.processAgc()
//        relies on to independently normalise the 1200 Hz and 2200 Hz envelopes
//        and compensate for the 3–6 dB de-emphasis imbalance between them.
//        After NfmDemodulator's AGC both tones arrive at approximately the same
//        amplitude, so the second-stage per-channel AGC inside AprsDecoder has
//        nothing to normalise — the slicer is back to comparing raw power levels
//        biased by the de-emphasis curve, i.e. always biased toward mark.
//
//    [5] tanh soft-limiter — further compresses and distorts the waveform.
//
//  The correct signal path for APRS is:
//
//    IQ at narrow IF rate (48 kHz)
//      │
//      ▼
//    [1] FM discriminator — NativeDsp.fmDemodulateStateful()
//        gain = fs / (2π × 5 000) — normalises ±5 kHz NFM deviation to ±1
//      │
//      ▼
//    [2] DC blocker — single-pole IIR (α = 0.9999), removes DC offset
//        introduced by any I/Q imbalance or discriminator bias.
//      │
//      ▼
//    Raw discriminator audio → AprsDecoder.feed()
//        AprsDecoder's AfskBandpassFilter + per-channel AGC take it from here.
//
//  Reference: Dire Wolf demod_afsk.c — the discriminator output goes directly
//  into the bandpass correlator; no AGC or limiter is applied at this stage.
//
class AprsDemodulator : Demodulator() {
    override val mode = DemodMode.APRS

    private val fmState = FloatArray(2)   // [prevI, prevQ] — stateful across blocks

    private var dcPrev = 0f
    private var dcAcc  = 0f

    private var cachedSampleRate = 0

    private fun init(fs: Int) {
        fmState[0] = 0f; fmState[1] = 0f
        dcPrev = 0f;  dcAcc = 0f
        cachedSampleRate = fs
    }

    override fun demodulate(iq: FloatArray, sampleRate: Int): FloatArray {
        if (sampleRate != cachedSampleRate) init(sampleRate)

        // [1] FM discriminator — same gain as NFM (±5 kHz deviation → ±1).
        val gainHz = sampleRate / (2f * PI.toFloat() * 5_000f)
        val disc = NativeDsp.fmDemodulateStateful(iq, fmState, gainHz)

        // [2] DC blocker only — no LPF, no AGC, no limiter.
        // AprsDecoder's per-channel biquad + AGC handles everything downstream.
        val out = FloatArray(disc.size)
        for (i in disc.indices) {
            dcAcc  = 0.9999f * (dcAcc + disc[i] - dcPrev)
            dcPrev = disc[i]
            out[i] = dcAcc
        }
        return out
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  WIDE FM DEMODULATOR (broadcast, with stereo pilot decoding)
// ═════════════════════════════════════════════════════════════════════════════
//
//  Signal flow (runs at the adaptive WFM IF rate, see DspEngine.wfmIfRate()):
//
//    IQ at wfmIfRate
//      │
//      ▼
//    [1] FM discriminator — atan2-based phase differentiator (bounded, no I²+Q² divide)
//        gain = fs / (2π × 75 kHz) — normalises ±75 kHz deviation to ±1
//      │
//      ▼
//    [2] 75 µs de-emphasis — 1-pole IIR, α = exp(−1 / (fs × 75e-6))
//        Coefficient is recomputed whenever fs changes (see init(fs)).
//      │  (mono path exits here)
//      ▼  (stereo path continues)
//    [3] Stereo BPF — 2nd-order RBJ bandpass centred at 38 kHz, Q≈1.27
//        b0 = sin(w0)/2, b1 = 0, b2 = −b0;  α = sin(w0)/(2Q)
//        Coefficients recomputed in init(fs) so the filter tracks fs changes.
//        Passes the 23–53 kHz stereo subchannel; suppresses pilot (19 kHz)
//        and mono audio (0–15 kHz).
//      │
//      ▼
//    [4] 38 kHz carrier regeneration — open-loop NCO at 2 × pilot frequency.
//        pilotStep = 2π × 19 000 / fs  (recomputed in init(fs)).
//        Doubling the phase gives the 38 kHz suppressed carrier.
//      │
//      ▼
//    [5] DSB product demodulation — stereoSub × carrier38 × 2
//        Recovers the L−R difference signal.
//      │
//      ▼
//    [6] Matrix  L = (L+R) + (L−R),  R = (L+R) − (L−R)
//        Returns interleaved [L₀, R₀, L₁, R₁, …] at wfmIfRate.
//      │
//      ▼
//    Audio PolyphaseResampler(wfmIfRate → 48 kHz)  — in DspEngine
//
class WfmDemodulator(private val stereo: Boolean = false) : Demodulator() {
    override val mode = if (stereo) DemodMode.WFM_STEREO else DemodMode.WFM

    // ── [1] FM discriminator state — prevI/prevQ across block boundaries ────────
    private val fmState = FloatArray(2)   // [0]=prevI, [1]=prevQ

    private var prevI = 0f
    private var prevQ = 0f

    // ── Per-rate state — recomputed in init(fs) ──────────────────────────────
    private var cachedSampleRate = 0

    // [2] 75 µs de-emphasis
    private var deemphAlpha75 = 0f
    private var deemphL = 0f
    private var deemphR = 0f

    // [2b] Mono audio LPF — 2nd-order Butterworth biquad, fc = 15 kHz.
    //      The FM discriminator output spans 0 to fs/2 (up to 125 kHz at 250 kS/s IF).
    //      Only 0–15 kHz is broadcast audio content; the remaining broadband
    //      noise aliases back into the audio band through the polyphase resampler
    //      unless it is removed here.  This is the primary cause of the "watery"/
    //      gritty sound on WFM mono: cutting at 15 kHz reduces aliased noise power
    //      by >20 dB before the final resampler stage.
    //      Applied AFTER de-emphasis (de-emph rolls off above ~2.1 kHz, so it
    //      already attenuates 2–15 kHz by ~17 dB; the explicit LPF finishes the job).
    private var monoLpfB0 = 1f;  private var monoLpfB1 = 0f;  private var monoLpfB2 = 0f
    private var monoLpfA1 = 0f;  private var monoLpfA2 = 0f
    private var monoLpfX1 = 0f;  private var monoLpfX2 = 0f
    private var monoLpfY1 = 0f;  private var monoLpfY2 = 0f

    // [3] Stereo BPF: 2nd-order RBJ bandpass (38 kHz centre, Q ≈ 1.267)
    //     Coefficients are recomputed from fs in init(); never hardcoded.
    private var bpfB0 = 0f   // b0 =  sin(w0)/2
    private var bpfB2 = 0f   // b2 = −sin(w0)/2   (b1 is always 0)
    private var bpfA1 = 0f   // a1 = −2·cos(w0) / a0
    private var bpfA2 = 0f   // a2 = (1 − α)   / a0
    // Direct-form II transposed state
    private var bpfW1 = 0f
    private var bpfW2 = 0f

    // [4] Pilot NCO — implemented as a complex phasor recurrence instead of a
    // running phase angle fed through Math.cos()/Math.sin() every sample.
    // Per-sample state: rotate the unit-magnitude phasor (pilotCos, pilotSin)
    // by the fixed per-sample rotation (rotCos, rotSin) using 4 multiplies +
    // 2 add/subs — no transcendental calls in the per-sample loop.
    // The 38 kHz carrier (2 × pilot frequency) is obtained from the
    // double-angle identities:
    //   cos(2θ) = cos²θ − sin²θ = 2cos²θ − 1
    // recomputed in init(fs) whenever the sample rate changes.
    private var rotCos = 1.0   // cos(pilotStep) — per-sample rotation, real part
    private var rotSin = 0.0   // sin(pilotStep) — per-sample rotation, imag part
    private var pilotCos = 1.0 // current phasor real part  (cos θ)
    private var pilotSin = 0.0 // current phasor imag part  (sin θ)

    // Pilot PLL — first-order frequency-locked loop that corrects the NCO to
    // track the actual 19 kHz pilot tone, whose frequency may deviate from
    // exactly 19 000 Hz by ±50 Hz due to broadcast transmitter tolerances.
    // An uncorrected ±50 Hz error at the NCO rotates the 38 kHz carrier phase
    // at ±100 Hz, producing a slow (~0.01 s) continuous phase sweep that
    // heterodynes the recovered L−R sub-channel and causes the characteristic
    // "watery" / chorusing / slow-tremolo artefact on stereo WFM.
    //
    // The pilot BPF (19 kHz bandpass, Q ≈ 50, ~380 Hz bandwidth) extracts the
    // 19 kHz tone from the discriminator output; the phase detector computes the
    // signed error between the BPF output and the NCO's current phase; a simple
    // integrator (loop filter) accumulates the error and nudges rotCos/rotSin.
    //
    // Pilot BPF (2nd-order RBJ bandpass, fc=19 kHz, Q=50 → BW≈380 Hz)
    private var pilotBpfB0 = 0f;  private var pilotBpfB2 = 0f
    private var pilotBpfA1 = 0f;  private var pilotBpfA2 = 0f
    private var pilotBpfW1 = 0f;  private var pilotBpfW2 = 0f
    // PLL integrator state
    private var pllFreqError = 0.0   // accumulated frequency correction (rad/sample)
    private val PLL_ALPHA    = 5e-5  // loop gain — small enough not to overshoot

    // Renormalisation counter: the phasor recurrence accumulates tiny
    // floating-point error in |phasor| over millions of iterations. Every
    // RENORM_INTERVAL samples we rescale (pilotCos, pilotSin) back to unit
    // magnitude — a single sqrt + 2 multiplies every ~0.2s, negligible cost,
    // keeping the 38 kHz carrier amplitude stable indefinitely.
    private var renormCounter = 0
    private companion object {
        const val RENORM_INTERVAL = 4096
    }

    // ── Output scratch buffers ────────────────────────────────────────────────
    // Reused across demodulate() calls instead of allocating a fresh FloatArray
    // every block (~15-20 times/sec at the WFM IF rate). Grown only when a
    // larger block size is encountered (e.g. on a sample-rate change).
    private var monoOut = FloatArray(0)
    private var stereoOut = FloatArray(0)
    // FM discriminator output buffer — reused to avoid per-block allocation.
    private var fmBuf = FloatArray(0)

    // ── Initialisation ────────────────────────────────────────────────────────
    private fun init(fs: Int) {
        // [2] 75 µs de-emphasis IIR coefficient
        deemphAlpha75 = exp(-1.0 / (fs.toDouble() * 75e-6)).toFloat()
        deemphL = 0f; deemphR = 0f

        // [2b] Mono audio LPF: 2nd-order Butterworth (RBJ cookbook, Q=1/√2)
        //      fc = 15 kHz, capped at 45 % of Nyquist for filter stability.
        //      Removes discriminator noise above the broadcast audio band
        //      before the polyphase resampler sees it.
        run {
            val fc    = minOf(15_000.0, fs * 0.45)
            val w0    = 2.0 * PI * fc / fs
            val cosW  = cos(w0);  val sinW = sin(w0)
            val alpha = sinW / sqrt(2.0)
            val a0inv = 1.0 / (1.0 + alpha)
            monoLpfB0 = ((1.0 - cosW) / 2.0 * a0inv).toFloat()
            monoLpfB1 = ((1.0 - cosW)        * a0inv).toFloat()
            monoLpfB2 = monoLpfB0
            monoLpfA1 = (-2.0 * cosW          * a0inv).toFloat()
            monoLpfA2 = ((1.0 - alpha)        * a0inv).toFloat()
            monoLpfX1 = 0f;  monoLpfX2 = 0f;  monoLpfY1 = 0f;  monoLpfY2 = 0f
        }

        // [3] 2nd-order RBJ bandpass — centred at 38 kHz, Q = 38/30 ≈ 1.267
        //     (bandwidth = 30 kHz covers the 23–53 kHz stereo subchannel)
        //     fc is capped at 0.45 × fs/2 so the biquad stays stable even if
        //     the IF rate is only just above 76 kHz.
        val fc  = minOf(38_000.0, fs * 0.45)
        val w0  = 2.0 * PI * fc / fs
        val Q   = 38_000.0 / 30_000.0          // ≈ 1.267
        val alpha = sin(w0) / (2.0 * Q)
        val a0inv = 1.0 / (1.0 + alpha)
        bpfB0 = ( sin(w0) / 2.0 * a0inv).toFloat()
        bpfB2 = (-sin(w0) / 2.0 * a0inv).toFloat()   // b1 always 0
        bpfA1 = (-2.0 * cos(w0)        * a0inv).toFloat()
        bpfA2 = ((1.0 - alpha)         * a0inv).toFloat()
        bpfW1 = 0f; bpfW2 = 0f

        // [4] Pilot NCO: per-sample rotation for a 19 kHz oscillator,
        // expressed as a unit phasor rotation (cos/sin of the per-sample
        // phase increment). Computed once here (two transcendental calls per
        // sample-rate change) instead of once per sample.
        val pilotStep = 2.0 * PI * 19_000.0 / fs
        rotCos = cos(pilotStep)
        rotSin = sin(pilotStep)
        pilotCos = 1.0
        pilotSin = 0.0

        // Pilot PLL BPF: 2nd-order RBJ bandpass centred at 19 kHz, Q = 50.
        // Narrow enough (≈ 380 Hz bandwidth) to pass only the 19 kHz stereo
        // pilot and reject music content, while wide enough to follow the
        // transmitter even if its pilot is slightly off-frequency.
        // Only needed for stereo; zero cost in mono path.
        if (stereo && fs > 40_000) {
            val pfc   = 19_000.0
            val pQ    = 50.0
            val pw0   = 2.0 * PI * pfc / fs
            val palpha = sin(pw0) / (2.0 * pQ)
            val pa0inv = 1.0 / (1.0 + palpha)
            pilotBpfB0 = ( sin(pw0) / 2.0 * pa0inv).toFloat()
            pilotBpfB2 = (-sin(pw0) / 2.0 * pa0inv).toFloat()
            pilotBpfA1 = (-2.0 * cos(pw0)  * pa0inv).toFloat()
            pilotBpfA2 = ((1.0 - palpha)   * pa0inv).toFloat()
            pilotBpfW1 = 0f;  pilotBpfW2 = 0f
        }
        pllFreqError = 0.0

        prevI = 0f; prevQ = 0f
        cachedSampleRate = fs
        fmState[0] = 0f; fmState[1] = 0f
    }

    override fun demodulate(iq: FloatArray, sampleRate: Int): FloatArray {
        if (sampleRate != cachedSampleRate) init(sampleRate)

        val n = iq.size / 2
        // [1] FM discriminator — stateful NEON/VOLK path ─────────────────────────
        // gain = fs / (2π × 75 kHz) normalises ±75 kHz deviation to ±1.
        // fmDemodulateStateful() updates fmState[0/1] in-place so the phase is
        // continuous across USB-bulk-transfer block seams (no clicks).
        val gainHz = sampleRate.toFloat() / (2f * Math.PI.toFloat() * 75_000f)
        if (fmBuf.size != n) fmBuf = FloatArray(n)
        NativeDsp.fmDemodulateStatefulInto(iq, fmState, gainHz, fmBuf)
        val fm = fmBuf

        if (!stereo) {
            // ── Mono: de-emphasize → audio LPF ───────────────────────────────
            // The audio LPF at 15 kHz removes discriminator noise above the
            // broadcast audio band before the polyphase resampler sees it.
            // Without it, energy from fs/2 − 15 kHz aliases back into the
            // 0–15 kHz audio band on every resample step, causing the
            // "watery"/gritty artefact.  De-emphasis runs first so its 2 kHz
            // roll-off gives the LPF a head-start; together they provide >40 dB
            // of alias rejection.
            if (monoOut.size != n) monoOut = FloatArray(n)
            val out = monoOut
            for (i in 0 until n) {
                // [2] 75 µs de-emphasis
                deemphL = deemphAlpha75 * deemphL + (1f - deemphAlpha75) * fm[i]
                // [2b] Mono audio LPF (direct-form II transposed biquad)
                val x   = deemphL
                val lpf = monoLpfB0 * x + monoLpfB1 * monoLpfX1 + monoLpfB2 * monoLpfX2 -
                          monoLpfA1 * monoLpfY1 - monoLpfA2 * monoLpfY2
                monoLpfX2 = monoLpfX1;  monoLpfX1 = x
                monoLpfY2 = monoLpfY1;  monoLpfY1 = lpf
                out[i] = lpf
            }
            return out
        }

        // ── Stereo: pilot BPF+PLL → NCO carrier → product demod → L/R matrix ─
        if (stereoOut.size != n * 2) stereoOut = FloatArray(n * 2)
        val out = stereoOut
        for (i in 0 until n) {
            val x = fm[i]

            // [3] RBJ bandpass — direct-form II transposed
            //     y = b0·x + w1;  w1 = b1·x − a1·y + w2;  w2 = b2·x − a2·y
            //     (b1 ≡ 0 simplifies the middle term)
            val y    = bpfB0 * x + bpfW1
            bpfW1    = -bpfA1 * y + bpfW2   // b1·x term drops (b1=0)
            bpfW2    = bpfB2 * x - bpfA2 * y

            // Pilot BPF: isolate the 19 kHz pilot tone from discriminator output.
            // Used only by the PLL to measure phase error; the 38 kHz stereo BPF
            // (y above) is unchanged and remains the signal path for demodulation.
            val pilotFiltered = pilotBpfB0 * x + pilotBpfW1
            pilotBpfW1        = -pilotBpfA1 * pilotFiltered + pilotBpfW2
            pilotBpfW2        = pilotBpfB2 * x - pilotBpfA2 * pilotFiltered

            // [4] NCO: advance pilot phasor by one step via complex rotation
            //     (pilotCos, pilotSin) ← (pilotCos, pilotSin) × (rotCos, rotSin)
            //     — 4 multiplies + 2 add/subs, replacing a per-sample running
            //     phase angle plus Math.cos()/Math.sin() calls.
            val newCos = pilotCos * rotCos - pilotSin * rotSin
            val newSin = pilotSin * rotCos + pilotCos * rotSin
            pilotCos = newCos
            pilotSin = newSin

            // Pilot PLL: compute signed phase error between the narrow-filtered
            // pilot and the NCO, then integrate into a frequency correction.
            // Phase error = Im(BPF_pilot × conj(NCO)) = BPF·sin(NCO) − 0·cos(NCO)
            // simplified: error = pilotFiltered · pilotSin (Q-arm of NCO)
            // Sign: when NCO leads the pilot (freq too high), pilotSin > 0 and
            // pilotFiltered < 0 (phase difference > 90°) → negative error → reduces freq.
            val pllError = pilotFiltered * pilotSin.toFloat()
            pllFreqError += PLL_ALPHA * pllError
            // Apply frequency correction: nudge the per-sample rotation angle
            // by accumulating a tiny phase correction each sample.
            // Recompute rotCos/rotSin from the corrected angle using a first-order
            // Taylor approximation (cheap; the correction per sample is tiny):
            //   rot_new ≈ rot_old − pllFreqError·rot_old_orthogonal
            // This avoids any transcendental calls in the per-sample loop.
            val correctedRotCos = rotCos - pllFreqError * rotSin
            val correctedRotSin = rotSin + pllFreqError * rotCos
            // Re-normalise to keep rotCos/rotSin on the unit circle
            val rotMag = sqrt(correctedRotCos * correctedRotCos + correctedRotSin * correctedRotSin)
            if (rotMag > 1e-12) {
                rotCos = correctedRotCos / rotMag
                rotSin = correctedRotSin / rotMag
            }

            renormCounter++
            if (renormCounter >= RENORM_INTERVAL) {
                renormCounter = 0
                val mag = sqrt(pilotCos * pilotCos + pilotSin * pilotSin)
                if (mag > 1e-12) {
                    pilotCos /= mag
                    pilotSin /= mag
                }
            }

            // [5] Carrier at 38 kHz = 2 × pilot via the double-angle identity:
            //     cos(2θ) = 2cos²θ − 1   — no transcendental call needed.
            val carrier38 = (2.0 * pilotCos * pilotCos - 1.0).toFloat()
            val diff = 2f * y * carrier38   // recovered L−R

            // [6] Matrix to L, R + de-emphasize each channel
            val m = x                       // L+R (mono) — no separate LPF needed;
                                            // the de-emphasis IIR acts as the audio LPF
            val l = m + diff
            val r = m - diff
            deemphL = deemphAlpha75 * deemphL + (1f - deemphAlpha75) * l
            deemphR = deemphAlpha75 * deemphR + (1f - deemphAlpha75) * r
            out[2 * i]     = deemphL
            out[2 * i + 1] = deemphR
        }
        return out
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  SSB (USB / LSB) DEMODULATOR  — Weaver method
// ═════════════════════════════════════════════════════════════════════════════
//
//  Signal flow (all processing at NARROW_INTERMEDIATE_RATE, 48 kHz):
//
//    IQ samples
//      │
//      ▼
//    [1] BFO product detector (mix with 1500 Hz quadrature oscillator)
//        Shifts the SSB passband (300–3000 Hz voice) down to −1200…+1500 Hz
//        centred around 0 Hz, ready for the symmetric low-pass filter.
//      │
//      ▼
//    [2] Dual FIR LPF on I and Q branches (63-tap Hamming-windowed sinc, fc = 3 kHz)
//        Rejects image and out-of-band noise before sideband selection.
//        At 48 kHz input rate the ratio fc/fs = 3k/48k = 0.0625 — well above
//        the old hardcoded 11025 Hz assumption (old ratio ≈ 0.27, now correct).
//      │
//      ▼
//    [3] Sideband select:  USB → filtI + filtQ,  LSB → filtI − filtQ
//      │
//      ▼
//    [4] DC blocker (1-pole high-pass, fc ≈ 24 Hz)
//        Removes any BFO carrier leak / DC component without affecting speech.
//      │
//      ▼
//    [5] Input-referred AGC — peak envelope follower
//        attack   50 ms  : fast settle when a new signal appears.
//        release   1.5 s : longer than AM/NFM — prevents gain pumping between
//                          SSB syllables (characteristic of over-fast SSB AGC).
//        target    0.70  : −3 dBFS, leaves headroom for the limiter.
//        max gain  200×  : recovers signals as weak as −49 dBFS.
//      │
//      ▼
//    [6] Tanh soft limiter (threshold 0.90 FS)
//      │
//      ▼
//    48-kHz PolyphaseResampler (in DspEngine)
//
class SsbDemodulator(private val isUsb: Boolean) : Demodulator() {
    override val mode = if (isUsb) DemodMode.USB else DemodMode.LSB

    private val bfFreq = 1500.0  // Beat frequency oscillator (1.5 kHz)
    private var bfoPhase = 0.0

    // Low-pass FIR — rebuilt whenever the sample rate changes so the cutoff
    // is always 3 kHz regardless of input rate.  The old code hardcoded
    // sampleRate=11025 here, so before narrow pre-decimation was added
    // fc was effectively 0 (3000/deviceRate ≈ 0.001) — the filter passed
    // everything, making USB/LSB sound like full-bandwidth noise.
    private var lpfSampleRate = 0
    private var lpfTaps = FloatArray(0)
    private val LPF_TAPS  = 63          // more taps for better roll-off
    private val LPF_CUTOFF = 3000.0     // SSB voice bandwidth ceiling

    // ── [2] Dual-branch FIR state — passed to NativeDsp.applyFirDual() ─────────
    // NativeDsp keeps the delay lines continuous across block boundaries so there
    // are no clicks at USB-block seams.  bufPos[0] is wrapped mod LPF_TAPS.
    private var lpfBufI  = FloatArray(LPF_TAPS)
    private var lpfBufQ  = FloatArray(LPF_TAPS)
    private val lpfBufPos = IntArray(1)   // single-element so it can be updated by ref

    // ── [4] DC blocker (1-pole high-pass) ──────────────────────────────────────
    // R = 0.9995 → fc ≈ 24 Hz at 48 kHz — removes BFO carrier leak without
    // affecting any speech content above ~50 Hz.
    private var dcX1 = 0f
    private var dcY1 = 0f
    private val DC_R = 0.9995f

    // ── [5] AGC ────────────────────────────────────────────────────────────────
    private var agcGain    = 1f
    private var agcEnv     = 0f
    private var atkAlpha   = 0f
    private var relAlpha   = 0f
    private var agcFsCache = 0

    private fun initAgc(fs: Int) {
        // Longer release (1.5 s) than AM/NFM to prevent gain pumping between
        // syllables — the natural way SSB sounds on a good receiver.
        atkAlpha = exp(-1.0 / (0.050 * fs)).toFloat()   // 50 ms attack
        relAlpha = exp(-1.0 / (1.500 * fs)).toFloat()   //  1.5 s release
        agcGain  = 1f
        agcEnv   = 0f
        agcFsCache = fs
    }

    override fun demodulate(iq: FloatArray, sampleRate: Int): FloatArray {
        val n = iq.size / 2
        val out = FloatArray(n)
        val bfoStep = 2.0 * PI * bfFreq / sampleRate

        // Rebuild the FIR prototype if the sample rate has changed.
        if (sampleRate != lpfSampleRate) {
            // Cap the cutoff to 45 % of Nyquist so the FIR taps remain stable even when
            // the user has manually set a very narrow IF bandwidth (e.g. slider at 4 kHz →
            // sampleRate=4000 Hz → unclamped fc = 3000/4000 = 0.75 which is above the
            // Nyquist limit and produces a completely broken/aliased filter).
            // 0.45 × fs/2 is the same safety margin used by the AM/NFM biquads above.
            val safeCutoff = minOf(LPF_CUTOFF, sampleRate * 0.45)
            lpfTaps = buildLpf(LPF_TAPS, safeCutoff, sampleRate.toDouble())
            lpfSampleRate = sampleRate
            lpfBufI  = FloatArray(LPF_TAPS)
            lpfBufQ  = FloatArray(LPF_TAPS)
            lpfBufPos[0] = 0
        }
        if (sampleRate != agcFsCache) initAgc(sampleRate)

        // ── [1] BFO product detection — mix entire block with NCO ──────────────
        // The BFO is a stateful phase accumulator; it must run sample-by-sample.
        // We keep it in Kotlin and produce an intermediate interleaved mixIQ buffer
        // so that step [2] can be handed off to NativeDsp as a contiguous block.
        val mixIQ = FloatArray(iq.size)
        for (i in 0 until n) {
            val inI = iq[2 * i]
            val inQ = iq[2 * i + 1]

            val bfoI = cos(bfoPhase).toFloat()
            val bfoQ = sin(bfoPhase).toFloat()
            mixIQ[2 * i]     = inI * bfoI - inQ * bfoQ
            mixIQ[2 * i + 1] = inI * bfoQ + inQ * bfoI

            bfoPhase += bfoStep
            if (bfoPhase > 2.0 * PI) bfoPhase -= 2.0 * PI
        }

        // ── [2] Dual-branch FIR LPF — NEON/VOLK block call ───────────────────
        // applyFirDual() processes I and Q channels together in one JNI call,
        // updating lpfBufI/lpfBufQ/lpfBufPos in-place to stay continuous across
        // USB block seams.  Returns an interleaved FloatArray (same length as mixIQ).
        val filtered = NativeDsp.applyFirDual(mixIQ, lpfTaps, lpfBufI, lpfBufQ, lpfBufPos)

        for (i in 0 until n) {
            val filtI = filtered[2 * i]
            val filtQ = filtered[2 * i + 1]

            // ── [3] Sideband select: USB = I+Q, LSB = I−Q ─────────────────────
            val raw = if (isUsb) filtI + filtQ else filtI - filtQ

            // ── [4] DC blocker: y[n] = x[n] − x[n−1] + R·y[n−1] ──────────────
            val dcOut = raw - dcX1 + DC_R * dcY1
            dcX1 = raw;  dcY1 = dcOut

            // ── [5] Input-referred AGC ─────────────────────────────────────────
            val absOut = abs(dcOut)
            agcEnv = if (absOut > agcEnv)
                atkAlpha * agcEnv + (1f - atkAlpha) * absOut   // fast attack
            else
                relAlpha * agcEnv + (1f - relAlpha) * absOut   // slow release
            if (agcEnv > 1e-7f) {
                agcGain = (0.70f / agcEnv).coerceIn(0.001f, 200f)
            }

            // ── [6] Apply gain + tanh soft-limiter ─────────────────────────────
            val g = dcOut * agcGain
            out[i] = when {
                g >  0.90f ->  0.90f + 0.10f * tanh(((g - 0.90f) * 10.0)).toFloat()
                g < -0.90f -> -0.90f - 0.10f * tanh(((-g - 0.90f) * 10.0)).toFloat()
                else       ->  g
            }
        }
        return out
    }

    private fun buildLpf(taps: Int, cutoff: Double, sampleRate: Double): FloatArray {
        val h = FloatArray(taps)
        val fc = cutoff / sampleRate
        val m = (taps - 1) / 2.0
        for (i in 0 until taps) {
            val x = 2.0 * PI * fc * (i - m)
            val sinc = if (x == 0.0) 1.0 else sin(x) / x
            // Hamming window
            val w = 0.54 - 0.46 * cos(2.0 * PI * i / (taps - 1))
            h[i] = (sinc * w).toFloat()
        }
        return h
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  CW DEMODULATOR (Morse code)
// ═════════════════════════════════════════════════════════════════════════════
//
//  Signal flow (all processing at the narrow intermediate rate, 8 kHz):
//
//    IQ samples (baseband — CW carrier offset is at 0 Hz after tuning)
//      │
//      ▼
//    [1] BFO product detector — multiply by complex NCO at ±800 Hz
//        For CW the carrier is a pure tone; the BFO shifts it to 800 Hz
//        in the audio band so the operator hears the classic CW side-tone.
//        CWR (reverse CW) uses −800 Hz, swapping the sideband.
//      │
//      ▼
//    [2] Narrow band-pass FIR (63-tap Hamming, 200–1400 Hz)
//        Rejects all audio outside the CW tone range; the two-stage
//        implementation uses a LPF prototype shifted to band-centre (800 Hz).
//        This removes wideband noise that would otherwise feed the AGC and
//        mask weak signals.
//      │
//      ▼
//    [3] Sideband select: take real part (I channel) — equivalent to
//        USB demodulation for the 800 Hz tone.
//      │
//      ▼
//    [4] DC blocker (1-pole high-pass, fc ≈ 24 Hz)
//        Removes any BFO carrier leak without affecting the audio tone.
//      │
//      ▼
//    [5] Input-referred AGC — peak envelope follower
//        attack   20 ms  : fast enough to respond to a new dit/dah.
//        release   0.5 s : slow enough to ride through inter-element gaps.
//        target    0.70  : −3 dBFS.
//        max gain  500×  : recovers very weak CW signals (−54 dBFS).
//      │
//      ▼
//    [6] Tanh soft limiter (threshold 0.90 FS)
//      │
//      ▼
//    48-kHz PolyphaseResampler (in DspEngine)
//
class CwDemodulator(private val reverse: Boolean = false) : Demodulator() {
    override val mode = if (reverse) DemodMode.CWR else DemodMode.CW

    // BFO frequency — the CW carrier is shifted to this audio frequency.
    private val BFO_FREQ = 800.0
    private var bfoPhase = 0.0

    // Narrow BPF prototype: LPF with cutoff = half the BPF bandwidth (600 Hz),
    // then frequency-shifted to BFO_FREQ by complex mixing.
    // Bandwidth = 1200 Hz centred on 800 Hz → passband 200–1400 Hz.
    private val LPF_TAPS   = 63
    private val LPF_CUTOFF = 600.0   // half-bandwidth of the BPF
    private var lpfSampleRate = 0
    private var lpfTaps    = FloatArray(0)
    private var lpfBufI    = FloatArray(LPF_TAPS)
    private var lpfBufQ    = FloatArray(LPF_TAPS)
    private val lpfBufPos  = IntArray(1)

    // DC blocker
    private var dcX1 = 0f
    private var dcY1 = 0f
    private val DC_R = 0.9995f

    // AGC
    private var agcGain    = 1f
    private var agcEnv     = 0f
    private var atkAlpha   = 0f
    private var relAlpha   = 0f
    private var agcFsCache = 0

    private fun initAgc(fs: Int) {
        atkAlpha   = exp(-1.0 / (0.020 * fs)).toFloat()   // 20 ms attack
        relAlpha   = exp(-1.0 / (0.500 * fs)).toFloat()   // 500 ms release
        agcGain    = 1f
        agcEnv     = 0f
        agcFsCache = fs
    }

    override fun demodulate(iq: FloatArray, sampleRate: Int): FloatArray {
        val n = iq.size / 2
        val out = FloatArray(n)

        // Rebuild the narrow LPF prototype when sample rate changes.
        // Cap cutoff to 45 % of fs to keep FIR stable at very low rates.
        if (sampleRate != lpfSampleRate) {
            val safeCutoff = minOf(LPF_CUTOFF, sampleRate * 0.45)
            lpfTaps = buildLpf(LPF_TAPS, safeCutoff, sampleRate.toDouble())
            lpfSampleRate = sampleRate
            lpfBufI  = FloatArray(LPF_TAPS)
            lpfBufQ  = FloatArray(LPF_TAPS)
            lpfBufPos[0] = 0
        }
        if (sampleRate != agcFsCache) initAgc(sampleRate)

        // ── [1] BFO product detection ─────────────────────────────────────────
        // Mix entire block with a complex NCO at ±BFO_FREQ.
        // CWR (reverse) uses negative frequency — flips the sideband.
        val bfoFreq = if (reverse) -BFO_FREQ else BFO_FREQ
        val bfoStep = 2.0 * PI * bfoFreq / sampleRate
        val mixIQ = FloatArray(iq.size)
        for (i in 0 until n) {
            val inI = iq[2 * i]
            val inQ = iq[2 * i + 1]
            val bfoI = cos(bfoPhase).toFloat()
            val bfoQ = sin(bfoPhase).toFloat()
            // Complex multiply: (inI + j·inQ) × (bfoI + j·bfoQ)
            mixIQ[2 * i]     = inI * bfoI - inQ * bfoQ
            mixIQ[2 * i + 1] = inI * bfoQ + inQ * bfoI
            bfoPhase += bfoStep
            if (bfoPhase > 2.0 * PI) bfoPhase -= 2.0 * PI
        }

        // ── [2] Narrow dual-branch FIR BPF ───────────────────────────────────
        val filtered = NativeDsp.applyFirDual(mixIQ, lpfTaps, lpfBufI, lpfBufQ, lpfBufPos)

        for (i in 0 until n) {
            // ── [3] Take real part — I channel gives the demodulated audio ─────
            val raw = filtered[2 * i]   // Re(filtered IQ)

            // ── [4] DC blocker ────────────────────────────────────────────────
            val dcOut = raw - dcX1 + DC_R * dcY1
            dcX1 = raw;  dcY1 = dcOut

            // ── [5] Input-referred AGC ────────────────────────────────────────
            val absOut = abs(dcOut)
            agcEnv = if (absOut > agcEnv)
                atkAlpha * agcEnv + (1f - atkAlpha) * absOut   // fast attack
            else
                relAlpha * agcEnv + (1f - relAlpha) * absOut   // slow release
            if (agcEnv > 1e-7f) {
                // Higher max gain (500×) than voice modes — CW needs more dynamic range.
                agcGain = (0.70f / agcEnv).coerceIn(0.001f, 500f)
            }

            // ── [6] Apply gain + tanh soft-limiter ────────────────────────────
            val g = dcOut * agcGain
            out[i] = when {
                g >  0.90f ->  0.90f + 0.10f * tanh(((g - 0.90f) * 10.0)).toFloat()
                g < -0.90f -> -0.90f - 0.10f * tanh(((-g - 0.90f) * 10.0)).toFloat()
                else       ->  g
            }
        }
        return out
    }

    /** Hamming-windowed sinc low-pass FIR prototype. */
    private fun buildLpf(taps: Int, cutoff: Double, sampleRate: Double): FloatArray {
        val h  = FloatArray(taps)
        val fc = cutoff / sampleRate
        val m  = (taps - 1) / 2.0
        for (i in 0 until taps) {
            val x    = 2.0 * PI * fc * (i - m)
            val sinc = if (x == 0.0) 1.0 else sin(x) / x
            val win  = 0.54 - 0.46 * cos(2.0 * PI * i / (taps - 1))
            h[i] = (sinc * win).toFloat()
        }
        return h
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  DSB DEMODULATOR (Double Side Band / DSB-SC)
// ═════════════════════════════════════════════════════════════════════════════
// DSB-SC carries audio on both sidebands of a suppressed carrier.
// The correct demodulation is a product detector: multiply by a local carrier
// at 0 Hz (i.e. take the real part of the baseband IQ), then low-pass filter.
// The previous implementation used an FM phase discriminator (d/dt phase),
// which is completely wrong for an AM-family signal — it produced
// differentiated noise instead of audio.
//
// Because the carrier is suppressed the recovered audio amplitude depends on
// carrier phase alignment; a DC-blocking high-pass on the output removes any
// residual carrier leak without affecting voice content.
class DsbDemodulator : Demodulator() {
    override val mode = DemodMode.DSB

    // Single-pole high-pass DC blocker on the demod output
    private var dcX1 = 0f
    private var dcY1 = 0f
    private val DC_R  = 0.9995f   // τ ≈ 2000 samples @ 48 kHz → ~24 Hz corner

    override fun demodulate(iq: FloatArray, sampleRate: Int): FloatArray {
        val n = iq.size / 2
        val out = FloatArray(n)
        for (i in 0 until n) {
            // Product detector with zero-frequency carrier: output = Re(IQ) = I sample.
            // For DSB-SC the baseband signal is centred at 0 Hz after down-conversion,
            // so the I channel already contains L+R (or the full audio for mono DSB).
            val x = iq[2 * i]
            // DC blocker: y[n] = x[n] - x[n-1] + R·y[n-1]
            val y = x - dcX1 + DC_R * dcY1
            dcX1 = x
            dcY1 = y
            out[i] = y
        }
        return out
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  RAW IQ (pass-through for recording / spectrum only)
// ═════════════════════════════════════════════════════════════════════════════
class RawDemodulator : Demodulator() {
    override val mode = DemodMode.RAW

    override fun demodulate(iq: FloatArray, sampleRate: Int): FloatArray {
        // Return magnitude of I/Q pairs for monitoring
        val n = iq.size / 2
        val out = FloatArray(n)
        for (i in 0 until n) {
            out[i] = sqrt(iq[2 * i] * iq[2 * i] + iq[2 * i + 1] * iq[2 * i + 1])
        }
        return out
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  DIGITAL VOICE DEMODULATOR  (DMR / YSF / D-STAR / P25 / NXDN / M17)
// ═════════════════════════════════════════════════════════════════════════════
//
//  Dedicated FM discriminator for digital voice protocols, kept separate from
//  AprsDemodulator so that future changes to either path (different gain,
//  filtering, or post-processing) do not accidentally affect the other.
//
//  Signal chain:
//    IQ at demodRate (≥ 48 kHz)
//      → FM discriminator  gain = fs / (2π × 5000)  (±5 kHz → ±1.0)
//      → DC blocker        α = 0.9999  (single-pole IIR, fc ≈ 0.8 Hz)
//      → float[] at demodRate  (passed to DigitalVoiceDecoder.feed())
//
//  Gain rationale — why ±5 kHz normalisation and NOT the per-protocol
//  outer-deviation value (±1944 Hz for DMR, ±2400 Hz for YSF):
//
//    The dsdcc cosine filter (dmr_filter) is a 61-tap RRC FIR whose
//    worst-case output gain is ~6.83× the peak input before dividing by
//    dmrgain.  For input at ±1.0 (short ±32767) the convolution sum can
//    reach ~±35 600, which overflows int16 when cast to short — producing
//    garbage symbol values and completely destroying 4FSK inner-symbol
//    discrimination.  Using ±5 kHz normalisation keeps DMR/YSF outer
//    symbols at ~39–48 % of FS (±12 750 / ±15 700 short), giving the
//    filter ~2× headroom before saturation, while still giving dsdcc's
//    adaptive min/max tracker enough signal range to converge.
//
//  No AGC, LPF, or limiter is applied here — dsdcc performs its own
//  adaptive level tracking (snapMinMax) and symbol clock recovery
//  internally.
//
class DigitalVoiceDemodulator : Demodulator() {
    override val mode = DemodMode.DMR   // representative; factory creates one per protocol

    private val fmState = FloatArray(2)   // [prevI, prevQ] — stateful across blocks

    private var dcPrev = 0f
    private var dcAcc  = 0f

    private var cachedSampleRate = 0

    private fun init(fs: Int) {
        fmState[0] = 0f; fmState[1] = 0f
        dcPrev = 0f;  dcAcc = 0f
        cachedSampleRate = fs
    }

    override fun demodulate(iq: FloatArray, sampleRate: Int): FloatArray {
        if (sampleRate != cachedSampleRate) init(sampleRate)

        // FM discriminator — ±5 kHz normalisation keeps all digital voice
        // signals within ~40–70 % of int16 FS, safely below the dsdcc
        // cosine-filter saturation ceiling.  See class comment for details.
        val gainHz = sampleRate / (2f * PI.toFloat() * 5_000f)
        val disc = NativeDsp.fmDemodulateStateful(iq, fmState, gainHz)

        // DC blocker — removes discriminator DC offset without distorting
        // the 4FSK symbol eye (single-pole IIR, fc ≈ 0.8 Hz at 48 kHz).
        val out = FloatArray(disc.size)
        for (i in disc.indices) {
            dcAcc  = 0.9999f * (dcAcc + disc[i] - dcPrev)
            dcPrev = disc[i]
            out[i] = dcAcc
        }
        return out
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  FACTORY
// ═════════════════════════════════════════════════════════════════════════════
object DemodulatorFactory {
    fun create(mode: DemodMode): Demodulator = when (mode) {
        DemodMode.AM     -> AmDemodulator()
        // ACARS is transmitted on an AM carrier (VDL Mode A).  The AM demodulator
        // recovers the baseband audio that contains the 1200/2400 Hz AFSK subcarrier
        // tones.  Previously ACARS fell through to RawDemodulator(), which returned
        // IQ magnitudes at device rate — completely wrong input for AcarsDecoder.
        DemodMode.ACARS  -> AmDemodulator()
        DemodMode.FM,
        DemodMode.NFM    -> NfmDemodulator()
        DemodMode.APRS   -> AprsDemodulator()  // FIX 17: raw discriminator, no AGC/LPF/limiter
        // Digital voice — each protocol gets its own DigitalVoiceDemodulator
        // instance so that APRS and digital voice signal chains are fully
        // decoupled and can evolve independently.
        DemodMode.DMR,
        DemodMode.P25,
        DemodMode.NXDN,
        DemodMode.DSTAR,
        DemodMode.YSF,
        DemodMode.M17,
        DemodMode.DPMR,
        DemodMode.DIG    -> DigitalVoiceDemodulator()
        DemodMode.WFM    -> WfmDemodulator(stereo = false)
        DemodMode.WFM_STEREO -> WfmDemodulator(stereo = true)
        DemodMode.USB    -> SsbDemodulator(isUsb = true)
        DemodMode.LSB    -> SsbDemodulator(isUsb = false)
        DemodMode.CW     -> CwDemodulator(reverse = false)
        DemodMode.CWR    -> CwDemodulator(reverse = true)
        DemodMode.DSB    -> DsbDemodulator()
        else             -> RawDemodulator()
    }
}
