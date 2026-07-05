// SPDX-License-Identifier: GPL-2.0-or-later
/*
 * Copyright (C) 2025 by arancormonk <180709949+arancormonk@users.noreply.github.com>
 */

/**
 * @file
 * @brief Internal helpers shared by AMBE 3600x2400 and 3600x2450 paths.
 *
 * Provides common ECC correction for C0, demodulation of C1 using a
 * pseudo-random sequence derived from C0, and packing of 49 AMBE
 * parameter bits from the four bitplanes.
 */

#include "ambe_common.h"
#include <math.h>
#include <stdint.h>
#include <string.h>
#include "mbe_adaptive.h"
#include "mbelib-neo/mbelib.h"

int
mbe_eccAmbe3600C0_common(char fr[4][24]) {
    int j, errs;
    char in[23], out[23];
    for (j = 0; j < 23; j++) {
        in[j] = fr[0][j + 1];
    }
    errs = mbe_golay2312(in, out);
    for (j = 0; j < 23; j++) {
        fr[0][j + 1] = out[j];
    }
    /* JMBE C0 uses Golay24: when the protected 23-bit codeword has no
     * syndrome, enforce even parity by correcting the extra parity bit. */
    if (errs == 0) {
        int ones = 0;
        for (j = 0; j < 24; j++) {
            ones += (fr[0][j] & 1);
        }
        if ((ones & 1) != 0) {
            fr[0][0] ^= 1;
            errs = 1;
        }
    }
    return errs;
}

int
mbe_eccAmbe3600C0Soft_common(mbe_soft_bit fr[4][24]) {
    int j, errs;
    mbe_soft_bit in[23];
    char out[23];

    for (j = 0; j < 23; j++) {
        in[j] = fr[0][j + 1];
    }
    errs = mbe_golay2312Soft(in, out);
    for (j = 0; j < 23; j++) {
        fr[0][j + 1].bit = (uint8_t)(out[j] & 1);
    }

    if (errs == 0) {
        int ones = 0;
        for (j = 0; j < 24; j++) {
            ones += (int)(fr[0][j].bit & 1u);
        }
        if ((ones & 1) != 0) {
            fr[0][0].bit ^= 1u;
            errs = 1;
        }
    }
    return errs;
}

void
mbe_demodulateAmbe3600Data_common(char fr[4][24]) {
    int i, j, k;
    unsigned short pr[115];
    unsigned short foo = 0;

    /* create pseudo-random modulator */
    for (i = 23; i >= 12; i--) {
        foo <<= 1;
        foo |= fr[0][i];
    }
    pr[0] = (unsigned short)(16 * foo);
    for (i = 1; i < 24; i++) {
        pr[i] = (unsigned short)((173 * pr[i - 1]) + 13849 - (65536 * (((173 * pr[i - 1]) + 13849) / 65536)));
    }
    for (i = 1; i < 24; i++) {
        pr[i] = (unsigned short)(pr[i] / 32768);
    }

    /* demodulate fr with pr */
    k = 1;
    for (j = 22; j >= 0; j--) {
        fr[1][j] = (char)((fr[1][j]) ^ pr[k]);
        k++;
    }
}

void
mbe_demodulateAmbe3600DataSoft_common(mbe_soft_bit fr[4][24]) {
    int i, j, k;
    unsigned short pr[115];
    unsigned short foo = 0;

    for (i = 23; i >= 12; i--) {
        foo <<= 1;
        foo |= (unsigned short)(fr[0][i].bit & 1u);
    }
    pr[0] = (unsigned short)(16 * foo);
    for (i = 1; i < 24; i++) {
        pr[i] = (unsigned short)((173 * pr[i - 1]) + 13849 - (65536 * (((173 * pr[i - 1]) + 13849) / 65536)));
    }
    for (i = 1; i < 24; i++) {
        pr[i] = (unsigned short)(pr[i] / 32768);
    }

    k = 1;
    for (j = 22; j >= 0; j--) {
        fr[1][j].bit = (uint8_t)((fr[1][j].bit & 1u) ^ (uint8_t)pr[k]);
        k++;
    }
}

int
mbe_eccAmbe3600Data_common(char fr[4][24], char* out49) {
    int j, errs;
    char *ambe, gin[24], gout[24];
    ambe = out49;
    /* just copy C0 */
    for (j = 23; j > 11; j--) {
        *ambe = fr[0][j];
        ambe++;
    }
    /* ecc and copy C1 */
    for (j = 0; j < 23; j++) {
        gin[j] = fr[1][j];
    }
    errs = mbe_golay2312(gin, gout);
    for (j = 22; j > 10; j--) {
        *ambe = gout[j];
        ambe++;
    }
    /* just copy C2 */
    for (j = 10; j >= 0; j--) {
        *ambe = fr[2][j];
        ambe++;
    }
    /* just copy C3 */
    for (j = 13; j >= 0; j--) {
        *ambe = fr[3][j];
        ambe++;
    }
    return errs;
}

