// SPDX-License-Identifier: GPL-2.0-or-later
/*
 * Copyright (C) 2025 by arancormonk <180709949+arancormonk@users.noreply.github.com>
 */

/**
 * @file
 * @brief Internal helpers for AMBE 3600x{2400,2450} ECC and demodulation.
 *
 * Declares common routines used by both AMBE 3600x2400 and 3600x2450
 * implementations to correct C0 with Golay24-compatible behavior
 * (Golay(23,12) + parity bit handling), demodulate C1, and extract the
 * 49-bit parameter vector.
 */

#ifndef MBELIB_NEO_INTERNAL_AMBE_COMMON_H
#define MBELIB_NEO_INTERNAL_AMBE_COMMON_H

#include "mbelib-neo/mbelib.h"

/**
 * @brief Correct C0 for AMBE 3600x{2400,2450} with Golay24 parity behavior.
 *
 * Applies Golay(23,12) decoding to `fr[0][1..23]` and then applies
 * Golay24 parity-bit correction on `fr[0][0]` when the protected
 * 23-bit codeword has zero syndrome.
 *
 * @param fr AMBE frame as 4x24 bitplanes (modified).
 * @return Number of corrected bit errors in C0.
 */
int mbe_eccAmbe3600C0_common(char fr[4][24]);
int mbe_eccAmbe3600C0Soft_common(mbe_soft_bit fr[4][24]);

/**
 * @brief Demodulate AMBE 3600x{2400,2450} C1 in-place.
 *
 * Uses a pseudo-random sequence derived from the C0 payload to remove
 * the interleaving/modulation applied to C1.
 *
 * @param fr AMBE frame as 4x24 bitplanes (modified).
 */
void mbe_demodulateAmbe3600Data_common(char fr[4][24]);
void mbe_demodulateAmbe3600DataSoft_common(mbe_soft_bit fr[4][24]);

/**
 * @brief Extract 49 parameter bits from C0..C3 with ECC.
 *
 * Copies C0, demodulates C1 and applies Golay(23,12), and copies C2/C3
 * into the 49-bit output parameter vector.
 *
 * @param fr     AMBE frame as 4x24 bitplanes (modified by demodulation).
 * @param out49  Output parameter bits (49 entries).
 * @return Number of corrected bit errors in protected fields.
 */
int mbe_eccAmbe3600Data_common(char fr[4][24], char* out49);
int mbe_eccAmbe3600DataSoft_common(mbe_soft_bit fr[4][24], char* out49);

/**
 * @brief Initialize AMBE parameter state to JMBE-compatible defaults.
 *
 * AMBE in JMBE starts from fundamental W124 (w0=(PI/32)*2*PI, L=15) with
 * unvoiced bands and unit spectral amplitudes. This helper mirrors that
 * state for AMBE-family decode paths.
 *
 * @param cur_mp  Output current parameter state.
 * @param prev_mp Output previous parameter state.
 * @param prev_mp_enhanced Output enhanced previous parameter state.
 */
void mbe_initAmbeParms_common(mbe_parms* cur_mp, mbe_parms* prev_mp, mbe_parms* prev_mp_enhanced);

/**
 * @brief Set AMBE parameters to JMBE-style ERASURE defaults (W120).
 *
 * Mirrors AMBEModelParameters.setDefaults(FrameType.ERASURE): w0=0,
 * L=9, unvoiced bands, unit amplitudes, zero gain term. Keeps phase and
 * unvoiced overlap/noise state from `state_src` to preserve synthesizer
 * continuity behavior.
 *
 * @param mp Target parameter set to rewrite.
 * @param state_src Source state for phase/noise continuity (may be NULL).
 */
void mbe_setAmbeErasureParms_common(mbe_parms* mp, const mbe_parms* state_src);

/**
 * @brief Ensure AMBE parameter state is initialized with AMBE defaults.
 *
 * If the previous state does not appear to be AMBE-initialized, this
 * routine resets all three state structs via mbe_initAmbeParms_common().
 *
 * @param cur_mp  In/out current parameter state.
 * @param prev_mp In/out previous parameter state.
 * @param prev_mp_enhanced In/out enhanced previous parameter state.
 */
void mbe_ensureAmbeDefaults_common(mbe_parms* cur_mp, mbe_parms* prev_mp, mbe_parms* prev_mp_enhanced);

#endif /* MBELIB_NEO_INTERNAL_AMBE_COMMON_H */
