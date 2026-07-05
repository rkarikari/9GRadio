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
 * @brief IMBE 7200x4400 parameter decode, ECC, and synthesis hooks.
 */

#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "imbe4400_internal.h"
#include "imbe7200x4400_const.h"
#include "mbe_adaptive.h"
#include "mbe_bitpack.h"
#include "mbe_compiler.h"
#include "mbe_result.h"
#include "mbe_validation.h"
#include "mbelib-neo/mbelib.h"

/**
 * @brief Thread-local cache for IMBE DCT cosine coefficients.
 *
 * Pre-computes the cosine terms used in the Ri inverse DCT and per-block
 * inverse DCT loops to eliminate repeated cosf() calls per frame.
 *
 * Ri DCT: cosf(M_PI * (m-1) * (i-0.5) / 6) for m=1..6, i=1..6
 * Per-block IDCT: cosf(M_PI * (k-1) * (j-0.5) / ji) for ji=1..10, j=1..ji, k=1..ji
 */
struct imbe_dct_cache {
    int inited;
    float ri_cos[7][7];         /* [m][i] for m=1..6, i=1..6 (index 0 unused) */
    float idct_cos[11][11][11]; /* [ji][j][k] for ji=1..10, j=1..ji, k=1..ji */
};

static MBE_THREAD_LOCAL struct imbe_dct_cache imbe_cache = {0};

/**
 * @brief Reset IMBE model state after excessive repeat headroom is exceeded.
 *
 * Matches JMBE IMBE behavior where repeat overflow falls back to a default
 * voice model instead of extending repeat/mute indefinitely. Per-frame error
 * metrics and synthesis continuity state are intentionally preserved.
 *
 * @param mp IMBE parameter state to reset.
 */
static void
imbe_reset_headroom_defaults(mbe_parms* mp) {
    if (!mp) {
        return;
    }

    mp->swn = 0;
    mp->tonePhase = 0;
    /* Match JMBE IMBEModelParameters.copy() overflow path:
     * setMBEFundamentalFrequency(IMBEFundamentalFrequency.DEFAULT), then rebuild model arrays. */
    mp->w0 = (float)((4.0 * M_PI) / (134.0 + 39.5));
    mp->L = (int)(0.9254 * (int)((M_PI / mp->w0) + 0.25));
    mp->K = 12;
    mp->gamma = 0.0f;

    for (int l = 0; l <= 56; l++) {
        mp->Vl[l] = 0;
        mp->Ml[l] = 1.0f;
        mp->log2Ml[l] = 0.0f;
    }

    mp->repeatCount = 0;
    mp->localEnergy = 75000.0f;
    mp->amplitudeThreshold = 20480;
    mp->mutingThreshold = MBE_MUTING_THRESHOLD_IMBE;
}

/**
 * @brief Initialize or return the thread-local IMBE DCT cache.
 *
 * Fills the cosine tables on first use. Because the cache is thread-local,
 * no locking is required.
 *
 * @return Pointer to the initialized cache.
 */
static struct imbe_dct_cache*
imbe_get_dct_cache(void) {
    if (imbe_cache.inited) {
        return &imbe_cache;
    }

    /* Fill Ri DCT cosine table: cosf(M_PI * (m-1) * (i-0.5) / 6) */
    for (int m = 1; m <= 6; m++) {
        for (int i = 1; i <= 6; i++) {
            imbe_cache.ri_cos[m][i] = cosf((M_PI * (float)(m - 1) * ((float)i - 0.5f)) / 6.0f);
        }
    }

    /* Fill per-block IDCT cosine table: cosf(M_PI * (k-1) * (j-0.5) / ji) */
    for (int ji = 1; ji <= 10; ji++) {
        for (int j = 1; j <= ji; j++) {
            for (int k = 1; k <= ji; k++) {
                imbe_cache.idct_cos[ji][j][k] = cosf((M_PI * (float)(k - 1) * ((float)j - 0.5f)) / (float)ji);
            }
        }
    }

    imbe_cache.inited = 1;
    return &imbe_cache;
}

