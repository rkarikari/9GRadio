// SPDX-License-Identifier: GPL-2.0-or-later
/*
 * Copyright (C) 2025 by arancormonk <180709949+arancormonk@users.noreply.github.com>
 */

#ifndef MBELIB_NEO_INTERNAL_MBE_TONE_H
#define MBELIB_NEO_INTERNAL_MBE_TONE_H

struct mbe_tone_frequency {
    float freq1;
    float freq2;
};

static inline int
mbe_tone_lookup_freqs(int tone_id, float* freq1, float* freq2) {
    static const struct mbe_tone_frequency dual_tones[36] = {
        {1336.0f, 941.0f}, {1209.0f, 697.0f}, {1336.0f, 697.0f}, {1477.0f, 697.0f}, {1209.0f, 770.0f},
        {1336.0f, 770.0f}, {1477.0f, 770.0f}, {1209.0f, 852.0f}, {1336.0f, 852.0f}, {1477.0f, 852.0f},
        {1633.0f, 697.0f}, {1633.0f, 770.0f}, {1633.0f, 852.0f}, {1633.0f, 941.0f}, {1209.0f, 941.0f},
        {1477.0f, 941.0f}, {1162.0f, 820.0f}, {1052.0f, 606.0f}, {1162.0f, 606.0f}, {1279.0f, 606.0f},
        {1052.0f, 672.0f}, {1162.0f, 672.0f}, {1279.0f, 672.0f}, {1052.0f, 743.0f}, {1162.0f, 743.0f},
        {1279.0f, 743.0f}, {1430.0f, 606.0f}, {1430.0f, 672.0f}, {1430.0f, 743.0f}, {1430.0f, 820.0f},
        {1052.0f, 820.0f}, {1279.0f, 820.0f}, {440.0f, 350.0f},  {480.0f, 440.0f},  {620.0f, 480.0f},
        {490.0f, 350.0f},
    };

    *freq1 = 0.0f;
    *freq2 = 0.0f;

    if (tone_id == 5) {
        *freq1 = 156.25f;
        *freq2 = *freq1;
        return 1;
    }
    if (tone_id == 6) {
        *freq1 = 187.5f;
        *freq2 = *freq1;
        return 1;
    }
    if ((tone_id >= 7) && (tone_id <= 122)) {
        *freq1 = 31.25f * (float)tone_id;
        *freq2 = *freq1;
        return 1;
    }
    if ((tone_id >= 128) && (tone_id <= 163)) {
        const struct mbe_tone_frequency tone = dual_tones[tone_id - 128];
        *freq1 = tone.freq1;
        *freq2 = tone.freq2;
        return 1;
    }

    return 0;
}

static inline int
mbe_tone_id_is_valid(int tone_id) {
    float freq1, freq2;
    return mbe_tone_lookup_freqs(tone_id, &freq1, &freq2);
}

#endif /* MBELIB_NEO_INTERNAL_MBE_TONE_H */
