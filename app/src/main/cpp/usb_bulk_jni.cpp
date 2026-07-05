/**
 * usb_bulk_jni.cpp — Direct USBDEVFS_BULK ioctl bridge for Android RTL-SDR
 *
 * This is the same approach used by SDR++ Android and keesj/librtlsdr-android:
 * open the Android-granted usbfs fd and issue USBDEVFS_BULK / USBDEVFS_CLEAR_HALT
 * ioctls directly, bypassing the Android Java USB HAL entirely.
 *
 * Why this works when FileInputStream.read() and bulkTransfer() fail:
 *   - bulkTransfer() can return -1 on broken OEM HALs even when the endpoint
 *     is unstalled at the chip level — the host-side HAL reports HALT.
 *   - FileInputStream.read() on a usbfs fd does NOT issue USBDEVFS_BULK; it
 *     triggers a plain VFS read() which the usbdevfs driver rejects (-EINVAL).
 *   - USBDEVFS_BULK ioctl on the same fd is what the kernel actually implements
 *     for bulk endpoint reads; this is what libusb, SDR++, and rtl_tcp_andro use.
 *   - USBDEVFS_CLEAR_HALT ioctl is the kernel equivalent of CLEAR_FEATURE
 *     (ENDPOINT_HALT); it clears both the chip-side and host-side HALT bit in
 *     one ioctl, whereas Android's controlTransfer(0x02, 3, 0, ep, …) only
 *     clears the host descriptor and relies on the HAL to propagate it to the
 *     kernel usbdevfs driver — which broken OEM HALs may not do.
 */

#include <jni.h>
#include <android/log.h>
#include <cerrno>
#include <cstring>
#include <cstdint>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>

#define TAG "UsbBulkJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/**
 * Issue a synchronous USBDEVFS_BULK ioctl on the given usbfs file descriptor.
 *
 * This is the canonical bulk read path used by libusb on Android when it
 * receives an fd from the Java UsbManager (UsbDeviceConnection.getFileDescriptor).
 *
 * @param fd          Raw usbfs file descriptor from UsbDeviceConnection.getFileDescriptor()
 * @param epAddress   Endpoint address (e.g. 0x81 for bulk-IN endpoint 1)
 * @param buf         Destination byte array
 * @param length      Maximum bytes to read
 * @param timeoutMs   Timeout in milliseconds (0 = no timeout)
 * @return            Actual bytes read, or negative errno on error
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_radiosport_ninegradio_usb_RtlSdrUsbHelper_nativeBulkRead(
        JNIEnv* env, jclass,
        jint fd, jint epAddress,
        jbyteArray jBuf, jint length, jint timeoutMs)
{
    if (fd < 0) {
        LOGE("nativeBulkRead: invalid fd %d", fd);
        return -1;
    }

    jbyte* buf = env->GetByteArrayElements(jBuf, nullptr);
    if (!buf) {
        LOGE("nativeBulkRead: GetByteArrayElements returned null");
        return -1;
    }

    struct usbdevfs_bulktransfer bulk;
    memset(&bulk, 0, sizeof(bulk));
    bulk.ep      = (unsigned int)epAddress;
    bulk.len     = (unsigned int)length;
    bulk.timeout = (unsigned int)timeoutMs;
    bulk.data    = buf;

    int ret = ioctl(fd, USBDEVFS_BULK, &bulk);

    env->ReleaseByteArrayElements(jBuf, buf, (ret > 0) ? 0 : JNI_ABORT);

    if (ret < 0) {
        int err = errno;
        // ENODEV / ENOENT = device disconnected (not a stall, escalate)
        // EPIPE          = endpoint stalled (HALT bit set) — recoverable
        // ETIMEDOUT      = timeout — recoverable
        if (err != EPIPE && err != ETIMEDOUT) {
            LOGW("nativeBulkRead: ioctl USBDEVFS_BULK ep=0x%02x errno=%d (%s)",
                 epAddress, err, strerror(err));
        }
        return -err;          // negative errno: caller can distinguish stall (-EPIPE=32) vs disconnect
    }

    return ret;   // actual bytes read
}

/**
 * Issue USBDEVFS_CLEAR_HALT on the given usbfs fd and endpoint.
 *
 * This is the kernel-level equivalent of USB CLEAR_FEATURE(ENDPOINT_HALT).
 * Unlike Android's controlTransfer(0x02, 3, 0, ep, …), this ioctl clears
 * the halt bit in the kernel usbdevfs driver directly, so it works on
 * OEM kernels where the Java HAL controlTransfer does not propagate the
 * clear to usbdevfs.
 *
 * @param fd          Raw usbfs file descriptor
 * @param epAddress   Endpoint address (e.g. 0x81)
 * @return            0 on success, negative errno on error
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_radiosport_ninegradio_usb_RtlSdrUsbHelper_nativeClearHalt(
        JNIEnv* env, jclass,
        jint fd, jint epAddress)
{
    if (fd < 0) {
        LOGE("nativeClearHalt: invalid fd %d", fd);
        return -1;
    }

    unsigned int ep = (unsigned int)epAddress;
    int ret = ioctl(fd, USBDEVFS_CLEAR_HALT, &ep);
    if (ret < 0) {
        int err = errno;
        LOGW("nativeClearHalt: ioctl USBDEVFS_CLEAR_HALT ep=0x%02x errno=%d (%s)",
             epAddress, err, strerror(err));
        return -err;
    }
    LOGI("nativeClearHalt: cleared halt on ep=0x%02x (fd=%d)", epAddress, fd);
    return 0;
}

/**
 * Reset the USB device via USBDEVFS_RESET ioctl.
 * Used as last resort when CLEAR_HALT doesn't recover the endpoint.
 *
 * WARNING: this disconnects and re-enumerates the device, which will
 * invalidate the UsbDeviceConnection. Only call if you intend to reconnect.
 *
 * @param fd   Raw usbfs file descriptor
 * @return     0 on success, negative errno on error
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_radiosport_ninegradio_usb_RtlSdrUsbHelper_nativeResetDevice(
        JNIEnv* env, jclass, jint fd)
{
    if (fd < 0) return -1;
    int ret = ioctl(fd, USBDEVFS_RESET, nullptr);
    if (ret < 0) {
        int err = errno;
        LOGE("nativeResetDevice: USBDEVFS_RESET errno=%d (%s)", err, strerror(err));
        return -err;
    }
    LOGI("nativeResetDevice: device reset issued (fd=%d)", fd);
    return 0;
}