static int
imbe_decode_fundamental(const char* imbe_d, mbe_parms* cur_mp, int* out_L9) {
    static const unsigned b0_indices[] = {0u, 1u, 2u, 3u, 4u, 5u, 85u, 86u};
    int b0 = mbe_bits_by_index_to_int(imbe_d, b0_indices, sizeof(b0_indices) / sizeof(b0_indices[0]));
    if (b0 > 207) {
#ifdef IMBE_DEBUG
        if ((b0 >= 216) && (b0 <= 219)) {
            fprintf(stderr, "Silence\n");
        } else {
            fprintf(stderr, "Invalid fundamental frequency\n");
        }
#endif
        return 1;
    }

    cur_mp->w0 = ((float)(4 * M_PI) / (float)((float)b0 + 39.5));

    int L = (int)(0.9254 * (int)((M_PI / cur_mp->w0) + 0.25));
    if ((L > 56) || (L < 9)) {
#ifdef IMBE_DEBUG
        fprintf(stderr, "invalid L: %i\n", L);
#endif
        return 1;
    }
    cur_mp->L = L;
    *out_L9 = L - 9;

    if (L < 37) {
        cur_mp->K = (int)((float)(L + 2) / (float)3);
    } else {
        cur_mp->K = 12;
    }

#ifdef IMBE_DEBUG
    fprintf(stderr, "b0:%i L:%i K:%i\n", b0, L, cur_mp->K);
#endif
    return 0;
}

static void
imbe_read_bit_layout(const char* imbe_d, int L9, char bb[58][12]) {
    const int* bo1 = bo[L9][0];
    const int* bo2 = bo1 + 1;
    for (int i = 6; i < 85; i++) {
        bb[*bo1][*bo2] = imbe_d[i];
#ifdef IMBE_DEBUG
        fprintf(stderr, "bo1: %i,bo2: %i, ", *bo1, *bo2);
#endif
        bo1 += 2;
        bo2 += 2;
    }
}

static void
imbe_decode_voicing(mbe_parms* cur_mp, char bb[58][12]) {
    int j = 1;
    int k = cur_mp->K - 1;
    for (int i = 1; i <= cur_mp->L; i++) {
        /* Cast via unsigned to avoid sign-extension when 'char' is signed */
        cur_mp->Vl[i] = (int)(unsigned char)bb[1][k];
        if (j == 3) {
            j = 1;
            if (k > 0) {
                k--;
            } else {
                k = 0;
            }
        } else {
            j++;
        }
    }
}

static void
imbe_decode_gains(char bb[58][12], int L9, float Gm[7]) {
    int b2 = mbe_bits_descending_to_int(bb[2], 5, 0);
    Gm[1] = B2[b2];
#ifdef IMBE_DEBUG
    fprintf(stderr, "b2: %i g1: %e\n", b2, Gm[1]);
#endif

    const float* ba1 = ba[L9][0];
    const float* ba2 = ba1 + 1;
    for (int i = 2; i < 7; i++) {
        int bm = mbe_bits_descending_to_int(bb[i + 1], (int)*ba1 - 1, 0);
        Gm[i] = (*ba2 * ((float)bm - exp2f((*ba1) - 1.0f) + 0.5f));
#ifdef IMBE_DEBUG
        fprintf(stderr, "G%i: %e, %i, ba1: %e, ba2: %e\n", i, Gm[i], bm, *ba1, *ba2);
#endif
        ba1 += 2;
        ba2 += 2;
    }
}

static void
imbe_compute_ri(const float Gm[7], float Ri[7]) {
    const struct imbe_dct_cache* cache = imbe_get_dct_cache();
    for (int i = 1; i <= 6; i++) {
        float sum = 0;
        for (int m = 1; m <= 6; m++) {
            int am = (m == 1) ? 1 : 2;
            sum = sum + ((float)am * Gm[m] * cache->ri_cos[m][i]);
#ifdef IMBE_DEBUG
            fprintf(stderr, "sum: %e ", sum);
#endif
        }
        Ri[i] = sum;
#ifdef IMBE_DEBUG
        fprintf(stderr, "R%i: %e\n", i, Ri[i]);
#endif
    }
#ifdef IMBE_DEBUG
    fprintf(stderr, "R1: %e\n", Ri[1]);
#endif
}

static void
imbe_decode_hoc_coefficients(char bb[58][12], int L9, const float Ri[7], float Cik[7][11]) {
    int m = 8;
    for (int i = 1; i <= 6; i++) {
        Cik[i][1] = Ri[i];
        for (int k = 2; k <= ImbeJi[L9][i - 1]; k++) {
            int Bm = hoba[L9][m - 8];
            if (Bm <= 0) {
                Cik[i][k] = 0;
            } else {
                int bm = mbe_bits_descending_to_int(bb[m], Bm - 1, 0);
                Cik[i][k] = ((quantstep[Bm - 1] * standdev[k - 2]) * (((float)bm - exp2f((float)Bm - 1.0f)) + 0.5f));
            }
            m++;
        }
    }
}

static void
imbe_inverse_dct_tl(float Cik[7][11], int L9, float Tl[57]) {
    const struct imbe_dct_cache* cache = imbe_get_dct_cache();
    int l = 1;
    for (int i = 1; i <= 6; i++) {
        int ji = ImbeJi[L9][i - 1];
        for (int j = 1; j <= ji; j++) {
            float sum = 0;
            for (int k = 1; k <= ji; k++) {
                int ak = (k == 1) ? 1 : 2;
                sum = sum + ((float)ak * Cik[i][k] * cache->idct_cos[ji][j][k]);
            }
            Tl[l] = sum;
            l++;
        }
    }
#ifdef IMBE_DEBUG
    fprintf(stderr, "T1: %e\n", Tl[1]);
#endif
}

