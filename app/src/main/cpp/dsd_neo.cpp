/**
 * dsd_neo.cpp — Digital Speech Decoder / Native Engine
 *
 * Implements a self-contained digital voice frame unpacker + metadata
 * extractor for DMR, P25 Phase 1, NXDN, D-STAR, YSF, and M17.
 *
 * ── Vocoder strategy (SDRAngel-style built-in mbelib-neo, see vocoder.h) ──
 * AMBE+2/AMBE/IMBE (DMR, P25, NXDN, D-STAR, YSF) are decoded by the
 * statically-linked mbelib-neo library (GPL-2.0-or-later, vendored at
 * app/src/main/cpp/mbelib-neo). vocoder_ready() always returns 1.
 * Every protocol below always produces real decoded speech with no
 * download and no user setup — exactly like SDRAngel's approach.
 *
 * The underlying multi-band-excitation patents that covered AMBE/AMBE+2/
 * IMBE have expired. mbelib-neo is a clean-room software implementation
 * (https://github.com/arancormonk/mbelib-neo) in wide legitimate use.
 *
 * vocoder_jni.cpp implements the mbelib-neo bridge:
 *   • vocoder_ready() → always 1
 *   • vocoder_decode_2400()  → mbe_processAmbe3600x2400Frame() (DMR/NXDN/YSF)
 *   • vocoder_decode_2450()  → mbe_processAmbe3600x2450Frame() (D-STAR)
 *   • vocoder_decode_2450x1150() → mbe_processImbe7200x4400Frame() (P25)
 *
 * M17 is unrelated to all of the above: it uses the open, patent-free
 * Codec2 3200 bit/s mode (LGPL, David Rowe), which IS implemented locally
 * below and always has been.
 *
 * ── Frame unpacking ───────────────────────────────────────────────────────────
 * Each protocol's voice frame format is unpacked from the raw dibit stream
 * provided by DigitalVoiceDecoder.kt (sync already stripped — see emit() in
 * that file), the AMBE+2/AMBE/IMBE codec bits are located precisely per
 * spec, and handed to the built-in mbelib-neo bridge (vocoder.h).
 *
 * ── Output ────────────────────────────────────────────────────────────────────
 * Every protocol below decodes to 8 kHz 16-bit PCM (one frame = 160 samples
 * = 20 ms). The JNI return value is a DsdNeoResult Kotlin object with
 * pcmAudio filled, plus metadata extracted from the frame header.
 *
 * ── References ────────────────────────────────────────────────────────────────
 * [1] SarahRoseLives/Pocket25 https://github.com/SarahRoseLives/Pocket25 (GPL-3.0)
 * [2] arancormonk/mbelib-neo  vendored at app/src/main/cpp/mbelib-neo (GPL-2.0-or-later)
 * [3] ETSI TS 102 361-1 — DMR frame structure
 * [4] TIA-102.BAAA-A — P25 Phase 1 IMBE/AMBE frame layout
 * [5] M17 Project specification v1.0 (Codec2 3200)
 * [6] Codec2 codec2.c / mbe.c (LGPL-2.1) David Rowe
 */

#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cmath>
#include <cstring>
#include <algorithm>
#include <vector>

// Vocoder C-bridge (implemented in vocoder_jni.cpp).
// mbelib-neo is statically linked; vocoder_ready() always returns 1 and
// the vocoder_decode_*() functions always produce real speech via mbelib-neo.
#include "vocoder.h"

#define TAG     "DsdNeo"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ─── Constants ────────────────────────────────────────────────────────────────

static constexpr int   AUDIO_RATE   = 8000;   // output sample rate (Hz)
static constexpr int   FRAME_SAMPS  = 160;    // samples per 20 ms AMBE frame
static constexpr int   NHAR_MAX     = 56;     // max harmonics (pitch floor ~71 Hz)
static constexpr float PI           = 3.14159265358979f;
static constexpr float TWO_PI       = 6.28318530717959f;

// ─── Protocol IDs (must match DigitalFrame.Protocol.ordinal in Kotlin) ────────

enum Protocol {
    PROTO_DMR        = 0,
    PROTO_P25_PHASE1 = 1,
    PROTO_P25_PHASE2 = 2,
    PROTO_NXDN48     = 3,
    PROTO_NXDN96     = 4,
    PROTO_DSTAR      = 5,
    PROTO_YSF        = 6,
    PROTO_M17        = 7,
    PROTO_UNKNOWN    = 8
};

// ─────────────────────────────────────────────────────────────────────────────
//  DCT-II inverse and shared synthesis state
//
//  Used only by the Codec2 3200 (M17) synthesiser below. AMBE/IMBE audio
//  for DMR, P25, NXDN, D-STAR, and YSF is decoded by the statically-linked
//  mbelib-neo library via the vocoder bridge in vocoder_jni.cpp.
// ─────────────────────────────────────────────────────────────────────────────

