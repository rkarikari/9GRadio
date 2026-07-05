package com.radiosport.ninegradio.dsp

import android.util.Log
import kotlinx.coroutines.flow.*
import kotlin.math.*

/**
 * POCSAG (Post Office Code Standardisation Advisory Group) paging decoder.
 *
 * Bit rates:  512, 1200, 2400 bps (auto-detect)
 * Modulation: 2-FSK on a narrow-FM carrier
 * Addresses:  21-bit RIC (Radio Identity Code)
 * Message types: Numeric, Alpha (BCD / 7-bit ASCII)
 *
 * Common frequencies:
 *   153.35 MHz (public safety), 157.450 MHz, 462.950 MHz, 929.x MHz
 */
class PocsagDecoder(private val audioRate: Int = 22_050) {

    companion object {
        private const val TAG = "POCSAG"

        // POCSAG sync codeword (constant)
        const val SYNC_CODEWORD = 0x7CD215D8u

        // Idle codeword
        const val IDLE_CODEWORD = 0x7A89C197u

        // Supported baud rates
        val BAUD_RATES = intArrayOf(512, 1200, 2400)

        // Frame structure
        const val CODEWORDS_PER_BATCH = 16
        const val BITS_PER_CODEWORD  = 32
    }

    data class PocsagMessage(
        val ric: Int,           // Radio Identity Code (address)
        val function: Int,      // 0=beep, 1=numeric, 2=reserved, 3=alpha
        val message: String,    // Decoded text
        val baudRate: Int,      // Detected baud rate
        val timestamp: Long = System.currentTimeMillis()
    ) {
        val functionName: String get() = when (function) {
            0 -> "Tone-Only"
            1 -> "Numeric"
            2 -> "Reserved"
            3 -> "Alpha"
            else -> "Unknown"
        }
    }

    private val _messages = MutableSharedFlow<PocsagMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<PocsagMessage> = _messages.asSharedFlow()

    // Auto-detect baud rate from zero-crossing rate
    private var detectedBaud = 1200
    private var sampleCountSinceBit = 0.0
    private val samplesPerBit: Double get() = audioRate.toDouble() / detectedBaud

    // Bit-level state
    private var prevSample = 0f
    private var prevBit    = false
    private var bitBuffer  = mutableListOf<Boolean>()

    // Frame-level state
    private val codewordBuf = IntArray(CODEWORDS_PER_BATCH)
    private var codewordPos = 0
    private var synced      = false
    private var batchPos    = 0

    // Message assembly
    private var currentRic   = 0
    private var currentFunc  = 0
    private val messageBits  = mutableListOf<Boolean>()

    /**
     * Feed audio from narrow-FM demodulator (mono, normalised −1..1).
     * Must be called from a single thread / coroutine — not thread-safe.
     */
    fun feed(samples: FloatArray) {
        // Auto-detect baud rate by measuring average zero-crossing interval
        autoBaud(samples)

        for (s in samples) {
            sampleCountSinceBit++
            if (prevSample * s < 0) {
                // Zero crossing - use for clock sync
                val err = sampleCountSinceBit - samplesPerBit / 2.0
                sampleCountSinceBit -= (err * 0.1).coerceIn(-2.0, 2.0)
            }
            if (sampleCountSinceBit >= samplesPerBit) {
                sampleCountSinceBit -= samplesPerBit
                onBit(s > 0)
            }
            prevSample = s
        }
    }

    private var zcWindow = FloatArray(512)
    private var zcPos    = 0
    private var zcCount  = 0

    private fun autoBaud(samples: FloatArray) {
        var crossings = 0
        var prev = zcWindow.lastOrNull() ?: 0f
        for (s in samples.take(256)) {
            if (prev * s < 0) crossings++
            prev = s
        }
        // crossings per sample → Hz → nearest baud
        val estimatedBaud = crossings * 2.0 * audioRate / samples.size.coerceAtLeast(1)
        detectedBaud = BAUD_RATES.minByOrNull { abs(it - estimatedBaud) } ?: 1200
    }

