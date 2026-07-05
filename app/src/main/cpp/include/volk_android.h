/**
 * volk_android.h — Vendored VOLK kernel implementations for Android/ARM.
 *
 * Extracted from GNU Radio VOLK (https://github.com/bastibl/volk),
 * licensed GPLv3.  Only the kernels needed by gnuradio_dsp.cpp are included:
 *
 *   gnuradio_volk_8u_s32f_convert_32f   — uint8 IQ → float32 (RTL-SDR raw bytes)
 *   gnuradio_volk_32fc_magnitude_32f    — complex magnitude for AM envelope / RMS
 *   gnuradio_volk_32f_x2_dot_prod_32f  — FIR inner product
 *   gnuradio_volk_32fc_s32f_atan2_32f  — instantaneous phase for FM discriminator
 *   gnuradio_volk_32f_x2_add_32f       — vector add (used for DC-offset subtraction)
 *
 * Each kernel has a NEON-vectorised path guarded by __ARM_NEON and a portable
 * scalar fallback.  No external dependency, no CMake find_library, no optional
 * flag — this header is always present and always compiled.
 *
 * Naming: all functions are prefixed "gnuradio_volk_" to avoid symbol clashes
 * if a device happens to ship libvolk.so from another source.
 */

#pragma once
#include <cstdint>
#include <cmath>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#  include <arm_neon.h>
#  define GR_HAVE_NEON 1
#endif

// ─── lv_32fc_t ────────────────────────────────────────────────────────────────
// VOLK's complex float type: interleaved [real, imag] pairs.
// We define it only when volk/volk_complex.h is not already included.
#ifndef LV_32FC_T_DEFINED
#define LV_32FC_T_DEFINED
typedef struct { float real; float imag; } lv_32fc_t;
#endif

// ─── Alignment macro ──────────────────────────────────────────────────────────
#ifndef __VOLK_ATTR_ALIGNED
#  define __VOLK_ATTR_ALIGNED(x) __attribute__((aligned(x)))
#endif

// =============================================================================
// gnuradio_volk_8u_s32f_convert_32f
//
// Converts an array of unsigned 8-bit integers to float32 and scales by
// (1 / scaleFactor).  Then gnuradio_dsp.cpp subtracts the DC offset
// (127.5 / 128) in a second vector pass.
//
// Source: volk/kernels/volk/volk_8i_s32f_convert_32f.h  (NEON path)
// Adapted: input reinterpreted as uint8_t (RTL-SDR raw IQ bytes are unsigned).
// =============================================================================
static inline void gnuradio_volk_8u_s32f_convert_32f(
        float*          outputVector,
        const uint8_t*  inputVector,
        float           scaleFactor,
        unsigned int    num_points)
{
#ifdef GR_HAVE_NEON
    const float iScale = 1.0f / scaleFactor;
    const float32x4_t qiScalar = vdupq_n_f32(iScale);

    unsigned int sixteenthPoints = num_points / 16;
    const uint8_t* inPtr  = inputVector;
    float*         outPtr = outputVector;

    for (unsigned int n = 0; n < sixteenthPoints; ++n) {
        // Load 16 unsigned bytes
        uint8x16_t u8val = vld1q_u8(inPtr);
        inPtr += 16;

        // Expand to two uint16x8
        uint16x8_t u16lo = vmovl_u8(vget_low_u8(u8val));
        uint16x8_t u16hi = vmovl_u8(vget_high_u8(u8val));

        // Expand each uint16x8 to two uint32x4 then convert to float
        float32x4_t f0 = vcvtq_f32_u32(vmovl_u16(vget_low_u16(u16lo)));
        float32x4_t f1 = vcvtq_f32_u32(vmovl_u16(vget_high_u16(u16lo)));
        float32x4_t f2 = vcvtq_f32_u32(vmovl_u16(vget_low_u16(u16hi)));
        float32x4_t f3 = vcvtq_f32_u32(vmovl_u16(vget_high_u16(u16hi)));

        vst1q_f32(outPtr,      vmulq_f32(f0, qiScalar));
        vst1q_f32(outPtr +  4, vmulq_f32(f1, qiScalar));
        vst1q_f32(outPtr +  8, vmulq_f32(f2, qiScalar));
        vst1q_f32(outPtr + 12, vmulq_f32(f3, qiScalar));
        outPtr += 16;
    }
    // Scalar tail
    for (unsigned int i = sixteenthPoints * 16; i < num_points; ++i)
        outputVector[i] = (float)inputVector[i] * iScale;
#else
    const float iScale = 1.0f / scaleFactor;
    for (unsigned int i = 0; i < num_points; ++i)
        outputVector[i] = (float)inputVector[i] * iScale;
#endif
}

