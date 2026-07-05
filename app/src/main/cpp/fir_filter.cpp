#include <cstdint>
#include <cstring>
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#define HAVE_NEON 1
#endif

/**
 * Apply a real-valued FIR filter to a real-valued signal.
 * Direct-form convolution, NEON-vectorised on ARM.
 *
 * @param input     Input signal
 * @param taps      Filter coefficients (impulse response)
 * @param output    Output signal (same length as input)
 * @param inputLen  Length of input / output
 * @param numTaps   Number of filter taps
 */
extern "C" void fir_filter_real(
        const float* __restrict__ input,
        const float* __restrict__ taps,
        float*       __restrict__ output,
        int                       inputLen,
        int                       numTaps)
{
    for (int i = 0; i < inputLen; i++) {
        float acc = 0.0f;
#ifdef HAVE_NEON
        float32x4_t vacc = vdupq_n_f32(0.0f);
        int k = 0;
        for (; k + 4 <= numTaps && (i - k - 3) >= 0; k += 4) {
            float32x4_t vtap = vld1q_f32(taps + k);
            // Build input vector [x[i-k], x[i-k-1], x[i-k-2], x[i-k-3]]
            float32x4_t vin;
            vin = vsetq_lane_f32(input[i - k],     vin, 0);
            vin = vsetq_lane_f32(input[i - k - 1], vin, 1);
            vin = vsetq_lane_f32(input[i - k - 2], vin, 2);
            vin = vsetq_lane_f32(input[i - k - 3], vin, 3);
            vacc = vmlaq_f32(vacc, vtap, vin);
        }
        // Horizontal sum
        float32x2_t s = vadd_f32(vget_low_f32(vacc), vget_high_f32(vacc));
        acc = vget_lane_f32(vpadd_f32(s, s), 0);
        // Scalar tail
        for (; k < numTaps && (i - k) >= 0; k++)
            acc += taps[k] * input[i - k];
#else
        for (int k = 0; k < numTaps && (i - k) >= 0; k++)
            acc += taps[k] * input[i - k];
#endif
        output[i] = acc;
    }
}

/**
 * Complex FIR filter (interleaved I,Q).
 * Applies the same real-valued taps to both I and Q channels.
 */
extern "C" void fir_filter_complex(
        const float* __restrict__ iq,
        const float* __restrict__ taps,
        float*       __restrict__ out,
        int                       samples,
        int                       numTaps)
{
    for (int i = 0; i < samples; i++) {
        float accI = 0.0f, accQ = 0.0f;
        for (int k = 0; k < numTaps && (i - k) >= 0; k++) {
            accI += taps[k] * iq[(i - k) * 2];
            accQ += taps[k] * iq[(i - k) * 2 + 1];
        }
        out[i * 2]     = accI;
        out[i * 2 + 1] = accQ;
    }
}

/**
 * Decimate: filter then keep every Nth sample.
 * Returns number of output samples written.
 */
extern "C" int fir_decimate(
        const float* __restrict__ input,
        const float* __restrict__ taps,
        float*       __restrict__ output,
        int                       inputLen,
        int                       numTaps,
        int                       factor)
{
    int outIdx = 0;
    for (int i = 0; i < inputLen; i += factor) {
        float acc = 0.0f;
        for (int k = 0; k < numTaps && (i - k) >= 0; k++)
            acc += taps[k] * input[i - k];
        output[outIdx++] = acc;
    }
    return outIdx;
}