static float
imbe_spectral_rho(int L) {
    if (L <= 15) {
        return 0.4f;
    }
    if (L <= 24) {
        return (0.03f * (float)L) - 0.05f;
    }
    return 0.7f;
}

static int
imbe_clamp_harmonic_count(int L) {
    if (L < MBE_MIN_HARMONIC_BANDS) {
        return MBE_MIN_HARMONIC_BANDS;
    }
    if (L > MBE_MAX_HARMONIC_BANDS) {
        return MBE_MAX_HARMONIC_BANDS;
    }
    return L;
}

static void
imbe_update_spectral_amplitudes(mbe_parms* cur_mp, mbe_parms* prev_mp, const float Tl[57], float rho) {
    int intkl[57];
    float flokl[57], deltal[57];
    int cur_L = imbe_clamp_harmonic_count(cur_mp->L);
    int prev_L = imbe_clamp_harmonic_count(prev_mp->L);

    cur_mp->L = cur_L;

    if (cur_L > prev_L) {
        for (int l = prev_L + 1; l <= cur_L; l++) {
            prev_mp->Ml[l] = prev_mp->Ml[prev_L];
            prev_mp->log2Ml[l] = prev_mp->log2Ml[prev_L];
        }
    }
    prev_mp->log2Ml[0] = prev_mp->log2Ml[1];
    prev_mp->Ml[0] = prev_mp->Ml[1];

    float Sum77 = 0;
    for (int l = 1; l <= cur_L; l++) {
        flokl[l] = ((float)prev_L / (float)cur_L) * (float)l;
        intkl[l] = (int)(flokl[l]);
        if (intkl[l] < 0) {
            intkl[l] = 0;
        } else if (intkl[l] > MBE_MAX_HARMONIC_BANDS) {
            intkl[l] = MBE_MAX_HARMONIC_BANDS;
        }
#ifdef IMBE_DEBUG
        fprintf(stderr, "flokl: %e, intkl: %i ", flokl[l], intkl[l]);
#endif
        deltal[l] = flokl[l] - (float)intkl[l];
#ifdef IMBE_DEBUG
        fprintf(stderr, "deltal: %e ", deltal[l]);
#endif
        int upper = intkl[l] + 1;
        if (upper > MBE_MAX_HARMONIC_BANDS) {
            upper = MBE_MAX_HARMONIC_BANDS;
        }
        Sum77 = Sum77 + ((((float)1 - deltal[l]) * prev_mp->log2Ml[intkl[l]]) + (deltal[l] * prev_mp->log2Ml[upper]));
    }
    Sum77 = ((rho / (float)cur_L) * Sum77);

#ifdef IMBE_DEBUG
    fprintf(stderr, "Sum77: %e\n", Sum77);
#endif

    for (int l = 1; l <= cur_L; l++) {
        int upper = intkl[l] + 1;
        if (upper > MBE_MAX_HARMONIC_BANDS) {
            upper = MBE_MAX_HARMONIC_BANDS;
        }
        float c1 = (rho * ((float)1 - deltal[l]) * prev_mp->log2Ml[intkl[l]]);
        float c2 = (rho * deltal[l] * prev_mp->log2Ml[upper]);
        cur_mp->log2Ml[l] = Tl[l] + c1 + c2 - Sum77;
        cur_mp->Ml[l] = exp2f(cur_mp->log2Ml[l]);
#ifdef IMBE_DEBUG
        fprintf(stderr, "rho: %e c1: %e c2: %e Sum77: %e T%i: %e log2M%i: %e M%i: %e\n", rho, c1, c2, Sum77, l, Tl[l],
                l, cur_mp->log2Ml[l], l, cur_mp->Ml[l]);
#endif
    }
}

/**
 * @brief Print IMBE 4400 parameter bits to stderr (debug aid).
 * @param imbe_d IMBE parameter bits (88).
 */
void
mbe_dumpImbe4400Data(const char* imbe_d) {

    int i;
    const char* imbe;

    imbe = imbe_d;
    for (i = 0; i < 88; i++) {
        fprintf(stderr, "%i", *imbe);
        imbe++;
    }
}

/**
 * @brief Print IMBE 7200x4400 parameter bits to stderr (debug aid).
 * @param imbe_d IMBE parameter bits (88).
 */
