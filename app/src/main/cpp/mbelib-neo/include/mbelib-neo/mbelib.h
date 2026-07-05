// SPDX-License-Identifier: GPL-2.0-or-later
/*
 * Copyright (C) 2025 by arancormonk <180709949+arancormonk@users.noreply.github.com>
 */

/**
 * @file
 * @brief Public API for mbelib-neo vocoder primitives and helpers.
 *
 * This header exposes the installed `mbe_` API used to decode IMBE/AMBE frames
 * and synthesize 8 kHz PCM audio. The 2.x API uses result-returning processing
 * calls and `mbe_process_result` status reporting; it is ABI-breaking relative
 * to 1.x, while minor releases within a major version are intended to remain
 * ABI compatible.
 *
 * @note The `*f` (float) PCM APIs return samples in mbelib's historical float
 *       scale (roughly int16/7), not normalized `[-1, +1]`. Use
 *       mbe_floattoshort() to convert to `int16_t` PCM, or scale by
 *       `(7.0f / 32768.0f)` for normalized floats (approximately `[-0.95, +0.95]`
 *       after soft clipping).
 *
 * @note Process and decode APIs validate public hard and soft bit arrays
 *       strictly. Hard bits must be exactly 0 or 1, and soft bit `bit` fields
 *       must be exactly 0 or 1. Invalid arguments return
 *       `MBE_STATUS_INVALID_ARGUMENT`; invalid bit values return
 *       `MBE_STATUS_INVALID_BITS`.
 *
 * @note Threading: processing APIs are reentrant when each stream has its own
 *       `mbe_parms` state. `mbe_setThreadRngSeed()` affects only the calling
 *       thread's synthesis RNG state.
 */

#ifndef MBELIB_NEO_PUBLIC_MBEBELIB_H
#define MBELIB_NEO_PUBLIC_MBEBELIB_H

#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif

// Expose project version macro. In normal builds this header is
// generated into the build include dir; provide a fallback for linting.
#if defined(__has_include)
#if __has_include("mbelib-neo/version.h")
#include "mbelib-neo/version.h"
#endif
#endif
#ifndef MBELIB_VERSION
#define MBELIB_VERSION "0.0.0-dev"
#endif

#if !defined(MBE_API)
#if defined(_WIN32) || defined(__CYGWIN__)
/*
 * On Windows, exporting from the DLL uses dllexport when building the
 * shared library and dllimport when consuming it. For static libraries,
 * dllimport is incorrect and can cause unresolved externals; in that case
 * consumers should see an empty decoration. We propagate MBE_STATIC from
 * the CMake target for static consumption.
 */
#if defined(MBE_STATIC)
#define MBE_API
#elif defined(MBE_BUILDING)
#define MBE_API __declspec(dllexport)
#else
#define MBE_API __declspec(dllimport)
#endif
#else
#define MBE_API __attribute__((visibility("default")))
#endif
#endif

#if !defined(MBE_DEPRECATED)
#if defined(_MSC_VER)
#define MBE_DEPRECATED(msg) __declspec(deprecated(msg))
#elif defined(__GNUC__) || defined(__clang__)
#define MBE_DEPRECATED(msg) __attribute__((deprecated(msg)))
#else
#define MBE_DEPRECATED(msg)
#endif
#endif

#if !defined(MBE_DEPRECATED_FOR)
#define MBE_DEPRECATED_FOR(newsym) MBE_DEPRECATED("Use " #newsym)
#endif

struct mbe_parameters {
    /** Fundamental radian frequency (w0). */
    float w0;
    /** Number of harmonic bands (L). */
    int L;
    /** Number of voiced bands (K). */
    int K;
    /** Voiced/unvoiced flags per band (1..56). */
    int Vl[57];
    /** Magnitude per band (1..56). */
    float Ml[57];
    /** Base-2 log magnitude per band (1..56). */
    float log2Ml[57];
    /** Absolute phase per band (1..56). */
    float PHIl[57];
    /** Smoothed phase per band (1..56). */
    float PSIl[57];
    /** Spectral amplitude enhancement scale. */
    float gamma;
    /** Tone synthesis phase accumulator. */
    uint32_t tonePhase;
    /** Sine wave increment for tone synthesis. */
    int swn;

    /* === Adaptive smoothing state (Algorithms #111-116) === */
    /** Local energy tracking with IIR smoothing (Algorithm #111). */
    float localEnergy;
    /** Amplitude threshold for scaling (Algorithm #115). */
    int amplitudeThreshold;
    /** Bit error rate for current frame (0.0 to 1.0). */
    float errorRate;
    /** Total bit errors detected/corrected in this frame. */
    int errorCountTotal;
    /** Coset 4 error count (IMBE-specific). */
    int errorCount4;

    /* === Frame repeat/muting state === */
    /** Consecutive repeat count (0 to MAX_FRAME_REPEATS). */
    int repeatCount;
    /** Muting threshold for this codec (IMBE: 0.0875, AMBE: 0.096). */
    float mutingThreshold;

    /* === FFT-based unvoiced synthesis state === */
    /** Previous frame inverse FFT output for WOLA (256 samples). */
    float previousUw[256];
    /** LCG noise generator state (seed). */
    float noiseSeed;
    /** Noise buffer overlap for continuity (96 samples). */
    float noiseOverlap[96];
};

