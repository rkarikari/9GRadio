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
 * @brief IMBE 7100x4400 parameter decode, ECC, and synthesis hooks.
 */

#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "imbe4400_internal.h"
#include "mbe_bitpack.h"
#include "mbe_result.h"
#include "mbelib-neo/mbelib.h"

/**
 * @brief Print IMBE 7100x4400 parameter bits to stderr (debug aid).
 * @param imbe_d IMBE parameter bits (88).
 */
void
mbe_dumpImbe7100x4400Data(const char* imbe_d) {

    int i;
    const char* imbe;

    imbe = imbe_d;
    for (i = 0; i < 88; i++) {
        if ((i == 7) || (i == 19) || (i == 31) || (i == 43) || (i == 54) || (i == 65)) {
            fprintf(stderr, " ");
        }
        fprintf(stderr, "%i", *imbe);
        imbe++;
    }
}

/**
 * @brief Print raw IMBE 7100x4400 frame bitplanes to stderr.
 * @param imbe_fr Frame as 7x24 bitplanes.
 */
void
mbe_dumpImbe7100x4400Frame(const char imbe_fr[7][24]) {

    int i, j;

    for (j = 18; j >= 0; j--) {
        if (j == 11) {
            fprintf(stderr, " ");
        }
        fprintf(stderr, "%i", imbe_fr[0][j]);
    }
    fprintf(stderr, " ");

    for (j = 23; j >= 0; j--) {
        if (j == 11) {
            fprintf(stderr, " ");
        }
        fprintf(stderr, "%i", imbe_fr[1][j]);
    }
    fprintf(stderr, " ");

    for (i = 2; i < 4; i++) {
        for (j = 22; j >= 0; j--) {
            if (j == 10) {
                fprintf(stderr, " ");
            }
            fprintf(stderr, "%i", imbe_fr[i][j]);
        }
        fprintf(stderr, " ");
    }
    for (i = 4; i < 6; i++) {
        for (j = 14; j >= 0; j--) {
            if (j == 3) {
                fprintf(stderr, " ");
            }
            fprintf(stderr, "%i", imbe_fr[i][j]);
        }
        fprintf(stderr, " ");
    }
    for (j = 22; j >= 0; j--) {
        fprintf(stderr, "%i", imbe_fr[6][j]);
    }
}

/**
 * @brief Apply ECC to IMBE 7100x4400 C0 and update in-place.
 * @param imbe_fr Frame as 7x24 bitplanes.
 * @return Number of corrected errors in C0.
 */
int
mbe_eccImbe7100x4400C0(char imbe_fr[7][24]) {

    int j, errs;
    char in[23], out[23];
    int ret = mbe_validate_bits((const char*)imbe_fr, (size_t)7u * 24u);
    if (ret < 0) {
        return ret;
    }

    for (j = 0; j < 18; j++) {
        in[j] = imbe_fr[0][j + 1];
    }
    for (j = 18; j < 23; j++) {
        in[j] = 0;
    }

    errs = mbe_golay2312(in, out);
    for (j = 0; j < 18; j++) {
        imbe_fr[0][j + 1] = out[j];
    }

    return errs;
}

static int
mbe_eccImbe7100x4400C0Soft(mbe_soft_bit imbe_fr[7][24]) {
    int j, errs;
    mbe_soft_bit in[23];
    char out[23];

    for (j = 0; j < 18; j++) {
        in[j] = imbe_fr[0][j + 1];
    }
    for (j = 18; j < 23; j++) {
        in[j] = mbe_softBitFromHard(0, 255u);
    }

    errs = mbe_golay2312Soft(in, out);
    for (j = 0; j < 18; j++) {
        imbe_fr[0][j + 1].bit = (uint8_t)(out[j] & 1);
    }

    return errs;
}

/**
 * @brief Internal: Apply ECC to IMBE 7100x4400 data with separate C4 error tracking.
 * @param imbe_fr Frame as 7x24 bitplanes.
 * @param imbe_d  Output parameter bits (88).
 * @param errs_c4 Output: errors in C4 (Hamming coset 4), or NULL if not needed.
 * @return Number of corrected errors in protected fields.
 */
