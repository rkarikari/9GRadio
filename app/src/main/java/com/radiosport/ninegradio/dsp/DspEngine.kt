package com.radiosport.ninegradio.dsp

import android.util.Log
import com.radiosport.ninegradio.debug.DebugBus
import com.radiosport.ninegradio.audio.AudioEngine
import com.radiosport.ninegradio.recording.IqRecorder
import com.radiosport.ninegradio.source.IqSource
import com.radiosport.ninegradio.usb.RtlSdrDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/**
 * Main DSP pipeline:
 *   RTL-SDR IQ → [FIR Decimation] → Demodulator → [Resampler] → Audio
 *                                 ↓
 *                              FFT Engine → Spectrum / Waterfall updates
 *                                 ↓
 *                         Protocol Decoders (APRS, RDS …)
 */
class DspEngine(private val device: IqSource) {

    // Centre frequency used for dual-APRS NCO calculation.
    // Set by enableDualAprs(); cleared to 0 by disableDualAprs().
    // Accessed only from the DSP processing coroutine so no lock needed.
    @Volatile private var dualAprsCentreHz = 0L

    // Reusable scratch buffer for WFM_STEREO → mono mixdown in processIqBlock().
    // Grown only when the block size changes; avoids a FloatArray allocation
    // on every IQ block (~15-20/sec at the WFM IF rate).
    private var monoMixBuf = FloatArray(0)

