#include <cmath>
#include <cstdint>

static constexpr float TWO_PI = 6.283185307179586f;

/**
 * FM discriminator via atan2 phase differentiation.
 *
 * Replaces the differential-phase formula (I·dQ/dt − Q·dI/dt)/(I²+Q²) which
 * divides by instantaneous signal power.  Near a carrier null the denominator
 * approaches 0, producing spikes of magnitude >> 1.0 that the Android AudioTrack
 * hard-clips.  At non-integer sample-rate ratios the PolyphaseResampler delivers
 * blocks whose length varies by ±1 sample, causing the null to align differently
 * each block and generating a periodic crackle / jitter.
 *
 * atan2f is bounded to (−π, π] regardless of signal level — no division, no spike.
 * This matches the approach used in gnuradio_dsp.cpp (gnuradio_volk_32fc_s32f_atan2_32f).
 *
 * @param iq        Interleaved complex float samples
 * @param out       FM demodulated output (real)
 * @param samples   Number of complex sample pairs
 * @param gain      Frequency deviation normalisation gain
 * @param prevI     Previous I sample (state, updated on return)
 * @param prevQ     Previous Q sample (state, updated on return)
 */
extern "C" void fm_discriminator(
        const float* __restrict__ iq,
        float*       __restrict__ out,
        int                       samples,
        float                     gain,
        float*                    prevI,
        float*                    prevQ)
{
    static constexpr float PI     =  3.14159265358979f;
    static constexpr float TWO_PI =  6.28318530717959f;

    float prevPhase = atan2f(*prevQ, *prevI);
    for (int i = 0; i < samples; i++) {
        float ci = iq[2*i], cq = iq[2*i+1];
        float phase = atan2f(cq, ci);
        float diff  = phase - prevPhase;
        // Phase unwrap to (−π, π]
        if (diff >  PI) diff -= TWO_PI;
        if (diff < -PI) diff += TWO_PI;
        out[i] = diff * gain;
        prevPhase = phase;
    }
    *prevI = iq[(samples - 1) * 2];
    *prevQ = iq[(samples - 1) * 2 + 1];
}

/**
 * FM discriminator via atan2 phase unwrapping.
 * Slower but more accurate for wide deviation signals.
 */
extern "C" void fm_atan2_demod(
        const float* __restrict__ iq,
        float*       __restrict__ out,
        int                       samples,
        float*                    prevPhase)
{
    float pp = *prevPhase;
    for (int i = 0; i < samples; i++) {
        float phase = atan2f(iq[2*i+1], iq[2*i]);
        float diff  = phase - pp;
        // Unwrap
        if (diff >  M_PI) diff -= TWO_PI;
        if (diff < -M_PI) diff += TWO_PI;
        out[i] = diff;
        pp = phase;
    }
    *prevPhase = pp;
}

/**
 * Single-pole IIR de-emphasis filter.
 * alpha = exp(-1.0 / (sampleRate * tau))
 * tau = 75e-6 (NA) or 50e-6 (EU)
 */
extern "C" void deemphasis_iir(
        float*  samples,
        int     len,
        float   alpha,
        float*  state)
{
    float y = *state;
    for (int i = 0; i < len; i++) {
        y = alpha * y + (1.0f - alpha) * samples[i];
        samples[i] = y;
    }
    *state = y;
}

/**
 * Generate complex sinusoid for frequency shifting (local oscillator).
 * out = [cos(phase), sin(phase)] for each sample.
 *
 * @param out     Output interleaved complex float (2N floats)
 * @param freq    Frequency in Hz (positive = shift up)
 * @param rate    Sample rate in S/s
 * @param phase   Initial phase in radians, updated on exit
 * @param N       Number of samples
 */
extern "C" void generate_lo(
        float*  __restrict__ out,
        double               freq,
        int                  rate,
        double*              phase,
        int                  N)
{
    double step = TWO_PI * freq / rate;
    double p    = *phase;
    for (int i = 0; i < N; i++) {
        out[2*i]   = (float)cos(p);
        out[2*i+1] = (float)sin(p);
        p += step;
        if (p > TWO_PI) p -= TWO_PI;
    }
    *phase = p;
}
