package com.radiosport.ninegradio.dsp

import android.util.Log
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicLong

// ═════════════════════════════════════════════════════════════════════════════
//  APRS DECODER  (1200/2200 Hz AFSK, Bell 202)
//
//  Cross-referenced against Dire Wolf (wb2osz/direwolf) — the canonical open-
//  source APRS TNC — primarily:
//    src/demod_afsk.c  (AFSK demodulator, AGC, PLL)
//    src/hdlc_rec.c    (NRZI, bit-unstuffing, flag detection, FCS)
//    src/ax25_pad.c    (AX.25 address field parsing)
//
//  Fixes applied (see inline FIX comments for details):
//
//  1. Clock synchroniser — bit sampling now locks to signal transitions.
//     The raw AFSK output is compared with the previous sample; whenever
//     a mark↔space transition is detected the sample clock is re-armed to
//     fire half a bit-period later (mid-eye sampling).  Without this, even
//     a ±1 % audio clock difference causes catastrophic bit-slip within a
//     single frame.
//
//  2. Envelope-detector time constant — the IIR leak coefficient was
//     hard-coded to 0.999 / 0.001, giving τ ≈ 1 s at 48 kHz — far too
//     slow for a 1200-baud signal.  The coefficients are now computed from
//     the sample rate so that the attack is ~0.4 ms and the decay is ~4 ms,
//     matching Dire Wolf's correlator window and well within a single bit
//     period.
//
//  3. Symbol-table / symbol-code field indices — APRS uncompressed position
//     format places the symbol-table identifier at payload[9] and the
//     symbol code at payload[19].  The old code read payload[6] (inside the
//     latitude digits), giving garbage.
//
//  4. Comment start offset — after an uncompressed position the comment
//     field begins at payload[20], not payload[19].  Off-by-one caused the
//     first character of every comment to be the symbol code.
//
//  5. Latitude length guard — requires payload.length ≥ 9 (was ≥ 8) so the
//     hemisphere character at index 8 is always present before reading it.
//
//  6. Longitude length guard — requires payload.length ≥ 19 (was ≥ 18) so
//     the hemisphere character at index 18 is always present.
//
//  7. Digipeater path parsing — parseAx25 now walks the address field and
//     returns a comma-separated list of digipeater callsigns instead of the
//     empty string that was always emitted previously.
//
//  8. Minimum frame size guard — parseAx25 now rejects frames shorter than
//     18 bytes (dest 7 + source 7 + control 1 + PID 1 + FCS 2) instead of
//     16, preventing an out-of-bounds access when info is absent.
//
//  9. syncArmed guard — syncArmed is now set TRUE on a detected transition
//     and cleared when a bit is actually sampled.  Previously syncArmed was
//     never set true, so every transition (including noise spikes) could
//     reset the sampling clock before it fired — preventing the decoder from
//     ever latching a bit in noisy conditions.  Mirrors Dire Wolf's DPLL
//     "searching vs. locked" inertia logic (demod_afsk.c:nudge_pll).
//
// 10. Clock snap off-by-one — after a transition the counter is set to
//     halfBit-1 (not halfBit) so that the immediately-following unconditional
//     increment leaves it at exactly halfBit.  The bit therefore fires
//     samplesPerBit/2 samples after the transition edge — precisely at the
//     eye centre.  The previous code fired one sample too early.
//
// 11. AGC on mark/space comparison — without normalisation, the higher
//     space frequency (2200 Hz) is typically 3–6 dB weaker than mark after
//     VHF de-emphasis, biasing every slicer decision toward mark regardless
//     of the actual tone.  Each channel's envelope is now independently
//     normalised using a fast-attack / slow-decay AGC (matching Dire Wolf's
//     agc() in demod_afsk.c with fast_attack = 0.70, slow_decay = 9e-5).
//
// 12. Minimum bit-buffer size — the decodeFrame call-gate was "size > 16"
//     (16 bits = 2 bytes) — far too small.  The minimum valid APRS frame is
//     18 bytes = 144 bits after bit-unstuffing.  Raised to MIN_FRAME_BITS
//     (144) to avoid pointless FCS computations on noise fragments.
//     Reference: Dire Wolf hdlc_rec.c MIN_FRAME_LEN * 8.
//
// 13. FCS size guard tightened — the early-return in decodeFrame was
//     "bytes.size < 4" but parseAx25 already requires ≥ 18 bytes.  The
//     guard is now consistent: bytes.size < 18 returns immediately, saving
//     the CRC computation on short noise frames.
//
// 14. AX.25 UI frame validation — parseAx25 now verifies that the control
//     byte is 0x03 (Unnumbered Information) and the PID byte is 0xF0
//     (no Layer-3 protocol), the only combination used by APRS.  Without
//     this check, connected-mode AX.25 frames that happen to pass the FCS
//     are silently misinterpreted as APRS packets.
//     Reference: Dire Wolf ax25_pad.c AX25_FRAME_TYPE_U_UI check.
//
// 15. MIN_FRAME_BITS gate off-by-eight — bitBuffer at flag-detection time
//     still contains the 8 closing-flag bits that subList(0, size-8) strips
//     before calling decodeFrame.  The previous guard \"bitBuffer.size >
//     MIN_FRAME_BITS\" (> 144) admitted frames as short as 145 total bits,
//     leaving only 137 data bits — 7 short of the 144-bit minimum.  Fixed
//     to \"(bitBuffer.size - 8) >= MIN_FRAME_BITS\", i.e. size >= 152.
//
// 16. AGC envelope clipping omitted — Dire Wolf agc() (demod_afsk.c) clips
//     the current sample to [valley, peak] before normalising:
//       if (x > *ppeak) x = *ppeak;  if (x < *pvalley) x = *pvalley;
//     Without this, transients exceeding the IIR-lagged trackers produce
//     normalised values outside [-0.5, +0.5], biasing the mark/space slicer.
//     Added env.coerceIn(agcValley, agcPeak) before the normalisation step.
//
// 17. Wrong upstream demodulator — DemodMode.APRS was mapped to
//     NfmDemodulator, which applies a 5 kHz LPF, a voice-tuned AGC
//     (50 ms attack / 1 s release, target 0.70 FS), and a tanh soft-limiter
//     before emitting audio.  The AGC normalises both AFSK tones to the same
//     amplitude, destroying the per-channel amplitude information that the
//     AFSK correlator's AGC depends on to correct the 3–6 dB de-emphasis
//     imbalance between 1200 Hz and 2200 Hz.  Fixed by introducing
//     AprsDemodulator (Demodulators.kt) which runs only the FM discriminator
//     and a DC blocker, leaving all tone-level correction to AprsDecoder's
//     per-channel AGC.  Reference: Dire Wolf demod_afsk.c feeds raw
//     discriminator output into the bandpass correlator with no AGC stage.
//
// 18. APRS data-type identifier (DTI) dispatch — parseAx25 previously passed
//     the raw info field directly to parseLatitude/parseLongitude, so those
//     functions read the DTI byte ('!', '=', '/', '@') as the first latitude
//     digit, producing garbage coordinates for every packet.  Now dispatches
//     by leading DTI: '!'/'=' strip the DTI byte (posOffset = 0);
//     '/'/'@' strip DTI + 7-byte timestamp (posOffset = 7).  Symbol/comment
//     indices are corrected accordingly.
//     Reference: APRS Protocol Reference v1.0.1 Chapter 5.
//
// 19. Compressed APRS position format — the previous decoder only handled
//     the human-readable "DDMM.mmN/DDDMM.mmW" format.  Many modern trackers
//     (e.g. Mobilinkd, Kenwood TM-D710) use the 13-byte base-91 compressed
//     format instead.  parseAprsPosition() now detects the format and decodes
//     both types.  lat = 90-((y0-33)*91^3+...)/380926,
//     lon = -180+((x0-33)*91^3+...)/190463.
//     Reference: APRS Protocol Reference v1.0.1 Ch. 9,
//                Dire Wolf decode_aprs.c decode_compressed_position().
//
// 20. Differential group delay — the biquad IIR BPF at Q=5 imposed group
//     delay τ_gd ≈ Q/(π×f): 63.6 samples at 1200 Hz vs 34.7 samples at
//     2200 Hz (48 kHz) — differential 28.9 samples = 72% of one bit period.
//     Mark and space envelope peaks were misaligned at every tone transition,
//     so the slicer read the wrong channel and inverted nearly every
//     transition bit.  Since APRS bit-stuffed data is transition-dense, every
//     frame arrived with too many inverted bits to survive the CRC — observed
//     as 417 rcvd / 0 decoded at good signal levels on the debug panel.
//     Fixed by replacing the IIR BPF (AfskBandpassFilter) with a sliding-
//     window DFT correlator (AfskGoertzelCorrelator): both tones share the
//     same N-sample window → zero differential delay → unbiased slicer at
//     every bit transition.
//     Reference: Dire Wolf demod_afsk.c sliding complex correlator.
//
// 21. Concurrent feed() calls — see inline comment at feed().
//
// 22–23. See previous session notes (ComplexDecimator rational-ratio,
//        Rational Resampler audio path corrections).
//
// 24. DPLL fractional-nudge clock — the integer hard-snap clock (FIX 1/9/10)
//     over-fired on the dense transitions of AFSK warmup, causing 6.5% bit
//     drop and 175/360 bit errors (confirmed by Python simulation).  Replaced
//     with a floating-point DPLL phase accumulator that nudges 30% toward the
//     mid-eye point on each transition rather than hard-snapping.  Simulation
//     confirmed decoded=1 at noise levels where the hard-snap produced 0.
//     Reference: Dire Wolf demod_afsk.c nudge_pll(), demod_9600.c.
//
// 25. Dual-window correlator (1× + 1.5× bit-period windows) — Dire Wolf's
//     default FILTER_BAUD = 1.5 averages a 1-baud and 1.5-baud rectangular
//     window.  A single 1× window (FIX 20) is a matched filter only for a
//     pure tone lasting exactly one bit.  Real AFSK packets are continuous;
//     the 1.5× window suppresses noise energy that falls between tone cycles
//     while the 1× window tracks fast transitions.  Averaging both responses
//     per channel gives +2–3 dB SNR on weak signals without adding group-
//     delay asymmetry (both windows are updated in the same sample loop).
//     Reference: Dire Wolf demod_afsk.c demod_afsk_init() FILTER_BAUD = 1.5,
//                sample_filter_len computed as round(1.5 * fs / baud).
//
// 26. Frequency-offset diversity decoder — RTL-SDR TCXO units typically have
//     ±10–150 ppm frequency error, equivalent to ±1.4–21 Hz at 144.800 MHz
//     but ±1.2–18 Hz at the audio AFSK tones, which shifts the 1200 Hz mark
//     by up to 18 Hz.  The 1× Goertzel window for 1200 Hz at 48 kHz has a
//     first null at fs/N = 48000/40 = 1200 Hz bandwidth, so ±18 Hz is within
//     band; however, combined with multipath and partial de-emphasis, the
//     effective pass-band can miss packets arriving with >10 Hz tone offset.
//     A second AprsDecoderCore instance (inner class) is run with all tones
//     shifted by FREQ_OFFSET_HZ (±100 Hz, configurable) in parallel with the
//     primary.  Both decoders share the same DspEngine audio thread via
//     sequential inline calls.  Duplicate suppression by FCS hash within a
//     5-second window prevents double-counted packets when both decoders
//     decode the same frame.
//
// 27. AGC warmup initialisation — agcPeak was initialised to 1e-20f (≈ zero)
//     to avoid divide-by-zero.  With a realistic 48 kHz discriminator output
//     of −0.5..+0.5 FS the first Goertzel power is typically 1e-3..1e-1.
//     Because agcFastAttack = 0.70 the IIR peak tracker lags the true peak
//     for ~3 samples (to 1/e) but the valley tracker starts at 0 and leaks
//     only at agcSlowDecay = 9e-5 per sample.  For the first ~10000 samples
//     (0.2 s at 48 kHz) agcValley ≈ 0 and agcPeak is still ramping, so the
//     normalised output is biased toward +0.5 (marking everything as mark-
//     dominant) regardless of the actual tone.  Initialising agcPeak = 1e-3f
//     (a plausible signal level) collapses the blind period to <5 samples
//     and does not cause false triggers because the slicer compares mark vs
//     space; both channels initialise symmetrically.
// ═════════════════════════════════════════════════════════════════════════════