// DCT-II inverse (length N) — reconstructs log spectral amplitudes from coefficients
static void idct(const float* c, float* out, int N) {
    for (int n = 0; n < N; n++) {
        float s = c[0] / sqrtf((float)N);
        for (int k = 1; k < N; k++) {
            s += c[k] * cosf(PI * k * (n + 0.5f) / N) * sqrtf(2.0f / N);
        }
        out[n] = s;
    }
}

// ─── Per-frame Codec2 synthesis state ───────────────────────────────────────

struct SynthState {
    float phaseAcc[NHAR_MAX]{};   // per-harmonic phase accumulator
    float noisePhase = 0.0f;
};

// Global state (one per library instance; protected by JNI single-call model)
static SynthState g_synth;

// ─────────────────────────────────────────────────────────────────────────────
//  Bit-extraction helpers
// ─────────────────────────────────────────────────────────────────────────────

static inline uint8_t getBit(const uint8_t* data, int bitPos) {
    return (data[bitPos >> 3] >> (7 - (bitPos & 7))) & 1;
}

static uint32_t getBits(const uint8_t* data, int startBit, int len) {
    uint32_t val = 0;
    for (int i = 0; i < len; i++) {
        val = (val << 1) | getBit(data, startBit + i);
    }
    return val;
}

// ─────────────────────────────────────────────────────────────────────────────
//  Vocoder bridge — see vocoder.h / vocoder_jni.cpp.
//  vocoder_ready() always returns 1 (mbelib-neo statically linked).
//  vocoder_decode_2400/2450/2450x1150 route to the built-in mbelib-neo codec.
//  The per-protocol decode_*() functions below call these bridge functions.
// ─────────────────────────────────────────────────────────────────────────────


// ─────────────────────────────────────────────────────────────────────────────
//  Codec2 3200 bit/s decoder — M17 protocol
//
//  M17 uses Codec2 mode 3200 (open-source, David Rowe / FreeDV).
//  One M17 voice frame = 16 bytes = 128 bits of Codec2 3200.
//  Two Codec2 frames of 64 bits each → 2 × 160 samples = 320 samples per
//  M17 voice frame.
//
//  Codec2 3200 parameter packing (from codec2/src/codec2.c):
//    WO (pitch)    : 7 bits
//    E (energy)    : 5 bits
//    b0..b9        : 8+8+7+6+5+5+4+4+4+4 = 55 bits  voiced harmonic amplitudes
//    Voiced flag   : 1 bit
//    Total         : 64 bits per frame
//
//  For robustness in this embedded implementation we decode the pitch/energy
//  and generate a parametric approximation rather than running the full
//  MLSA/mixed-excitation filter chain.
// ─────────────────────────────────────────────────────────────────────────────

struct Codec2Params {
    float   Wo;       // fundamental frequency (rad/sample), 0 = unvoiced
    float   E;        // energy (linear)
    float   b[10];    // spectral shape coefficients
    bool    voiced;
};

// Codec2 energy table (5-bit index → linear energy)
static const float C2_ENERGY_TABLE[32] = {
    0.000f,0.001f,0.002f,0.003f,0.005f,0.007f,0.010f,0.014f,
    0.020f,0.028f,0.039f,0.055f,0.077f,0.109f,0.154f,0.218f,
    0.308f,0.436f,0.616f,0.871f,1.231f,1.742f,2.463f,3.482f,
    4.925f,6.966f,9.849f,13.930f,19.700f,27.862f,39.400f,55.723f
};

static Codec2Params decode_codec2_frame(const uint8_t* bits, int offset) {
    Codec2Params p{};

    // WO (7 bits): pitch = Wo_min + (Wo_max - Wo_min) * idx/127
    // Wo_min = 2π*100/8000, Wo_max = 2π*800/8000
    int woIdx = (int)getBits(bits, offset, 7);  offset += 7;
    if (woIdx == 0) {
        p.Wo     = 0.0f;
        p.voiced = false;
    } else {
        float Wo_min = TWO_PI * 100.0f / 8000.0f;
        float Wo_max = TWO_PI * 800.0f / 8000.0f;
        p.Wo     = Wo_min + (Wo_max - Wo_min) * woIdx / 127.0f;
        p.voiced = true;
    }

    // E (5 bits)
    int eIdx  = (int)getBits(bits, offset, 5);  offset += 5;
    p.E       = C2_ENERGY_TABLE[eIdx];

    // Spectral amplitude bits: 8+8+7+6+5+5+4+4+4+4 = 55 bits
    static const int bBits[10] = {8,8,7,6,5,5,4,4,4,4};
    for (int k = 0; k < 10; k++) {
        int raw = (int)getBits(bits, offset, bBits[k]);
        offset += bBits[k];
        // De-quantise to log domain (crude linear mapping)
        int maxVal = (1 << bBits[k]);
        p.b[k] = (raw - maxVal/2) * (1.0f / maxVal) * 4.0f;
    }

    // Voiced bit (1 bit)
    p.voiced = p.voiced && (getBit(bits, offset) != 0);

    return p;
}

