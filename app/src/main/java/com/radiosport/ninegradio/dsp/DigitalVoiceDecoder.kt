package com.radiosport.ninegradio.dsp

import android.util.Log
import com.radiosport.ninegradio.debug.DebugBus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.math.*

// ============================================================
//  Digital Voice Decoder  –  DMR / YSF / D-STAR
//
//  Audio path (mbelib — always available):
//    RTL-SDR IQ → FM discriminator (NativeDsp) → 48 kHz float
//    → feed() → short[] → dsdcc DSDDecoder.run(sample)
//    → dsdcc invokes mbelib internally on each DV frame
//    → getAudio1()/getAudio2() → PCM int16 accumulated in native buffer
//    → DsdccNative.poll().pcmAudio → DigitalFrame.pcmAudio
//    → DspEngine.startDigVoiceAudio() → AudioTrack
// ============================================================

data class DigitalFrame(
    val protocol:    Protocol,
    val frameType:   FrameType,
    val srcId:       Int        = 0,
    val dstId:       Int        = 0,
    val isGroup:     Boolean    = false,
    val encrypted:   Boolean    = false,
    val emergency:   Boolean    = false,
    val talkerAlias: String     = "",
    val rawSymbols:  ShortArray = ShortArray(0),
    val pcmAudio:    ShortArray = ShortArray(0),
    val rssi:        Float      = -100f,
    val snr:         Float      = 0f,
    val timestamp:   Long       = System.currentTimeMillis()
) {
    enum class Protocol { DMR, P25_PHASE1, P25_PHASE2, NXDN48, NXDN96, DSTAR, YSF, M17, DPMR, UNKNOWN }
    enum class FrameType { VOICE, DATA, CONTROL, SYNC_ONLY }
}

// ── Legacy JNI companion (kept for backward compat) ─────────────────────────
object DigitalVoiceJni {
    private const val TAG = "DigitalVoiceJni"
    var nativeAvailable = false
        private set
    var statusText: String = "DSD-Neo library: initialising"
        private set

    init {
        try {
            System.loadLibrary("dsd_neo")
            nativeAvailable = true
            statusText = "DSD-Neo library: loaded (mbelib built-in)"
            Log.i(TAG, "DSD-Neo native library loaded")
        } catch (e: UnsatisfiedLinkError) {
            nativeAvailable = false
            statusText = "DSD-Neo library: not present"
            Log.w(TAG, "DSD-Neo not present: ${e.message}")
        }
    }

    fun decode(symbols: ByteArray, protocol: Int): DsdNeoResult =
        if (nativeAvailable) {
            try { nativeDecode(symbols, protocol) }
            catch (e: Exception) { Log.w(TAG, "Native decode error: ${e.message}"); DsdNeoResult() }
        } else DsdNeoResult()

    data class DsdNeoResult(
        val srcId:       Int        = 0,
        val dstId:       Int        = 0,
        val isGroup:     Boolean    = false,
        val encrypted:   Boolean    = false,
        val emergency:   Boolean    = false,
        val talkerAlias: String     = "",
        val pcmAudio:    ShortArray = ShortArray(0)
    )

    @JvmStatic private external fun nativeDecode(symbols: ByteArray, protocol: Int): DsdNeoResult
}

// ============================================================
//  Main decoder class — streaming handle-based path
// ============================================================

class DigitalVoiceDecoder {

    private val _frames = MutableSharedFlow<DigitalFrame>(extraBufferCapacity = 32)
    val frames: SharedFlow<DigitalFrame> = _frames.asSharedFlow()

    @Volatile private var activeMode: DemodMode = DemodMode.DMR

    // Native dsdcc handle (-1 = not created / library absent)
    @Volatile private var nativeHandle: Long = -1L

    // Reusable short buffer (avoids per-block allocation)
    private var shortBuf = ShortArray(4096)

    // Debug counters
    private var dbgSamples = 0L
    private var dbgFrames  = 0L
    private var dbgPcmSamples = 0L

    // RSSI
    private var rssiDb = -100f

    fun setMode(mode: DemodMode) {
        if (mode != activeMode) {
            activeMode = mode
            resetState()
        }
    }

    fun reset() = resetState()

    /**
     * Call this when the vocoder mode changes (mbelib ↔ plugin).
     * Destroys and recreates the native decoder handle so it is
     * initialised with the new enableMbelib() flag.
     */
    fun rebuildHandle() {
        DsdccNative.destroy(nativeHandle)
        nativeHandle = -1L
        val proto = activeProtoOrdinal()
        if (proto >= 0 && DsdccNative.available) {
            nativeHandle = DsdccNative.create(proto)
            Log.i("DigVoice", "rebuildHandle mode=${activeMode} " +
                  "vocoder=${DsdccNative.vocoderModeLabel} handle=$nativeHandle")
        }
    }

