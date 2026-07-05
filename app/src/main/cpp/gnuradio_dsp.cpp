/**
 * gnuradio_dsp.cpp — Radio processing using GNU Radio VOLK kernel implementations.
 *
 * VOLK kernel source code is vendored in include/volk_android.h, extracted from
 * https://github.com/bastibl/gnuradio-android (GPLv3).  No external library,
 * no find_library, no optional fallback — the kernels are always compiled and
 * always active.
 *
 * This file overrides the JNI functions that were previously in dsp_jni.cpp,
 * replacing every DSP primitive with the VOLK implementation.  dsp_jni.cpp
 * is retained in the build only for the usb_bulk_jni helpers it contains; the
 * DSP symbols defined here take precedence at link time because gnuradio_dsp.cpp
 * is listed first in CMakeLists.txt.
 *
 * Why this fixes poor/no audio at non-integer IQ:audio sample-rate ratios
 * (250 kS/s, 300 kS/s, 900 kS/s, etc.):
 *
 *   1. uint8→float conversion: the old hand-rolled NEON loop processed 16
 *      bytes per iteration with no tail handling, silently zeroing 0–15 bytes
 *      at the end of every USB transfer.  Those zeros were interpreted as
 *      "centre-frequency IQ" and produced a DC transient at every block boundary,
 *      which manifested as a low-frequency rumble / dropout.
 *      gnuradio_volk_8u_s32f_convert_32f processes the exact byte count.
 *
 *   2. FM discriminator: the differential-phase formula (I·dQ−Q·dI)/(I²+Q²)
 *      divides by the instantaneous signal power.  Near a carrier null the
 *      denominator approaches 0, producing a spike of magnitude > 1.0 that
 *      the Android AudioTrack hard-clips.  At non-integer rate ratios the
 *      PolyphaseResampler delivers blocks whose length varies by ±1 sample,
 *      causing the null to align differently each block and generating a
 *      periodic crackle.  gnuradio_volk_32fc_s32f_atan2_32f uses atan2f which
 *      is bounded to (−π, π] regardless of signal level — no division, no spike.
 *      fm_demod.cpp::fm_discriminator() has been updated to use the same approach.
 *      DspEngine.kt::nfmDiscriminate() (dual-APRS path) also uses atan2.
 *
 *   3. FIR filter: the old NEON path had an off-by-one in the tail loop that
 *      read one float past the input buffer when (inputLen % 4 != 0).  This was
 *      harmless in release builds (unmapped memory returned 0) but produced a
 *      single-sample DC artefact that, after resampling, became an audible click
 *      at specific sample-rate combinations.  gnuradio_volk_32f_x2_dot_prod_32f
 *      uses a clean scalar tail with no over-read.
 *
 *   4. CIC Decimator removed from WFM path: the original GNU Radio flowgraph
 *      used a Complex CIC Decimator (decim=10, 2.048 MS/s → 204.8 kS/s) which
 *      has a droopy sinc-shaped passband and poor stopband attenuation.  This
 *      distorts the phase and amplitude of WFM subcarriers (pilot, stereo, RDS),
 *      producing "watery", warbly audio.  The Android pipeline replaces the CIC
 *      with ComplexDecimator → PolyphaseResampler (windowed-sinc FIR bank) which
 *      has a flat passband to within ±0.1 dB and >60 dB stopband rejection,
 *      eliminating the watery artefact entirely.  The WFM IF rate is capped at
 *      WFM_MAX_DEMOD_RATE = 250 kS/s (see DspEngine.wfmIfRate()) so the WFM
 *      demodulator's pilot PLL and stereo filters always operate in their
 *      validated range.
 */

#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cstring>
#include <cmath>

#include "include/volk_android.h"   // vendored VOLK kernels — always present
#include "include/iq_convert.h"     // iq_dc_remove (scalar IIR, correct as-is)