void
mbe_dumpImbe7200x4400Data(const char* imbe_d) {

    int i;
    const char* imbe;

    imbe = imbe_d;
    for (i = 0; i < 88; i++) {
        if ((i == 12) || (i == 24) || (i == 36) || (i == 48) || (i == 59) || (i == 70) || (i == 81)) {
            fprintf(stderr, " ");
        }
        fprintf(stderr, "%i", *imbe);
        imbe++;
    }
}

/**
 * @brief Print raw IMBE 7200x4400 frame bitplanes to stderr.
 * @param imbe_fr Frame as 8x23 bitplanes (last row partial).
 */
void
mbe_dumpImbe7200x4400Frame(const char imbe_fr[8][23]) {

    int i, j;

    for (i = 0; i < 4; i++) {
        for (j = 22; j >= 0; j--) {
            fprintf(stderr, "%i", imbe_fr[i][j]);
        }
        fprintf(stderr, " ");
    }
    for (i = 4; i < 7; i++) {
        for (j = 14; j >= 0; j--) {
            fprintf(stderr, "%i", imbe_fr[i][j]);
        }
        fprintf(stderr, " ");
    }
    for (j = 6; j >= 0; j--) {
        fprintf(stderr, "%i", imbe_fr[7][j]);
    }
}

/**
 * @brief Apply ECC to IMBE 7200x4400 C0 and update in-place.
 * @param imbe_fr Frame as 8x23 bitplanes.
 * @return Number of corrected errors in C0.
 */
int
mbe_eccImbe7200x4400C0(char imbe_fr[8][23]) {

    int j, errs;
    char in[23], out[23];
    int ret = mbe_validate_bits((const char*)imbe_fr, (size_t)8u * 23u);
    if (ret < 0) {
        return ret;
    }

    for (j = 0; j < 23; j++) {
        in[j] = imbe_fr[0][j];
    }
    errs = mbe_golay2312(in, out);
    for (j = 0; j < 23; j++) {
        imbe_fr[0][j] = out[j];
    }

    return errs;
}

static int
mbe_eccImbe7200x4400C0Soft(mbe_soft_bit imbe_fr[8][23]) {
    int j, errs;
    mbe_soft_bit in[23];
    char out[23];

    for (j = 0; j < 23; j++) {
        in[j] = imbe_fr[0][j];
    }
    errs = mbe_golay2312Soft(in, out);
    for (j = 0; j < 23; j++) {
        imbe_fr[0][j].bit = (uint8_t)(out[j] & 1);
    }

    return errs;
}

/**
 * @brief Internal: Apply ECC to IMBE 7200x4400 data with separate C4 error tracking.
 * @param imbe_fr Frame as 8x23 bitplanes.
 * @param imbe_d  Output parameter bits (88).
 * @param errs_c4 Output: errors in C4 (Hamming coset 4), or NULL if not needed.
 * @return Number of corrected errors in protected fields.
 */
static int
mbe_eccImbe7200x4400DataInternal(char imbe_fr[8][23], char* imbe_d, int* errs_c4) {

    int i, j, errs;
    char *imbe, gin[23], gout[23], hin[15], hout[15];

    errs = 0;
    imbe = imbe_d;
    for (i = 0; i < 4; i++) {
        if (i > 0) {
            for (j = 0; j < 23; j++) {
                gin[j] = imbe_fr[i][j];
            }
            errs += mbe_golay2312(gin, gout);
            for (j = 22; j > 10; j--) {
                *imbe = gout[j];
                imbe++;
            }
        } else {
            for (j = 22; j > 10; j--) {
                *imbe = imbe_fr[i][j];
                imbe++;
            }
        }
    }
    for (i = 4; i < 7; i++) {
        for (j = 0; j < 15; j++) {
            hin[j] = imbe_fr[i][j];
        }
        int hamming_errs = mbe_hamming1511(hin, hout);
        errs += hamming_errs;
        /* Track C4 (first Hamming coset) errors separately for adaptive smoothing */
        if (i == 4 && errs_c4 != NULL) {
            *errs_c4 = hamming_errs;
        }
        for (j = 14; j >= 4; j--) {
            *imbe = hout[j];
            imbe++;
        }
    }
    for (j = 6; j >= 0; j--) {
        *imbe = imbe_fr[7][j];
        imbe++;
    }

    return errs;
}

