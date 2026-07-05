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
 * @brief AMBE 3600x2400 parameter decode, ECC, and synthesis hooks.
 */

#include <math.h>
#include <stdio.h>
#include <string.h>

#include "ambe3600x2400_const.h"
#include "ambe_common.h"
#include "mbe_adaptive.h"
#include "mbe_compiler.h"
#include "mbe_result.h"
#include "mbelib-neo/mbelib.h"

/**
 * @brief Thread-local cache for AMBE DCT cosine coefficients.
 *
 * Pre-computes the cosine terms used in the Ri inverse DCT and per-block
 * inverse DCT loops to eliminate repeated cosf() calls per frame.
 *
 * Ri DCT: cosf(M_PI * (m-1) * (i-0.5) / 8) for m=1..8, i=1..8
 * Per-block IDCT: cosf(M_PI * (k-1) * (j-0.5) / ji) for ji=1..17, j=1..ji, k=1..ji
 */
struct ambe_dct_cache {
    int inited;
    float ri_cos[9][9];         /* [m][i] for m=1..8, i=1..8 (index 0 unused) */
    float idct_cos[18][18][18]; /* [ji][j][k] for ji=1..17, j=1..ji, k=1..ji */
};

static MBE_THREAD_LOCAL struct ambe_dct_cache ambe_cache = {0};

/**
 * @brief Initialize or return the thread-local AMBE DCT cache.
 *
 * Fills the cosine tables on first use. Because the cache is thread-local,
 * no locking is required.
 *
 * @return Pointer to the initialized cache.
 */
static struct ambe_dct_cache*
ambe_get_dct_cache(void) {
    if (ambe_cache.inited) {
        return &ambe_cache;
    }

    /* Fill Ri DCT cosine table: cosf(M_PI * (m-1) * (i-0.5) / 8) */
    for (int m = 1; m <= 8; m++) {
        for (int i = 1; i <= 8; i++) {
            ambe_cache.ri_cos[m][i] = cosf((M_PI * (float)(m - 1) * ((float)i - 0.5f)) / 8.0f);
        }
    }

    /* Fill per-block IDCT cosine table: cosf(M_PI * (k-1) * (j-0.5) / ji) */
    for (int ji = 1; ji <= 17; ji++) {
        for (int j = 1; j <= ji; j++) {
            for (int k = 1; k <= ji; k++) {
                ambe_cache.idct_cos[ji][j][k] = cosf((M_PI * (float)(k - 1) * ((float)j - 0.5f)) / (float)ji);
            }
        }
    }

    ambe_cache.inited = 1;
    return &ambe_cache;
}

/**
 * @brief Print AMBE 2400 parameter bits to stderr (debug aid).
 * @param ambe_d AMBE parameter bits (49).
 */
void
mbe_dumpAmbe2400Data(const char* ambe_d) {

    int i;
    const char* ambe;

    ambe = ambe_d;
    for (i = 0; i < 49; i++) {
        fprintf(stderr, "%i", *ambe);
        ambe++;
    }
    fprintf(stderr, " ");
}

/**
 * @brief Print raw AMBE 3600x2400 frame bitplanes to stderr.
 * @param ambe_fr Frame as 4x24 bitplanes.
 */
void
mbe_dumpAmbe3600x2400Frame(const char ambe_fr[4][24]) {

    int j;

    // c0
    fprintf(stderr, "ambe_fr c0: ");
    for (j = 23; j >= 0; j--) {
        fprintf(stderr, "%i", ambe_fr[0][j]);
    }
    fprintf(stderr, " ");
    // c1
    fprintf(stderr, "ambe_fr c1: ");
    for (j = 22; j >= 0; j--) {
        fprintf(stderr, "%i", ambe_fr[1][j]);
    }
    fprintf(stderr, " ");
    // c2
    fprintf(stderr, "ambe_fr c2: ");
    for (j = 10; j >= 0; j--) {
        fprintf(stderr, "%i", ambe_fr[2][j]);
    }
    fprintf(stderr, " ");
    // c3
    fprintf(stderr, "ambe_fr c3: ");
    for (j = 13; j >= 0; j--) {
        fprintf(stderr, "%i", ambe_fr[3][j]);
    }
    fprintf(stderr, " ");
}

/**
 * @brief Apply ECC to AMBE 3600x2400 C0 and update in-place.
 * @param ambe_fr Frame as 4x24 bitplanes.
 * @return Number of corrected errors in C0.
 */
int
mbe_eccAmbe3600x2400C0(char ambe_fr[4][24]) {
    int ret = mbe_validate_bits((const char*)ambe_fr, (size_t)4u * 24u);
    if (ret < 0) {
        return ret;
    }
    return mbe_eccAmbe3600C0_common(ambe_fr);
}

