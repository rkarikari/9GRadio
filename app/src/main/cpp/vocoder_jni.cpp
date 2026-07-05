/**
 * vocoder_jni.cpp
 *
 * Built-in AMBE+2/AMBE/IMBE vocoder bridge using statically-linked mbelib-neo v2.x.
 *
 * STRATEGY (mirroring SDRAngel's approach):
 *   mbelib-neo is always present (statically linked into libdsd_neo.so via
 *   the mbelib_neo_obj CMake target). vocoder_ready() always returns 1.
 *   DMR, YSF, NXDN (AMBE+2 3600×2400), D-STAR (AMBE 3600×2450), and
 *   P25 (IMBE 7200×4400) all decode to real speech without any user action,
 *   download, or setup.
 *
 * mbelib-neo v2.x API changes (from v1.x / original szechyjs mbelib):
 *   - mbe_processAmbe3600x2400Frame() now returns int (status) and takes
 *     mbe_process_result* instead of int* errs / int* errs2 / char* err_str.
 *   - Same for mbe_processAmbe3600x2450Frame() and mbe_processImbe7200x4400Frame().
 *   - mbe_parms struct: removed int un / int repeat; added tonePhase (uint32_t),
 *     repeatCount, mutingThreshold, previousUw[256], noiseSeed, noiseOverlap[96].
 *   - ambe_fr / imbe_fr input arrays are now const char in decode/process functions.
 *   - New helper: mbe_initProcessResult(), mbe_formatProcessResult().
 *
 * mbelib-neo API (from mbelib-neo/include/mbelib-neo/mbelib.h):
 *   mbe_processAmbe3600x2400Frame()  — DMR / NXDN / YSF  (AMBE+2 2400)
 *   mbe_processAmbe3600x2450Frame()  — D-STAR             (AMBE  2450)
 *   mbe_processImbe7200x4400Frame()  — P25 Phase 1        (IMBE  4400)
 *   All return int (total errors) and take:
 *     (short* out, mbe_process_result* result, const char frame[][cols],
 *      char* ambe_d/imbe_d, mbe_parms* cur, mbe_parms* prev, mbe_parms* prev_enh)
 *
 * Input frame format expected by dsd_neo.cpp:
 *   codec[] = packed bytes, MSB-first, one bit per payload bit.
 *   We unpack them into mbelib-neo's 2D bitplane arrays here.
 *
 * References:
 *   [1] SDRAngel dsddemod.cpp — uses mbelib directly, same frame layout
 *   [2] mbelib-neo/include/mbelib-neo/mbelib.h — full v2.x API
 *   [3] DSD / dsd.h — AMBE frame bitplane layout (ambe_fr[4][24], imbe_fr[8][23])
 */

#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <cstdint>
#include <cmath>

#include "vocoder.h"
// mbelib-neo v2.x public header (statically linked via mbelib_neo_obj CMake target)
#include "mbelib-neo/mbelib.h"

#define TAG "VocoderJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// Per-protocol mbelib-neo state (one set per codec type, not per frame)
// mbe_parms carries phase/prediction state across consecutive frames —
// must be persistent, not stack-allocated per call.
// ---------------------------------------------------------------------------

// AMBE+2 3600×2400 (DMR / NXDN / YSF)
static mbe_parms g_ambe2400_cur{};
static mbe_parms g_ambe2400_prev{};
static mbe_parms g_ambe2400_prev_enh{};
static bool      g_ambe2400_init = false;

// AMBE 3600×2450 (D-STAR)
static mbe_parms g_ambe2450_cur{};
static mbe_parms g_ambe2450_prev{};
static mbe_parms g_ambe2450_prev_enh{};
static bool      g_ambe2450_init = false;

// IMBE 7200×4400 (P25 Phase 1)
static mbe_parms g_imbe4400_cur{};
static mbe_parms g_imbe4400_prev{};
static mbe_parms g_imbe4400_prev_enh{};
static bool      g_imbe4400_init = false;