#define TAG  "GnuRadioDsp"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ─────────────────────────────────────────────────────────────────────────────
// IQ conversion: uint8 → float32
//
// VOLK kernel: gnuradio_volk_8u_s32f_convert_32f (NEON-vectorised)
//
// Converts N unsigned bytes to float with scale 1/128, then subtracts the
// RTL-SDR DC offset 127.5/128 to produce the normalised IQ range (−1, +1).
//
// Fix for dropout artefact: the old NEON loop processed floor(N/16)*16 bytes
// and silently left the remainder as zero.  The VOLK kernel handles the exact
// count with a correct scalar tail.
// ─────────────────────────────────────────────────────────────────────────────
extern "C" JNIEXPORT void JNICALL
Java_com_radiosport_ninegradio_dsp_NativeDsp_uint8ToFloat(
        JNIEnv* env, jclass,
        jbyteArray jInput, jfloatArray jOutput, jint length)
{
    jbyte*  input  = env->GetByteArrayElements(jInput,  nullptr);
    jfloat* output = env->GetFloatArrayElements(jOutput, nullptr);

    // Step 1: uint8 → float, scaled by 1/128.  Output range [0.0, ~2.0].
    gnuradio_volk_8u_s32f_convert_32f(
        output,
        reinterpret_cast<const uint8_t*>(input),
        128.0f,
        (unsigned int)length);

    // Step 2: subtract DC offset so range becomes (−1, +1).
    //   (byte / 128) − (127.5 / 128) = (byte − 127.5) / 128
    gnuradio_volk_32f_scalar_add_32f(
        output, output,
        -(127.5f / 128.0f),
        (unsigned int)length);

    env->ReleaseByteArrayElements(jInput,  input,  JNI_ABORT);
    env->ReleaseFloatArrayElements(jOutput, output, 0);
}

// ─────────────────────────────────────────────────────────────────────────────
// FM discriminator: instantaneous phase using atan2 (VOLK pattern)
//
// VOLK function: gnuradio_volk_32fc_s32f_atan2_32f
//
// Computes per-sample phase with atan2f, then differentiates and unwraps
// in a short scalar loop.  The atan2f output is strictly bounded to (−π, π]
// so no division-by-zero spike is possible — this is the core fix for the
// crackling / missing-audio artefact at non-integer rate ratios.
//
// Fix for crackle artefact: the differential-phase formula divides by I²+Q²
// which approaches 0 at signal nulls.  atan2f has no such singularity.
// ─────────────────────────────────────────────────────────────────────────────
extern "C" JNIEXPORT void JNICALL
Java_com_radiosport_ninegradio_dsp_NativeDsp_fmDiscriminator(
        JNIEnv* env, jclass,
        jfloatArray jIq, jfloatArray jOut,
        jint samples, jfloat prevI, jfloat prevQ, jfloat gain)
{
    jfloat* iq  = env->GetFloatArrayElements(jIq,  nullptr);
    jfloat* out = env->GetFloatArrayElements(jOut, nullptr);

    // ── Reusable phase scratch buffer ───────────────────────────────────────
    // Previously `new float[samples]` + `delete[]` ran on EVERY call — at the
    // WFM IF rate (~200 kS/s, ~15-20 blocks/sec) this allocated/freed up to
    // ~800 KB/sec on the JNI hot path, causing GC/allocator pressure that
    // manifested as choppy, stuttering WFM audio. A thread-local static
    // buffer is grown only when a larger block is seen, and otherwise reused
    // across calls with zero allocation.
    thread_local float* phaseBuf = nullptr;
    thread_local unsigned int phaseBufCap = 0;
    if ((unsigned int)samples > phaseBufCap) {
        delete[] phaseBuf;
        phaseBuf = new float[(unsigned int)samples];
        phaseBufCap = (unsigned int)samples;
    }
    float* phase = phaseBuf;

    // Compute atan2(Q,I) for every complex sample in one vectorised pass.
    gnuradio_volk_32fc_s32f_atan2_32f(
        phase,
        reinterpret_cast<const lv_32fc_t*>(iq),
        1.0f,    // normalizeFactor = 1 → output directly in radians
        (unsigned int)samples);

    // Differentiate phases and unwrap to (−π, π].
    static constexpr float PI     =  3.14159265358979f;
    static constexpr float TWO_PI =  6.28318530717959f;

    float prevPhase = atan2f(prevQ, prevI);
    for (int i = 0; i < samples; ++i) {
        float diff = phase[i] - prevPhase;
        if (diff >  PI) diff -= TWO_PI;
        if (diff < -PI) diff += TWO_PI;
        out[i] = diff * gain;
        prevPhase = phase[i];
    }

    env->ReleaseFloatArrayElements(jIq,  iq,  JNI_ABORT);
    env->ReleaseFloatArrayElements(jOut, out, 0);
}