static int
mbe_eccImbe7200x4400DataSoftInternal(mbe_soft_bit imbe_fr[8][23], char* imbe_d, int* errs_c4) {
    int i, j, errs;
    char *imbe, gout[23], hout[15];
    mbe_soft_bit gin[23], hin[15];

    errs = 0;
    imbe = imbe_d;
    for (i = 0; i < 4; i++) {
        if (i > 0) {
            for (j = 0; j < 23; j++) {
                gin[j] = imbe_fr[i][j];
            }
            errs += mbe_golay2312Soft(gin, gout);
            for (j = 22; j > 10; j--) {
                *imbe = gout[j];
                imbe++;
            }
        } else {
            for (j = 22; j > 10; j--) {
                *imbe = (char)(imbe_fr[i][j].bit & 1u);
                imbe++;
            }
        }
    }
    for (i = 4; i < 7; i++) {
        for (j = 0; j < 15; j++) {
            hin[j] = imbe_fr[i][j];
        }
        int hamming_errs = mbe_hamming1511Soft(hin, hout);
        errs += hamming_errs;
        if (i == 4 && errs_c4 != NULL) {
            *errs_c4 = hamming_errs;
        }
        for (j = 14; j >= 4; j--) {
            *imbe = hout[j];
            imbe++;
        }
    }
    for (j = 6; j >= 0; j--) {
        *imbe = (char)(imbe_fr[7][j].bit & 1u);
        imbe++;
    }

    return errs;
}

/**
 * @brief Apply ECC to IMBE 7200x4400 data and pack parameter bits.
 * @param imbe_fr Frame as 8x23 bitplanes.
 * @param imbe_d  Output parameter bits (88).
 * @return Number of corrected errors in protected fields.
 */
int
mbe_eccImbe7200x4400Data(char imbe_fr[8][23], char* imbe_d) {
    if (!imbe_d) {
        return MBE_STATUS_INVALID_ARGUMENT;
    }
    int ret = mbe_validate_bits((const char*)imbe_fr, (size_t)8u * 23u);
    if (ret < 0) {
        return ret;
    }
    return mbe_eccImbe7200x4400DataInternal(imbe_fr, imbe_d, NULL);
}

/**
 * @brief Decode IMBE 4400 parameters from demodulated bitstream.
 * @param imbe_d  Demodulated IMBE parameter bits (88).
 * @param cur_mp  Output: current frame parameters.
 * @param prev_mp Input: previous frame parameters (for prediction).
 * @return 0 on voice; non-zero for special frames (implementation-specific).
 */
int
mbe_decodeImbe4400Parms(const char* imbe_d, mbe_parms* cur_mp, mbe_parms* prev_mp) {

    int L9;
    float Cik[7][11] = {{0.0f}}, Tl[57] = {0.0f}, Gm[7], Ri[7];
    char bb[58][12];
    int ret;

    if (!cur_mp || !prev_mp) {
        return MBE_STATUS_INVALID_ARGUMENT;
    }
    ret = mbe_validate_bits(imbe_d, 88u);
    if (ret < 0) {
        return ret;
    }

    if (imbe_decode_fundamental(imbe_d, cur_mp, &L9) != 0) {
        return 1;
    }

    // read bits from imbe_d into b0..bL+1
    imbe_read_bit_layout(imbe_d, L9, bb);

    // Vl
    imbe_decode_voicing(cur_mp, bb);

    imbe_decode_gains(bb, L9, Gm);

    // inverse DCT Gi to give Ri (also known as Ci,1) - using cached cosines
    imbe_compute_ri(Gm, Ri);

    // load b8..bL+1 into Ci,k
    imbe_decode_hoc_coefficients(bb, L9, Ri, Cik);

    // inverse DCT each Ci,k to give ci,j (Tl) - using cached cosines
    imbe_inverse_dct_tl(Cik, L9, Tl);

    // determine log2Ml by applying ci,j to previous log2Ml
    imbe_update_spectral_amplitudes(cur_mp, prev_mp, Tl, imbe_spectral_rho(cur_mp->L));

    return 0;
}

/**
 * @brief Demodulate interleaved IMBE 7200x4400 data in-place.
 * @param imbe Frame as 8x23 bitplanes (modified).
 */
int
mbe_demodulateImbe7200x4400Data(char imbe[8][23]) {

    int i, j, k;
    unsigned short pr[115];
    unsigned short foo;

    int ret = mbe_validate_bits((const char*)imbe, (size_t)8u * 23u);
    if (ret < 0) {
        return ret;
    }

    // create pseudo-random modulator
    foo = (unsigned short)mbe_bits_descending_to_int(imbe[0], 22, 11);
    pr[0] = (16 * foo);
    for (i = 1; i < 115; i++) {
        pr[i] = (173 * pr[i - 1]) + 13849 - (65536 * (((173 * pr[i - 1]) + 13849) / 65536));
    }
    for (i = 1; i < 115; i++) {
        pr[i] >>= 15; /* normalize to {0,1} cheaply */
    }

    // demodulate imbe with pr
    k = 1;
    for (i = 1; i < 4; i++) {
        for (j = 22; j >= 0; j--) {
            imbe[i][j] = ((imbe[i][j]) ^ pr[k]);
            k++;
        }
    }
    for (i = 4; i < 7; i++) {
        for (j = 14; j >= 0; j--) {
            imbe[i][j] = ((imbe[i][j]) ^ pr[k]);
            k++;
        }
    }
    return 0;
}