typedef struct mbe_parameters mbe_parms;

/**
 * @brief Soft-decision input bit.
 *
 * `bit` carries the caller's hard decision (0 or 1). `reliability` is the
 * confidence in that hard decision, where 0 means unknown/erasure-like and
 * 255 means highly reliable.
 */
typedef struct mbe_soft_bit {
    uint8_t bit;
    uint8_t reliability;
} mbe_soft_bit;

/** Processing/result flag: frame input used soft decisions. */
#define MBE_PROCESS_FLAG_SOFT_INPUT 0x0001u
/** Processing/result flag: C0 error count is available to the synthesis path. */
#define MBE_PROCESS_FLAG_C0_VALID   0x0002u
/** Processing/result flag: IMBE C4 error count is available. */
#define MBE_PROCESS_FLAG_C4_VALID   0x0004u
/** Processing/result flag: frame was classified as tone. */
#define MBE_PROCESS_FLAG_TONE       0x0010u
/** Processing/result flag: frame was classified as erasure. */
#define MBE_PROCESS_FLAG_ERASURE    0x0020u
/** Processing/result flag: previous parameters were repeated. */
#define MBE_PROCESS_FLAG_REPEAT     0x0040u
/** Processing/result flag: output was muted/comfort-noise substituted. */
#define MBE_PROCESS_FLAG_MUTE       0x0080u

/** Status code: invalid pointer, invalid status counters, or otherwise unusable arguments. */
#define MBE_STATUS_INVALID_ARGUMENT (-1)
/** Status code: hard or soft input bit arrays contained values other than 0 or 1. */
#define MBE_STATUS_INVALID_BITS     (-2)

/**
 * @brief Frame decode and synthesis status.
 *
 * Decode helpers initialize and populate this structure when a non-NULL
 * pointer is supplied. Synthesis calls consume available decode context,
 * update synthesis status flags, and return `total_errors`.
 */
typedef struct mbe_process_result {
    /** Corrected errors in the C0/protected header field, when `MBE_PROCESS_FLAG_C0_VALID` is set. */
    int c0_errors;
    /** Corrected errors in protected parameter fields, excluding `c0_errors`. */
    int protected_errors;
    /** Corrected IMBE C4/Hamming errors, when `MBE_PROCESS_FLAG_C4_VALID` is set. */
    int c4_errors;
    /** Total error count used for repeat/muting decisions and status formatting. */
    int total_errors;
    /** Bitwise OR of `MBE_PROCESS_FLAG_*` values. */
    unsigned flags;
} mbe_process_result;

/** @brief Reset a process result to all-zero/default values. */
MBE_API void mbe_initProcessResult(mbe_process_result* result);
/**
 * @brief Format a process result as a compact status trace.
 *
 * Writes `'='` repeated `result->total_errors` times, then any set `E`, `T`,
 * `R`, and `M` status flags in that order, followed by NUL. Output is
 * truncated to `size`.
 */
MBE_API void mbe_formatProcessResult(char* str, size_t size, const mbe_process_result* result);
/**
 * @brief Build one soft bit from a hard bit and reliability.
 * @param bit Hard decision; any non-zero value maps to 1.
 * @param reliability Confidence in the hard decision (`0` unknown/erasure-like, `255` highly reliable).
 */
MBE_API mbe_soft_bit mbe_softBitFromHard(int bit, uint8_t reliability);
/**
 * @brief Build one soft bit from a signed LLR.
 * @param llr Signed log-likelihood-like value; positive maps to bit 1, non-positive maps to bit 0.
 * @return Soft bit with reliability equal to `abs(llr)` clamped to `0..255`.
 */
MBE_API mbe_soft_bit mbe_softBitFromLlr(int16_t llr);
/**
 * @brief Convert hard 0/1 bits to soft bits with a fixed reliability.
 * @return 0 on success, or a negative `MBE_STATUS_*` code.
 */
MBE_API int mbe_softBitsFromHard(const char* bits, mbe_soft_bit* soft, size_t count, uint8_t reliability);
/**
 * @brief Convert signed LLRs to soft bits.
 * @return 0 on success, or a negative `MBE_STATUS_*` code.
 */
MBE_API int mbe_softBitsFromLlr(const int16_t* llr, mbe_soft_bit* soft, size_t count);

/**
 * @brief Correct a (23,12) Golay encoded block in-place and extract data.
 * @param block Pointer to packed 23-bit block (upper bits ignored). On return, contains 12-bit data.
 * @return 0 on success, or a negative `MBE_STATUS_*` code.
 */
MBE_API int mbe_checkGolayBlock(long int* block);
/**
 * @brief Decode a (23,12) Golay codeword.
 * @param in  Input bits, LSB at index 0, length 23.
 * @param out Output bits, corrected, LSB at index 0, length 23.
 * @return Number of corrected bit errors in the protected portion.
 */
MBE_API int mbe_golay2312(const char* in, char* out);
/**
 * @brief Soft-decision Golay(23,12) decode.
 * @param in  Input soft bits, LSB at index 0, length 23.
 * @param out Output hard bits, length 23.
 * @return Number of hard-decision data-bit differences between `in` and the selected codeword.
 * @note Matches mbe_golay2312 output semantics: data bits are corrected, parity bits preserve the hard input.
 */
