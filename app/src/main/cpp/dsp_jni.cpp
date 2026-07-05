#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cmath>
#include <cstring>
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#endif

#define TAG "RtlSdrNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ─── IQ Conversion: uint8 → float (I-127.5)/128 ──────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_com_radiosport_ninegradio_dsp_NativeDsp_uint8ToFloat(
        JNIEnv* env, jclass,
        jbyteArray jInput, jfloatArray jOutput, jint length)
{
    jbyte* input  = env->GetByteArrayElements(jInput,  nullptr);
    jfloat* output = env->GetFloatArrayElements(jOutput, nullptr);

    const int n = length;
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
    // NEON vectorised path: process 16 bytes at a time
    const float32x4_t scale  = vdupq_n_f32(1.0f / 128.0f);
    const float32x4_t offset = vdupq_n_f32(127.5f / 128.0f);
    int i = 0;
    for (; i + 16 <= n; i += 16) {
        uint8x16_t u8 = vld1q_u8((const uint8_t*)(input + i));
        // Expand to u16
        uint16x8_t u16lo = vmovl_u8(vget_low_u8(u8));
        uint16x8_t u16hi = vmovl_u8(vget_high_u8(u8));
        // Convert to f32 in 4-wide lanes
        float32x4_t f0 = vcvtq_f32_u32(vmovl_u16(vget_low_u16(u16lo)));
        float32x4_t f1 = vcvtq_f32_u32(vmovl_u16(vget_high_u16(u16lo)));
        float32x4_t f2 = vcvtq_f32_u32(vmovl_u16(vget_low_u16(u16hi)));
        float32x4_t f3 = vcvtq_f32_u32(vmovl_u16(vget_high_u16(u16hi)));
        // Scale: (x - 127.5) / 128
        f0 = vsubq_f32(vmulq_f32(f0, scale), offset);
        f1 = vsubq_f32(vmulq_f32(f1, scale), offset);
        f2 = vsubq_f32(vmulq_f32(f2, scale), offset);
        f3 = vsubq_f32(vmulq_f32(f3, scale), offset);
        vst1q_f32(output + i,      f0);
        vst1q_f32(output + i + 4,  f1);
        vst1q_f32(output + i + 8,  f2);
        vst1q_f32(output + i + 12, f3);
    }
    for (; i < n; i++) {
        output[i] = ((uint8_t)input[i] - 127.5f) / 128.0f;
    }
#else
    // Scalar fallback
    for (int i = 0; i < n; i++) {
        output[i] = ((uint8_t)input[i] - 127.5f) / 128.0f;
    }
#endif

    env->ReleaseByteArrayElements(jInput,  input,  JNI_ABORT);
    env->ReleaseFloatArrayElements(jOutput, output, 0);
}

// ─── FM Discriminator ─────────────────────────────────────────────────────────
//
// NOTE: This translation unit is not part of the active build (see
// CMakeLists.txt — only gnuradio_dsp.cpp's fmDiscriminator JNI export is
// linked). It is kept for reference/fallback builds, so it must not regress
// to the old power-dividing formula. Delegates to the atan2-based
// fm_discriminator() in fm_demod.cpp, which is bounded and immune to the
// I^2+Q^2 -> 0 spikes that caused watery/jittery WFM audio.

extern "C" void fm_discriminator(
        const float* iq, float* out, int samples,
        float gain, float* prevI, float* prevQ);

extern "C" JNIEXPORT void JNICALL
Java_com_radiosport_ninegradio_dsp_NativeDsp_fmDiscriminator(
        JNIEnv* env, jclass,
        jfloatArray jIq, jfloatArray jOut,
        jint samples, jfloat prevI, jfloat prevQ, jfloat gain)
{
    jfloat* iq  = env->GetFloatArrayElements(jIq,  nullptr);
    jfloat* out = env->GetFloatArrayElements(jOut, nullptr);

    float pi = prevI, pq = prevQ;
    fm_discriminator(iq, out, samples, gain, &pi, &pq);

    env->ReleaseFloatArrayElements(jIq,  iq,  JNI_ABORT);
    env->ReleaseFloatArrayElements(jOut, out, 0);
}

