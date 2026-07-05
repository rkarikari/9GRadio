// SPDX-License-Identifier: GPL-2.0-or-later
/*
 * Copyright (C) 2025 by arancormonk <180709949+arancormonk@users.noreply.github.com>
 *
 * Copyright (C) 2010 mbelib Author
 * GPG Key ID: 0xEA5EFE2C (9E7A 5527 9CDC EBF7 BF1B  D772 4F98 E863 EA5E FE2C)
 *
 * Portions were originally under the ISC license; this mbelib-neo
 * distribution is provided under GPL-2.0-or-later. See LICENSE for details.
 */

/**
 * @file
 * @brief Core MBE parameter utilities and speech/tone synthesis.
 *
 * @defgroup mbe_internal Internal Helpers
 * @brief Private helpers for voiced/unvoiced mixing, RNG, and SIMD paths.
 *
 * These functions and utilities are used internally by the library’s synthesis
 * implementation and are not part of the public API. Names and semantics may
 * change between releases. Where appropriate, helpers note the impact of
 * `MBELIB_ENABLE_SIMD` on determinism and ordering.
 *
 * @{ 
 */

#include <math.h>
#include <stdint.h>
#include <string.h>
#ifdef MBE_DEBUG
#include <stdio.h>
#endif
#include "mbe_compiler.h"
#if defined(MBELIB_ENABLE_SIMD)
#if defined(MBE_SIMD_TARGET_SSE2)
#include <emmintrin.h>
#endif
#if defined(MBE_SIMD_TARGET_NEON)
#include <arm_neon.h>
#endif
#endif

#include "mbe_adaptive.h"
#include "mbe_math.h"
#include "mbe_result.h"
#include "mbe_tone.h"
#include "mbe_unvoiced_fft.h"
#include "mbe_validation.h"
#include "mbelib-neo/mbelib.h"
#include "mbelib-neo/version.h"
#include "mbelib_const.h"

#define MBE_TWO_PI (2.0f * (float)M_PI)

/* Thread-local PRNG state and helpers (xorshift32) */
static MBE_THREAD_LOCAL uint32_t mbe_rng_state = 0x12345678u;

/* Thread-local FFT plan for unvoiced synthesis */
static MBE_THREAD_LOCAL mbe_fft_plan* mbe_fft_plan_instance = NULL;

void
mbe_initProcessResult(mbe_process_result* result) {
    if (!result) {
        return;
    }
    memset(result, 0, sizeof(*result));
}

void
mbe_formatProcessResult(char* str, size_t size, const mbe_process_result* result) {
    static const struct {
        unsigned flag;
        char marker;
    } markers[] = {
        {MBE_PROCESS_FLAG_ERASURE, 'E'},
        {MBE_PROCESS_FLAG_TONE, 'T'},
        {MBE_PROCESS_FLAG_REPEAT, 'R'},
        {MBE_PROCESS_FLAG_MUTE, 'M'},
    };

    if (!str || size == 0u) {
        return;
    }

    size_t pos = 0u;
    int total_errors = result ? result->total_errors : 0;
    if (total_errors < 0) {
        total_errors = 0;
    }

    for (int i = 0; i < total_errors && pos + 1u < size; ++i) {
        str[pos++] = '=';
    }

    if (result) {
        const unsigned flags = result->flags;
        for (size_t i = 0u; i < sizeof(markers) / sizeof(markers[0]) && pos + 1u < size; ++i) {
            if ((flags & markers[i].flag) != 0u) {
                str[pos++] = markers[i].marker;
            }
        }
    }
    str[pos] = '\0';
}

static uint8_t
mbe_clamp_u8_int(int value) {
    if (value < 0) {
        return 0u;
    }
    if (value > 255) {
        return 255u;
    }
    return (uint8_t)value;
}

mbe_soft_bit
mbe_softBitFromHard(int bit, uint8_t reliability) {
    mbe_soft_bit soft;
    soft.bit = (uint8_t)(bit ? 1u : 0u);
    soft.reliability = reliability;
    return soft;
}

mbe_soft_bit
mbe_softBitFromLlr(int16_t llr) {
    int mag = (llr < 0) ? -(int)llr : (int)llr;
    mbe_soft_bit soft;
    soft.bit = (uint8_t)((llr > 0) ? 1u : 0u);
    soft.reliability = mbe_clamp_u8_int(mag);
    return soft;
}

int
mbe_softBitsFromHard(const char* bits, mbe_soft_bit* soft, size_t count, uint8_t reliability) {
    if (!soft) {
        return MBE_STATUS_INVALID_ARGUMENT;
    }
    int ret = mbe_validate_bits(bits, count);
    if (ret < 0) {
        return ret;
    }
    for (size_t i = 0u; i < count; ++i) {
        soft[i] = mbe_softBitFromHard(bits[i], reliability);
    }
    return 0;
}

int
mbe_softBitsFromLlr(const int16_t* llr, mbe_soft_bit* soft, size_t count) {
    if (!llr || !soft) {
        return MBE_STATUS_INVALID_ARGUMENT;
    }
    for (size_t i = 0u; i < count; ++i) {
        soft[i] = mbe_softBitFromLlr(llr[i]);
    }
    return 0;
}

/**
 * @brief Get or create the thread-local FFT plan.
 * @return FFT plan for unvoiced synthesis, or NULL on allocation failure.
 */
static mbe_fft_plan*
mbe_get_fft_plan(void) {
    if (MBE_LIKELY(mbe_fft_plan_instance != NULL)) {
        return mbe_fft_plan_instance;
    }
    mbe_fft_plan_instance = mbe_fft_plan_alloc();
    return mbe_fft_plan_instance;
}

void
mbe_setThreadRngSeed(uint32_t seed) {
    if (seed == 0u) {
        seed = 0x6d25357bu; /* avoid zero state */
    }
    mbe_rng_state = seed;
    mbe_seedComfortNoiseRng(seed);
    mbe_seedUnvoicedNoiseLcg(seed);
}

/**
 * @brief Thread-local xorshift32 PRNG step.
 * @return New 32-bit pseudo-random state value (never 0).
 * @note Retained as a lightweight utility; current synthesis paths use
 *       codec-specific generators seeded via mbe_setThreadRngSeed().
 */
#if defined(__GNUC__) || defined(__clang__)
__attribute__((unused))
#endif
static inline uint32_t
mbe_xorshift32(void) {
    uint32_t x = mbe_rng_state;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    mbe_rng_state = x ? x : 0x6d25357bu;
    return mbe_rng_state;
}

/*
 * Voiced-path helpers keep the oscillator recurrence scalar so the synthesis
 * order matches the historical implementation, then apply SIMD only to the
 * output accumulation. The paired helper lets the common prev+cur voiced case
 * touch the output buffer once per 4-sample block instead of twice.
 */