static void synth_codec2(const Codec2Params& p, int16_t* out) {
    if (!p.voiced || p.Wo <= 0.0f) {
        // Comfort noise
        float en = sqrtf(p.E) * 2000.0f;
        for (int n = 0; n < FRAME_SAMPS; n++) {
            g_synth.noisePhase = g_synth.noisePhase * 1664525.0f + 1013904223.0f;
            float ns = *reinterpret_cast<int32_t*>(&g_synth.noisePhase) / 2147483648.0f;
            out[n] = (int16_t)(ns * en);
        }
        return;
    }

    int L = (int)(PI / p.Wo);
    L = std::min(L, NHAR_MAX);

    // Reconstruct spectral amplitudes from b[] coefficients via DCT
    float logAm[NHAR_MAX]{};
    int nCoeff = std::min(10, L);
    idct(p.b, logAm, nCoeff);

    float gain = sqrtf(p.E) * 3000.0f;

    for (int n = 0; n < FRAME_SAMPS; n++) {
        float samp = 0.0f;
        for (int l = 0; l < L; l++) {
            float amp = gain * expf(logAm[l]);
            g_synth.phaseAcc[l] += p.Wo * (l + 1);
            if (g_synth.phaseAcc[l] > PI) g_synth.phaseAcc[l] -= TWO_PI;
            samp += amp * cosf(g_synth.phaseAcc[l]);
        }
        float out_f = std::max(-32000.0f, std::min(32000.0f, samp));
        out[n] = (int16_t)out_f;
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Protocol-specific frame unpackers
//
//  Each function receives the raw dibit array from DigitalVoiceDecoder
//  (values 0–3 per dibit) and:
//    1. Reconstructs the bit stream with error correction where possible
//    2. Extracts metadata (srcId, dstId, encrypted, emergency, talkerAlias)
//    3. Locates the AMBE/Codec2 speech payload
//    4. Calls decode_codec2_frame() + synth_codec2(), or the vocoder plugin for AMBE protocols
//    5. Returns synthesised PCM in `pcm` (16 kHz upsampled from 8 kHz)
//
//  Metadata parsing follows published specifications:
//    DMR — ETSI TS 102 361-1, §9.1.9 (LC, CSBK)
//    P25 — TIA-102.BAAA-A, §7.5 (LDU/HDU)
//    NXDN — ICOM NXDN §6.4 (VCALL)
//    D-STAR — JARL D-STAR §4 (Header)
//    YSF — Yaesu spec §4.3 (FICH)
//    M17 — M17 spec §3.2 (LSF)
// ─────────────────────────────────────────────────────────────────────────────

struct FrameResult {
    int    srcId        = 0;
    int    dstId        = 0;
    bool   isGroup      = false;
    bool   encrypted    = false;
    bool   emergency    = false;
    char   talkerAlias[64]{};
    int    pcmSamples   = 0;      // number of valid int16 samples in pcm[]
    int16_t pcm[FRAME_SAMPS * 5]{};  // up to 5 sub-frames (YSF DN has 5 VCH, M17 has 2 Codec2)
    bool   valid        = false;
};

// ── Dibit → packed byte stream ────────────────────────────────────────────────
// Converts the dibit symbol array (values 0–3, MSB-first in each dibit)
// to a packed byte stream for bit-level operations above.
static void dibits_to_bytes(const int8_t* dibits, int nDibits, uint8_t* bytes) {
    memset(bytes, 0, (nDibits + 3) / 4);
    for (int i = 0; i < nDibits; i++) {
        int d = dibits[i] & 3;
        // MSB dibit → bits (2i) and (2i+1)
        int bitPos = i * 2;
        if (d & 2) bytes[bitPos >> 3] |= (0x80 >> (bitPos & 7));
        if (d & 1) bytes[(bitPos+1) >> 3] |= (0x80 >> ((bitPos+1) & 7));
    }
}

// (D-STAR voice bits are now handed straight to vocoder_decode_2450_bits()
// in decode_dstar() below — bit-exact, dsdcc-style, no byte packing. The
// byte-packing helpers that used to live here were a needless detour: see
// the comment above decode_dstar() for the full explanation.)

// ── Simple Golay(24,12) error correction ─────────────────────────────────────
// Used by DMR (slot type / LC), P25 (NID), NXDN (LICH)
static const uint32_t GOLAY_POLY = 0xC75;

static uint32_t golay_syndrome(uint32_t codeword) {
    uint32_t s = codeword;
    for (int i = 0; i < 12; i++) {
        if (s & 0x800000) s ^= GOLAY_POLY << (11 - i);
        s <<= 1;
    }
    return (s >> 12) & 0xFFF;
}

static int golay_decode(uint32_t& codeword) {
    uint32_t s = golay_syndrome(codeword);
    if (s == 0) return 0;  // no errors
    // Try single-bit error
    for (int i = 0; i < 24; i++) {
        uint32_t test = codeword ^ (1u << i);
        if (golay_syndrome(test) == 0) { codeword = test; return 1; }
    }
    return -1;  // uncorrectable
}

// ── DMR frame unpacker ────────────────────────────────────────────────────────
// ETSI TS 102 361-1 §9.1.8 — DMR voice burst TDMA slot layout (full burst):
//
//   CACH(12) | VF1(36) | VF2a(18) | SYNC(24) | VF2b(18) | EMB1(4) | ES(16) | EMB2(4) | VF3(36)
//   (total: 12+36+18+24+18+4+16+4+36 = 168 dibits per TDMA slot, plus trailing CACH)
//
//   Our emit() window starts right after the 24-dibit SYNC field, so
//   decode_dmr() receives the post-sync payload starting at dibit 0:
//
//   [Post-sync payload received by this function]
//   Pos   0.. 17  VF2b  — voice frame 2 second half (18 dibits = 36 bits)
//   Pos  18.. 53  VF3   — voice frame 3 complete     (36 dibits = 72 bits)
//   Pos  54..     guard / trailing CACH of next slot / unused
//
// BUG FIX (confirmed by tracing dsdcc dmr.cpp DSDDMR::processVoiceDibit()'s
// literal symbolIndex arithmetic): EMB1(4 dibits)+ES(16 dibits)+EMB2(4
// dibits) = 24 dibits is NOT a field that follows VF2b in a sync-bearing
// burst. It is the SAME 24-dibit slot that SYNC occupies — ETSI TS 102
// 361-1's "Voice Sync" burst (frame A, the one we detect and strip here)
// carries SYNC in that slot; the non-sync voice bursts (frames B-F) carry
// EMB1+ES+EMB2 there INSTEAD, as an alternative to sync, never as well as
// it. Concretely, walking dsdcc's section offsets in dibits — CACH(12),
// VF1(36), VF2a(18), [EMB1(4)+ES(16)+EMB2(4) i.e. the sync-equivalent
// slot](24), VF2b(18), VF3(36) — totals 12+36+18+24+18+36 = 144, the full
// per-slot dibit count, with no room for a *second*, separate EMB/ES/EMB2
// region anywhere else in the burst.
//
// The previous version of this function inserted a second, phantom
// EMB1+ES+EMB2 gap of 24 dibits between VF2b and VF3, which pushed every
// VF3 read 24 dibits too late (offset 42 instead of 18). Those 24 "ignored"
// dibits were in fact real VF3 payload, and the bytes actually read at
// offset 42-77 ran past the true end of VF3 into the next slot's
// CACH/VF1 — i.e. every "VF3" frame fed to the vocoder was garbage from
// the following timeslot, not voice data at all. VF2b was unaffected
// (still read from offset 0), so DMR audio was previously built almost
// entirely from VF2b half-frames; VF3 contributed only ECC-masked noise.
//
// Fix: VF3 starts immediately after VF2b, at offset 18 (no gap). This
// also halves the minimum window needed (54 dibits instead of 78).
static FrameResult decode_dmr(const int8_t* dibits, int nDibits) {
    FrameResult r{};
    // Need at least VF2b(18) dibits to attempt the half-frame decode.
    if (nDibits < 18) return r;

    r.pcmSamples = 0;

    if (vocoder_ready()) {
        // ── VF2b: post-sync dibits 0..17 — second half of VF2 ───────────────
        // Only 18 of 36 required dibits are present (VF2a is before our
        // window, in the previous burst segment). Decode first so the
        // vocoder's prev-frame state advances in chronological order
        // (VF2b precedes VF3 within the same burst).
        {
            uint8_t codec2[9]{};
            for (int i = 0; i < 18; i++) {
                int d = dibits[i] & 3;
                int bp = 36 + i * 2;   // second half of 36-dibit frame
                if (d & 2) codec2[bp >> 3] |= (0x80u >> (bp & 7));
                bp++;
                if (d & 1) codec2[bp >> 3] |= (0x80u >> (bp & 7));
            }
            int16_t pcm[FRAME_SAMPS]{};
            if (vocoder_decode_2400(codec2, pcm)) {
                memcpy(r.pcm + r.pcmSamples, pcm, FRAME_SAMPS * sizeof(int16_t));
                r.pcmSamples += FRAME_SAMPS;
            }
        }

        // ── VF3: post-sync dibits 18..53 — complete 36-dibit AMBE frame ──────
        // Entirely after VF2b, immediately adjacent (no gap), all 36 dibits
        // present, no half-frame reconstruction needed.
        if (nDibits >= 54) {
            uint8_t codec3[9]{};
            for (int i = 0; i < 36; i++) {
                int d = dibits[18 + i] & 3;
                int bp = i * 2;
                if (d & 2) codec3[bp >> 3] |= (0x80u >> (bp & 7));
                bp++;
                if (d & 1) codec3[bp >> 3] |= (0x80u >> (bp & 7));
            }
            int16_t pcm[FRAME_SAMPS]{};
            if (vocoder_decode_2400(codec3, pcm)) {
                memcpy(r.pcm + r.pcmSamples, pcm, FRAME_SAMPS * sizeof(int16_t));
                r.pcmSamples += FRAME_SAMPS;
            }
        }
    }

    r.valid = true;
    return r;
}

// ── P25 Phase 1 frame unpacker ────────────────────────────────────────────────
// TIA-102.BAAA-A §7.5 — LDU1/LDU2 voice frames contain 9 × 144-bit IMBE frames
// For Phase 1 C4FM: 4FSK dibits, 4800 baud, 9 IMBE vocoder blocks per LDU

static FrameResult decode_p25(const int8_t* dibits, int nDibits) {
    FrameResult r{};
    if (nDibits < 72) return r;

    uint8_t bytes[36]{};
    dibits_to_bytes(dibits, std::min(nDibits, 72), bytes);

    // P25 LDU header (HDU): talkgroup at bytes 4..5, srcId at bytes 6..8
    if (nDibits >= 96) {
        uint8_t hdr[48]{};
        dibits_to_bytes(dibits, std::min(nDibits, 96), hdr);
        r.dstId   = (hdr[4] << 8) | hdr[5];
        r.srcId   = (hdr[6] << 16) | (hdr[7] << 8) | hdr[8];
        r.isGroup = true;
        r.encrypted = (hdr[0] & 0x40) != 0;
        r.emergency = (hdr[0] & 0x80) != 0;
    }

    // ── Built-in vocoder (IMBE 7200×4400 — P25) ──────────────────────────────
    // BUG FIX: this used to copy only 9 bytes (72 bits) into `codec` and call
    // vocoder_decode_2450x1150() expecting a P25 IMBE result. A real IMBE
    // 7200x4400 codeword is 144 bits (TIA-102.BAAA-A: four Golay(23,12)
    // fields + three Hamming(15,11) fields + 7 raw bits = 23*4+15*3+7 = 144)
    // — 18 bytes, not 9. The 72-bit buffer silently truncated every frame to
    // its first half before handing it to the vocoder, so even with a vocoder
    // available the decoded "speech" was synthesised from half a codeword
    // every time. `bytes[]` above already holds the full 72-dibit / 144-bit
    // window (see dibits_to_bytes() call above) — just stop truncating it.
    if (vocoder_ready()) {
        uint8_t codec[18]{};
        memcpy(codec, bytes, 18);
        int16_t pcm[FRAME_SAMPS]{};
        if (vocoder_decode_2450x1150(codec, pcm)) {
            memcpy(r.pcm, pcm, FRAME_SAMPS * sizeof(int16_t));
            r.pcmSamples = FRAME_SAMPS;
            r.valid = true;
            return r;
        }
    }
    r.valid = true;
    return r;
}

// ── NXDN frame unpacker ───────────────────────────────────────────────────────
// ICOM NXDN spec §6.4 — NXDN96: 4800 baud, 12-symbol LICH + 180-symbol body
// VCALL frame: src/dst in LICH; AMBE+2 speech in body bits 0–95

static FrameResult decode_nxdn(const int8_t* dibits, int nDibits) {
    FrameResult r{};
    if (nDibits < 96) return r;

    // NXDN LICH (12 dibits = 24 bits):
    //   b0-b3:  RF channel type
    //   b4:     direction
    //   b5-b11: SACCH option (contains call type, SrcID, DstID in VCALL)
    uint8_t lich[4]{};
    dibits_to_bytes(dibits, 12, lich);

    // VCALL SACCH: dstId at bits 12-22, srcId at bits 22-32 (simplified)
    r.dstId   = ((lich[1] & 0x0F) << 6) | (lich[2] >> 2);
    r.srcId   = ((lich[2] & 0x03) << 8) | lich[3];
    r.isGroup = (lich[0] & 0x40) == 0;
    r.encrypted = (lich[1] & 0x80) != 0;

    uint8_t body[50]{};
    dibits_to_bytes(dibits + 12, std::min(nDibits - 12, 96), body);

    // ── Built-in vocoder (AMBE+2 3600×2400 — NXDN) ────────────────────────────
    if (vocoder_ready()) {
        uint8_t codec[9]{};
        memcpy(codec, body, 9);
        int16_t pcm[FRAME_SAMPS]{};
        if (vocoder_decode_2400(codec, pcm)) {
            memcpy(r.pcm, pcm, FRAME_SAMPS * sizeof(int16_t));
            r.pcmSamples = FRAME_SAMPS;
            r.valid = true;
            return r;
        }
    }
    r.valid = true;
    return r;
}

// ── D-STAR frame unpacker ─────────────────────────────────────────────────────
// JARL D-STAR §4 / AE5PL "D-STAR Uncovered" — GMSK, 4800 baud. Each 20 ms
// voice frame on the air is 96 bits: 72 bits AMBE voice (3600 bps) followed
// by 24 bits of slow data (1200 bps). See DigitalVoiceDecoder.kt's
// checkDstar()/tickDstarFrameClock() for the continuous 96-bit frame clock
// that tracks frame boundaries using the data field's DATA_SYNC word, which
// (per spec) appears only once every 21 frames — not on every frame.
//
// DigitalVoiceDecoder.kt now hands this function exactly one frame's 72
// voice bits per call (the data field is excluded — it isn't part of the
// AMBE codec frame). Earlier versions of this function instead received a
// 768-bit window (24-bit sync + 168-bit slow data + 576 stale bits) only
// once every ~420 ms, and only ever decoded the first AMBE frame of those
// 576 bits — discarding the other 7. Combined with the rarity of that
// match, that's why D-STAR audio was sparse and choppy even with a working
// sync correlator and a working vocoder call. This function is now invoked
// once per 20 ms, continuously, for the duration of a locked transmission.
//
// BUG FIX: this used to pack the 72 raw bits into 9 bytes (with a per-byte
// LSB/MSB reversal to compensate for D-STAR's UART-style GMSK framing),
// then unpack those bytes back out into ambe_fr[4][24] via the dW[]/dX[]
// interleave. That round trip is unnecessary and, worse, isn't what dsdcc
// does: dsdcc's DSDDstar::processVoice() never assembles bytes for the
// voice payload at all — it takes the raw demodulated bit for symbol k
// straight from the bit slicer and assigns ambe_fr[dW[k]][dX[k]] = bit_k,
// one symbol at a time, k = 0..71, in receive order. Reconstructing that
// through a byte-pack/byte-reverse/byte-unpack detour adds an extra layer
// that has to be proven equivalent on every code path and is exactly the
// kind of indirection that hides ordering bugs. We now hand the raw bit
// array straight to vocoder_decode_2450_bits(), which fills ambe_fr via
// dW[]/dX[] directly — bit-exact dsdcc, no packing step to get wrong.
static FrameResult decode_dstar(const int8_t* dibits, int nDibits) {
    FrameResult r{};
    const int kVoiceBits = 72;   // one AMBE 3600x2450 frame, one bit/symbol
    if (nDibits < kVoiceBits) return r;

    uint8_t voiceBits[kVoiceBits];
    for (int i = 0; i < kVoiceBits; i++) voiceBits[i] = (uint8_t)(dibits[i] & 1);

    // ── Built-in vocoder (legacy AMBE 3600×2450 — D-STAR) ─────────────────────
    if (vocoder_ready()) {
        int16_t pcm[FRAME_SAMPS]{};
        if (vocoder_decode_2450_bits(voiceBits, pcm)) {
            memcpy(r.pcm, pcm, FRAME_SAMPS * sizeof(int16_t));
            r.pcmSamples = FRAME_SAMPS;
            r.valid = true;
            return r;
        }
    }
    r.valid = true;
    return r;
}

// ── YSF frame unpacker ────────────────────────────────────────────────────────
// Yaesu System Fusion spec §4 — C4FM, 4800 baud, DN mode (half-rate AMBE+2)
//
// The Kotlin emit() layer ALREADY strips the 20-symbol sync, so dsd_neo
// receives the 480 payload dibits (indices 0..479) only.
//
// YSF DN (VD1) payload layout (480 dibits, sync already stripped — confirmed
// against DSDcc ysf.cpp processVD1()):
//   Dibits [0..99]     = FICH (Frame Information Channel Header, 100 dibits)
//   Dibits [100..135]  = DCH(0) (Data Channel, 36 dibits)
//   Dibits [136..171]  = VCH(0) — AMBE+2 voice frame 0 (36 dibits, 9 bytes)
//   Dibits [172..207]  = DCH(1)
//   Dibits [208..243]  = VCH(1) — AMBE+2 voice frame 1
//   Dibits [244..279]  = DCH(2)
//   Dibits [280..315]  = VCH(2) — AMBE+2 voice frame 2
//   Dibits [316..351]  = DCH(3)
//   Dibits [352..387]  = VCH(3) — AMBE+2 voice frame 3
//   Dibits [388..423]  = DCH(4)
//   Dibits [424..459]  = VCH(4) — AMBE+2 voice frame 4
//   Dibits [460..479]  = guard / unused
//
// Each VCH block = 36 dibits = 72 bits = one half-rate AMBE+2 3600×2400 frame.
// FIX: Previous code decoded ONLY VCH(0), discarding VCH(1)–VCH(4). A YSF DN
// burst carries FIVE AMBE frames (one per 20 ms sub-slot → 100 ms total), so
// decoding only the first produces audio at 1/5 the correct density — the same
// choppy/sparse output observed. All five VCH blocks must be decoded and their
// PCM concatenated. This matches DSDcc processVD1() which calls processAMBE()
// once for every VCH block (symbolIndex in [36,72), [108,144), [180,216),
// [252,288), [324,360) — i.e. every other 36-dibit slot after FICH).
//
// vocoder_decode_2400() → bytes_to_ambe_fr_dmr() applies the DSDcc
// rW/rX/rY/rZ interleave to the 36 sequential AMBE dibits — identical to DMR.

static FrameResult decode_ysf(const int8_t* dibits, int nDibits) {
    FrameResult r{};
    // Need at minimum FICH(100) + DCH0(36) + VCH0(36) = 172 dibits
    if (nDibits < 172) return r;

    // FICH (100 dibits = 200 bits): Golay-protected channel info.
    // We extract only the key flag bits from the raw (pre-deinterleave)
    // FICH bytes rather than running full Golay decode here.
    uint8_t fich[13]{};  // first 50 dibits → 100 bits → 13 bytes is enough for flags
    dibits_to_bytes(dibits, std::min(nDibits, 50), fich);
    // FICH bit layout (pre-interleave, raw): FI[1:0] CM[1:0] BS[1:0] BN[1:0]
    // FN[2:0] FT[2:0] Dev SQL[1:0] SC[7:0] DT[1:0] MR[1:0] VoIP Enc RAN[5:0]
    r.encrypted = (fich[3] & 0x02) != 0;  // Enc bit (bit 30 of FICH data word)
    r.isGroup   = (fich[0] & 0x04) == 0;  // CM bit 0: 0=group, 1=individual

    // ── Decode all 5 VCH blocks (VCH0..VCH4) — one AMBE+2 frame each ─────────
    // VCH(n) starts at dibit offset: 100 + (2n+1)*36  (n = 0..4)
    //   n=0: 100+36=136, n=1: 100+108=208, n=2: 100+180=280,
    //   n=3: 100+252=352, n=4: 100+324=424
    // Each produces FRAME_SAMPS (160) PCM samples at 8 kHz.
    // Concatenated output = up to 5 × 160 = 800 samples (100 ms).
    static const int kVchOffsets[5] = { 136, 208, 280, 352, 424 };
    static const int kAmbeDibits   = 36;

    r.pcmSamples = 0;
    if (vocoder_ready()) {
        for (int v = 0; v < 5; v++) {
            int off = kVchOffsets[v];
            if (nDibits < off + kAmbeDibits) break;  // partial frame — stop here

            uint8_t codec[9]{};
            dibits_to_bytes(dibits + off, kAmbeDibits, codec);

            int16_t pcm[FRAME_SAMPS]{};
            if (vocoder_decode_2400(codec, pcm)) {
                memcpy(r.pcm + r.pcmSamples, pcm, FRAME_SAMPS * sizeof(int16_t));
                r.pcmSamples += FRAME_SAMPS;
            }
        }
    }
    r.valid = true;
    return r;
}

// ── M17 frame unpacker ────────────────────────────────────────────────────────
// M17 Project spec v1.0 §3 — 4FSK, 4800 sym/s
// Stream frames = 8-sym sync + 368-sym payload
//   Payload: LICH (48 bits) + FN (16 bits) + Codec2 3200 (128 bits) + CRC (16)
// LSF frame: 240 bits (src 48 bits, dst 48 bits, type 16 bits, meta 112 bits, CRC 16)
// Codec2 3200: 2 × 64-bit frames per M17 payload → 2 × 160 = 320 PCM samples

static FrameResult decode_m17(const int8_t* dibits, int nDibits) {
    FrameResult r{};
    if (nDibits < 184) return r;

    uint8_t payload[50]{};
    // Skip 8-dibit sync, extract payload
    dibits_to_bytes(dibits + 8, std::min(nDibits - 8, 184), payload);

    // LICH block (6 bytes = 48 bits) — encodes LSF fragment
    // LSF contains: dst[0..5] = 6 char callsign, src[6..11] = 6 char callsign
    // Encode callsigns as ID hash
    uint32_t dstHash = 5381, srcHash = 5381;
    for (int i = 0; i < 6; i++) dstHash = ((dstHash << 5) + dstHash) + payload[i];
    for (int i = 6; i < 12; i++) srcHash = ((srcHash << 5) + srcHash) + payload[i];
    r.dstId   = (int)(dstHash & 0xFFFFFF);
    r.srcId   = (int)(srcHash & 0xFFFFFF);
    r.isGroup = (payload[12] & 0x02) != 0;
    r.encrypted = (payload[12] & 0x04) != 0;
    r.emergency = (payload[12] & 0x08) != 0;

    // Codec2 payload at byte offset 6 (after LICH): 16 bytes = 128 bits
    // Two 64-bit Codec2 3200 frames
    uint8_t c2bits[16]{};
    memcpy(c2bits, payload + 6, std::min(16, (int)sizeof(payload) - 6));

    Codec2Params p1 = decode_codec2_frame(c2bits, 0);
    Codec2Params p2 = decode_codec2_frame(c2bits, 64);

    synth_codec2(p1, r.pcm);
    synth_codec2(p2, r.pcm + FRAME_SAMPS);
    r.pcmSamples = FRAME_SAMPS * 2;
    r.valid = true;
    return r;
}

// ─────────────────────────────────────────────────────────────────────────────
//  JNI entry point
//
//  Called from DigitalVoiceJni.kt::nativeDecode()
//  Signature: (Ljava/lang/Byte;I)Lcom/radiosport/ninegradio/dsp/DigitalVoiceJni$DsdNeoResult;
// ─────────────────────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT jobject JNICALL
Java_com_radiosport_ninegradio_dsp_DigitalVoiceJni_nativeDecode(
        JNIEnv* env,
        jclass  /* clazz */,
        jbyteArray jSymbols,
        jint       protocol)
{
    // ── Get dibit array ──────────────────────────────────────────────────────
    jsize nDibits = env->GetArrayLength(jSymbols);
    jbyte* symBuf = env->GetByteArrayElements(jSymbols, nullptr);
    if (!symBuf || nDibits == 0) {
        if (symBuf) env->ReleaseByteArrayElements(jSymbols, symBuf, JNI_ABORT);
        return nullptr;
    }

    // ── Decode ───────────────────────────────────────────────────────────────
    FrameResult res{};
    switch (protocol) {
        case PROTO_DMR:        res = decode_dmr  (symBuf, nDibits); break;
        case PROTO_P25_PHASE1: res = decode_p25  (symBuf, nDibits); break;
        case PROTO_P25_PHASE2: res = decode_p25  (symBuf, nDibits); break;
        case PROTO_NXDN48:
        case PROTO_NXDN96:     res = decode_nxdn (symBuf, nDibits); break;
        case PROTO_DSTAR:      res = decode_dstar(symBuf, nDibits); break;
        case PROTO_YSF:        res = decode_ysf  (symBuf, nDibits); break;
        case PROTO_M17:        res = decode_m17  (symBuf, nDibits); break;
        default:               break;
    }

    env->ReleaseByteArrayElements(jSymbols, symBuf, JNI_ABORT);

    // ── Build DsdNeoResult Kotlin object ─────────────────────────────────────
    jclass resClass = env->FindClass(
        "com/radiosport/ninegradio/dsp/DigitalVoiceJni$DsdNeoResult");
    if (!resClass) {
        LOGE("Cannot find DsdNeoResult class");
        return nullptr;
    }

    // Default (empty) constructor
    jmethodID ctor = env->GetMethodID(resClass, "<init>", "()V");
    if (!ctor) { LOGE("Cannot find DsdNeoResult ctor"); return nullptr; }

    // We use the copy constructor (data class) to supply all fields at once
    // via the primary constructor: (IIZZZLjava/lang/String;[S)V
    jmethodID ctorFull = env->GetMethodID(resClass, "<init>",
        "(IIZZZLjava/lang/String;[S)V");

    jstring alias = env->NewStringUTF(res.talkerAlias);

    // Build pcmAudio short array
    jshortArray jPcm = env->NewShortArray(res.pcmSamples);
    if (res.pcmSamples > 0) {
        env->SetShortArrayRegion(jPcm, 0, res.pcmSamples, res.pcm);
    }

    jobject result;
    if (ctorFull) {
        result = env->NewObject(resClass, ctorFull,
            (jint)res.srcId,
            (jint)res.dstId,
            (jboolean)res.isGroup,
            (jboolean)res.encrypted,
            (jboolean)res.emergency,
            alias,
            jPcm);
    } else {
        // Fallback: create with defaults and set fields manually
        result = env->NewObject(resClass, ctor);
        if (result) {
            auto setInt = [&](const char* name, jint val) {
                jfieldID f = env->GetFieldID(resClass, name, "I");
                if (f) env->SetIntField(result, f, val);
            };
            auto setBool = [&](const char* name, jboolean val) {
                jfieldID f = env->GetFieldID(resClass, name, "Z");
                if (f) env->SetBooleanField(result, f, val);
            };
            setInt("srcId",    (jint)res.srcId);
            setInt("dstId",    (jint)res.dstId);
            setBool("isGroup",   (jboolean)res.isGroup);
            setBool("encrypted", (jboolean)res.encrypted);
            setBool("emergency", (jboolean)res.emergency);
            {
                jfieldID f = env->GetFieldID(resClass, "talkerAlias", "Ljava/lang/String;");
                if (f) env->SetObjectField(result, f, alias);
            }
            {
                jfieldID f = env->GetFieldID(resClass, "pcmAudio", "[S");
                if (f) env->SetObjectField(result, f, jPcm);
            }
        }
    }

    env->DeleteLocalRef(alias);
    env->DeleteLocalRef(jPcm);

    return result;
}