    // --------------------------------------------------------
    //  feed() — main entry from DspEngine
    //  samples: 48 kHz normalised float discriminator output
    // --------------------------------------------------------

    fun feed(samples: FloatArray, rfSignalDb: Float = Float.NaN) {
        if (!rfSignalDb.isNaN()) rssiDb = rfSignalDb

        val n = samples.size
        if (shortBuf.size < n) shortBuf = ShortArray(n)
        for (i in 0 until n) {
            shortBuf[i] = (samples[i] * 32767f).toInt()
                .coerceIn(-32768, 32767).toShort()
        }

        if (DsdccNative.available && nativeHandle >= 0) {
            // ── Native path (dsdcc + mbelib or plugin) ──────────────────────
            DsdccNative.feed(nativeHandle, shortBuf.copyOf(n))
            val meta = DsdccNative.poll(nativeHandle)

            dbgSamples += n
            if (meta.pcmAudio.isNotEmpty()) dbgPcmSamples += meta.pcmAudio.size

            if (meta.dvReady) {
                dbgFrames++
                emitFromMeta(meta)
            }

            // Debug reporting every ~1 s
            if (dbgSamples % 48_000L < n) {
                DebugBus.setExtra(DebugBus.STAGE_DEMODULATOR, DebugBus.EXTRA_DV_SYMBOLS,
                    "samples=${dbgSamples/1000}k frames=$dbgFrames " +
                    "pcm=${dbgPcmSamples/8000}s vocoder=${DsdccNative.vocoderModeLabel}")
                DebugBus.setExtra(DebugBus.STAGE_DEMODULATOR, DebugBus.EXTRA_DV_SYNC_BEST,
                    "${activeMode.displayName} src=${meta.srcCall} dst=${meta.dstCall}")

                // FEC/vocoder telemetry — useful for diagnosing "frame syncs but
                // audio is garbled/unintelligible" (high C0/total error counts
                // point to weak signal or an FEC issue) vs "no errors but still
                // garbled" (points to a synthesis-side bug instead). Previously
                // gated to DMR/DIG only, even though slot 1 stats are populated
                // for every protocol (P25/D-STAR/YSF/NXDN/dPMR only ever use
                // slot 1; slot 2 fields simply stay at 0 for those).
                run {
                    val vs = DsdccNative.pollVocoderStats(nativeHandle)
                    DebugBus.setExtra(DebugBus.STAGE_DEMODULATOR, DebugBus.EXTRA_DV_ACTIVE_SLOT,
                        when (vs.activeSlot) { 1 -> "1"; 2 -> "2"; else -> "0 (none)" })
                    DebugBus.setExtra(DebugBus.STAGE_DEMODULATOR, DebugBus.EXTRA_DV_VOCODER_FLAGS,
                        "T1=${vs.toneCount1} E1=${vs.erasureCount1} R1=${vs.repeatCount1} M1=${vs.muteCount1} | " +
                        "T2=${vs.toneCount2} E2=${vs.erasureCount2} R2=${vs.repeatCount2} M2=${vs.muteCount2}")
                    DebugBus.setExtra(DebugBus.STAGE_DEMODULATOR, DebugBus.EXTRA_DV_FRAME_ERRORS,
                        "S1 C0=${vs.lastC0Errors1}/Tot=${vs.lastTotalErrors1} | " +
                        "S2 C0=${vs.lastC0Errors2}/Tot=${vs.lastTotalErrors2}")
                }
            }

        } else {
            // ── Fallback: pure-Kotlin sync detection only, no voice ──────────
            kotlinFallbackFeed(shortBuf, n)
        }
    }

    // --------------------------------------------------------
    //  Emit a DigitalFrame from native metadata + PCM
    // --------------------------------------------------------