MBE_API int mbe_golay2312Soft(const mbe_soft_bit* in, char* out);
/**
 * @brief Decode a (15,11) Hamming codeword (IMBE/AMBE common use).
 * @param in  Input bits, LSB at index 0, length 15.
 * @param out Output bits, corrected, LSB at index 0, length 15.
 * @return Number of corrected bit errors (0 or 1).
 */
MBE_API int mbe_hamming1511(const char* in, char* out);
/**
 * @brief Soft-decision Hamming(15,11) decode.
 * @param in  Input soft bits, LSB at index 0, length 15.
 * @param out Output corrected hard bits, LSB at index 0, length 15.
 * @return Number of hard-decision bit differences between `in` and the selected codeword.
 */
MBE_API int mbe_hamming1511Soft(const mbe_soft_bit* in, char* out);
/**
 * @brief Decode a (15,11) Hamming codeword with IMBE 7100x4400 mapping.
 * @param in  Input bits, LSB at index 0, length 15.
 * @param out Output bits, corrected, LSB at index 0, length 15.
 * @return Number of corrected bit errors (0 or 1).
 */
MBE_API int mbe_7100x4400hamming1511(const char* in, char* out);
/**
 * @brief Soft-decision Hamming(15,11) decode with IMBE 7100x4400 mapping.
 * @param in  Input soft bits, LSB at index 0, length 15.
 * @param out Output corrected hard bits, LSB at index 0, length 15.
 * @return Number of hard-decision bit differences between `in` and the selected codeword.
 */
MBE_API int mbe_7100x4400hamming1511Soft(const mbe_soft_bit* in, char* out);

/* Prototypes from ambe3600x2400.c */
/** @brief Print AMBE 2400 parameter bits to stderr (debug). */
MBE_API void mbe_dumpAmbe2400Data(const char* ambe_d);
/** @brief Print a raw AMBE 3600x2400 frame to stderr (debug). */
MBE_API void mbe_dumpAmbe3600x2400Frame(const char ambe_fr[4][24]);
/**
 * @brief Apply ECC to AMBE 3600x2400 C0 and update in-place.
 * @param ambe_fr AMBE frame as 4x24 bitplanes.
 * @return Number of corrected errors in C0.
 */
MBE_API int mbe_eccAmbe3600x2400C0(char ambe_fr[4][24]);
/**
 * @brief Apply ECC to AMBE 3600x2400 data and pack parameters.
 * @param ambe_fr AMBE frame as 4x24 bitplanes.
 * @param ambe_d  Output parameter bits (49).
 * @return Number of corrected errors in protected fields.
 */
MBE_API int mbe_eccAmbe3600x2400Data(char ambe_fr[4][24], char* ambe_d);
/**
 * @brief Decode AMBE 2400 parameters from demodulated bits.
 * @param ambe_d  Demodulated AMBE parameter bits (49).
 * @param cur_mp  Output: current frame parameters.
 * @param prev_mp Input: previous frame parameters (for prediction).
 * @return Tone index or 0 for voice; implementation-specific non-zero for tone frames.
 */
MBE_API int mbe_decodeAmbe2400Parms(const char* ambe_d, mbe_parms* cur_mp, mbe_parms* prev_mp);
/**
 * @brief Demodulate interleaved AMBE 3600x2400 data in-place.
 * @param ambe_fr AMBE frame as 4x24 bitplanes, updated in-place.
 * @return 0 on success, or a negative `MBE_STATUS_*` code.
 */
MBE_API int mbe_demodulateAmbe3600x2400Data(char ambe_fr[4][24]);
/**
 * @brief Decode a hard AMBE 3600x2400 frame to parameter bits without synthesis.
 * @param ambe_fr Input frame as 4x24 bitplanes; not modified.
 * @param ambe_d  Output parameter bits (49).
 * @param result  Optional output status; receives C0/protected/total errors and `MBE_PROCESS_FLAG_C0_VALID`.
 * @return Corrected error total (`c0_errors + protected_errors`).
 */
MBE_API int mbe_decodeAmbe3600x2400Frame(const char ambe_fr[4][24], char ambe_d[49], mbe_process_result* result);
/**
 * @brief Decode a soft AMBE 3600x2400 frame to parameter bits without synthesis.
 * @param ambe_fr Input soft frame as 4x24 bitplanes; not modified.
 * @param ambe_d  Output hard parameter bits (49).
 * @param result  Optional output status; also sets `MBE_PROCESS_FLAG_SOFT_INPUT`.
 * @return Corrected error total (`c0_errors + protected_errors`).
 */
MBE_API int mbe_decodeAmbe3600x2400SoftFrame(const mbe_soft_bit ambe_fr[4][24], char ambe_d[49],
                                             mbe_process_result* result);