int
mbe_eccAmbe3600DataSoft_common(mbe_soft_bit fr[4][24], char* out49) {
    int j, errs;
    char *ambe, gout[23];
    mbe_soft_bit gin[23];

    ambe = out49;
    for (j = 23; j > 11; j--) {
        *ambe = (char)(fr[0][j].bit & 1u);
        ambe++;
    }

    for (j = 0; j < 23; j++) {
        gin[j] = fr[1][j];
    }
    errs = mbe_golay2312Soft(gin, gout);
    for (j = 22; j > 10; j--) {
        *ambe = gout[j];
        ambe++;
    }

    for (j = 10; j >= 0; j--) {
        *ambe = (char)(fr[2][j].bit & 1u);
        ambe++;
    }
    for (j = 13; j >= 0; j--) {
        *ambe = (char)(fr[3][j].bit & 1u);
        ambe++;
    }
    return errs;
}

void
mbe_initAmbeParms_common(mbe_parms* cur_mp, mbe_parms* prev_mp, mbe_parms* prev_mp_enhanced) {
    if (!cur_mp || !prev_mp || !prev_mp_enhanced) {
        return;
    }

    prev_mp->swn = 0;
    prev_mp->tonePhase = 0;
    /* JMBE AMBEFundamentalFrequency.W124: constructor uses (frequency * 2*PI), where frequency is PI/32. */
    prev_mp->w0 = (float)((M_PI / 32.0) * (2.0 * M_PI));
    prev_mp->L = 15;
    prev_mp->K = 0;
    prev_mp->gamma = 0.0f;

    for (int l = 0; l <= 56; l++) {
        prev_mp->Ml[l] = 1.0f;
        prev_mp->Vl[l] = 0;
        prev_mp->log2Ml[l] = 0.0f; /* log2(1.0) */
        prev_mp->PHIl[l] = 0.0f;
        /* JMBE previous-phase arrays start at 0.0f. */
        prev_mp->PSIl[l] = 0.0f;
    }

    prev_mp->localEnergy = MBE_DEFAULT_LOCAL_ENERGY;
    prev_mp->amplitudeThreshold = MBE_DEFAULT_AMPLITUDE_THRESHOLD;
    prev_mp->errorRate = 0.0f;
    prev_mp->errorCountTotal = 0;
    prev_mp->errorCount4 = 0;

    prev_mp->repeatCount = 0;
    prev_mp->mutingThreshold = MBE_MUTING_THRESHOLD_AMBE;

    prev_mp->noiseSeed = -1.0f;
    memset(prev_mp->noiseOverlap, 0, sizeof(prev_mp->noiseOverlap));
    memset(prev_mp->previousUw, 0, sizeof(prev_mp->previousUw));

    *cur_mp = *prev_mp;
    *prev_mp_enhanced = *prev_mp;
}

void
mbe_setAmbeErasureParms_common(mbe_parms* mp, const mbe_parms* state_src) {
    if (!mp) {
        return;
    }

    const mbe_parms* continuity = state_src ? state_src : mp;

    mp->swn = 0;
    mp->tonePhase = 0;
    mp->w0 = 0.0f; /* JMBE AMBEFundamentalFrequency.W120..W123 */
    mp->L = 9;
    mp->K = 0;
    mp->gamma = 0.0f;

    for (int l = 0; l <= 56; l++) {
        mp->Ml[l] = 1.0f;
        mp->Vl[l] = 0;
        mp->log2Ml[l] = 0.0f; /* log2(1.0) */
        mp->PHIl[l] = continuity->PHIl[l];
        mp->PSIl[l] = continuity->PSIl[l];
    }

    mp->localEnergy = MBE_DEFAULT_LOCAL_ENERGY;
    mp->amplitudeThreshold = MBE_DEFAULT_AMPLITUDE_THRESHOLD;

    mp->noiseSeed = continuity->noiseSeed;
    memcpy(mp->noiseOverlap, continuity->noiseOverlap, sizeof(mp->noiseOverlap));
    memcpy(mp->previousUw, continuity->previousUw, sizeof(mp->previousUw));
}

void
mbe_ensureAmbeDefaults_common(mbe_parms* cur_mp, mbe_parms* prev_mp, mbe_parms* prev_mp_enhanced) {
    if (!prev_mp) {
        return;
    }

    if (fabsf(prev_mp->mutingThreshold - MBE_MUTING_THRESHOLD_AMBE) > 1e-6f) {
        mbe_initAmbeParms_common(cur_mp, prev_mp, prev_mp_enhanced);
    }
}
