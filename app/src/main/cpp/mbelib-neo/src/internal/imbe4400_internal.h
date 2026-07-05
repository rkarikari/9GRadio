// SPDX-License-Identifier: GPL-2.0-or-later
/*
 * Copyright (C) 2025 by arancormonk <180709949+arancormonk@users.noreply.github.com>
 */

#ifndef MBELIB_NEO_INTERNAL_IMBE4400_INTERNAL_H
#define MBELIB_NEO_INTERNAL_IMBE4400_INTERNAL_H

#include "mbelib-neo/mbelib.h"

int mbe_processImbe4400Dataf_internal(float* aout_buf, mbe_process_result* result, const char imbe_d[88],
                                      mbe_parms* cur_mp, mbe_parms* prev_mp, mbe_parms* prev_mp_enhanced);

#endif /* MBELIB_NEO_INTERNAL_IMBE4400_INTERNAL_H */