/**
 * @brief Process AMBE 2400 parameters into 8 kHz float PCM.
 * @param aout_buf Output buffer of 160 float samples.
 * @param result   Optional in/out status context. C0 context is used when `MBE_PROCESS_FLAG_C0_VALID` is set.
 * @param ambe_d   Demodulated parameter bits (49).
 * @param cur_mp   In/out: current frame parameters (may be enhanced).
 * @param prev_mp  In/out: previous frame parameters.
 * @param prev_mp_enhanced In/out: enhanced previous parameters for continuity.
 * @return Total error count on success, or a negative `MBE_STATUS_*` code.
 */
MBE_API int mbe_processAmbe2400Dataf(float* aout_buf, mbe_process_result* result, const char ambe_d[49],
                                     mbe_parms* cur_mp, mbe_parms* prev_mp, mbe_parms* prev_mp_enhanced);
/**
 * @brief Process AMBE 2400 parameters into 8 kHz 16-bit PCM.
 * @see mbe_processAmbe2400Dataf for details.
 */
MBE_API int mbe_processAmbe2400Data(short* aout_buf, mbe_process_result* result, const char ambe_d[49],
                                    mbe_parms* cur_mp, mbe_parms* prev_mp, mbe_parms* prev_mp_enhanced);
/**
 * @brief Process a complete AMBE 3600x2400 frame into 8 kHz float PCM.
 * @param aout_buf Output buffer of 160 float samples.
 * @param result   Optional output status populated by decode and synthesis.
 * @param ambe_fr  Input frame as 4x24 bitplanes.
 * @param ambe_d   Scratch/output parameter bits (49).
 * @param cur_mp,prev_mp,prev_mp_enhanced Parameter state as per Dataf variant.
 * @return Total error count on success, or a negative `MBE_STATUS_*` code.
 */
MBE_API int mbe_processAmbe3600x2400Framef(float* aout_buf, mbe_process_result* result, const char ambe_fr[4][24],
                                           char ambe_d[49], mbe_parms* cur_mp, mbe_parms* prev_mp,
                                           mbe_parms* prev_mp_enhanced);
/**
 * @brief Process a complete AMBE 3600x2400 frame into 8 kHz 16-bit PCM.
 * @see mbe_processAmbe3600x2400Framef for details.
 */
MBE_API int mbe_processAmbe3600x2400Frame(short* aout_buf, mbe_process_result* result, const char ambe_fr[4][24],
                                          char ambe_d[49], mbe_parms* cur_mp, mbe_parms* prev_mp,
                                          mbe_parms* prev_mp_enhanced);
/**
 * @brief Process a soft AMBE 3600x2400 frame into float PCM.
 * @param result Optional output status populated by decode and synthesis.
 * @return Final total error count used by the wrapper.
 */
MBE_API int mbe_processAmbe3600x2400SoftFramef(float* aout_buf, mbe_process_result* result,
                                               const mbe_soft_bit ambe_fr[4][24], char ambe_d[49], mbe_parms* cur_mp,
                                               mbe_parms* prev_mp, mbe_parms* prev_mp_enhanced);
/** @brief Process a soft AMBE 3600x2400 frame into int16 PCM; see float variant for result semantics. */
MBE_API int mbe_processAmbe3600x2400SoftFrame(short* aout_buf, mbe_process_result* result,
                                              const mbe_soft_bit ambe_fr[4][24], char ambe_d[49], mbe_parms* cur_mp,
                                              mbe_parms* prev_mp, mbe_parms* prev_mp_enhanced);

/* Prototypes from ambe3600x2450.c */
/** @brief Print AMBE 2450 parameter bits to stderr (debug). */
MBE_API void mbe_dumpAmbe2450Data(const char* ambe_d);
/** @brief Print a raw AMBE 3600x2450 frame to stderr (debug). */
MBE_API void mbe_dumpAmbe3600x2450Frame(const char ambe_fr[4][24]);
/** @brief ECC correction for AMBE 3600x2450 C0. */
MBE_API int mbe_eccAmbe3600x2450C0(char ambe_fr[4][24]);
/** @brief ECC and parameter packing for AMBE 3600x2450. */
MBE_API int mbe_eccAmbe3600x2450Data(char ambe_fr[4][24], char* ambe_d);
/** @brief Decode AMBE 2450 parameters. */
MBE_API int mbe_decodeAmbe2450Parms(const char* ambe_d, mbe_parms* cur_mp, mbe_parms* prev_mp);
/** @brief Demodulate AMBE 3600x2450 interleaved data. */
MBE_API int mbe_demodulateAmbe3600x2450Data(char ambe_fr[4][24]);
/**
 * @brief Decode a hard AMBE 3600x2450 frame to parameter bits without synthesis.
 * @param ambe_fr Input frame as 4x24 bitplanes; not modified.
 * @param ambe_d  Output parameter bits (49).
 * @param result  Optional output status; receives C0/protected/total errors and `MBE_PROCESS_FLAG_C0_VALID`.
 * @return Corrected error total (`c0_errors + protected_errors`).
 */
MBE_API int mbe_decodeAmbe3600x2450Frame(const char ambe_fr[4][24], char ambe_d[49], mbe_process_result* result);
/**
 * @brief Decode a soft AMBE 3600x2450 frame to parameter bits without synthesis.
 * @param ambe_fr Input soft frame as 4x24 bitplanes; not modified.
 * @param ambe_d  Output hard parameter bits (49).
 * @param result  Optional output status; also sets `MBE_PROCESS_FLAG_SOFT_INPUT`.
 * @return Corrected error total (`c0_errors + protected_errors`).
 */
