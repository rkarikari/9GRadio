package com.radiosport.ninegradio.dsp

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Pipes the same raw, pre-vocoder FM-discriminator audio that feeds
 * [DigitalVoiceDecoder] (and the APRS/ACARS/FLEX decoders) out over UDP as
 * headerless, signed 16-bit little-endian mono PCM datagrams -- the exact
 * wire format documented by dsd-neo / dsd-fme's `-i udp[:bind_addr:port]`
 * input (default `127.0.0.1:7355`, sample rate given with `-s`, 48000 by
 * default) and by most other command-line DSD tools' UDP audio input.
 *
 * This lets 9GRadio act purely as an RTL-SDR front end / FM discriminator
 * for a full desktop decoder (dsd-neo, DSD-FME, DSDPlus, SDRTrunk's audio
 * input, etc.) running on a PC on the same network -- useful both as a
 * cross-check against the built-in dsdcc decoder and for protocols 9GRadio
 * does not implement natively (P25 Phase 2, EDACS, ProVoice, encrypted
 * traffic key handling, trunking control-channel following, and so on).
 *
 * Usage:
 *   stream.configure(host, port)   // may be called while running to retune
 *   stream.setEnabled(true)
 *   stream.send(decoderAudio)      // [-1, 1] float samples, called once per
 *                                  // DSP block, same thread
 *   stream.setEnabled(false)       // or stream.shutdown() when tearing down
 *
 * [send] is designed to be called inline from the DSP processing
 * thread/coroutine on every block, exactly like [DigitalVoiceDecoder.feed]
 * -- see the "FIX 21" / "FIX" comments in DspEngine.processIqBlock() for why
 * that call site must stay single-threaded and non-blocking. To honour that,
 * [send] never touches the socket itself: it just offers the (copied) sample
 * block onto a small bounded queue and returns immediately. A single
 * dedicated sender thread owns the [DatagramSocket] and does the actual
 * (blocking) I/O, encoding, and any host/port changes. If the queue is full
 * (the network can't keep up, or nothing is listening) the oldest pending
 * block is dropped rather than blocking or growing without bound -- audio
 * continuity for the *local* decoders must never depend on an external
 * listener being present or fast enough.
 */
class ExternalDecoderStream {

    companion object {
        private const val TAG = "ExternalDecoderStream"
        /** Matches dsd-neo/dsd-fme's default `-i udp` bind/port. */
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 7355
        /** Bounded so a stalled/absent listener can't build unbounded backlog. */
        private const val QUEUE_CAPACITY = 32
    }

    @Volatile private var host: String = DEFAULT_HOST
    @Volatile private var port: Int = DEFAULT_PORT
    private val enabled = AtomicBoolean(false)

    private val queue = ArrayBlockingQueue<ShortArray>(QUEUE_CAPACITY)
    private var senderThread: Thread? = null
    private val running = AtomicBoolean(false)

    @Volatile var packetsSent: Long = 0
        private set
    @Volatile var packetsDropped: Long = 0
        private set
    @Volatile var lastError: String? = null
        private set

    /** Update the destination. Safe to call at any time, including while enabled. */
    @Synchronized
    fun configure(host: String, port: Int) {
        this.host = host.ifBlank { DEFAULT_HOST }
        this.port = if (port in 1..65535) port else DEFAULT_PORT
    }

    val isEnabled: Boolean get() = enabled.get()

    fun setEnabled(on: Boolean) {
        if (on) start() else stop()
    }

    private fun start() {
        if (!enabled.compareAndSet(false, true)) return
        packetsSent = 0
        packetsDropped = 0
        lastError = null
        queue.clear()
        running.set(true)
        senderThread = thread(name = "ExternalDecoderStream-sender", isDaemon = true) {
            runSenderLoop()
        }
        Log.i(TAG, "Started -> udp://$host:$port (PCM16LE mono)")
    }

    private fun stop() {
        if (!enabled.compareAndSet(true, false)) return
        running.set(false)
        // Wake the sender thread if it's blocked on queue.take().
        queue.offer(ShortArray(0))
        senderThread?.join(500)
        senderThread = null
        Log.i(TAG, "Stopped (sent=$packetsSent dropped=$packetsDropped)")
    }

    /**
     * Enqueue a block of [-1, 1] float samples (the same representation
     * DigitalVoiceDecoder.feed() takes) for transmission as PCM16LE. No-op
     * when disabled. Never blocks the caller (the DSP thread) -- drops the
     * oldest queued block instead of applying backpressure.
     */
    fun send(samples: FloatArray) {
        if (!enabled.get() || samples.isEmpty()) return
        // Convert to int16 here (same [-1,1] -> PCM16 scaling DigitalVoiceDecoder
        // uses) and copy: the caller's float buffer may be reused/mutated on
        // the next block.
        val pcm = ShortArray(samples.size)
        for (i in samples.indices) {
            pcm[i] = (samples[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
        if (!queue.offer(pcm)) {
            queue.poll() // drop oldest
            packetsDropped++
            queue.offer(pcm)
        }
    }

    fun shutdown() {
        stop()
    }

    // ── Sender thread ───────────────────────────────────────────────────────

    private fun runSenderLoop() {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
        } catch (e: Exception) {
            lastError = e.message
            Log.e(TAG, "Failed to open UDP socket", e)
            running.set(false)
            enabled.set(false)
            return
        }

        var scratch = ByteArray(4096)

        while (running.get()) {
            val block = try {
                queue.take()
            } catch (e: InterruptedException) {
                break
            }
            if (block.isEmpty()) continue // wake-up sentinel from stop()

            val needed = block.size * 2
            if (scratch.size < needed) scratch = ByteArray(needed)

            // Signed PCM16LE, mono, headerless -- exactly the dsd-neo/dsd-fme
            // "-i udp" wire format.
            var o = 0
            for (s in block) {
                scratch[o]     = (s.toInt() and 0xFF).toByte()
                scratch[o + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
                o += 2
            }

            try {
                val addr = InetAddress.getByName(host)
                val packet = DatagramPacket(scratch, needed, addr, port)
                socket.send(packet)
                packetsSent++
            } catch (e: Exception) {
                // Typical causes: DNS failure for a bad hostname, or (on some
                // devices) an ICMP port-unreachable surfaced back as an
                // exception when nothing is listening yet. Either way, don't
                // spam the log or crash the sender thread -- just drop this
                // block and keep going; the next enabled listener will start
                // receiving audio immediately once it comes up.
                lastError = e.message
                packetsDropped++
            }
        }

        socket.close()
    }
}
