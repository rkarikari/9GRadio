// SPDX-License-Identifier: GPL-2.0-or-later
/*
 * Copyright (C) 2025 by arancormonk <180709949+arancormonk@users.noreply.github.com>
 */

/**
 * @file
 * @brief Compiler-specific macros for optimization hints and portability.
 */

#ifndef MBELIB_NEO_INTERNAL_MBE_COMPILER_H
#define MBELIB_NEO_INTERNAL_MBE_COMPILER_H

/**
 * @brief Branch prediction hint for likely conditions.
 *
 * Use in hot loops where the condition is almost always true.
 * Example: if (MBE_LIKELY(ptr != NULL))
 */
#if defined(__GNUC__) || defined(__clang__)
#define MBE_LIKELY(x)   __builtin_expect(!!(x), 1)
#define MBE_UNLIKELY(x) __builtin_expect(!!(x), 0)
#else
#define MBE_LIKELY(x)   (x)
#define MBE_UNLIKELY(x) (x)
#endif

/**
 * @brief Thread-local storage specifier.
 *
 * Portable macro for thread-local variables across compilers.
 */
#if defined(_MSC_VER)
#define MBE_THREAD_LOCAL __declspec(thread)
#elif defined(__GNUC__) || defined(__clang__)
#define MBE_THREAD_LOCAL __thread
#elif defined(__STDC_VERSION__) && (__STDC_VERSION__ >= 201112L)
#define MBE_THREAD_LOCAL _Thread_local
#else
/* Fallback for non-C11 compilers: most support __thread as an extension */
#define MBE_THREAD_LOCAL __thread
#endif

/**
 * @brief Cache line alignment specifier.
 *
 * Portable macro for aligning struct members or variables to cache line
 * boundaries (typically 64 bytes). Helps avoid false sharing in SIMD
 * operations and improves memory access patterns.
 *
 * Usage: MBE_ALIGNAS(64) float buffer[256];
 */
#if defined(_MSC_VER)
#define MBE_ALIGNAS(n) __declspec(align(n))
#elif defined(__GNUC__) || defined(__clang__)
#define MBE_ALIGNAS(n) __attribute__((aligned(n)))
#elif defined(__STDC_VERSION__) && (__STDC_VERSION__ >= 201112L)
#define MBE_ALIGNAS(n) _Alignas(n)
#else
/* Fallback: no alignment (may reduce SIMD performance) */
#define MBE_ALIGNAS(n)
#endif

/**
 * @brief Default cache line size for alignment.
 *
 * 64 bytes is standard for most modern x86 and ARM processors.
 */
#define MBE_CACHE_LINE_SIZE 64

/**
 * @brief Normalized architecture and SIMD-target detection helpers.
 *
 * Keep these checks centralized so all translation units make the same
 * SSE2-vs-NEON decision. ARM64EC must be treated as an ARM64 target even
 * though MSVC also defines `_M_X64` for that environment.
 *
 * `MBE_SIMD_TARGET_*` describes the ISA the current translation unit is
 * compiled to use. It is a compile-time capability signal, not a runtime
 * CPU-feature probe.
 *
 * The test suite can force synthetic detection scenarios through the
 * `MBE_TEST_OVERRIDE_*` hooks below without redefining compiler-provided
 * target macros. When an architecture override is active, SIMD inference
 * must come from the normalized architecture macros so the host compiler's
 * native target macros do not leak into the synthetic scenario.
 */
#if (defined(MBE_TEST_OVERRIDE_ARCH_X86_64) && defined(MBE_TEST_OVERRIDE_ARCH_ARM64EC))                                \
    || (defined(MBE_TEST_OVERRIDE_ARCH_X86_64) && defined(MBE_TEST_OVERRIDE_ARCH_X86_32))                              \
    || (defined(MBE_TEST_OVERRIDE_ARCH_ARM64EC) && defined(MBE_TEST_OVERRIDE_ARCH_X86_32))
#error "MBE test arch overrides are mutually exclusive"
#endif

#if defined(MBE_TEST_OVERRIDE_SIMD_SSE2) && defined(MBE_TEST_OVERRIDE_SIMD_NEON)
#error "MBE test SIMD overrides are mutually exclusive"
#endif

#if defined(MBE_TEST_OVERRIDE_ARCH_X86_64)
#define MBE_ARCH_X86_64 1
#elif defined(MBE_TEST_OVERRIDE_ARCH_ARM64EC)
#define MBE_ARCH_ARM64EC 1
#define MBE_ARCH_AARCH64 1
#elif defined(MBE_TEST_OVERRIDE_ARCH_X86_32)
#define MBE_ARCH_X86_32 1
#else
#if defined(_M_ARM64EC)
#define MBE_ARCH_ARM64EC 1
#endif

#if defined(__aarch64__) || defined(_M_ARM64) || defined(MBE_ARCH_ARM64EC)
#define MBE_ARCH_AARCH64 1
#endif

#if (defined(__x86_64__) || defined(_M_X64) || defined(_M_AMD64)) && !defined(MBE_ARCH_ARM64EC)
#define MBE_ARCH_X86_64 1
#endif

#if defined(__i386__) || defined(_M_IX86)
#define MBE_ARCH_X86_32 1
#endif
#endif

#if defined(MBE_TEST_OVERRIDE_SIMD_NEON)
#define MBE_SIMD_TARGET_NEON 1
#elif defined(MBE_TEST_OVERRIDE_SIMD_SSE2)
#define MBE_SIMD_TARGET_SSE2 1
#elif defined(MBE_TEST_OVERRIDE_ARCH_X86_64) || defined(MBE_TEST_OVERRIDE_ARCH_ARM64EC)                                \
    || defined(MBE_TEST_OVERRIDE_ARCH_X86_32)
#if defined(MBE_ARCH_AARCH64)
#define MBE_SIMD_TARGET_NEON 1
#endif

#if defined(MBE_ARCH_X86_64) && !defined(MBE_ARCH_ARM64EC)
#define MBE_SIMD_TARGET_SSE2 1
#elif defined(MBE_ARCH_X86_32) && defined(_M_IX86_FP) && (_M_IX86_FP >= 2)
#define MBE_SIMD_TARGET_SSE2 1
#endif
#else
#if defined(__ARM_NEON) || defined(__ARM_NEON__) || defined(MBE_ARCH_AARCH64)
#define MBE_SIMD_TARGET_NEON 1
#endif

#if !defined(MBE_ARCH_ARM64EC)                                                                                         \
    && (defined(MBE_ARCH_X86_64)                                                                                       \
        || (defined(MBE_ARCH_X86_32) && (defined(__SSE2__) || (defined(_M_IX86_FP) && (_M_IX86_FP >= 2)))))
#define MBE_SIMD_TARGET_SSE2 1
#endif
#endif

#endif /* MBELIB_NEO_INTERNAL_MBE_COMPILER_H */