/**
 * @brief Apply ECC to AMBE 3600x2400 data and pack parameter bits.
 * @param ambe_fr Frame as 4x24 bitplanes.
 * @param ambe_d  Output parameter bits (49).
 * @return Number of corrected errors in protected fields.
 */
int
mbe_eccAmbe3600x2400Data(char ambe_fr[4][24], char* ambe_d) {
    if (!ambe_d) {
        return MBE_STATUS_INVALID_ARGUMENT;
    }
    int ret = mbe_validate_bits((const char*)ambe_fr, (size_t)4u * 24u);
    if (ret < 0) {
        return ret;
    }
    return mbe_eccAmbe3600Data_common(ambe_fr, ambe_d);
}

static int
ambe2400_decode_b0(const char* ambe_d) {
    int b0 = 0;
    b0 |= ambe_d[0] << 6;
    b0 |= ambe_d[1] << 5;
    b0 |= ambe_d[2] << 4;
    b0 |= ambe_d[3] << 3;
    b0 |= ambe_d[4] << 2;
    b0 |= ambe_d[5] << 1;
    b0 |= ambe_d[48];
    return b0;
}

static int
ambe2400_decode_tone_index(const char* ambe_d) {
    static const int t7tab[8] = {1, 0, 0, 0, 0, 1, 1, 1};
    static const int t6tab[8] = {0, 0, 0, 1, 1, 1, 1, 0};
    static const int t5tab[8] = {0, 0, 1, 0, 1, 1, 0, 1};
    int def = (ambe_d[6] << 2) | (ambe_d[7] << 1) | ambe_d[8];

    int tone_index = 0;
    tone_index |= t7tab[def] << 7;
    tone_index |= t6tab[def] << 6;
    tone_index |= t5tab[def] << 5;
    tone_index |= ambe_d[9] << 4;
    tone_index |= ambe_d[42] << 3;
    tone_index |= ambe_d[43] << 2;
    tone_index |= ambe_d[10] << 1;
    tone_index |= ambe_d[11];

#ifdef AMBE_DEBUG
    int tone_volume = (ambe_d[12] << 7) | (ambe_d[13] << 6) | (ambe_d[14] << 5) | (ambe_d[15] << 4) | (ambe_d[16] << 3)
                      | (ambe_d[44] << 2) | (ambe_d[45] << 1) | ambe_d[17];
    (void)tone_volume;
#endif
    return tone_index;
}

static void
ambe2400_set_silence_model(mbe_parms* cur_mp, int* L) {
    cur_mp->w0 = ((float)2 * M_PI) / (float)32;
    *L = 14;
    cur_mp->L = 14;
    for (int l = 1; l <= *L; l++) {
        cur_mp->Vl[l] = 0;
    }
}

static int
ambe2400_handle_tone_frame(const char* ambe_d, mbe_parms* cur_mp, int b0, int* L) {
    if ((b0 & 0x7E) != 0x7E) {
        return 0;
    }

    int tone_index = ambe2400_decode_tone_index(ambe_d);
    if ((tone_index >= 5) && (tone_index <= 122)) {
        return tone_index;
    }

    if (!((tone_index >= 128) && (tone_index <= 163))) {
#ifdef AMBE_DEBUG
        fprintf(stderr, "Silence Frame\n");
#endif
        ambe2400_set_silence_model(cur_mp, L);
    }

#ifdef AMBE_DEBUG
    fprintf(stderr, "Tone Frame\n");
#endif
    return 3;
}

static void
ambe2400_setup_voice_model(mbe_parms* cur_mp, int b0, int* L, float* f0) {
    *f0 = exp2f(-4.311767578125f - (2.1336e-2f * ((float)b0 + 0.5f)));
    cur_mp->w0 = *f0 * (float)2 * M_PI;
    *L = AmbePlusLtable[b0];
    cur_mp->L = *L;
}

static void
ambe2400_decode_vuv(const char* ambe_d, mbe_parms* cur_mp, int b0, int L, float f0) {
    (void)b0;
    int b1 = 0;
    b1 |= ambe_d[38] << 3;
    b1 |= ambe_d[39] << 2;
    b1 |= ambe_d[40] << 1;
    b1 |= ambe_d[41];

    for (int l = 1; l <= L; l++) {
        int jl = (int)((float)l * (float)16.0 * f0);
        cur_mp->Vl[l] = AmbePlusVuv[b1][jl];
#ifdef AMBE_DEBUG
        fprintf(stderr, "jl[%i]:%i Vl[%i]:%i\n", l, jl, l, cur_mp->Vl[l]);
#endif
    }
#ifdef AMBE_DEBUG
    fprintf(stderr, "\nb0:%i w0:%f L:%i b1:%i\n", b0, cur_mp->w0, L, b1);
#endif
}

