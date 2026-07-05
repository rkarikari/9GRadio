package com.radiosport.ninegradio.dsp

/**
 * All supported demodulation modes for 9GRadio.
 */
enum class DemodMode(
    val displayName: String,
    val defaultBwHz: Int,
    val minFreqHz: Long,
    val maxFreqHz: Long
) {
    AM   ("AM",      10_000,      500_000L, 1_766_000_000L),
    FM   ("FM",      200_000,   65_000_000L, 1_766_000_000L),
    NFM  ("NFM",      12_500,    500_000L, 1_766_000_000L),
    WFM  ("WFM",     200_000,  65_000_000L,   108_000_000L),
    WFM_STEREO("WFM Stereo", 200_000, 65_000_000L, 108_000_000L),
    USB  ("USB",       3_000,     500_000L, 1_766_000_000L),
    LSB  ("LSB",       3_000,     500_000L, 1_766_000_000L),
    CW   ("CW",          500,     500_000L, 1_766_000_000L),
    CWR  ("CWR",         500,     500_000L, 1_766_000_000L),
    DSB  ("DSB",       6_000,     500_000L, 1_766_000_000L),
    RAW  ("RAW IQ",  200_000,     500_000L, 1_766_000_000L),
    DRM  ("DRM",      15_000,       3_000L,  28_800_000L),
    APRS ("APRS",     12_500,  140_000_000L, 148_000_000L),
    ADSB ("ADS-B",   1_000_000, 1_090_000_000L, 1_090_000_000L),
    ACARS("ACARS",    25_000,  126_000_000L, 136_975_000L),
    FLEX ("FLEX",     12_500,  900_000_000L, 940_000_000L),

    // ── Digital voice modes — 12.5 kHz NFM channel, 4FSK/C4FM/GMSK ──────────
    //
    // All digital voice modes share the NFM/AprsDemodulator discriminator
    // pipeline and feed their demodulated audio into DigitalVoiceDecoder.
    // The decoder identifies the protocol from sync words and — when the
    // DSD-Neo native library is present — decodes voice frames.
    //
    // References:
    //   DMR    — ETSI TS 102 361-1, 430–470 MHz UHF / 136–174 MHz VHF
    //   P25    — TIA-102, 136–174 / 380–512 / 700–800 MHz
    //   NXDN   — ICOM/Kenwood, 136–174 / 400–512 MHz
    //   D-STAR — JARL, 144 / 430 / 1200 MHz amateur
    //   YSF    — Yaesu, 144 / 430 MHz amateur
    //   M17    — M17 Project, 144 / 430 / 900 MHz amateur
    //   dPMR   — ETSI TS 102 658, 6.25 kHz FDMA, 66–960 MHz (PMR446: 446 MHz)
    DMR  ("DMR",       12_500,   136_000_000L, 1_766_000_000L),
    P25  ("P25",       12_500,   136_000_000L,   900_000_000L),
    NXDN ("NXDN",      6_250,    136_000_000L,   512_000_000L),
    DSTAR("D-STAR",   12_500,   144_000_000L, 1_300_000_000L),
    YSF  ("YSF",       12_500,   144_000_000L,   450_000_000L),
    M17  ("M17",       12_500,   144_000_000L, 1_000_000_000L),
    DPMR ("dPMR",      6_250,     66_000_000L,   960_000_000L),

    // ── Auto-detect digital voice — 12.5 kHz NFM channel ─────────────────
    //
    // DIG passes the discriminator audio into a dsdcc DSDDecodeAuto decoder,
    // which tries all known sync words (DMR, P25, NXDN, D-STAR, YSF) and
    // locks onto whichever is detected first.  The identified protocol is
    // reported back in every DigitalFrame via DigitalFrame.Protocol so the
    // UI can display the actual mode.  Useful for scanning unknown digital
    // voice traffic without knowing the protocol in advance.
    DIG  ("Dig",       12_500,   136_000_000L, 1_766_000_000L);

    val requiresDirectSampling: Boolean
        get() = maxFreqHz <= RtlSdrDevice_CONSTS.HF_CUTOFF_HZ

    /**
     * Default intermediate rate (Hz) used for the ComplexDecimator that narrows
     * the device bandwidth to a per-mode channel width before demodulation.
     *
     * All values are exact factors of 48 000 Hz (the audio output rate and the
     * new NARROW_INTERMEDIATE_RATE), so deviceRate → ifRate → 48 000 audio is
     * always a two-step integer decimation with no fractional resampling:
     *
     *   CW / CWR  →   8 000 Hz  (48 000 ÷ 6)   ±500 Hz tone
     *   USB / LSB →  12 000 Hz  (48 000 ÷ 4)   300–3 000 Hz voice SSB
     *   DSB       →  16 000 Hz  (48 000 ÷ 3)   ±6 kHz double-sideband
     *   AM        →  24 000 Hz  (48 000 ÷ 2)   ±10 kHz broadcast AM
     *   NFM/FLEX  →  16 000 Hz (48 000 ÷ 3)   ±5 kHz FM deviation
     *   Digital voice (DMR/P25/NXDN/D-STAR/YSF/M17) → 16 000 Hz (48 000 ÷ 3)
     *   ACARS     →  24 000 Hz  (48 000 ÷ 2)   25 kHz VHF aviation channel
     *   All others (incl. APRS) → 48 000 Hz (48 000 ÷ 1) full narrow-mode bandwidth
     *
     *   FIX 20: APRS deliberately falls into "All others" rather than the
     *   NFM/FLEX bucket — see the APRS branch comment below for why.
     */
    fun defaultIfRate(): Int = when (this) {
        CW, CWR          ->  8_000   // 48 000 ÷ 6
        USB, LSB         -> 12_000   // 48 000 ÷ 4
        DSB              -> 16_000   // 48 000 ÷ 3
        AM               -> 24_000   // 48 000 ÷ 2
        NFM, FM,
        FLEX,
        DMR, P25, NXDN,
        DSTAR, YSF, M17,
        DPMR,
        DIG              -> 16_000   // 48 000 ÷ 3
        ACARS            -> 24_000   // 48 000 ÷ 2
        // FIX 20: APRS must NOT share the 16 kHz voice-NFM IF bucket.
        //
        // DspEngine.setDemodMode() locks ifBandwidthHz to this value on every
        // mode switch (it is only left as the -1/auto sentinel when
        // modeDefault >= the dynamic ceiling). With APRS grouped at 16 kHz,
        // every single-channel APRS session was pinned to a 16 kHz IF —
        // three times narrower than the path the rest of this file actually
        // designs around.
        //
        // The dual-watch APRS path (DspEngine.ensureDualAprsChain /
        // enableDualAprs) deliberately targets ~48 kHz via
        // narrowIfRate(deviceRate) = narrowIfRateFor(deviceRate,
        // DEFAULT_AUDIO_RATE), and AprsDecoder's AfskBandpassFilter/AGC/PLL
        // constants (FIX 1, 2, 11, 16) were all tuned assuming that signal
        // chain. Single-channel mode — the common case, since dual-watch
        // requires >= 820 kS/s — silently used a different, narrower IF and
        // was never brought in line with that design.
        //
        // Falling through to the 48 000 default was *intended* to make
        // single-channel APRS resolve the same way dual-watch does, by
        // leaving setDemodMode()'s ifBandwidthHz at the -1/auto sentinel so
        // resolveNarrowIfRate() picks the correct sink-rate-relative IF.
        //
        // FIX 24 correction: the assumption "modeDefault (48 000) is usually
        // >= the dynamic ceiling" is FALSE at the device sample rates most
        // RTL-SDR dongles default to. narrowIfRateFor(deviceRate, 48 000) —
        // the smallest exact divisor of deviceRate that is >= 48 000 — is
        // 51 200 Hz at 2.048 / 1.024 / 2.56 MS/s and 50 000 Hz at 3.2 MS/s,
        // all strictly greater than 48 000. setDemodMode()'s
        // "modeDefault >= dynamicCeiling" check therefore evaluated false on
        // exactly those rates and pinned ifBandwidthHz to a literal 48 000 —
        // which is not an exact divisor of those device rates — instead of
        // leaving it on auto. See DspEngine.setDemodMode() FIX 24 for the fix:
        // APRS is now routed into the auto branch unconditionally rather than
        // relying on this (sometimes-false) comparison.
        else             -> 48_000   // 48 000 ÷ 1 (maximum narrow rate)
    }

    companion object {
        fun fromDisplayName(name: String): DemodMode? = values().find { it.displayName == name }
    }

    // ── Intelligent Auto-Configuration defaults ──────────────────────────────
    //
    // "True intelligence" seed values applied the FIRST time a protocol is
    // selected (i.e. before the user has ever saved a per-mode snapshot via
    // MainViewModel.saveSettingsForMode). Once the user changes any of these
    // for a mode, their choice is persisted per-mode as usual and these
    // defaults are no longer consulted for that mode -- this only sets a
    // sensible starting point, it never fights a manual override.
    //
    //   FFT size      : 2048 bins for every mode (fixed per spec).
    //   Frame avg.    : x8 for every mode (fixed per spec).
    //   Decimation    : chosen per-protocol to match the signal's actual
    //                   occupied bandwidth, narrowing the analysis bandwidth
    //                   (and thus improving effective SNR / bin resolution)
    //                   for narrowband/slow protocols, while leaving wide
    //                   protocols undecimated so their full channel remains
    //                   visible:
    //                     AM             -> 16   (narrow AM channel, some
    //                                              margin for tuning by ear)
    //                     NFM            -> 32   (12.5 kHz channel -- narrow)
    //                     WFM            -> Off  (1) -- 200 kHz broadcast
    //                                              channel needs full BW
    //                     WFM Stereo     -> Off  (1) -- same as WFM
    //                     USB/LSB        -> 32   (3 kHz voice SSB -- narrow)
    //                     CW/CWR         -> 64   (single tone, extremely
    //                                              narrow -- most decimation)
    //                     APRS           -> 16   (12.5 kHz AFSK channel)
    //                     DMR/YSF/D-STAR -> 8    (12.5 kHz digital voice,
    //                                              but sync/frame timing is
    //                                              sensitive to over-narrowing)
    //                   All other modes default to 1 (off) -- unclassified
    //                   or wide-bandwidth protocols (FM, DSB, RAW, DRM,
    //                   ADS-B, ACARS, FLEX, P25, NXDN, M17, DIG) keep full
    //                   analysis bandwidth until the user chooses otherwise.
    val defaultFftSize: Int get() = 2048
    val defaultFrameAveraging: Int get() = 8

    val defaultDecimation: Int
        get() = when (this) {
            AM                -> 16
            NFM               -> 32
            WFM, WFM_STEREO   -> 1        // "Off"
            USB, LSB          -> 32
            CW, CWR           -> 64
            APRS              -> 16
            DMR, YSF, DSTAR   -> 8
            else              -> 1        // Off / unclassified -- full bandwidth
        }
}

// Reference constants to avoid circular import
private object RtlSdrDevice_CONSTS {
    const val HF_CUTOFF_HZ = 28_800_000L
}

/**
 * Base class for all demodulators.
 */
abstract class Demodulator {
    abstract val mode: DemodMode

    /** Process a block of complex IQ samples (interleaved float I, Q).
     *  Returns a float array of audio samples (mono unless stereo WFM). */
    abstract fun demodulate(iq: FloatArray, sampleRate: Int): FloatArray

    protected fun complexMagnitude(i: Float, q: Float): Float =
        Math.sqrt((i * i + q * q).toDouble()).toFloat()

    protected fun atan2Fast(y: Float, x: Float): Float = Math.atan2(y.toDouble(), x.toDouble()).toFloat()
}