static inline void ensure_ambe2400_init() {
    if (!g_ambe2400_init) {
        mbe_initMbeParms(&g_ambe2400_cur, &g_ambe2400_prev, &g_ambe2400_prev_enh);
        g_ambe2400_init = true;
    }
}
static inline void ensure_ambe2450_init() {
    if (!g_ambe2450_init) {
        mbe_initMbeParms(&g_ambe2450_cur, &g_ambe2450_prev, &g_ambe2450_prev_enh);
        g_ambe2450_init = true;
    }
}
static inline void ensure_imbe4400_init() {
    if (!g_imbe4400_init) {
        mbe_initMbeParms(&g_imbe4400_cur, &g_imbe4400_prev, &g_imbe4400_prev_enh);
        g_imbe4400_init = true;
    }
}

// ---------------------------------------------------------------------------
// Bit-unpacking helpers
//
// mbelib-neo expects frame bits in 2D char arrays (each element 0 or 1):
//   AMBE 3600x2400 / 3600x2450 : char ambe_fr[4][24]  (72 bits used)
//   IMBE 7200x4400              : char imbe_fr[8][23]  (144 bits used)
//
// Layout: row = codec class (C0..C3 or C0..C7), col = bit within class.
// mbelib reads: C0=fr[0][12..23], C1=fr[1][0..22], C2=fr[2][0..10],
//               C3=fr[3][0..13]  (AMBE); rows 0-6 for IMBE.
//
// PROTOCOL INTERLEAVE - DMR / NXDN / YSF:
//   DSDcc uses rW/rX/rY/rZ tables (36 entries) to map 36 on-air dibits
//   into ambe_fr[4][24]:
//     ambe_fr[rW[i]][rX[i]] = dibit[i] >> 1   (bit 1)
//     ambe_fr[rY[i]][rZ[i]] = dibit[i] & 1    (bit 0)
//   dsd_neo.cpp packs codec[] as raw sequential dibits, so we apply this
//   interleave here. WITHOUT it, the codec classes receive scrambled bits
//   and mbelib-neo produces silence or noise on every frame.
//
// D-STAR (AMBE 3600x2450):
//   dsd_neo.cpp applies bits_to_bytes_dstar() (LSB-first byte reversal).
//   After that, the 72 bits are placed using D-STAR's own dW[72]/dX[72]
//   interleave (DSDcc dstar.cpp DSDDstar::processVoice()):
//     ambe_fr[dW[k]][dX[k]] = bit k
//   The sequential column-major fill previously used was WRONG and produced
//   scrambled codec class assignments → silence on every D-STAR frame.
//
// P25 IMBE (7200x4400):
//   18 bytes packed sequentially. bit k -> imbe_fr[k&7][k>>3].
//   mbe_demodulateImbe7200x4400Data() handles its own internal FEC interleave.
//
// References:
//   DSDcc nxdn.cpp/dmr.cpp/ysf.cpp - rW/rX/rY/rZ tables + ambe_fr usage
//   DSDcc dstar.cpp - dW/dX interleave tables (DSDDstar::processVoice)
//   DSD / dsd.h - original mbelib column-major convention
// ---------------------------------------------------------------------------

// ---- DMR / NXDN / YSF interleave tables (DSDcc nxdn.cpp, identical in dmr.cpp/ysf.cpp) ----
static const int rW[36] = {
    0, 1, 0, 1, 0, 1,
    0, 1, 0, 1, 0, 1,
    0, 1, 0, 1, 0, 1,
    0, 1, 0, 1, 0, 2,
    0, 2, 0, 2, 0, 2,
    0, 2, 0, 2, 0, 2
};
static const int rX[36] = {
    23, 10, 22,  9, 21,  8,
    20,  7, 19,  6, 18,  5,
    17,  4, 16,  3, 15,  2,
    14,  1, 13,  0, 12, 10,
    11,  9, 10,  8,  9,  7,
     8,  6,  7,  5,  6,  4
};
static const int rY[36] = {
    0, 2, 0, 2, 0, 2,
    0, 2, 0, 3, 0, 3,
    1, 3, 1, 3, 1, 3,
    1, 3, 1, 3, 1, 3,
    1, 3, 1, 3, 1, 3,
    1, 3, 1, 3, 1, 3
};
static const int rZ[36] = {
     5,  3,  4,  2,  3,  1,
     2,  0,  1, 13,  0, 12,
    22, 11, 21, 10, 20,  9,
    19,  8, 18,  7, 17,  6,
    16,  5, 15,  4, 14,  3,
    13,  2, 12,  1, 11,  0
};

