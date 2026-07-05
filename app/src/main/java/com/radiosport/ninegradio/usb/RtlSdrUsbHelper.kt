package com.radiosport.ninegradio.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.util.Log
import java.io.FileDescriptor
import java.lang.reflect.Field
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android USB bulk-transfer helper — SDR++ Android / rtl_tcp_andro / keesj pattern.
 *
 * ## Background
 *
 * The previous implementation used Android's async [UsbRequest] / [UsbDeviceConnection.requestWait]
 * API as its primary transfer tier.  On Samsung Galaxy devices (r7naxx, Android 13) this reliably
 * produces SIGSEGV at `usb_request_wait+204` (fault addr 0x305a), regardless of synchronization
 * guards around [UsbRequest.cancel] / [UsbRequest.close]:
 *
 *   • x0 = 0x3046 at crash site — the `usb_device*` passed to usb_request_wait() is already a
 *     dangling/freed pointer before any close() call can corrupt it.
 *   • The crash reproduces on both the initial start (process uptime 3–4 s) and after a restart,
 *     ruling out a teardown race as the sole cause.
 *   • Samsung's USB HAL frees the native usb_device* at an indeterminate point relative to
 *     requestWait(), and no amount of inRequestWait / teardownInProgress flagging closes the gap
 *     because the corruption happens at the HAL level, below the Java synchronization layer.
 *
 * ## Three-tier transfer strategy
 *
 * ### Tier 0 — Synchronous bulkTransfer()  (primary, SDR++ / rtl_tcp_andro pattern)
 *   [UsbDeviceConnection.bulkTransfer] is a direct wrapper around USBDEVFS_BULK ioctl.
 *   It is fully re-entrant from a single thread, requires no shared native state beyond the
 *   connection handle, and returns actual_length as its integer return value with no
 *   ByteBuffer.position() / limit() ambiguity.  It is safe to call after cancel or close:
 *   the call simply returns -1, allowing the streaming loop to exit cleanly without any
 *   explicit synchronization.
 *
 *   Trade-off vs async URBs: a single synchronous bulkTransfer() leaves a small FIFO-stall
 *   window between transfers (~0.2 ms at 2.4 MSPS / 131 072 B buffers).  In practice this is
 *   invisible — the RTL2832U FIFO is 512 KB and the host re-issues the ioctl faster than the
 *   FIFO can fill — and is far preferable to a non-deterministic SIGSEGV.
 *
 * ### Tier 1 — Direct USBDEVFS_BULK ioctl via JNI  (SDR++ / libusb native pattern)
 *   When [bulkTransfer] returns -1 persistently ([FD_FALLBACK_THRESHOLD] consecutive errors),
 *   falls back to calling the USBDEVFS_BULK ioctl directly via a JNI bridge
 *   (RtlSdrUsbHelper.nativeBulkRead).  This is exactly what SDR++ Android and libusb do:
 *   they open the Android-granted fd from UsbDeviceConnection.getFileDescriptor() and call
 *   ioctl(fd, USBDEVFS_BULK, &bulk) — the kernel usbdevfs driver returns actual_length from
 *   struct usbdevfs_urb, bypassing the broken OEM HAL entirely.
 *
 *   This path also issues USBDEVFS_CLEAR_HALT via nativeClearHalt() before the first read,
 *   which clears the endpoint halt bit in the kernel directly (not just in the Java descriptor),
 *   fixing the case where Android's controlTransfer(0x02, 3, 0, ep) does not propagate the
 *   clear to the kernel usbdevfs driver on some OEM builds.
 *
 *   NOTE: FileInputStream.read() on a usbfs fd does NOT issue USBDEVFS_BULK — it triggers
 *   a plain VFS read() which the usbdevfs driver rejects with -EINVAL.  The ioctl path is
 *   the only correct Java-accessible fallback when bulkTransfer() fails.
 *
 * ## Thread safety
 *   [read] must be called from a single thread (the IO streaming coroutine).
 *   [startPipeline] / [stopPipeline] / [takePipelineResult] provide a multi-threaded
 *   pipelined read path that submits [NUM_PIPELINE_THREADS] concurrent bulkTransfer()
 *   calls; see their KDoc for details.
 *   [close], [resetPath], and [clearHaltNative] may be called from any thread.
 */