static void
ambe2400_decode_gain(const char* ambe_d, mbe_parms* cur_mp, const mbe_parms* prev_mp) {
    int b2 = 0;
    b2 |= ambe_d[6] << 5;
    b2 |= ambe_d[7] << 4;
    b2 |= ambe_d[8] << 3;
    b2 |= ambe_d[9] << 2;
    b2 |= ambe_d[42] << 1;
    b2 |= ambe_d[43];

    float deltaGamma = AmbePlusDg[b2];
    cur_mp->gamma = deltaGamma + ((float)0.5 * prev_mp->gamma);
#ifdef AMBE_DEBUG
    fprintf(stderr, "b2: %i, deltaGamma: %f gamma: %f gamma-1: %f\n", b2, deltaGamma, cur_mp->gamma, prev_mp->gamma);
#endif
}

static void
ambe2400_decode_ri(const char* ambe_d, float Ri[9]) {
    float Gm[9];
    Gm[1] = 0;

    int b3 = 0;
    b3 |= ambe_d[10] << 8;
    b3 |= ambe_d[11] << 7;
    b3 |= ambe_d[12] << 6;
    b3 |= ambe_d[13] << 5;
    b3 |= ambe_d[14] << 4;
    b3 |= ambe_d[15] << 3;
    b3 |= ambe_d[16] << 2;
    b3 |= ambe_d[44] << 1;
    b3 |= ambe_d[45];
    Gm[2] = AmbePlusPRBA24[b3][0];
    Gm[3] = AmbePlusPRBA24[b3][1];
    Gm[4] = AmbePlusPRBA24[b3][2];

    int b4 = 0;
    b4 |= ambe_d[17] << 6;
    b4 |= ambe_d[18] << 5;
    b4 |= ambe_d[19] << 4;
    b4 |= ambe_d[20] << 3;
    b4 |= ambe_d[21] << 2;
    b4 |= ambe_d[46] << 1;
    b4 |= ambe_d[47];
    Gm[5] = AmbePlusPRBA58[b4][0];
    Gm[6] = AmbePlusPRBA58[b4][1];
    Gm[7] = AmbePlusPRBA58[b4][2];
    Gm[8] = AmbePlusPRBA58[b4][3];

#ifdef AMBE_DEBUG
    fprintf(stderr, "b3: %i Gm[2]: %f Gm[3]: %f Gm[4]: %f b4: %i Gm[5]: %f Gm[6]: %f Gm[7]: %f Gm[8]: %f\n", b3, Gm[2],
            Gm[3], Gm[4], b4, Gm[5], Gm[6], Gm[7], Gm[8]);
#endif

    const struct ambe_dct_cache* cache = ambe_get_dct_cache();
    for (int i = 1; i <= 8; i++) {
        float sum = 0;
        for (int m = 1; m <= 8; m++) {
            int am = (m == 1) ? 1 : 2;
            sum = sum + ((float)am * Gm[m] * cache->ri_cos[m][i]);
        }
        Ri[i] = sum;
#ifdef AMBE_DEBUG
        fprintf(stderr, "R%i: %f ", i, Ri[i]);
#endif
    }
#ifdef AMBE_DEBUG
    fprintf(stderr, "\n");
#endif
}

static void
ambe2400_load_hoc_block(float Cik[5][18], int block, int ji, int code, const float hoc[16][4]) {
    for (int k = 3; k <= ji; k++) {
        if (k > 6) {
            Cik[block][k] = 0;
        } else {
            Cik[block][k] = hoc[code][k - 3];
#ifdef AMBE_DEBUG
            fprintf(stderr, "C%i,%i: %f ", block, k, Cik[block][k]);
#endif
        }
    }
}