MBE_API int mbe_decodeAmbe3600x2450SoftFrame(const mbe_soft_bit ambe_fr[4][24], char ambe_d[49],
                                             mbe_process_result* result);
/**
 * @brief Process AMBE 2450 parameters into float PCM.
 * @param aout_buf Output buffer of 160 float samples.
 * @param result   Optional in/out status context. C0 context is used when `MBE_PROCESS_FLAG_C0_VALID` is set.
 * @param ambe_d   Demodulated parameter bits (49).
 * @param cur_mp   In/out: current frame parameters (may be enhanced).
 * @param prev_mp  In/out: previous frame parameters.
 * @param prev_mp_enhanced In/out: enhanced previous parameters for continuity.
 * @return Total error count on success, or a negative `MBE_STATUS_*` code.
 */
MBE_API int mbe_processAmbe2450Dataf(float* aout_buf, mbe_process_result* result, const char ambe_d[49],
                                     mbe_parms* cur_mp, mbe_parms* prev_mp, mbe_parms* prev_mp_enhanced);
/** @brief Process AMBE 2450 parameters into 16-bit PCM. */
MBE_API int mbe_processAmbe2450Data(short* aout_buf, mbe_process_result* result, const char ambe_d[49],
                                    mbe_parms* cur_mp, mbe_parms* prev_mp, mbe_parms* prev_mp_enhanced);
/**
 * @brief Process AMBE 3600x2450 frame into float PCM.
 * @param aout_buf Output buffer of 160 float samples.
 * @param result   Optional output status populated by decode and synthesis.
 * @param ambe_fr  Input frame as 4x24 bitplanes.
 * @param ambe_d   Scratch/output parameter bits (49).
 * @param cur_mp,prev_mp,prev_mp_enhanced Parameter state as per Dataf variant.
 * @return Total error count on success, or a negative `MBE_STATUS_*` code.
 */
MBE_API int mbe_processAmbe3600x2450Framef(float* aout_buf, mbe_process_result* result, const char ambe_fr[4][24],
                                           char ambe_d[49], mbe_parms* cur_mp, mbe_parms* prev_mp,
                                           mbe_parms* prev_mp_enhanced);
/** @brief Process AMBE 3600x2450 frame into 16-bit PCM. */
MBE_API int mbe_processAmbe3600x2450Frame(short* aout_buf, mbe_process_result* result, const char ambe_fr[4][24],
                                          char ambe_d[49], mbe_parms* cur_mp, mbe_parms* prev_mp,
                                          mbe_parms* prev_mp_enhanced);
/**
 * @brief Process a soft AMBE 3600x2450 frame into float PCM.
 * @param result Optional output status populated by decode and synthesis.
 * @return Final total error count used by the wrapper.
 */
MBE_API int mbe_processAmbe3600x2450SoftFramef(float* aout_buf, mbe_process_result* result,
                                               const mbe_soft_bit ambe_fr[4][24], char ambe_d[49], mbe_parms* cur_mp,
                                               mbe_parms* prev_mp, mbe_parms* prev_mp_enhanced);
/** @brief Process a soft AMBE 3600x2450 frame into int16 PCM; see float variant for result semantics. */
MBE_API int mbe_processAmbe3600x2450SoftFrame(short* aout_buf, mbe_process_result* result,
                                              const mbe_soft_bit ambe_fr[4][24], char ambe_d[49], mbe_parms* cur_mp,
                                              mbe_parms* prev_mp, mbe_parms* prev_mp_enhanced);

/* Prototypes from imbe7200x4400.c */
/** @brief Print IMBE 4400 parameter bits to stderr (debug). */
MBE_API void mbe_dumpImbe4400Data(const char* imbe_d);
/** @brief Print IMBE 7200x4400 parameter bits to stderr (debug). */
MBE_API void mbe_dumpImbe7200x4400Data(const char* imbe_d);
/** @brief Print a raw IMBE 7200x4400 frame to stderr (debug). */
MBE_API void mbe_dumpImbe7200x4400Frame(const char imbe_fr[8][23]);
/** @brief ECC correction for IMBE 7200x4400 C0. */
MBE_API int mbe_eccImbe7200x4400C0(char imbe_fr[8][23]);
/** @brief ECC and parameter packing for IMBE 7200x4400. */
MBE_API int mbe_eccImbe7200x4400Data(char imbe_fr[8][23], char* imbe_d);
/** @brief Decode IMBE 4400 parameters. */
MBE_API int mbe_decodeImbe4400Parms(const char* imbe_d, mbe_parms* cur_mp, mbe_parms* prev_mp);
/** @brief Demodulate IMBE 7200x4400 interleaved data. */
MBE_API int mbe_demodulateImbe7200x4400Data(char imbe[8][23]);
/**
 * @brief Decode a hard IMBE 7200x4400 frame to parameter bits without synthesis.
 * @param imbe_fr Input frame as 8x23 bitplanes; not modified.
 * @param imbe_d  Output parameter bits (88).
 * @param result  Optional output status; receives C0/protected/C4/total errors and valid-context flags.
 * @return Corrected error total (`c0_errors + protected_errors`).
 */