// =============================================================================
// gnuradio_volk_32f_x2_add_32f
//
// Adds a scalar constant to every element of a float vector (in-place variant).
// Used to subtract the DC offset after gnuradio_volk_8u_s32f_convert_32f:
//   out[i] = in[i] + offset    where offset = -(127.5/128)
//
// VOLK source: no dedicated kernel; implemented directly with NEON vaddq_f32.
// =============================================================================
static inline void gnuradio_volk_32f_scalar_add_32f(
        float*       outputVector,
        const float* inputVector,
        float        scalar,
        unsigned int num_points)
{
#ifdef GR_HAVE_NEON
    const float32x4_t vs = vdupq_n_f32(scalar);
    unsigned int quarterPoints = num_points / 4;
    const float* inPtr  = inputVector;
    float*       outPtr = outputVector;

    for (unsigned int n = 0; n < quarterPoints; ++n) {
        float32x4_t v = vld1q_f32(inPtr);
        vst1q_f32(outPtr, vaddq_f32(v, vs));
        inPtr  += 4;
        outPtr += 4;
    }
    for (unsigned int i = quarterPoints * 4; i < num_points; ++i)
        outputVector[i] = inputVector[i] + scalar;
#else
    for (unsigned int i = 0; i < num_points; ++i)
        outputVector[i] = inputVector[i] + scalar;
#endif
}

// =============================================================================
// gnuradio_volk_32fc_magnitude_32f
//
// Computes |z| = sqrt(re²+im²) for each complex sample.
//
// Source: volk/kernels/volk/volk_32fc_magnitude_32f.h  (NEON path)
// Note: the VOLK NEON path uses vrsqrteq_f32 (reciprocal sqrt estimate) then
// vrecpeq_f32 to get magnitude.  We use the "fancy sweet" approximation path
// which is accurate to ~1% — sufficient for AM envelope and power estimation.
// For exact magnitude the scalar sqrtf tail is used for any remainder.
// =============================================================================
static inline void gnuradio_volk_32fc_magnitude_32f(
        float*           magnitudeVector,
        const lv_32fc_t* complexVector,
        unsigned int     num_points)
{
#ifdef GR_HAVE_NEON
    const float*  complexVectorPtr   = reinterpret_cast<const float*>(complexVector);
    float*        magnitudeVectorPtr = magnitudeVector;
    unsigned int  quarterPoints      = num_points / 4;

    for (unsigned int n = 0; n < quarterPoints; ++n) {
        // Load 4 complex samples (8 floats): [I0,Q0,I1,Q1,I2,Q2,I3,Q3]
        float32x4x2_t cplx = vld2q_f32(complexVectorPtr);
        // I² + Q² for each of the 4 pairs
        float32x4_t i2 = vmulq_f32(cplx.val[0], cplx.val[0]);
        float32x4_t mag2 = vmlaq_f32(i2, cplx.val[1], cplx.val[1]);
        // Reciprocal sqrt estimate → reciprocal → approximate magnitude
        float32x4_t rsqrt_est = vrsqrteq_f32(mag2);
        float32x4_t mag = vrecpeq_f32(rsqrt_est);
        vst1q_f32(magnitudeVectorPtr, mag);
        complexVectorPtr   += 8;
        magnitudeVectorPtr += 4;
    }
    // Exact scalar tail
    for (unsigned int i = quarterPoints * 4; i < num_points; ++i) {
        float re = *complexVectorPtr++;
        float im = *complexVectorPtr++;
        *magnitudeVectorPtr++ = sqrtf(re*re + im*im);
    }
#else
    const float* p = reinterpret_cast<const float*>(complexVector);
    for (unsigned int i = 0; i < num_points; ++i) {
        float re = *p++, im = *p++;
        magnitudeVector[i] = sqrtf(re*re + im*im);
    }
#endif
}