static void
ambe2400_decode_cik(const char* ambe_d, int L, const float Ri[9], float Cik[5][18], int Ji[5]) {
    const float rconst = ((float)1 / ((float)2 * M_SQRT2));
    Cik[1][1] = (float)0.5 * (Ri[1] + Ri[2]);
    Cik[1][2] = rconst * (Ri[1] - Ri[2]);
    Cik[2][1] = (float)0.5 * (Ri[3] + Ri[4]);
    Cik[2][2] = rconst * (Ri[3] - Ri[4]);
    Cik[3][1] = (float)0.5 * (Ri[5] + Ri[6]);
    Cik[3][2] = rconst * (Ri[5] - Ri[6]);
    Cik[4][1] = (float)0.5 * (Ri[7] + Ri[8]);
    Cik[4][2] = rconst * (Ri[7] - Ri[8]);

    int b5 = 0;
    b5 |= ambe_d[22] << 3;
    b5 |= ambe_d[23] << 2;
    b5 |= ambe_d[25] << 1;
    b5 |= ambe_d[26];

    int b6 = 0;
    b6 |= ambe_d[27] << 3;
    b6 |= ambe_d[28] << 2;
    b6 |= ambe_d[29] << 1;
    b6 |= ambe_d[30];

    int b7 = 0;
    b7 |= ambe_d[31] << 3;
    b7 |= ambe_d[32] << 2;
    b7 |= ambe_d[33] << 1;
    b7 |= ambe_d[34];

    int b8 = 0;
    b8 |= ambe_d[35] << 3;
    b8 |= ambe_d[36] << 2;
    b8 |= ambe_d[37] << 1;

    Ji[1] = AmbePlusLmprbl[L][0];
    Ji[2] = AmbePlusLmprbl[L][1];
    Ji[3] = AmbePlusLmprbl[L][2];
    Ji[4] = AmbePlusLmprbl[L][3];
#ifdef AMBE_DEBUG
    fprintf(stderr, "Ji[1]: %i Ji[2]: %i Ji[3]: %i Ji[4]: %i\n", Ji[1], Ji[2], Ji[3], Ji[4]);
    fprintf(stderr, "b5: %i b6: %i b7: %i b8: %i\n", b5, b6, b7, b8);
#endif

    ambe2400_load_hoc_block(Cik, 1, Ji[1], b5, AmbePlusHOCb5);
    ambe2400_load_hoc_block(Cik, 2, Ji[2], b6, AmbePlusHOCb6);
    ambe2400_load_hoc_block(Cik, 3, Ji[3], b7, AmbePlusHOCb7);
    ambe2400_load_hoc_block(Cik, 4, Ji[4], b8, AmbePlusHOCb8);
#ifdef AMBE_DEBUG
    fprintf(stderr, "\n");
#endif
}

static void
ambe2400_inverse_dct_tl(float Cik[5][18], const int Ji[5], float Tl[57]) {
    const struct ambe_dct_cache* cache = ambe_get_dct_cache();
    int l = 1;
    for (int i = 1; i <= 4; i++) {
        int ji = Ji[i];
        for (int j = 1; j <= ji; j++) {
            float sum = 0;
            for (int k = 1; k <= ji; k++) {
                int ak = (k == 1) ? 1 : 2;
#ifdef AMBE_DEBUG
                fprintf(stderr, "j: %i Cik[%i][%i]: %f ", j, i, k, Cik[i][k]);
#endif
                sum = sum + ((float)ak * Cik[i][k] * cache->idct_cos[ji][j][k]);
            }
            Tl[l] = sum;
#ifdef AMBE_DEBUG
            fprintf(stderr, "Tl[%i]: %f\n", l, Tl[l]);
#endif
            l++;
        }
    }
}

static void
ambe2400_update_spectral_amplitudes(mbe_parms* cur_mp, mbe_parms* prev_mp, const float Tl[57], float unvc) {
    int intkl[57] = {0};
    float flokl[57] = {0.0f};
    float deltal[57] = {0.0f};
    int prev_L = prev_mp->L;
    if (cur_mp->L < 1) {
        cur_mp->L = 1;
    } else if (cur_mp->L > 56) {
        cur_mp->L = 56;
    }
    if (prev_L < 1) {
        prev_L = 1;
    } else if (prev_L > 56) {
        prev_L = 56;
    }

    if (cur_mp->L > prev_L) {
        for (int l = prev_L + 1; l <= cur_mp->L; l++) {
            prev_mp->Ml[l] = prev_mp->Ml[prev_L];
            prev_mp->log2Ml[l] = prev_mp->log2Ml[prev_L];
        }
    }
    prev_mp->log2Ml[0] = prev_mp->log2Ml[1];
    prev_mp->Ml[0] = prev_mp->Ml[1];

    float Sum43 = 0;
    for (int l = 1; l <= cur_mp->L; l++) {
        flokl[l] = ((float)prev_L / (float)cur_mp->L) * (float)l;
        intkl[l] = (int)(flokl[l]);
#ifdef AMBE_DEBUG
        fprintf(stderr, "flok%i: %f, intk%i: %i ", l, flokl[l], l, intkl[l]);
#endif
        deltal[l] = flokl[l] - (float)intkl[l];
#ifdef AMBE_DEBUG
        fprintf(stderr, "delta%i: %f ", l, deltal[l]);
#endif
        Sum43 = Sum43
                + ((((float)1 - deltal[l]) * prev_mp->log2Ml[intkl[l]]) + (deltal[l] * prev_mp->log2Ml[intkl[l] + 1]));
    }
    Sum43 = (((float)0.65 / (float)cur_mp->L) * Sum43);
#ifdef AMBE_DEBUG
    fprintf(stderr, "\n");
    fprintf(stderr, "Sum43: %f\n", Sum43);
#endif

    float Sum42 = 0;
    for (int l = 1; l <= cur_mp->L; l++) {
        Sum42 += Tl[l];
    }
    Sum42 = Sum42 / (float)cur_mp->L;
    float BigGamma = cur_mp->gamma - (0.5f * log2f((float)cur_mp->L)) - Sum42;

    for (int l = 1; l <= cur_mp->L; l++) {
        float c1 = ((float)0.65 * ((float)1 - deltal[l]) * prev_mp->log2Ml[intkl[l]]);
        float c2 = ((float)0.65 * deltal[l] * prev_mp->log2Ml[intkl[l] + 1]);
        cur_mp->log2Ml[l] = Tl[l] + c1 + c2 - Sum43 + BigGamma;
        if (cur_mp->Vl[l] == 1) {
            cur_mp->Ml[l] = exp2f(cur_mp->log2Ml[l]);
        } else {
            cur_mp->Ml[l] = unvc * exp2f(cur_mp->log2Ml[l]);
        }
#ifdef AMBE_DEBUG
        fprintf(stderr, "flokl[%i]: %f, intkl[%i]: %i ", l, flokl[l], l, intkl[l]);
        fprintf(stderr, "deltal[%i]: %f ", l, deltal[l]);
        fprintf(stderr, "prev_mp->log2Ml[%i]: %f\n", l, prev_mp->log2Ml[intkl[l]]);
        fprintf(stderr, "BigGamma: %f c1: %f c2: %f Sum43: %f Tl[%i]: %f log2Ml[%i]: %f Ml[%i]: %f\n", BigGamma, c1, c2,
                Sum43, l, Tl[l], l, cur_mp->log2Ml[l], l, cur_mp->Ml[l]);
#endif
    }
}