MBE_API int mbe_decodeImbe7200x4400Frame(const char imbe_fr[8][23], char imbe_d[88], mbe_process_result* result);
/**
 * @brief Decode a soft IMBE 7200x4400 frame to parameter bits without synthesis.
 * @param imbe_fr Input soft frame as 8x23 bitplanes; not modified.
 * @param imbe_d  Output hard parameter bits (88).
 * @param result  Optional output status; also sets `MBE_PROCESS_FLAG_SOFT_INPUT`.
 * @return Corrected error total (`c0_errors + protected_errors`).
 */
MBE_API int mbe_decodeImbe7200x4400SoftFrame(const mbe_soft_bit imbe_fr[8][23], char imbe_d[88],
                                             mbe_process_result* result);
/**
 * @brief Process IMBE 4400 parameters into float PCM.
 * @param aout_buf Output buffer of 160 float samples.
 * @param result   Optional in/out status context. C0/C4 context is used when the matching valid flags are set.
 * @param imbe_d   Demodulated parameter bits (88).
 * @param cur_mp   In/out: current frame parameters (may be enhanced).
 * @param prev_mp  In/out: previous frame parameters.
 * @param prev_mp_enhanced In/out: enhanced previous parameters for continuity.
 * @return Total error count on success, or a negative `MBE_STATUS_*` code.
 */
MBE_API int mbe_processImbe4400Dataf(float* aout_buf, mbe_process_result* result, const char imbe_d[88],
                                     mbe_parms* cur_mp, mbe_parms* prev_mp, mbe_parms* prev_mp_enhanced);
/** @brief Process IMBE 4400 parameters into 16-bit PCM. */
MBE_API int mbe_processImbe4400Data(short* aout_buf, mbe_process_result* result, const char imbe_d[88],
                                    mbe_parms* cur_mp, mbe_parms* prev_mp, mbe_parms* prev_mp_enhanced);
/**
 * @brief Process IMBE 7200x4400 frame into float PCM.
 * @param aout_buf Output buffer of 160 float samples.
 * @param result   Optional output status populated by decode and synthesis.
 * @param imbe_fr  Input frame as 8x23 bitplanes.
 * @param imbe_d   Scratch/output parameter bits (88).
 * @param cur_mp,prev_mp,prev_mp_enhanced Parameter state as per Dataf variant.
 * @return Total error count on success, or a negative `MBE_STATUS_*` code.
 */
MBE_API int mbe_processImbe7200x4400Framef(float* aout_buf, mbe_process_result* result, const char imbe_fr[8][23],
                                           char imbe_d[88], mbe_parms* cur_mp, mbe_parms* prev_mp,
                                           mbe_parms* prev_mp_enhanced);
/** @brief Process IMBE 7200x4400 frame into 16-bit PCM. */
MBE_API int mbe_processImbe7200x4400Frame(short* aout_buf, mbe_process_result* result, const char imbe_fr[8][23],
                                          char imbe_d[88], mbe_parms* cur_mp, mbe_parms* prev_mp,
                                          mbe_parms* prev_mp_enhanced);
/**
 * @brief Process a soft IMBE 7200x4400 frame into float PCM.
 * @param result Optional output status populated by decode and synthesis.
 * @return Final total error count used by the wrapper.
 */
MBE_API int mbe_processImbe7200x4400SoftFramef(float* aout_buf, mbe_process_result* result,
                                               const mbe_soft_bit imbe_fr[8][23], char imbe_d[88], mbe_parms* cur_mp,
                                               mbe_parms* prev_mp, mbe_parms* prev_mp_enhanced);
/** @brief Process a soft IMBE 7200x4400 frame into int16 PCM; see float variant for result semantics. */
MBE_API int mbe_processImbe7200x4400SoftFrame(short* aout_buf, mbe_process_result* result,
                                              const mbe_soft_bit imbe_fr[8][23], char imbe_d[88], mbe_parms* cur_mp,
                                              mbe_parms* prev_mp, mbe_parms* prev_mp_enhanced);

/* Prototypes from imbe7100x4400.c */
/** @brief Print IMBE 7100x4400 parameter bits to stderr (debug). */
MBE_API void mbe_dumpImbe7100x4400Data(const char* imbe_d);
/** @brief Print IMBE 7100x4400 frame to stderr (debug). */
MBE_API void mbe_dumpImbe7100x4400Frame(const char imbe_fr[7][24]);
/** @brief ECC correction for IMBE 7100x4400 C0. */
MBE_API int mbe_eccImbe7100x4400C0(char imbe_fr[7][24]);
/** @brief ECC and parameter packing for IMBE 7100x4400. */
MBE_API int mbe_eccImbe7100x4400Data(char imbe_fr[7][24], char* imbe_d);
/** @brief Demodulate IMBE 7100x4400 interleaved data. */
MBE_API int mbe_demodulateImbe7100x4400Data(char imbe[7][24]);
/** @brief Convert IMBE 7100x4400 parameter set into 7200x4400 layout. */
MBE_API int mbe_convertImbe7100to7200(char* imbe_d);
/**
 * @brief Decode a hard IMBE 7100x4400 frame to converted IMBE 4400 parameter bits without synthesis.
 * @param imbe_fr Input frame as 7x24 bitplanes; not modified.
 * @param imbe_d  Output parameter bits (88), converted to the 7200x4400/IMBE 4400 layout.
 * @param result  Optional output status; receives C0/protected/C4/total errors and valid-context flags.
 * @return Corrected error total (`c0_errors + protected_errors`).
 */
