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
 * @brief Error-correcting code helpers for Golay and Hamming.
 */

#include <stdint.h>
#include <string.h>
#include "ecc_const.h"
#include "mbe_result.h"
#include "mbelib-neo/mbelib.h"

/*
 * Precomputed correction masks for Hamming (15,11) variants.
 * These map 4-bit syndromes to a single-bit mask (1 << bit_index) and
 * avoid any runtime initialization or data races.
 */
static const int ham1511_lut[16] = {
    /* index: syndrome [0..15] */
    0, 8, 4, 2048, 2, 512, 64, 8192, 1, 256, 32, 4096, 16, 1024, 128, 16384,
};

static const int ham1511_7100_lut[16] = {
    /* index: syndrome [0..15] */
    0, 8, 4, 64, 2, 512, 32, 2048, 1, 16384, 256, 8192, 16, 128, 1024, 4096,
};

static int
soft_bit_cost(const mbe_soft_bit* soft, int index, int candidate_bit) {
    return (((int)(soft[index].bit & 1u)) == (candidate_bit & 1)) ? 0 : (int)soft[index].reliability;
}

static int
count_bit_differences_soft(const mbe_soft_bit* soft, const char* candidate, int count) {
    int diffs = 0;
    for (int i = 0; i < count; ++i) {
        if (((int)(soft[i].bit & 1u)) != (candidate[i] & 1)) {
            ++diffs;
        }
    }
    return diffs;
}

static int
soft_decode_candidate_is_better(int have_best, int score, int best_score, int matches_hard, int best_matches_hard,
                                int diffs, int best_diffs) {
    if (!have_best || score < best_score) {
        return 1;
    }
    if (score != best_score) {
        return 0;
    }
    if (matches_hard != best_matches_hard) {
        return matches_hard;
    }
    return diffs < best_diffs;
}

static void
golay_encode_data_word(uint32_t data, char candidate[23]) {
    uint32_t ecc = 0u;

    for (int i = 0; i < 12; ++i) {
        int bit = (int)((data >> (11 - i)) & 1u);
        candidate[22 - i] = (char)bit;
        if (bit != 0) {
            ecc ^= (uint32_t)golayGenerator[i];
        }
    }
    for (int j = 10; j >= 0; --j) {
        candidate[j] = (char)((ecc >> j) & 1u);
    }
}

static int
golay_data_matches(const char* a, const char* b) {
    for (int j = 22; j >= 11; --j) {
        if ((a[j] & 1) != (b[j] & 1)) {
            return 0;
        }
    }
    return 1;
}

static int
golay_data_difference_count(const mbe_soft_bit* soft, const char* candidate) {
    int diffs = 0;
    for (int j = 22; j >= 11; --j) {
        if (((int)(soft[j].bit & 1u)) != (candidate[j] & 1)) {
            ++diffs;
        }
    }
    return diffs;
}

static int
hamming_syndrome_from_block(uint32_t block, const int generator[4]) {
    int syndrome = 0;

    for (int i = 0; i < 4; i++) {
        int stmp = (int)(block & (uint32_t)generator[i]);
        int stmp2 = (stmp & 1);
        for (int j = 0; j < 14; j++) {
            stmp >>= 1;
            stmp2 ^= (stmp & 1);
        }
        syndrome |= (stmp2 << i);
    }
    return syndrome;
}

static uint32_t
hamming_block_from_bits(const char code[15]) {
    uint32_t block = 0u;

    for (int i = 14; i >= 0; i--) {
        block <<= 1;
        block |= (uint32_t)(code[i] & 1);
    }
    return block;
}

static const int ham1511_standard_data_pos[11] = {2, 4, 5, 6, 8, 9, 10, 11, 12, 13, 14};
static const int ham1511_standard_parity_pos[4] = {0, 1, 3, 7};
static const int ham1511_7100_data_pos[11] = {4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14};
static const int ham1511_7100_parity_pos[4] = {0, 1, 2, 3};

static int
hamming_encode_data_word(uint32_t data, const int generator[4], const int data_pos[11], const int parity_pos[4],
                         char candidate[15]) {
    memset(candidate, 0, 15);
    for (int i = 0; i < 11; ++i) {
        candidate[data_pos[i]] = (char)((data >> i) & 1u);
    }

    for (int p = 0; p < 16; ++p) {
        for (int i = 0; i < 4; ++i) {
            candidate[parity_pos[i]] = (char)((p >> i) & 1);
        }
        if (hamming_syndrome_from_block(hamming_block_from_bits(candidate), generator) == 0) {
            return 1;
        }
    }
    return 0;
}