class RtlSdrUsbHelper(
    private val connection: UsbDeviceConnection,
    private val endpoint: UsbEndpoint
) : AutoCloseable {

    companion object {
        private const val TAG = "RtlSdrUsbHelper"

        /**
         * Number of concurrent bulkTransfer() threads in the pipeline.
         *
         * rtl_tcp_andro uses 15 async libusb URBs (DEFAULT_BUF_NUMBER in librtlsdr.c).
         * We replicate that with 3 concurrent Java threads, each blocking on its own
         * bulkTransfer() call.  3 is sufficient: at 2 MS/s / 32 KB buffers, each
         * transfer takes ~4 ms.  With 3 in-flight the USB host always has a queued
         * URB ready the instant any one completes, matching the async-URB behaviour
         * that makes rtl_tcp_andro stutter-free.
         */
        const val NUM_PIPELINE_THREADS = 3

        /**
         * Consecutive bulkTransfer(-1) count before switching to the JNI/ioctl path.
         * Kept at 2 so the fallback activates quickly on broken OEM HALs: the first
         * transfer attempt is always on tier 0, and if the endpoint halt bit is already
         * set (from a previous session) we want to switch to the ioctl path fast.
         */
        const val FD_FALLBACK_THRESHOLD = 2

        /** Human-readable descriptor of a [UsbEndpoint] for log/debug output. */
        fun endpointDesc(ep: UsbEndpoint): String =
            "ep=0x${(ep.address and 0xFF).toString(16)} " +
            "dir=${if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"} " +
            "type=${ep.type} maxPkt=${ep.maxPacketSize}"

        // Reflect the private int field that backs FileDescriptor so we can wrap a raw
        // file-descriptor integer obtained from UsbDeviceConnection.getFileDescriptor().
        // The field is named "descriptor" before API 28 and "fd" from API 28 onward.
        private val FD_INT_FIELD: Field? by lazy {
            try {
                FileDescriptor::class.java
                    .getDeclaredField("descriptor")
                    .also { it.isAccessible = true }
            } catch (_: NoSuchFieldException) {
                try {
                    FileDescriptor::class.java
                        .getDeclaredField("fd")
                        .also { it.isAccessible = true }
                } catch (_: Exception) { null }
            }
        }

        // ── Native JNI bridge (loaded via ninegradio_dsp shared library) ────────

        /**
         * Issue a synchronous USBDEVFS_BULK ioctl on the given usbfs fd.
         * Returns actual bytes read (> 0), or negative errno on error.
         * -32 = -EPIPE = endpoint stalled (recoverable).
         * -19 = -ENODEV = device disconnected (escalate to reconnect).
         */
        @JvmStatic
        external fun nativeBulkRead(
            fd: Int, epAddress: Int,
            buf: ByteArray, length: Int, timeoutMs: Int
        ): Int

        /**
         * Issue USBDEVFS_CLEAR_HALT on the given usbfs fd and endpoint.
         * Clears the kernel-side halt bit directly — bypasses OEM HAL bugs
         * where Android's controlTransfer(0x02,3,0,ep) doesn't propagate.
         * Returns 0 on success, negative errno on error.
         */
        @JvmStatic
        external fun nativeClearHalt(fd: Int, epAddress: Int): Int

        /**
         * Issue USBDEVFS_RESET — last-resort device reset.
         * Invalidates the UsbDeviceConnection; only call before reconnect.
         */
        @JvmStatic
        external fun nativeResetDevice(fd: Int): Int

        private var nativeAvailable = false

        init {
            try {
                System.loadLibrary("ninegradio_dsp")
                nativeAvailable = true
                Log.i(TAG, "Native USB bulk ioctl library loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Native library unavailable — tier 1 ioctl path disabled: ${e.message}")
            }
        }
    }

    // ── State ────────────────────────────────────────────────────────────────

    @Volatile private var closed = false

    /**
     * Active transfer tier:
     *   0 = bulkTransfer()       (primary, SDR++ / rtl_tcp_andro pattern)
     *   1 = USBDEVFS_BULK ioctl  (JNI / libusb / SDR++ native-layer fallback)
     */
    @Volatile private var tier = 0

    /** Consecutive -1 returns from bulkTransfer() since the last successful read. */
    private var consecutiveBulkErrors = 0

    /** True once we have switched permanently to the JNI ioctl path (tier 1). */
    val usingNativePath: Boolean get() = tier == 1

    // ── Tier 1 — raw usbfs fd (for USBDEVFS_BULK ioctl) ──────────────────────

    /** Raw usbfs file descriptor from UsbDeviceConnection.getFileDescriptor(). */
    private var rawFd: Int = -1

    /** Whether the native clear-halt was issued when we entered tier 1. */
    private var nativeClearHaltIssued = false

    // ────────────────────────────────────────────────────────────────────────
    //  PUBLIC API
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Read up to [length] bytes of IQ data into [buf], blocking until data arrive or
     * [timeoutMs] elapses.
     *
     * Returns:
     *  - > 0 : actual bytes read
     *  -   0 : zero-length packet (device alive, FIFO empty — retry immediately)
     *  -  -1 : error or timeout
     */
    fun read(buf: ByteArray, length: Int, timeoutMs: Int): Int {
        if (closed) return -1
        return when (tier) {
            0    -> readViaBulkTransfer(buf, length, timeoutMs)
            else -> readViaIoctl(buf, length, timeoutMs)
        }
    }

    /**
     * Issue USBDEVFS_CLEAR_HALT directly via the kernel ioctl, bypassing the
     * Android Java HAL.  Called from RtlSdrDevice.resetEndpoint() in addition
     * to the controlTransfer(0x02,3,0,ep) call, so the halt bit is guaranteed
     * to be cleared at both the Java descriptor level and the kernel usbdevfs
     * level — covering OEM HALs that do not propagate one to the other.
     *
     * Safe to call from any thread; returns immediately.
     */
    fun clearHaltNative(): Boolean {
        if (!nativeAvailable) return false
        val fd = connection.fileDescriptor
        if (fd < 0) return false
        val epAddr = endpoint.address and 0xFF
        val ret = nativeClearHalt(fd, epAddr)
        if (ret != 0) {
            Log.w(TAG, "clearHaltNative: ep=0x${epAddr.toString(16)} returned $ret")
        } else {
            Log.i(TAG, "clearHaltNative: halt cleared on ep=0x${epAddr.toString(16)}")
        }
        return ret == 0
    }

    /**
     * Reset transfer-path state back to bulkTransfer().
     *
     * Safe to call from any thread at any time.  Called by
     * [RtlSdrDevice.restartStreaming] and [RtlSdrDevice.stopStreaming].
     */
    fun resetPath() {
        stopPipeline()
        consecutiveBulkErrors = 0
        nativeClearHaltIssued = false
        if (tier != 0) {
            Log.i(TAG, "resetPath(): returning to bulkTransfer() (tier 0)")
        }
        rawFd = -1
        tier = 0
    }

    /** Human-readable name of the currently active transfer path (for logs/debug UI). */
    fun transferMode(): String = when (tier) {
        0    -> "bulkTransfer()"
        else -> "ioctl(USBDEVFS_BULK)"
    }

    // ────────────────────────────────────────────────────────────────────────
    //  PIPELINED MULTI-BUFFER STREAMING
    // ────────────────────────────────────────────────────────────────────────
    //
    // Root cause of USB stutter (confirmed from rtl_tcp_andro source):
    //
    //   rtlsdrdevice.c calls rtlsdr_read_async(device, cb, NULL, 0, 0).
    //   librtlsdr.c DEFAULT_BUF_NUMBER = 15, DEFAULT_BUF_LENGTH = 262,144 bytes.
    //   rtlsdr_read_async() submits ALL 15 URBs to the kernel simultaneously via
    //   libusb_submit_transfer().  The USB host controller keeps 15 transfers queued;
    //   when one completes, the next is ALREADY IN THE KERNEL — zero gap.
    //   The RTL2832U FIFO drains continuously at the full hardware rate.
    //
    //   9GRadio's single-thread synchronous bulkTransfer() model submits ONE URB
    //   at a time.  After bulkTransfer() returns, ~0–1 ms elapses while the
    //   streaming coroutine copies chunks, updates DebugBus, etc.  During that
    //   gap NO URB is waiting in the kernel, so the device FIFO begins refilling.
    //   The NEXT bulkTransfer() must then wait ~4 ms for the FIFO to reach 32 KB
    //   before the kernel can satisfy it.  Result: each 32 KB transfer (4 ms of
    //   data) takes ~8 ms wall time — exactly what the diagnostic reports:
    //     "USB avg transfer 7.92 ms (max 11 ms)" → rate 52% below expected.
    //
    // Fix: submit NUM_PIPELINE_THREADS = 3 concurrent bulkTransfer() calls on
    // separate Java threads.  Completed buffers are posted to a
    // LinkedBlockingQueue; the streaming coroutine takes() from it in order.
    // With 3 threads in flight the host controller always has a URB queued,
    // eliminating the FIFO-fill gap.  Each 32 KB transfer then completes in the
    // true hardware time (~4 ms), doubling the delivery rate to ~250 blocks/s
    // and matching the TCP source's continuous stream behaviour.
    //
    // Thread safety: pipeline threads call connection.bulkTransfer() directly,
    // bypassing the tier-switch state machine in read() (consecutiveBulkErrors,
    // tier) which is not thread-safe.  Errors are signalled via the sentinel
    // PipelineResult.bytesRead < 0 and handled by the streaming coroutine.
    // The tier-1 ioctl path is used for the non-pipeline single-thread read()
    // fallback only (error recovery after streaming restart).

    /** Sentinel value posted to [pipelineQueue] when a pipeline thread exits. */
    private val PIPELINE_EOF = ByteArray(0)

    data class PipelineResult(val buf: ByteArray, val bytesRead: Int)

    @Volatile private var pipelineActive = false
    private val pipelineQueue = LinkedBlockingQueue<PipelineResult>(NUM_PIPELINE_THREADS * 4)
    private val pipelineThreads = mutableListOf<Thread>()
    private val pipelineErrorCount = AtomicInteger(0)

    /**
     * Start [NUM_PIPELINE_THREADS] background Java threads, each issuing overlapping
     * [android.hardware.usb.UsbDeviceConnection.bulkTransfer] calls.
     *
     * Completed results are enqueued to [pipelineQueue].  The caller must consume
     * them promptly via [takePipelineResult]; the queue capacity is bounded to
     * [NUM_PIPELINE_THREADS] × 4 to bound memory use without blocking producers.
     *
     * Each thread calls bulkTransfer() directly — not through [read()] — so the
     * tier-switch state machine ([consecutiveBulkErrors] etc.) is not involved.
     * Errors (bytesRead ≤ 0) are reported via [PipelineResult.bytesRead] and the
     * thread exits, posting a final result so the consumer can detect termination.
     *
     * Call [stopPipeline] before [close].
     */
    fun startPipeline(bufferSize: Int, timeoutMs: Int) {
        if (pipelineActive) return
        pipelineActive = true
        pipelineErrorCount.set(0)
        pipelineThreads.clear()
        pipelineQueue.clear()
        repeat(NUM_PIPELINE_THREADS) { idx ->
            val t = Thread({
                Log.i(TAG, "Pipeline thread $idx started (buf=${bufferSize}B timeout=${timeoutMs}ms)")
                while (pipelineActive && !closed) {
                    val buf = ByteArray(bufferSize)
                    // Call bulkTransfer() directly — NOT through read() — to avoid
                    // mutating consecutiveBulkErrors / tier from multiple threads.
                    val n = connection.bulkTransfer(endpoint, buf, bufferSize, timeoutMs)
                    try {
                        pipelineQueue.put(PipelineResult(buf, n))
                    } catch (_: InterruptedException) {
                        break
                    }
                    if (n < 0) {
                        // A negative return signals a USB error.  Track it; the
                        // streaming coroutine will decide whether to restart.
                        val errors = pipelineErrorCount.incrementAndGet()
                        Log.w(TAG, "Pipeline thread $idx: bulkTransfer returned $n (error #$errors)")
                        if (errors >= NUM_PIPELINE_THREADS * 3) {
                            // All threads hitting errors — device likely disconnected.
                            Log.e(TAG, "Pipeline: too many errors, thread $idx exiting")
                            break
                        }
                    } else {
                        // Successful transfer resets the shared error counter.
                        pipelineErrorCount.set(0)
                    }
                }
                Log.i(TAG, "Pipeline thread $idx exiting")
            }, "usb-pipeline-$idx")
            t.isDaemon = true
            t.start()
            pipelineThreads.add(t)
        }
        Log.i(TAG, "Pipeline started: $NUM_PIPELINE_THREADS threads, buf=${bufferSize}B")
    }

    /**
     * Block until the next completed transfer is available, or [pollTimeoutMs] elapses.
     *
     * Returns null on timeout (caller should check [pipelineActive] and retry).
     */
    fun takePipelineResult(pollTimeoutMs: Long = 500L): PipelineResult? =
        pipelineQueue.poll(pollTimeoutMs, TimeUnit.MILLISECONDS)

    /**
     * Stop all pipeline threads and drain the queue.
     * Safe to call from any thread.  Blocks briefly for threads to notice the stop flag.
     */
    fun stopPipeline() {
        pipelineActive = false
        pipelineThreads.forEach { it.interrupt() }
        pipelineThreads.clear()
        pipelineQueue.clear()
        Log.i(TAG, "Pipeline stopped")
    }

    override fun close() {
        closed = true
        stopPipeline()
        rawFd = -1
        // No UsbRequest objects to cancel or close.
        // The in-flight bulkTransfer() / nativeBulkRead() (if any) will return
        // once RtlSdrDevice.close() calls connection.close() (which makes
        // bulkTransfer() fail-fast) or the timeout elapses.
    }

    // ────────────────────────────────────────────────────────────────────────
    //  TIER 0 — Synchronous bulkTransfer()  (primary path)
    // ────────────────────────────────────────────────────────────────────────

    private fun readViaBulkTransfer(buf: ByteArray, length: Int, timeoutMs: Int): Int {
        val n = connection.bulkTransfer(endpoint, buf, length, timeoutMs)
        return when {
            n > 0 -> {
                consecutiveBulkErrors = 0
                n
            }
            n == 0 -> {
                consecutiveBulkErrors = 0
                0
            }
            else -> {
                // n < 0 — USB stall, device disconnected, or connection closed.
                consecutiveBulkErrors++
                Log.w(TAG, "bulkTransfer() returned $n (error #$consecutiveBulkErrors)")
                if (consecutiveBulkErrors >= FD_FALLBACK_THRESHOLD) {
                    // Switch to the direct USBDEVFS_BULK ioctl path.
                    Log.w(TAG, "Switching to ioctl(USBDEVFS_BULK) path after $consecutiveBulkErrors errors")
                    if (openNativePath()) {
                        tier = 1
                        consecutiveBulkErrors = 0
                        readViaIoctl(buf, length, timeoutMs)   // retry immediately on new path
                    } else {
                        Log.e(TAG, "Native ioctl path unavailable — staying on bulkTransfer()")
                        consecutiveBulkErrors = 0
                        n
                    }
                } else {
                    n
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  TIER 1 — Direct USBDEVFS_BULK ioctl via JNI  (libusb / SDR++ pattern)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Obtain the raw usbfs fd and, on first entry, issue USBDEVFS_CLEAR_HALT
     * to ensure the endpoint halt bit is cleared at the kernel level.
     */
    private fun openNativePath(): Boolean {
        if (!nativeAvailable) {
            Log.e(TAG, "openNativePath: native library not loaded")
            return false
        }
        val fd = connection.fileDescriptor
        if (fd < 0) {
            Log.e(TAG, "openNativePath: getFileDescriptor() returned $fd")
            return false
        }
        rawFd = fd
        // Issue kernel-level CLEAR_HALT so the USBDEVFS_BULK ioctl doesn't
        // immediately return -EPIPE on the very first call.
        if (!nativeClearHaltIssued) {
            val epAddr = endpoint.address and 0xFF
            val r = nativeClearHalt(fd, epAddr)
            Log.i(TAG, "openNativePath: nativeClearHalt ep=0x${epAddr.toString(16)} → $r")
            nativeClearHaltIssued = true
        }
        Log.i(TAG, "openNativePath: rawFd=$fd endpoint=${endpointDesc(endpoint)}")
        return true
    }

    /**
     * Read via direct USBDEVFS_BULK ioctl.
     *
     * nativeBulkRead() returns actual_length (> 0) on success, or a negative
     * errno value on error.  We map -EPIPE (stall, recoverable) → -1 so the
     * streaming loop sees the same convention as bulkTransfer(), and propagate
     * -ENODEV (device gone) as -1 (the streaming loop will escalate on N
     * consecutive errors regardless).
     */
    private fun readViaIoctl(buf: ByteArray, length: Int, timeoutMs: Int): Int {
        val fd = rawFd
        if (fd < 0) return -1
        val epAddr = endpoint.address and 0xFF
        val n = nativeBulkRead(fd, epAddr, buf, length, timeoutMs)
        return when {
            n > 0 -> n
            n == 0 -> 0
            else -> {
                // Negative errno from the ioctl.  Map all errors to -1 for the
                // streaming loop; it already handles consecutive -1 returns.
                Log.w(TAG, "nativeBulkRead: errno=${-n} (ep=0x${epAddr.toString(16)})")
                -1
            }
        }
    }
}
