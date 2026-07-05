cmake_minimum_required(VERSION 3.22.1)
project(ninegradio_native)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── mbelib-neo: AMBE/IMBE vocoder (vendored in cpp/mbelib-neo/) ──────────────
# Source: https://github.com/arancormonk/mbelib-neo (GPL-2.0-or-later)
# v2.0.0 — new API: process functions return int, take mbe_process_result*.
# Compiled as a static object library so it links into dsd_neo without a
# separate .so.  All C files compile cleanly at C99.
#
# Include paths (mirroring the upstream CMakeLists.txt target_include_directories):
#   PUBLIC  : mbelib-neo/include            (mbelib-neo/mbelib.h, version.h)
#   PRIVATE : mbelib-neo/                   (project root — for src/ relative paths)
#             mbelib-neo/src/internal       (ambe_common.h, mbelib_const.h, etc.)
#             mbelib-neo/src/external/pffft (pffft.h, fftpack.h)
set(MBELIB_NEO_SOURCES
    mbelib-neo/src/ambe/ambe3600x2400.c
    mbelib-neo/src/ambe/ambe3600x2450.c
    mbelib-neo/src/ambe/ambe_common.c
    mbelib-neo/src/ecc/ecc.c
    mbelib-neo/src/ecc/ecc_const.c
    mbelib-neo/src/imbe/imbe7100x4400.c
    mbelib-neo/src/imbe/imbe7200x4400.c
    mbelib-neo/src/core/mbelib.c
    mbelib-neo/src/core/mbe_adaptive.c
    mbelib-neo/src/core/mbe_unvoiced_fft.c
    mbelib-neo/src/external/pffft/pffft.c
    mbelib-neo/src/external/pffft/fftpack.c
)

add_library(mbelib_neo_obj OBJECT ${MBELIB_NEO_SOURCES})
# MBE_STATIC suppresses dllimport decorations (same as upstream mbe-static target)
target_compile_definitions(mbelib_neo_obj PUBLIC MBE_STATIC=1)
target_include_directories(mbelib_neo_obj
    PUBLIC
        ${CMAKE_SOURCE_DIR}/mbelib-neo/include
    PRIVATE
        ${CMAKE_SOURCE_DIR}/mbelib-neo
        ${CMAKE_SOURCE_DIR}/mbelib-neo/src/internal
        ${CMAKE_SOURCE_DIR}/mbelib-neo/src/external/pffft
)
target_compile_options(mbelib_neo_obj PRIVATE
    -std=c99
    -O2
    -ffast-math
    -Wno-unused-parameter
    -Wno-unused-variable
    -Wno-missing-prototypes
    -Wno-vla
)

# ── dsdcc sources (vendor copy in cpp/dsdcc/) ─────────────────────────────────
set(DSDCC_SOURCES
    dsdcc/crc.cpp
    dsdcc/descramble.cpp
    dsdcc/dmr.cpp
    dsdcc/dpmr.cpp
    dsdcc/dsd_decoder.cpp
    dsdcc/dsd_filters.cpp
    dsdcc/dsd_logger.cpp
    dsdcc/dsd_mbe.cpp
    dsdcc/dsd_opts.cpp
    dsdcc/dsd_state.cpp
    dsdcc/dsd_symbol.cpp
    dsdcc/dsd_sync.cpp
    dsdcc/dsd_upsample.cpp
    dsdcc/dstar.cpp
    dsdcc/fec.cpp
    dsdcc/locator.cpp
    dsdcc/mbefec.cpp
    dsdcc/nxdn.cpp
    dsdcc/nxdnconvolution.cpp
    dsdcc/nxdncrc.cpp
    dsdcc/nxdnmessage.cpp
    dsdcc/p25p1_heuristics.cpp
    dsdcc/p25p1_hamming.cpp
    dsdcc/p25p1.cpp
    dsdcc/phaselock.cpp
    dsdcc/pn.cpp
    dsdcc/timeutil.cpp
    dsdcc/viterbi.cpp
    dsdcc/viterbi3.cpp
    dsdcc/viterbi5.cpp
    dsdcc/ysf.cpp
)

# ── dsd_neo: dsdcc + mbelib-neo + JNI bridge ─────────────────────────────────
#
# Provides:
#   1. DsdccNative.*  (handle-based streaming API with PCM output)
#   2. DigitalVoiceJni.nativeDecode  (legacy single-shot)
#
# Vocoder:
#   mbelib-neo built-in (statically linked, GPL-2.0+) — always available,
#   no plugin, download, or user setup required.
add_library(dsd_neo SHARED
    dsdcc_jni.cpp
    vocoder_jni.cpp
    ${DSDCC_SOURCES}
    $<TARGET_OBJECTS:mbelib_neo_obj>
)

target_include_directories(dsd_neo PRIVATE
    ${CMAKE_SOURCE_DIR}                      # for dsdcc/* includes
    ${CMAKE_SOURCE_DIR}/dsdcc
    ${CMAKE_SOURCE_DIR}/mbelib-neo/include   # mbelib-neo/mbelib.h
    ${CMAKE_SOURCE_DIR}/include              # vocoder.h, volk_android.h
)

# MBE_STATIC inherited from mbelib_neo_obj PUBLIC compile definition;
# re-declare here explicitly so dsd_neo sources also see it.
target_compile_definitions(dsd_neo PRIVATE
    dsdcc_STATIC          # suppress DSDCC_API dllimport/dllexport decoration
    DSD_USE_MBELIB        # enable dsd_mbe.cpp → mbelib vocoder path
    MBE_STATIC=1          # suppress dllimport from mbelib-neo/mbelib.h
)

target_compile_options(dsd_neo PRIVATE
    -O2
    -ffast-math
    -ftree-vectorize
    -Wno-unused-parameter
    -Wno-unused-variable
)

find_library(log-lib log)
target_link_libraries(dsd_neo
    ${log-lib}
    android
    # libm for mbelib-neo FFT / math
    m
)

# ── ninegradio_dsp: existing NEON/VOLK DSP library ───────────────────────────
add_library(ninegradio_dsp SHARED ninegradio_dsp_jni.cpp)
target_compile_options(ninegradio_dsp PRIVATE -O3 -ffast-math)
target_link_libraries(ninegradio_dsp ${log-lib} android)