    companion object {
        private const val TAG = "DspEngine"

        // ── Pipeline block-size constants ──────────────────────────────────────
        /**
         * USB USBDEVFS_BULK transfer size (bytes).
         * Must be a multiple of the USB Full-Speed bulk max-packet size (512 B).
         * 32,768 = 64 packets — efficient USB batching.
         */
        const val USB_STREAMING_BUF = 32_768

        /**
         * DSP block size (bytes) delivered to the IQ flow and processIqBlock().
         *
         * Both sources present this block size to the DSP pipeline:
         *  • USB:   startStreaming() emits 32,768-byte USB transfers split into
         *           2 × DSP_CHUNK_SIZE chunks before the SharedFlow emit.
         *  • TCP:   RtlTcpSource.READ_BUF_SIZE = 16,384 = DSP_CHUNK_SIZE.
         *
         * At 1,920,000 S/s this gives a block period of ≈ 4.27 ms,
         * matching the TCP source's natural granularity and giving both sources
         * equal DSP budget and SharedFlow jitter headroom.
         */
        const val DSP_CHUNK_SIZE = 16_384

        /**
         * Internal intermediate and protocol-decoder audio rate (Hz).
         * Must stay at 48 000 — it is an exact integer divisor of every device
         * sample rate in [RtlSdrDevice.SAMPLE_RATES] (all multiples of 48 000),
         * guaranteeing whole-number ComplexDecimator factors with no fractional
         * resampling artefacts.  Protocol decoders (ACARS, APRS, RDS) also have
         * coefficients built for this rate and must not change.
         */
        const val DEFAULT_AUDIO_RATE = 48_000

        /**
         * Default audio-output (sink) sample rate presented to Android AudioTrack.
         *
         * The audio sink rate is independent of the internal processing rate
         * [DEFAULT_AUDIO_RATE] = 48 000 Hz.  A PolyphaseResampler bridges the two
         * only when they differ.  Selectable via the UI audio-sink chip row.
         */
        // 48 kHz matches DEFAULT_AUDIO_RATE so the WFM resampler ratio is always
        // an exact integer (or trivially 1:1), avoiding the large-interpFactor
        // polyphase-bank collapse that produced "watery"/gritty audio at 44.1 kHz.
        // (GCD(wfmIfRate, 44100) can yield interpFactor = 441 → only 3 taps/branch.)
        const val DEFAULT_AUDIO_SINK_RATE = 48_000

        /**
         * Supported audio-output (sink) sample rates in Hz.
         * Presented as chip buttons in the RF tab and as a ListPreference.
         */
        val AUDIO_SINK_RATES = intArrayOf(48_000, 44_100, 32_000, 24_000, 22_050, 16_000)

        /** Maximum FFT / spectrum update rate (fps). Keeps CPU load bounded on fast sample rates. */
        private const val SPECTRUM_MAX_FPS = 30
        /** Assumed narrowband channel width (Hz) used to average FFT bins
         *  around the tuned (DC) centre frequency for [DspStats.narrowbandCenterDb].
         *  Matches the common 12.5 kHz NFM channel spacing used elsewhere
         *  (e.g. FrequencyScanner's default stepHz); a reasonable default for
         *  gating channel-specific signal level regardless of exact mode. */
        private const val NARROWBAND_CHANNEL_HZ = 12_500.0

        /** Minimum IF bandwidth the user can select (Hz). */
        const val MIN_IF_BANDWIDTH_HZ = 100

        /**
         * SSB carrier / BFO offset (Hz).
         *
         * The BFO mixes with exp(+j*2pi*1500*t) — an UPWARD shift of +1500 Hz:
         *   mixI = inI*cos - inQ*sin ;  mixQ = inI*sin + inQ*cos
         *
         * Hardware must be tuned +1500 Hz ABOVE the dial for BOTH USB and LSB:
         *   hardware = dial + SSB_BFO_OFFSET_HZ
         *
         * USB proof (tone at f_audio):
         *   carrier in IF = dial - hw = -1500 Hz
         *   voice   in IF = -1500 + f_audio  (300-3000 Hz voice -> -1200 to +1500 Hz)
         *   after BFO +1500: f_audio -> within 3 kHz LPF -> I+Q -> correct audio.
         *
         * LSB proof (tone at f_audio below carrier):
         *   voice   in IF = -1500 - f_audio  (-1800 to -4500 Hz)
         *   after BFO +1500: -f_audio -> within 3 kHz LPF -> I-Q -> correct audio.
         *
         * Without the offset, voice shifts to +1800..+4500 Hz after the BFO and is
         * blocked by the 3 kHz LPF, producing silence. (The old dial-1500 for USB
         * was derived assuming a downward-shifting BFO and was therefore wrong here.)
         */
        // Public (not private) so UI code -- specifically the spectrum/waterfall
        // display, which must show the *actual* hardware-tuned frequency, not the
        // dial frequency, to keep the FFT trace aligned with the passband
        // highlight box for USB/LSB -- can apply the identical offset. See
        // MainActivity.hardwareCenterFreqHz().
        const val SSB_BFO_OFFSET_HZ = 1500L
        private const val SPECTRUM_MIN_INTERVAL_MS = 1000L / SPECTRUM_MAX_FPS

        /**
         * Target WFM IF bandwidth (Hz).
         *
         * The actual WFM intermediate rate is chosen per-device-rate to land as
         * close as possible to this target while never exceeding
         * [WFM_MAX_DEMOD_RATE] (the hard 250 kS/s ceiling the WFM demodulator's
         * filters — pilot PLL, stereo BPF, de-emphasis IIR, RDS BPF — are
         * designed/validated for).
         *
         * The WfmDemodulator receives the actual wfmIfRate via the sampleRate
         * parameter of demodulate(), so its de-emphasis IIR, pilot NCO step, and
         * stereo BPF coefficients are always recomputed for the true IF rate.
         *
         * The pre-demodulator decimation from deviceRate → wfmIfRate is performed
         * by [ComplexDecimator], which wraps [PolyphaseResampler] — a fully
         * RATIONAL (L/M) polyphase resampler (interpFactor/decimFactor derived via
         * GCD). It is NOT restricted to integer decimation, so wfmIfRate no longer
         * needs to be an exact divisor of deviceRate.
         *
         * The audio stage then uses a second PolyphaseResampler(wfmIfRate → 48 kHz)
         * to bridge whatever rational ratio results.
         *
         * References: SDR++ dsp/demod/fm.h rational-decimator design;
         * GnuRadio rational_resampler_xxx.
         */
        private const val WFM_TARGET_BW = 200_000

        /**
         * Hard ceiling on the WFM demodulator input (IF) rate, in Hz.
         *
         * The WFM/WFM-Stereo demodulator's pilot-tone PLL, 19 kHz/38 kHz stereo
         * band-pass filters, de-emphasis IIR and the RDS 57 kHz decoder all have
         * their coefficients (and numerical stability margins) validated up to
         * 250 kS/s. [wfmIfRate] therefore NEVER returns a value above this rate —
         * any deviceRate above 250 000 Hz is brought down to ≤ 250 000 Hz via the
         * rational [PolyphaseResampler] in [ComplexDecimator], rather than being
         * passed through unchanged.
         */
        const val WFM_MAX_DEMOD_RATE = 250_000

        /**
         * Compute the WFM intermediate (demodulator input) rate for a given
         * device sample rate, via RATIONAL resampling.
         *
         * Goals, in priority order:
         *   1. wfmIfRate ≤ [WFM_MAX_DEMOD_RATE] (250 000 Hz) — ALWAYS, even for
         *      device rates below 250 kHz (e.g. 256 000 / 264 000 / 272 000 /
         *      286 000 / 288 000 / 300 000 are all > 250 000 and must be
         *      decimated, however slightly).
         *   2. wfmIfRate as close as possible to [WFM_TARGET_BW] (200 000 Hz),
         *      which sits comfortably inside the 150–250 kHz window needed to
         *      pass the FM stereo multiplex (0–53 kHz) plus RDS (57 kHz).
         *   3. Prefer ratios with a small GCD-reduced decimFactor/interpFactor
         *      (i.e. prefer exact-integer divisors of deviceRate when one exists
         *      near the target) to minimise [PolyphaseResampler] CPU cost — but
         *      this is now a *preference*, not a requirement: when no good exact
         *      divisor exists, a fractional ratio is used instead of falling back
         *      to a poorly-placed IF rate.
         *
         * Algorithm:
         *   - If deviceRate ≤ WFM_TARGET_BW (200 000 Hz): return deviceRate
         *     unchanged (N=1 / 1:1, no decimation needed — already below target
         *     and ≤ 250 000).
         *   - Otherwise, search integer divisors N of deviceRate (N = 1..) for
         *     one whose quotient lies in [WFM_TARGET_BW, WFM_MAX_DEMOD_RATE]
         *     (i.e. deviceRate/N ∈ [200 000, 250 000]), preferring the quotient
         *     closest to WFM_TARGET_BW.
         *   - If no exact divisor lands in that window, fall back to the
         *     RATIONAL target: the largest integer ≤ WFM_MAX_DEMOD_RATE that is
         *     ≥ WFM_TARGET_BW where possible, computed as
         *     deviceRate scaled down by a rational factor — concretely,
         *     pick ifRate = WFM_TARGET_BW when deviceRate > WFM_TARGET_BW and no
         *     better exact divisor exists; [ComplexDecimator]/[PolyphaseResampler]
         *     then perform the resulting rational (deviceRate : ifRate) decimation.
         *   - Final safety clamp: ifRate = min(ifRate, WFM_MAX_DEMOD_RATE).
         *
         * Examples:
         *   240 000 → 240 000  (≤ target, N=1, no decimation)
         *   250 000 → 250 000  (≤ target, N=1, no decimation)
         *   256 000 → 200 000  (no exact divisor in [200k,250k]; rational target)
         *   272 000 → 200 000  (no exact divisor in [200k,250k]; rational target)
         *   286 000 → 200 000  (rational target — exceeds 250k otherwise)
         *   300 000 → 200 000  (rational target — exact ÷1.5 not in window)
         *   912 000 → 228 000  (÷4, exact divisor closest to target)
         *   960 000 → 240 000  (÷4, exact divisor)
         * 1 024 000 → 204 800  (÷5, exact divisor in [200k,250k])
         * 1 440 000 → 240 000  (÷6, exact divisor in [200k,250k])
         * 1 920 000 → 240 000  (÷8, exact divisor in [200k,250k])
         * 2 048 000 → 204 800  (÷10, exact divisor in [200k,250k])
         * 2 160 000 → 216 000  (÷10, exact divisor in [200k,250k])
         * 2 400 000 → 200 000  (÷12, exact divisor in [200k,250k])
         */
        fun wfmIfRate(deviceRate: Int): Int {
            // Already within the WFM demodulator's operating range: no decimation
            // required.  Pass the device rate straight through (1:1 ComplexDecimator).
            // This covers the full low-rate band (240 000–300 000 Hz) and any device
            // rate that happens to land at or below the 250 kS/s ceiling.
            if (deviceRate <= WFM_MAX_DEMOD_RATE) {
                return deviceRate
            }

            // Search exact integer divisors of deviceRate whose quotient lands
            // in the [WFM_TARGET_BW, WFM_MAX_DEMOD_RATE] window, preferring the
            // quotient closest to WFM_TARGET_BW.
            var bestRate = -1
            var bestDist = Int.MAX_VALUE
            val nMax = deviceRate / WFM_TARGET_BW
            for (n in 1..maxOf(1, nMax + 1)) {
                if (deviceRate % n != 0) continue
                val ifRate = deviceRate / n
                if (ifRate < WFM_TARGET_BW || ifRate > WFM_MAX_DEMOD_RATE) continue
                val dist = kotlin.math.abs(ifRate - WFM_TARGET_BW)
                if (dist < bestDist) { bestDist = dist; bestRate = ifRate }
            }
            if (bestRate > 0) return bestRate

            // No exact divisor landed in the window — use a RATIONAL decimation
            // target. WFM_TARGET_BW (200 000 Hz) is itself ≤ WFM_MAX_DEMOD_RATE,
            // so this is always safely within the ceiling; ComplexDecimator's
            // PolyphaseResampler reduces (deviceRate : WFM_TARGET_BW) via GCD to
            // the appropriate rational interp/decim ratio.
            return minOf(WFM_TARGET_BW, WFM_MAX_DEMOD_RATE, deviceRate)
        }

        /**
         * Minimum demodulator input rate (Hz).
         *
         * The demodulators' internal filters (NFM biquad at 5 kHz, AM biquad at 8 kHz,
         * SSB LPF at 3 kHz) require at least 2× their highest audio frequency to satisfy
         * Nyquist.  ACARS occupies a 25 kHz channel (±12.5 kHz), so 25 kHz is the
         * broadest per-mode minimum.  Rounding up to 30 kHz gives a comfortable margin.
         *
         * This floor is enforced by [narrowIfRateFor] so no mode ever receives an IF
         * rate too low for its audio bandwidth, regardless of what [audioSinkRate] the
         * user has selected.
         */
        private const val MIN_DEMOD_RATE = 30_000

        /**
         * Compute the narrow-mode intermediate (IF) sample rate for a given device
         * rate and audio-sink rate.
         *
         * This replaces the old [narrowIfRate] which unconditionally targeted 48 kHz,
         * forcing an unnecessary two-stage chain (deviceRate → ~48 k → sinkRate) even
         * when a direct single-stage decimation to sinkRate is possible.  GnuRadio's
         * rational_resampler_xxx block uses exactly this single-stage approach.
         *
         * Algorithm — find the SMALLEST exact divisor of [deviceRate] that is
         * ≥ max([sinkRate], [MIN_DEMOD_RATE]):
         *
         *   1. Compute N_max = deviceRate / max(sinkRate, MIN_DEMOD_RATE).
         *   2. Walk N from 1 to N_max (largest-to-smallest IF rate):
         *        if (deviceRate % N == 0) AND (deviceRate/N ≥ floor), record it.
         *   3. The last recorded rate is the smallest exact-divisor IF ≥ floor.
         *
         * This guarantees:
         *   • PURE INTEGER ComplexDecimator factor (deviceRate / ifRate is an integer).
         *   • The IF rate is never smaller than the sink rate (no audio aliasing).
         *   • The IF rate is as small as possible, minimising the final resampler
         *     ratio and therefore maximising audio quality and minimising CPU cost.
         *
         * When [sinkRate] is itself an exact divisor of [deviceRate] and ≥ MIN_DEMOD_RATE,
         * the returned IF rate equals [sinkRate] — the PolyphaseResampler is trivially
         * 1:1 (no-op) and the demodulator runs at the final audio rate, identical to
         * GnuRadio's direct-decimation path.
         *
         * Examples:
         *   250 000, sinkRate=32 000:  smallest divisor ≥ 32 000 → 50 000 (÷5)
         *   250 000, sinkRate=24 000:  smallest divisor ≥ 24 000 → 25 000 (÷10)
         * 1 024 000, sinkRate=32 000:  smallest divisor ≥ 32 000 → 32 000 (÷32, direct!)
         * 2 560 000, sinkRate=32 000:  smallest divisor ≥ 32 000 → 32 000 (÷80, direct!)
         * 2 560 000, sinkRate=48 000:  smallest divisor ≥ 48 000 → 51 200 (÷50)
         *
         * Note: 2 560 000 has NO exact divisor near 48 000 Hz (old hardcoded target),
         * causing the old [narrowIfRate] to fall back to a fractional-ratio decimation
         * that produced distorted audio.  This function has no such failure mode.
         */
        fun narrowIfRateFor(deviceRate: Int, sinkRate: Int): Int {
            val floor = maxOf(sinkRate, MIN_DEMOD_RATE)
            val nMax = deviceRate / floor   // largest N that keeps ifRate >= floor
            var bestRate = deviceRate       // fallback: no-decimation (N=1)
            for (n in 1..maxOf(1, nMax)) {
                if (deviceRate % n == 0) {
                    val ifRate = deviceRate / n
                    if (ifRate >= floor) bestRate = ifRate
                }
            }
            return bestRate
        }

        /**
         * Backward-compatible single-argument overload — uses [DEFAULT_AUDIO_RATE]
         * (48 000 Hz) as the sink rate.  Retained for the dual-APRS path which
         * always needs 48 kHz output for the protocol decoders, and for any callers
         * outside the main audio chain.
         */
        fun narrowIfRate(deviceRate: Int): Int = narrowIfRateFor(deviceRate, DEFAULT_AUDIO_RATE)

        /** Backward-compat alias — kept so existing log strings compile unchanged. */
        private const val NARROW_INTERMEDIATE_RATE = DEFAULT_AUDIO_RATE   // 48 000 Hz

        /**
         * DC-blocker leaky-integrator coefficient.
         * At 2 MS/s this gives τ ≈ 10 000 samples ≈ 5 ms — fast enough to
         * converge within a couple of USB blocks, slow enough not to affect
         * audio content after FM demodulation.
         */
        private const val DC_ALPHA = 0.9999f

        // APRS channel frequencies used by the dual-watch NCO path
        const val APRS_NA_HZ = 144_390_000L   // North America
        const val APRS_EU_HZ = 144_800_000L   // Europe / Australia
        const val APRS_MIDPOINT_HZ = (APRS_NA_HZ + APRS_EU_HZ) / 2  // 144_595_000
        // Minimum sample rate (Hz) that can cover both channels simultaneously.
        // Each channel is 205 kHz from the midpoint; passband must reach ≥ 205 kHz
        // either side, i.e. total bandwidth ≥ 410 kHz → sample rate ≥ 820 kHz.
        const val DUAL_APRS_MIN_SAMPLE_RATE = 820_000

        /** Set of all digital voice demod modes that feed [DigitalVoiceDecoder]. */
        val DIGITAL_VOICE_MODES = setOf(
            DemodMode.DMR, DemodMode.P25, DemodMode.NXDN,
            DemodMode.DSTAR, DemodMode.YSF, DemodMode.M17,
            DemodMode.DPMR,
            DemodMode.DIG
        )
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Current demodulator
    @Volatile private var demodulator: Demodulator = NfmDemodulator()
    @Volatile private var demodMode = DemodMode.NFM

    // ─── IF Filter / Protocol Bandwidth ──────────────────────────────────────
    // User-adjustable narrow-mode intermediate rate.  The ComplexDecimator
    // down-converts the full device rate to this rate before demodulation,
    // which sets the effective IF bandwidth (≈ 0.9 × ifBandwidthHz).
    //
    // Narrow modes use narrowDecimator; the rate is reset to the mode default
    // whenever the demod mode changes, then the user can narrow or widen it.
    //
    // When -1 (sentinel), the rate is computed dynamically per-block as
    // narrowIfRate(deviceRate) — the closest integer-divisor of the device rate
    // to DEFAULT_AUDIO_RATE (48 kHz).  This handles 250 kS/s (→ 50 kHz) and
    // any future non-48k-multiple device rate without hard-coding.
    // Valid user-set range: 2 kHz – narrowIfRate(deviceRate) for narrow modes.
    @Volatile private var ifBandwidthHz: Int = -1   // -1 = auto (dynamic per device rate)

    // FFT engine for spectrum
    val fftEngine = FftEngine(256)

    // Audio output — recreated via setAudioSinkRate() when the user changes the
    // output sample rate.  @Volatile so setAudioSinkRate() can swap the reference
    // atomically from any thread without holding a lock (start/stop are guarded
    // by the DSP coroutine's sequential execution model).
    @Volatile private var audioSinkRate: Int = DEFAULT_AUDIO_SINK_RATE
    @Volatile private var audioEngine = AudioEngine(DEFAULT_AUDIO_SINK_RATE)

    // IQ recorder
    private val iqRecorder = IqRecorder()

    // RDS decoder (active in WFM/WFM_STEREO mode) — reconstructed with the
    // actual WFM IF rate the first time a WFM block is processed, then reused.
    // Exposed as a val for UI access; the internal decoder is replaced when the
    // device rate changes (see getOrCreateRdsDecoder()).
    private var _rdsDecoder: RdsDecoder = RdsDecoder(213_333)  // placeholder
    val rdsDecoder: RdsDecoder get() = _rdsDecoder
    private var rdsDecoderRate = 0

    // ACARS decoder (active in ACARS mode) — expects 48 kHz audio
    val acarsDecoder = AcarsDecoder(48_000)

    // Paging decoder (active in FLEX mode). POCSAG mode/support has been
    // removed from the app; this decoder (still named PocsagDecoder, as it
    // implements the POCSAG bit-sync/codeword pipeline) is now only invoked
    // for DemodMode.FLEX. See the feed() call site below.
    //
    // FIX 28: this was constructed as PocsagDecoder(22_050), a leftover from
    // before decoderAudio existed, when protocol decoders were fed audio at
    // whatever raw rate the demodulator happened to produce. Every block
    // reaching a protocol decoder is now unconditionally resampled to
    // DEFAULT_AUDIO_RATE (48 kHz) by resampleForDecoder() below — ACARS and
    // APRS were already constructed/configured for 48 kHz to match, but this
    // one was never updated. PocsagDecoder has no setSampleRate(); audioRate
    // is a constructor-only val baked into samplesPerBit (audioRate /
    // detectedBaud) and into autoBaud()'s crossings→Hz conversion. With
    // audioRate=22_050 but real samples arriving at 48_000 Hz, every "sample"
    // the clock-recovery loop counted was only 22050/48000 ≈ 46% of an actual
    // audio sample's worth of time, so samplesPerBit was ~2.18× too small —
    // onBit() fired more than twice as often as the real bit period, autoBaud
    // misclassified the incoming rate against BAUD_RATES, and the bit stream
    // fed to the sync-word search was essentially random. That made POCSAG
    // sync (0x7CD215D8) and therefore every FLEX/POCSAG message silently
    // fail to lock — the demodulator and AGC looked fine, there was simply
    // never a valid bitstream downstream. Constructing at 48_000 to match
    // the actual feed rate is the fix.
    val pocsagDecoder = PocsagDecoder(48_000)

    // APRS decoder (active in APRS mode) — expects 48 kHz audio
    val aprsDecoder = AprsDecoder().also { it.setSampleRate(DEFAULT_AUDIO_RATE) }

    // Digital voice decoder (active in DMR / P25 / NXDN / D-STAR / YSF / M17 modes).
    // Receives 48 kHz raw FM-discriminated audio (same path as APRS — no AGC/LPF/limiter).
    // Identifies the protocol from sync words and — when the DSD-Neo native library
    // is present (libdsd_neo.so in jniLibs/) — decodes voice frames via DigitalVoiceJni.
    val digitalVoiceDecoder = DigitalVoiceDecoder()

    /**
     * Pipes raw 48 kHz FM-discriminator audio (the same pre-vocoder signal
     * fed to [digitalVoiceDecoder]) out over UDP as PCM16LE for an external
     * decoder app (dsd-neo, DSD-FME, DSDPlus, etc.) to consume. Disabled by
     * default; see [ExternalDecoderStream] for the wire format and rationale.
     */
    val externalDecoderStream = ExternalDecoderStream()

    /** Enable/disable piping [digitalVoiceDecoder]'s input audio to the configured external decoder. */
    fun setExternalDecoderEnabled(enabled: Boolean) = externalDecoderStream.setEnabled(enabled)

    /** Change the UDP destination. Safe to call while streaming; takes effect on the next block. */
    fun setExternalDecoderTarget(host: String, port: Int) = externalDecoderStream.configure(host, port)

    // DC block toggle — enabled by default.  Disabling may help when the leaky-
    // integrator time constant is too short for the selected sample rate and
    // produces audible modulation artefacts near the centre frequency.
    @Volatile private var dcBlockEnabled = true

    // ─── Audio Noise Processing ───────────────────────────────────────────────
    // Noise Blanker (NB) — suppresses impulsive noise spikes (ignition, power-line clicks).
    // Noise Reducer (NR) — spectral-subtraction broadband noise reduction.
    // Both are disabled by default and toggled via the UI switch in the control panel.
    private val noiseBlanker = AudioDsp.NoiseBlanker(thresholdFactor = 4f)
    private val noiseReducer = AudioDsp.NoiseReducer()
    @Volatile private var noiseBlankerEnabled = false
    @Volatile private var noiseReducerEnabled = false

    // Software frequency offset — shifts the entire baseband by a fixed Hz amount
    // by rotating the IQ phasor each sample.  Useful when a signal sits slightly
    // off-centre and you want to bring it to zero-IF without re-tuning the hardware.
    // Allowed values: 0 (off), ±1000, ±1500, ±2000 Hz.
    @Volatile private var freqOffsetHz = 0
    // Continuous NCO phase accumulator — preserved across IQ blocks so the complex
    // rotation has no discontinuity at USB bulk-transfer boundaries.
    private var freqShiftPhase = 0.0

    /** Last dial frequency requested by the user (Hz).
     *  Remembered so setCarrierFrequency() can reapply the SSB BFO offset whenever
     *  the demod mode flips between SSB and non-SSB (see setDemodMode()). */
    @Volatile private var dialFreqHz: Long = 0L

    // Squelch
    @Volatile private var squelchLevel = -200f
    @Volatile private var squelchOpen = false

    // Volume
    @Volatile private var volume = 1.0f

    // FFT rate limiter — last wall-clock time a spectrum frame was emitted
    @Volatile private var lastSpectrumMs = 0L

    // DC-blocker state: running IIR mean for I and Q channels.
    // Persists across processIqBlock() calls so the filter is continuous
    // across USB bulk-transfer boundaries (no step artefact at block edges).
    // Only accessed from the single DSP processing coroutine — no lock needed.
    private val dcState = FloatArray(2)   // [dcI, dcQ], initialised to 0f

    // Reusable float-IQ buffer for convertUint8ToFloat() output.  The RTL-SDR
    // always delivers exactly [bufferSize] bytes per block (default 131 072), so
    // this buffer is allocated once on the first block and reused thereafter —
    // eliminating a 512 KB FloatArray allocation on every IQ block (~15–20/sec).
    private var floatIqBuf = FloatArray(0)

    // Spectrum/waterfall publish flow — DROP_OLDEST so tryEmit() never blocks
    private val _spectrumFlow = MutableSharedFlow<FloatArray>(
        replay = 1,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val spectrumFlow: SharedFlow<FloatArray> = _spectrumFlow.asSharedFlow()

    /**
     * Demodulated audio samples at DEFAULT_AUDIO_RATE (48 kHz) — consumed by
     * protocol decoders (APRS, etc.) and any other subscriber needing baseband.
     */
    private val _audioFlow = MutableSharedFlow<FloatArray>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val audioFlow: SharedFlow<FloatArray> = _audioFlow.asSharedFlow()

    /**
     * Raw IQ float samples — consumed by ADS-B decoder which needs magnitude at sample-rate.
     */
    private val _iqMagnitudeFlow = MutableSharedFlow<FloatArray>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val iqMagnitudeFlow: SharedFlow<FloatArray> = _iqMagnitudeFlow.asSharedFlow()

    /**
     * Dual-channel APRS audio — emits a Pair<FloatArray, FloatArray> where
     * [first] is the NA channel (144.390 MHz) and [second] is the EU channel
     * (144.800 MHz), both at DEFAULT_AUDIO_RATE (48 kHz), NFM-demodulated.
     *
     * Only emitted when the device sample rate is wide enough to capture both
     * channels simultaneously (≥ 820 kHz, i.e. the SDR is tuned to the midpoint
     * 144.595 MHz and both ±205 kHz offsets fall inside the passband).
     * When the sample rate is too narrow the flow simply does not emit; callers
     * must fall back to single-channel mode.
     *
     * Each channel is produced by:
     *   1. NCO-mix the full-rate IQ to shift the target frequency to 0 Hz.
     *   2. Polyphase-decimate to narrowIfRate(deviceRate) — the closest exact-integer-
     *      divisor of the device rate to 48 kHz (e.g. 50 kHz at 250 kS/s).
     *   3. NFM discriminator (gain normalised for ±5 kHz deviation at the IF rate).
     *   4. Polyphase-resample to DEFAULT_AUDIO_RATE (48 kHz).
     */
    private val _dualAprsFlow = MutableSharedFlow<Pair<FloatArray, FloatArray>>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val dualAprsFlow: SharedFlow<Pair<FloatArray, FloatArray>> = _dualAprsFlow.asSharedFlow()

    // Per-channel NCO phase accumulators — persist across IQ blocks for glitch-free rotation.
    private var dualAprsPhaseNA = 0.0
    private var dualAprsPhaseEU = 0.0

    // Per-channel ComplexDecimators and resamplers for the dual-APRS path.
    // Recreated whenever the device sample rate changes.
    private var dualAprsDecimNA: ComplexDecimator? = null
    private var dualAprsDecimEU: ComplexDecimator? = null
    private var dualAprsResampNA: PolyphaseResampler? = null
    private var dualAprsResampEU: PolyphaseResampler? = null
    private var dualAprsDeviceRate = 0

    // Per-channel FM discriminator state (previous I/Q sample)
    private var dualAprsPrevINa = 0f; private var dualAprsPrevQNa = 0f
    private var dualAprsPrevIEu = 0f; private var dualAprsPrevQEu = 0f

    /** Recreate dual-APRS DSP objects whenever the device sample rate changes. */
    private fun ensureDualAprsChain(deviceRate: Int) {
        if (deviceRate == dualAprsDeviceRate) return
        val aprsIfRate = narrowIfRate(deviceRate)   // dynamic: 48 kHz for most rates, 50 kHz at 250 kS/s
        dualAprsDecimNA = ComplexDecimator(deviceRate, aprsIfRate)
        dualAprsDecimEU = ComplexDecimator(deviceRate, aprsIfRate)
        dualAprsResampNA = PolyphaseResampler(aprsIfRate, DEFAULT_AUDIO_RATE)
        dualAprsResampEU = PolyphaseResampler(aprsIfRate, DEFAULT_AUDIO_RATE)
        dualAprsDeviceRate = deviceRate
        dualAprsPhaseNA = 0.0; dualAprsPhaseEU = 0.0
        dualAprsPrevINa = 0f; dualAprsPrevQNa = 0f
        dualAprsPrevIEu = 0f; dualAprsPrevQEu = 0f
        Log.d(TAG, "DualAPRS chain created: $deviceRate → $aprsIfRate → $DEFAULT_AUDIO_RATE Hz")
    }

    /**
     * Mix a full-rate interleaved IQ block by [offsetHz] using a continuous NCO,
     * updating [phaseRef] in place.  Returns a new FloatArray (does not modify input).
     */
    private fun ncoMix(iq: FloatArray, deviceRate: Int, offsetHz: Double,
                       phaseRef: DoubleArray): FloatArray {
        val n = iq.size / 2
        val out = FloatArray(iq.size)
        val step = -2.0 * Math.PI * offsetHz / deviceRate   // negate: shift signal down to 0 Hz
        // Phasor-recurrence: same optimisation as the main NCO path — 4 muls instead
        // of 2 transcendentals per sample.
        val stepRe = Math.cos(step)
        val stepIm = Math.sin(step)
        var pRe = Math.cos(phaseRef[0])
        var pIm = Math.sin(phaseRef[0])
        for (i in 0 until n) {
            val cosP = pRe.toFloat()
            val sinP = pIm.toFloat()
            val re = iq[2 * i]; val im = iq[2 * i + 1]
            out[2 * i]     = re * cosP - im * sinP
            out[2 * i + 1] = re * sinP + im * cosP
            val nextRe = pRe * stepRe - pIm * stepIm
            val nextIm = pRe * stepIm + pIm * stepRe
            pRe = nextRe; pIm = nextIm
        }
        val mag = Math.sqrt(pRe * pRe + pIm * pIm)
        if (mag > 0.0) { pRe /= mag; pIm /= mag }
        phaseRef[0] = Math.atan2(pIm, pRe)
        return out
    }

    /**
     * Run a single NFM FM discriminator pass on a decimated IQ block.
     * [prevState] in/out via single-element arrays for continuity.
     * [ifRate] is the sample rate of [iq] (used to scale the discriminator gain).
     * Returns audio at [ifRate].
     *
     * Uses atan2-based phase differentiation (same approach as gnuradio_dsp.cpp /
     * NativeDsp.fmDemodulateStateful) instead of the differential-phase formula
     * (I·dQ−Q·dI)/(I²+Q²).  The old formula divides by instantaneous signal power
     * which approaches zero at carrier nulls, producing spikes of magnitude >> 1.0
     * that manifest as crackling / jittery audio — especially at non-integer IF ratios
     * where block boundaries cause the null to realign differently each block.
     * atan2f is bounded to (−π, π] regardless of signal level — no division, no spike.
     */
    private fun nfmDiscriminate(iq: FloatArray, prevState: FloatArray,
                                 ifRate: Int = DEFAULT_AUDIO_RATE): FloatArray {
        val n = iq.size / 2
        val out = FloatArray(n)
        val gain = ifRate / (2f * Math.PI.toFloat() * 5_000f)
        val pi2 = (2.0 * Math.PI).toFloat()

        // prevState[0] = previous I, prevState[1] = previous Q
        var prevPhase = Math.atan2(prevState[1].toDouble(), prevState[0].toDouble()).toFloat()

        for (i in 0 until n) {
            val ci = iq[2 * i]; val cq = iq[2 * i + 1]
            val phase = Math.atan2(cq.toDouble(), ci.toDouble()).toFloat()
            var diff = phase - prevPhase
            // Phase unwrap to (−π, π]
            if (diff >  Math.PI)  diff -= pi2
            if (diff < -Math.PI)  diff += pi2
            out[i] = diff * gain
            prevPhase = phase
        }
        // Store last sample so phase is continuous across USB block boundaries
        val lastI = iq[(n - 1) * 2]
        val lastQ = iq[(n - 1) * 2 + 1]
        prevState[0] = lastI; prevState[1] = lastQ
        return out
    }

    // DSP statistics
    private val _stats = MutableStateFlow(DspStats())
    val statsFlow: StateFlow<DspStats> = _stats.asStateFlow()

    data class DspStats(
        val sampleRate: Int = 0,
        val audioRate: Int = DEFAULT_AUDIO_SINK_RATE,
        val demodMode: String = "NFM",
        val squelchOpen: Boolean = false,
        val signalDb: Float = -120f,
        /** Average power in a narrow window of FFT bins centred on the tuned
         *  frequency (DC bin) — unlike [signalDb] (the wideband spectrum
         *  peak, used for the live spectrum-display squelch), this reflects
         *  only the channel currently tuned to and is what a channelized
         *  scanner should gate on. See [FrequencyScanner] squelch fix notes. */
        val narrowbandCenterDb: Float = -120f,
        val audioVolume: Float = 1f,
        val isRecordingIq: Boolean = false,
        val isRecordingAudio: Boolean = false,
        val bufferDrops: Int = 0
    )

    private var processingJob: Job? = null

    // ─── Decoupled audio output ───────────────────────────────────────────────
    // processIqBlock() must never block the IQ collection coroutine waiting on
    // AudioTrack.write().  When WRITE_BLOCKING is used and the AudioTrack buffer
    // is full, the DSP coroutine stalls.  While it stalls, the USB IQ producer
    // keeps emitting into _iqFlow (DROP_OLDEST, capacity 17).  After ~3 minutes
    // enough blocks have been silently evicted to produce audible gaps in WFM.
    // The TCP source is immune because the server-side socket buffer absorbs the
    // backpressure and slows the producer, preventing overflow.
    //
    // Fix: processIqBlock() sends audio samples to audioWriteChannel without
    // blocking.  A dedicated audioWriterJob coroutine (on Dispatchers.Default)
    // drains the channel and calls audioEngine.write() — WRITE_BLOCKING is still
    // used there, so playback stays perfectly rate-limited without any busy-loop.
    // The IQ collection coroutine is now decoupled from AudioTrack latency.
    //
    // Channel capacity: 8 × ~1280 float samples/block at 48 kHz = ~1.07 s of
    // headroom.  If the DSP produces audio faster than the AudioTrack can drain
    // it (which should never happen in steady state), the oldest block is dropped
    // rather than blocking the IQ consumer.
    private val audioWriteChannel = Channel<FloatArray>(
        capacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private var audioWriterJob: Job? = null

    // ─── Digital voice audio output ───────────────────────────────────────────
    // When a digital voice mode is active and the DSD-Neo software path or the
    // AMBE hardware plugin has decoded a frame, pcmAudio carries 8 kHz 16-bit
    // PCM samples from the vocoder. A dedicated coroutine subscribes to
    // digitalVoiceDecoder.frames and upsamples + routes non-empty pcmAudio to
    // audioWriteChannel. For digital voice modes this coroutine is the sole
    // writer to that channel — processIqBlock() does not write anything to it
    // in that case (see "Write to AudioTrack" in processIqBlock).
    //
    // Upsampling: 8 kHz → audioSinkRate (48 kHz) using linear interpolation.
    // The ratio 48000/8000 = 6 is exact, so no fractional resampler is needed.
    private var digVoiceAudioJob: Job? = null

    // ─── WFM pre-decimation ───────────────────────────────────────────────────
    // The WFM IF rate is computed adaptively per device rate (see wfmIfRate()).
    // Integer-only decimation eliminates fractional-ratio artefacts and pitch
    // drift; the WfmDemodulator reconstructs all coefficients from the actual
    // IF rate received via sampleRate in demodulate().
    @Volatile private var wfmDecimator: ComplexDecimator? = null
    private var wfmDecimatorInputRate  = 0
    private var wfmDecimatorOutputRate = 0

    private fun getWfmDecimator(deviceRate: Int): Pair<ComplexDecimator, Int> {
        val ifRate = wfmIfRate(deviceRate)
        if (wfmDecimator == null || wfmDecimatorInputRate != deviceRate
                || wfmDecimatorOutputRate != ifRate) {
            wfmDecimator = ComplexDecimator(deviceRate, ifRate)
            wfmDecimatorInputRate  = deviceRate
            wfmDecimatorOutputRate = ifRate
            // deviceRate:ifRate may now be a RATIONAL ratio (not just an integer
            // decimation factor) — reduce via GCD for logging/DebugBus.
            val gRate = gcd(deviceRate, ifRate)
            val decimP = deviceRate / gRate   // input side of the ratio
            val decimQ = ifRate / gRate       // output side of the ratio
            Log.d(TAG, "WFM ComplexDecimator created: $deviceRate → $ifRate Hz " +
                    "(ratio=$decimP:$decimQ, integer=${deviceRate % ifRate == 0})")
            // WFM: the final resampler bridges wfmIfRate → sinkRate
            val g = gcd(ifRate, audioSinkRate)
            DebugBus.setChain(com.radiosport.ninegradio.debug.DebugBus.ChainSnapshot(
                sourceHz    = deviceRate,
                ifHz        = ifRate,
                decimFactor = (deviceRate / ifRate).coerceAtLeast(1),
                sinkHz      = audioSinkRate,
                resampleP   = audioSinkRate / g,
                resampleQ   = ifRate / g,
                direct      = ifRate == audioSinkRate,
                demodMode   = demodMode.displayName,
                wfmIfHz     = ifRate,
                timestampMs = System.currentTimeMillis(),
                decimP      = decimP,
                decimQ      = decimQ
            ))
        }
        return Pair(wfmDecimator!!, ifRate)
    }

    /** Return (or recreate) the RDS decoder for the given WFM IF rate. */
    private fun getOrCreateRdsDecoder(ifRate: Int): RdsDecoder {
        if (ifRate != rdsDecoderRate) {
            _rdsDecoder = RdsDecoder(ifRate)
            rdsDecoderRate = ifRate
        }
        return _rdsDecoder
    }

    // ─── Narrow-mode pre-decimation ──────────────────────────────────────────
    // AM, NFM, USB, LSB, CW, CWR, DSB, ACARS need channel-bandwidth IQ, not the
    // full device bandwidth.  We decimate to narrowIfRateFor(deviceRate, audioSinkRate)
    // — the smallest exact-integer-divisor of deviceRate that is ≥ audioSinkRate.
    //
    // GnuRadio rational_resampler approach:
    //   • 1920kS/s, sinkRate=32kHz  → IF=32kHz  (÷60, direct; no final resampler)
    //   • 250kS/s,  sinkRate=32kHz  → IF=50kHz  (÷5),  then 50k→32k (16/25)
    //   • 2560kS/s, sinkRate=32kHz  → IF=32kHz  (÷80, direct; previously BROKEN)
    //
    // Invalidated by setAudioSinkRate() and setDemodMode(); rebuilt on next IQ block.
    @Volatile private var narrowDecimator: ComplexDecimator? = null
    private var narrowDecimatorInputRate = 0
    private var narrowDecimatorOutputRate = 0

    /**
     * Resolve the effective narrow IF rate for [deviceRate] and the current
     * [audioSinkRate].
     *
     * When [ifBandwidthHz] == -1 (auto), delegates to [narrowIfRateFor](deviceRate,
     * audioSinkRate): the smallest exact-integer-divisor of deviceRate that is ≥
     * audioSinkRate (and ≥ MIN_DEMOD_RATE).  This is the GnuRadio-style single-stage
     * approach — when deviceRate is a multiple of audioSinkRate the IF rate equals
     * the sink rate exactly and the final PolyphaseResampler is a trivial no-op.
     *
     * When [ifBandwidthHz] > 0 (user override), the value is clamped to
     * [MIN_IF_BANDWIDTH_HZ, narrowIfRateFor(deviceRate, audioSinkRate)] so it never
     * exceeds the dynamic IF ceiling for the current device rate and sink rate.
     */
    private fun resolveNarrowIfRate(deviceRate: Int): Int {
        // FIX 23: APRS decoder must receive audio at EXACTLY DEFAULT_AUDIO_RATE (48 kHz).
        //
        // FIX 22 used DEFAULT_AUDIO_RATE as the FLOOR for narrowIfRateFor(), but that
        // function returns the SMALLEST EXACT DIVISOR of deviceRate that is ≥ the floor.
        // For deviceRate = 2 048 000:
        //
        //   narrowIfRateFor(2_048_000, 48_000) returns 51_200 Hz
        //   (51 200 is the smallest exact divisor of 2 048 000 that is ≥ 48 000,
        //    because 2 048 000 / 40 = 51 200 and 2 048 000 is NOT a multiple of 48 000)
        //
        // So the ComplexDecimator still outputs at 51.2 kHz, resampleForDecoder() still
        // runs a 51.2 → 48 kHz PolyphaseResampler (ratio 15/16), and that resampler
        // introduces the same group-delay / phase distortion on the 1200/2200 Hz AFSK
        // tones that causes 99.8% FCS failures — exactly what the debug panel shows:
        //   decode_attempts  1 027
        //   fcs_failed       1 025
        //   decoded              0
        //
        // The root fix: for APRS, bypass the divisor search entirely and target
        // EXACTLY DEFAULT_AUDIO_RATE (48 000 Hz) as the ComplexDecimator output rate.
        // ComplexDecimator wraps PolyphaseResampler, which accepts any rational ratio
        // (L/M = GCD-reduced interpFactor/decimFactor), so 2 048 000 → 48 000 is a
        // valid rational decimation (ratio = 128:3 after GCD=16 000 reduction, interp=3, decim=128).
        // resampleForDecoder() then gets inRate == DEFAULT_AUDIO_RATE and returns the
        // input array unchanged — a true no-op — so the AFSK tones reach AprsDecoder
        // with no post-discriminator resampling artefacts at all.
        //
        // Rate analysis for common RTL-SDR device rates with audioSinkRate = 16 kHz:
        //   OLD (FIX 22): narrowIfRateFor(deviceRate, 48_000) → divisor search
        //     2 048 000 → 51 200 (÷40)  ← 51.2→48 kHz resampler fires  ← BUG
        //     1 920 000 → 48 000 (÷40)  ← no-op ✓
        //     1 024 000 → 64 000 (÷16)  ← 64→48 kHz resampler fires  ← BUG
        //       960 000 → 48 000 (÷20)  ← no-op ✓
        //
        //   NEW (FIX 23): force exactly 48 000, rational ComplexDecimator for all rates
        //     2 048 000 → 48 000 (rational 128:3 via PolyphaseResampler)  ← no-op ✓
        //     1 920 000 → 48 000 (integer ÷40, same as before)            ← no-op ✓
        //     1 024 000 → 48 000 (rational 64:3 via PolyphaseResampler)   ← no-op ✓
        //       960 000 → 48 000 (integer ÷20, same as before)            ← no-op ✓
        //
        // The dual-APRS path (ensureDualAprsChain) already uses narrowIfRate(deviceRate)
        // = narrowIfRateFor(deviceRate, DEFAULT_AUDIO_RATE) → same divisor-search flaw,
        // but adds a second explicit PolyphaseResampler(aprsIfRate → 48 kHz) stage.
        // That path is unaffected by this change and is corrected separately.
        //
        // All other narrow modes continue to use narrowIfRateFor(deviceRate, audioSinkRate)
        // (the GnuRadio single-stage direct-decimation approach) — no change there.
        if (demodMode == DemodMode.APRS) {
            // Force the ComplexDecimator to output at exactly 48 kHz.
            // PolyphaseResampler handles the rational deviceRate:48000 ratio via GCD.
            // resampleForDecoder() sees inRate == DEFAULT_AUDIO_RATE → returns input
            // array unchanged (zero-copy, zero group delay, zero phase distortion).
            val aprsIfRate = DEFAULT_AUDIO_RATE
            return if (ifBandwidthHz <= 0) aprsIfRate
                   else ifBandwidthHz.coerceIn(MIN_IF_BANDWIDTH_HZ, aprsIfRate)
        }
        val auto = narrowIfRateFor(deviceRate, audioSinkRate)
        return if (ifBandwidthHz <= 0) auto
               else ifBandwidthHz.coerceIn(MIN_IF_BANDWIDTH_HZ, auto)
    }

    private fun getNarrowDecimator(deviceRate: Int): ComplexDecimator {
        // Rebuild when the device rate OR the resolved IF rate changes.
        // resolveNarrowIfRate() now calls narrowIfRateFor(deviceRate, audioSinkRate),
        // so the IF rate tracks the sink rate automatically; setAudioSinkRate()
        // invalidates this decimator whenever the sink rate changes.
        val targetRate = resolveNarrowIfRate(deviceRate)
        // Read the volatile field exactly ONCE into a local val so that any
        // concurrent write from setIfBandwidth() / setMode() / setAudioSinkRate()
        // cannot null it out between our null-check and the return — which is the
        // TOCTOU race that caused the NullPointerException at runtime.
        // (The @Volatile annotation on narrowDecimator guarantees visibility but
        // does NOT prevent another thread from storing null in the interval
        // between  `if (narrowDecimator == null …)`  and  `return narrowDecimator!!`.)
        val current = narrowDecimator
        if (current != null
                && narrowDecimatorInputRate  == deviceRate
                && narrowDecimatorOutputRate == targetRate) {
            return current   // fast path — no allocation, no race
        }
        val fresh = ComplexDecimator(deviceRate, targetRate)
        narrowDecimator           = fresh
        narrowDecimatorInputRate  = deviceRate
        narrowDecimatorOutputRate = targetRate
        Log.d(TAG, "Narrow ComplexDecimator created: $deviceRate → $targetRate Hz" +
                " (N=${deviceRate/targetRate}, sinkRate=$audioSinkRate Hz)")
        publishChainSnapshot(deviceRate, targetRate, audioSinkRate)
        return fresh
    }

    /** Publish the current IQ→Audio chain rates to DebugBus for the Debug Panel. */
    private fun publishChainSnapshot(deviceRate: Int, ifRate: Int, sinkRate: Int) {
        val g = gcd(ifRate, sinkRate)
        val p = sinkRate / g
        val q = ifRate   / g
        val isAuto = ifBandwidthHz <= 0
        DebugBus.setChain(com.radiosport.ninegradio.debug.DebugBus.ChainSnapshot(
            sourceHz    = deviceRate,
            ifHz        = ifRate,
            decimFactor = deviceRate / ifRate,
            sinkHz      = sinkRate,
            resampleP   = p,
            resampleQ   = q,
            direct      = ifRate == sinkRate,
            demodMode   = demodMode.displayName,
            wfmIfHz     = 0,
            timestampMs = System.currentTimeMillis(),
            protocolFilterHz   = if (isAuto) ifRate else ifBandwidthHz,
            protocolFilterAuto = isAuto
        ))
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    /** Returns true for modes that benefit from narrow pre-decimation. */
    private fun DemodMode.isNarrowMode() = when (this) {
        DemodMode.AM, DemodMode.NFM, DemodMode.FM,
        DemodMode.USB, DemodMode.LSB,
        DemodMode.CW, DemodMode.CWR,
        DemodMode.DSB,
        DemodMode.APRS,
        // ACARS channels are 25 kHz wide (VHF aviation band).  Without narrow-mode
        // pre-decimation the AM envelope detector integrates broadband noise across
        // the full device capture bandwidth (250 kHz–3.2 MHz), raising the noise
        // floor 15–20 dB and burying the 1200/2400 Hz subcarrier tones.
        DemodMode.ACARS -> true
        // Digital voice modes use 12.5 kHz or 6.25 kHz channels (4FSK/C4FM/GMSK).
        // Narrow-mode pre-decimation is essential — without it the full device
        // bandwidth (~250 kHz+) passes to the discriminator, swamping the symbol eye.
        DemodMode.DMR, DemodMode.P25, DemodMode.NXDN,
        DemodMode.DSTAR, DemodMode.YSF, DemodMode.M17,
        DemodMode.DPMR,
        DemodMode.DIG -> true
        else -> false
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    fun start() {
        audioEngine.start()
        // Re-arm the peak-hold warm-up so the initial-acquisition transient
        // (filter/decimator settling, AGC convergence) doesn't get latched
        // in as a permanent false peak line on the spectrogram.
        fftEngine.resetPeakHold()
        DebugBus.setStatus(DebugBus.STAGE_AUDIO_TRACK, DebugBus.StageStatus.OK, "AudioTrack started @ ${audioSinkRate} Hz")
        startAudioWriter()
        startDigVoiceAudio()
        startProcessingPipeline()
        rebuildVocoderDecoder()
        Log.i(TAG, "DSP engine started")
    }

    /** Launch (or re-launch) the dedicated audio writer coroutine. */
    private fun startAudioWriter() {
        audioWriterJob?.cancel()
        // DISPATCHERS.IO — not Dispatchers.Default.
        //
        // AudioEngine.write() calls AudioTrack.write(WRITE_BLOCKING) inside a
        // Java synchronized() block.  synchronized() is not a coroutine suspend
        // point: the thread is completely parked while AudioTrack drains room in
        // its ring buffer.  On a budget 2-core device Dispatchers.Default has only
        // 2 threads; with one permanently blocked in the audio write loop there is
        // only ONE thread left for processIqBlock() — and any competing scheduler
        // pressure (GC, binder, UI) causes the 34 ms (→ now 8.5 ms) DSP budget
        // to slip, triggering the SharedFlow DROP_OLDEST and producing audio gaps.
        //
        // Dispatchers.IO has an elastic thread pool (default 64 threads) specifically
        // designed for blocking I/O calls.  AudioTrack.write(WRITE_BLOCKING) is exactly
        // that: a blocking I/O call into the Android audio HAL.  Parking an IO thread
        // for a few ms costs nothing — it does not reduce the CPU-bound Default pool
        // that processIqBlock() depends on.
        //
        // rtl_tcp never suffers this problem because its 4.3 ms audio blocks return
        // from AudioTrack.write() almost immediately (ring-buffer copy), so the
        // Default thread is only parked for <1 ms per block rather than up to 8.5 ms.
        audioWriterJob = scope.launch(Dispatchers.IO) {
            for (samples in audioWriteChannel) {
                audioEngine.write(samples)
            }
        }
    }

    /**
     * Subscribe to [DigitalVoiceDecoder.frames] and route decoded PCM audio
     * to the AudioTrack.
     *
     * DSD-Neo / AMBE plugin returns 8 kHz 16-bit PCM. We upsample to
     * [audioSinkRate] (48 kHz) with a factor-of-6 linear interpolation and
     * convert to FloatArray before pushing to [audioWriteChannel]. Using
     * `collect` (not `collectLatest`) ensures every decoded frame is played —
     * there is no reason to drop voice frames under normal load.
     *
     * This is the ONLY writer to [audioWriteChannel] while a digital voice
     * mode is active — processIqBlock() intentionally skips its normal
     * per-IQ-block write in that case (see the "Write to AudioTrack" section
     * there) so its much-higher-frequency writes can't evict real decoded
     * audio out of the small DROP_OLDEST channel before it gets played.
     *
     * If the native library is absent (nativeAvailable = false) or no vocoder
     * (DSD-Neo software path or AMBE plugin) successfully decodes a frame,
     * pcmAudio will be empty and this coroutine simply emits nothing for that
     * frame — there is no separate silence-fill path for digital voice modes.
     */
    private fun startDigVoiceAudio() {
        digVoiceAudioJob?.cancel()
        digVoiceAudioJob = scope.launch(Dispatchers.Default) {
            digitalVoiceDecoder.frames.collect { frame ->
                val pcm = frame.pcmAudio
                if (pcm.isEmpty()) return@collect

                // Upsample 8 kHz → audioSinkRate using linear interpolation.
                //
                // mbelib (built-in): setUpsampling() is intentionally NOT called in
                // createDecoder(), so mbelib outputs raw 8 kHz frames (160 samples).
                // ratio is therefore audioSinkRate/8000 (= 6 for the default 48 kHz sink).
                val ratio    = audioSinkRate / 8000
                val outLen   = pcm.size * ratio
                val floatBuf = FloatArray(outLen)
                val scale    = 1.0f / 32768.0f

                for (i in pcm.indices) {
                    val s0 = pcm[i].toFloat() * scale
                    val s1 = if (i + 1 < pcm.size) pcm[i + 1].toFloat() * scale else s0
                    for (r in 0 until ratio) {
                        // FIX: use floating-point division for the interpolation weight.
                        // The previous code used integer `r / ratio` which always
                        // evaluated to 0 for r < ratio, making every interpolated point
                        // equal to s0 (nearest-neighbour, not linear interpolation).
                        floatBuf[i * ratio + r] = s0 + (s1 - s0) * r.toFloat() / ratio
                    }
                }

                // Apply volume
                val vol = volume
                if (vol != 1.0f) {
                    for (i in floatBuf.indices) {
                        floatBuf[i] = (floatBuf[i] * vol).coerceIn(-1f, 1f)
                    }
                }

                audioWriteChannel.trySend(floatBuf)
            }
        }
    }

    fun stop() {
        processingJob?.cancel()
        audioWriterJob?.cancel()
        digVoiceAudioJob?.cancel()
        audioEngine.stop()
        iqRecorder.stop()
        externalDecoderStream.shutdown()
        scope.cancel()
        DebugBus.setStatus(DebugBus.STAGE_DSP_PROCESS, DebugBus.StageStatus.IDLE, "Engine stopped")
        DebugBus.setStatus(DebugBus.STAGE_FFT_ENGINE, DebugBus.StageStatus.IDLE, "Engine stopped")
        DebugBus.setStatus(DebugBus.STAGE_DEMODULATOR, DebugBus.StageStatus.IDLE, "Engine stopped")
        DebugBus.setStatus(DebugBus.STAGE_AUDIO_RESAMPLE, DebugBus.StageStatus.IDLE, "Engine stopped")
        DebugBus.setStatus(DebugBus.STAGE_AUDIO_TRACK, DebugBus.StageStatus.IDLE, "Engine stopped")
        Log.i(TAG, "DSP engine stopped")
    }

    /**
     * Rebuild the native dsdcc decoder handle.  Safe to call while the engine
     * is running because [DigitalVoiceDecoder.rebuildHandle] holds the native
     * mutex around the destroy/create pair.
     */
    fun rebuildVocoderDecoder() {
        digitalVoiceDecoder.rebuildHandle()
        Log.i(TAG, "rebuildVocoderDecoder: handle recreated, vocoder=${DsdccNative.vocoderModeLabel}")
    }

    fun setDemodMode(mode: DemodMode) {
        val modeChanged = (mode != demodMode)
        demodMode = mode
        demodulator = DemodulatorFactory.create(mode)
        // Switching protocols changes the IF bandwidth, decimation chain, and
        // tuned frequency — any peaks accumulated under the previous mode are
        // no longer meaningful, so clear the peak-hold trace and re-arm the
        // warm-up window to ignore the resulting retune/reconfigure transient.
        if (modeChanged) {
            fftEngine.resetPeakHold()
            DebugBus.clearChain()
            // Notify the digital voice decoder of the new mode so it activates
            // the correct signal path (GMSK for D-STAR, 4FSK for everything else)
            // and resets internal state.  Called for every mode change, not just
            // when entering a digital voice mode, so the decoder always starts clean.
            digitalVoiceDecoder.setMode(mode)
        }
        // Reset IF bandwidth to the mode's default when switching to a
        // DIFFERENT mode, so the decimation chain always starts at a sensible
        // bandwidth for the new mode.
        //
        // When [mode] equals the mode that was already active (e.g.
        // applyAllSettings() re-invoking setDemodMode() on reconnect without
        // an actual mode switch), the user's Protocol Filter width
        // (ifBandwidthHz, set via setIfBandwidth()) must NOT be clobbered —
        // doing so silently desyncs the RF-tab "Width" slider from the
        // engine's actual IF bandwidth, making the control appear broken.
        //
        // Modes whose defaultIfRate() equals the full narrow-IF ceiling (48 kHz)
        // use the -1 sentinel so resolveNarrowIfRate() always picks the correct
        // exact-integer-divisor ceiling for the current device rate (e.g. 50 kHz
        // at 250 kS/s instead of forcing a fractional 48 kHz).
        if (mode.isNarrowMode() && modeChanged) {
            val modeDefault = mode.defaultIfRate()
            // Use -1 sentinel when the mode's default IF ≥ the current dynamic ceiling,
            // so resolveNarrowIfRate() picks the correct sink-rate-relative ceiling on
            // the next IQ block (e.g. 32 kHz at 250 kS/s when sinkRate=32 kHz, instead
            // of the old hardcoded 48 kHz target).
            val dynamicCeiling = narrowIfRateFor(device.getSampleRate(), audioSinkRate)
            // USB/LSB: always use the auto sentinel (-1) so the UI slider and spectrum
            // highlight reflect the true audio passband (defaultBwHz = 3 kHz) rather
            // than the internal IF decimation rate (defaultIfRate = 12 kHz).
            // resolveNarrowIfRate() will then return the full narrow-IF ceiling (typically
            // 48 kHz), and SsbDemodulator's own 3 kHz LPF provides the final selectivity.
            // This prevents the spectrum highlight from showing 12 kHz (the DSP rate) when
            // the demodulator only ever passes 3 kHz of audio.
            // FIX 24: APRS must ALWAYS resolve via the auto (-1) sentinel, never the
            // literal modeDefault (48 000).
            //
            // modeDefault for APRS is 48 000 (DemodMode.defaultIfRate()'s "maximum
            // narrow rate" bucket), chosen specifically so this branch falls through
            // to auto. But "modeDefault >= dynamicCeiling" computes dynamicCeiling
            // from narrowIfRateFor(deviceRate, audioSinkRate) — the GENERIC sink-rate
            // floor — not the APRS-specific DEFAULT_AUDIO_RATE floor that
            // resolveNarrowIfRate() actually uses for APRS. At the most common RTL-SDR
            // device rates (2.048 / 1.024 / 2.56 MS/s -> 51 200 Hz ceiling; 3.2 MS/s ->
            // 50 000 Hz) that true ceiling EXCEEDS 48 000, so "48000 >= ceiling" is
            // FALSE and this branch pins ifBandwidthHz to a literal 48000.
            //
            // 48000 is then NOT an exact integer divisor of those device rates (e.g.
            // 2 048 000 / 48 000 = 42.67), so resolveNarrowIfRate()'s
            // ifBandwidthHz.coerceIn(MIN, ceiling) passes 48000 straight through to
            // ComplexDecimator(2_048_000, 48_000) — a fractional 3/128 rational
            // resampler instead of the pure-integer decimation (-> 51 200 Hz) that
            // FIX 22 was specifically designed to guarantee for the AFSK bit-clock.
            // The narrow IF path silently stopped being the clean decimate-only chain
            // FIX 1-21's AFSK timing assumes, on the exact sample rates most dongles
            // ship with by default.
            //
            // Explicitly routing APRS into the auto branch removes the broken
            // modeDefault/dynamicCeiling comparison entirely for this mode: auto
            // always defers to resolveNarrowIfRate()'s APRS-aware floor, which is
            // already correct.
            ifBandwidthHz = if (mode == DemodMode.USB || mode == DemodMode.LSB ||
                                 mode == DemodMode.APRS || modeDefault >= dynamicCeiling) -1 else modeDefault
            narrowDecimator = null
            narrowDecimatorInputRate  = 0
            narrowDecimatorOutputRate = 0
        }
        // Auto-configure direct sampling for HF modes
        if (mode.requiresDirectSampling) {
            device.setDirectSampling(RtlSdrDevice.DIRECT_SAMPLING_Q)  // = 2
        } else if (device.getDirectSampling() != RtlSdrDevice.DIRECT_SAMPLING_OFF) {  // != 0
            device.setDirectSampling(RtlSdrDevice.DIRECT_SAMPLING_OFF) // = 0
        }
        // Reapply the carrier-offset hardware frequency whenever the mode changes
        // into or out of USB/LSB.  setCarrierFrequency() applies ±SSB_BFO_OFFSET_HZ
        // for USB/LSB and passes dialFreqHz straight through for all other modes.
        // This corrects the 1500 Hz tuning error that occurs because the Weaver BFO
        // mixes at 1500 Hz: the hardware must be pre-offset so the carrier lands at
        // 0 Hz after demodulation, matching what the dial shows.  Only reapply when
        // modeChanged to avoid an unnecessary retune (and its transient) on a simple
        // applyAllSettings() reconnect where the mode has not actually changed.
        if (modeChanged && dialFreqHz > 0L) {
            setCarrierFrequency(dialFreqHz)
        }
        // Free WFM decimator when leaving WFM modes — saves memory and forces
        // re-creation (with up-to-date device rate) if we return to WFM later.
        if (mode != DemodMode.WFM && mode != DemodMode.WFM_STEREO) {
            wfmDecimator = null
            wfmDecimatorInputRate  = 0
            wfmDecimatorOutputRate = 0
        }
        // Free narrow decimator when leaving narrow modes — same rationale.
        if (!mode.isNarrowMode()) {
            narrowDecimator = null
            narrowDecimatorInputRate = 0
        }
        updateStats()
        DebugBus.setStatus(DebugBus.STAGE_DEMODULATOR, DebugBus.StageStatus.OK,
            "Mode: ${mode.displayName}  directSamp: ${mode.requiresDirectSampling}")
        Log.i(TAG, "Demod mode: $mode")
    }

    fun setSquelchLevel(db: Float) {
        squelchLevel = db
        updateStats()
    }

    fun setVolume(vol: Float) {
        volume = vol.coerceIn(0f, 2f)
        // AudioTrack.setVolume() is silently clamped to getMaxVolume() (1.0) on all
        // Android devices.  Clamp here so the intent is explicit: hardware level handles
        // attenuation (0–1.0) and processIqBlock applies a software sample-gain for
        // values above 1.0, giving the full 0–2× range to the user.
        audioEngine.setVolume(vol.coerceIn(0f, 1f))
        updateStats()
    }

    /**
     * Switch the audio-output (sink) sample rate presented to Android AudioTrack.
     *
     * This controls ONLY what reaches the speaker/headphone — the internal DSP
     * pipeline (decimation, demodulation, protocol decoders) always operates at
     * [DEFAULT_AUDIO_RATE] = 48 000 Hz.  A PolyphaseResampler bridges the two
     * rates only when they differ, eliminating the mickey-mouse / slow-motion
     * pitch distortion that can occur when certain IQ sample rates produce a
     * demod output whose ratio to 48 kHz is non-trivial for the AudioTrack.
     *
     * Call this while the engine is running: it stops the current AudioTrack,
     * replaces [audioEngine] with a new one at [rate], and restarts playback
     * transparently.
     *
     * @param rate One of [AUDIO_SINK_RATES] (44 100, 32 000, 24 000, 22 050,
     *             16 000, 48 000 Hz).
     */
    fun setAudioSinkRate(rate: Int) {
        if (rate == audioSinkRate) return
        Log.i(TAG, "Audio sink rate: $audioSinkRate → $rate Hz")
        audioSinkRate = rate
        // Invalidate the sink resampler so it is rebuilt for the new rate.
        audioResampler    = null
        resamplerInRate   = 0
        resamplerOutRate  = 0
        // Invalidate the narrow decimator — narrowIfRateFor(deviceRate, sinkRate) may
        // return a different IF rate for the new sink rate, so the ComplexDecimator
        // must be rebuilt on the next IQ block.  Example: at 250 kS/s, switching from
        // sinkRate=48 kHz (IF=50 kHz) to sinkRate=32 kHz (IF=50 kHz→same here, but
        // at 1024 kS/s: 48 kHz→sinkRate=32 kHz means IF changes 51.2 k→32 k directly).
        narrowDecimator       = null
        narrowDecimatorInputRate  = 0
        narrowDecimatorOutputRate = 0
        // Replace the AudioTrack at the new output rate.
        val oldEngine = audioEngine
        val newEngine = AudioEngine(rate)
        audioEngine = newEngine   // swap before start so write() goes to new engine
        oldEngine.stop()
        newEngine.start()
        startAudioWriter()   // re-launch writer bound to the new AudioEngine instance
        startDigVoiceAudio() // re-launch with updated audioSinkRate for upsampling ratio
        DebugBus.setStatus(DebugBus.STAGE_AUDIO_TRACK, DebugBus.StageStatus.OK,
            "AudioTrack restarted @ $rate Hz")
        updateStats()
    }

    fun getAudioSinkRate(): Int = audioSinkRate

    fun startIqRecording(filePath: String) {
        iqRecorder.start(filePath, device.getSampleRate())
        updateStats()
    }

    fun stopIqRecording() {
        iqRecorder.stop()
        updateStats()
    }

    fun startAudioRecording(filePath: String) {
        audioEngine.startRecording(filePath)
        updateStats()
    }

    fun stopAudioRecording() {
        audioEngine.stopRecording()
        updateStats()
    }

    fun getFftSize(): Int = fftEngine.fftSize
    fun setFftSize(size: Int) { fftEngine.setFftSize(size) }
    fun setFftWindow(w: FftEngine.WindowType) { fftEngine.setWindowType(w) }
    fun setFftSmoothing(alpha: Float) { fftEngine.smoothingAlpha = alpha }

    /**
     * Set the FFT decimation factor.  Valid values: 1 (off), 2, 4, 8, 16, 32, 64.
     * FftEngine.decimationFactor is updated immediately; the next IQ block
     * processed by processUint8/processFloat will use the new factor.
     * The caller must also update SpectrumView/WaterfallView with the effective
     * sample rate (= deviceSampleRate / decimationFactor).
     */
    fun setFftDecimation(factor: Int) {
        fftEngine.decimationFactor = factor
        Log.i(TAG, "FFT decimation: ×$factor  effective rate: ${device.getSampleRate() / factor.coerceAtLeast(1)} Hz")
    }

    fun getFftDecimation(): Int = fftEngine.decimationFactor

    /** Toggle the IQ-level DC-offset removal filter.  Disable if the leaky
     *  integrator is audibly interfering with signals near the centre frequency. */
    fun setDcBlockEnabled(enabled: Boolean) {
        dcBlockEnabled = enabled
        if (!enabled) dcState.fill(0f)   // reset leaky-integrator so re-enable starts clean
        Log.i(TAG, "DC block ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Set the IF filter bandwidth for narrow modes (AM, NFM, USB, LSB, CW, etc.).
     * This controls the intermediate rate of the ComplexDecimator that precedes
     * demodulation — effectively the channel bandwidth seen by the demodulator.
     *
     * Clamped to [MIN_IF_BANDWIDTH_HZ, narrowIfRateFor(deviceRate, audioSinkRate)].
     * The upper bound is the smallest exact-integer-divisor of the device rate that
     * is ≥ audioSinkRate — i.e. the same dynamic ceiling used by the auto path.
     * The narrowDecimator is torn down and rebuilt on the next IQ block.
     *
     * Pass 0 (or any negative value) to reset to auto (dynamic per device+sink rate).
     *
     * @param hz  Desired IF bandwidth in Hz (e.g. 2000 = very narrow CW,
     *            15000 = standard NFM, 25000 = AM/ACARS), or 0 for auto.
     */
    fun setIfBandwidth(hz: Int) {
        // 0 (or negative) means "auto — use narrowIfRate(deviceRate)".
        // Reset to sentinel -1 so resolveNarrowIfRate() picks the correct rate
        // for the current device rate on the next IQ block.
        val newHz = if (hz <= 0) -1
                    else hz.coerceIn(MIN_IF_BANDWIDTH_HZ, Int.MAX_VALUE)
        if (newHz == ifBandwidthHz) return
        ifBandwidthHz = newHz
        // Invalidate so getNarrowDecimator() rebuilds at next IQ block.
        narrowDecimator = null
        narrowDecimatorInputRate  = 0
        narrowDecimatorOutputRate = 0
        Log.i(TAG, "IF bandwidth set to ${if (newHz < 0) "auto" else "$newHz Hz"}")
    }

    /**
     * Returns the current IF bandwidth in Hz, or -1 when in auto mode
     * (rate is determined dynamically by [narrowIfRate] for the device rate).
     */
    fun getIfBandwidth(): Int = ifBandwidthHz

    /** Enable or disable the Noise Blanker (impulsive-noise suppressor). */
    fun setNoiseBlankerEnabled(enabled: Boolean) {
        noiseBlankerEnabled = enabled
        Log.i(TAG, "Noise Blanker ${if (enabled) "enabled" else "disabled"}")
    }

    /** Enable or disable the Noise Reducer (spectral-subtraction broadband NR).
     *  Enabling resets the noise floor calibration — the first ~10 audio frames
     *  are used to estimate the noise floor before reduction begins. */
    fun setNoiseReducerEnabled(enabled: Boolean) {
        noiseReducerEnabled = enabled
        if (enabled) noiseReducer.reset()  // fresh calibration each enable
        Log.i(TAG, "Noise Reducer ${if (enabled) "enabled" else "disabled"}")
    }

    fun isNoiseBlankerEnabled(): Boolean = noiseBlankerEnabled
    fun isNoiseReducerEnabled(): Boolean = noiseReducerEnabled

    /** Set a software frequency offset applied to the IQ stream before demodulation.
     *  Positive values shift the passband upward (signal appears to move left in spectrum);
     *  negative values shift it downward.  Pass 0 to disable. */
    fun setFreqOffset(hz: Int) {
        freqOffsetHz = hz
        freqShiftPhase = 0.0   // reset NCO phase to avoid a click on setting change
        Log.i(TAG, "Frequency offset: $hz Hz")
    }

    /**
     * Tune the hardware to the user's [dialHz] frequency, transparently applying the
     * SSB BFO carrier offset for USB and LSB modes.
     *
     * The BFO mixes with exp(+j*2pi*1500*t) — an UPWARD shift of +1500 Hz.
     * To place the carrier at -1500 Hz in the IF (so after the BFO it lands at 0 Hz),
     * the hardware must tune +1500 Hz ABOVE the dial for both USB and LSB:
     *
     *   USB, LSB -> hardware = dialHz + SSB_BFO_OFFSET_HZ
     *   All other modes -> hardware = dialHz (no offset)
     *
     * This is the single point that applies the offset; all callers route through here.
     *
     * @param dialHz The frequency the user intends to receive (as shown on the dial).
     */
    fun setCarrierFrequency(dialHz: Long) {
        dialFreqHz = dialHz
        // The BFO mixes with exp(+j*2pi*1500*t) — a +1500 Hz upward spectral shift.
        // To have a USB carrier (at dialHz in RF) land at 0 Hz after demodulation, the
        // hardware must present that carrier at −1500 Hz in the baseband IF:
        //
        //   hardware = dialHz + 1500 Hz  →  carrier in IF at (dialHz − (dialHz+1500)) = −1500 Hz
        //   after BFO +1500 Hz shift:  −1500 + 1500 = 0 Hz  ✓
        //
        // USB voice (300–3000 Hz above carrier) then sits at −1200 to +1500 Hz in the IF,
        // and after the BFO shift occupies +300 to +3000 Hz — all within the 3 kHz LPF.
        // I+Q sideband select then yields correct audio.
        //
        // For LSB, the voice is 300–3000 Hz BELOW the carrier, so in the IF it sits at
        // −1800 to −4500 Hz.  After BFO +1500: −300 to −3000 Hz — within the LPF.
        // I−Q sideband select inverts the negative-frequency components into audio. ✓
        //
        // Therefore both USB and LSB use the same +SSB_BFO_OFFSET_HZ hardware shift.
        // (The previous −1500 for USB was wrong: it placed voice at +1800–+4500 Hz
        //  in the IF, which the +1500 BFO shift pushed to +3300–+6000 Hz — entirely
        //  above the 3 kHz LPF, producing silence.)
        val hwHz = when (demodMode) {
            DemodMode.USB,
            DemodMode.LSB -> dialHz + SSB_BFO_OFFSET_HZ
            else          -> dialHz
        }
        device.setCenterFrequency(hwHz)
        Log.i(TAG, "setCarrierFrequency: dial=$dialHz mode=$demodMode hw=$hwHz")
    }

    /**
     * Activate the dual-APRS simultaneous-reception path.
     *
     * The caller must have already tuned the device to [centreHz] (typically
     * [APRS_MIDPOINT_HZ] = 144.595 MHz) so both APRS channels fall inside the
     * passband.  The DSP loop will start emitting to [dualAprsFlow] on the next
     * IQ block; the caller subscribes to that flow and feeds two [AprsDecoder]
     * instances independently.
     *
     * Has no effect if [centreHz] is 0 or if the device sample rate is less than
     * [DUAL_APRS_MIN_SAMPLE_RATE].
     */
    fun enableDualAprs(centreHz: Long) {
        dualAprsCentreHz = centreHz
        Log.i(TAG, "Dual-APRS enabled, centre: ${centreHz / 1e6} MHz")
    }

    /** Deactivate the dual-APRS path and release its DSP objects. */
    fun disableDualAprs() {
        dualAprsCentreHz = 0L
        dualAprsDecimNA = null; dualAprsDecimEU = null
        dualAprsResampNA = null; dualAprsResampEU = null
        dualAprsDeviceRate = 0
        Log.i(TAG, "Dual-APRS disabled")
    }

    // ─── Processing Pipeline ─────────────────────────────────────────────────

    private fun startProcessingPipeline() {
        DebugBus.setStatus(DebugBus.STAGE_FFT_ENGINE, DebugBus.StageStatus.OK,
            "FFT size: ${fftEngine.fftSize}  waiting for IQ stream…")
        DebugBus.setStatus(DebugBus.STAGE_DSP_PROCESS, DebugBus.StageStatus.OK, "Pipeline starting…")
        DebugBus.setStatus(DebugBus.STAGE_AUDIO_RESAMPLE, DebugBus.StageStatus.OK,
            "Resampler ready  → ${DEFAULT_AUDIO_RATE} Hz")

        // Launch the IQ collector coroutine.  We must ensure collect() is registered
        // on the SharedFlow BEFORE startStreaming() begins emitting, otherwise with
        // replay=0 all IQ data is silently dropped.
        //
        // The old yield() trick is unreliable across dispatcher boundaries: this
        // scope uses Dispatchers.Default while RtlSdrDevice.scope uses Dispatchers.IO.
        // A single yield() only suspends within the same thread pool; IO can win the
        // race and call emit() before Default reaches collect().
        //
        // Fix: launch the collector, then in a second coroutine confirm subscriptionCount
        // >= 1 before calling startStreaming().  RtlSdrDevice.startStreaming() also has
        // its own subscriber-wait; together these two guards guarantee no IQ block is lost.
        processingJob = scope.launch {
            device.iqFlow.collect { rawIq ->
                processIqBlock(rawIq)
            }
        }

        // In a separate coroutine: wait until the collector above has registered its
        // subscription on iqFlow, then start USB streaming.  Using scope.launch keeps
        // this non-blocking (start() is not a suspend function).
        scope.launch {
            val deadline = System.currentTimeMillis() + 500L
            while (device.iqSubscriberCount == 0 && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(2)
            }
            if (device.iqSubscriberCount == 0) {
                Log.w(TAG, "startProcessingPipeline: IQ subscriber not ready after 500 ms — starting anyway")
            }
            // Start device streaming now that the collector is confirmed subscribed.
            //
            // USB BUFFER SIZE & CHUNKING RATIONALE — 32,768 bytes split into 2 × 16,384-byte chunks
            // ──────────────────────────────────────────────────────────────────────────────────────
            // The RTL-SDR Android bulkTransfer() is a single-URB synchronous model.
            // At 2 MS/s, a 32,768-byte (8,192 IQ samples) USB transfer takes ~8 ms to
            // complete.  The transfer is then split into 2 × DSP_CHUNK_SIZE (16,384-byte)
            // chunks and emitted to the SharedFlow one at a time.
            //
            // Without a yield() between chunk emits, both chunks arrive at the SharedFlow
            // in the same ~0 ms window after the 8 ms USB stall.  The DSP coroutine on
            // Dispatchers.Default processes them as a burst, producing two audio frames
            // back-to-back with no temporal separation.  AudioTrack receives a burst of
            // 8 ms of audio then silence for ~8 ms — a rhythmic gap that manifests as
            // audible WFM stutter even though the SharedFlow never drops a block.
            //
            // rtl_tcp is immune because RtlTcpSource.READ_BUF_SIZE = 16,384 bytes and
            // InputStream.read() blocks naturally for ~4 ms per chunk, spacing each emit
            // 4 ms apart and giving Dispatchers.Default time to process each block before
            // the next arrives.
            //
            // Fix: RtlSdrDevice emits each 16,384-byte chunk individually and calls
            // yield() after every emit.  Dispatchers.IO and Dispatchers.Default share the
            // same backing JVM thread pool; yield() parks the IO streaming thread for one
            // scheduler quantum so Default can run processIqBlock() on the first chunk
            // before the second is emitted — matching TCP's natural timing at zero cost.
            //
            // USB buffer remains 32,768 bytes (64 × 512 B USB packets — efficient) and
            // the chunk pool (40 slots) is sized for the full SharedFlow depth (17) plus
            // 2 in-flight slots + headroom, so the producer can never overwrite a buffer
            // still held by the DSP consumer.
            device.startStreaming(USB_STREAMING_BUF)
        }
    }

    private fun processIqBlock(rawIq: ByteArray) {
        // ── Zero-content guard ────────────────────────────────────────────────
        // The async URB path in RtlSdrUsbHelper can produce all-zero buffers
        // when the direct ByteBuffer suffers a DMA cache-coherency failure (CPU
        // reads stale zeros instead of the DMA-written IQ data).  Processing
        // such a block would:
        //   • push -300 dBFS into the spectrum (log₁₀(1×10⁻¹⁵))
        //   • write silence to the AudioTrack despite squelch showing OPEN
        //   • flood the waterfall with all-black rows
        //
        // Detection: RTL-SDR outputs unsigned-8 IQ centred at ~127.5; a true
        // all-zero block is physically impossible from a live device.  Sample
        // 64 bytes spread 512 bytes apart across the buffer — cheap O(1) check
        // that catches the full-buffer-zero case without walking every byte.
        //
        // The helper auto-detects the same condition and switches to bulkTransfer()
        // after a few blocks, so this guard only fires transiently.
        val zeroCheckStep = 512
        val zeroCheckCount = minOf(64, rawIq.size / zeroCheckStep)
        if (zeroCheckCount > 0) {
            var allZero = true
            for (k in 0 until zeroCheckCount) {
                if (rawIq[k * zeroCheckStep] != 0.toByte()) { allZero = false; break }
            }
            if (allZero) {
                // Log sparingly (once per second at ~14 blocks/s = every ~14 blocks)
                val snap = DebugBus.snapshot()[DebugBus.STAGE_IQ_STREAM]
                if (snap.counter % 14L == 0L) {
                    Log.w(TAG, "processIqBlock: all-zero IQ data — USB async DMA failure; " +
                        "waiting for helper to switch to bulkTransfer()")
                    DebugBus.setError(DebugBus.STAGE_DSP_PROCESS,
                        "All-zero IQ — USB DMA coherency failure (helper switching to bulkTransfer)")
                }
                return
            }
        }

        // Forward to IQ recorder
        if (iqRecorder.isRecording) {
            iqRecorder.write(rawIq)
        }

        // ── FFT spectrum (rate-limited) ───────────────────────────────────────
        // The FFT peak bin is also used as the squelch signal level (see below).
        // We always run the FFT — even in frames that aren't emitted to the display —
        // so that squelchSignalDb is updated every IQ block for responsive gating.
        // The rate-limited path only controls when the result is published to the UI.
        // fftSnapshot is FftEngine's internal `smoothed` buffer (no copy) — see
        // FftEngine.processUint8 doc. Safe to read here (single DSP thread), but
        // must be .copyOf()'d before handing off to the (rate-limited) flow emit
        // below, since it will be mutated in place on the next IQ block.
        val fftSnapshot = fftEngine.processUint8(rawIq)
        // Peak FFT bin in dBFS — computed inside FftEngine during the smoothing
        // pass (fftEngine.lastPeakDb), avoiding a second O(n) maxOrNull() scan
        // over the returned array on every IQ block.
        // Used as signalDb for the squelch gate so the threshold the user drags on
        // the spectrum display is in the same dBFS reference as the squelch decision.
        val squelchSignalDb = fftEngine.lastPeakDb

        // ── Narrowband (channel-specific) signal level ──────────────────────
        // fftEngine.lastPeakDb / squelchSignalDb above is the PEAK across the
        // *entire* captured spectrum (e.g. a 2 MHz window) — correct for the
        // live spectrum-display squelch (the user drags the SQL line against
        // what they see across the whole waterfall). It is NOT correct for a
        // channelized scanner: when FrequencyScanner retunes the device to a
        // candidate channel, the tuned frequency sits at the FFT's centre
        // (DC) bin, but any stronger signal *elsewhere* in the same capture
        // window (an adjacent channel, a broadcast station, a birdie) would
        // dominate squelchSignalDb regardless of whether the actual tuned
        // channel has any traffic on it — so the scanner's squelch threshold
        // had essentially no channel-specific effect (its "signal" reading
        // barely varied between a dead channel and a busy one, since it
        // wasn't measuring the tuned channel at all).
        //
        // Fix: average power over a small window of bins straddling the
        // centre (DC) bin — sized to roughly one channel's worth of
        // bandwidth (12.5 kHz) — and expose it separately as
        // narrowbandCenterDb for channel-specific consumers like the scanner.
        val fftBinCount = fftSnapshot.size
        val hzPerBin = if (fftBinCount > 0)
            device.getSampleRate().toDouble() / fftEngine.decimationFactor / fftBinCount
        else 1.0
        val channelHalfBins = if (hzPerBin > 0)
            (NARROWBAND_CHANNEL_HZ / hzPerBin / 2.0).toInt().coerceAtLeast(1)
        else 1
        val centerIdx = fftBinCount / 2
        val loBin = (centerIdx - channelHalfBins).coerceAtLeast(0)
        val hiBin = (centerIdx + channelHalfBins).coerceAtMost((fftBinCount - 1).coerceAtLeast(0))
        var centerPowerSum = 0.0
        var centerBinTally = 0
        for (i in loBin..hiBin) {
            centerPowerSum += Math.pow(10.0, fftSnapshot[i] / 10.0)
            centerBinTally++
        }
        val narrowbandCenterDb = if (centerBinTally > 0)
            (10.0 * Math.log10(centerPowerSum / centerBinTally)).toFloat()
        else squelchSignalDb
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastSpectrumMs >= SPECTRUM_MIN_INTERVAL_MS) {
            lastSpectrumMs = nowMs
            _spectrumFlow.tryEmit(fftSnapshot.copyOf())
            DebugBus.tick(DebugBus.STAGE_FFT_ENGINE)
            val snap = DebugBus.snapshot()[DebugBus.STAGE_FFT_ENGINE]
            if (snap.counter % 15L == 0L) {
                val signalMin = fftSnapshot.minOrNull() ?: -200f
                val signalMax = squelchSignalDb
                DebugBus.setDetail(DebugBus.STAGE_FFT_ENGINE,
                    "FFT size: ${fftEngine.fftSize}  " +
                    "peak: ${"%.1f".format(signalMax)} dBFS  " +
                    "floor: ${"%.1f".format(signalMin)} dBFS")
                DebugBus.setStatus(DebugBus.STAGE_FFT_ENGINE, DebugBus.StageStatus.OK,
                    "FFT size: ${fftEngine.fftSize}  " +
                    "peak: ${"%.1f".format(signalMax)} dBFS  " +
                    "floor: ${"%.1f".format(signalMin)} dBFS")
            }
        }

        // ── Convert uint8 IQ → normalised float IQ ───────────────────────────
        // Reuse a single cached buffer (floatIqBuf) rather than allocating a
        // fresh FloatArray(rawIq.size) = 512 KB on every block.  The USB block
        // size is constant after the first transfer so the buffer is allocated
        // exactly once and reused for every subsequent block.
        val n = rawIq.size / 2
        if (floatIqBuf.size != rawIq.size) floatIqBuf = FloatArray(rawIq.size)
        val floatIq = floatIqBuf
        if (NativeDsp.isNativeAvailable()) {
            NativeDsp.convertUint8ToFloatInto(rawIq, floatIq, rawIq.size)
        } else {
            for (i in rawIq.indices) {
                floatIq[i] = ((rawIq[i].toInt() and 0xFF) - 127.5f) / 128.0f
            }
        }

        // ── DC offset removal ─────────────────────────────────────────────────
        // RTL-SDR hardware leaves a large DC component at the centre bin due
        // to LO leakage in the direct-conversion mixer.  Remove it with a
        // leaky-integrator IIR filter before any downstream processing so:
        //   • the squelch power measurement is not inflated by a constant bias,
        //   • weak signals near the centre frequency are not buried under DC,
        //   • ADS-B magnitude samples have a clean zero baseline.
        // The FFT spectrum gets its own block-mean removal inside FftEngine so
        // the two paths are decoupled (FFT still reads the raw uint8 buffer).
        // Conditionally applied — user can disable via Settings if it interferes.
        if (dcBlockEnabled) {
            NativeDsp.removeDc(floatIq, dcState, DC_ALPHA)
        }

        // ── Software frequency offset (NCO fine-tune) ─────────────────────────
        // Rotates every IQ sample by a fixed angular increment, effectively
        // translating the baseband spectrum up or down by freqOffsetHz.  Done
        // after DC removal so the DC spike is not shifted into the passband.
        // The phase accumulator persists across blocks for continuous rotation.
        val offsetHz = freqOffsetHz
        if (offsetHz != 0) {
            // Phase-rotation recurrence: multiply the running phasor by a fixed
            // unit step each sample instead of calling cos/sin per sample.
            // Cost: 4 muls + 2 adds per sample vs 2 transcendentals — roughly
            // 20× faster on ARM, eliminating up to 2M cos/sin calls/sec at 2 MS/s.
            // Phase re-normalisation every block keeps magnitude drift < 1e-6.
            val phaseStep = 2.0 * Math.PI * offsetHz / device.getSampleRate()
            val stepRe = Math.cos(phaseStep)   // computed once per block
            val stepIm = Math.sin(phaseStep)
            // Restore from saved phase accumulator
            var pRe = Math.cos(freqShiftPhase)
            var pIm = Math.sin(freqShiftPhase)
            val numSamples = floatIq.size / 2
            for (i in 0 until numSamples) {
                val cosP = pRe.toFloat()
                val sinP = pIm.toFloat()
                val re = floatIq[2 * i]
                val im = floatIq[2 * i + 1]
                floatIq[2 * i]     = re * cosP - im * sinP
                floatIq[2 * i + 1] = re * sinP + im * cosP
                // Advance phasor: (pRe + j·pIm) × (stepRe + j·stepIm)
                val nextRe = pRe * stepRe - pIm * stepIm
                val nextIm = pRe * stepIm + pIm * stepRe
                pRe = nextRe; pIm = nextIm
            }
            // Re-normalise magnitude drift once per block (cheap compared to per-sample)
            val mag = Math.sqrt(pRe * pRe + pIm * pIm)
            if (mag > 0.0) { pRe /= mag; pIm /= mag }
            // Persist phase as angle for next block
            freqShiftPhase = Math.atan2(pIm, pRe)
        }

        // ── Emit IQ magnitude for ADS-B (and other raw-IQ consumers) ─────────
        if (_iqMagnitudeFlow.subscriptionCount.value > 0) {
            val mag = FloatArray(n)
            for (i in 0 until n) {
                val I = floatIq[2 * i]; val Q = floatIq[2 * i + 1]
                mag[i] = kotlin.math.sqrt(I * I + Q * Q)
            }
            _iqMagnitudeFlow.tryEmit(mag)
        }

        // ── Dual-APRS simultaneous reception ─────────────────────────────────
        // When AprsActivity is in dual-watch mode it subscribes to _dualAprsFlow.
        // We derive two independent NFM-demodulated audio streams from the same
        // floatIq block by:
        //   1. NCO-mixing a copy of the IQ to shift each APRS channel to 0 Hz.
        //   2. Polyphase-decimating each mixed copy to narrowIfRate(deviceRate).
        //   3. Running an FM discriminator on each decimated stream.
        //   4. Resampling each discriminator output to DEFAULT_AUDIO_RATE.
        //
        // The NCO offset is (targetFreq − centreFreq).  AprsActivity is responsible
        // for tuning the device to the APRS midpoint (144.595 MHz) so that both
        // channels are within the passband; it reads centreFreq from the ViewModel
        // and supplies it via enableDualAprs().  If the sample rate is too narrow
        // to cover both channels the flow is simply not emitted (no exception).
        if (_dualAprsFlow.subscriptionCount.value > 0 && dualAprsCentreHz != 0L) {
            val deviceRate = device.getSampleRate()
            val naOffset = (APRS_NA_HZ - dualAprsCentreHz).toDouble()
            val euOffset = (APRS_EU_HZ - dualAprsCentreHz).toDouble()
            val halfBw   = deviceRate / 2.0

            // Only process if both channels are inside the current passband.
            if (kotlin.math.abs(naOffset) < halfBw && kotlin.math.abs(euOffset) < halfBw) {
                ensureDualAprsChain(deviceRate)
                val aprsIfRate = narrowIfRate(deviceRate)
                val phNA = doubleArrayOf(dualAprsPhaseNA)
                val phEU = doubleArrayOf(dualAprsPhaseEU)

                val mixedNA = ncoMix(floatIq, deviceRate, naOffset, phNA)
                val mixedEU = ncoMix(floatIq, deviceRate, euOffset, phEU)
                dualAprsPhaseNA = phNA[0]; dualAprsPhaseEU = phEU[0]

                val decimNA = dualAprsDecimNA!!.process(mixedNA)
                val decimEU = dualAprsDecimEU!!.process(mixedEU)

                val stateNA = floatArrayOf(dualAprsPrevINa, dualAprsPrevQNa)
                val stateEU = floatArrayOf(dualAprsPrevIEu, dualAprsPrevQEu)

                val discNA = nfmDiscriminate(decimNA, stateNA, aprsIfRate)
                val discEU = nfmDiscriminate(decimEU, stateEU, aprsIfRate)
                dualAprsPrevINa = stateNA[0]; dualAprsPrevQNa = stateNA[1]
                dualAprsPrevIEu = stateEU[0]; dualAprsPrevQEu = stateEU[1]

                val audioNA = dualAprsResampNA!!.process(discNA)
                val audioEU = dualAprsResampEU!!.process(discEU)

                _dualAprsFlow.tryEmit(Pair(audioNA, audioEU))
            }
        }

        // ── Signal level → squelch gate ───────────────────────────────────────
        // Use the peak FFT bin (squelchSignalDb, computed above) as the signal level
        // for the squelch gate.  This is the correct reference because:
        //
        //   • The user positions the SQL line by looking at the spectrum display.
        //   • The spectrum displays per-bin amplitude in dBFS (FftEngine.computeFft:
        //     20·log10(binMag / windowSum)).
        //   • A wideband RMS estimator reads 20–25 dB higher than the spectrum noise
        //     floor for the same signal, because it integrates power across the entire
        //     2 MHz bandwidth while each FFT bin represents only ~8 kHz of that power.
        //   • Using RMS therefore made the squelch open ~20 dB above the visual line,
        //     so the SQL marker appeared "above the noise" even at its minimum setting.
        //
        // With squelchSignalDb = spectrum.max(), a SQL threshold of −60 dBFS opens
        // exactly when the highest visible spectral peak crosses −60 dBFS — which is
        // precisely what the user expects from dragging the SQL line on-screen.
        val signalDb = squelchSignalDb
        squelchOpen = signalDb > squelchLevel
        DebugBus.tick(DebugBus.STAGE_DSP_PROCESS)
        if (DebugBus.snapshot()[DebugBus.STAGE_DSP_PROCESS].counter % 10L == 0L) {
            DebugBus.setDetail(DebugBus.STAGE_DSP_PROCESS,
                "${"%.1f".format(signalDb)} dBFS  squelch: ${if (squelchOpen) "OPEN" else "closed"}  mode: ${demodMode.displayName}")
        }

        // ── WFM pre-decimation ────────────────────────────────────────────────
        // Integer-decimate to the adaptive WFM IF rate (wfmIfRate(deviceRate)).
        //
        // ── Narrow-mode pre-decimation ────────────────────────────────────────
        // Decimate to narrowIfRateFor(deviceRate, audioSinkRate) — the smallest
        // exact-integer-divisor of deviceRate that is ≥ audioSinkRate.
        //
        // This mirrors GnuRadio's rational_resampler approach:
        //   • When deviceRate is a multiple of audioSinkRate, the IF rate equals
        //     audioSinkRate and the final PolyphaseResampler is trivially 1:1.
        //   • When it is not (e.g. 250 kS/s → 32 kHz: IF=50 kHz, then 50→32 kHz),
        //     the resampler ratio is as small as possible for maximum quality.
        //   • No hardcoded 48 kHz intermediate — the IF rate scales with sinkRate.
        //   • 2560 kS/s (which has no exact divisor near 48 kHz) now decimes
        //     cleanly: e.g. to 32 kHz directly (÷80) for a 32 kHz sink.
        val isWfm = demodMode == DemodMode.WFM || demodMode == DemodMode.WFM_STEREO
        val isNarrow = demodMode.isNarrowMode()
        val isDigital = demodMode in DIGITAL_VOICE_MODES
        val deviceRate = device.getSampleRate()
        val (iqForDemod, demodRate) = when {
            isWfm   -> {
                val (decim, ifRate) = getWfmDecimator(deviceRate)
                Pair(decim.process(floatIq), ifRate)
            }
            isNarrow -> {
                val narrowRate = resolveNarrowIfRate(deviceRate)
                Pair(getNarrowDecimator(deviceRate).process(floatIq), narrowRate)
            }
            else    -> Pair(floatIq, deviceRate)
        }

        // ── Demodulate ────────────────────────────────────────────────────────
        // Protocol decoder modes (APRS, ACARS, FLEX, digital voice) MUST
        // Protocol decoder modes that bypass squelch — always demodulate so the
        // decoder sees real audio even on sub-threshold bursts:
        //   APRS / ACARS / FLEX: brief data bursts often arrive below squelch.
        //   Gating with silence means the packet is lost entirely.
        //   Speaker audio is still silenced below (re-applied to sinkAudio).
        //
        // Digital voice (DMR / D-STAR / YSF) is NOT bypassed.
        // dsdcc running on pure noise produces false sync and spurious vocoder
        // output (clicks, bursts) heard through the speaker when squelch is
        // closed.  Digital voice always rides a carrier that opens squelch, so
        // there are no sub-threshold frames to miss.
        val isProtocolDecoderMode = demodMode == DemodMode.APRS ||
            demodMode == DemodMode.ACARS ||
            demodMode == DemodMode.FLEX
        val squelchGated = !squelchOpen && squelchLevel > -150f
        val audio = when {
            !squelchGated || isProtocolDecoderMode -> {
                demodulator.demodulate(iqForDemod, demodRate)
            }
            else -> {
                // Squelched: emit correctly-sized silence so the AudioTrack buffer
                // doesn't starve (avoid glitches when squelch briefly opens).
                // Use rational Long arithmetic so the silence block exactly matches
                // what PolyphaseResampler produces for this block.
                // The old integer division  demodRate / DEFAULT_AUDIO_RATE  truncates
                // non-integer WFM IF ratios (e.g. 216 000/48 000 = 4 instead of 4.5),
                // making the silence block up to 27 % too large.  Those excess samples
                // accumulate in the AudioTrack buffer and cause progressive pitch drift
                // whenever the squelch cycles — heard as Mickey Mouse or slow motion.
                val outN = iqForDemod.size / 2
                val silenceSamples = (outN.toLong() * audioSinkRate / demodRate)
                    .toInt().coerceAtLeast(1)
                FloatArray(silenceSamples)
            }
        }

        // ── Demod output tick ────────────────────────────────────────────────
        if (audio.isNotEmpty()) {
            DebugBus.tick(DebugBus.STAGE_DEMODULATOR)
            if (DebugBus.snapshot()[DebugBus.STAGE_DEMODULATOR].counter % 15L == 0L) {
                DebugBus.setDetail(DebugBus.STAGE_DEMODULATOR,
                    "${demodMode.displayName}  ${audio.size} samples  " +
                    when {
                        isWfm            -> {
                            val g = gcd(deviceRate, demodRate)
                            "WFM IF: $demodRate Hz (ratio=${deviceRate/g}:${demodRate/g})"
                        }
                        demodMode.isNarrowMode() -> "narrow IF: $demodRate Hz (N=${deviceRate/demodRate})"
                        else             -> "rate: $deviceRate Hz"
                    })
            }
            // APRS frame diagnostics: push the full decode funnel on EVERY tick so
            // the Debug Panel reflects live state without ~1-second lag.
            // The funnel shows exactly where frames are dying:
            //   samples_in=0 → decoder not called; flags=0 → no bit-sync;
            //   size_rej >> flags → noise only; fcs_fail >> rcvd → bit errors;
            //   ax25_fail > 0 → AX.25 frame structure problems.
            if (demodMode == DemodMode.APRS) {
                DebugBus.setExtra(DebugBus.STAGE_DEMODULATOR, DebugBus.EXTRA_APRS_SAMPLES_IN, "${aprsDecoder.samplesIn}")
                DebugBus.setExtra(DebugBus.STAGE_DEMODULATOR, DebugBus.EXTRA_APRS_FLAG_SYNCS, "${aprsDecoder.flagSyncs}")
                DebugBus.setExtra(DebugBus.STAGE_DEMODULATOR, DebugBus.EXTRA_APRS_SIZE_REJ,   "${aprsDecoder.sizeRejected}")
                DebugBus.setExtra(DebugBus.STAGE_DEMODULATOR, DebugBus.EXTRA_APRS_RCVD,       "${aprsDecoder.framesReceived}")
                DebugBus.setExtra(DebugBus.STAGE_DEMODULATOR, DebugBus.EXTRA_APRS_FCS_FAIL,   "${aprsDecoder.fcsFailed}")
                DebugBus.setExtra(DebugBus.STAGE_DEMODULATOR, DebugBus.EXTRA_APRS_AX25_FAIL,  "${aprsDecoder.ax25Failed}")
                DebugBus.setExtra(DebugBus.STAGE_DEMODULATOR, DebugBus.EXTRA_APRS_DECODED,    "${aprsDecoder.framesDecoded}")
            } else {
                DebugBus.clearExtra(DebugBus.STAGE_DEMODULATOR, DebugBus.EXTRA_APRS_SAMPLES_IN)
                DebugBus.clearExtra(DebugBus.STAGE_DEMODULATOR, DebugBus.EXTRA_APRS_FLAG_SYNCS)
                DebugBus.clearExtra(DebugBus.STAGE_DEMODULATOR, DebugBus.EXTRA_APRS_SIZE_REJ)
                DebugBus.clearExtra(DebugBus.STAGE_DEMODULATOR, DebugBus.EXTRA_APRS_RCVD)
                DebugBus.clearExtra(DebugBus.STAGE_DEMODULATOR, DebugBus.EXTRA_APRS_FCS_FAIL)
                DebugBus.clearExtra(DebugBus.STAGE_DEMODULATOR, DebugBus.EXTRA_APRS_AX25_FAIL)
                DebugBus.clearExtra(DebugBus.STAGE_DEMODULATOR, DebugBus.EXTRA_APRS_DECODED)
            }
        }

        // ── WFM_STEREO: mix L,R down to mono ─────────────────────────────────
        // WfmDemodulator returns interleaved [L₀, R₀, L₁, R₁, …] at wfmIfRate.
        // AudioEngine uses a mono AudioTrack; writing the stereo buffer directly
        // would play at 2× speed with alternating L/R artefacts.
        // Mix to (L+R)/2 mono before the resampler.
        // Allocation note: the mono mix buffer is reused across calls (grown
        // only when the block size changes) instead of allocating a fresh
        // FloatArray every block — this ran ~15-20 times/sec at the WFM IF
        // rate and contributed to GC-induced audio choppiness. The mono path
        // (non-WFM-stereo) remains a zero-alloc direct reference.
        val monoAudio = if (demodMode == DemodMode.WFM_STEREO
                && audio.size >= 2 && audio.size % 2 == 0) {
            val outN = audio.size / 2
            if (monoMixBuf.size != outN) monoMixBuf = FloatArray(outN)
            val mono = monoMixBuf
            for (i in mono.indices) mono[i] = (audio[2 * i] + audio[2 * i + 1]) * 0.5f
            mono
        } else {
            audio
        }

        // ── RDS decoder — needs mono audio at the WFM IF rate ─────────────────
        // Lazily (re)creates the RDS decoder whenever the IF rate changes so it
        // always operates at the correct rate for the RDS sub-carrier (57 kHz).
        if ((demodMode == DemodMode.WFM || demodMode == DemodMode.WFM_STEREO)
                && monoAudio.isNotEmpty()) {
            getOrCreateRdsDecoder(demodRate).feedAudio(monoAudio)
        }

        // ── Resample mono audio → 48 kHz for protocol decoders / audioFlow ────
        // Protocol decoders (ACARS, APRS, RDS) and audioFlow subscribers always
        // need 48 kHz regardless of the user-selected audio-sink rate.
        // resampleForDecoder() is a no-op when demodRate is already 48 kHz.
        val decoderAudio = if (monoAudio.isNotEmpty()) {
            resampleForDecoder(monoAudio, demodRate)
        } else {
            FloatArray(0)
        }

        // ── Resample mono audio → audioSinkRate for AudioTrack ────────────────
        // narrowIfRateFor() now ensures demodRate is always the smallest exact
        // divisor of deviceRate that is ≥ audioSinkRate, so three cases arise:
        //
        //   demodRate == audioSinkRate  → ComplexDecimator already landed at the
        //     exact sink rate.  No resampler needed; use monoAudio directly.
        //     (GnuRadio direct-decimation path — e.g. 1920kS/s→48kHz, ÷40.)
        //
        //   demodRate == DEFAULT_AUDIO_RATE (48 kHz) and audioSinkRate==48 kHz
        //     → reuse decoderAudio (same array, already at 48 kHz).
        //
        //   demodRate > audioSinkRate  → rational resample demodRate→sinkRate.
        //     (e.g. 250kS/s→50kHz→32kHz: demodRate=50k, sinkRate=32k, ratio 16/25.)
        val sinkAudio = if (monoAudio.isNotEmpty()) {
            // For protocol decoder modes where we bypassed the squelch gate above
            // to produce real audio for the decoder: re-apply squelch silence to
            // the AudioTrack path so the user doesn't hear raw AFSK/digital tones
            // through the speaker when the squelch is closed.
            if (isProtocolDecoderMode && squelchGated) {
                val silenceSamples = (monoAudio.size.toLong() * audioSinkRate / demodRate)
                    .toInt().coerceAtLeast(1)
                FloatArray(silenceSamples)
            } else {
                when {
                    demodRate == audioSinkRate          -> monoAudio        // direct: no resampler
                    audioSinkRate == DEFAULT_AUDIO_RATE -> decoderAudio     // reuse decoder output
                    else                               -> resampleForSink(monoAudio, demodRate)
                }
            }
        } else {
            FloatArray(0)
        }

        // ── Resample tick (log the sink path) ─────────────────────────────────
        if (sinkAudio.isNotEmpty()) {
            DebugBus.tick(DebugBus.STAGE_AUDIO_RESAMPLE)
            if (DebugBus.snapshot()[DebugBus.STAGE_AUDIO_RESAMPLE].counter % 15L == 0L) {
                DebugBus.setDetail(DebugBus.STAGE_AUDIO_RESAMPLE,
                    "${demodRate} → ${audioSinkRate} Hz  ${sinkAudio.size} samples out")
            }
        }

        // ── Protocol decoders (non-WFM) — feed 48 kHz decoderAudio ───────────
        // acarsDecoder was constructed with AcarsDecoder(48_000) → correct rate.
        // pocsagDecoder is now constructed with PocsagDecoder(48_000) (FIX 28,
        // see the field declaration above) to match decoderAudio's actual rate.
        // POCSAG mode/support has been removed; pocsagDecoder now only runs
        // for DemodMode.FLEX.
        if (demodMode == DemodMode.ACARS && decoderAudio.isNotEmpty()) {
            acarsDecoder.feed(decoderAudio)
        }
        if (demodMode == DemodMode.FLEX
                && decoderAudio.isNotEmpty()) {
            pocsagDecoder.feed(decoderAudio)
        }
        // aprsDecoder: APRS mode uses NFM demodulation; the 48 kHz audio
        // produced by the narrow-FM discriminator is fed directly to the AFSK
        // decoder.
        //
        // FIX 21: feed() is a plain synchronous function now (see
        // ProtocolDecoders.kt) and is called inline, on this same DSP
        // processing thread/coroutine, rather than via scope.launch{}.
        // scope uses Dispatchers.Default — a multi-threaded pool — so
        // launching a new coroutine per IQ block gave no guarantee that
        // block N's feed() would finish (or even start) before block N+1's,
        // letting concurrent calls race on bitBuffer / samplesSinceBit /
        // lastRawBit / syncArmed / lastBit and the bandpass-filter envelope
        // state. That silently corrupted the bit-clock on essentially every
        // packet, regardless of how correct the underlying AFSK/AX.25 logic
        // (FIX 1-19) was. Calling feed() directly here guarantees blocks are
        // processed in order, on one thread, with no extra allocation.
        if (demodMode == DemodMode.APRS && decoderAudio.isNotEmpty()) {
            aprsDecoder.feed(decoderAudio)
        }
        // Digital voice decoder — DMR / P25 / NXDN / D-STAR / YSF / M17.
        // Uses the same raw FM-discriminated 48 kHz audio as APRS (AprsDemodulator
        // path — no post-discriminator AGC, LPF, or limiter). DigitalVoiceDecoder
        // performs its own clock recovery, 4FSK symbol slicing, and sync-word
        // correlation. Emits DigitalFrame events on digitalVoiceDecoder.frames.
        //
        // FIX: feed() is a plain synchronous function now (see
        // DigitalVoiceDecoder.kt), and is called inline, on this same DSP
        // processing thread/coroutine, rather than via scope.launch{}.
        // scope uses Dispatchers.Default — a multi-threaded pool — so
        // launching a new coroutine per IQ block gave no guarantee that
        // block N's feed() would finish (or even start) before block N+1's,
        // letting concurrent calls race on agcEnv/dcAcc/phase/tedPrev/
        // thrHigh/thrLow/symBuf/symWrite and the Gardner TED state. That
        // silently corrupted the symbol clock on essentially every frame, so
        // sync words almost never matched and the AMBE/Codec2 vocoder was
        // essentially never invoked — the receiver looked alive (AGC/RSSI
        // still updated) while producing silence. Calling feed() directly
        // here guarantees blocks are processed in order, on one thread, with
        // no extra allocation — the same fix already applied to the APRS/
        // AFSK decoder above (see "FIX 21").
        if (decoderAudio.isNotEmpty() && demodMode in DIGITAL_VOICE_MODES && !squelchGated) {
            DebugBus.setExtra(DebugBus.STAGE_DEMODULATOR, DebugBus.EXTRA_DV_FEED_RATE,
                "${demodRate} Hz \u2192 ${DEFAULT_AUDIO_RATE} Hz (decoderAudio)")
            digitalVoiceDecoder.feed(decoderAudio, squelchSignalDb)
        }

        // ── Publish 48 kHz audio baseband for APRS / other subscribers ────────
        if (decoderAudio.isNotEmpty() && _audioFlow.subscriptionCount.value > 0) {
            _audioFlow.tryEmit(decoderAudio)
        }

        // ── Pipe the same pre-vocoder audio to an external decoder app ────────
        // Independent of demod mode/squelch gating above: a user piping to an
        // external decoder (e.g. to follow a protocol 9GRadio doesn't decode
        // natively, or to cross-check the built-in dsdcc decode) wants the
        // raw discriminator feed exactly as-is, not just the subset already
        // routed to DigitalVoiceDecoder. No-op when disabled (see
        // ExternalDecoderStream.send()).
        if (decoderAudio.isNotEmpty()) {
            externalDecoderStream.send(decoderAudio)
        }

        // ── Write to AudioTrack ───────────────────────────────────────────────
        // Digital voice modes: decoded speech comes from the DSD-Neo/AMBE
        // vocoder via DigitalFrame.pcmAudio, written to audioWriteChannel by
        // the dedicated startDigVoiceAudio() coroutine (one write per ~20 ms
        // decoded voice frame).
        //
        // BUGFIX: this function used to ALSO push a same-size silence block to
        // audioWriteChannel on every single IQ block while in a digital voice
        // mode, "to avoid AudioTrack starvation". But processIqBlock() runs at
        // the IQ processing rate (hundreds of blocks/sec) — vastly faster than
        // voice frames arrive — into a small capacity=8 DROP_OLDEST channel.
        // Those frequent silence writes evicted the real decoded PCM sitting
        // in the queue almost as soon as startDigVoiceAudio() enqueued it,
        // so the AMBE/DSD-Neo decoder could be decoding perfectly and the
        // speaker would still only ever produce silence. Digital voice modes
        // now skip this per-IQ-block write entirely and let the per-frame
        // writer be the sole producer for audioWriteChannel; AudioTrack
        // underrun during the (typically <100 ms) gap between voice frames
        // is far less audible than permanently dropping all decoded audio.
        if (!isDigital && sinkAudio.isNotEmpty()) {
            var audioOut = sinkAudio
            // Noise processing only applies to analogue demod modes
            if (noiseBlankerEnabled) {
                audioOut = noiseBlanker.process(audioOut)
            }
            if (noiseReducerEnabled) {
                if (!noiseReducer.isCalibrated()) noiseReducer.calibrate(audioOut)
                audioOut = noiseReducer.process(audioOut)
            }
            val vol = volume
            if (vol > 1.001f) {
                for (i in audioOut.indices) {
                    audioOut[i] = (audioOut[i] * vol).coerceIn(-1f, 1f)
                }
            }
            audioWriteChannel.trySend(audioOut)
            DebugBus.tick(DebugBus.STAGE_AUDIO_TRACK)
            if (DebugBus.snapshot()[DebugBus.STAGE_AUDIO_TRACK].counter % 15L == 0L) {
                val rate = DebugBus.snapshot()[DebugBus.STAGE_AUDIO_TRACK].ratePerSec
                DebugBus.setDetail(DebugBus.STAGE_AUDIO_TRACK,
                    "${audioSinkRate} Hz  ${sinkAudio.size} samp/write  ${rate.toInt()} writes/s  vol: $volume")
            }
        } else if (isDigital) {
            // Still tick the stage so the debug panel shows this path as alive
            // (the actual audio write happens in startDigVoiceAudio()).
            DebugBus.tick(DebugBus.STAGE_AUDIO_TRACK)
        }

        // ── Stats update ──────────────────────────────────────────────────────
        // bufferDrops reflects IQ blocks silently discarded by the SharedFlow
        // DROP_OLDEST policy when processIqBlock() is too slow to keep up.
        // The count lives in DebugBus (incremented by RtlSdrDevice.tryEmit()).
        _stats.value = _stats.value.copy(
            sampleRate = device.getSampleRate(),
            audioRate  = audioSinkRate,
            demodMode = demodMode.displayName,
            squelchOpen = squelchOpen,
            signalDb = signalDb,
            narrowbandCenterDb = narrowbandCenterDb,
            isRecordingIq = iqRecorder.isRecording,
            isRecordingAudio = audioEngine.isRecording,
            bufferDrops = DebugBus.getIqPerf().flowDropCount.toInt()
        )
    }

    // ── Sink resampler ────────────────────────────────────────────────────────
    // Converts demod audio (at demodRate) → audioSinkRate for the AudioTrack.
    // Persists across blocks so the polyphase delay-line gives continuous-time
    // reconstruction at USB transfer boundaries (no click/glitch at block edges).
    // Invalidated (set null) by setAudioSinkRate() whenever the user switches
    // the output rate; also rebuilt automatically when demodRate changes (e.g.
    // switching between WFM IF rate and narrow-mode rate).
    private var audioResampler: PolyphaseResampler? = null
    private var resamplerInRate  = 0
    private var resamplerOutRate = 0

    /**
     * Resample [input] from [inRate] to the current [audioSinkRate].
     * Only called when demodRate > audioSinkRate (the PolyphaseResampler is not
     * trivially 1:1).  When demodRate == audioSinkRate the caller uses [input]
     * directly (no-op fast path) and this function is never invoked.
     */
    private fun resampleForSink(input: FloatArray, inRate: Int): FloatArray {
        val outRate = audioSinkRate
        if (inRate == outRate) return input
        if (inRate != resamplerInRate || outRate != resamplerOutRate || audioResampler == null) {
            audioResampler = PolyphaseResampler(inRate, outRate)
            resamplerInRate  = inRate
            resamplerOutRate = outRate
            Log.d(TAG, "Sink PolyphaseResampler created: $inRate → $outRate Hz")
        }
        return audioResampler!!.process(input)
    }

    // ── Decoder resampler ─────────────────────────────────────────────────────
    // Protocol decoders (ACARS, APRS, RDS) and the audioFlow subscribers always
    // require audio at DEFAULT_AUDIO_RATE (48 000 Hz) regardless of the user's
    // chosen audio-sink rate.  This second resampler bridges demodRate → 48 kHz
    // and is independent of the sink resampler so both can maintain their own
    // delay-line state without interference.
    private var decoderResampler: PolyphaseResampler? = null
    private var decoderResamplerInRate  = 0
    private var decoderResamplerOutRate = 0

    /**
     * Resample [input] from [inRate] to [DEFAULT_AUDIO_RATE] (48 000 Hz) for
     * protocol decoders and audioFlow subscribers.
     * Returns [input] unchanged when [inRate] already equals 48 000 Hz.
     */
    private fun resampleForDecoder(input: FloatArray, inRate: Int): FloatArray {
        val outRate = DEFAULT_AUDIO_RATE
        if (inRate == outRate) return input
        if (inRate != decoderResamplerInRate || outRate != decoderResamplerOutRate
                || decoderResampler == null) {
            decoderResampler = PolyphaseResampler(inRate, outRate)
            decoderResamplerInRate  = inRate
            decoderResamplerOutRate = outRate
            Log.d(TAG, "Decoder PolyphaseResampler created: $inRate → $outRate Hz")
        }
        return decoderResampler!!.process(input)
    }

    private fun updateStats() {
        _stats.value = _stats.value.copy(
            sampleRate = device.getSampleRate(),   // keep sample-rate current on every settings change
            demodMode = demodMode.displayName,
            audioVolume = volume,
            isRecordingIq = iqRecorder.isRecording,
            isRecordingAudio = audioEngine.isRecording
        )
    }
}