/**
 * @brief Decode AMBE 2400 parameters from demodulated bitstream.
 * @param ambe_d  Demodulated AMBE parameter bits (49).
 * @param cur_mp  Output: current frame parameters.
 * @param prev_mp Input: previous frame parameters (for prediction).
 * @return Tone index or 0 for voice; implementation-specific non-zero for tone frames.
 */
int
mbe_decodeAmbe2400Parms(const char* ambe_d, mbe_parms* cur_mp, mbe_parms* prev_mp) {

    int L = 0;
    int Ji[5];
    float f0;
    float Cik[5][18];
    float Tl[57] = {0};
    float Ri[9];
    int ret;

    if (!cur_mp || !prev_mp) {
        return MBE_STATUS_INVALID_ARGUMENT;
    }
    ret = mbe_validate_bits(ambe_d, 49u);
    if (ret < 0) {
        return ret;
    }

#ifdef AMBE_DEBUG
    fprintf(stderr, "\n");
#endif

    int b0 = ambe2400_decode_b0(ambe_d);
    int tone_frame = ambe2400_handle_tone_frame(ambe_d, cur_mp, b0, &L);
    if (tone_frame != 0) {
        return tone_frame;
    }

    ambe2400_setup_voice_model(cur_mp, b0, &L, &f0);
    float unvc = (float)0.2046 / sqrtf(cur_mp->w0);

    ambe2400_decode_vuv(ambe_d, cur_mp, b0, L, f0);
    ambe2400_decode_gain(ambe_d, cur_mp, prev_mp);
    ambe2400_decode_ri(ambe_d, Ri);
    ambe2400_decode_cik(ambe_d, L, Ri, Cik, Ji);
    ambe2400_inverse_dct_tl(Cik, Ji, Tl);
    ambe2400_update_spectral_amplitudes(cur_mp, prev_mp, Tl, unvc);

    return 0;
}

/**
 * @brief Demodulate interleaved AMBE 3600x2400 data in-place.
 * @param ambe_fr Frame as 4x24 bitplanes (modified).
 */
int
mbe_demodulateAmbe3600x2400Data(char ambe_fr[4][24]) {
    int ret = mbe_validate_bits((const char*)ambe_fr, (size_t)4u * 24u);
    if (ret < 0) {
        return ret;
    }
    mbe_demodulateAmbe3600Data_common(ambe_fr);
    return 0;
}

int
mbe_decodeAmbe3600x2400Frame(const char ambe_fr[4][24], char ambe_d[49], mbe_process_result* result) {
    char fr[4][24];
    int c0_errors;
    int protected_errors;
    int ret;

    if (result) {
        mbe_initProcessResult(result);
    }
    if (!ambe_d) {
        return MBE_STATUS_INVALID_ARGUMENT;
    }
    ret = mbe_validate_bits((const char*)ambe_fr, (size_t)4u * 24u);
    if (ret < 0) {
        return ret;
    }

    memcpy(fr, ambe_fr, sizeof(fr));
    c0_errors = mbe_eccAmbe3600x2400C0(fr);
    ret = mbe_demodulateAmbe3600x2400Data(fr);
    if (ret < 0) {
        return ret;
    }
    protected_errors = mbe_eccAmbe3600x2400Data(fr, ambe_d);

    if (result) {
        result->c0_errors = c0_errors;
        result->protected_errors = protected_errors;
        result->total_errors = c0_errors + protected_errors;
        result->flags = MBE_PROCESS_FLAG_C0_VALID;
    }
    return c0_errors + protected_errors;
}

