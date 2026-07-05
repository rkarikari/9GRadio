#include <cmath>
#include <cstdint>
#include <cstring>
#include <cstdlib>

/**
 * Linear interpolation resampler.
 * Simple but introduces aliasing — use polyphase for production.
 */
extern "C" int resample_linear(
        const float* __restrict__ input,  int inLen,
        float*       __restrict__ output, int outLen,
        int                       inRate, int outRate)
{
    double ratio = (double)inRate / outRate;
    int written = 0;
    for (int i = 0; i < outLen; i++) {
        double srcPos = i * ratio;
        int    idx  = (int)srcPos;
        float  frac = (float)(srcPos - idx);
        float  s0   = (idx     < inLen) ? input[idx]     : 0.0f;
        float  s1   = (idx + 1 < inLen) ? input[idx + 1] : 0.0f;
        output[i]   = s0 + frac * (s1 - s0);
        written++;
    }
    return written;
}

/**
 * Polyphase resampler state.
 * Holds the filter bank and fractional position.
 */
typedef struct {
    float*  filterBank;   // numPhases × phaseLen coefficients
    float*  delayLine;    // phaseLen history samples
    int     numPhases;
    int     phaseLen;
    int     interpFactor;
    int     decimFactor;
    int     dlPos;
    long long inputCount;
    long long outputCount;
} PolyState;

static int gcd(int a, int b) { return b == 0 ? a : gcd(b, a % b); }

extern "C" void* poly_create(int inRate, int outRate, int numTaps) {
    PolyState* s = (PolyState*)calloc(1, sizeof(PolyState));
    int g = gcd(inRate, outRate);
    s->interpFactor = outRate / g;
    s->decimFactor  = inRate  / g;
    s->numPhases    = s->interpFactor;
    s->phaseLen     = (numTaps + s->numPhases - 1) / s->numPhases;
    int total       = s->numPhases * s->phaseLen;

    // Build windowed-sinc prototype.
    //
    // Correct anti-aliasing cutoff: fc = min(1, interpFactor / decimFactor) / 2
    //   = interpFactor / (2 * decimFactor)   when decimFactor >= interpFactor.
    //
    // The old formula  0.5 / max(decimFactor, interpFactor)  is only correct when
    // decimFactor == interpFactor. For WFM pre-decimation (e.g. 2048k→200k:
    // decimFactor=128, interpFactor=125) max() returns 128, giving fc≈0.0039 which
    // cuts off everything above ~8 kHz — far below the 100 kHz needed for WFM.
    // Correct formula gives fc = 125/(2×128) ≈ 0.488 → ~100 kHz passband.
    float* proto = (float*)calloc(total, sizeof(float));
    double fc_d = (double)s->interpFactor / (2.0 * (double)s->decimFactor);
    if (fc_d > 0.45) fc_d = 0.45;  // guard band: stay away from Nyquist
    float fc = (float)fc_d;    double m  = total - 1.0;
    for (int i = 0; i < total; i++) {
        double x = 2.0 * M_PI * fc * (i - m/2.0);
        double sinc = (fabs(x) < 1e-9) ? 1.0 : sin(x) / x;
        double w    = 0.35875 - 0.48829*cos(2*M_PI*i/m)
                              + 0.14128*cos(4*M_PI*i/m)
                              - 0.01168*cos(6*M_PI*i/m);
        proto[i] = (float)(sinc * w * s->numPhases);
    }
    // Polyphase decomposition
    s->filterBank = (float*)calloc(s->numPhases * s->phaseLen, sizeof(float));
    for (int i = 0; i < total; i++)
        s->filterBank[(i % s->numPhases) * s->phaseLen + i / s->numPhases] = proto[i];
    free(proto);

    s->delayLine = (float*)calloc(s->phaseLen, sizeof(float));
    return s;
}

extern "C" int poly_process(void* state, const float* input, int inLen, float* output) {
    PolyState* s = (PolyState*)state;
    int outIdx = 0;
    for (int n = 0; n < inLen; n++) {
        // Write new sample to delay line
        s->delayLine[s->dlPos % s->phaseLen] = input[n];
        s->dlPos++;
        s->inputCount++;

        // Generate output samples
        while (s->outputCount * s->decimFactor < s->inputCount * s->interpFactor) {
            int phase = (int)(s->outputCount * s->decimFactor % s->interpFactor);
            float* taps = s->filterBank + phase * s->phaseLen;
            float acc = 0.0f;
            for (int k = 0; k < s->phaseLen; k++) {
                int pos = (s->dlPos - 1 - k + s->phaseLen * 16) % s->phaseLen;
                acc += taps[k] * s->delayLine[pos];
            }
            output[outIdx++] = acc;
            s->outputCount++;
        }
    }
    return outIdx;
}

extern "C" void poly_reset(void* state) {
    PolyState* s = (PolyState*)state;
    memset(s->delayLine, 0, s->phaseLen * sizeof(float));
    s->dlPos = 0;
    s->inputCount  = 0;
    s->outputCount = 0;
}

extern "C" void poly_destroy(void* state) {
    PolyState* s = (PolyState*)state;
    if (s) { free(s->filterBank); free(s->delayLine); free(s); }
}
