// dsd_mbelib.h — DSDmbelibParms wrapping mbelib-neo mbe_parms structs.
// Included by dsd_mbe.cpp when DSD_USE_MBELIB is defined.
// Source: https://github.com/arancormonk/mbelib-neo (vendored in cpp/mbelib-neo/)
//
// mbelib-neo v2.x API notes:
//   mbe_processAmbe/ImbeXXXXFramef() now returns int (total errors) and takes
//   mbe_process_result* instead of int* errs / int* errs2 / char* err_str.
//   The uvquality parameter has been removed in v2.x.
//   See dsd_mbe.cpp for updated call sites.

#ifndef DSDCC_DSD_MBELIB_H_
#define DSDCC_DSD_MBELIB_H_

#include "mbelib-neo/mbelib.h"  // mbe_parms, mbe_initMbeParms, mbe_process*

namespace DSDcc {

struct DSDmbelibParms {
    mbe_parms m_cur_mp;
    mbe_parms m_prev_mp;
    mbe_parms m_prev_mp_enhanced;
};

} // namespace DSDcc

// Global alias for compatibility with dsd_mbe.cpp
using DSDmbelibParms = DSDcc::DSDmbelibParms;

#endif // DSDCC_DSD_MBELIB_H_