// Unpack 9 codec bytes (36 dibits, MSB-first) into ambe_fr[4][24] using the
// DMR/NXDN/YSF DSDcc interleave schedule.
// codec[]: 36 raw dibits packed sequentially (dibit[i] = bits 2i and 2i+1).
static void bytes_to_ambe_fr_dmr(const uint8_t* codec, char ambe_fr[4][24]) {
    memset(ambe_fr, 0, 4 * 24 * sizeof(char));
    for (int i = 0; i < 36; i++) {
        int bit1pos = i * 2;
        int bit0pos = i * 2 + 1;
        int bit1    = (codec[bit1pos >> 3] >> (7 - (bit1pos & 7))) & 1;
        int bit0    = (codec[bit0pos >> 3] >> (7 - (bit0pos & 7))) & 1;
        ambe_fr[rW[i]][rX[i]] = (char)bit1;
        ambe_fr[rY[i]][rZ[i]] = (char)bit0;
    }
}

// ---- D-STAR interleave tables (DSDcc dstar.cpp dW/dX, 72 entries) ----
// Each of the 72 voice bits is placed into ambe_fr[dW[i]][dX[i]].
// This interleave is unique to D-STAR and different from both the
// rW/rX/rY/rZ (DMR/NXDN/YSF) tables and a sequential column-major fill.
// Using sequential fill produces scrambled codec class assignments and
// therefore silence on every D-STAR frame.
static const int dW[72] = {
    0, 0, 3, 2, 1, 1, 0, 0, 1, 1, 0, 0,
    3, 2, 1, 1, 3, 2, 1, 1, 0, 0, 3, 2,
    0, 0, 3, 2, 1, 1, 0, 0, 1, 1, 0, 0,
    3, 2, 1, 1, 3, 2, 1, 1, 0, 0, 3, 2,
    0, 0, 3, 2, 1, 1, 0, 0, 1, 1, 0, 0,
    3, 2, 1, 1, 3, 3, 2, 1, 0, 0, 3, 3,
};
static const int dX[72] = {
    10, 22, 11,  9, 10, 22, 11, 23,  8, 20,  9, 21,
    10,  8,  9, 21,  8,  6,  7, 19,  8, 20,  9,  7,
     6, 18,  7,  5,  6, 18,  7, 19,  4, 16,  5, 17,
     6,  4,  5, 17,  4,  2,  3, 15,  4, 16,  5,  3,
     2, 14,  3,  1,  2, 14,  3, 15,  0, 12,  1, 13,
     2,  0,  1, 13,  0, 12, 10, 11,  0, 12,  1, 13,
};

// Unpack 9 codec bytes (72 bits, LSB-first-per-byte already reversed by
// bits_to_bytes_dstar()) into ambe_fr[4][24] using D-STAR's dW/dX interleave.
// bit k → ambe_fr[dW[k]][dX[k]]  (DSDcc dstar.cpp DSDDstar::processVoice())
static void bytes_to_ambe_fr_dstar(const uint8_t* codec, char ambe_fr[4][24]) {
    memset(ambe_fr, 0, 4 * 24 * sizeof(char));
    for (int k = 0; k < 72; k++) {
        int byteIdx = k >> 3;
        int bitIdx  = 7 - (k & 7);
        ambe_fr[dW[k]][dX[k]] = (char)((codec[byteIdx] >> bitIdx) & 1);
    }
}

// Direct per-bit fill — exactly what dsdcc's DSDDstar::processVoice() does:
//   ambe_fr[dW[k]][dX[k]] = bit_k   for k = 0..71, one symbol at a time.
// bits[]: 72 bytes, ONE BIT PER BYTE (0 or 1), in receive order. No byte
// packing/unpacking round trip at all — this is the bit-exact dsdcc path.
static void bits_to_ambe_fr_dstar_direct(const uint8_t* bits, char ambe_fr[4][24]) {
    memset(ambe_fr, 0, 4 * 24 * sizeof(char));
    for (int k = 0; k < 72; k++) {
        ambe_fr[dW[k]][dX[k]] = (char)(bits[k] & 1);
    }
}