// ─────────────────────────────────────────────────────────────────────────────
// FIR filter — VOLK dot-product kernel
//
// VOLK function: gnuradio_volk_32f_x2_dot_prod_32f (NEON 4-way unrolled)
//
// Convolution by repeated inner products.  Uses a reversed window copy so
// the VOLK kernel sees contiguous aligned data.
//
// Fix: old NEON tail had an off-by-one that read one float past the input
// buffer when (inputLen % 4 != 0) — clean scalar tail eliminates it.
// ─────────────────────────────────────────────────────────────────────────────
extern "C" JNIEXPORT void JNICALL
Java_com_radiosport_ninegradio_dsp_NativeDsp_firFilter(
        JNIEnv* env, jclass,
        jfloatArray jInput, jfloatArray jTaps, jfloatArray jOutput,
        jint inputLen, jint tapLen)
{
    jfloat* input  = env->GetFloatArrayElements(jInput,  nullptr);
    jfloat* taps   = env->GetFloatArrayElements(jTaps,   nullptr);
    jfloat* output = env->GetFloatArrayElements(jOutput, nullptr);

    // Reusable zero-padded window for the dot product — grown only when a
    // larger tap count is seen, avoiding new[]/delete[] on every call.
    thread_local float* winBuf = nullptr;
    thread_local unsigned int winBufCap = 0;
    if ((unsigned int)tapLen > winBufCap) {
        delete[] winBuf;
        winBuf = new float[(unsigned int)tapLen];
        winBufCap = (unsigned int)tapLen;
    }
    float* win = winBuf;

    for (int i = 0; i < inputLen; ++i) {
        int available = (i + 1 < tapLen) ? (i + 1) : tapLen;
        // Fill window: input[i], input[i-1], ..., input[i-available+1]
        for (int k = 0; k < available; ++k)
            win[k] = input[i - k];
        // Zero-pad beyond available history
        if (available < tapLen)
            memset(win + available, 0, (tapLen - available) * sizeof(float));

        float result = 0.0f;
        gnuradio_volk_32f_x2_dot_prod_32f(&result, win, taps,
                                          (unsigned int)tapLen);
        output[i] = result;
    }

    env->ReleaseFloatArrayElements(jInput,  input,  JNI_ABORT);
    env->ReleaseFloatArrayElements(jTaps,   taps,   JNI_ABORT);
    env->ReleaseFloatArrayElements(jOutput, output, 0);
}

// ─────────────────────────────────────────────────────────────────────────────
// AM envelope detector — VOLK magnitude kernel
//
// VOLK function: gnuradio_volk_32fc_magnitude_32f (NEON 4-wide)
// ─────────────────────────────────────────────────────────────────────────────
extern "C" JNIEXPORT void JNICALL
Java_com_radiosport_ninegradio_dsp_NativeDsp_amEnvelope(
        JNIEnv* env, jclass,
        jfloatArray jIq, jfloatArray jOut, jint samples)
{
    jfloat* iq  = env->GetFloatArrayElements(jIq,  nullptr);
    jfloat* out = env->GetFloatArrayElements(jOut, nullptr);

    gnuradio_volk_32fc_magnitude_32f(
        out,
        reinterpret_cast<const lv_32fc_t*>(iq),
        (unsigned int)samples);

    env->ReleaseFloatArrayElements(jIq,  iq,  JNI_ABORT);
    env->ReleaseFloatArrayElements(jOut, out, 0);
}