static inline void
mbe_fill_voiced_cos_block4(float* restrict cblk, float* restrict c, float* restrict s, float sd, float cd) {
    float cc = *c;
    float ss = *s;

    for (int k = 0; k < 4; ++k) {
        cblk[k] = cc;
        float cpn = (cc * cd) - (ss * sd);
        float spn = (ss * cd) + (cc * sd);
        cc = cpn;
        ss = spn;
    }

    *c = cc;
    *s = ss;
}

/**
 * @brief Add four voiced samples to the output using oscillator recurrence.
 *
 * Generates four consecutive cosine samples from the current oscillator state,
 * multiplies by window `W[0..3]` and gain `gain`, and accumulates into
 * `Ss[0..3]`. `gain` already includes the JMBE `2.0f` voiced multiplier.
 *
 * @param Ss Output sample pointer (adds to Ss[0..3]).
 * @param W  Window values for these four samples.
 * @param gain Voiced gain for this band/component, including the `2.0f` factor.
 * @param c  In/out cosine register.
 * @param s  In/out sine register.
 * @param sd sin(Δθ) per step.
 * @param cd cos(Δθ) per step.
 */
/** @internal @ingroup mbe_internal */
static inline void
mbe_add_voiced_block4(float* restrict Ss, const float* restrict W, float gain, float* restrict c, float* restrict s,
                      float sd, float cd) {
    float cblk[4];
    mbe_fill_voiced_cos_block4(cblk, c, s, sd, cd);

#if defined(MBELIB_ENABLE_SIMD) && defined(MBE_SIMD_TARGET_SSE2)
    __m128 vC = _mm_loadu_ps(cblk);
    __m128 vW = _mm_loadu_ps(W);
    __m128 vA = _mm_set1_ps(gain);
    __m128 vS = _mm_loadu_ps(Ss);
    vS = _mm_add_ps(vS, _mm_mul_ps(_mm_mul_ps(vC, vW), vA));
    _mm_storeu_ps(Ss, vS);
#elif defined(MBELIB_ENABLE_SIMD) && defined(MBE_SIMD_TARGET_NEON)
    float32x4_t vC = vld1q_f32(cblk);
    float32x4_t vW = vld1q_f32(W);
    float32x4_t vA = vdupq_n_f32(gain);
    float32x4_t vS = vld1q_f32(Ss);
    vS = vaddq_f32(vS, vmulq_f32(vmulq_f32(vC, vW), vA));
    vst1q_f32(Ss, vS);
#else
    Ss[0] += gain * W[0] * cblk[0];
    Ss[1] += gain * W[1] * cblk[1];
    Ss[2] += gain * W[2] * cblk[2];
    Ss[3] += gain * W[3] * cblk[3];
#endif
}

/**
 * @brief Add previous/current voiced contributions in one 4-sample block.
 *
 * This preserves the historical add order (`prev`, then `cur`) while avoiding
 * a second load/store of the output buffer when both voiced components are
 * active for the same harmonic.
 */
typedef struct mbe_voiced_block_component {
    const float* W;
    float gain;
    float* c;
    float* s;
    float sd;
    float cd;
} mbe_voiced_block_component;

/** @internal @ingroup mbe_internal */
static inline void
mbe_add_voiced_dual_block4(float* restrict Ss, const mbe_voiced_block_component* restrict prev,
                           const mbe_voiced_block_component* restrict cur) {
    float prev_cblk[4];
    float cur_cblk[4];

    mbe_fill_voiced_cos_block4(prev_cblk, prev->c, prev->s, prev->sd, prev->cd);
    mbe_fill_voiced_cos_block4(cur_cblk, cur->c, cur->s, cur->sd, cur->cd);

#if defined(MBELIB_ENABLE_SIMD) && defined(MBE_SIMD_TARGET_SSE2)
    __m128 vS = _mm_loadu_ps(Ss);
    __m128 vPrev = _mm_mul_ps(_mm_mul_ps(_mm_loadu_ps(prev_cblk), _mm_loadu_ps(prev->W)), _mm_set1_ps(prev->gain));
    __m128 vCur = _mm_mul_ps(_mm_mul_ps(_mm_loadu_ps(cur_cblk), _mm_loadu_ps(cur->W)), _mm_set1_ps(cur->gain));
    vS = _mm_add_ps(vS, vPrev);
    vS = _mm_add_ps(vS, vCur);
    _mm_storeu_ps(Ss, vS);
#elif defined(MBELIB_ENABLE_SIMD) && defined(MBE_SIMD_TARGET_NEON)
    float32x4_t vS = vld1q_f32(Ss);
    float32x4_t vPrev = vmulq_f32(vmulq_f32(vld1q_f32(prev_cblk), vld1q_f32(prev->W)), vdupq_n_f32(prev->gain));
    float32x4_t vCur = vmulq_f32(vmulq_f32(vld1q_f32(cur_cblk), vld1q_f32(cur->W)), vdupq_n_f32(cur->gain));
    vS = vaddq_f32(vS, vPrev);
    vS = vaddq_f32(vS, vCur);
    vst1q_f32(Ss, vS);
#else
    Ss[0] += prev->gain * prev->W[0] * prev_cblk[0];
    Ss[1] += prev->gain * prev->W[1] * prev_cblk[1];
    Ss[2] += prev->gain * prev->W[2] * prev_cblk[2];
    Ss[3] += prev->gain * prev->W[3] * prev_cblk[3];
    Ss[0] += cur->gain * cur->W[0] * cur_cblk[0];
    Ss[1] += cur->gain * cur->W[1] * cur_cblk[1];
    Ss[2] += cur->gain * cur->W[2] * cur_cblk[2];
    Ss[3] += cur->gain * cur->W[3] * cur_cblk[3];
#endif
}

/** @} */ /* end of mbe_internal */

const char*
mbe_versionString(void) {
    return MBELIB_VERSION;
}

/**
 * @brief Copy MBE parameter set from input to output.
 * @param source_mp      Source parameter set.
 * @param destination_mp Destination parameter set.
 *
 * Uses struct assignment for efficiency - the compiler generates optimal
 * code (typically a single memcpy-like operation) rather than many individual
 * field copies. The mbe_parms struct contains no pointers, so shallow copy
 * is correct and safe.
 */
void
mbe_moveMbeParms(const mbe_parms* source_mp, mbe_parms* destination_mp) {
    if (!source_mp || !destination_mp) {
        return;
    }
    *destination_mp = *source_mp;
}

/**
 * @brief Replace current parameters with the last known parameters.
 * @param cur_mp  Destination parameter set to fill.
 * @param prev_mp Source parameter set from previous frame.
 *
 * Uses struct assignment for efficiency. See mbe_moveMbeParms() for details.
 */