static int
hamming1511_soft_common(const mbe_soft_bit* in, char* out, const int generator[4], int variant7100) {
    char hard_out[15];
    char candidate[15];
    char best[15] = {0};
    const int* data_pos = variant7100 ? ham1511_7100_data_pos : ham1511_standard_data_pos;
    const int* parity_pos = variant7100 ? ham1511_7100_parity_pos : ham1511_standard_parity_pos;
    int best_score = 0x3fffffff;
    int best_diffs = 0x3fffffff;
    int have_best = 0;

    if (!out) {
        return MBE_STATUS_INVALID_ARGUMENT;
    }
    int ret = mbe_validate_soft_bits(in, 15u);
    if (ret < 0) {
        return ret;
    }

    char hard_in[15];
    for (int i = 0; i < 15; ++i) {
        hard_in[i] = (char)(in[i].bit & 1u);
    }
    if (variant7100) {
        (void)mbe_7100x4400hamming1511(hard_in, hard_out);
    } else {
        (void)mbe_hamming1511(hard_in, hard_out);
    }

    for (uint32_t data = 0u; data < 2048u; ++data) {
        if (!hamming_encode_data_word(data, generator, data_pos, parity_pos, candidate)) {
            continue;
        }

        int score = 0;
        for (int i = 0; i < 15; ++i) {
            score += soft_bit_cost(in, i, candidate[i]);
        }
        int diffs = count_bit_differences_soft(in, candidate, 15);
        int matches_hard = (memcmp(candidate, hard_out, 15) == 0);
        int best_matches_hard = have_best ? (memcmp(best, hard_out, 15) == 0) : 0;

        if (soft_decode_candidate_is_better(have_best, score, best_score, matches_hard, best_matches_hard, diffs,
                                            best_diffs)) {
            memcpy(best, candidate, 15);
            best_score = score;
            best_diffs = diffs;
            have_best = 1;
        }
    }

    if (!have_best) {
        memcpy(best, hard_out, 15);
        best_diffs = count_bit_differences_soft(in, best, 15);
    }

    memcpy(out, best, 15);
    return best_diffs;
}

/**
 * @brief Correct a (23,12) Golay encoded block in-place and extract data.
 * @param block Pointer to packed 23-bit codeword (LSBs contain codeword). On return, holds 12-bit data.
 */
int
mbe_checkGolayBlock(long int* block) {

    int i;
    int syndrome, eccexpected, eccbits, databits;
    uint32_t mask;
    uint32_t block_u;

    if (!block) {
        return MBE_STATUS_INVALID_ARGUMENT;
    }

    block_u = (uint32_t)(*block);

    mask = 0x400000u; /* MSB of 23-bit codeword */
    eccexpected = 0;
    for (i = 0; i < 12; i++) {
        if ((block_u & mask) != 0u) {
            eccexpected ^= golayGenerator[i];
        }
        mask >>= 1;
    }
    eccbits = (int)(block_u & 0x7ffu);
    syndrome = eccexpected ^ eccbits;

    databits = (int)(block_u >> 11);
    databits ^= golayMatrix[syndrome];

    *block = (long)databits;
    return 0;
}

/**
 * @brief Decode a (23,12) Golay codeword.
 * @param in  Input bits, LSB at index 0, length 23.
 * @param out Output bits, corrected, LSB at index 0, length 23.
 * @return Number of corrected bit errors in the protected portion.
 */
int
mbe_golay2312(const char* in, char* out) {

    int i, errs;
    uint32_t block = 0u;
    int ret;

    if (!out) {
        return MBE_STATUS_INVALID_ARGUMENT;
    }
    ret = mbe_validate_bits(in, 23u);
    if (ret < 0) {
        return ret;
    }

    for (i = 22; i >= 0; i--) {
        block <<= 1;
        block |= (uint32_t)(in[i] & 1);
    }

    long tmp = (long)block;
    ret = mbe_checkGolayBlock(&tmp);
    if (ret < 0) {
        return ret;
    }
    block = (uint32_t)tmp;

    for (i = 22; i >= 11; i--) {
        out[i] = (char)((block & 2048u) >> 11);
        block <<= 1;
    }
    for (i = 10; i >= 0; i--) {
        out[i] = in[i];
    }

    errs = 0;
    for (i = 22; i >= 11; i--) {
        if (out[i] != in[i]) {
            errs++;
        }
    }
    return errs;
}