static void
mbe_demodulateImbe7200x4400DataSoft(mbe_soft_bit imbe[8][23]) {
    int i, j, k;
    unsigned short pr[115];
    unsigned short foo;

    foo = 0;
    for (i = 22; i >= 11; i--) {
        foo <<= 1;
        foo |= (unsigned short)(imbe[0][i].bit & 1u);
    }
    pr[0] = (unsigned short)(16 * foo);
    for (i = 1; i < 115; i++) {
        pr[i] = (unsigned short)((173 * pr[i - 1]) + 13849 - (65536 * (((173 * pr[i - 1]) + 13849) / 65536)));
    }
    for (i = 1; i < 115; i++) {
        pr[i] >>= 15;
    }

    k = 1;
    for (i = 1; i < 4; i++) {
        for (j = 22; j >= 0; j--) {
            imbe[i][j].bit = (uint8_t)((imbe[i][j].bit & 1u) ^ (uint8_t)pr[k]);
            k++;
        }
    }
    for (i = 4; i < 7; i++) {
        for (j = 14; j >= 0; j--) {
            imbe[i][j].bit = (uint8_t)((imbe[i][j].bit & 1u) ^ (uint8_t)pr[k]);
            k++;
        }
    }
}

int
mbe_decodeImbe7200x4400Frame(const char imbe_fr[8][23], char imbe_d[88], mbe_process_result* result) {
    char fr[8][23];
    int c0_errors;
    int protected_errors;
    int c4_errors = 0;
    int ret;

    if (result) {
        mbe_initProcessResult(result);
    }
    if (!imbe_d) {
        return MBE_STATUS_INVALID_ARGUMENT;
    }
    ret = mbe_validate_bits((const char*)imbe_fr, (size_t)8u * 23u);
    if (ret < 0) {
        return ret;
    }

    memcpy(fr, imbe_fr, sizeof(fr));
    c0_errors = mbe_eccImbe7200x4400C0(fr);
    ret = mbe_demodulateImbe7200x4400Data(fr);
    if (ret < 0) {
        return ret;
    }
    protected_errors = mbe_eccImbe7200x4400DataInternal(fr, imbe_d, &c4_errors);

    if (result) {
        result->c0_errors = c0_errors;
        result->protected_errors = protected_errors;
        result->c4_errors = c4_errors;
        result->total_errors = c0_errors + protected_errors;
        result->flags = MBE_PROCESS_FLAG_C0_VALID | MBE_PROCESS_FLAG_C4_VALID;
    }
    return c0_errors + protected_errors;
}

int
mbe_decodeImbe7200x4400SoftFrame(const mbe_soft_bit imbe_fr[8][23], char imbe_d[88], mbe_process_result* result) {
    mbe_soft_bit fr[8][23];
    int c0_errors;
    int protected_errors;
    int c4_errors = 0;
    int ret;

    if (result) {
        mbe_initProcessResult(result);
    }
    if (!imbe_d) {
        return MBE_STATUS_INVALID_ARGUMENT;
    }
    ret = mbe_validate_soft_bits((const mbe_soft_bit*)imbe_fr, (size_t)8u * 23u);
    if (ret < 0) {
        return ret;
    }

    memcpy(fr, imbe_fr, sizeof(fr));
    c0_errors = mbe_eccImbe7200x4400C0Soft(fr);
    mbe_demodulateImbe7200x4400DataSoft(fr);
    protected_errors = mbe_eccImbe7200x4400DataSoftInternal(fr, imbe_d, &c4_errors);

    if (result) {
        result->c0_errors = c0_errors;
        result->protected_errors = protected_errors;
        result->c4_errors = c4_errors;
        result->total_errors = c0_errors + protected_errors;
        result->flags = MBE_PROCESS_FLAG_SOFT_INPUT | MBE_PROCESS_FLAG_C0_VALID | MBE_PROCESS_FLAG_C4_VALID;
    }
    return c0_errors + protected_errors;
}