void
mbe_useLastMbeParms(mbe_parms* cur_mp, const mbe_parms* prev_mp) {
    if (!cur_mp || !prev_mp) {
        return;
    }
    *cur_mp = *prev_mp;
}

/**
 * @brief Initialize MBE parameter state for decoding and synthesis.
 * @param cur_mp Output: current parameter state.
 * @param prev_mp Output: previous parameter state (reset to defaults).
 * @param prev_mp_enhanced Output: enhanced previous parameter state.
 */
void
mbe_initMbeParms(mbe_parms* cur_mp, mbe_parms* prev_mp, mbe_parms* prev_mp_enhanced) {

    if (!cur_mp || !prev_mp || !prev_mp_enhanced) {
        return;
    }

    int l;
    prev_mp->swn = 0;
    prev_mp->tonePhase = 0;
    /* Match JMBE IMBEModelParameters default (IMBEFundamentalFrequency.DEFAULT, index 134). */
    prev_mp->w0 = (float)((4.0 * M_PI) / (134.0 + 39.5));
    prev_mp->L = (int)(0.9254 * (int)((M_PI / prev_mp->w0) + 0.25));
    prev_mp->K = 12;
    prev_mp->gamma = (float)0;
    for (l = 0; l <= 56; l++) {
        prev_mp->Ml[l] = 1.0f;
        prev_mp->Vl[l] = 0;
        prev_mp->log2Ml[l] = (float)0; // log2 of 1 == 0
        prev_mp->PHIl[l] = (float)0;
        /* JMBE previous-phase arrays start at 0.0f. */
        prev_mp->PSIl[l] = 0.0f;
    }
    /* Initialize adaptive smoothing state */
    prev_mp->localEnergy = MBE_DEFAULT_LOCAL_ENERGY;
    prev_mp->amplitudeThreshold = MBE_DEFAULT_AMPLITUDE_THRESHOLD;
    prev_mp->errorRate = 0.0f;
    prev_mp->errorCountTotal = 0;
    prev_mp->errorCount4 = 0;

    /* Initialize frame repeat state */
    prev_mp->repeatCount = 0;
    prev_mp->mutingThreshold = MBE_MUTING_THRESHOLD_IMBE;

    /* Initialize FFT-based unvoiced synthesis state.
     * Use seed<0 sentinel so first nextBuffer() returns JMBE's initial all-zero
     * 256-sample buffer, then primes with MBE_LCG_DEFAULT_SEED. */
    prev_mp->noiseSeed = -1.0f;
    memset(prev_mp->noiseOverlap, 0, sizeof(prev_mp->noiseOverlap));
    memset(prev_mp->previousUw, 0, sizeof(prev_mp->previousUw));

    mbe_moveMbeParms(prev_mp, cur_mp);
    mbe_moveMbeParms(prev_mp, prev_mp_enhanced);
}

static void
mbe_precompute_harmonic_cosines(float cos_tab[57], int L, float w0) {
    float s_step, c_step;
    mbe_sincosf(w0, &s_step, &c_step);
    float c = 1.0f, s = 0.0f;
    for (int l = 1; l <= L; ++l) {
        float cn = (c * c_step) - (s * s_step);
        float sn = (s * c_step) + (c * s_step);
        c = cn;
        s = sn;
        cos_tab[l] = c;
    }
}

static void
mbe_accumulate_spectral_energy(const mbe_parms* cur_mp, const float cos_tab[57], float* out_rm0, float* out_rm1) {
    const int L = cur_mp->L;
    float Rm0 = 0.0f;
    float Rm1 = 0.0f;
    int l;

#if defined(MBELIB_ENABLE_SIMD)
#if defined(MBE_SIMD_TARGET_SSE2)
    if (L >= 4) {
        __m128 vRm0 = _mm_setzero_ps();
        __m128 vRm1 = _mm_setzero_ps();
        int l4;
        for (l4 = 1; l4 + 3 <= L; l4 += 4) {
            __m128 vMl = _mm_loadu_ps(&cur_mp->Ml[l4]);
            __m128 vCos = _mm_loadu_ps(&cos_tab[l4]);
            __m128 vMl2 = _mm_mul_ps(vMl, vMl);
            vRm0 = _mm_add_ps(vRm0, vMl2);
            vRm1 = _mm_add_ps(vRm1, _mm_mul_ps(vMl2, vCos));
        }
        __m128 shuf = _mm_shuffle_ps(vRm0, vRm0, _MM_SHUFFLE(2, 3, 0, 1));
        __m128 sums = _mm_add_ps(vRm0, shuf);
        shuf = _mm_movehl_ps(shuf, sums);
        sums = _mm_add_ss(sums, shuf);
        Rm0 = _mm_cvtss_f32(sums);

        shuf = _mm_shuffle_ps(vRm1, vRm1, _MM_SHUFFLE(2, 3, 0, 1));
        sums = _mm_add_ps(vRm1, shuf);
        shuf = _mm_movehl_ps(shuf, sums);
        sums = _mm_add_ss(sums, shuf);
        Rm1 = _mm_cvtss_f32(sums);

        for (l = l4; l <= L; l++) {
            const float Ml2 = cur_mp->Ml[l] * cur_mp->Ml[l];
            Rm0 += Ml2;
            Rm1 += Ml2 * cos_tab[l];
        }
    } else
#elif defined(MBE_SIMD_TARGET_NEON)
    if (L >= 4) {
        float32x4_t vRm0 = vdupq_n_f32(0.0f);
        float32x4_t vRm1 = vdupq_n_f32(0.0f);
        int l4;
        for (l4 = 1; l4 + 3 <= L; l4 += 4) {
            float32x4_t vMl = vld1q_f32(&cur_mp->Ml[l4]);
            float32x4_t vCos = vld1q_f32(&cos_tab[l4]);
            float32x4_t vMl2 = vmulq_f32(vMl, vMl);
            vRm0 = vaddq_f32(vRm0, vMl2);
            vRm1 = vmlaq_f32(vRm1, vMl2, vCos);
        }
        float32x2_t sum2 = vadd_f32(vget_low_f32(vRm0), vget_high_f32(vRm0));
        Rm0 = vget_lane_f32(vpadd_f32(sum2, sum2), 0);
        sum2 = vadd_f32(vget_low_f32(vRm1), vget_high_f32(vRm1));
        Rm1 = vget_lane_f32(vpadd_f32(sum2, sum2), 0);

        for (l = l4; l <= L; l++) {
            const float Ml2 = cur_mp->Ml[l] * cur_mp->Ml[l];
            Rm0 += Ml2;
            Rm1 += Ml2 * cos_tab[l];
        }
    } else
#endif
#endif
    {
        for (l = 1; l <= L; l++) {
            const float Ml2 = cur_mp->Ml[l] * cur_mp->Ml[l];
            Rm0 += Ml2;
            Rm1 += Ml2 * cos_tab[l];
        }
    }

    *out_rm0 = Rm0;
    *out_rm1 = Rm1;
}

