package com.radiosport.ninegradio.audio

import android.media.*
import android.util.Log
import com.radiosport.ninegradio.debug.DebugBus
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

class AudioEngine(private val sampleRate: Int = 48_000) {

    companion object {
        private const val TAG = "AudioEngine"
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_FLOAT

        private fun playStateName(state: Int) = when (state) {
            AudioTrack.PLAYSTATE_PLAYING -> "PLAYING"
            AudioTrack.PLAYSTATE_PAUSED  -> "PAUSED"
            AudioTrack.PLAYSTATE_STOPPED -> "STOPPED"
            else                         -> "UNKNOWN($state)"
        }
        private fun initStateName(state: Int) = when (state) {
            AudioTrack.STATE_INITIALIZED      -> "STATE_INITIALIZED"
            AudioTrack.STATE_UNINITIALIZED    -> "STATE_UNINITIALIZED"
            else                              -> "STATE_UNKNOWN($state)"
        }
    }

    private var audioTrack: AudioTrack? = null
    private var pcmRecorder: PcmRecorder? = null
    private val writeFails = AtomicInteger(0)

    // ─── Thread-safety ──────────────────────────────────────────────────────
    // start()/stop() are called from the UI/service thread (engine lifecycle,
    // setAudioSinkRate() swaps), while write() is called from the DSP coroutine
    // running on Dispatchers.Default. Without synchronization, stop() can call
    // audioTrack.release() while write() is mid-call inside
    // AudioTrack.native_write_float() on the other thread, producing:
    //   java.lang.IllegalStateException: Unable to retrieve AudioTrack pointer for write()
    // [trackLock] makes start()/stop()/write() mutually exclusive so the
    // native object is never released while a write() holds a reference to it.
    private val trackLock = Any()

    val isRecording: Boolean get() = pcmRecorder?.isRecording == true

    fun start() {
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, CHANNEL_CONFIG, AUDIO_FORMAT)
        // Buffer sizing rationale:
        //
        // The DSP pipeline delivers audio in blocks driven by the USB IQ transfer
        // cadence (~4 ms per 16,384-byte DSP_CHUNK_SIZE block at 2 MS/s).
        // The AudioTrack buffer must hold AT LEAST 2× one DSP block so that while
        // the DSP is computing the next block the track can continue draining the
        // previous one without underrunning.
        //
        // At low sink rates (e.g. 16 kHz) the 150 ms formula previously used gave
        // only 9600 B = 2400 float samples ≈ 150 ms — barely half a DSP block.
        // Any jitter in the USB transfer (XFER_MAX can spike to 274 ms even with
        // JITTER reported as 2.9 ms) caused the AudioTrack to underrun and produce
        // the audible "skip" with no error in the debug panel (because AudioTrack
        // write() returned OK — the samples were accepted — but the ring buffer had
        // already emptied before they arrived).
        //
        // Fix: use a 500 ms floor (in bytes at ENCODING_PCM_FLOAT = 4 B/sample).
        // 500 ms comfortably spans any realistic IQ block + scheduler jitter at every
        // supported sink rate, while keeping latency well below the 1–2 s threshold
        // where SDR radio feels sluggish.
        //
        // The previous 150 ms comment claimed "avoids 500 ms lag" — that lag came
        // from WRITE_NON_BLOCKING + a large pre-fill, not from the buffer size
        // itself.  With WRITE_BLOCKING there is no pre-fill and no lag: the DSP
        // simply blocks while the buffer drains, perfectly rate-limiting to real time.
        val targetMs   = 500                                  // ms headroom
        val targetBytes = sampleRate * targetMs / 1000 * 4   // ENCODING_PCM_FLOAT = 4 B/sample
        val bufSize = minBuf.coerceAtLeast(targetBytes)

