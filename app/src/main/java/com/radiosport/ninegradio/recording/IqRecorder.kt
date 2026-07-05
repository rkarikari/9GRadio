package com.radiosport.ninegradio.recording

import android.util.Log
import java.io.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPOutputStream

/**
 * Records raw IQ data from the RTL-SDR to a file.
 * Supports .iq (raw uint8) and .iq.gz (gzip compressed) formats.
 */
class IqRecorder {

    companion object {
        private const val TAG = "IqRecorder"

        fun suggestedFilename(freqHz: Long, sampleRate: Int, ext: String = ".iq"): String {
            val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val freqMhz = freqHz / 1_000_000.0
            return "iq_${String.format("%.3f", freqMhz)}MHz_${sampleRate / 1000}kSps_$dateStr$ext"
        }
    }

    var isRecording = false
        private set

    var filePath: String = ""
        private set

    var bytesWritten = 0L
        private set

    var startTimeMs = 0L
        private set

    private var sampleRate = 0
    private var outputStream: OutputStream? = null
    private var fileOutputStream: FileOutputStream? = null

    enum class Format { RAW_UINT8, GZIP_UINT8, FLOAT32 }

    private var format = Format.RAW_UINT8

    /**
     * Start recording IQ data.
     * @param path Full file path to write to.
     * @param rate Sample rate (for metadata).
     * @param fmt Output format.
     */
    fun start(path: String, rate: Int, fmt: Format = Format.RAW_UINT8) {
        if (isRecording) stop()
        filePath = path
        sampleRate = rate
        format = fmt
        bytesWritten = 0

        try {
            val file = File(path)
            file.parentFile?.mkdirs()
            fileOutputStream = FileOutputStream(file)
            outputStream = when (fmt) {
                Format.GZIP_UINT8 -> GZIPOutputStream(fileOutputStream)
                else -> BufferedOutputStream(fileOutputStream, 65536)
            }
            isRecording = true
            startTimeMs = System.currentTimeMillis()
            Log.i(TAG, "IQ recording started: $path ($fmt, $rate S/s)")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start IQ recording", e)
        }
    }

    /**
     * Write a block of raw IQ bytes.
     */
    fun write(data: ByteArray) {
        if (!isRecording) return
        try {
            when (format) {
                Format.FLOAT32 -> {
                    // Convert uint8 to float32 before writing
                    val buf = ByteArray(data.size * 4)
                    for (i in data.indices) {
                        val f = ((data[i].toInt() and 0xFF) - 127.5f) / 128f
                        val bits = java.lang.Float.floatToRawIntBits(f)
                        buf[i * 4]     = (bits and 0xFF).toByte()
                        buf[i * 4 + 1] = (bits shr 8 and 0xFF).toByte()
                        buf[i * 4 + 2] = (bits shr 16 and 0xFF).toByte()
                        buf[i * 4 + 3] = (bits shr 24 and 0xFF).toByte()
                    }
                    outputStream?.write(buf)
                    bytesWritten += buf.size
                }
                else -> {
                    outputStream?.write(data)
                    bytesWritten += data.size
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "IQ write error", e)
            stop()
        }
    }

    fun stop() {
        if (!isRecording) return
        isRecording = false
        try {
            outputStream?.flush()
            outputStream?.close()
            val durationMs = System.currentTimeMillis() - startTimeMs
            Log.i(TAG, "IQ recording stopped: $bytesWritten bytes in ${durationMs}ms -> $filePath")
        } catch (e: IOException) {
            Log.e(TAG, "Error closing IQ file", e)
        }
        outputStream = null
        fileOutputStream = null
    }

    fun getDurationMs(): Long = if (isRecording) System.currentTimeMillis() - startTimeMs else 0L

    fun getFileSizeMb(): Float = bytesWritten / (1024f * 1024f)

    fun getEstimatedRemainingSeconds(maxFileSizeMb: Float): Long {
        if (!isRecording || bytesWritten == 0L) return Long.MAX_VALUE
        val elapsedMs = getDurationMs()
        val bytesPerMs = bytesWritten.toFloat() / elapsedMs
        val remainingBytes = (maxFileSizeMb * 1024 * 1024) - bytesWritten
        return (remainingBytes / bytesPerMs / 1000).toLong()
    }
}