static void
mbe_apply_spectral_weights(mbe_parms* cur_mp, const float cos_tab[57], float Rm0, float Rm1) {
    float Wl[57];
    float R2m0 = Rm0 * Rm0;
    float R2m1 = Rm1 * Rm1;
    const int L = cur_mp->L;

    for (int l = 1; l <= L; l++) {
        if (cur_mp->Ml[l] != 0.0f) {
            const float cos_w0l = cos_tab[l];
            Wl[l] = sqrtf(cur_mp->Ml[l])
                    * sqrtf(sqrtf(((float)0.96 * (float)M_PI * ((R2m0 + R2m1) - ((float)2 * Rm0 * Rm1 * cos_w0l)))
                                  / (cur_mp->w0 * Rm0 * (R2m0 - R2m1))));

            if ((8 * l) <= L) {
                /* no-op for low harmonics */
            } else if (Wl[l] > 1.2f) {
                cur_mp->Ml[l] = 1.2f * cur_mp->Ml[l];
            } else if (Wl[l] < 0.5f) {
                cur_mp->Ml[l] = 0.5f * cur_mp->Ml[l];
            } else {
                cur_mp->Ml[l] = Wl[l] * cur_mp->Ml[l];
            }
        }
    }
}

static float
mbe_sum_spectral_magnitudes_squared(const mbe_parms* cur_mp) {
    const int L = cur_mp->L;
    float sum = 0.0f;
    int l;

#if defined(MBELIB_ENABLE_SIMD)
#if defined(MBE_SIMD_TARGET_SSE2)
    if (L >= 4) {
        __m128 vsum = _mm_setzero_ps();
        __m128 sign_mask = _mm_set1_ps(-0.0f);
        int l4;
        for (l4 = 1; l4 + 3 <= L; l4 += 4) {
            __m128 vMl = _mm_loadu_ps(&cur_mp->Ml[l4]);
            vMl = _mm_andnot_ps(sign_mask, vMl);
            vsum = _mm_add_ps(vsum, _mm_mul_ps(vMl, vMl));
        }
        __m128 shuf = _mm_shuffle_ps(vsum, vsum, _MM_SHUFFLE(2, 3, 0, 1));
        __m128 sums = _mm_add_ps(vsum, shuf);
        shuf = _mm_movehl_ps(shuf, sums);
        sums = _mm_add_ss(sums, shuf);
        sum = _mm_cvtss_f32(sums);

        for (l = l4; l <= L; l++) {
            float M = cur_mp->Ml[l];
            if (M < 0.0f) {
                M = -M;
            }
            sum += M * M;
        }
    } else
#elif defined(MBE_SIMD_TARGET_NEON)
    if (L >= 4) {
        float32x4_t vsum = vdupq_n_f32(0.0f);
        int l4;
        for (l4 = 1; l4 + 3 <= L; l4 += 4) {
            float32x4_t vMl = vld1q_f32(&cur_mp->Ml[l4]);
            vMl = vabsq_f32(vMl);
            vsum = vmlaq_f32(vsum, vMl, vMl);
        }
        float32x2_t sum2 = vadd_f32(vget_low_f32(vsum), vget_high_f32(vsum));
        sum = vget_lane_f32(vpadd_f32(sum2, sum2), 0);

        for (l = l4; l <= L; l++) {
            float M = cur_mp->Ml[l];
            if (M < 0.0f) {
                M = -M;
            }
            sum += M * M;
        }
    } else
#endif
#endif
    {
        for (l = 1; l <= L; l++) {
            float M = cur_mp->Ml[l];
            if (M < 0.0f) {
                M = -M;
            }
            sum += M * M;
        }
    }

    return sum;
}

static void
mbe_scale_spectral_magnitudes(mbe_parms* cur_mp, float gamma) {
    const int L = cur_mp->L;
    int l;

#if defined(MBELIB_ENABLE_SIMD)
#if defined(MBE_SIMD_TARGET_SSE2)
    if (L >= 4) {
        __m128 vgamma = _mm_set1_ps(gamma);
        int l4;
        for (l4 = 1; l4 + 3 <= L; l4 += 4) {
            __m128 vMl = _mm_loadu_ps(&cur_mp->Ml[l4]);
            vMl = _mm_mul_ps(vMl, vgamma);
            _mm_storeu_ps(&cur_mp->Ml[l4], vMl);
        }
        for (l = l4; l <= L; l++) {
            cur_mp->Ml[l] = gamma * cur_mp->Ml[l];
        }
    } else
#elif defined(MBE_SIMD_TARGET_NEON)
    if (L >= 4) {
        float32x4_t vgamma = vdupq_n_f32(gamma);
        int l4;
        for (l4 = 1; l4 + 3 <= L; l4 += 4) {
            float32x4_t vMl = vld1q_f32(&cur_mp->Ml[l4]);
            vMl = vmulq_f32(vMl, vgamma);
            vst1q_f32(&cur_mp->Ml[l4], vMl);
        }
        for (l = l4; l <= L; l++) {
            cur_mp->Ml[l] = gamma * cur_mp->Ml[l];
        }
    } else
#endif
#endif
    {
        for (l = 1; l <= L; l++) {
            cur_mp->Ml[l] = gamma * cur_mp->Ml[l];
        }
    }
}

/**
 * @brief Apply spectral amplitude enhancement to the current parameters.
 * @param cur_mp In/out parameter set to enhance.
 *
 * Uses SIMD optimizations for accumulation and scaling loops when available.
 */
float
mbe_spectralAmpEnhanceWithRm0(mbe_parms* cur_mp) {

    float Rm0, Rm1;
    float cos_tab[57];

    if (MBE_UNLIKELY(!cur_mp || !mbe_harmonic_count_is_valid(cur_mp->L))) {
        return 0.0f;
    }

    mbe_precompute_harmonic_cosines(cos_tab, cur_mp->L, cur_mp->w0);
    mbe_accumulate_spectral_energy(cur_mp, cos_tab, &Rm0, &Rm1);

    mbe_apply_spectral_weights(cur_mp, cos_tab, Rm0, Rm1);

    float sum = mbe_sum_spectral_magnitudes_squared(cur_mp);
    float gamma = (sum == 0.0f) ? 1.0f : sqrtf(Rm0 / sum);
    mbe_scale_spectral_magnitudes(cur_mp, gamma);

    return Rm0;
}

void
mbe_spectralAmpEnhance(mbe_parms* cur_mp) {
    (void)mbe_spectralAmpEnhanceWithRm0(cur_mp);
}