    private fun emitFromMeta(meta: DsdccNative.DecoderMeta) {
        val proto = when (activeMode) {
            DemodMode.DMR   -> DigitalFrame.Protocol.DMR
            DemodMode.DSTAR -> DigitalFrame.Protocol.DSTAR
            DemodMode.YSF   -> DigitalFrame.Protocol.YSF
            DemodMode.P25   -> DigitalFrame.Protocol.P25_PHASE1
            DemodMode.NXDN  -> DigitalFrame.Protocol.NXDN96
            DemodMode.DPMR  -> DigitalFrame.Protocol.DPMR
            DemodMode.DIG   -> when (meta.detectedProto) {
                // Map dsdcc proto ordinal → DigitalFrame.Protocol
                0    -> DigitalFrame.Protocol.DMR
                1    -> DigitalFrame.Protocol.P25_PHASE1
                3    -> DigitalFrame.Protocol.NXDN48
                4    -> DigitalFrame.Protocol.NXDN96
                5    -> DigitalFrame.Protocol.DSTAR
                6    -> DigitalFrame.Protocol.YSF
                8    -> DigitalFrame.Protocol.DPMR
                else -> DigitalFrame.Protocol.UNKNOWN
            }
            else            -> DigitalFrame.Protocol.UNKNOWN
        }

        val alias = buildString {
            if (meta.srcCall.isNotEmpty()) append(meta.srcCall.trim())
            if (meta.dstCall.isNotEmpty()) {
                if (isNotEmpty()) append("→")
                append(meta.dstCall.trim())
            }
        }

        val frame = DigitalFrame(
            protocol    = proto,
            frameType   = DigitalFrame.FrameType.VOICE,
            srcId       = meta.srcId,
            dstId       = meta.dstId,
            isGroup     = meta.isGroup,
            encrypted   = meta.encrypted,
            talkerAlias = alias,
            pcmAudio    = meta.pcmAudio,   // real PCM from mbelib or plugin
            rssi        = rssiDb
        )

        _frames.tryEmit(frame)
        DebugBus.tick(DebugBus.STAGE_DEMODULATOR)
        DebugBus.setExtra(DebugBus.STAGE_DEMODULATOR, DebugBus.EXTRA_DV_FRAMES_TOTAL,
            dbgFrames.toString())
    }

    // --------------------------------------------------------
    //  Kotlin fallback sync engine
    // --------------------------------------------------------

    private val SYNC_DMR_VOICE_BS   = intArrayOf(0,0,0,0,0,0,0,0,1,3,1,1,1,1,3,3,3,1,1,3,3,1,3,3,1,3,1,1,3,3,1,3)
    private val SYNC_DMR_DATA_BS    = intArrayOf(0,0,0,0,0,0,0,0,3,1,3,3,3,3,1,1,1,3,3,1,1,3,1,1,3,1,3,3,1,1,3,1)
    private val SYNC_DSTAR          = intArrayOf(0,0,0,0,0,0,0,0,3,1,3,1,3,1,3,1,3,1,3,3,1,3,1,1,1,3,3,1,3,1,1,1)
    private val SYNC_DSTAR_HDR      = intArrayOf(0,0,0,0,0,0,0,0,1,3,1,3,1,3,1,3,1,3,1,1,3,1,3,3,3,1,1,3,1,3,3,3)
    private val SYNC_YSF            = intArrayOf(0,0,0,0,0,0,0,0,0,0,0,0,3,1,1,1,1,3,1,1,3,1,3,1,1,3,1,3,1,1,3,1)

    private val syncHistory = IntArray(32)
    private var syncIdx = 0
    private var sampleIndex = 0
    private var symSum = 0; private var symCount = 0
    private var center = 0; private var maxLvl = 0; private var minLvl = 0
    private var umid = 0; private var lmid = 0
    private var lmmBuf = ShortArray(240); private var lmmIdx = 0
    private var lmmCount = 0
    private var dbgKtSymbols = 0L

    private fun kotlinFallbackFeed(buf: ShortArray, n: Int) {
        val sps = 10
        for (i in 0 until n) {
            val s = buf[i]
            lmmBuf[lmmCount % lmmBuf.size] = s
            lmmCount++
            if (sampleIndex in 4..5) { symSum += s; symCount++ }
            sampleIndex++
            if (sampleIndex >= sps) {
                sampleIndex = 0
                if (symCount > 0) {
                    val sym = symSum / symCount
                    symSum = 0; symCount = 0
                    lmmIdx++
                    if (lmmIdx >= 24) { lmmIdx = 0; updateMinMax() }
                    syncHistory[dbgKtSymbols.toInt() % 32] = if (sym > center) 1 else 3
                    dbgKtSymbols++
                    if (dbgKtSymbols >= 32) checkKtSync()
                }
            }
        }
    }