int
mbe_decodeAmbe3600x2400SoftFrame(const mbe_soft_bit ambe_fr[4][24], char ambe_d[49], mbe_process_result* result) {
    mbe_soft_bit fr[4][24];
    int c0_errors;
    int protected_errors;
    int ret;

    if (result) {
        mbe_initProcessResult(result);
    }
    if (!ambe_d) {
        return MBE_STATUS_INVALID_ARGUMENT;
    }
    ret = mbe_validate_soft_bits((const mbe_soft_bit*)ambe_fr, (size_t)4u * 24u);
    if (ret < 0) {
        return ret;
    }

    memcpy(fr, ambe_fr, sizeof(fr));
    c0_errors = mbe_eccAmbe3600C0Soft_common(fr);
    mbe_demodulateAmbe3600DataSoft_common(fr);
    protected_errors = mbe_eccAmbe3600DataSoft_common(fr, ambe_d);

    if (result) {
        result->c0_errors = c0_errors;
        result->protected_errors = protected_errors;
        result->total_errors = c0_errors + protected_errors;
        result->flags = MBE_PROCESS_FLAG_SOFT_INPUT | MBE_PROCESS_FLAG_C0_VALID;
    }
    return c0_errors + protected_errors;
}

static int
ambe2400_prepare_process(mbe_process_result* result, const char ambe_d[49], mbe_parms* cur_mp, mbe_parms* prev_mp,
                         mbe_parms* prev_mp_enhanced, int* total_errors, int* c0_errors) {
    int ret;

    if (!cur_mp || !prev_mp || !prev_mp_enhanced || !total_errors || !c0_errors) {
        return MBE_STATUS_INVALID_ARGUMENT;
    }
    ret = mbe_result_resolve_total_errors(result, total_errors);
    if (ret < 0) {
        return ret;
    }
    ret = mbe_validate_bits(ambe_d, 49u);
    if (ret < 0) {
        return ret;
    }

    *c0_errors = ((result->flags & MBE_PROCESS_FLAG_C0_VALID) != 0u) ? result->c0_errors : 0;
    mbe_result_prepare_synthesis(result, *total_errors);

    /* AMBE family uses W124 defaults in JMBE; normalize generic init state. */
    mbe_ensureAmbeDefaults_common(cur_mp, prev_mp, prev_mp_enhanced);

    /* Set AMBE-specific muting threshold (9.6% vs IMBE's 8.75%). */
    cur_mp->mutingThreshold = MBE_MUTING_THRESHOLD_AMBE;

    cur_mp->errorCountTotal = *total_errors;
    cur_mp->errorCount4 = 0; /* AMBE has no Hamming cosets */
    cur_mp->errorRate = (0.95f * prev_mp->errorRate) + (0.001064f * (float)cur_mp->errorCountTotal);
    return 0;
}

static void
ambe2400_update_decode_state(int bad, int c0_errors, int total_errors, mbe_process_result* result, mbe_parms* cur_mp,
                             const mbe_parms* prev_mp) {
    if (bad == 2) {
        mbe_result_set_flag(result, MBE_PROCESS_FLAG_ERASURE);
        cur_mp->repeatCount = 0;
        mbe_setAmbeErasureParms_common(cur_mp, prev_mp);
        return;
    }
    if (bad == 3) {
        // A "tone frame" is a rare, spec-reserved signalling frame (b0
        // pitch code pinned to 126/127) distinct from ordinary voice.
        // Trusting it under ANY corrected bit errors is what let ordinary,
        // slightly-noisy voice frames get misclassified as tone frames and
        // rendered as a loud, fixed-amplitude sine tone (see
        // mbe_synthesizeTonefdstar) mixed right under the real voice --
        // heard as a constant, DMR-only "beat" for the rest of the
        // transmission (JMBE's issue #31 is the same failure class:
        // "random AMBE tone frames were being generated during high bit
        // error rate decoding"). The D-STAR/YSF path (ambe3600x2450.c)
        // avoids this because ambe2450_synthesize_tone() falls back to
        // reusing the prior voice model on an unconvincing tone candidate
        // instead of always rendering a tone.
        //
        // NOTE: this guard used to live a few lines further down, gating
        // an unrelated "bad in [7,122]" branch that this bad==3 case never
        // reaches (bad==3 always returns above before that check runs) --
        // so the "require a clean decode" protection it describes was
        // never actually applied to the one path that renders an audible
        // tone. Applying it here, directly on the bad==3 branch, is the
        // actual fix: require a completely clean decode before trusting
        // the classification -- tone frames are rare enough that losing
        // an occasional real one costs far less than injecting a false
        // one into ordinary speech. Under any bit errors, fall through to
        // the ordinary repeat/voice handling below instead.
        if ((c0_errors == 0) && (total_errors == 0)) {
            mbe_result_set_flag(result, MBE_PROCESS_FLAG_TONE);
            cur_mp->repeatCount = 0;
            return;
        }
        // Unconvincing tone candidate under bit errors -- do not render a
        // tone; fall through to the repeat/erasure handling below.
    }
    if ((bad >= 5) && (bad <= 122) && (c0_errors == 0) && (total_errors == 0)) {
        return;
    }
    if (total_errors > 3) {
        mbe_useLastMbeParms(cur_mp, prev_mp);
        cur_mp->repeatCount++;
        mbe_result_set_flag(result, MBE_PROCESS_FLAG_REPEAT);
        return;
    }

    cur_mp->repeatCount = 0;
}