/* JMBE float-domain soft clip translated to this library's float scale. */
#define MBE_AUDIO_SOFT_CLIP_FLOAT   ((32767.0f * 0.95f) / 7.0f)
#define MBE_TONE_PHASE_SCALE        4294967296.0
#define MBE_TONE_PHASE_RAD_PER_TICK ((2.0 * M_PI) / MBE_TONE_PHASE_SCALE)

static inline float
mbe_clipFloatSample(float sample) {
    if (sample > MBE_AUDIO_SOFT_CLIP_FLOAT) {
        return MBE_AUDIO_SOFT_CLIP_FLOAT;
    }
    if (sample < -MBE_AUDIO_SOFT_CLIP_FLOAT) {
        return -MBE_AUDIO_SOFT_CLIP_FLOAT;
    }
    return sample;
}

static void
mbe_clipFloatBuffer(float* samples, int count) {
    for (int i = 0; i < count; i++) {
        samples[i] = mbe_clipFloatSample(samples[i]);
    }
}

#ifndef DISABLE_AMBE_TONES
static inline uint32_t
mbe_tonePhaseStep(double freq_hz) {
    double step = (freq_hz / 8000.0) * MBE_TONE_PHASE_SCALE;
    if (step <= 0.0) {
        return 0u;
    }
    return (uint32_t)(step + 0.5);
}

static inline float
mbe_toneSampleFromPhase(uint32_t phase) {
    float angle = (float)(((double)phase * MBE_TONE_PHASE_RAD_PER_TICK) - (M_PI / 2.0));
    return sinf(angle);
}

static void
mbe_renderTonef(float* aout_buf, mbe_parms* cur_mp, float freq1, float freq2, int amplitude_id) {
    if (!aout_buf || !cur_mp || freq1 <= 0.0f) {
        mbe_synthesizeSilencef(aout_buf);
        return;
    }

    const int dual_tone = (freq2 > 0.0f) && (fabsf(freq2 - freq1) > 1e-6f);
    const float gain = (((amplitude_id < 0) ? 0.0f : (float)amplitude_id) / 127.0f) * MBE_AUDIO_SOFT_CLIP_FLOAT;
    const uint32_t step1 = mbe_tonePhaseStep((double)freq1);
    const uint32_t step2 = dual_tone ? mbe_tonePhaseStep((double)freq2) : 0u;
    uint32_t phase1 = (uint32_t)cur_mp->swn;
    uint32_t phase2 = cur_mp->tonePhase;

    for (int n = 0; n < 160; n++) {
        phase1 += step1;
        float s1 = mbe_toneSampleFromPhase(phase1);

        if (dual_tone) {
            phase2 += step2;
            float s2 = mbe_toneSampleFromPhase(phase2);
            aout_buf[n] = (0.5f * gain * s1) + (0.5f * gain * s2);
        } else {
            aout_buf[n] = gain * s1;
        }
    }

    cur_mp->swn = (int)phase1;
    cur_mp->tonePhase = phase2;
}
#endif

/**
 * @brief Synthesize a tone frame into 160 float samples at 8 kHz.
 * @param aout_buf Output buffer of 160 float samples.
 * @param ambe_d   AMBE parameter bits (49) providing tone indices.
 * @param cur_mp   Current parameter set (tone synthesis state).
 */
void
mbe_synthesizeTonef(float* aout_buf, const char* ambe_d, mbe_parms* cur_mp) {
#ifdef DISABLE_AMBE_TONES // generate silence if tones disabled
    (void)ambe_d;
    (void)cur_mp;
    mbe_synthesizeSilencef(aout_buf);
    return;
#else
    int i;

    if (!cur_mp || mbe_validate_bits(ambe_d, 49u) < 0) {
        mbe_synthesizeSilencef(aout_buf);
        return;
    }

    int u0, u1, u2, u3;
    u0 = u1 = u2 = u3 = 0;

    for (i = 0; i < 12; i++) {
        u0 = u0 << 1;
        u0 = u0 | (int)ambe_d[i];
    }

    for (i = 12; i < 24; i++) {
        u1 = u1 << 1;
        u1 = u1 | (int)ambe_d[i];
    }

    for (i = 24; i < 35; i++) {
        u2 = u2 << 1;
        u2 = u2 | (int)ambe_d[i];
    }

    for (i = 35; i < 49; i++) {
        u3 = u3 << 1;
        u3 = u3 | (int)ambe_d[i];
    }

    int AD, ID0, ID1, ID2, ID3, ID4;
    AD = ((u0 & 0x3f) << 1) + ((u3 >> 4) & 0x1);
    ID0 = 0;
    ID1 = ((u1 & 0xfff) >> 4);
    ID2 = ((u1 & 0xf) << 4) + ((u2 >> 7) & 0xf);
    ID3 = ((u2 & 0x7f) << 1) + ((u2 >> 13) & 0x1);
    ID4 = ((u3 & 0x1fe0) >> 5);

    float freq1, freq2;
    (void)ID0;
    (void)ID2;
    (void)ID3; /* parsed for potential validation */
    (void)ID4;

    if (!mbe_tone_lookup_freqs(ID1, &freq1, &freq2)) {
        mbe_synthesizeSilencef(aout_buf);
        return;
    }

    mbe_renderTonef(aout_buf, cur_mp, freq1, freq2, AD);
#endif
}

/**
 * @brief Synthesize a D-STAR style tone into 160 float samples.
 * @param aout_buf Output buffer of 160 float samples.
 * @param ambe_d   AMBE parameter bits (49).
 * @param cur_mp   Current parameter set.
 * @param ID1      Tone index selector.
 */
void
mbe_synthesizeTonefdstar(float* aout_buf, const char* ambe_d, mbe_parms* cur_mp, int ID1) {
#ifdef DISABLE_AMBE_TONES // generate silence if tones disabled
    (void)ambe_d;
    (void)cur_mp;
    (void)ID1;
    mbe_synthesizeSilencef(aout_buf);
    return;
#else
    int AD = 103; /* JMBE nominal D-STAR tone amplitude */
    float freq1 = 0, freq2 = 0;
    (void)ambe_d;

    if (!cur_mp) {
        mbe_synthesizeSilencef(aout_buf);
        return;
    }

    switch (ID1) {
        // single tones, set frequency
        case 5:
            freq1 = 156.25;
            freq2 = freq1;
            break;
        case 6:
            freq1 = 187.5;
            freq2 = freq1;
            break;
        // single tones, calculated frequency
        default:
            if ((ID1 >= 7) && (ID1 <= 122)) {
                freq1 = 31.25f * (float)ID1;
                freq2 = freq1;
            }
    }

    if (freq1 <= 0.0f) {
        mbe_synthesizeSilencef(aout_buf);
        return;
    }

    mbe_renderTonef(aout_buf, cur_mp, freq1, freq2, AD);
#endif
}

/**
 * @brief Write 160 float samples of silence.
 * @param aout_buf Output buffer of 160 float samples.
 */