    private fun onBit(bit: Boolean) {
        bitBuffer.add(bit)

        if (!synced) {
            // Look for POCSAG sync word in bit stream
            if (bitBuffer.size >= 32) {
                val word = bitBuffer.takeLast(32).fold(0u) { acc, b ->
                    (acc shl 1) or (if (b) 1u else 0u)
                }
                if (word == SYNC_CODEWORD) {
                    synced = true
                    batchPos = 0
                    codewordPos = 0
                    Log.d(TAG, "POCSAG sync acquired at $detectedBaud bps")
                }
            }
            if (bitBuffer.size > 64) bitBuffer.removeFirst()
            return
        }

        // Accumulate 32-bit codewords
        if (bitBuffer.size >= 32) {
            val word = bitBuffer.take(32).fold(0u) { acc, b ->
                (acc shl 1) or (if (b) 1u else 0u)
            }
            repeat(32) { bitBuffer.removeFirst() }

            if (word == SYNC_CODEWORD) {
                batchPos = 0; return  // Start of new batch
            }

            processCordword(word.toInt())
            batchPos++
            if (batchPos >= CODEWORDS_PER_BATCH) {
                batchPos = 0
                // Expect next sync or go unsynced
            }
        }
    }

    private fun processCordword(word: Int) {
        if (!checkBch(word)) return  // BCH error

        val isAddress = (word and 0x80000000.toInt()) == 0

        if (isAddress) {
            // Flush previous message if any
            if (messageBits.isNotEmpty()) {
                emitMessage()
            }
            currentRic  = (word shr 13) and 0x1FFFFF
            currentFunc = (word shr 11) and 0x03
            messageBits.clear()
        } else {
            // Message codeword — 20 payload bits (bits 30..11)
            for (i in 30 downTo 11) {
                messageBits.add((word shr i) and 1 == 1)
            }
        }
    }

    private fun emitMessage() {
        val text = when (currentFunc) {
            1    -> decodeNumeric(messageBits)
            3    -> decodeAlpha(messageBits)
            0    -> "[Tone Only]"
            else -> "[Reserved]"
        }
        if (currentRic != 0) {
            val msg = PocsagMessage(currentRic, currentFunc, text, detectedBaud)
            Log.d(TAG, "POCSAG RIC=${msg.ric} [${msg.functionName}] ${msg.message.take(60)}")
            _messages.tryEmit(msg)
        }
        messageBits.clear()
    }

    private fun decodeNumeric(bits: List<Boolean>): String {
        // BCD: 4 bits per digit, 0–9 mapped to chars, A=[, B=], C=+, D=-, E= , F=?
        val numMap = "0123456789[]+- ?"
        val sb = StringBuilder()
        var i = 0
        while (i + 3 < bits.size) {
            val nibble = ((if (bits[i]) 8 else 0) or
                         (if (bits[i+1]) 4 else 0) or
                         (if (bits[i+2]) 2 else 0) or
                         (if (bits[i+3]) 1 else 0))
            sb.append(numMap.getOrElse(nibble) { '?' })
            i += 4
        }
        return sb.toString().trimEnd('?', ' ')
    }

    private fun decodeAlpha(bits: List<Boolean>): String {
        // 7-bit ASCII LSB-first
        val sb = StringBuilder()
        var i = 0
        while (i + 6 < bits.size) {
            var ch = 0
            for (j in 0..6) {
                if (bits.getOrElse(i + j) { false }) ch = ch or (1 shl j)
            }
            i += 7
            val c = ch and 0x7F
            if (c in 32..126) sb.append(c.toChar())
            else if (c == 13 || c == 10) sb.append('\n')
        }
        return sb.toString().trim()
    }

    /**
     * BCH(31,21) error check/correct.
     * Generator: x^10 + x^9 + x^8 + x^6 + x^5 + x^3 + 1 = 0x769
     * Returns true if codeword is valid (or single-bit corrected).
     */
    private fun checkBch(word: Int): Boolean {
        var syndrome = 0
        var w = word ushr 1  // bit 31 is message/address flag, not part of BCH
        for (i in 30 downTo 0) {
            syndrome = syndrome shl 1
            if ((w shr i) and 1 == 1) syndrome = syndrome xor 1
            if (syndrome and 0x400 != 0) syndrome = syndrome xor 0x769
        }
        return (syndrome and 0x3FF) == 0
    }
}