        // PERFORMANCE_MODE_LOW_LATENCY is incompatible with USAGE_MEDIA on many
        // Android devices: the Builder silently falls back to an uninitialized
        // state, making every write() a silent no-op.
        // SDR radio audio has no sub-20ms latency requirement, so use NONE.
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(CHANNEL_CONFIG)
                    .setEncoding(AUDIO_FORMAT)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
            .build()

        val state = track.state
        DebugBus.setExtra(DebugBus.STAGE_AUDIO_TRACK, DebugBus.EXTRA_AT_BUF_SIZE,
            "${bufSize}B (${bufSize / 4 * 1000 / sampleRate}ms)")

        if (state != AudioTrack.STATE_INITIALIZED) {
            val reason = "AudioTrack init FAILED — ${initStateName(state)}"
            Log.e(TAG, reason)
            DebugBus.setExtra(DebugBus.STAGE_AUDIO_TRACK, DebugBus.EXTRA_AT_INIT,
                "FAIL (${initStateName(state)})")
            DebugBus.setExtra(DebugBus.STAGE_AUDIO_TRACK, DebugBus.EXTRA_AT_PLAY, "—")
            DebugBus.setError(DebugBus.STAGE_AUDIO_TRACK, reason)
            track.release()
            return
        }

        DebugBus.setExtra(DebugBus.STAGE_AUDIO_TRACK, DebugBus.EXTRA_AT_INIT,
            initStateName(state))

        track.play()

        // Register a no-op routing-changed listener so the framework does NOT
        // attempt to call listAudioPorts() via AudioTrack.broadcastRoutingChange()
        // on a binder thread while the app's audio port cache is being rebuilt.
        // Without this, a Bluetooth/headphone plug event triggers a SIGSEGV in
        // audiopolicy-aidl-cpp's Parcel::readData path (null pointer dereference
        // inside extent_recycle / je_extents_alloc) because the system tries to
        // allocate memory for the updated port list during a routing event.
        // Adding a listener causes the framework to post the notification through
        // the registered handler instead, which is safe to receive and ignore.
        track.addOnRoutingChangedListener(
            AudioRouting.OnRoutingChangedListener { /* no-op: routing changes are benign for SDR audio */ },
            android.os.Handler(android.os.Looper.getMainLooper())
        )

        // Verify play actually started
        val playState = track.playState
        DebugBus.setExtra(DebugBus.STAGE_AUDIO_TRACK, DebugBus.EXTRA_AT_PLAY,
            playStateName(playState))
        if (playState != AudioTrack.PLAYSTATE_PLAYING) {
            val msg = "play() called but state is ${playStateName(playState)}"
            Log.w(TAG, msg)
            DebugBus.setExtra(DebugBus.STAGE_AUDIO_TRACK, DebugBus.EXTRA_AT_PLAY,
                "⚠ ${playStateName(playState)}")
        }

        writeFails.set(0)
        DebugBus.setExtra(DebugBus.STAGE_AUDIO_TRACK, DebugBus.EXTRA_AT_WRITE_FAIL, "0")
        DebugBus.setExtra(DebugBus.STAGE_AUDIO_TRACK, DebugBus.EXTRA_AT_LAST_WRITE, "—")