void
mbe_synthesizeSilencef(float* aout_buf) {
    if (aout_buf == NULL) {
        return;
    }
    memset(aout_buf, 0, 160 * sizeof(*aout_buf));
}

/**
 * @brief Write 160 16-bit samples of silence.
 * @param aout_buf Output buffer of 160 16-bit samples.
 */
void
mbe_synthesizeSilence(short* aout_buf) {
    if (aout_buf == NULL) {
        return;
    }
    memset(aout_buf, 0, 160 * sizeof(*aout_buf));
}

/**
 * @brief Synthesize one speech frame into 160 float samples at 8 kHz.
 *
 * Uses FFT-based unvoiced synthesis (JMBE Algorithms #117-126) for
 * high-quality unvoiced audio with proper WOLA frame blending.
 *
 * @param aout_buf Output buffer of 160 float samples.
 * @param cur_mp   Current parameter set.
 * @param prev_mp  Previous parameter set.
 */
/* JMBE-compatible white noise scalar for phase calculation: 2*PI / 53125 */
#define MBE_WHITE_NOISE_SCALAR (2.0f * (float)M_PI / 53125.0f)

static int
mbe_should_mute_speech(const mbe_parms* cur_mp) {
    int mute_on_error_rate = (fabsf(cur_mp->mutingThreshold - MBE_MUTING_THRESHOLD_AMBE) > 1e-6f);
    return mbe_isMaxFrameRepeat(cur_mp) || (mute_on_error_rate && mbe_requiresMuting(cur_mp));
}

static int
mbe_count_unvoiced_bands(const mbe_parms* cur_mp) {
    int numUv = 0;
    for (int l = 0; l <= cur_mp->L; l++) {
        if (cur_mp->Vl[l] == 0) {
            numUv++;
        }
    }
    return numUv;
}

static int
mbe_reconcile_speech_model_lengths(mbe_parms* cur_mp, mbe_parms* prev_mp) {
    if (cur_mp->L > prev_mp->L) {
        int maxl = cur_mp->L;
        for (int l = prev_mp->L + 1; l <= maxl; l++) {
            prev_mp->Ml[l] = 0.0f;
            prev_mp->Vl[l] = 1;
        }
        return maxl;
    }

    int maxl = prev_mp->L;
    for (int l = cur_mp->L + 1; l <= maxl; l++) {
        cur_mp->Ml[l] = 0.0f;
        cur_mp->Vl[l] = 1;
    }
    return maxl;
}

static void
mbe_update_speech_phases(mbe_parms* cur_mp, mbe_parms* prev_mp, const float noise_buffer[256], int numUv, int N) {
    const float cw0 = cur_mp->w0;
    const float pw0 = prev_mp->w0;

    for (int l = 1; l <= 56; l++) {
        float prev_psil_wrapped = fmodf(prev_mp->PSIl[l], MBE_TWO_PI);
        if (prev_psil_wrapped < 0.0f) {
            prev_psil_wrapped += MBE_TWO_PI;
        }
        prev_mp->PSIl[l] = prev_psil_wrapped;

        cur_mp->PSIl[l] = prev_psil_wrapped + ((pw0 + cw0) * ((float)(l * N) / 2.0f));
        if (l <= (cur_mp->L / 4)) {
            cur_mp->PHIl[l] = cur_mp->PSIl[l];
        } else {
            float pl = (MBE_WHITE_NOISE_SCALAR * noise_buffer[l]) - (float)M_PI;
            cur_mp->PHIl[l] = cur_mp->PSIl[l] + (((float)numUv * pl) / (float)cur_mp->L);
        }
    }
}

static void
mbe_render_voiced_interpolated(float* aout_buf, const mbe_parms* cur_mp, const mbe_parms* prev_mp, int l, int N,
                               float cw0, float pw0, float pw0l) {
    float* Ss = aout_buf;
    float deltaphil = cur_mp->PHIl[l] - prev_mp->PHIl[l] - (((pw0 + cw0) * (float)(l * N)) / 2.0f);
    float deltawl = (1.0f / (float)N)
                    * (deltaphil - (2.0f * (float)M_PI * floorf((deltaphil + (float)M_PI) / (2.0f * (float)M_PI))));

    for (int n = 0; n < N; n++) {
        float thetaln =
            prev_mp->PHIl[l] + ((pw0l + deltawl) * (float)n) + (((cw0 - pw0) * (float)(l * n * n)) / (float)(2 * N));
        float aln = prev_mp->Ml[l] + (((float)n / (float)N) * (cur_mp->Ml[l] - prev_mp->Ml[l]));
        *Ss += 2.0f * aln * cosf(thetaln);
        Ss++;
    }
}

static void
mbe_render_voiced_windowed(float* aout_buf, const mbe_parms* cur_mp, const mbe_parms* prev_mp, int l, int N, float cw0l,
                           float pw0l, int cur_voiced, int prev_voiced) {
    float* Ss = aout_buf;

    if (prev_voiced && cur_voiced) {
        const float gain_prev = 2.0f * prev_mp->Ml[l];
        float sd_prev, cd_prev;
        mbe_sincosf(pw0l, &sd_prev, &cd_prev);
        float s_prev, c_prev;
        mbe_sincosf(prev_mp->PHIl[l], &s_prev, &c_prev);

        const float gain_cur = 2.0f * cur_mp->Ml[l];
        float sd_cur, cd_cur;
        mbe_sincosf(cw0l, &sd_cur, &cd_cur);
        float s_cur, c_cur;
        mbe_sincosf(cur_mp->PHIl[l] - (cw0l * (float)N), &s_cur, &c_cur);

        for (int n = 0; n < N; n += 4) {
            const mbe_voiced_block_component prev_component = {Ws + n + N, gain_prev, &c_prev,
                                                               &s_prev,    sd_prev,   cd_prev};
            const mbe_voiced_block_component cur_component = {Ws + n, gain_cur, &c_cur, &s_cur, sd_cur, cd_cur};
            mbe_add_voiced_dual_block4(Ss, &prev_component, &cur_component);
            Ss += 4;
        }
    } else if (prev_voiced) {
        const float gain_prev = 2.0f * prev_mp->Ml[l];
        float sd_prev, cd_prev;
        mbe_sincosf(pw0l, &sd_prev, &cd_prev);
        float s_prev, c_prev;
        mbe_sincosf(prev_mp->PHIl[l], &s_prev, &c_prev);

        for (int n = 0; n < N; n += 4) {
            mbe_add_voiced_block4(Ss, Ws + n + N, gain_prev, &c_prev, &s_prev, sd_prev, cd_prev);
            Ss += 4;
        }
    } else if (cur_voiced) {
        const float gain_cur = 2.0f * cur_mp->Ml[l];
        float sd_cur, cd_cur;
        mbe_sincosf(cw0l, &sd_cur, &cd_cur);
        float s_cur, c_cur;
        mbe_sincosf(cur_mp->PHIl[l] - (cw0l * (float)N), &s_cur, &c_cur);

        for (int n = 0; n < N; n += 4) {
            mbe_add_voiced_block4(Ss, Ws + n, gain_cur, &c_cur, &s_cur, sd_cur, cd_cur);
            Ss += 4;
        }
    }
}

