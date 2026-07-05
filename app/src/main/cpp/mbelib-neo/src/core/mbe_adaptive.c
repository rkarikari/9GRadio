// SPDX-License-Identifier: GPL-2.0-or-later
/*
 * Copyright (C) 2025 by arancormonk <180709949+arancormonk@users.noreply.github.com>
 *
 * Adaptive smoothing implementation.
 * Implements JMBE Algorithms #111-116 for error-based audio quality improvement.
 */

#include <math.h>
#include <stdint.h>

#include "mbe_adaptive.h"
#include "mbe_compiler.h"
#include "mbe_validation.h"
#include "mbelib-neo/mbelib.h"

/* Thread-local storage for comfort noise RNG to avoid cross-thread interference.
 * JMBE uses per-synthesizer java.util.Random instances. */
#define MBE_JAVA_RNG_MULT      0x5DEECE66DULL
#define MBE_JAVA_RNG_ADD       0xBULL
#define MBE_JAVA_RNG_MASK      ((1ULL << 48) - 1ULL)
#define MBE_JAVA_RNG_INIT_SEED 0x12345678ULL
#if defined(__FLT_MAX__)
#define MBE_FLOAT_MAX __FLT_MAX__
#else
#include <float.h>
#define MBE_FLOAT_MAX FLT_MAX
#endif
static MBE_THREAD_LOCAL uint64_t mbe_comfort_noise_seed48 = 0;
static MBE_THREAD_LOCAL int mbe_comfort_noise_seeded = 0;

void
mbe_seedComfortNoiseRng(uint32_t seed) {
    if (seed == 0u) {
        seed = 0x6d25357bu;
    }
    mbe_comfort_noise_seed48 = (((uint64_t)seed) ^ MBE_JAVA_RNG_MULT) & MBE_JAVA_RNG_MASK;
    mbe_comfort_noise_seeded = 1;
}

/**
 * @brief Java Random-compatible next(bits) generator for comfort noise.
 *
 * Replicates java.util.Random's 48-bit LCG:
 *   seed = (seed * 0x5DEECE66D + 0xB) & ((1<<48)-1)
 *   return seed >>> (48 - bits)
 *
 * @param bits Number of high-order bits requested (1..32).
 * @return Pseudorandom value with the requested number of bits.
 */
static inline uint32_t
mbe_java_random_next_bits(int bits) {
    if (!mbe_comfort_noise_seeded) {
        mbe_comfort_noise_seed48 = (MBE_JAVA_RNG_INIT_SEED ^ MBE_JAVA_RNG_MULT) & MBE_JAVA_RNG_MASK;
        mbe_comfort_noise_seeded = 1;
    }

    mbe_comfort_noise_seed48 = (mbe_comfort_noise_seed48 * MBE_JAVA_RNG_MULT + MBE_JAVA_RNG_ADD) & MBE_JAVA_RNG_MASK;
    return (uint32_t)(mbe_comfort_noise_seed48 >> (48 - bits));
}

/**
 * @brief Check if adaptive smoothing is required based on error rates.
 *
 * Smoothing is required when error rate exceeds 1.25% or total errors exceed 4.
 *
 * @param mp Parameter set to check.
 * @return Non-zero if smoothing should be applied.
 */
int
mbe_requiresAdaptiveSmoothing(const mbe_parms* mp) {
    if (MBE_UNLIKELY(!mp)) {
        return 0;
    }
    return (mp->errorRate > MBE_ERROR_THRESHOLD_ENTRY) || (mp->errorCountTotal > 4);
}

/**
 * @brief Check if frame should be muted due to excessive errors.
 *
 * Uses the codec-specific muting threshold stored in mp->mutingThreshold.
 * IMBE uses 8.75% (0.0875), AMBE uses 9.6% (0.096).
 *
 * @param mp Parameter set to check.
 * @return Non-zero if frame should be muted.
 */