static int
imbe4400_prepare_process(mbe_process_result* result, const char imbe_d[88], mbe_parms* cur_mp, const mbe_parms* prev_mp,
                         const mbe_parms* prev_mp_enhanced, int* total_errors, int* c0_errors, int* c0_errors_valid) {
    int c4_errors_valid;
    int ret;

    if (!cur_mp || !prev_mp || !prev_mp_enhanced || !total_errors || !c0_errors || !c0_errors_valid) {
        return MBE_STATUS_INVALID_ARGUMENT;
    }
    ret = mbe_result_resolve_total_errors(result, total_errors);
    if (ret < 0) {
        return ret;
    }
    ret = mbe_validate_bits(imbe_d, 88u);
    if (ret < 0) {
        return ret;
    }

    *c0_errors_valid = (result && ((result->flags & MBE_PROCESS_FLAG_C0_VALID) != 0u));
    c4_errors_valid = (result && ((result->flags & MBE_PROCESS_FLAG_C4_VALID) != 0u));
    *c0_errors = *c0_errors_valid ? result->c0_errors : 0;
    cur_mp->errorCount4 = c4_errors_valid ? result->c4_errors : 0;
    mbe_result_prepare_synthesis(result, *total_errors);

    cur_mp->mutingThreshold = MBE_MUTING_THRESHOLD_IMBE;
    cur_mp->errorCountTotal = *total_errors;
    cur_mp->errorRate = (0.95f * prev_mp->errorRate) + (0.000365f * (float)*total_errors);
    return 0;
}

static int
imbe4400_repeat_required(int bad, int total_errors, int c0_errors, int c0_errors_valid, float repeat_threshold) {
    if (bad == 1) {
        return 1;
    }
    if (c0_errors_valid) {
        /* JMBE IMBE repeat criteria when C0 errors are available from frame decode. */
        return ((c0_errors >= 2) && ((float)total_errors >= repeat_threshold));
    }

    /* Dataf callers pass parameter bits only (no C0 context); keep historical total-error fallback. */
    return (total_errors > 5);
}

static void
imbe4400_apply_repeat_decision(int repeat_required, mbe_process_result* result, mbe_parms* cur_mp,
                               const mbe_parms* prev_mp) {
    if (!repeat_required) {
        cur_mp->repeatCount = 0;
        return;
    }

    if (prev_mp->repeatCount > (MBE_MAX_FRAME_REPEATS - 1)) {
        /* JMBE IMBE headroom behavior: reset to default model after prolonged repeats. */
        imbe_reset_headroom_defaults(cur_mp);
    } else {
        mbe_useLastMbeParms(cur_mp, prev_mp);
        cur_mp->repeatCount++;
    }
    mbe_result_set_flag(result, MBE_PROCESS_FLAG_REPEAT);
}

static void
imbe4400_synthesize_frame(float* aout_buf, mbe_process_result* result, mbe_parms* cur_mp, mbe_parms* prev_mp,
                          mbe_parms* prev_mp_enhanced) {
    int frame_muted = mbe_isMaxFrameRepeat(cur_mp) || mbe_requiresMuting(cur_mp);

    mbe_moveMbeParms(cur_mp, prev_mp);
    float pre_enh_rm0 = mbe_spectralAmpEnhanceWithRm0(cur_mp);
    mbe_synthesizeSpeechWithPreEnhRm0f(aout_buf, cur_mp, prev_mp_enhanced, pre_enh_rm0);

    if (frame_muted) {
        mbe_result_set_flag(result, MBE_PROCESS_FLAG_MUTE);
    }

    mbe_moveMbeParms(cur_mp, prev_mp_enhanced);
}

int
mbe_processImbe4400Dataf_internal(float* aout_buf, mbe_process_result* result, const char imbe_d[88], mbe_parms* cur_mp,
                                  mbe_parms* prev_mp, mbe_parms* prev_mp_enhanced) {
    int bad;
    int repeat_required;
    float repeat_threshold;
    int total_errors;
    int c0_errors;
    int c0_errors_valid;
    int ret;

    if (!aout_buf || !cur_mp || !prev_mp || !prev_mp_enhanced) {
        return MBE_STATUS_INVALID_ARGUMENT;
    }
    ret = imbe4400_prepare_process(result, imbe_d, cur_mp, prev_mp, prev_mp_enhanced, &total_errors, &c0_errors,
                                   &c0_errors_valid);
    if (ret < 0) {
        return ret;
    }

    bad = mbe_decodeImbe4400Parms(imbe_d, cur_mp, prev_mp);
    if (bad < 0) {
        return bad;
    }
    repeat_threshold = 10.0f + (40.0f * cur_mp->errorRate);
    repeat_required = imbe4400_repeat_required(bad, total_errors, c0_errors, c0_errors_valid, repeat_threshold);

    imbe4400_apply_repeat_decision(repeat_required, result, cur_mp, prev_mp);
    imbe4400_synthesize_frame(aout_buf, result, cur_mp, prev_mp, prev_mp_enhanced);
    return result ? result->total_errors : total_errors;
}

/**
 * @brief Process IMBE 4400 parameters into 160 float samples at 8 kHz.
 * @param aout_buf Output buffer of 160 float samples.
 * @param result   Optional in/out status context.
 * @param imbe_d   Demodulated parameter bits (88).
 * @param cur_mp   In/out: current frame parameters (may be enhanced).
 * @param prev_mp  In/out: previous frame parameters.
 * @param prev_mp_enhanced In/out: enhanced previous parameters for continuity.
 */