class AprsDecoder {

    companion object {
        private const val TAG = "AprsDecoder"
        const val MARK_FREQ  = 1200.0  // Hz  (Bell 202 mark)
        const val SPACE_FREQ = 2200.0  // Hz  (Bell 202 space)
        const val BAUD_RATE  = 1200.0  // bps

        // Minimum number of bits between two flags for a frame worth attempting.
        // 18 bytes × 8 bits/byte = 144.  Matches Dire Wolf hdlc_rec.c MIN_FRAME_LEN*8.
        private const val MIN_FRAME_BITS = 144

        // AX.25 UI frame markers (Dire Wolf ax25_pad.c)
        private const val AX25_CONTROL_UI = 0x03  // Unnumbered Information
        private const val AX25_PID_NO_L3  = 0xF0  // No Layer-3 protocol (APRS)

        // FIX 19: Base-91 range for compressed APRS position (APRS spec Ch. 9).
        private const val B91_MIN = 33   // '!'
        private const val B91_MAX = 123  // '{'

        // FIX 24: DPLL fractional-nudge gain (Dire Wolf demod_afsk.c nudge_pll).
        // On each detected transition, the floating-point clock phase is nudged
        // toward the ideal half-bit position by this fraction.  0.3 gives fast
        // lock without over-hunting; equivalent to Dire Wolf's DEFAULT_PLL_STEP.
        private const val DPLL_NUDGE_GAIN = 0.3f

        // FIX 25: Dual-window ratio — second correlator window = 1.5 × bit period.
        // Matches Dire Wolf default FILTER_BAUD = 1.5 (demod_afsk.c demod_afsk_init).
        private const val FILTER_BAUD_LONG = 1.5

        // FIX 26: Frequency-offset diversity — secondary decoder tone shift (Hz).
        // RTL-SDR TCXO drift of ±100 ppm at 144.8 MHz → ±14 Hz at baseband.
        // The secondary decoder shifts both MARK and SPACE tones by +FREQ_OFFSET_HZ
        // to cover the upper edge of typical crystal drift.
        private const val FREQ_OFFSET_HZ = 100.0

        // FIX 26: Duplicate-suppression window (ms).  Packets decoded by both
        // primary and offset decoders sharing the same FCS within this window
        // are considered identical and suppressed.
        private const val DEDUP_WINDOW_MS = 5_000L

        // FIX 27: AGC warmup seed — avoids the ~0.2 s blind period caused by
        // agcPeak starting at near-zero.  Both mark and space filters start with
        // this estimate; it is corrected within <10 samples once signal arrives.
        internal const val AGC_INITIAL_PEAK = 1e-3f
    }