// Unpack 18 codec bytes into imbe_fr[8][23] with sequential column-major order.
// Used for P25 Phase 1 IMBE 7200x4400 (144 bits).
// bit k -> imbe_fr[k & 7][k >> 3]
static void bytes_to_imbe_fr(const uint8_t* codec, char imbe_fr[8][23]) {
    memset(imbe_fr, 0, 8 * 23 * sizeof(char));
    for (int k = 0; k < 144; k++) {
        int byteIdx = k >> 3;
        int bitIdx  = 7 - (k & 7);
        imbe_fr[k & 7][k >> 3] = (char)((codec[byteIdx] >> bitIdx) & 1);
    }
}

// ---------------------------------------------------------------------------
// Built-in mbelib-neo v2.x decode functions
// ---------------------------------------------------------------------------
//
// mbelib-neo v2.x API:
//   mbe_processAmbe3600x2400Frame(short* out, mbe_process_result* result,
//       const char ambe_fr[4][24], char ambe_d[49],
//       mbe_parms* cur_mp, mbe_parms* prev_mp, mbe_parms* prev_mp_enhanced)
//   returns int (total errors), or negative on invalid input.
//
// mbe_process_result is an optional output status struct. Pass NULL to ignore
// detailed status; the return value (total errors) is always available.
//
// IMPORTANT: mbe_processAmbeXXXXFrame() / mbe_processImbeXXXXFrame() each
// internally call mbe_moveMbeParms(cur_mp, prev_mp) and
// mbe_moveMbeParms(cur_mp, prev_mp_enhanced) before returning.  Do NOT call
// mbe_moveMbeParms() again after the processFrame call — doing so overwrites
// prev_mp a second time with the already-advanced cur_mp value, discarding
// the properly-sequenced prev_mp_enhanced state that mbelib-neo maintains for
// spectral amplitude enhancement continuity across consecutive voice frames.
// (Bug: every frame sounded metallic / clipped because prev_mp_enhanced was
// reset to cur_mp on every frame rather than trailing one frame behind.)
//
// Verified against mbelib-neo/src/ambe/ambe3600x2400.c mbe_processAmbe2400Dataf:
//   bad==0, repeat<=3 path:
//     mbe_moveMbeParms(cur_mp, prev_mp);          ← advances prev
//     mbe_spectralAmpEnhance(cur_mp);
//     mbe_synthesizeSpeechf(…, cur_mp, prev_mp_enhanced, …);
//     mbe_moveMbeParms(cur_mp, prev_mp_enhanced); ← advances prev_enh
//   All other paths (error/repeat/silence) call mbe_initMbeParms or
//   mbe_moveMbeParms internally as well.  No external state management needed.

// Returns 1 on success. pcm must be 160 × int16_t.
static int mbe_decode_ambe2400(const uint8_t* codec9, int16_t* pcm) {
    ensure_ambe2400_init();
    char ambe_fr[4][24];
    char ambe_d[49]{};
    // DMR/NXDN/YSF: apply DSDcc rW/rX/rY/rZ interleave (not sequential).
    // The interleave maps 36 sequential on-air dibits into the 4×24 bit-plane
    // layout that mbelib-neo's ECC/demodulate/decode pipeline expects.
    bytes_to_ambe_fr_dmr(codec9, ambe_fr);

    // mbelib-neo v2.x: pass NULL for result to skip detailed status struct.
    // processAmbe3600x2400Frame calls mbe_moveMbeParms internally; do NOT
    // call it again here (see comment block above).
    int totalErrs = mbe_processAmbe3600x2400Frame(
        pcm, nullptr,
        const_cast<const char(*)[24]>(ambe_fr), ambe_d,
        &g_ambe2400_cur, &g_ambe2400_prev, &g_ambe2400_prev_enh);
    if (totalErrs > 0) {
        LOGI("AMBE2400 totalErrs=%d", totalErrs);
    }
    return 1;
}