// =============================================================================
// gnuradio_volk_32f_x2_dot_prod_32f
//
// Inner product (dot product) of two float arrays: result = Σ a[i]*b[i]
//
// Source: volk/kernels/volk/volk_32f_x2_dot_prod_32f.h  (neonopts path)
// Uses 4-way unrolled accumulators to hide multiply-accumulate latency.
// =============================================================================
static inline void gnuradio_volk_32f_x2_dot_prod_32f(
        float*       result,
        const float* input,
        const float* taps,
        unsigned int num_points)
{
#ifdef GR_HAVE_NEON
    unsigned int sixteenthPoints = num_points / 16;
    const float* aPtr = input;
    const float* bPtr = taps;

    float32x4_t acc0 = vdupq_n_f32(0.0f);
    float32x4_t acc1 = vdupq_n_f32(0.0f);
    float32x4_t acc2 = vdupq_n_f32(0.0f);
    float32x4_t acc3 = vdupq_n_f32(0.0f);

    for (unsigned int n = 0; n < sixteenthPoints; ++n) {
        float32x4x4_t a = vld4q_f32(aPtr);
        float32x4x4_t b = vld4q_f32(bPtr);
        acc0 = vmlaq_f32(acc0, a.val[0], b.val[0]);
        acc1 = vmlaq_f32(acc1, a.val[1], b.val[1]);
        acc2 = vmlaq_f32(acc2, a.val[2], b.val[2]);
        acc3 = vmlaq_f32(acc3, a.val[3], b.val[3]);
        aPtr += 16;
        bPtr += 16;
    }
    acc0 = vaddq_f32(acc0, acc1);
    acc2 = vaddq_f32(acc2, acc3);
    acc0 = vaddq_f32(acc0, acc2);

    __VOLK_ATTR_ALIGNED(16) float acc[4];
    vst1q_f32(acc, acc0);
    float dot = acc[0] + acc[1] + acc[2] + acc[3];

    for (unsigned int i = sixteenthPoints * 16; i < num_points; ++i)
        dot += aPtr[i - sixteenthPoints*16] * bPtr[i - sixteenthPoints*16];

    *result = dot;
#else
    float dot = 0.0f;
    for (unsigned int i = 0; i < num_points; ++i)
        dot += input[i] * taps[i];
    *result = dot;
#endif
}

// =============================================================================
// gnuradio_volk_32fc_s32f_atan2_32f
//
// Computes atan2(imag, real) for each complex sample and divides by
// normalizeFactor.  Used to compute instantaneous phase for FM demodulation.
//
// Source: volk/kernels/volk/volk_32fc_s32f_atan2_32f.h
// The VOLK source has no NEON path for atan2 (transcendental functions are
// expensive to vectorise correctly).  The generic scalar path using atan2f is
// the correct implementation — it produces the bounded output that eliminates
// the division-by-zero spikes in the differential-phase discriminator.
// =============================================================================
static inline void gnuradio_volk_32fc_s32f_atan2_32f(
        float*           outputVector,
        const lv_32fc_t* complexVector,
        float            normalizeFactor,
        unsigned int     num_points)
{
    const float* inPtr  = reinterpret_cast<const float*>(complexVector);
    const float  invNorm = 1.0f / normalizeFactor;
    for (unsigned int i = 0; i < num_points; ++i) {
        float re = *inPtr++;
        float im = *inPtr++;
        outputVector[i] = atan2f(im, re) * invNorm;
    }
}

