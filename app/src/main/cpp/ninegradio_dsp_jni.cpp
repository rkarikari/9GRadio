// ninegradio_dsp_jni.cpp
// JNI implementations for two Kotlin classes that load "ninegradio_dsp":
//
//   RtlSdrUsbHelper (companion object, @JvmStatic) — USB usbdevfs ioctls
//   NativeDsp       (object,           @JvmStatic) — SDR DSP primitives
//
// Both use @JvmStatic, so all JNI functions take (JNIEnv*, jclass, ...).

#include <jni.h>
#include <android/log.h>

// USB ioctl headers
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>
#include <errno.h>
#include <string.h>

// DSP headers
#include <math.h>
#include <stdint.h>

// =============================================================================
// RtlSdrUsbHelper — USB usbdevfs ioctl bridge
// com.radiosport.ninegradio.usb.RtlSdrUsbHelper (companion object, @JvmStatic)
// =============================================================================

#define USB_TAG "ninegradio_dsp"

extern "C"
JNIEXPORT jint JNICALL
Java_com_radiosport_ninegradio_usb_RtlSdrUsbHelper_nativeBulkRead(
        JNIEnv* env, jclass,
        jint fd, jint epAddress,
        jbyteArray buf, jint length, jint timeoutMs)
{
    if (fd < 0 || length <= 0) return -EINVAL;
    jbyte* data = env->GetByteArrayElements(buf, nullptr);
    if (!data) return -ENOMEM;

    struct usbdevfs_bulktransfer bulk{};
    bulk.ep      = (unsigned int)epAddress;
    bulk.len     = (unsigned int)length;
    bulk.timeout = (unsigned int)timeoutMs;
    bulk.data    = data;

    int ret = ioctl(fd, USBDEVFS_BULK, &bulk);
    int err = (ret < 0) ? errno : 0;
    env->ReleaseByteArrayElements(buf, data, (ret > 0) ? 0 : JNI_ABORT);

    if (ret < 0) {
        __android_log_print(ANDROID_LOG_WARN, USB_TAG,
            "nativeBulkRead: ep=0x%02x errno=%d (%s)", epAddress, err, strerror(err));
        return -err;
    }
    return ret;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_radiosport_ninegradio_usb_RtlSdrUsbHelper_nativeClearHalt(
        JNIEnv*, jclass,
        jint fd, jint epAddress)
{
    if (fd < 0) return -EBADF;
    unsigned int ep = (unsigned int)epAddress;
    int ret = ioctl(fd, USBDEVFS_CLEAR_HALT, &ep);
    if (ret < 0) {
        int err = errno;
        __android_log_print(ANDROID_LOG_WARN, USB_TAG,
            "nativeClearHalt: ep=0x%02x errno=%d (%s)", epAddress, err, strerror(err));
        return -err;
    }
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_radiosport_ninegradio_usb_RtlSdrUsbHelper_nativeResetDevice(
        JNIEnv*, jclass,
        jint fd)
{
    if (fd < 0) return -EBADF;
    int ret = ioctl(fd, USBDEVFS_RESET, nullptr);
    if (ret < 0) {
        int err = errno;
        __android_log_print(ANDROID_LOG_WARN, USB_TAG,
            "nativeResetDevice: errno=%d (%s)", err, strerror(err));
        return -err;
    }
    return 0;
}

// =============================================================================
// NativeDsp — SDR DSP primitives
// com.radiosport.ninegradio.dsp.NativeDsp (Kotlin object, @JvmStatic)
// =============================================================================

extern "C"
JNIEXPORT void JNICALL
Java_com_radiosport_ninegradio_dsp_NativeDsp_uint8ToFloat(
        JNIEnv* env, jclass,
        jbyteArray input, jfloatArray output, jint length)
{
    jbyte*  in  = env->GetByteArrayElements(input,  nullptr);
    jfloat* out = env->GetFloatArrayElements(output, nullptr);
    for (jint i = 0; i < length; i++)
        out[i] = ((in[i] & 0xFF) - 127.5f) / 128.0f;
    env->ReleaseByteArrayElements(input,  in,  JNI_ABORT);
    env->ReleaseFloatArrayElements(output, out, 0);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_radiosport_ninegradio_dsp_NativeDsp_fmDiscriminator(
        JNIEnv* env, jclass,
        jfloatArray iq_arr, jfloatArray out_arr, jint samples,
        jfloat prevI, jfloat prevQ, jfloat gain)
{
    jfloat* iq  = env->GetFloatArrayElements(iq_arr,  nullptr);
    jfloat* out = env->GetFloatArrayElements(out_arr, nullptr);
    float prevPhase = atan2f(prevQ, prevI);
    for (jint i = 0; i < samples; i++) {
        float phase = atan2f(iq[2*i+1], iq[2*i]);
        float diff  = phase - prevPhase;
        if (diff >  (float)M_PI) diff -= 2.0f*(float)M_PI;
        if (diff < -(float)M_PI) diff += 2.0f*(float)M_PI;
        out[i]    = diff * gain;
        prevPhase = phase;
    }
    env->ReleaseFloatArrayElements(iq_arr,  iq,  JNI_ABORT);
    env->ReleaseFloatArrayElements(out_arr, out, 0);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_radiosport_ninegradio_dsp_NativeDsp_firFilter(
        JNIEnv* env, jclass,
        jfloatArray input_arr, jfloatArray taps_arr,
        jfloatArray output_arr,
        jint inputLen, jint tapLen)
{
    jfloat* in   = env->GetFloatArrayElements(input_arr,  nullptr);
    jfloat* taps = env->GetFloatArrayElements(taps_arr,   nullptr);
    jfloat* out  = env->GetFloatArrayElements(output_arr, nullptr);
    for (jint i = 0; i < inputLen; i++) {
        float acc = 0.0f;
        for (jint k = 0; k < tapLen; k++)
            if (i - k >= 0) acc += taps[k] * in[i - k];
        out[i] = acc;
    }
    env->ReleaseFloatArrayElements(input_arr,  in,   JNI_ABORT);
    env->ReleaseFloatArrayElements(taps_arr,   taps, JNI_ABORT);
    env->ReleaseFloatArrayElements(output_arr, out,  0);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_radiosport_ninegradio_dsp_NativeDsp_amEnvelope(
        JNIEnv* env, jclass,
        jfloatArray iq_arr, jfloatArray out_arr, jint samples)
{
    jfloat* iq  = env->GetFloatArrayElements(iq_arr,  nullptr);
    jfloat* out = env->GetFloatArrayElements(out_arr, nullptr);
    for (jint i = 0; i < samples; i++) {
        float I = iq[2*i], Q = iq[2*i+1];
        out[i] = sqrtf(I*I + Q*Q);
    }
    env->ReleaseFloatArrayElements(iq_arr,  iq,  JNI_ABORT);
    env->ReleaseFloatArrayElements(out_arr, out, 0);
}

extern "C"
JNIEXPORT jfloat JNICALL
Java_com_radiosport_ninegradio_dsp_NativeDsp_computeRms(
        JNIEnv* env, jclass,
        jfloatArray iq_arr, jint samples)
{
    if (samples <= 0) return 0.0f;
    jfloat* iq = env->GetFloatArrayElements(iq_arr, nullptr);
    double power = 0.0;
    for (jint i = 0; i < samples; i++) {
        double I = iq[2*i], Q = iq[2*i+1];
        power += I*I + Q*Q;
    }
    env->ReleaseFloatArrayElements(iq_arr, iq, JNI_ABORT);
    return (jfloat)(power / samples);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_radiosport_ninegradio_dsp_NativeDsp_resampleLinear(
        JNIEnv* env, jclass,
        jfloatArray input_arr,  jint inLen,
        jfloatArray output_arr, jint outLen,
        jint inRate, jint outRate)
{
    jfloat* in  = env->GetFloatArrayElements(input_arr,  nullptr);
    jfloat* out = env->GetFloatArrayElements(output_arr, nullptr);
    double ratio = (double)inRate / outRate;
    for (jint i = 0; i < outLen; i++) {
        double srcPos = i * ratio;
        int    idx    = (int)srcPos;
        float  frac   = (float)(srcPos - idx);
        float  s0 = (idx     < inLen) ? in[idx]     : 0.0f;
        float  s1 = (idx + 1 < inLen) ? in[idx + 1] : 0.0f;
        out[i] = s0 + frac * (s1 - s0);
    }
    env->ReleaseFloatArrayElements(input_arr,  in,  JNI_ABORT);
    env->ReleaseFloatArrayElements(output_arr, out, 0);
    return outLen;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_radiosport_ninegradio_dsp_NativeDsp_dcRemoveInPlace(
        JNIEnv* env, jclass,
        jfloatArray iq_arr, jint samples, jfloat alpha,
        jfloatArray state_arr)
{
    jfloat* iq    = env->GetFloatArrayElements(iq_arr,    nullptr);
    jfloat* state = env->GetFloatArrayElements(state_arr, nullptr);
    float beta = 1.0f - alpha;
    float di = state[0], dq = state[1];
    for (jint i = 0; i < samples; i++) {
        float xi = iq[2*i], xq = iq[2*i+1];
        di = alpha*di + beta*xi;
        dq = alpha*dq + beta*xq;
        iq[2*i]   = xi - di;
        iq[2*i+1] = xq - dq;
    }
    state[0] = di; state[1] = dq;
    env->ReleaseFloatArrayElements(iq_arr,    iq,    0);
    env->ReleaseFloatArrayElements(state_arr, state, 0);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_radiosport_ninegradio_dsp_NativeDsp_volkGetMachine(
        JNIEnv* env, jclass)
{
    return env->NewStringUTF("neon_generic");
}