int
mbe_requiresMuting(const mbe_parms* mp) {
    if (MBE_UNLIKELY(!mp)) {
        return 0;
    }
    return mp->errorRate > mp->mutingThreshold;
}

/**
 * @brief Check if max repeat threshold has been exceeded.
 *
 * @param mp Parameter set to check.
 * @return Non-zero if repeatCount >= MBE_MAX_FRAME_REPEATS.
 */
int
mbe_isMaxFrameRepeat(const mbe_parms* mp) {
    if (MBE_UNLIKELY(!mp)) {
        return 0;
    }
    return mp->repeatCount >= MBE_MAX_FRAME_REPEATS;
}

/**
 * @brief Generate comfort noise for muted frames (float version).
 *
 * Generates low-level uniform white noise to fill gaps during frame muting.
 *
 * @param aout_buf Output buffer of 160 float samples.
 */
void
mbe_synthesizeComfortNoisef(float* aout_buf) {
    if (MBE_UNLIKELY(!aout_buf)) {
        return;
    }

    /* JMBE muted-noise model: uniform white noise in [-1, +1] with gain 0.003.
     * Translate to this library's float-domain scale (short path multiplies by 7). */
    const float gain = (0.003f * 32767.0f) / 7.0f;

    for (int i = 0; i < 160; i++) {
        /* JMBE parity: use Java Random-like 24-bit float generation. */
        float u = ((float)mbe_java_random_next_bits(24) / 16777216.0f) * 2.0f - 1.0f;
        aout_buf[i] = u * gain;
    }
}

/**
 * @brief Generate comfort noise for muted frames (16-bit version).
 *
 * @param aout_buf Output buffer of 160 16-bit samples.
 */
void
mbe_synthesizeComfortNoise(short* aout_buf) {
    if (MBE_UNLIKELY(!aout_buf)) {
        return;
    }

    float float_buf[160];
    mbe_synthesizeComfortNoisef(float_buf);

    /* Reuse float->short scaling so noise amplitude matches JMBE's 0.003 model. */
    mbe_floattoshort(float_buf, aout_buf);
}

static float
mbe_current_frame_rm0(const mbe_parms* cur_mp) {
    float rm0 = 0.0f;
    if (!cur_mp || !mbe_harmonic_count_is_valid(cur_mp->L)) {
        return 0.0f;
    }
    for (int l = 1; l <= cur_mp->L; l++) {
        rm0 += cur_mp->Ml[l] * cur_mp->Ml[l];
    }
    return rm0;
}

static float
mbe_smoothed_local_energy(float prev_energy, float rm0) {
    if (prev_energy < MBE_MIN_LOCAL_ENERGY) {
        prev_energy = MBE_DEFAULT_LOCAL_ENERGY;
    }

    float local_energy = MBE_ENERGY_SMOOTH_ALPHA * prev_energy + MBE_ENERGY_SMOOTH_BETA * rm0;
    if (local_energy < MBE_MIN_LOCAL_ENERGY) {
        local_energy = MBE_MIN_LOCAL_ENERGY;
    }
    return local_energy;
}

static float
mbe_adaptive_vm(float local_energy, float error_rate, int error_total, int error_count4) {
    if (error_rate <= MBE_ERROR_THRESHOLD_LOW && error_total <= 4) {
        return MBE_FLOAT_MAX;
    }

    /* x^(3/8) = (x^(1/8))^3, where x^(1/8) = sqrtf(sqrtf(sqrtf(x))). */
    float x8 = sqrtf(sqrtf(sqrtf(local_energy)));
    float energy = x8 * x8 * x8;
    if (error_rate <= MBE_ERROR_THRESHOLD_ENTRY && error_count4 == 0) {
        return (MBE_ADAPTIVE_GAIN * energy) / expf(MBE_ADAPTIVE_EXPONENT * error_rate);
    }
    return MBE_ADAPTIVE_ALT * energy;
}