static void
ambe2400_synthesize_voice(float* aout_buf, mbe_process_result* result, mbe_parms* cur_mp, mbe_parms* prev_mp,
                          mbe_parms* prev_mp_enhanced) {
    if (cur_mp->repeatCount < MBE_MAX_FRAME_REPEATS) {
        mbe_moveMbeParms(cur_mp, prev_mp);
        float pre_enh_rm0 = mbe_spectralAmpEnhanceWithRm0(cur_mp);
        mbe_synthesizeSpeechWithPreEnhRm0f(aout_buf, cur_mp, prev_mp_enhanced, pre_enh_rm0);
        mbe_moveMbeParms(cur_mp, prev_mp_enhanced);
        return;
    }

    mbe_result_set_flag(result, MBE_PROCESS_FLAG_MUTE);
    mbe_synthesizeComfortNoisef(aout_buf);
    mbe_initAmbeParms_common(cur_mp, prev_mp, prev_mp_enhanced);
}

static void
ambe2400_synthesize_erasure(float* aout_buf, const mbe_parms* cur_mp, mbe_parms* prev_mp, mbe_parms* prev_mp_enhanced) {
    mbe_synthesizeComfortNoisef(aout_buf);
    mbe_moveMbeParms(cur_mp, prev_mp);
    mbe_moveMbeParms(cur_mp, prev_mp_enhanced);
}

static void
ambe2400_synthesize_frame(float* aout_buf, mbe_process_result* result, const char ambe_d[49], int bad, int c0_errors,
                          int total_errors, mbe_parms* cur_mp, mbe_parms* prev_mp, mbe_parms* prev_mp_enhanced) {
    if ((bad >= 7) && (bad <= 122) && (c0_errors == 0) && (total_errors == 0)) {
        mbe_synthesizeTonefdstar(aout_buf, ambe_d, cur_mp, bad);
        mbe_moveMbeParms(cur_mp, prev_mp);
        return;
    }
    if (bad == 0) {
        ambe2400_synthesize_voice(aout_buf, result, cur_mp, prev_mp, prev_mp_enhanced);
        return;
    }
    if (bad == 2) {
        ambe2400_synthesize_erasure(aout_buf, cur_mp, prev_mp, prev_mp_enhanced);
        return;
    }
    if ((bad >= 7) && (bad <= 122)) {
        // Same rare/high-impact tone code, but didn't clear the strict
        // clean-decode bar above -- most likely ordinary voice nudged
        // into the reserved b0=126/127 codes by a bit error, not a real
        // tone. Smoothly continue the previous voice frame rather than
        // gambling on a possibly-bogus tone or hard-muting to silence.
        ambe2400_synthesize_erasure(aout_buf, cur_mp, prev_mp, prev_mp_enhanced);
        return;
    }

    mbe_synthesizeComfortNoisef(aout_buf);
    mbe_initAmbeParms_common(cur_mp, prev_mp, prev_mp_enhanced);
}

