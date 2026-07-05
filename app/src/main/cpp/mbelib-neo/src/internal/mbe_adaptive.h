// SPDX-License-Identifier: GPL-2.0-or-later
/*
 * Copyright (C) 2025 by arancormonk <180709949+arancormonk@users.noreply.github.com>
 *
 * Internal header for adaptive smoothing algorithms.
 * Implements JMBE Algorithms #111-116 for error-based audio smoothing.
 */

#ifndef MBEINT_MBE_ADAPTIVE_H
#define MBEINT_MBE_ADAPTIVE_H

#include <stdint.h>

#include "mbelib-neo/mbelib.h"

/* Algorithm constants from JMBE specification */

/** Default local energy value (Algorithm #111). */
#define MBE_DEFAULT_LOCAL_ENERGY        75000.0f

/** Minimum local energy threshold (Algorithm #111). */
#define MBE_MIN_LOCAL_ENERGY            10000.0f

/** Energy smoothing coefficient alpha (Algorithm #111). */
#define MBE_ENERGY_SMOOTH_ALPHA         0.95f

/** Energy smoothing coefficient beta (Algorithm #111). */
#define MBE_ENERGY_SMOOTH_BETA          0.05f

/** Default amplitude threshold (Algorithm #115). */
#define MBE_DEFAULT_AMPLITUDE_THRESHOLD 20480

/** Error rate threshold for smoothing entry (Algorithm #112). */
#define MBE_ERROR_THRESHOLD_ENTRY       0.0125f

/** Low error rate threshold (Algorithm #112). */
#define MBE_ERROR_THRESHOLD_LOW         0.005f

/** Adaptive gain constant (Algorithm #112). */
#define MBE_ADAPTIVE_GAIN               45.255f

/** Adaptive exponent constant (Algorithm #112). */
#define MBE_ADAPTIVE_EXPONENT           277.26f

/** Alternative adaptive multiplier (Algorithm #112). */
#define MBE_ADAPTIVE_ALT                1.414f

/** Amplitude penalty per error (Algorithm #115). */
#define MBE_AMPLITUDE_PENALTY_PER_ERROR 300

/** Amplitude base constant (Algorithm #115). */
#define MBE_AMPLITUDE_BASE              6000

/**
 * @brief Apply adaptive smoothing with caller-supplied pre-enhancement RM0.
 *
 * JMBE Algorithm #111 uses local energy derived from pre-enhanced spectral
 * amplitudes. Internal decode/synthesize paths pass that value explicitly so
 * the handoff cannot retain caller stack addresses.
 *
 * @param cur_mp Current frame parameters (modified in-place).
 * @param prev_mp Previous frame parameters (for local energy).
 * @param rm0 Sum of squared pre-enhancement amplitudes.
 */
void mbe_applyAdaptiveSmoothingWithRm0(mbe_parms* cur_mp, const mbe_parms* prev_mp, float rm0);

/**
 * @brief Apply spectral amplitude enhancement and return pre-enhancement RM0.
 * @param cur_mp In/out parameter set to enhance.
 * @return Sum of squared amplitudes before enhancement.
 */
float mbe_spectralAmpEnhanceWithRm0(mbe_parms* cur_mp);

/**
 * @brief Synthesize speech using a captured pre-enhancement RM0 value.
 * @param aout_buf Output buffer of 160 float samples.
 * @param cur_mp Current frame parameters.
 * @param prev_mp Previous enhanced frame parameters.
 * @param rm0 Sum of squared pre-enhancement amplitudes.
 */
void mbe_synthesizeSpeechWithPreEnhRm0f(float* aout_buf, mbe_parms* cur_mp, mbe_parms* prev_mp, float rm0);

/**
 * @brief Seed the comfort-noise RNG used by mbe_synthesizeComfortNoisef().
 *
 * Uses Java Random-compatible 48-bit state initialization.
 *
 * @param seed Thread-local seed value (0 maps to a fixed non-zero default).
 */
void mbe_seedComfortNoiseRng(uint32_t seed);

#endif /* MBEINT_MBE_ADAPTIVE_H */