static int
mbe_eccImbe7100x4400DataInternal(char imbe_fr[7][24], char* imbe_d, int* errs_c4) {

    int i, j, errs;
    char *imbe, gin[23], gout[23], hin[15], hout[15];

    /* initialize errs implicitly via first ECC call below */
    imbe = imbe_d;

    // just copy C0
    for (j = 18; j > 11; j--) {
        *imbe = imbe_fr[0][j];
        imbe++;
    }

    // ecc and copy C1
    for (j = 0; j < 23; j++) {
        gin[j] = imbe_fr[1][j + 1];
    }
    errs = mbe_golay2312(gin, gout);
    for (j = 22; j > 10; j--) {
        *imbe = gout[j];
        imbe++;
    }

    // ecc and copy C2, C3
    for (i = 2; i < 4; i++) {
        for (j = 0; j < 23; j++) {
            gin[j] = imbe_fr[i][j];
        }
        errs += mbe_golay2312(gin, gout);
        for (j = 22; j > 10; j--) {
            *imbe = gout[j];
            imbe++;
        }
    }
    // ecc and copy C4, C5
    for (i = 4; i < 6; i++) {
        for (j = 0; j < 15; j++) {
            hin[j] = imbe_fr[i][j];
        }
        int hamming_errs = mbe_7100x4400hamming1511(hin, hout);
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

    // just copy C6
    for (j = 22; j >= 0; j--) {
        *imbe = imbe_fr[6][j];
        imbe++;
    }

    return errs;
}

static int
mbe_eccImbe7100x4400DataSoftInternal(mbe_soft_bit imbe_fr[7][24], char* imbe_d, int* errs_c4) {
    int i, j, errs;
    char *imbe, gout[23], hout[15];
    mbe_soft_bit gin[23], hin[15];

    imbe = imbe_d;

    for (j = 18; j > 11; j--) {
        *imbe = (char)(imbe_fr[0][j].bit & 1u);
        imbe++;
    }

    for (j = 0; j < 23; j++) {
        gin[j] = imbe_fr[1][j + 1];
    }
    errs = mbe_golay2312Soft(gin, gout);
    for (j = 22; j > 10; j--) {
        *imbe = gout[j];
        imbe++;
    }

    for (i = 2; i < 4; i++) {
        for (j = 0; j < 23; j++) {
            gin[j] = imbe_fr[i][j];
        }
        errs += mbe_golay2312Soft(gin, gout);
        for (j = 22; j > 10; j--) {
            *imbe = gout[j];
            imbe++;
        }
    }
    for (i = 4; i < 6; i++) {
        for (j = 0; j < 15; j++) {
            hin[j] = imbe_fr[i][j];
        }
        int hamming_errs = mbe_7100x4400hamming1511Soft(hin, hout);
        errs += hamming_errs;
        if (i == 4 && errs_c4 != NULL) {
            *errs_c4 = hamming_errs;
        }
        for (j = 14; j >= 4; j--) {
            *imbe = hout[j];
            imbe++;
        }
    }

    for (j = 22; j >= 0; j--) {
        *imbe = (char)(imbe_fr[6][j].bit & 1u);
        imbe++;
    }

    return errs;
}

/**
 * @brief Apply ECC to IMBE 7100x4400 data and pack parameter bits.
 * @param imbe_fr Frame as 7x24 bitplanes.
 * @param imbe_d  Output parameter bits (88).
 * @return Number of corrected errors in protected fields.
 */
int
mbe_eccImbe7100x4400Data(char imbe_fr[7][24], char* imbe_d) {
    if (!imbe_d) {
        return MBE_STATUS_INVALID_ARGUMENT;
    }
    int ret = mbe_validate_bits((const char*)imbe_fr, (size_t)7u * 24u);
    if (ret < 0) {
        return ret;
    }
    return mbe_eccImbe7100x4400DataInternal(imbe_fr, imbe_d, NULL);
}

/**
 * @brief Demodulate interleaved IMBE 7100x4400 data in-place.
 * @param imbe Frame as 7x24 bitplanes (modified).
 */
int
mbe_demodulateImbe7100x4400Data(char imbe[7][24]) {

    int i, j, k;
    unsigned short pr[115];
    unsigned short seed;
    int ret = mbe_validate_bits((const char*)imbe, (size_t)7u * 24u);
    if (ret < 0) {
        return ret;
    }

    // create pseudo-random modulator
    seed = (unsigned short)mbe_bits_descending_to_int(imbe[0], 18, 12);
    pr[0] = (16 * seed);
    for (i = 1; i < 101; i++) {
        pr[i] = (173 * pr[i - 1]) + 13849 - (65536 * (((173 * pr[i - 1]) + 13849) / 65536));
    }
    /* retain pr[100] only for legacy reference; 'seed' not used afterward */
    for (i = 1; i < 101; i++) {
        pr[i] >>= 15; /* normalize to {0,1} cheaply */
    }

    // demodulate imbe with pr
    k = 1;
    for (j = 23; j >= 0; j--) {
        imbe[1][j] = ((imbe[1][j]) ^ pr[k]);
        k++;
    }

    for (i = 2; i < 4; i++) {
        for (j = 22; j >= 0; j--) {
            imbe[i][j] = ((imbe[i][j]) ^ pr[k]);
            k++;
        }
    }

    for (i = 4; i < 6; i++) {
        for (j = 14; j >= 0; j--) {
            imbe[i][j] = ((imbe[i][j]) ^ pr[k]);
            k++;
        }
    }
    return 0;
}

static void
mbe_demodulateImbe7100x4400DataSoft(mbe_soft_bit imbe[7][24]) {
    int i, j, k;
    unsigned short pr[115];
    unsigned short seed;

    seed = 0;
    for (i = 18; i > 11; i--) {
        seed <<= 1;
        seed |= (unsigned short)(imbe[0][i].bit & 1u);
    }
    pr[0] = (unsigned short)(16 * seed);
    for (i = 1; i < 101; i++) {
        pr[i] = (unsigned short)((173 * pr[i - 1]) + 13849 - (65536 * (((173 * pr[i - 1]) + 13849) / 65536)));
    }
    for (i = 1; i < 101; i++) {
        pr[i] >>= 15;
    }

    k = 1;
    for (j = 23; j >= 0; j--) {
        imbe[1][j].bit = (uint8_t)((imbe[1][j].bit & 1u) ^ (uint8_t)pr[k]);
        k++;
    }

    for (i = 2; i < 4; i++) {
        for (j = 22; j >= 0; j--) {
            imbe[i][j].bit = (uint8_t)((imbe[i][j].bit & 1u) ^ (uint8_t)pr[k]);
            k++;
        }
    }

    for (i = 4; i < 6; i++) {
        for (j = 14; j >= 0; j--) {
            imbe[i][j].bit = (uint8_t)((imbe[i][j].bit & 1u) ^ (uint8_t)pr[k]);
            k++;
        }
    }
}

/**
 * @brief Convert IMBE 7100x4400 parameter layout to 7200x4400 layout.
 * @param imbe_d In/out parameter vector (88 bits), converted in-place.
 */
int
mbe_convertImbe7100to7200(char* imbe_d) {

    int i, j, k, K, L, b0;
    char tmp_imbe[88];
    float w0;
    int ret = mbe_validate_bits(imbe_d, 88u);
    if (ret < 0) {
        return ret;
    }

    // decode fundamental frequency w0 from b0
    static const unsigned b0_indices[] = {1u, 2u, 3u, 4u, 5u, 6u, 86u, 87u};
    b0 = mbe_bits_by_index_to_int(imbe_d, b0_indices, sizeof(b0_indices) / sizeof(b0_indices[0]));
    w0 = ((float)(4 * M_PI) / (float)((float)b0 + 39.5));

    // decode L from w0
    L = (int)(0.9254 * (int)((M_PI / w0) + 0.25));

    // decode K from L
    if (L < 37) {
        K = (int)((float)(L + 2) / (float)3);
    } else {
        K = 12;
    }

    // rearrange bits from imbe7100x4400 format to imbe7200x4400 format
    tmp_imbe[87] = imbe_d[0];      // "status"/zero bit
    tmp_imbe[48 + K] = imbe_d[42]; // b2.2
    tmp_imbe[49 + K] = imbe_d[43]; // b2.1

    k = 44;
    j = 48;
    for (i = 0; i < K; i++) {
        tmp_imbe[j] = imbe_d[k]; // b1
        j++;
        k++;
    }

    j = 0;
    k = 1;
    while (j < 87) {
        tmp_imbe[j] = imbe_d[k];
        if (++j == 48) {
            j += (K + 2); // skip over b1, b2.2, b2.1 on dest
        }
        if (++k == 42) {
            k += (K + 2); // skip over b2.2, b2.1, b1 on src
        }
    }

    //copy new format back to imbe_d

    for (i = 0; i < 88; i++) {
        imbe_d[i] = tmp_imbe[i];
    }
    return 0;
}

int
mbe_decodeImbe7100x4400Frame(const char imbe_fr[7][24], char imbe_d[88], mbe_process_result* result) {
    char fr[7][24];
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
    ret = mbe_validate_bits((const char*)imbe_fr, (size_t)7u * 24u);
    if (ret < 0) {
        return ret;
    }

    memcpy(fr, imbe_fr, sizeof(fr));
    c0_errors = mbe_eccImbe7100x4400C0(fr);
    ret = mbe_demodulateImbe7100x4400Data(fr);
    if (ret < 0) {
        return ret;
    }
    protected_errors = mbe_eccImbe7100x4400DataInternal(fr, imbe_d, &c4_errors);
    ret = mbe_convertImbe7100to7200(imbe_d);
    if (ret < 0) {
        return ret;
    }

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
mbe_decodeImbe7100x4400SoftFrame(const mbe_soft_bit imbe_fr[7][24], char imbe_d[88], mbe_process_result* result) {
    mbe_soft_bit fr[7][24];
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
    ret = mbe_validate_soft_bits((const mbe_soft_bit*)imbe_fr, (size_t)7u * 24u);
    if (ret < 0) {
        return ret;
    }

    memcpy(fr, imbe_fr, sizeof(fr));
    c0_errors = mbe_eccImbe7100x4400C0Soft(fr);
    mbe_demodulateImbe7100x4400DataSoft(fr);
    protected_errors = mbe_eccImbe7100x4400DataSoftInternal(fr, imbe_d, &c4_errors);
    ret = mbe_convertImbe7100to7200(imbe_d);
    if (ret < 0) {
        return ret;
    }

    if (result) {
        result->c0_errors = c0_errors;
        result->protected_errors = protected_errors;
        result->c4_errors = c4_errors;
        result->total_errors = c0_errors + protected_errors;
        result->flags = MBE_PROCESS_FLAG_SOFT_INPUT | MBE_PROCESS_FLAG_C0_VALID | MBE_PROCESS_FLAG_C4_VALID;
    }
    return c0_errors + protected_errors;
}

/**
 * @brief Process a complete IMBE 7100x4400 frame into float PCM.
 * @param aout_buf Output buffer of 160 float samples.
 * @param result   Optional output status populated by decode and synthesis.
 * @param imbe_fr  Input frame as 7x24 bitplanes.
 * @param imbe_d   Scratch/output parameter bits (88).
 * @param cur_mp,prev_mp,prev_mp_enhanced Parameter state as per Dataf variant.
 */
int
mbe_processImbe7100x4400Framef(float* aout_buf, mbe_process_result* result, const char imbe_fr[7][24], char imbe_d[88],
                               mbe_parms* cur_mp, mbe_parms* prev_mp, mbe_parms* prev_mp_enhanced) {
    mbe_process_result local_result;
    int ret;
    if (!result) {
        result = &local_result;
    }
    ret = mbe_decodeImbe7100x4400Frame(imbe_fr, imbe_d, result);
    if (ret < 0) {
        return ret;
    }
    return mbe_processImbe4400Dataf_internal(aout_buf, result, imbe_d, cur_mp, prev_mp, prev_mp_enhanced);
}

int
mbe_processImbe7100x4400SoftFramef(float* aout_buf, mbe_process_result* result, const mbe_soft_bit imbe_fr[7][24],
                                   char imbe_d[88], mbe_parms* cur_mp, mbe_parms* prev_mp,
                                   mbe_parms* prev_mp_enhanced) {
    mbe_process_result local_result;
    int ret;
    if (!result) {
        result = &local_result;
    }
    ret = mbe_decodeImbe7100x4400SoftFrame(imbe_fr, imbe_d, result);
    if (ret < 0) {
        return ret;
    }
    return mbe_processImbe4400Dataf_internal(aout_buf, result, imbe_d, cur_mp, prev_mp, prev_mp_enhanced);
}

int
mbe_processImbe7100x4400SoftFrame(short* aout_buf, mbe_process_result* result, const mbe_soft_bit imbe_fr[7][24],
                                  char imbe_d[88], mbe_parms* cur_mp, mbe_parms* prev_mp, mbe_parms* prev_mp_enhanced) {
    float float_buf[160];
    int ret;
    if (!aout_buf) {
        return MBE_STATUS_INVALID_ARGUMENT;
    }
    ret = mbe_processImbe7100x4400SoftFramef(float_buf, result, imbe_fr, imbe_d, cur_mp, prev_mp, prev_mp_enhanced);
    if (ret < 0) {
        return ret;
    }
    mbe_floattoshort(float_buf, aout_buf);
    return ret;
}

/**
 * @brief Process a complete IMBE 7100x4400 frame into 16-bit PCM.
 * @see mbe_processImbe7100x4400Framef for details.
 */
int
mbe_processImbe7100x4400Frame(short* aout_buf, mbe_process_result* result, const char imbe_fr[7][24], char imbe_d[88],
                              mbe_parms* cur_mp, mbe_parms* prev_mp, mbe_parms* prev_mp_enhanced) {

    float float_buf[160];
    int ret;
    if (!aout_buf) {
        return MBE_STATUS_INVALID_ARGUMENT;
    }
    ret = mbe_processImbe7100x4400Framef(float_buf, result, imbe_fr, imbe_d, cur_mp, prev_mp, prev_mp_enhanced);
    if (ret < 0) {
        return ret;
    }
    mbe_floattoshort(float_buf, aout_buf);
    return ret;
}