int
mbe_processImbe4400Dataf(float* aout_buf, mbe_process_result* result, const char imbe_d[88], mbe_parms* cur_mp,
                         mbe_parms* prev_mp, mbe_parms* prev_mp_enhanced) {
    mbe_process_result local_result;

    if (!result) {
        mbe_initProcessResult(&local_result);
        result = &local_result;
    }
    return mbe_processImbe4400Dataf_internal(aout_buf, result, imbe_d, cur_mp, prev_mp, prev_mp_enhanced);
}

int
mbe_processImbe4400Data(short* aout_buf, mbe_process_result* result, const char imbe_d[88], mbe_parms* cur_mp,
                        mbe_parms* prev_mp, mbe_parms* prev_mp_enhanced) {
    float float_buf[160];
    int ret;
    if (!aout_buf) {
        return MBE_STATUS_INVALID_ARGUMENT;
    }
    ret = mbe_processImbe4400Dataf(float_buf, result, imbe_d, cur_mp, prev_mp, prev_mp_enhanced);
    if (ret < 0) {
        return ret;
    }
    mbe_floattoshort(float_buf, aout_buf);
    return ret;
}

/**
 * @brief Process a complete IMBE 7200x4400 frame into float PCM.
 * @param aout_buf Output buffer of 160 float samples.
 * @param result   Optional output status populated by decode and synthesis.
 * @param imbe_fr  Input frame as 8x23 bitplanes.
 * @param imbe_d   Scratch/output parameter bits (88).
 * @param cur_mp,prev_mp,prev_mp_enhanced Parameter state as per Dataf variant.
 */
int
mbe_processImbe7200x4400Framef(float* aout_buf, mbe_process_result* result, const char imbe_fr[8][23], char imbe_d[88],
                               mbe_parms* cur_mp, mbe_parms* prev_mp, mbe_parms* prev_mp_enhanced) {
    mbe_process_result local_result;
    int ret;
    if (!result) {
        result = &local_result;
    }
    ret = mbe_decodeImbe7200x4400Frame(imbe_fr, imbe_d, result);
    if (ret < 0) {
        return ret;
    }
    return mbe_processImbe4400Dataf(aout_buf, result, imbe_d, cur_mp, prev_mp, prev_mp_enhanced);
}

int
mbe_processImbe7200x4400SoftFramef(float* aout_buf, mbe_process_result* result, const mbe_soft_bit imbe_fr[8][23],
                                   char imbe_d[88], mbe_parms* cur_mp, mbe_parms* prev_mp,
                                   mbe_parms* prev_mp_enhanced) {
    mbe_process_result local_result;
    int ret;
    if (!result) {
        result = &local_result;
    }
    ret = mbe_decodeImbe7200x4400SoftFrame(imbe_fr, imbe_d, result);
    if (ret < 0) {
        return ret;
    }
    return mbe_processImbe4400Dataf(aout_buf, result, imbe_d, cur_mp, prev_mp, prev_mp_enhanced);
}

int
mbe_processImbe7200x4400SoftFrame(short* aout_buf, mbe_process_result* result, const mbe_soft_bit imbe_fr[8][23],
                                  char imbe_d[88], mbe_parms* cur_mp, mbe_parms* prev_mp, mbe_parms* prev_mp_enhanced) {
    float float_buf[160];
    int ret;
    if (!aout_buf) {
        return MBE_STATUS_INVALID_ARGUMENT;
    }
    ret = mbe_processImbe7200x4400SoftFramef(float_buf, result, imbe_fr, imbe_d, cur_mp, prev_mp, prev_mp_enhanced);
    if (ret < 0) {
        return ret;
    }
    mbe_floattoshort(float_buf, aout_buf);
    return ret;
}

/**
 * @brief Process a complete IMBE 7200x4400 frame into 16-bit PCM.
 * @see mbe_processImbe7200x4400Framef for details.
 */
int
mbe_processImbe7200x4400Frame(short* aout_buf, mbe_process_result* result, const char imbe_fr[8][23], char imbe_d[88],
                              mbe_parms* cur_mp, mbe_parms* prev_mp, mbe_parms* prev_mp_enhanced) {

    float float_buf[160];
    int ret;
    if (!aout_buf) {
        return MBE_STATUS_INVALID_ARGUMENT;
    }
    ret = mbe_processImbe7200x4400Framef(float_buf, result, imbe_fr, imbe_d, cur_mp, prev_mp, prev_mp_enhanced);
    if (ret < 0) {
        return ret;
    }
    mbe_floattoshort(float_buf, aout_buf);
    return ret;
}