static int
mbe_adaptive_amplitude_threshold(float error_rate, int error_total, int prev_threshold) {
    if (prev_threshold <= 0) {
        prev_threshold = MBE_DEFAULT_AMPLITUDE_THRESHOLD;
    }
    if (error_rate <= MBE_ERROR_THRESHOLD_LOW && error_total <= 6) {
        return MBE_DEFAULT_AMPLITUDE_THRESHOLD;
    }
    return MBE_AMPLITUDE_BASE - (MBE_AMPLITUDE_PENALTY_PER_ERROR * error_total) + prev_threshold;
}

/**
 * @brief Apply adaptive smoothing to parameters based on error rates.
 *
 * Implements JMBE Algorithms #111-116:
 * - Algorithm #111: Local energy tracking with IIR smoothing
 * - Algorithm #112: Adaptive threshold calculation
 * - Algorithm #113: Apply threshold to voicing decisions
 * - Algorithm #114: Calculate amplitude measure
 * - Algorithm #115: Calculate amplitude threshold
 * - Algorithm #116: Scale enhanced spectral amplitudes
 *
 * @param cur_mp Current frame parameters (modified in-place).
 * @param prev_mp Previous frame parameters (for local energy).
 * @param RM0 Current-frame spectral energy for Algorithm #111.
 */
static void
mbe_applyAdaptiveSmoothingCore(mbe_parms* cur_mp, const mbe_parms* prev_mp, float RM0) {
    float* M = cur_mp->Ml;
    int* V = cur_mp->Vl;
    int L = cur_mp->L;
    float errorRate = cur_mp->errorRate;
    int errorTotal = cur_mp->errorCountTotal;
    int errorCount4 = cur_mp->errorCount4;

    /* Algorithm #111: Calculate local energy with IIR smoothing */
    cur_mp->localEnergy = mbe_smoothed_local_energy(prev_mp->localEnergy, RM0);

    /* Algorithm #112: Calculate adaptive threshold VM */
    float VM = mbe_adaptive_vm(cur_mp->localEnergy, errorRate, errorTotal, errorCount4);

    /* Algorithm #113: Apply threshold to voicing decisions */
    for (int l = 1; l <= L; l++) {
        if (M[l] > VM) {
            V[l] = 1; /* Force voiced when amplitude exceeds threshold */
        }
    }

    /* Algorithm #114: Calculate amplitude measure */
    float Am = 0.0f;
    for (int l = 1; l <= L; l++) {
        Am += M[l];
    }

    /* Algorithm #115: Calculate amplitude threshold */
    int Tm = mbe_adaptive_amplitude_threshold(errorRate, errorTotal, prev_mp->amplitudeThreshold);
    cur_mp->amplitudeThreshold = Tm;

    /* Algorithm #116: Scale enhanced spectral amplitudes if exceeded */
    if (Am > (float)Tm && Am > 0.0f) {
        float scale = (float)Tm / Am;
        for (int l = 1; l <= L; l++) {
            M[l] *= scale;
        }
    }
}

void
mbe_applyAdaptiveSmoothingWithRm0(mbe_parms* cur_mp, const mbe_parms* prev_mp, float rm0) {
    if (MBE_UNLIKELY(!cur_mp || !prev_mp || !mbe_harmonic_count_is_valid(cur_mp->L)
                     || !mbe_harmonic_count_is_valid(prev_mp->L))) {
        return;
    }

    mbe_applyAdaptiveSmoothingCore(cur_mp, prev_mp, rm0);
}

void
mbe_applyAdaptiveSmoothing(mbe_parms* cur_mp, const mbe_parms* prev_mp) {
    if (MBE_UNLIKELY(!cur_mp || !prev_mp || !mbe_harmonic_count_is_valid(cur_mp->L)
                     || !mbe_harmonic_count_is_valid(prev_mp->L))) {
        return;
    }

    mbe_applyAdaptiveSmoothingCore(cur_mp, prev_mp, mbe_current_frame_rm0(cur_mp));
}
