#include "include/iq_convert.h"
#include <cstring>
#include <android/log.h>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#define HAVE_NEON 1
#endif

void iq_uint8_to_float(const uint8_t* __restrict__ in,
                       float*         __restrict__ out,
                       int                         len)
{
#ifdef HAVE_NEON
    const float32x4_t vScale  = vdupq_n_f32(1.0f / 128.0f);
    const float32x4_t vOffset = vdupq_n_f32(127.5f / 128.0f);
    int i = 0;
    for (; i + 16 <= len; i += 16) {
        uint8x16_t u8 = vld1q_u8(in + i);
        uint16x8_t u16lo = vmovl_u8(vget_low_u8(u8));
        uint16x8_t u16hi = vmovl_u8(vget_high_u8(u8));
        float32x4_t f0 = vsubq_f32(vmulq_f32(vcvtq_f32_u32(vmovl_u16(vget_low_u16(u16lo))),  vScale), vOffset);
        float32x4_t f1 = vsubq_f32(vmulq_f32(vcvtq_f32_u32(vmovl_u16(vget_high_u16(u16lo))), vScale), vOffset);
        float32x4_t f2 = vsubq_f32(vmulq_f32(vcvtq_f32_u32(vmovl_u16(vget_low_u16(u16hi))),  vScale), vOffset);
        float32x4_t f3 = vsubq_f32(vmulq_f32(vcvtq_f32_u32(vmovl_u16(vget_high_u16(u16hi))), vScale), vOffset);
        vst1q_f32(out + i,      f0);
        vst1q_f32(out + i + 4,  f1);
        vst1q_f32(out + i + 8,  f2);
        vst1q_f32(out + i + 12, f3);
    }
    for (; i < len; i++)
        out[i] = ((float)in[i] - 127.5f) / 128.0f;
#else
    for (int i = 0; i < len; i++)
        out[i] = ((float)in[i] - 127.5f) / 128.0f;
#endif
}

void iq_float_to_uint8(const float* __restrict__ in,
                       uint8_t*     __restrict__ out,
                       int                       len)
{
    for (int i = 0; i < len; i++) {
        float v = in[i] * 128.0f + 127.5f;
        if (v < 0.0f)   v = 0.0f;
        if (v > 255.0f) v = 255.0f;
        out[i] = (uint8_t)v;
    }
}

float iq_mean_power(const float* __restrict__ iq, int samples)
{
    double sum = 0.0;
#ifdef HAVE_NEON
    float32x4_t acc = vdupq_n_f32(0.0f);
    int i = 0;
    for (; i + 4 <= samples; i += 4) {
        float32x4_t vi = vld1q_f32(iq + i * 2);
        float32x4_t vq = vld1q_f32(iq + i * 2 + 4);
        acc = vmlaq_f32(acc, vi, vi);
        acc = vmlaq_f32(acc, vq, vq);
    }
    float32x2_t sum2 = vadd_f32(vget_low_f32(acc), vget_high_f32(acc));
    sum = (double)vget_lane_f32(vpadd_f32(sum2, sum2), 0);
    for (; i < samples; i++) {
        float I = iq[2*i], Q = iq[2*i+1];
        sum += I*I + Q*Q;
    }
#else
    for (int i = 0; i < samples; i++) {
        float I = iq[2*i], Q = iq[2*i+1];
        sum += I*I + Q*Q;
    }
#endif
    return (float)(sum / samples);
}

void iq_magnitude(const float* __restrict__ iq,
                  float*       __restrict__ out,
                  int                       samples)
{
    for (int i = 0; i < samples; i++) {
        float I = iq[2*i], Q = iq[2*i+1];
        out[i] = sqrtf(I*I + Q*Q);
    }
}

void iq_complex_multiply(const float* __restrict__ iq,
                         const float* __restrict__ lo,
                         float*       __restrict__ out,
                         int                       samples)
{
    for (int i = 0; i < samples; i++) {
        float ai = iq[2*i],   aq = iq[2*i+1];
        float bi = lo[2*i],   bq = lo[2*i+1];
        out[2*i]   = ai*bi - aq*bq;
        out[2*i+1] = ai*bq + aq*bi;
    }
}

// ─── DC-blocking IIR filter ───────────────────────────────────────────────────
// RTL-SDR hardware mixes the incoming RF with a direct-digital-synthesis local
// oscillator, but non-ideal mixer balance leaves a residual DC component in the
// baseband IQ stream.  This manifests as a large spike at the centre bin of
// every FFT frame and as a constant carrier offset that can mask weak signals.
//
// The leaky integrator below estimates the running mean of each I/Q channel and
// subtracts it.  The IIR nature keeps the estimate continuous across USB bulk
// transfers (no block-boundary clicks).  The sequential feedback dependency
// prevents full SIMD vectorisation, so both paths use the same scalar loop —
// the compiler auto-vectorises the multiply-accumulate internally.
void iq_dc_remove(float* __restrict__ iq,
                  int                 samples,
                  float               alpha,
                  float* __restrict__ dcState)
{
    const float beta = 1.0f - alpha;
    float di = dcState[0];
    float dq = dcState[1];

    for (int i = 0; i < samples; i++) {
        float xi = iq[2 * i];
        float xq = iq[2 * i + 1];
        di = alpha * di + beta * xi;
        dq = alpha * dq + beta * xq;
        iq[2 * i]     = xi - di;
        iq[2 * i + 1] = xq - dq;
    }

    dcState[0] = di;
    dcState[1] = dq;
}