int
mbe_golay2312Soft(const mbe_soft_bit* in, char* out) {
    char hard_in[23];
    char hard_out[23];
    char candidate[23];
    char best[23] = {0};
    int best_score = 0x3fffffff;
    int best_data_diffs = 0x3fffffff;
    int have_best = 0;
    int ret;

    if (!out) {
        return MBE_STATUS_INVALID_ARGUMENT;
    }
    ret = mbe_validate_soft_bits(in, 23u);
    if (ret < 0) {
        return ret;
    }

    for (int i = 0; i < 23; ++i) {
        hard_in[i] = (char)(in[i].bit & 1u);
    }
    (void)mbe_golay2312(hard_in, hard_out);

    for (uint32_t data = 0u; data < 4096u; ++data) {
        golay_encode_data_word(data, candidate);
        int score = 0;
        for (int i = 0; i < 23; ++i) {
            score += soft_bit_cost(in, i, candidate[i]);
        }

        int data_diffs = golay_data_difference_count(in, candidate);
        int matches_hard = golay_data_matches(candidate, hard_out);
        int best_matches_hard = have_best ? golay_data_matches(best, hard_out) : 0;

        if (soft_decode_candidate_is_better(have_best, score, best_score, matches_hard, best_matches_hard, data_diffs,
                                            best_data_diffs)) {
            memcpy(best, candidate, 23);
            best_score = score;
            best_data_diffs = data_diffs;
            have_best = 1;
        }
    }

    if (!have_best) {
        memcpy(best, hard_out, 23);
        best_data_diffs = golay_data_difference_count(in, best);
    }

    memcpy(out, best, 23);
    for (int i = 10; i >= 0; --i) {
        out[i] = hard_in[i];
    }
    return best_data_diffs;
}

/**
 * @brief Decode a (15,11) Hamming codeword.
 * @param in  Input bits, LSB at index 0, length 15.
 * @param out Output bits, corrected, LSB at index 0, length 15.
 * @return Number of corrected bit errors (0 or 1).
 * @note Uses a precomputed syndrome→bitmask LUT for thread safety (no lazy init).
 */
int
mbe_hamming1511(const char* in, char* out) {
    int i, j, errs;
    uint32_t block = 0u;
    int syndrome;
    int ret;

    if (!out) {
        return MBE_STATUS_INVALID_ARGUMENT;
    }
    ret = mbe_validate_bits(in, 15u);
    if (ret < 0) {
        return ret;
    }

    errs = 0;

    for (i = 14; i >= 0; i--) {
        block <<= 1;
        block |= (uint32_t)(in[i] & 1);
    }

    syndrome = 0;
    for (i = 0; i < 4; i++) {
        int stmp = (int)(block & (uint32_t)hammingGenerator[i]);
        int stmp2 = (stmp & 1);
        for (j = 0; j < 14; j++) {
            stmp >>= 1;
            stmp2 ^= (stmp & 1);
        }
        syndrome |= (stmp2 << i);
    }
    if (syndrome > 0) {
        errs++;
        block ^= (uint32_t)ham1511_lut[syndrome];
    }

    for (i = 14; i >= 0; i--) {
        out[i] = (char)((block & 0x4000u) >> 14);
        block <<= 1;
    }
    return errs;
}

int
mbe_hamming1511Soft(const mbe_soft_bit* in, char* out) {
    return hamming1511_soft_common(in, out, hammingGenerator, 0);
}

/**
 * @brief Decode a (15,11) Hamming codeword with IMBE 7100x4400 mapping.
 * @param in  Input bits, LSB at index 0, length 15.
 * @param out Output bits, corrected, LSB at index 0, length 15.
 * @return Number of corrected bit errors (0 or 1).
 * @note Uses a precomputed syndrome→bitmask LUT for thread safety (no lazy init).
 */
int
mbe_7100x4400hamming1511(const char* in, char* out) {
    int i, j, errs;
    uint32_t block = 0u;
    int syndrome;
    int ret;

    if (!out) {
        return MBE_STATUS_INVALID_ARGUMENT;
    }
    ret = mbe_validate_bits(in, 15u);
    if (ret < 0) {
        return ret;
    }

    errs = 0;

    for (i = 14; i >= 0; i--) {
        block <<= 1;
        block |= (uint32_t)(in[i] & 1);
    }

    syndrome = 0;
    for (i = 0; i < 4; i++) {
        int stmp = (int)(block & (uint32_t)imbe7100x4400hammingGenerator[i]);
        int stmp2 = (stmp & 1);
        for (j = 0; j < 14; j++) {
            stmp >>= 1;
            stmp2 ^= (stmp & 1);
        }
        syndrome |= (stmp2 << i);
    }
    if (syndrome > 0) {
        errs++;
        block ^= (uint32_t)ham1511_7100_lut[syndrome];
    }

    for (i = 14; i >= 0; i--) {
        out[i] = (char)((block & 0x4000u) >> 14);
        block <<= 1;
    }
    return errs;
}

int
mbe_7100x4400hamming1511Soft(const mbe_soft_bit* in, char* out) {
    return hamming1511_soft_common(in, out, imbe7100x4400hammingGenerator, 1);
}
