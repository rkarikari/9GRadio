// SPDX-License-Identifier: GPL-2.0-or-later
/*
 * Copyright (C) 2026 by arancormonk <180709949+arancormonk@users.noreply.github.com>
 */

#ifndef MBELIB_NEO_INTERNAL_MBE_VALIDATION_H
#define MBELIB_NEO_INTERNAL_MBE_VALIDATION_H

#define MBE_MIN_HARMONIC_BANDS 1
#define MBE_MAX_HARMONIC_BANDS 56
#define MBE_MAX_FRAME_BITS     184

static inline int
mbe_harmonic_count_is_valid(int L) {
    return L >= MBE_MIN_HARMONIC_BANDS && L <= MBE_MAX_HARMONIC_BANDS;
}

static inline int
mbe_error_count_is_valid(int count) {
    return count >= 0 && count <= MBE_MAX_FRAME_BITS;
}

#endif /* MBELIB_NEO_INTERNAL_MBE_VALIDATION_H */