MBE_API int mbe_decodeImbe7100x4400Frame(const char imbe_fr[7][24], char imbe_d[88], mbe_process_result* result);
/**
 * @brief Decode a soft IMBE 7100x4400 frame to converted IMBE 4400 parameter bits without synthesis.
 * @param imbe_fr Input soft frame as 7x24 bitplanes; not modified.
 * @param imbe_d  Output hard parameter bits (88), converted to the 7200x4400/IMBE 4400 layout.
 * @param result  Optional output status; also sets `MBE_PROCESS_FLAG_SOFT_INPUT`.
 * @return Corrected error total (`c0_errors + protected_errors`).
 */
MBE_API int mbe_decodeImbe7100x4400SoftFrame(const mbe_soft_bit imbe_fr[7][24], char imbe_d[88],
                                             mbe_process_result* result);
/**
 * @brief Process IMBE 7100x4400 frame into float PCM.
 * @param aout_buf Output buffer of 160 float samples.
 * @param result   Optional output status populated by decode and synthesis.
 * @param imbe_fr  Input frame as 7x24 bitplanes.
 * @param imbe_d   Scratch/output parameter bits (88, converted to 7200 layout).
 * @param cur_mp,prev_mp,prev_mp_enhanced Parameter state as per Dataf variant.
 * @return Total error count on success, or a negative `MBE_STATUS_*` code.
 */
MBE_API int mbe_processImbe7100x4400Framef(float* aout_buf, mbe_process_result* result, const char imbe_fr[7][24],
                                           char imbe_d[88], mbe_parms* cur_mp, mbe_parms* prev_mp,
                                           mbe_parms* prev_mp_enhanced);
/** @brief Process IMBE 7100x4400 frame into 16-bit PCM. */
MBE_API int mbe_processImbe7100x4400Frame(short* aout_buf, mbe_process_result* result, const char imbe_fr[7][24],
                                          char imbe_d[88], mbe_parms* cur_mp, mbe_parms* prev_mp,
                                          mbe_parms* prev_mp_enhanced);
/**
 * @brief Process a soft IMBE 7100x4400 frame into float PCM.
 * @param result Optional output status populated by decode and synthesis.
 * @return Final total error count used by the wrapper.
 */
MBE_API int mbe_processImbe7100x4400SoftFramef(float* aout_buf, mbe_process_result* result,
                                               const mbe_soft_bit imbe_fr[7][24], char imbe_d[88], mbe_parms* cur_mp,
                                               mbe_parms* prev_mp, mbe_parms* prev_mp_enhanced);
/** @brief Process a soft IMBE 7100x4400 frame into int16 PCM; see float variant for result semantics. */
MBE_API int mbe_processImbe7100x4400SoftFrame(short* aout_buf, mbe_process_result* result,
                                              const mbe_soft_bit imbe_fr[7][24], char imbe_d[88], mbe_parms* cur_mp,
                                              mbe_parms* prev_mp, mbe_parms* prev_mp_enhanced);

/**
 * @brief Get a pointer to a static NUL-terminated version string.
 *        The returned pointer remains valid for the lifetime of the program.
 */
MBE_API const char* mbe_versionString(void);

/**
 * @brief Set thread-local RNG seeds used by synthesis noise generators.
 *        Applies to comfort noise and unvoiced LCG cold-start state.
 * @param seed Any 32-bit seed value. A zero seed is accepted and remapped to
 *             an internal non-zero state.
 */
MBE_API void mbe_setThreadRngSeed(uint32_t seed);
/**
 * @brief Copy MBE parameter set from one struct to another.
 * @param source_mp Source parameters.
 * @param destination_mp Destination parameters. If either pointer is NULL, this is a no-op.
 */
MBE_API void mbe_moveMbeParms(const mbe_parms* source_mp, mbe_parms* destination_mp);
/**
 * @brief Replace current parameters with the last known parameters.
 * @param cur_mp Destination parameters to fill.
 * @param prev_mp Source parameters from previous frame. If either pointer is NULL, this is a no-op.
 */
MBE_API void mbe_useLastMbeParms(mbe_parms* cur_mp, const mbe_parms* prev_mp);
/**
 * @brief Initialize parameter state for decoding and synthesis.
 * @param cur_mp Output: current parameter state.
 * @param prev_mp Output: previous parameter state (zeroed/reset).
 * @param prev_mp_enhanced Output: enhanced previous parameter state. If any output pointer is NULL, this is a no-op.
 */
MBE_API void mbe_initMbeParms(mbe_parms* cur_mp, mbe_parms* prev_mp, mbe_parms* prev_mp_enhanced);
/**
 * @brief Apply spectral amplitude enhancement in-place.
 *
 * Invalid parameter state, including an out-of-range harmonic count `L`, is
 * ignored.
 * @param cur_mp In/out parameter set to enhance.
 */
