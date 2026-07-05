package com.radiosport.ninegradio.dsp

import android.util.Log

/**
 * Thin Kotlin wrapper around the native dsdcc handle-based JNI API.
 *
 * ## Vocoder
 * AMBE+2 / IMBE decoding is always performed by the statically-linked
 * mbelib-neo library (vendored in cpp/mbelib-neo/), a clean-room software
 * implementation, GPL-2.0-or-later. Always available, no setup required.
 *
 * ## Audio pipeline
 * [feedSamples] feeds 48 kHz discriminator shorts into the native DSDDecoder.
 * When a DV frame is decoded, mbelib-neo produces 160 int16 PCM samples at
 * 8 kHz (×6 upsampling → native 48 kHz).
 * [getPcm] drains the native PCM accumulation buffer as a [ShortArray].
 * The caller (DspEngine / DigitalVoiceDecoder) writes these to AudioTrack.
 */
object DsdccNative {

    private const val TAG = "DsdccNative"

    val available: Boolean get() = DigitalVoiceJni.nativeAvailable

    // ── Protocol ordinals (must match DigitalFrame.Protocol enum order) ──────
    const val PROTO_DMR   = 0
    const val PROTO_DSTAR = 5
    const val PROTO_YSF   = 6
    /** Ordinal that maps to DSDDecodeAuto in dsdcc_jni.cpp (any value not otherwise mapped). */
    const val PROTO_AUTO  = 7
    /** dPMR (ETSI TS 102 658). Maps to DSDDecodeDPMR in dsdcc_jni.cpp. */
    const val PROTO_DPMR  = 8

    // ── Native declarations ──────────────────────────────────────────────────
    @JvmStatic external fun createDecoder(protocol: Int): Long
    @JvmStatic external fun destroyDecoder(handle: Long)
    @JvmStatic external fun feedSamples(handle: Long, samples: ShortArray, length: Int)

    /**
     * Returns int[8]: [srcId, dstId, isGroup, encrypted, dvReady, hasPcm,
     *                   fichError, mbeRate]
     * dvReady   — at least one DV frame was processed since the last call.
     * hasPcm    — PCM samples are waiting in the native buffer (call getPcm).
     * fichError — YSF FICH status: 0=OK 1=Golay fail 2=CRC fail -1=N/A
     * mbeRate   — DSDMBERate ordinal for the last frame (-1=N/A)
     */
    @JvmStatic external fun getMetadata(handle: Long): IntArray

    /** Drain the native PCM accumulation buffer.  Empty if no voice decoded yet. */
    @JvmStatic external fun getPcm(handle: Long): ShortArray

    @JvmStatic external fun getSrcCall(handle: Long): String
    @JvmStatic external fun getDstCall(handle: Long): String

    /**
     * Diagnostic telemetry for the debug panel.
     *
     * Returns long[15]: [activeSlot,
     *   framesSeen1, toneCount1, erasureCount1, repeatCount1, muteCount1, lastC0Errors1, lastTotalErrors1,
     *   framesSeen2, toneCount2, erasureCount2, repeatCount2, muteCount2, lastC0Errors2, lastTotalErrors2]
     *
     * activeSlot mirrors the JNI-layer DMR slot lock (0=unclaimed, 1 or 2).
     * tone/erasure/repeat/mute counts are cumulative MBE_PROCESS_FLAG_* hits
     * since the decoder handle was created — if toneCount climbs in lockstep
     * with an audible beat on DMR, the AMBE tone-frame path is the cause.
     */
    @JvmStatic external fun getVocoderStats(handle: Long): LongArray

    /**
     * Returns the protocol ordinal that dsdcc most recently locked onto
     * when running in DSDDecodeAuto mode, or -1 if no sync has been acquired.
     * Mapping:
     *   0 = DMR, 1 = P25 Phase 1, 3 = NXDN48, 4 = NXDN96, 5 = D-STAR, 6 = YSF,
     *   8 = dPMR.
     * Only meaningful when the decoder was created with [PROTO_AUTO].
     */
    @JvmStatic external fun getDetectedProtocol(handle: Long): Int

    // ── Managed decoder lifecycle ────────────────────────────────────────────

    fun create(protocol: Int): Long {
        if (!available) return -1L
        return try {
            createDecoder(protocol)
        } catch (e: Exception) {
            Log.w(TAG, "createDecoder failed: ${e.message}")
            -1L
        }
    }

    fun destroy(handle: Long) {
        if (!available || handle < 0) return
        try { destroyDecoder(handle) } catch (_: Exception) {}
    }