        synchronized(trackLock) {
            audioTrack = track
        }
        Log.i(TAG, "AudioTrack started: $sampleRate Hz, buf=${bufSize}B (${bufSize / 4 * 1000 / sampleRate}ms)")
    }

    fun stop() {
        // Synchronized with write(): ensures no write() call is mid-flight
        // inside AudioTrack.native_write_float() on the DSP coroutine when we
        // call stop()/release() here, which would otherwise crash with
        // "IllegalStateException: Unable to retrieve AudioTrack pointer".
        val track = synchronized(trackLock) {
            val t = audioTrack
            audioTrack = null
            t
        }
        try {
            track?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack.stop() threw", e)
        }
        try {
            // Remove all routing changed listeners before release so the framework
            // does not deliver a stale routing-change callback to a released track.
            track?.removeOnRoutingChangedListener(
                AudioRouting.OnRoutingChangedListener { }
            )
            track?.release()
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack.release() threw", e)
        }
        DebugBus.setExtra(DebugBus.STAGE_AUDIO_TRACK, DebugBus.EXTRA_AT_PLAY, "STOPPED")
        DebugBus.setExtra(DebugBus.STAGE_AUDIO_TRACK, DebugBus.EXTRA_AT_LAST_WRITE, "—")
        pcmRecorder?.stop()
    }

    fun write(samples: FloatArray) {
        // Hold trackLock for the duration of the write so a concurrent stop()
        // cannot release the native AudioTrack out from under this call.
        // The lock is held across the (possibly blocking) native write —
        // stop() is a comparatively rare, short operation, so this does not
        // introduce meaningful contention on the hot DSP path.
        synchronized(trackLock) {
            val track = audioTrack ?: return

            // ── Live play-state check: catches paused/stopped tracks before writing ──
            val currentPlayState = try {
                track.playState
            } catch (e: IllegalStateException) {
                Log.w(TAG, "AudioTrack.playState threw (track released?)", e)
                return
            }
            if (currentPlayState != AudioTrack.PLAYSTATE_PLAYING) {
                DebugBus.setExtra(DebugBus.STAGE_AUDIO_TRACK, DebugBus.EXTRA_AT_PLAY,
                    "⚠ ${playStateName(currentPlayState)} — not playing!")
                // Attempt to resume if paused (e.g. transient audio focus loss then regained)
                if (currentPlayState == AudioTrack.PLAYSTATE_PAUSED) {
                    try { track.play() } catch (_: Exception) { }
                }
            } else {
                // Update play state periodically so stale "⚠" entries are cleared
                DebugBus.setExtra(DebugBus.STAGE_AUDIO_TRACK, DebugBus.EXTRA_AT_PLAY,
                    playStateName(currentPlayState))
            }

            // WRITE_BLOCKING: the write call suspends the calling coroutine (Dispatchers.Default)
            // until the AudioTrack buffer has room for the samples.  This naturally rate-limits
            // the DSP pipeline to real-time and eliminates silent sample drops that
            // WRITE_NON_BLOCKING causes when the 500 ms buffer fills ahead of playback.
            // For WFM at 48 kHz the blocking duration is at most ~64 ms per IQ block — harmless.
            var written = 0
            while (written < samples.size) {
                val n = try {
                    track.write(samples, written, samples.size - written, AudioTrack.WRITE_BLOCKING)
                } catch (e: IllegalStateException) {
                    // Native AudioTrack pointer is gone — the track was released
                    // concurrently (e.g. stop() raced this write()). Treat as a
                    // benign stop rather than crashing the DSP coroutine.
                    Log.w(TAG, "AudioTrack.write() threw IllegalStateException (track released)", e)
                    DebugBus.setExtra(DebugBus.STAGE_AUDIO_TRACK, DebugBus.EXTRA_AT_LAST_WRITE,
                        "FAIL: released mid-write")
                    return
                }
                if (n <= 0) {
                    // n == 0 → track released; n < 0 → AudioTrack error code
                    val failStr = when (n) {
                        AudioTrack.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
                        AudioTrack.ERROR_BAD_VALUE         -> "ERROR_BAD_VALUE"
                        AudioTrack.ERROR_DEAD_OBJECT       -> "ERROR_DEAD_OBJECT"
                        AudioTrack.ERROR                   -> "ERROR"
                        0                                  -> "0 (track released?)"
                        else                               -> "UNKNOWN($n)"
                    }
                    val totalFails = writeFails.incrementAndGet()
                    DebugBus.setExtra(DebugBus.STAGE_AUDIO_TRACK, DebugBus.EXTRA_AT_WRITE_FAIL,
                        "$totalFails")
                    DebugBus.setExtra(DebugBus.STAGE_AUDIO_TRACK, DebugBus.EXTRA_AT_LAST_WRITE,
                        "FAIL: $failStr")
                    if (n == AudioTrack.ERROR_DEAD_OBJECT) {
                        DebugBus.setError(DebugBus.STAGE_AUDIO_TRACK,
                            "write() ERROR_DEAD_OBJECT — AudioTrack dead (fails=$totalFails)")
                    }
                    Log.w(TAG, "AudioTrack.write() returned $n ($failStr), fails=$totalFails")
                    break
                }
                written += n
            }

            if (written > 0) {
                DebugBus.setExtra(DebugBus.STAGE_AUDIO_TRACK, DebugBus.EXTRA_AT_LAST_WRITE,
                    "OK ($written samples)")
            }
        }
        pcmRecorder?.writeSamples(samples)
    }

    fun setVolume(vol: Float) {
        synchronized(trackLock) {
            audioTrack?.setVolume(vol.coerceIn(0f, AudioTrack.getMaxVolume()))
        }
    }

    fun startRecording(filePath: String) {
        pcmRecorder?.stop()
        pcmRecorder = PcmRecorder(filePath, sampleRate).also { it.start() }
    }

    fun stopRecording() {
        pcmRecorder?.stop()
        pcmRecorder = null
    }
}