static void
mbe_render_voiced_speech(float* aout_buf, const mbe_parms* cur_mp, const mbe_parms* prev_mp, int maxl, int N) {
    const float cw0 = cur_mp->w0;
    const float pw0 = prev_mp->w0;

    for (int l = 1; l <= maxl; l++) {
        float cw0l = cw0 * (float)l;
        float pw0l = pw0 * (float)l;
        int cur_voiced = (cur_mp->Vl[l] == 1);
        int prev_voiced = (prev_mp->Vl[l] == 1);

        if (cur_voiced || prev_voiced) {
            int use_interpolation = (l < 8) && cur_voiced && prev_voiced && (fabsf(cw0 - pw0) < (0.1f * cw0));
            if (use_interpolation) {
                mbe_render_voiced_interpolated(aout_buf, cur_mp, prev_mp, l, N, cw0, pw0, pw0l);
            } else {
                mbe_render_voiced_windowed(aout_buf, cur_mp, prev_mp, l, N, cw0l, pw0l, cur_voiced, prev_voiced);
            }
        }
    }
}

static void
mbe_synthesizeSpeechCore(float* aout_buf, mbe_parms* cur_mp, mbe_parms* prev_mp, int has_pre_enh_rm0,
                         float pre_enh_rm0) {

    const int N = 160;

    if (MBE_UNLIKELY(!aout_buf)) {
        return;
    }
    if (MBE_UNLIKELY(!cur_mp || !prev_mp || !mbe_harmonic_count_is_valid(cur_mp->L)
                     || !mbe_harmonic_count_is_valid(prev_mp->L))) {
        mbe_synthesizeSilencef(aout_buf);
        return;
    }

    /* Apply adaptive smoothing (Algorithms #111-116) before muting checks.
     * JMBE computes/update smoothing state during parameter preparation even
     * for frames that are subsequently muted. */
    if (has_pre_enh_rm0) {
        mbe_applyAdaptiveSmoothingWithRm0(cur_mp, prev_mp, pre_enh_rm0);
    } else {
        mbe_applyAdaptiveSmoothing(cur_mp, prev_mp);
    }

    /* Frame muting:
     * - JMBE IMBE path mutes on max repeats OR error-rate threshold.
     * - JMBE AMBE path mutes on max repeats only (error-rate muting is not applied in AMBE synth path). */
    if (mbe_should_mute_speech(cur_mp)) {
        /* Muted frames output comfort noise while preserving model progression. */
        mbe_synthesizeComfortNoisef(aout_buf);
        return;
    }

    /* Algorithm #117: Generate 256 white noise samples FIRST (JMBE-compatible)
     * This buffer is used for both phase calculation and unvoiced synthesis */
    float noise_buffer[256];
    mbe_generate_noise_with_overlap(noise_buffer, &cur_mp->noiseSeed, cur_mp->noiseOverlap);

    /* Initialize output buffer to zero */
    memset(aout_buf, 0, N * sizeof(float));

    /* eq 128 and 129: Handle different L values between frames */
    int maxl = mbe_reconcile_speech_model_lengths(cur_mp, prev_mp);

    /* Update phase from eq 139, 140
     * JMBE-compatible: use noise_buffer[l] for phase randomization instead of separate RNG */
    mbe_update_speech_phases(cur_mp, prev_mp, noise_buffer, mbe_count_unvoiced_bands(cur_mp), N);

    /* Synthesize voiced components
     * Use phase/amplitude interpolation (Algorithms #134-138) for low harmonics
     * when pitch is stable, otherwise use windowed oscillator approach
     */
    mbe_render_voiced_speech(aout_buf, cur_mp, prev_mp, maxl, N);

    /* Synthesize unvoiced components using FFT method (JMBE Algorithms #117-126)
     * Use the same noise buffer that was used for phase calculation */
    mbe_fft_plan* plan = mbe_get_fft_plan();
    if (plan) {
        mbe_synthesizeUnvoicedFFTWithNoise(aout_buf, cur_mp, prev_mp, plan, noise_buffer);
    }

    /* Match JMBE float-path soft clipping semantics for synthesized speech. */
    mbe_clipFloatBuffer(aout_buf, N);
}

void
mbe_synthesizeSpeechWithPreEnhRm0f(float* aout_buf, mbe_parms* cur_mp, mbe_parms* prev_mp, float rm0) {
    mbe_synthesizeSpeechCore(aout_buf, cur_mp, prev_mp, 1, rm0);
}

void
mbe_synthesizeSpeechf(float* aout_buf, mbe_parms* cur_mp, mbe_parms* prev_mp) {
    mbe_synthesizeSpeechCore(aout_buf, cur_mp, prev_mp, 0, 0.0f);
}

/**
 * @brief Synthesize one speech frame into 160 16-bit samples at 8 kHz.
 * @param aout_buf Output buffer of 160 16-bit samples.
 * @param cur_mp   Current parameter set.
 * @param prev_mp  Previous parameter set.
 */
void
mbe_synthesizeSpeech(short* aout_buf, mbe_parms* cur_mp, mbe_parms* prev_mp) {
    float float_buf[160];

    if (MBE_UNLIKELY(!aout_buf)) {
        return;
    }
    mbe_synthesizeSpeechf(float_buf, cur_mp, prev_mp);
    mbe_floattoshort(float_buf, aout_buf);
}

/**
 * @brief Convert 160 float samples to clipped/scaled 16-bit PCM.
 * @param float_buf Input 160 float samples.
 * @param aout_buf  Output 160 16-bit samples.
 */
/*
 * Runtime-dispatched float->short conversion with SIMD specializations.
 * Keeps public API unchanged while selecting the best implementation at runtime.
 */
/**
 * @brief Portable scalar fallback for float→int16 conversion.
 * @param float_buf Input 160 float samples.
 * @param aout_buf  Output 160 int16 samples.
 */