int
mbe_processAmbe2400Dataf(float* aout_buf, mbe_process_result* result, const char ambe_d[49], mbe_parms* cur_mp,
                         mbe_parms* prev_mp, mbe_parms* prev_mp_enhanced) {
    mbe_process_result local_result;
    int bad;
    int c0_errors;
    int total_errors;
    int ret;

    if (!result) {
        mbe_initProcessResult(&local_result);
        result = &local_result;
    }
    if (!aout_buf) {
        return MBE_STATUS_INVALID_ARGUMENT;
    }
    ret = ambe2400_prepare_process(result, ambe_d, cur_mp, prev_mp, prev_mp_enhanced, &total_errors, &c0_errors);
    if (ret < 0) {
        return ret;
    }

    bad = mbe_decodeAmbe2400Parms(ambe_d, cur_mp, prev_mp);
    if (bad < 0) {
        return bad;
    }

    ambe2400_update_decode_state(bad, c0_errors, total_errors, result, cur_mp, prev_mp);
    ambe2400_synthesize_frame(aout_buf, result, ambe_d, bad, c0_errors, total_errors, cur_mp, prev_mp,
                              prev_mp_enhanced);
    return result->total_errors;
}

int
mbe_processAmbe2400Data(short* aout_buf, mbe_process_result* result, const char ambe_d[49], mbe_parms* cur_mp,
                        mbe_parms* prev_mp, mbe_parms* prev_mp_enhanced) {
    float float_buf[160];
    int ret;
    if (!aout_buf) {
        return MBE_STATUS_INVALID_ARGUMENT;
    }
    ret = mbe_processAmbe2400Dataf(float_buf, result, ambe_d, cur_mp, prev_mp, prev_mp_enhanced);
    if (ret < 0) {
        return ret;
    }
    mbe_floattoshort(float_buf, aout_buf);
    return ret;
}

/**
 * @brief Process a complete AMBE 3600x2400 frame into float PCM.
 * @param aout_buf Output buffer of 160 float samples.
 * @param result   Optional output status populated by decode and synthesis.
 * @param ambe_fr  Input frame as 4x24 bitplanes.
 * @param ambe_d   Scratch/output parameter bits (49).
 * @param cur_mp,prev_mp,prev_mp_enhanced Parameter state as per Dataf variant.
 */
int
mbe_processAmbe3600x2400Framef(float* aout_buf, mbe_process_result* result, const char ambe_fr[4][24], char ambe_d[49],
                               mbe_parms* cur_mp, mbe_parms* prev_mp, mbe_parms* prev_mp_enhanced) {
    mbe_process_result local_result;
    int ret;
    if (!result) {
        result = &local_result;
    }
    ret = mbe_decodeAmbe3600x2400Frame(ambe_fr, ambe_d, result);
    if (ret < 0) {
        return ret;
    }
    return mbe_processAmbe2400Dataf(aout_buf, result, ambe_d, cur_mp, prev_mp, prev_mp_enhanced);
}

int
mbe_processAmbe3600x2400SoftFramef(float* aout_buf, mbe_process_result* result, const mbe_soft_bit ambe_fr[4][24],
                                   char ambe_d[49], mbe_parms* cur_mp, mbe_parms* prev_mp,
                                   mbe_parms* prev_mp_enhanced) {
    mbe_process_result local_result;
    int ret;
    if (!result) {
        result = &local_result;
    }
    ret = mbe_decodeAmbe3600x2400SoftFrame(ambe_fr, ambe_d, result);
    if (ret < 0) {
        return ret;
    }
    return mbe_processAmbe2400Dataf(aout_buf, result, ambe_d, cur_mp, prev_mp, prev_mp_enhanced);
}

int
mbe_processAmbe3600x2400SoftFrame(short* aout_buf, mbe_process_result* result, const mbe_soft_bit ambe_fr[4][24],
                                  char ambe_d[49], mbe_parms* cur_mp, mbe_parms* prev_mp, mbe_parms* prev_mp_enhanced) {
    float float_buf[160];
    int ret;
    if (!aout_buf) {
        return MBE_STATUS_INVALID_ARGUMENT;
    }
    ret = mbe_processAmbe3600x2400SoftFramef(float_buf, result, ambe_fr, ambe_d, cur_mp, prev_mp, prev_mp_enhanced);
    if (ret < 0) {
        return ret;
    }
    mbe_floattoshort(float_buf, aout_buf);
    return ret;
}

/**
 * @brief Process a complete AMBE 3600x2400 frame into 16-bit PCM.
 * @see mbe_processAmbe3600x2400Framef for details.
 */
int
mbe_processAmbe3600x2400Frame(short* aout_buf, mbe_process_result* result, const char ambe_fr[4][24], char ambe_d[49],
                              mbe_parms* cur_mp, mbe_parms* prev_mp, mbe_parms* prev_mp_enhanced) {
    float float_buf[160];
    int ret;

    if (!aout_buf) {
        return MBE_STATUS_INVALID_ARGUMENT;
    }
    ret = mbe_processAmbe3600x2400Framef(float_buf, result, ambe_fr, ambe_d, cur_mp, prev_mp, prev_mp_enhanced);
    if (ret < 0) {
        return ret;
    }
    mbe_floattoshort(float_buf, aout_buf);
    return ret;
}