/**
 * Records demodulated audio to a WAV file.
 */
class PcmRecorder(private val filePath: String, private val sampleRate: Int) {
    var isRecording = false
        private set

    private var outputStream: DataOutputStream? = null
    private var bytesWritten = 0

    fun start() {
        try {
            val file = File(filePath)
            file.parentFile?.mkdirs()
            outputStream = DataOutputStream(BufferedOutputStream(FileOutputStream(file)))
            // Write placeholder WAV header (will be updated on stop)
            writeWavHeader(outputStream!!, 0)
            isRecording = true
            Log.i("PcmRecorder", "Recording to $filePath")
        } catch (e: IOException) {
            Log.e("PcmRecorder", "Failed to start recording", e)
        }
    }

    fun writeSamples(samples: FloatArray) {
        if (!isRecording) return
        val stream = outputStream ?: return
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            buf.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }
        stream.write(buf.array())
        bytesWritten += buf.array().size
    }

    fun stop() {
        if (!isRecording) return
        isRecording = false
        try {
            outputStream?.flush()
            outputStream?.close()
            // Update WAV header with actual size
            val raf = RandomAccessFile(filePath, "rw")
            raf.seek(4)
            raf.write(intToLe(36 + bytesWritten))
            raf.seek(40)
            raf.write(intToLe(bytesWritten))
            raf.close()
            Log.i("PcmRecorder", "Saved $bytesWritten bytes to $filePath")
        } catch (e: IOException) {
            Log.e("PcmRecorder", "Error finalizing WAV", e)
        }
    }

    private fun writeWavHeader(stream: DataOutputStream, dataLen: Int) {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        stream.writeBytes("RIFF")
        stream.write(intToLe(36 + dataLen))
        stream.writeBytes("WAVE")
        stream.writeBytes("fmt ")
        stream.write(intToLe(16))
        stream.write(shortToLe(1))        // PCM
        stream.write(shortToLe(channels.toShort()))
        stream.write(intToLe(sampleRate))
        stream.write(intToLe(byteRate))
        stream.write(shortToLe((channels * bitsPerSample / 8).toShort()))
        stream.write(shortToLe(bitsPerSample.toShort()))
        stream.writeBytes("data")
        stream.write(intToLe(dataLen))
    }

    private fun intToLe(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte(),
        (v shr 16 and 0xFF).toByte(), (v shr 24 and 0xFF).toByte()
    )

    private fun shortToLe(v: Short): ByteArray = byteArrayOf(
        (v.toInt() and 0xFF).toByte(), (v.toInt() shr 8 and 0xFF).toByte()
    )
}