static int mbe_decode_ambe2450(const uint8_t* codec9, int16_t* pcm) {
    ensure_ambe2450_init();
    char ambe_fr[4][24];
    char ambe_d[49]{};
    // D-STAR: bits_to_bytes_dstar() in dsd_neo already applied the per-byte
    // LSB→MSB bit reversal required by D-STAR's UART-style GMSK framing.
    // After that reversal, fill ambe_fr using D-STAR's dW[72]/dX[72] interleave
    // (DSDcc dstar.cpp DSDDstar::processVoice()) — NOT sequential column-major.
    bytes_to_ambe_fr_dstar(codec9, ambe_fr);

    // processAmbe3600x2450Frame calls mbe_moveMbeParms internally; do NOT
    // call it again here (see comment block above).
    int totalErrs = mbe_processAmbe3600x2450Frame(
        pcm, nullptr,
        const_cast<const char(*)[24]>(ambe_fr), ambe_d,
        &g_ambe2450_cur, &g_ambe2450_prev, &g_ambe2450_prev_enh);
    if (totalErrs > 0) {
        LOGI("AMBE2450 (D-STAR) totalErrs=%d", totalErrs);
    }
    return 1;
}

// Bit-exact dsdcc path: ambe_fr filled directly from the 72 raw bits with
// no byte packing/unpacking round trip at all (see bits_to_ambe_fr_dstar_direct()
// above). This is identical, bit for bit, to dsdcc's DSDDstar::processVoice().
static int mbe_decode_ambe2450_bits(const uint8_t* bits72, int16_t* pcm) {
    ensure_ambe2450_init();
    char ambe_fr[4][24];
    char ambe_d[49]{};
    bits_to_ambe_fr_dstar_direct(bits72, ambe_fr);

    int totalErrs = mbe_processAmbe3600x2450Frame(
        pcm, nullptr,
        const_cast<const char(*)[24]>(ambe_fr), ambe_d,
        &g_ambe2450_cur, &g_ambe2450_prev, &g_ambe2450_prev_enh);
    if (totalErrs > 0) {
        LOGI("AMBE2450bits (D-STAR) totalErrs=%d", totalErrs);
    }
    return 1;
}

static int mbe_decode_imbe7200x4400(const uint8_t* codec18, int16_t* pcm) {
    ensure_imbe4400_init();
    char imbe_fr[8][23];
    char imbe_d[88]{};
    bytes_to_imbe_fr(codec18, imbe_fr);

    // processImbe7200x4400Frame calls mbe_moveMbeParms internally; do NOT
    // call it again here (see comment block above).
    int totalErrs = mbe_processImbe7200x4400Frame(
        pcm, nullptr,
        const_cast<const char(*)[23]>(imbe_fr), imbe_d,
        &g_imbe4400_cur, &g_imbe4400_prev, &g_imbe4400_prev_enh);
    if (totalErrs > 0) {
        LOGI("IMBE7200x4400 (P25) totalErrs=%d", totalErrs);
    }
    return 1;
}

// ---------------------------------------------------------------------------
// C bridge — called from dsd_neo.cpp
// ---------------------------------------------------------------------------

extern "C" {

/** Always returns 1: mbelib-neo is statically linked and always available. */
int vocoder_ready() {
    return 1;
}

/** DMR / NXDN / YSF — AMBE+2 3600×2400 (9 bytes in, 160 × int16 out) */
int vocoder_decode_2400(uint8_t* codec, int16_t* pcm) {
    if (!codec || !pcm) return 0;
    return mbe_decode_ambe2400(codec, pcm);
}

/** P25 Phase 1 — IMBE 7200×4400 (18 bytes in, 160 × int16 out) */
int vocoder_decode_2450x1150(uint8_t* codec, int16_t* pcm) {
    if (!codec || !pcm) return 0;
    return mbe_decode_imbe7200x4400(codec, pcm);
}

/** D-STAR — AMBE 3600×2450 (9 bytes in, 160 × int16 out) */
int vocoder_decode_2450(uint8_t* codec, int16_t* pcm) {
    if (!codec || !pcm) return 0;
    return mbe_decode_ambe2450(codec, pcm);
}

/** D-STAR — AMBE 3600×2450, bit-exact dsdcc path (72 bytes in, one bit per
 *  byte, in receive order; 160 × int16 out). ambe_fr is filled directly via
 *  dW[]/dX[], bit for bit, exactly as dsdcc's DSDDstar::processVoice() does it. */
int vocoder_decode_2450_bits(const uint8_t* bits, int16_t* pcm) {
    if (!bits || !pcm) return 0;
    return mbe_decode_ambe2450_bits(bits, pcm);
}

} // extern "C"