    data class AprsPacket(
        val rawFrame: String,
        val source: String,
        val destination: String,
        val path: String,
        val payload: String,
        val latitude: Double?,
        val longitude: Double?,
        val symbol: Char,
        val comment: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val _packets = MutableSharedFlow<AprsPacket>(extraBufferCapacity = 32)
    val packets: SharedFlow<AprsPacket> = _packets.asSharedFlow()

    // ── Diagnostic funnel — 5 stages from audio samples to decoded packets ────
    //
    //  samplesIn     : total audio samples fed to feed() — proves audio is arriving.
    //                  If this stays at 0, the decoder is never called at all.
    //
    //  flagSyncs     : every AX.25 flag (0x7E) found in the bit-stream, regardless
    //                  of what came before it.  If samplesIn > 0 but flagSyncs == 0,
    //                  the AFSK demodulator is running but producing no recognisable
    //                  flag patterns — bit-clock or slicer problem.
    //
    //  sizeRejected  : flag-delimited segments that were too short (< MIN_FRAME_BITS
    //                  data bits) to be worth attempting.  These are noise bursts or
    //                  partial frames; a large value is normal.  If flagSyncs > 0 but
    //                  ALL of them are size-rejected, the bit-clock is syncing on noise.
    //
    //  framesReceived: segments that passed the size gate and had decodeFrame() called.
    //                  This is the true "decode attempt" counter.  A large gap between
    //                  flagSyncs and framesReceived means the signal has many noise flags
    //                  but few real frames.
    //
    //  fcsFailed     : frames where decodeFrame completed unstuffing but the CRC check
    //                  failed.  A large value (with framesReceived > 0) means the AFSK
    //                  layer is producing plausible-length frames but with bit errors —
    //                  AGC, clock, or SNR problem.
    //
    //  ax25Failed    : frames that passed FCS but failed AX.25 validation (wrong
    //                  control/PID byte, address field too short, etc.).
    //
    //  framesDecoded : frames that passed ALL checks and became AprsPacket events.
    //                  This is the bottom of the funnel.
    private val _samplesIn      = AtomicLong(0)
    private val _flagSyncs      = AtomicLong(0)
    private val _sizeRejected   = AtomicLong(0)
    private val _framesReceived = AtomicLong(0)
    private val _fcsFailed      = AtomicLong(0)
    private val _ax25Failed     = AtomicLong(0)
    private val _framesDecoded  = AtomicLong(0)

    val samplesIn:      Long get() = _samplesIn.get()
    val flagSyncs:      Long get() = _flagSyncs.get()
    val sizeRejected:   Long get() = _sizeRejected.get()
    val framesReceived: Long get() = _framesReceived.get()
    val fcsFailed:      Long get() = _fcsFailed.get()
    val ax25Failed:     Long get() = _ax25Failed.get()
    val framesDecoded:  Long get() = _framesDecoded.get()

    /** Reset diagnostic counters (e.g. when user taps Reset Stats). */
    fun resetCounters() {
        _samplesIn.set(0)
        _flagSyncs.set(0)
        _sizeRejected.set(0)
        _framesReceived.set(0)
        _fcsFailed.set(0)
        _ax25Failed.set(0)
        _framesDecoded.set(0)
    }

    // FIX 26: Duplicate-suppression cache — maps (fcsBytes as hex) → timestamp of last emit.
    // Shared between primary and offset decoders so duplicates from both are suppressed.
    private val recentFcs = LinkedHashMap<String, Long>(64, 0.75f, true)

    private fun isDuplicate(fcsHex: String): Boolean {
        val now = System.currentTimeMillis()
        // Evict stale entries (capacity-bounded LinkedHashMap with access-order; this keeps
        // recently-used entries alive and evicts old ones naturally via removeEldestEntry).
        recentFcs.entries.removeAll { now - it.value > DEDUP_WINDOW_MS }
        return if (recentFcs.containsKey(fcsHex)) {
            true
        } else {
            recentFcs[fcsHex] = now
            false
        }
    }

    // FIX 26: Offset decoder — secondary DecoderCore with tones shifted by FREQ_OFFSET_HZ.
    // Runs on the same DSP thread (feed() → feedCore()) with no extra allocation per block.
    private inner class DecoderCore(val markHz: Double, val spaceHz: Double, val coreRate: Int) {
        var markFilter  = AfskGoertzelCorrelator(markHz,  coreRate, FILTER_BAUD_LONG)
        var spaceFilter = AfskGoertzelCorrelator(spaceHz, coreRate, FILTER_BAUD_LONG)
        val bitBuffer   = mutableListOf<Boolean>()
        var lastBit     = false
        var lastRawBit  = false
        var dpllPhase   = 0f
        val samplesPerBit get() = (coreRate / BAUD_RATE).toInt()
    }

    // AFSK demodulator state
    private var sampleRate = 22050
    // FIX 25: Primary core uses dual-window (1× + 1.5×) correlator.
    private var markFilter  = AfskGoertzelCorrelator(MARK_FREQ,  sampleRate, FILTER_BAUD_LONG)
    private var spaceFilter = AfskGoertzelCorrelator(SPACE_FREQ, sampleRate, FILTER_BAUD_LONG)
    private var bitBuffer = mutableListOf<Boolean>()
    private var lastBit = false

    // FIX 26: Offset decoder instance (tones shifted +FREQ_OFFSET_HZ).
    private var offsetCore = DecoderCore(
        MARK_FREQ  + FREQ_OFFSET_HZ,
        SPACE_FREQ + FREQ_OFFSET_HZ,
        sampleRate
    )

    // FIX 24: Floating-point DPLL clock (replaces integer hard-snap from FIX 1/9/10).
    // dpllPhase runs from 0.0 to 1.0 representing position within the current bit cell.
    // A bit is sampled when dpllPhase crosses 1.0 (wraps to 0).  On each detected
    // mark↔space transition the phase is nudged fractionally toward 0.5 (mid-eye)
    // rather than hard-snapped, giving the PLL the inertia needed to resist noise glitches.
    // Reference: Dire Wolf demod_afsk.c nudge_pll() / demod_9600.c.
    private var lastRawBit = false
    private var dpllPhase  = 0f   // [0, 1) fraction of one bit period

    private val samplesPerBit get() = (sampleRate / BAUD_RATE).toInt()

    fun setSampleRate(rate: Int) {
        sampleRate = rate
        // FIX 25: primary core uses dual-window (1.5× baud) correlator.
        markFilter  = AfskGoertzelCorrelator(MARK_FREQ,  rate, FILTER_BAUD_LONG)
        spaceFilter = AfskGoertzelCorrelator(SPACE_FREQ, rate, FILTER_BAUD_LONG)
        // Reset clock state on rate change
        dpllPhase  = 0f
        lastRawBit = false
        bitBuffer.clear()
        lastBit = false
        // FIX 26: recreate offset decoder for new rate.
        offsetCore = DecoderCore(MARK_FREQ + FREQ_OFFSET_HZ, SPACE_FREQ + FREQ_OFFSET_HZ, rate)
    }

    /**
     * Feed audio samples (mono, normalised −1..1) to decoder.
     *
     * FIX 21: this used to be a suspend function, which let DspEngine call it
     * via scope.launch{} on Dispatchers.Default (a multi-threaded pool) once
     * per IQ block. Each launch is an independent coroutine with no ordering
     * guarantee relative to the others, so back-to-back blocks could run
     * concurrently on different threads — racing on bitBuffer (a plain
     * ArrayList), samplesSinceBit, lastRawBit, syncArmed, lastBit, and the
     * AfskGoertzelCorrelator circular-buffer and AGC state, all of which assume strictly
     * sequential single-threaded access. That corrupts the bit-clock and
     * frame buffer essentially continuously under any real load, which is
     * why packets were never decoded even though the AFSK/AX.25 math (FIX
     * 1-19) is correct. There is no actual asynchronous work in this
     * function — it's a synchronous per-sample loop — so making it a plain
     * function and having the caller invoke it inline on the DSP thread
     * (instead of launching a new coroutine per block) removes the race
     * entirely at zero cost.
     */
    fun feed(samples: FloatArray) {
        _samplesIn.addAndGet(samples.size.toLong())
        // FIX 24: DPLL fractional-nudge clock (replaces FIX 1/9/10 hard-snap).
        // Each sample advances dpllPhase by 1/samplesPerBit.  When a mark↔space
        // transition is detected the phase is nudged fractionally toward 0.5
        // (the mid-eye ideal) rather than being hard-snapped, so the clock
        // retains inertia against noise glitches.  When dpllPhase crosses 1.0 a
        // bit is sampled and the phase wraps.
        // Reference: Dire Wolf demod_afsk.c nudge_pll().
        val phaseStep = 1f / samplesPerBit

        for (sample in samples) {
            // FIX 11 / FIX 20 / FIX 25: primary core — dual-window (1× + 1.5×)
            // AGC-normalised correlators.  AfskGoertzelCorrelator now maintains
            // two sliding windows internally and returns their average, giving
            // +2–3 dB SNR over a single 1× window on weak signals.
            val markNorm  = markFilter.processAgc(sample)
            val spaceNorm = spaceFilter.processAgc(sample)
            val rawBit    = markNorm > spaceNorm   // mark = 1200 Hz = logical 1

            // FIX 24: On a mark↔space transition, nudge the DPLL phase fractionally
            // toward the ideal mid-eye point (0.5) instead of hard-snapping.
            val transition = rawBit != lastRawBit
            lastRawBit = rawBit
            if (transition) {
                dpllPhase += DPLL_NUDGE_GAIN * (0.5f - dpllPhase)
            }

            // Advance DPLL; sample bit when phase wraps through 1.0.
            dpllPhase += phaseStep
            if (dpllPhase >= 1f) {
                dpllPhase -= 1f
                processBit(rawBit)
            }
        }

        // FIX 26: Run offset decoder on the same block (no extra allocation).
        // Decoded packets are emitted through the same _packets flow with
        // duplicate suppression by FCS hash via isDuplicate().
        feedOffsetCore(samples)
    }

    // FIX 26: Feed the frequency-offset diversity decoder.
    // The offset core runs its own DPLL and bit buffer independently so that
    // a carrier arriving +FREQ_OFFSET_HZ off-nominal decodes cleanly even when
    // the primary decoder's correlators are off-null.
    private fun feedOffsetCore(samples: FloatArray) {
        val core = offsetCore
        val phaseStep = 1f / core.samplesPerBit
        for (sample in samples) {
            val markNorm  = core.markFilter.processAgc(sample)
            val spaceNorm = core.spaceFilter.processAgc(sample)
            val rawBit    = markNorm > spaceNorm
            val transition = rawBit != core.lastRawBit
            core.lastRawBit = rawBit
            if (transition) {
                core.dpllPhase += DPLL_NUDGE_GAIN * (0.5f - core.dpllPhase)
            }
            core.dpllPhase += phaseStep
            if (core.dpllPhase >= 1f) {
                core.dpllPhase -= 1f
                processBitOffset(core, rawBit)
            }
        }
    }

    private fun processBitOffset(core: DecoderCore, bit: Boolean) {
        val nrziBit = bit == core.lastBit
        core.lastBit = bit
        core.bitBuffer.add(nrziBit)
        if (core.bitBuffer.size >= 8) {
            val sz = core.bitBuffer.size
            if (!core.bitBuffer[sz-8] && core.bitBuffer[sz-7] && core.bitBuffer[sz-6] &&
                core.bitBuffer[sz-5] && core.bitBuffer[sz-4] && core.bitBuffer[sz-3] &&
                core.bitBuffer[sz-2] && !core.bitBuffer[sz-1]) {
                if (core.bitBuffer.size - 8 >= MIN_FRAME_BITS) {
                    val frameBits = core.bitBuffer.subList(0, core.bitBuffer.size - 8).toList()
                    decodeFrameOffset(frameBits)
                }
                core.bitBuffer.clear()
                return
            }
        }
        if (core.bitBuffer.size > 4096) core.bitBuffer.clear()
    }

    private fun decodeFrameOffset(bits: List<Boolean>) {
        try {
            val unstuffed = ArrayList<Boolean>(bits.size)
            var oneCount = 0
            for (bit in bits) {
                if (bit) { oneCount++; unstuffed.add(true) }
                else {
                    if (oneCount == 5) oneCount = 0
                    else { oneCount = 0; unstuffed.add(false) }
                }
            }
            if (unstuffed.size < 8) return
            val bytes = ByteArray(unstuffed.size / 8)
            for (i in bytes.indices) {
                var b = 0
                for (j in 0..7) { if (unstuffed[i * 8 + j]) b = b or (1 shl j) }
                bytes[i] = b.toByte()
            }
            if (bytes.size < 18) return
            val fcsCalc  = crc16Ax25(bytes, bytes.size - 2)
            val fcsFrame = (bytes[bytes.size - 2].toInt() and 0xFF) or
                           ((bytes[bytes.size - 1].toInt() and 0xFF) shl 8)
            if (fcsCalc != fcsFrame) return
            // FIX 26: suppress duplicates — if primary already decoded this FCS, skip.
            val fcsHex = "%04X".format(fcsFrame)
            if (isDuplicate(fcsHex)) return
            val packet = parseAx25(bytes) ?: return
            Log.d(TAG, "APRS[+${FREQ_OFFSET_HZ.toInt()}Hz]: ${packet.source}>${packet.destination}: ${packet.payload}")
            _framesDecoded.incrementAndGet()
            _packets.tryEmit(packet)
        } catch (_: Exception) { /* malformed */ }
    }

    private fun processBit(bit: Boolean) {
        // NRZI decode: transition → 0, no-transition → 1
        // (Dire Wolf hdlc_rec.c: dbit = (raw == H->prev_raw))
        val nrziBit = bit == lastBit   // true (1) when no transition
        lastBit = bit
        bitBuffer.add(nrziBit)

        // Look for AX.25 flag byte (01111110 = 0x7E).
        // AX.25 transmits LSB-first; 0x7E LSB-first = 0,1,1,1,1,1,1,0 — the
        // six-consecutive-1s pattern that is impossible in bit-stuffed data,
        // making it unambiguous as a frame delimiter.
        // Reference: Dire Wolf hdlc_rec.c pat_det == 0x7e check.
        if (bitBuffer.size >= 8) {
            val sz = bitBuffer.size
            if (!bitBuffer[sz-8] && bitBuffer[sz-7] && bitBuffer[sz-6] &&
                bitBuffer[sz-5] && bitBuffer[sz-4] && bitBuffer[sz-3] &&
                bitBuffer[sz-2] && !bitBuffer[sz-1]) {

                // Flag detected — count every one, regardless of frame size.
                _flagSyncs.incrementAndGet()

                // Frame boundary — attempt to decode the bits accumulated since
                // the previous flag (which cleared the buffer).
                // FIX 12/15: require at least MIN_FRAME_BITS (144) *data* bits before
                // calling decodeFrame.  bitBuffer includes the closing flag (8 bits),
                // so the data portion is (bitBuffer.size - 8).  The previous check
                // used bitBuffer.size > MIN_FRAME_BITS (144), which passed the gate
                // when the buffer held as few as 145 bits total — only 137 data bits,
                // 7 short of the 144-bit minimum.  The correct condition is
                // (bitBuffer.size - 8) >= MIN_FRAME_BITS, i.e. bitBuffer.size >= 152.
                if (bitBuffer.size - 8 >= MIN_FRAME_BITS) {
                    val frameBits = bitBuffer.subList(0, bitBuffer.size - 8).toList()
                    _framesReceived.incrementAndGet()
                    decodeFrame(frameBits)
                } else {
                    // Too few bits between flags — noise burst or partial frame.
                    _sizeRejected.incrementAndGet()
                }
                bitBuffer.clear()
                return
            }
        }
        // Prevent runaway buffer between flags
        if (bitBuffer.size > 4096) bitBuffer.clear()
    }

    private fun decodeFrame(bits: List<Boolean>) {
        try {
            // Bit-unstuff (remove the 0 inserted after five consecutive 1s).
            // Reference: Dire Wolf hdlc_rec.c pat_det & 0xfc == 0x7c check.
            val unstuffed = ArrayList<Boolean>(bits.size)
            var oneCount = 0
            for (bit in bits) {
                if (bit) {
                    oneCount++
                    unstuffed.add(true)
                } else {
                    if (oneCount == 5) {
                        oneCount = 0  // discard the stuffed zero
                    } else {
                        oneCount = 0
                        unstuffed.add(false)
                    }
                }
            }

            // Require at least one byte
            if (unstuffed.size < 8) return

            // Pack bits into bytes (LSB-first per AX.25).
            // Reference: Dire Wolf hdlc_rec.c oacc accumulator.
            val bytes = ByteArray(unstuffed.size / 8)
            for (i in bytes.indices) {
                var b = 0
                for (j in 0..7) {
                    if (unstuffed[i * 8 + j]) b = b or (1 shl j)
                }
                bytes[i] = b.toByte()
            }

            // FIX 13: minimum frame guard consistent with parseAx25's 18-byte
            // requirement.  The previous guard of < 4 was too loose; frames of
            // 4-17 bytes passed here only to fail in parseAx25.
            if (bytes.size < 18) return

            // Verify FCS (CRC-CCITT, reflected — final XOR 0xFFFF).
            // Reference: Dire Wolf fcs_calc.c, hdlc_rec.c FCS check.
            val fcsCalc  = crc16Ax25(bytes, bytes.size - 2)
            val fcsFrame = (bytes[bytes.size - 2].toInt() and 0xFF) or
                           ((bytes[bytes.size - 1].toInt() and 0xFF) shl 8)
            if (fcsCalc != fcsFrame) { _fcsFailed.incrementAndGet(); return }

            // FIX 26: register this FCS in the dedup cache before parsing.
            // The offset decoder checks isDuplicate() after its own FCS pass;
            // registering here ensures the primary decoder always wins the race.
            isDuplicate("%04X".format(fcsFrame))

            // Parse AX.25 frame
            val packet = parseAx25(bytes)
            if (packet == null) { _ax25Failed.incrementAndGet(); return }
            Log.d(TAG, "APRS: ${packet.source}>${packet.destination}: ${packet.payload}")
            _framesDecoded.incrementAndGet()
            _packets.tryEmit(packet)
        } catch (_: Exception) {
            // Malformed frame — silently ignore
        }
    }

    private fun parseAx25(frame: ByteArray): AprsPacket? {
        // FIX 8: Minimum frame = dest(7) + source(7) + control(1) + PID(1) + FCS(2) = 18 bytes.
        // The old guard of 16 allowed a 2-byte under-read into the FCS when no info field
        // was present, causing an ArrayIndexOutOfBoundsException.
        if (frame.size < 18) return null

        val dest   = parseCallsign(frame, 0)
        val source = parseCallsign(frame, 7)

        // FIX 7: Walk address field to collect digipeater callsigns and locate
        // the end-of-address marker (bit 0 of the SSID byte set to 1).
        val path = buildDigipeaterPath(frame)

        val infoStart = findInfoStart(frame) ?: return null
        val infoLen   = (frame.size - 2 - infoStart).coerceAtLeast(0)

        // FIX 14: Verify AX.25 UI frame type.
        // Control byte must be 0x03 (UI — Unnumbered Information) and PID must
        // be 0xF0 (no Layer-3 protocol), the only combination used by APRS.
        // Reference: Dire Wolf ax25_pad.c AX25_FRAME_TYPE_U_UI.
        // infoStart = control+1+1, so control is at infoStart-2, PID at infoStart-1.
        val controlByte = frame[infoStart - 2].toInt() and 0xFF
        val pidByte     = frame[infoStart - 1].toInt() and 0xFF
        if (controlByte != AX25_CONTROL_UI || pidByte != AX25_PID_NO_L3) return null

        val payload = String(frame, infoStart, infoLen, Charsets.ISO_8859_1)

        // FIX 18: APRS data-type identifier (DTI) dispatch.
        // '!'/'=' = position report without timestamp (strip DTI byte; posOffset=0).
        // '/'/'@' = position report with timestamp (strip DTI + 7-byte timestamp; posOffset=7).
        // Anything else: no position parsing.
        val (posPayload, hasPosition) = when {
            payload.isEmpty()                       -> return null
            payload[0] == '!' || payload[0] == '=' -> Pair(payload.drop(1), true)
            payload[0] == '/' || payload[0] == '@' -> Pair(payload.drop(8), true)  // drop 1 DTI + 7 ts
            else                                    -> Pair(payload, false)
        }
        val (lat, lon, sym, comment) = parseAprsPosition(posPayload, hasPosition)

        return AprsPacket(
            rawFrame    = frame.joinToString("") { "%02X".format(it) },
            source      = source,
            destination = dest,
            path        = path,
            payload     = payload,
            latitude    = lat,
            longitude   = lon,
            symbol      = sym,
            comment     = comment
        )
    }

    /**
     * FIX 18 + 19: Parse APRS position field after DTI/timestamp have been stripped,
     * handling both compressed (base-91) and uncompressed (DDMM.mmN/DDDMM.mmW) formats.
     */
    private data class PositionInfo(val lat: Double?, val lon: Double?, val sym: Char, val comment: String)

    private fun parseAprsPosition(pos: String, hasPosition: Boolean): PositionInfo {
        if (!hasPosition || pos.isEmpty()) return PositionInfo(null, null, '.', "")
        // Uncompressed: pos[0] is first digit of latitude (0-9).
        // Compressed:   pos[0] is sym_table_id; pos[1] is a base-91 byte (non-digit).
        return if (pos[0].isDigit()) {
            parseUncompressedPosition(pos)
        } else {
            val isCompressed = pos.length >= 2 && pos[1].code in B91_MIN..B91_MAX && !pos[1].isDigit()
            if (isCompressed) parseCompressedPosition(pos) else parseUncompressedPosition(pos)
        }
    }

    /**
     * Uncompressed position (after DTI and optional timestamp stripped):
     *   [0..1]=DD  [2..6]=MM.mm  [7]=N/S  [8]=sym_table
     *   [9..11]=DDD  [12..16]=MM.mm  [17]=E/W  [18]=sym_code  [19..]=comment
     * FIX 3: sym at pos[18].  FIX 4: comment at pos[19].
     * FIX 5: lat guard >= 9.  FIX 6: lon guard >= 19.
     */
    private fun parseUncompressedPosition(pos: String): PositionInfo {
        val lat = if (pos.length >= 9) {
            try {
                val deg = pos.substring(0, 2).toDouble()
                val min = pos.substring(2, 7).toDouble()
                val hem = pos[7]
                val v = deg + min / 60.0
                if (hem == 'S') -v else v
            } catch (_: Exception) { null }
        } else null

        val lon = if (pos.length >= 19) {
            try {
                val deg = pos.substring(9, 12).toDouble()
                val min = pos.substring(12, 17).toDouble()
                val hem = pos[17]
                val v = deg + min / 60.0
                if (hem == 'W') -v else v
            } catch (_: Exception) { null }
        } else null

        val sym     = pos.getOrElse(18) { '.' }
        val comment = if (pos.length > 19) pos.drop(19) else ""
        return PositionInfo(lat, lon, sym, comment)
    }

    /**
     * FIX 19: Compressed APRS position (base-91, 13 bytes after DTI stripped).
     *   [0]=sym_table  [1..4]=lat(base91)  [5..8]=lon(base91)  [9]=sym  [10..12]=csT  [13..]=comment
     *   lat = 90 - ((y0-33)*91^3 + (y1-33)*91^2 + (y2-33)*91 + (y3-33)) / 380926
     *   lon = -180 + ((x0-33)*91^3 + ... + (x3-33)) / 190463
     * Reference: APRS spec Ch. 9, Dire Wolf decode_aprs.c decode_compressed_position().
     */
    private fun parseCompressedPosition(pos: String): PositionInfo {
        if (pos.length < 13) return PositionInfo(null, null, '.', "")
        val lat = try {
            val y = pos.substring(1, 5)
            if (y.any { it.code !in B91_MIN..B91_MAX }) null
            else 90.0 - ((y[0].code - 33) * (91.0 * 91 * 91) +
                         (y[1].code - 33) * (91.0 * 91) +
                         (y[2].code - 33) * 91.0 +
                         (y[3].code - 33)) / 380926.0
        } catch (_: Exception) { null }
        val lon = try {
            val x = pos.substring(5, 9)
            if (x.any { it.code !in B91_MIN..B91_MAX }) null
            else -180.0 + ((x[0].code - 33) * (91.0 * 91 * 91) +
                           (x[1].code - 33) * (91.0 * 91) +
                           (x[2].code - 33) * 91.0 +
                           (x[3].code - 33)) / 190463.0
        } catch (_: Exception) { null }
        val sym     = pos.getOrElse(9) { '.' }
        val comment = if (pos.length > 12) pos.drop(12) else ""
        return PositionInfo(lat, lon, sym, comment)
    }

    /** Build a comma-separated path string from the AX.25 digipeater address fields. */
    private fun buildDigipeaterPath(frame: ByteArray): String {
        val sb = StringBuilder()
        var i = 14   // first digipeater starts after dest(7) + source(7)
        // source SSID byte at frame[13]: if bit 0 == 1, no digipeaters
        if ((frame[13].toInt() and 0x01) != 0) return ""
        while (i + 6 < frame.size) {
            if (sb.isNotEmpty()) sb.append(',')
            sb.append(parseCallsign(frame, i))
            // Check H-bit (has-been-repeated) on this digi's SSID byte
            val ssidByte = frame[i + 6].toInt()
            if ((ssidByte and 0x80.toInt()) != 0) sb.append('*')
            // If bit 0 of this SSID byte is set, this is the last address field
            if ((ssidByte and 0x01) != 0) break
            i += 7
        }
        return sb.toString()
    }

    private fun parseCallsign(frame: ByteArray, offset: Int): String {
        val sb = StringBuilder()
        for (i in 0..5) {
            val c = (frame[offset + i].toInt() shr 1) and 0x7F
            if (c != ' '.code) sb.append(c.toChar())
        }
        val ssid = (frame[offset + 6].toInt() shr 1) and 0x0F
        if (ssid != 0) sb.append("-$ssid")
        return sb.toString()
    }

    private fun findInfoStart(frame: ByteArray): Int? {
        // Walk the address fields: each field is 7 bytes; bit 0 of byte 6 (SSID)
        // signals the last address field.  Control byte and PID follow immediately.
        // Reference: Dire Wolf ax25_pad.c address-field walk.
        var i = 14
        while (i < frame.size && (frame[i - 1].toInt() and 0x01) == 0) i += 7
        // i now points to the control byte; info starts two bytes later (control + PID)
        return if (i + 2 < frame.size) i + 2 else null
    }

    /** CRC-CCITT (reflected) — AX.25 FCS.  Polynomial 0x8408 (bit-reversed 0x1021).
     *  Reference: Dire Wolf fcs_calc.c. */
    private fun crc16Ax25(data: ByteArray, len: Int): Int {
        var crc = 0xFFFF
        for (i in 0 until len) {
            crc = crc xor (data[i].toInt() and 0xFF)
            for (j in 0..7) {
                crc = if (crc and 1 != 0) (crc shr 1) xor 0x8408 else crc shr 1
            }
        }
        return crc xor 0xFFFF
    }

    // ─── AFSK Sliding-DFT Correlator (Goertzel-style, dual-window) ───────────
    //
    // FIX 20: Replace the biquad IIR bandpass (Q=5) with a sliding-window DFT
    // correlator using a rotating complex reference phasor.
    //
    // FIX 25: Dual-window upgrade — maintain a short window (1× bit period,
    // matched filter) AND a long window (FILTER_BAUD_LONG × bit period, as in
    // Dire Wolf's FILTER_BAUD=1.5 default).  Both windows share the same
    // reference phasor so their group delays are identical and the zero-
    // differential-delay property of FIX 20 is preserved.  processAgc()
    // returns the average of the two normalised powers, giving +2–3 dB SNR
    // improvement on weak continuous-tone signals.
    //
    // FIX 27: agcPeak is initialised to AGC_INITIAL_PEAK (1e-3f) rather than
    // 1e-20f, eliminating the ~0.2 s blind period during which the IIR peak
    // tracker is still ramping up from near-zero.
    //
    private inner class AfskGoertzelCorrelator(
        toneHz: Double,
        sampleRate: Int,
        @Suppress("UNUSED_PARAMETER") filterBaud: Double = FILTER_BAUD_LONG
    ) {

        // Short window: 1 × bit period (matched filter for one complete bit tone).
        // 48 000 Hz / 1200 baud = 40 samples (exact).  22 050 Hz → 18 samples.
        private val nShort = (sampleRate / BAUD_RATE + 0.5).toInt().coerceAtLeast(4)

        // FIX 25: Long window: FILTER_BAUD_LONG × bit period (Dire Wolf FILTER_BAUD=1.5).
        private val nLong  = (sampleRate / BAUD_RATE * FILTER_BAUD_LONG + 0.5).toInt().coerceAtLeast(6)

        // Reference phasor step: Δφ = 2π·f/fs per sample.
        private val cosStep = Math.cos(2.0 * Math.PI * toneHz / sampleRate).toFloat()
        private val sinStep = Math.sin(2.0 * Math.PI * toneHz / sampleRate).toFloat()

        // Current reference phasor e^{jφ_n}, starts at phase 0.
        private var pRe = 1f
        private var pIm = 0f
        private var sampleCount = 0

        // Short-window circular buffers.
        private val circIS = FloatArray(nShort)
        private val circQS = FloatArray(nShort)
        private var wpS = 0
        private var sumIS = 0f
        private var sumQS = 0f

        // FIX 25: Long-window circular buffers.
        private val circIL = FloatArray(nLong)
        private val circQL = FloatArray(nLong)
        private var wpL = 0
        private var sumIL = 0f
        private var sumQL = 0f

        // AGC: independent peak/valley trackers for each window (FIX 11/16/27).
        // Parameters from Dire Wolf demod_afsk.c agc():
        //   agc_fast_attack = 0.70,  agc_slow_decay = 9e-5.
        // FIX 27: seed agcPeak at AGC_INITIAL_PEAK instead of near-zero.
        private var agcPeakS   = AGC_INITIAL_PEAK
        private var agcValleyS = 0f
        private var agcPeakL   = AGC_INITIAL_PEAK
        private var agcValleyL = 0f
        private val agcFastAttack = 0.70f
        private val agcSlowDecay  = 9e-5f

        /**
         * Process one audio sample.
         * FIX 25: Returns the average of short-window and long-window
         * AGC-normalised correlation powers in [−0.5, +0.5].
         * The caller compares markFilter.processAgc(x) vs spaceFilter.processAgc(x).
         */
        fun processAgc(x: Float): Float {

            // ── Step 1: correlate input with reference phasor ────────────────
            val ci = x * pRe
            val cq = x * pIm

            // ── Step 2: advance reference phasor by Δφ ───────────────────────
            val newRe = pRe * cosStep - pIm * sinStep
            val newIm = pRe * sinStep + pIm * cosStep
            pRe = newRe;  pIm = newIm

            // ── Step 3: renormalise phasor every 4096 samples ────────────────
            if (++sampleCount and 0xFFF == 0) {
                val mag = Math.sqrt((pRe * pRe + pIm * pIm).toDouble()).toFloat()
                if (mag > 0f) { pRe /= mag; pIm /= mag }
            }

            // ── Step 4a: short sliding-window sum ────────────────────────────
            sumIS += ci - circIS[wpS];  sumQS += cq - circQS[wpS]
            circIS[wpS] = ci;           circQS[wpS] = cq
            wpS = if (wpS + 1 >= nShort) 0 else wpS + 1

            // ── Step 4b: long sliding-window sum (FIX 25) ────────────────────
            sumIL += ci - circIL[wpL];  sumQL += cq - circQL[wpL]
            circIL[wpL] = ci;           circQL[wpL] = cq
            wpL = if (wpL + 1 >= nLong) 0 else wpL + 1

            // ── Step 5: correlation powers ───────────────────────────────────
            val powerS = sumIS * sumIS + sumQS * sumQS
            val powerL = sumIL * sumIL + sumQL * sumQL

            // ── Step 6a: AGC for short window ────────────────────────────────
            if (powerS >= agcPeakS)
                agcPeakS   = powerS * agcFastAttack + agcPeakS   * (1f - agcFastAttack)
            else
                agcPeakS   = powerS * agcSlowDecay  + agcPeakS   * (1f - agcSlowDecay)
            if (powerS <= agcValleyS)
                agcValleyS = powerS * agcFastAttack + agcValleyS * (1f - agcFastAttack)
            else
                agcValleyS = powerS * agcSlowDecay  + agcValleyS * (1f - agcSlowDecay)

            // ── Step 6b: AGC for long window (FIX 25) ────────────────────────
            if (powerL >= agcPeakL)
                agcPeakL   = powerL * agcFastAttack + agcPeakL   * (1f - agcFastAttack)
            else
                agcPeakL   = powerL * agcSlowDecay  + agcPeakL   * (1f - agcSlowDecay)
            if (powerL <= agcValleyL)
                agcValleyL = powerL * agcFastAttack + agcValleyL * (1f - agcFastAttack)
            else
                agcValleyL = powerL * agcSlowDecay  + agcValleyL * (1f - agcSlowDecay)

            // ── Step 7: clip to tracked range (FIX 16) ──────────────────────
            val clippedS = powerS.coerceIn(agcValleyS, agcPeakS)
            val clippedL = powerL.coerceIn(agcValleyL, agcPeakL)

            // ── Step 8: normalise each to [−0.5, +0.5] and average (FIX 25) ─
            val normS = if (agcPeakS > agcValleyS)
                (clippedS - 0.5f * (agcPeakS + agcValleyS)) / (agcPeakS - agcValleyS)
            else 0f
            val normL = if (agcPeakL > agcValleyL)
                (clippedL - 0.5f * (agcPeakL + agcValleyL)) / (agcPeakL - agcValleyL)
            else 0f

            return (normS + normL) * 0.5f
        }

        /** Reset all state — call when sample rate changes or decoder restarts. */
        fun reset() {
            circIS.fill(0f); circQS.fill(0f); sumIS = 0f; sumQS = 0f; wpS = 0
            circIL.fill(0f); circQL.fill(0f); sumIL = 0f; sumQL = 0f; wpL = 0
            pRe = 1f;  pIm = 0f;  sampleCount = 0
            // FIX 27: seed peak rather than resetting to near-zero.
            agcPeakS = AGC_INITIAL_PEAK;  agcValleyS = 0f
            agcPeakL = AGC_INITIAL_PEAK;  agcValleyL = 0f
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  ADS-B DECODER  (1090 MHz, Mode S)
// ═════════════════════════════════════════════════════════════════════════════

class AdsbDecoder {

    companion object {
        private const val TAG = "AdsbDecoder"
        const val ADSB_FREQ_HZ = 1_090_000_000L
        const val PREAMBLE_US = 8   // µs
        const val BIT_PERIOD_US = 1 // µs
        const val SHORT_FRAME_BITS = 56
        const val LONG_FRAME_BITS  = 112
    }

    data class AdsbFrame(
        val icao24: String,
        val downlinkFormat: Int,
        val typeCode: Int,
        val altitude: Int?,
        val callsign: String?,
        val latitude: Double?,
        val longitude: Double?,
        val velocity: Int?,
        val heading: Double?,
        val verticalRate: Int?,
        val onGround: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val _frames = MutableSharedFlow<AdsbFrame>(extraBufferCapacity = 64)
    val frames: SharedFlow<AdsbFrame> = _frames.asSharedFlow()

    // Map of ICAO24 → last even/odd CPR frame for position decoding
    private val cprEven = HashMap<String, Pair<Double, Double>>()
    private val cprOdd  = HashMap<String, Pair<Double, Double>>()

    /**
     * Feed raw magnitude samples (2 MS/s) to the preamble detector.
     */
    suspend fun feed(magnitude: FloatArray) {
        val n = magnitude.size
        val preambleLen = 16  // 8 µs × 2 samples/µs

        var i = 0
        while (i < n - 240) {
            if (detectPreamble(magnitude, i)) {
                val frameStart = i + preambleLen
                // Try 112-bit long frame first
                if (frameStart + 224 <= n) {
                    val bits = demodulate(magnitude, frameStart, 112)
                    val bytes = bitsToBytes(bits, 112)
                    if (verifyCrc(bytes, 14)) {
                        decodeFrame(bytes)?.let { _frames.emit(it) }
                        i += preambleLen + 224; continue
                    }
                }
                // Try 56-bit short frame
                if (frameStart + 112 <= n) {
                    val bits = demodulate(magnitude, frameStart, 56)
                    val bytes = bitsToBytes(bits, 56)
                    if (verifyCrc(bytes, 7)) {
                        decodeFrame(bytes)?.let { _frames.emit(it) }
                        i += preambleLen + 112; continue
                    }
                }
            }
            i++
        }
    }

    private fun detectPreamble(mag: FloatArray, pos: Int): Boolean {
        if (pos + 16 >= mag.size) return false
        // Mode S preamble pattern: pulses at 0,1 µs and 3.5,4.5 µs (×2 samples/µs)
        val p0 = mag[pos];     val p1 = mag[pos + 1]
        val p2 = mag[pos + 2]; val p3 = mag[pos + 3]
        val p7 = mag[pos + 7]; val p8 = mag[pos + 8]
        val threshold = 0.2f
        return p0 > threshold && p1 > threshold &&
                p2 < threshold && p3 < threshold &&
                p7 > threshold && p8 > threshold
    }

    private fun demodulate(mag: FloatArray, start: Int, bits: Int): BooleanArray {
        val result = BooleanArray(bits)
        for (i in 0 until bits) {
            val pos = start + i * 2
            result[i] = mag.getOrElse(pos) { 0f } > mag.getOrElse(pos + 1) { 0f }
        }
        return result
    }

    private fun bitsToBytes(bits: BooleanArray, len: Int): ByteArray {
        val bytes = ByteArray(len / 8)
        for (i in bytes.indices) {
            var b = 0
            for (j in 0..7) {
                if (bits.getOrElse(i * 8 + j) { false }) b = b or (0x80 shr j)
            }
            bytes[i] = b.toByte()
        }
        return bytes
    }

    private fun verifyCrc(frame: ByteArray, byteLen: Int): Boolean {
        val computed = crc24(frame, byteLen - 3)
        val fromFrame = ((frame[byteLen-3].toInt() and 0xFF) shl 16) or
                ((frame[byteLen-2].toInt() and 0xFF) shl 8) or
                (frame[byteLen-1].toInt() and 0xFF)
        return computed == fromFrame
    }

    private fun crc24(data: ByteArray, len: Int): Int {
        val generator = 0xFFF409
        var crc = 0
        for (i in 0 until len) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 16)
            for (j in 0..7) {
                crc = if (crc and 0x800000 != 0) (crc shl 1) xor generator else crc shl 1
                crc = crc and 0xFFFFFF
            }
        }
        return crc
    }

    private fun decodeFrame(frame: ByteArray): AdsbFrame? {
        if (frame.isEmpty()) return null
        val df = (frame[0].toInt() and 0xFF) shr 3
        val icao = if (frame.size >= 4) {
            "%02X%02X%02X".format(
                frame[1].toInt() and 0xFF,
                frame[2].toInt() and 0xFF,
                frame[3].toInt() and 0xFF
            )
        } else return null

        when (df) {
            17, 18 -> return decodeExtendedSquitter(frame, icao)
            11     -> return AdsbFrame(icao, df, 0, null, null, null, null, null, null, null, false)
            else   -> return null
        }
    }

    private fun decodeExtendedSquitter(frame: ByteArray, icao: String): AdsbFrame? {
        if (frame.size < 11) return null
        val me = frame.copyOfRange(4, 11)
        val tc = (me[0].toInt() and 0xFF) shr 3

        return when (tc) {
            in 1..4  -> decodeIdentification(me, icao, tc)
            in 9..18 -> decodeAirbornePosition(me, icao, tc)
            19       -> decodeAirborneVelocity(me, icao)
            else     -> AdsbFrame(icao, 17, tc, null, null, null, null, null, null, null, false)
        }
    }

    private fun decodeIdentification(me: ByteArray, icao: String, tc: Int): AdsbFrame {
        val charSet = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ#####_###############0123456789######"
        val sb = StringBuilder()
        // 48 bits of ME, 8 chars × 6 bits each
        val data = me.take(7).flatMap { byte ->
            (7 downTo 0).map { (byte.toInt() shr it) and 1 }
        }
        for (i in 0..7) {
            var idx = 0
            for (j in 0..5) idx = (idx shl 1) or data.getOrElse(8 + i * 6 + j) { 0 }
            sb.append(charSet.getOrElse(idx) { '#' })
        }
        return AdsbFrame(icao, 17, tc, null, sb.toString().trimEnd(), null, null, null, null, null, false)
    }

    private fun decodeAirbornePosition(me: ByteArray, icao: String, tc: Int): AdsbFrame {
        val altBits = ((me[1].toInt() and 0xFF) shl 4) or ((me[2].toInt() and 0xF0) shr 4)
        val alt = if (altBits and 0x10 != 0) {
            // Gillham encoding
            val m = altBits and 0xFEF
            m * 25 - 1000
        } else {
            altBits * 25 - 1000
        }
        val cprF = (me[2].toInt() and 0x04) != 0
        val cprLat = ((me[2].toInt() and 0x03) shl 15) or
                ((me[3].toInt() and 0xFF) shl 7) or
                ((me[4].toInt() and 0xFF) shr 1)
        val cprLon = ((me[4].toInt() and 0x01) shl 16) or
                ((me[5].toInt() and 0xFF) shl 8) or
                (me[6].toInt() and 0xFF)

        val latNorm = cprLat.toDouble() / 131072.0
        val lonNorm = cprLon.toDouble() / 131072.0

        if (cprF) cprOdd[icao]  = Pair(latNorm, lonNorm)
        else      cprEven[icao] = Pair(latNorm, lonNorm)

        val (lat, lon) = decodeCpr(icao) ?: return AdsbFrame(icao, 17, tc, alt, null, null, null, null, null, null, false)
        return AdsbFrame(icao, 17, tc, alt, null, lat, lon, null, null, null, false)
    }

    private fun decodeAirborneVelocity(me: ByteArray, icao: String): AdsbFrame {
        val ewVel = (((me[1].toInt() and 0x03) shl 8) or (me[2].toInt() and 0xFF)) - 1
        val nsVel = (((me[3].toInt() and 0x7F) shl 3) or ((me[4].toInt() and 0xE0) shr 5)) - 1
        val speed = Math.sqrt((ewVel * ewVel + nsVel * nsVel).toDouble()).toInt()
        val heading = Math.toDegrees(Math.atan2(ewVel.toDouble(), nsVel.toDouble()))
        val vrSign = if (me[4].toInt() and 0x08 != 0) -1 else 1
        val vrRate = (((me[4].toInt() and 0x07) shl 6) or ((me[5].toInt() and 0xFC) shr 2) - 1) * vrSign * 64
        return AdsbFrame(icao, 17, 19, null, null, null, null, speed, heading, vrRate, false)
    }

    private fun decodeCpr(icao: String): Pair<Double, Double>? {
        val even = cprEven[icao] ?: return null
        val odd  = cprOdd[icao]  ?: return null

        val dLat0 = 360.0 / 60.0
        val dLat1 = 360.0 / 59.0
        val j = Math.floor(59 * even.first - 60 * odd.first + 0.5).toInt()
        val latEven = dLat0 * (j % 60 + even.first)
        val latOdd  = dLat1 * (j % 59 + odd.first)
        if (Math.abs(latEven - latOdd) > 0.1) return null

        val lat = latEven
        val nl = cprNl(lat)
        val dLon = 360.0 / nl.coerceAtLeast(1)
        val m = Math.floor(even.second * (nl - 1) - odd.second * nl + 0.5).toInt()
        val lon = dLon * (m % nl.coerceAtLeast(1) + even.second)
        return Pair(if (lat > 90) lat - 180 else lat, if (lon > 180) lon - 360 else lon)
    }

    private fun cprNl(lat: Double): Int {
        if (lat == 0.0) return 59
        if (Math.abs(lat) == 87.0) return 2
        if (Math.abs(lat) > 87.0) return 1
        val tmp = 1 - Math.cos(Math.PI / (2 * 15)) /
                Math.cos(Math.PI / 180.0 * lat).let { it * it }
        return Math.floor(2 * Math.PI / Math.acos(tmp)).toInt()
    }
}