    fun feed(handle: Long, samples: ShortArray) {
        if (!available || handle < 0 || samples.isEmpty()) return
        try {
            feedSamples(handle, samples, samples.size)
        } catch (e: Exception) {
            Log.w(TAG, "feedSamples error: ${e.message}")
        }
    }

    data class DecoderMeta(
        val srcId:     Int     = 0,
        val dstId:     Int     = 0,
        val isGroup:   Boolean = false,
        val encrypted: Boolean = false,
        val dvReady:   Boolean = false,
        val hasPcm:    Boolean = false,
        val srcCall:   String  = "",
        val dstCall:   String  = "",
        val pcmAudio:  ShortArray = ShortArray(0),
        /** YSF FICH decode status: 0=OK 1=Golay 2=CRC -1=N/A. Diagnostic only. */
        val fichError: Int = -1,
        /** DSDMBERate ordinal for last frame. -1=N/A. */
        val mbeRate:   Int = -1,
        /**
         * Protocol detected by DSDDecodeAuto (-1=none yet, 0=DMR, 1=P25,
         * 3=NXDN48, 4=NXDN96, 5=D-STAR, 6=YSF).
         * Only meaningful when the decoder was created with [PROTO_AUTO].
         */
        val detectedProto: Int = -1
    )

    /**
     * Poll metadata and drain any available PCM from the decoder.
     * Returns a [DecoderMeta] with [pcmAudio] filled if voice was decoded.
     */
    fun poll(handle: Long): DecoderMeta {
        if (!available || handle < 0) return DecoderMeta()
        return try {
            val m = getMetadata(handle)
            val hasPcm = m.getOrElse(5) { 0 } != 0
            val pcm = if (hasPcm) getPcm(handle) else ShortArray(0)
            DecoderMeta(
                srcId     = m[0],
                dstId     = m[1],
                isGroup   = m[2] != 0,
                encrypted = m[3] != 0,
                dvReady   = m[4] != 0,
                hasPcm    = hasPcm,
                srcCall   = getSrcCall(handle),
                dstCall   = getDstCall(handle),
                pcmAudio  = pcm,
                fichError = m.getOrElse(6) { -1 },
                mbeRate   = m.getOrElse(7) { -1 },
                detectedProto = try { getDetectedProtocol(handle) } catch (_: Exception) { -1 }
            )
        } catch (e: Exception) {
            Log.w(TAG, "poll error: ${e.message}")
            DecoderMeta()
        }
    }

    /** Convenience: current vocoder mode as a human-readable label. */
    val vocoderModeLabel: String
        get() = if (!available) "unavailable" else "mbelib-neo (built-in)"

    data class VocoderStats(
        val activeSlot: Int = 0,
        val framesSeen1: Long = 0, val toneCount1: Long = 0, val erasureCount1: Long = 0,
        val repeatCount1: Long = 0, val muteCount1: Long = 0,
        val lastC0Errors1: Int = 0, val lastTotalErrors1: Int = 0,
        val framesSeen2: Long = 0, val toneCount2: Long = 0, val erasureCount2: Long = 0,
        val repeatCount2: Long = 0, val muteCount2: Long = 0,
        val lastC0Errors2: Int = 0, val lastTotalErrors2: Int = 0
    )

    /** Poll per-slot vocoder diagnostic telemetry. Safe to call even when unavailable. */
    fun pollVocoderStats(handle: Long): VocoderStats {
        if (!available || handle < 0) return VocoderStats()
        return try {
            val s = getVocoderStats(handle)
            if (s.size < 15) return VocoderStats()
            VocoderStats(
                activeSlot       = s[0].toInt(),
                framesSeen1      = s[1],  toneCount1 = s[2], erasureCount1 = s[3],
                repeatCount1     = s[4],  muteCount1 = s[5],
                lastC0Errors1    = s[6].toInt(), lastTotalErrors1 = s[7].toInt(),
                framesSeen2      = s[8],  toneCount2 = s[9], erasureCount2 = s[10],
                repeatCount2     = s[11], muteCount2 = s[12],
                lastC0Errors2    = s[13].toInt(), lastTotalErrors2 = s[14].toInt()
            )
        } catch (e: Exception) {
            Log.w(TAG, "getVocoderStats error: ${e.message}")
            VocoderStats()
        }
    }

    /**
     * Human-readable label for a YSF sub-mode from [DecoderMeta.mbeRate].
     * Returns null for non-YSF protocols or unavailable mbeRate.
     */
    fun ysfSubModeLabel(mbeRate: Int): String? = when (mbeRate) {
        3    -> "YSF V/D type 1 (AMBE+2 3600×2450)"
        6    -> "YSF V/D type 2 (AMBE+2 2450)"
        7    -> "YSF VFR (AMBE+2 7200×4400, mbelib only)"
        else -> null
    }
}
