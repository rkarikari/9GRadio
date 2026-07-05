#pragma once
#include <cstdint>
#include <cmath>

// ─── IQ conversion helpers ────────────────────────────────────────────────────

/**
 * Convert RTL-SDR uint8 IQ buffer to normalised float32.
 * Formula: out[i] = (in[i] - 127.5) / 128.0
 *
 * ARM NEON path processes 16 bytes per loop iteration.
 * Scalar fallback for non-ARM or x86.
 */
void iq_uint8_to_float(const uint8_t* __restrict__ in,
                       float*         __restrict__ out,
                       int                         len);

/**
 * Convert float32 IQ to uint8 (for playback or re-encoding).
 * Formula: out[i] = clamp(in[i] * 128.0 + 127.5, 0, 255)
 */
void iq_float_to_uint8(const float*   __restrict__ in,
                       uint8_t*       __restrict__ out,
                       int                         len);

/**
 * Compute mean power (sum of I²+Q²) / N over N complex samples.
 */
float iq_mean_power(const float* __restrict__ iq, int samples);

/**
 * Compute magnitude (envelope) for each complex sample.
 * out[i] = sqrt(I[2i]² + Q[2i+1]²)
 */
void iq_magnitude(const float* __restrict__ iq,
                  float*       __restrict__ out,
                  int                       samples);

/**
 * Complex multiply: (a+jb)(c+jd) for each sample pair.
 * Used for frequency shifting (mixing with local oscillator).
 */
void iq_complex_multiply(const float* __restrict__ iq,
                         const float* __restrict__ lo,
                         float*       __restrict__  out,
                         int                        samples);

/**
 * DC-blocking IIR filter (leaky integrator) applied in-place to an
 * interleaved float IQ buffer.
 *
 * For each sample pair:
 *   dc[n] = alpha * dc[n-1] + (1 - alpha) * x[n]
 *   out[n] = x[n] - dc[n]
 *
 * dcState[0] = running DC estimate for I channel (read & updated).
 * dcState[1] = running DC estimate for Q channel (read & updated).
 *
 * Recommended alpha ≈ 0.9999 — gives a time constant of ~10 000 samples,
 * i.e. ~5 ms at 2 MS/s.  Fast enough to track hardware drift, slow enough
 * to leave audio content untouched after FM demodulation.
 *
 * Preserving dcState across calls makes the filter continuous across blocks.
 */
void iq_dc_remove(float* __restrict__ iq,
                  int                 samples,
                  float               alpha,
                  float* __restrict__ dcState);