// ─── FIR Low-Pass Filter ──────────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_com_radiosport_ninegradio_dsp_NativeDsp_firFilter(
        JNIEnv* env, jclass,
        jfloatArray jInput, jfloatArray jTaps, jfloatArray jOutput,
        jint inputLen, jint tapLen)
{
    jfloat* input  = env->GetFloatArrayElements(jInput,  nullptr);
    jfloat* taps   = env->GetFloatArrayElements(jTaps,   nullptr);
    jfloat* output = env->GetFloatArrayElements(jOutput, nullptr);

    for (int i = 0; i < inputLen; i++) {
        float acc = 0.0f;
        for (int k = 0; k < tapLen && (i - k) >= 0; k++) {
            acc += taps[k] * input[i - k];
        }
        output[i] = acc;
    }

    env->ReleaseFloatArrayElements(jInput,  input,  JNI_ABORT);
    env->ReleaseFloatArrayElements(jTaps,   taps,   JNI_ABORT);
    env->ReleaseFloatArrayElements(jOutput, output, 0);
}

// ─── AM Envelope Detector ─────────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_com_radiosport_ninegradio_dsp_NativeDsp_amEnvelope(
        JNIEnv* env, jclass,
        jfloatArray jIq, jfloatArray jOut, jint samples)
{
    jfloat* iq  = env->GetFloatArrayElements(jIq,  nullptr);
    jfloat* out = env->GetFloatArrayElements(jOut, nullptr);

    for (int i = 0; i < samples; i++) {
        float I = iq[2*i], Q = iq[2*i+1];
        out[i] = sqrtf(I*I + Q*Q);
    }

    env->ReleaseFloatArrayElements(jIq,  iq,  JNI_ABORT);
    env->ReleaseFloatArrayElements(jOut, out, 0);
}

// ─── RMS Power ───────────────────────────────────────────────────────────────

extern "C" JNIEXPORT jfloat JNICALL
Java_com_radiosport_ninegradio_dsp_NativeDsp_computeRms(
        JNIEnv* env, jclass, jfloatArray jIq, jint samples)
{
    jfloat* iq = env->GetFloatArrayElements(jIq, nullptr);
    double power = 0.0;
    for (int i = 0; i < samples; i++) {
        float I = iq[2*i], Q = iq[2*i+1];
        power += (double)(I*I + Q*Q);
    }
    env->ReleaseFloatArrayElements(jIq, iq, JNI_ABORT);
    return (jfloat)(power / samples);
}

// ─── Linear Resampler ─────────────────────────────────────────────────────────

extern "C" JNIEXPORT jint JNICALL
Java_com_radiosport_ninegradio_dsp_NativeDsp_resampleLinear(
        JNIEnv* env, jclass,
        jfloatArray jInput, jint inLen,
        jfloatArray jOutput, jint outLen,
        jint inRate, jint outRate)
{
    jfloat* input  = env->GetFloatArrayElements(jInput,  nullptr);
    jfloat* output = env->GetFloatArrayElements(jOutput, nullptr);

    double ratio = (double)inRate / outRate;
    int written = 0;
    for (int i = 0; i < outLen; i++) {
        double srcPos = i * ratio;
        int idx = (int)srcPos;
        float frac = (float)(srcPos - idx);
        float s0 = (idx < inLen)     ? input[idx]     : 0.0f;
        float s1 = (idx+1 < inLen)   ? input[idx+1]   : 0.0f;
        output[i] = s0 + frac * (s1 - s0);
        written++;
    }

    env->ReleaseFloatArrayElements(jInput,  input,  JNI_ABORT);
    env->ReleaseFloatArrayElements(jOutput, output, 0);
    return written;
}

// ─── DC-blocking IIR filter ───────────────────────────────────────────────────
// Removes the RTL-SDR hardware DC spike by subtracting a running mean from
// each I and Q channel using a leaky integrator.  Applied in-place on the
// interleaved float IQ buffer before FFT and before demodulation.
//
// jState is a float[2] holding [dcI, dcQ]; it is read and updated on each
// call so the filter is continuous across USB bulk-transfer blocks.
extern "C" JNIEXPORT void JNICALL
Java_com_radiosport_ninegradio_dsp_NativeDsp_dcRemoveInPlace(
        JNIEnv* env, jclass,
        jfloatArray jIq, jint samples, jfloat alpha, jfloatArray jState)
{
    jfloat* iq    = env->GetFloatArrayElements(jIq,    nullptr);
    jfloat* state = env->GetFloatArrayElements(jState, nullptr);

    const float beta = 1.0f - alpha;
    float di = state[0], dq = state[1];

    for (int i = 0; i < samples; i++) {
        float xi = iq[2 * i];
        float xq = iq[2 * i + 1];
        di = alpha * di + beta * xi;
        dq = alpha * dq + beta * xq;
        iq[2 * i]     = xi - di;
        iq[2 * i + 1] = xq - dq;
    }

    state[0] = di;
    state[1] = dq;

    env->ReleaseFloatArrayElements(jIq,    iq,    0);   // write back modified IQ
    env->ReleaseFloatArrayElements(jState, state, 0);   // write back updated DC state
}