    private fun updateMinMax() {
        val n = minOf(lmmCount, lmmBuf.size)
        if (n == 0) return
        var lo = lmmBuf[0]; var hi = lmmBuf[0]
        for (i in 1 until n) {
            val v = lmmBuf[i % lmmBuf.size]
            if (v < lo) lo = v; if (v > hi) hi = v
        }
        maxLvl += (hi - maxLvl) / 4
        minLvl += (lo - minLvl) / 4
        center = (maxLvl + minLvl) / 2
        umid   = (maxLvl - center) / 2 + center
        lmid   = (minLvl - center) / 2 + center
    }

    private fun checkKtSync() {
        val hist = IntArray(32) { i ->
            syncHistory[((dbgKtSymbols - 32 + i).toInt() % 32 + 32) % 32]
        }
        fun matches(pattern: IntArray, tol: Int): Boolean {
            var errs = 0
            for (i in pattern.indices)
                if (pattern[i] != 0 && hist[i] != pattern[i]) { errs++; if (errs > tol) return false }
            return errs <= tol
        }
        val proto: DigitalFrame.Protocol
        val ft: DigitalFrame.FrameType
        when (activeMode) {
            DemodMode.DMR   -> if (matches(SYNC_DMR_VOICE_BS, 2) || matches(SYNC_DMR_DATA_BS, 2)) {
                                   proto = DigitalFrame.Protocol.DMR; ft = DigitalFrame.FrameType.SYNC_ONLY
                               } else return
            DemodMode.DSTAR -> if (matches(SYNC_DSTAR, 2) || matches(SYNC_DSTAR_HDR, 2)) {
                                   proto = DigitalFrame.Protocol.DSTAR; ft = DigitalFrame.FrameType.SYNC_ONLY
                               } else return
            DemodMode.YSF   -> if (matches(SYNC_YSF, 1)) {
                                   proto = DigitalFrame.Protocol.YSF; ft = DigitalFrame.FrameType.SYNC_ONLY
                               } else return
            DemodMode.DIG   -> when {
                                   matches(SYNC_DMR_VOICE_BS, 2) || matches(SYNC_DMR_DATA_BS, 2) ->
                                       { proto = DigitalFrame.Protocol.DMR;   ft = DigitalFrame.FrameType.SYNC_ONLY }
                                   matches(SYNC_DSTAR, 2) || matches(SYNC_DSTAR_HDR, 2) ->
                                       { proto = DigitalFrame.Protocol.DSTAR; ft = DigitalFrame.FrameType.SYNC_ONLY }
                                   matches(SYNC_YSF, 1) ->
                                       { proto = DigitalFrame.Protocol.YSF;   ft = DigitalFrame.FrameType.SYNC_ONLY }
                                   else -> return
                               }
            else -> return
        }
        dbgFrames++
        _frames.tryEmit(DigitalFrame(protocol = proto, frameType = ft, rssi = rssiDb))
        DebugBus.tick(DebugBus.STAGE_DEMODULATOR)
        DebugBus.setExtra(DebugBus.STAGE_DEMODULATOR, DebugBus.EXTRA_DV_SYNC_HITS,
            dbgFrames.toString())
    }

    // --------------------------------------------------------
    //  State reset — called on mode change
    // --------------------------------------------------------

    private fun activeProtoOrdinal(): Int = when (activeMode) {
        DemodMode.DMR   -> DsdccNative.PROTO_DMR
        DemodMode.DSTAR -> DsdccNative.PROTO_DSTAR
        DemodMode.YSF   -> DsdccNative.PROTO_YSF
        DemodMode.P25   -> DigitalFrame.Protocol.P25_PHASE1.ordinal
        DemodMode.NXDN  -> DigitalFrame.Protocol.NXDN96.ordinal
        DemodMode.DPMR  -> DsdccNative.PROTO_DPMR
        DemodMode.DIG   -> DsdccNative.PROTO_AUTO   // auto-detect all protocols
        else            -> -1
    }

    private fun resetState() {
        DsdccNative.destroy(nativeHandle)
        nativeHandle = -1L
        val proto = activeProtoOrdinal()
        if (proto >= 0) nativeHandle = DsdccNative.create(proto)

        syncHistory.fill(0); syncIdx = 0
        sampleIndex = 0; symSum = 0; symCount = 0
        center = 0; maxLvl = 0; minLvl = 0; umid = 0; lmid = 0
        lmmBuf.fill(0); lmmIdx = 0; lmmCount = 0
        dbgKtSymbols = 0L; dbgFrames = 0L; dbgSamples = 0L; dbgPcmSamples = 0L

        Log.d("DigVoice", "reset mode=${activeMode} handle=$nativeHandle " +
              "vocoder=${DsdccNative.vocoderModeLabel}")
    }
}