MBE_API void mbe_spectralAmpEnhance(mbe_parms* cur_mp);
/**
 * @brief Synthesize tone frame (AMBE tone indices) into float PCM.
 * @param aout_buf Output buffer of 160 float samples.
 * @param ambe_d   AMBE parameter bits (49).
 * @param cur_mp   Current parameter set (tone synthesis state). NULL or invalid tone inputs synthesize silence.
 */
MBE_API void mbe_synthesizeTonef(float* aout_buf, const char* ambe_d, mbe_parms* cur_mp);
/**
 * @brief Synthesize tone for D-STAR style indices into float PCM.
 * @param aout_buf Output buffer of 160 float samples.
 * @param ambe_d   AMBE parameter bits (49).
 * @param cur_mp   Current parameter set. NULL synthesizes silence.
 * @param ID1      Tone index selector.
 */
MBE_API void mbe_synthesizeTonefdstar(float* aout_buf, const char* ambe_d, mbe_parms* cur_mp, int ID1);
/** @brief Fill float PCM buffer with 160 samples of silence. */
MBE_API void mbe_synthesizeSilencef(float* aout_buf);
/** @brief Fill 16-bit PCM buffer with 160 samples of silence. */
MBE_API void mbe_synthesizeSilence(short* aout_buf);
/**
 * @brief Synthesize one speech frame into float PCM.
 *
 * If `cur_mp` or `prev_mp` has an out-of-range harmonic count `L`, the output
 * buffer is filled with silence.
 * @param aout_buf Output buffer of 160 float samples.
 * @param cur_mp   Current parameter set.
 * @param prev_mp  Previous parameter set.
 */
MBE_API void mbe_synthesizeSpeechf(float* aout_buf, mbe_parms* cur_mp, mbe_parms* prev_mp);
/**
 * @brief Synthesize one speech frame into 16-bit PCM.
 *
 * If `cur_mp` or `prev_mp` has an out-of-range harmonic count `L`, the output
 * buffer is filled with silence.
 * @param aout_buf Output buffer of 160 16-bit samples.
 * @param cur_mp   Current parameter set.
 * @param prev_mp  Previous parameter set.
 */
MBE_API void mbe_synthesizeSpeech(short* aout_buf, mbe_parms* cur_mp, mbe_parms* prev_mp);
/**
 * @brief Convert 160 float samples to clipped/scaled 16-bit PCM.
 *
 * Applies the same scaling used by the `short` entry points: a fixed gain of
 * `7.0` and soft clipping at 95% of int16 full-scale before converting to
 * `short`. Non-finite input samples are handled before conversion: NaN becomes
 * zero and infinities clip to the corresponding bound. This makes the output
 * equivalent to calling the corresponding `short` synthesis APIs with the same
 * input state.
 * @param float_buf Input 160 float samples.
 * @param aout_buf  Output 160 16-bit samples.
 */
MBE_API void mbe_floattoshort(const float* float_buf, short* aout_buf);

/* === Frame repeat and muting functions === */

/** Maximum consecutive frame repeats before muting. */
#define MBE_MAX_FRAME_REPEATS     4

/** IMBE muting threshold (8.75% error rate). */
#define MBE_MUTING_THRESHOLD_IMBE 0.0875f

/** AMBE muting threshold (9.6% error rate). */
#define MBE_MUTING_THRESHOLD_AMBE 0.096f

/**
 * @brief Check if frame should be muted due to excessive errors.
 * @param mp Parameter set to check.
 * @return Non-zero if frame should be muted.
 */
MBE_API int mbe_requiresMuting(const mbe_parms* mp);

/**
 * @brief Check if max repeat threshold has been exceeded.
 * @param mp Parameter set to check.
 * @return Non-zero if repeatCount >= MBE_MAX_FRAME_REPEATS.
 */
MBE_API int mbe_isMaxFrameRepeat(const mbe_parms* mp);

/**
 * @brief Generate comfort noise for muted frames.
 * @param aout_buf Output buffer of 160 float samples.
 */
MBE_API void mbe_synthesizeComfortNoisef(float* aout_buf);

/**
 * @brief Generate comfort noise for muted frames (16-bit).
 * @param aout_buf Output buffer of 160 16-bit samples.
 */
MBE_API void mbe_synthesizeComfortNoise(short* aout_buf);

/* === Adaptive smoothing functions === */

/**
 * @brief Apply adaptive smoothing to parameters based on error rates.
 *        Implements JMBE Algorithms #111-116.
 *
 * Invalid parameter state, including an out-of-range harmonic count `L` in
 * either parameter set, is ignored.
 * @param cur_mp Current frame parameters (modified in-place).
 * @param prev_mp Previous frame parameters (for local energy).
 */
MBE_API void mbe_applyAdaptiveSmoothing(mbe_parms* cur_mp, const mbe_parms* prev_mp);

/**
 * @brief Check if adaptive smoothing is required based on error rates.
 * @param mp Parameter set to check.
 * @return Non-zero if smoothing should be applied.
 */
MBE_API int mbe_requiresAdaptiveSmoothing(const mbe_parms* mp);

#ifdef __cplusplus
}
#endif

#endif // MBELIB_NEO_PUBLIC_MBEBELIB_H
