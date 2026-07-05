// SPDX-License-Identifier: GPL-2.0-or-later
/*
 * Copyright (C) 2026 by arancormonk <180709949+arancormonk@users.noreply.github.com>
 */

#ifndef MBELIB_NEO_INTERNAL_MBE_BITPACK_H
#define MBELIB_NEO_INTERNAL_MBE_BITPACK_H

#include <stddef.h>

static inline int
mbe_bits_by_index_to_int(const char* bits, const unsigned* indices, size_t count) {
    int value = 0;
    for (size_t i = 0u; i < count; ++i) {
        value = (value << 1) | (int)(bits[indices[i]] & 1);
    }
    return value;
}

static inline int
mbe_bits_descending_to_int(const char* bits, int high, int low) {
    int value = 0;
    for (int i = high; i >= low; --i) {
        value = (value << 1) | (int)(bits[i] & 1);
    }
    return value;
}

#endif /* MBELIB_NEO_INTERNAL_MBE_BITPACK_H */