static inline float
mbe_scaleAndClipFloatSampleForShort(float sample) {
    const float again = 7.0f;
    const float max_amplitude = 32767.0f * 0.95f; /* ~31128.65 */
    uint32_t sample_bits;
    memcpy(&sample_bits, &sample, sizeof(sample_bits));

    const uint32_t abs_bits = sample_bits & 0x7FFFFFFFu;
    if (abs_bits > 0x7F800000u) {
        return 0.0f;
    }
    if (abs_bits == 0x7F800000u) {
        return ((sample_bits & 0x80000000u) != 0u) ? -max_amplitude : max_amplitude;
    }

    float audio = again * sample;
    if (audio > max_amplitude) {
#ifdef MBE_DEBUG
        fprintf(stderr, "audio clip: %f\n", audio);
#endif
        return max_amplitude;
    }
    if (audio < -max_amplitude) {
#ifdef MBE_DEBUG
        fprintf(stderr, "audio clip: %f\n", audio);
#endif
        return -max_amplitude;
    }
    return audio;
}

#if defined(MBELIB_ENABLE_SIMD)
static inline int
mbe_floatSampleIsNonFinite(float sample) {
    uint32_t sample_bits;
    memcpy(&sample_bits, &sample, sizeof(sample_bits));

    return (sample_bits & 0x7FFFFFFFu) >= 0x7F800000u;
}

static int
mbe_floatBufferHasNonFinite(const float* float_buf) {
    for (int i = 0; i < 160; i++) {
        if (mbe_floatSampleIsNonFinite(float_buf[i])) {
            return 1;
        }
    }
    return 0;
}

static void
mbe_floattoshort_scalar(const float* restrict float_buf, short* restrict aout_buf) {
    for (int i = 0; i < 160; i++) {
        aout_buf[i] = (short)mbe_scaleAndClipFloatSampleForShort(float_buf[i]);
    }
}

/**
 * @brief SSE2 specialization for float→int16 conversion.
 */
#if defined(MBE_SIMD_TARGET_SSE2)
static void
mbe_floattoshort_sse2(const float* restrict float_buf, short* restrict aout_buf) {
    /* JMBE-compatible soft clipping at 95% of maximum amplitude */
    const __m128 vscale = _mm_set1_ps(7.0f);
    const __m128 vmaxv = _mm_set1_ps(32767.0f * 0.95f);
    const __m128 vminv = _mm_set1_ps(-32767.0f * 0.95f);
    for (int i = 0; i < 160; i += 8) {
        __m128 a = _mm_mul_ps(_mm_loadu_ps(float_buf + i), vscale);
        __m128 b = _mm_mul_ps(_mm_loadu_ps(float_buf + i + 4), vscale);
        a = _mm_min_ps(_mm_max_ps(a, vminv), vmaxv);
        b = _mm_min_ps(_mm_max_ps(b, vminv), vmaxv);
        __m128i ia = _mm_cvttps_epi32(a);
        __m128i ib = _mm_cvttps_epi32(b);
        __m128i packed = _mm_packs_epi32(ia, ib);
        _mm_storeu_si128((__m128i*)(aout_buf + i), packed);
    }
}
#endif

/**
 * @brief NEON specialization for float→int16 conversion.
 */
#if defined(MBE_SIMD_TARGET_NEON)
static void
mbe_floattoshort_neon(const float* restrict float_buf, short* restrict aout_buf) {
    /* JMBE-compatible soft clipping at 95% of maximum amplitude */
    const float32x4_t vscale = vdupq_n_f32(7.0f);
    const float32x4_t vmaxv = vdupq_n_f32(32767.0f * 0.95f);
    const float32x4_t vminv = vdupq_n_f32(-32767.0f * 0.95f);
    for (int i = 0; i < 160; i += 8) {
        float32x4_t a = vmulq_f32(vld1q_f32(float_buf + i), vscale);
        float32x4_t b = vmulq_f32(vld1q_f32(float_buf + i + 4), vscale);
        a = vminq_f32(vmaxq_f32(a, vminv), vmaxv);
        b = vminq_f32(vmaxq_f32(b, vminv), vmaxv);
        int32x4_t ia = vcvtq_s32_f32(a);
        int32x4_t ib = vcvtq_s32_f32(b);
        int16x4_t na = vqmovn_s32(ia);
        int16x4_t nb = vqmovn_s32(ib);
        int16x8_t packed = vcombine_s16(na, nb);
        vst1q_s16(aout_buf + i, packed);
    }
}
#endif

typedef void (*mbe_floattoshort_fn)(const float* restrict, short* restrict);
/*
 * Keep dispatch state thread-local so first-use initialization has no cross-thread
 * data races and still amortizes probe cost to one-time per thread.
 */
static MBE_THREAD_LOCAL mbe_floattoshort_fn mbe_floattoshort_impl = NULL; /**< Runtime-selected impl pointer. */

/**
 * @brief Initialize conversion dispatch for the compiled target ISA.
 */
static void
mbe_init_runtime_dispatch(void) {
    if (mbe_floattoshort_impl) {
        return;
    }
    /* Choose implementation first, then publish once. */
    mbe_floattoshort_fn impl = mbe_floattoshort_scalar;

#if defined(MBE_ARCH_AARCH64)
    /* NEON is mandatory on AArch64, including ARM64EC. */
#if defined(MBE_SIMD_TARGET_NEON)
    impl = mbe_floattoshort_neon;
#endif
#elif defined(MBE_ARCH_X86_64)
    /* SSE2 is guaranteed on x86_64 */
#if defined(MBE_SIMD_TARGET_SSE2)
    impl = mbe_floattoshort_sse2;
#endif
#elif defined(MBE_ARCH_X86_32)
    /*
     * 32-bit x86 only compiles the SSE2 specialization when the build targets
     * SSE2 explicitly (for example /arch:SSE2 or -msse2).
     */
#if defined(MBE_SIMD_TARGET_SSE2)
    impl = mbe_floattoshort_sse2;
#endif
#elif defined(MBE_SIMD_TARGET_NEON)
    /* Assume NEON if building with NEON intrinsics */
    impl = mbe_floattoshort_neon;
#endif
    mbe_floattoshort_impl = impl;
}

void
mbe_floattoshort(const float* restrict float_buf, short* restrict aout_buf) {
    if (MBE_UNLIKELY(!float_buf || !aout_buf)) {
        return;
    }
    if (MBE_UNLIKELY(mbe_floatBufferHasNonFinite(float_buf))) {
        mbe_floattoshort_scalar(float_buf, aout_buf);
        return;
    }
    if (MBE_UNLIKELY(!mbe_floattoshort_impl)) {
        mbe_init_runtime_dispatch();
    }
    mbe_floattoshort_impl(float_buf, aout_buf);
}

#else  /* MBELIB_ENABLE_SIMD not set: keep scalar implementation */
void
mbe_floattoshort(const float* restrict float_buf, short* restrict aout_buf) {
    if (MBE_UNLIKELY(!float_buf || !aout_buf)) {
        return;
    }
    for (int i = 0; i < 160; i++) {
        aout_buf[i] = (short)mbe_scaleAndClipFloatSampleForShort(float_buf[i]);
    }
}
#endif /* MBELIB_ENABLE_SIMD */