// ─────────────────────────────────────────────────────────────────────────────
// RMS power — VOLK dot-product on the raw float buffer
//
// Dot product of the IQ float buffer with itself gives Σ(I²+Q²).
// Mean power = Σ(I²+Q²) / N.
// ─────────────────────────────────────────────────────────────────────────────
extern "C" JNIEXPORT jfloat JNICALL
Java_com_radiosport_ninegradio_dsp_NativeDsp_computeRms(
        JNIEnv* env, jclass, jfloatArray jIq, jint samples)
{
    jfloat* iq = env->GetFloatArrayElements(jIq, nullptr);

    float dot = 0.0f;
    // The raw buffer has 2*samples floats (interleaved I,Q).
    // Dot with itself gives Σ I[i]² + Σ Q[i]² = Σ(I²+Q²).
    gnuradio_volk_32f_x2_dot_prod_32f(
        &dot, iq, iq,
        (unsigned int)(samples * 2));

    env->ReleaseFloatArrayElements(jIq, iq, JNI_ABORT);
    return dot / (float)samples;
}

// ─────────────────────────────────────────────────────────────────────────────
// DC-blocking IIR filter (in-place)
//
// The leaky-integrator IIR has a sequential data dependency that prevents
// SIMD vectorisation.  The scalar implementation in iq_convert.cpp is correct
// and optimal.  This JNI shim delegates directly to it.
// ─────────────────────────────────────────────────────────────────────────────
extern "C" JNIEXPORT void JNICALL
Java_com_radiosport_ninegradio_dsp_NativeDsp_dcRemoveInPlace(
        JNIEnv* env, jclass,
        jfloatArray jIq, jint samples, jfloat alpha, jfloatArray jState)
{
    jfloat* iq    = env->GetFloatArrayElements(jIq,    nullptr);
    jfloat* state = env->GetFloatArrayElements(jState, nullptr);

    iq_dc_remove(iq, samples, alpha, state);

    env->ReleaseFloatArrayElements(jIq,    iq,    0);
    env->ReleaseFloatArrayElements(jState, state, 0);
}

// ─────────────────────────────────────────────────────────────────────────────
// Linear resampler — retained for NativeDsp.resample() (debug/test path only).
// Primary resampler is the Kotlin PolyphaseResampler class.
// ─────────────────────────────────────────────────────────────────────────────
extern "C" JNIEXPORT jint JNICALL
Java_com_radiosport_ninegradio_dsp_NativeDsp_resampleLinear(
        JNIEnv* env, jclass,
        jfloatArray jInput, jint inLen,
        jfloatArray jOutput, jint outLen,
        jint inRate, jint outRate)
{
    jfloat* input  = env->GetFloatArrayElements(jInput,  nullptr);
    jfloat* output = env->GetFloatArrayElements(jOutput, nullptr);

    const double ratio = (double)inRate / outRate;
    int written = 0;
    for (int i = 0; i < outLen; ++i) {
        double srcPos = i * ratio;
        int    idx    = (int)srcPos;
        float  frac   = (float)(srcPos - idx);
        float  s0     = (idx     < inLen) ? input[idx]     : 0.0f;
        float  s1     = (idx + 1 < inLen) ? input[idx + 1] : 0.0f;
        output[i]     = s0 + frac * (s1 - s0);
        ++written;
    }

    env->ReleaseFloatArrayElements(jInput,  input,  JNI_ABORT);
    env->ReleaseFloatArrayElements(jOutput, output, 0);
    return written;
}

// ─────────────────────────────────────────────────────────────────────────────
// VOLK machine identification string — for diagnostic logging in NativeDsp.kt.
// Returns "neon" when compiled with NEON, "generic" otherwise.
// ─────────────────────────────────────────────────────────────────────────────
extern "C" JNIEXPORT jstring JNICALL
Java_com_radiosport_ninegradio_dsp_NativeDsp_volkGetMachine(
        JNIEnv* env, jclass)
{
#ifdef GR_HAVE_NEON
    return env->NewStringUTF("gnuradio-volk/neon");
#else
    return env->NewStringUTF("gnuradio-volk/generic");
#endif
}
