package com.radiosport.ninegradio.dsp

import android.util.Log
import kotlinx.coroutines.flow.*
import kotlin.math.*

/**
 * ACARS (Aircraft Communications Addressing and Reporting System) decoder.
 *
 * Reference: https://github.com/f00b4r0/acarsdec (GPLv2, Thierry Leconte / Thibaut VARENE)
 *
 * ── Signal path ──────────────────────────────────────────────────────────────
 *  1. AM audio at [sampleRate], mono, normalised −1…1
 *  2. Two biquad IIR BPFs extract mark (2400 Hz) and space (1200 Hz) envelope power
 *  3. Bit-clock synchroniser: re-arms to mid-eye on every mark↔space transition
 *  4. NRZI decode: no transition → 1, transition → 0
 *  5. Bytes accumulated LSb-first; MSb = odd-parity bit (NOT stripped during accumulation)
 *  6. State machine mirrors acarsdec acars.c:
 *       PREKEY → SYNC → SOH1 → TXT → CRC1 → CRC2
 *  7. CRC-16/KERMIT (poly 0x1021 reflected = 0x8408, init 0x0000, no output XOR)
 *     computed over raw bytes including parity bit; residue check against 0x0000
 *  8. Parity stripped (AND 0x7F) for field extraction only
 *
 * ── Frame preamble (raw on-wire bytes, LSb-first, parity in MSb) ─────────────
 *   ≥ 12 × 0xFF   mark preamble (all-ones, no transitions in NRZI)
 *   0xAB           '+' (0x2B) with odd-parity MSb set
 *   0x2A           '*' (parity already odd, MSb = 0)
 *   0x16           SYN (parity already odd, MSb = 0)
 *   0x16           SYN
 *   0x01           SOH (parity already odd, MSb = 0)
 *
 * ── Frame body (raw bytes after SOH, parity in each MSb) ────────────────────
 *   [0]     mode character
 *   [1..7]  aircraft registration, 7 bytes (dot/space padded)
 *   [8]     ACK/NAK  (0x06 = ACK | 0x15 = NAK | printable uplink char)
 *   [9..10] 2-char message label
 *   [11]    block ID
 *   [12]    STX = 0x02
 *   [13+]   message text
 *   [last]  ETX = 0x83 (0x03|0x80) or ETB = 0x97 (0x17|0x80)
 *   then:   CRC byte 0, CRC byte 1  (captured separately; both included in residue check)
 *   then:   DEL = 0x7F (optional, ignored)
 */
class AcarsDecoder(private val sampleRate: Int = 48_000) {

    companion object {
        private const val TAG  = "AcarsDecoder"
        const val BAUD_RATE    = 2400.0
        const val MARK_HZ      = 2400.0
        const val SPACE_HZ     = 1200.0

        // Raw on-wire byte values (7-bit ASCII + odd-parity MSb).
        // parity(0x16) = 3 ones → odd → MSb = 0 → raw = 0x16
        private val SYN_RAW : Byte = 0x16
        // parity(0x01) = 1 one  → odd → MSb = 0 → raw = 0x01
        private val SOH_RAW : Byte = 0x01
        // parity(0x02) = 1 one  → odd → MSb = 0 → raw = 0x02
        private val STX_RAW : Byte = 0x02
        // parity(0x03) = 2 ones → even → MSb = 1 → raw = 0x83
        private val ETX_RAW : Byte = 0x83.toByte()
        // parity(0x17) = 4 ones → even → MSb = 1 → raw = 0x97
        private val ETB_RAW : Byte = 0x97.toByte()
        // parity(0x2B) = 4 ones → even → MSb = 1 → raw = 0xAB  ('+')
        private val PLUS_RAW: Byte = 0xAB.toByte()
        // parity(0x2A) = 3 ones → odd  → MSb = 0 → raw = 0x2A  ('*')
        private val STAR_RAW: Byte = 0x2A

        /** Minimum consecutive 0xFF bytes before accepting a sync sequence. */
        private const val PREKEY_MIN = 12

        /**
         * CRC-16/KERMIT lookup table.
         * Poly = 0x1021 reflected = 0x8408, init = 0x0000, no output XOR.
         * Identical to crc_ccitt_table in acarsdec/syndrom.h.
         * Update: crc = (crc ushr 8) xor TABLE[(crc xor byte) and 0xFF]
         */
        private val CRC_TABLE = intArrayOf(
            0x0000, 0x1189, 0x2312, 0x329b, 0x4624, 0x57ad, 0x6536, 0x74bf,
            0x8c48, 0x9dc1, 0xaf5a, 0xbed3, 0xca6c, 0xdbe5, 0xe97e, 0xf8f7,
            0x1081, 0x0108, 0x3393, 0x221a, 0x56a5, 0x472c, 0x75b7, 0x643e,
            0x9cc9, 0x8d40, 0xbfdb, 0xae52, 0xdaed, 0xcb64, 0xf9ff, 0xe876,
            0x2102, 0x308b, 0x0210, 0x1399, 0x6726, 0x76af, 0x4434, 0x55bd,
            0xad4a, 0xbcc3, 0x8e58, 0x9fd1, 0xeb6e, 0xfae7, 0xc87c, 0xd9f5,
            0x3183, 0x200a, 0x1291, 0x0318, 0x77a7, 0x662e, 0x54b5, 0x453c,
            0xbdcb, 0xac42, 0x9ed9, 0x8f50, 0xfbef, 0xea66, 0xd8fd, 0xc974,
            0x4204, 0x538d, 0x6116, 0x709f, 0x0420, 0x15a9, 0x2732, 0x36bb,
            0xce4c, 0xdfc5, 0xed5e, 0xfcd7, 0x8868, 0x99e1, 0xab7a, 0xbaf3,
            0x5285, 0x430c, 0x7197, 0x601e, 0x14a1, 0x0528, 0x37b3, 0x263a,
            0xdecd, 0xcf44, 0xfddf, 0xec56, 0x98e9, 0x8960, 0xbbfb, 0xaa72,
            0x6306, 0x728f, 0x4014, 0x519d, 0x2522, 0x34ab, 0x0630, 0x17b9,
            0xef4e, 0xfec7, 0xcc5c, 0xddd5, 0xa96a, 0xb8e3, 0x8a78, 0x9bf1,
            0x7387, 0x620e, 0x5095, 0x411c, 0x35a3, 0x242a, 0x16b1, 0x0738,
            0xffcf, 0xee46, 0xdcdd, 0xcd54, 0xb9eb, 0xa862, 0x9af9, 0x8b70,
            0x8408, 0x9581, 0xa71a, 0xb693, 0xc22c, 0xd3a5, 0xe13e, 0xf0b7,
            0x0840, 0x19c9, 0x2b52, 0x3adb, 0x4e64, 0x5fed, 0x6d76, 0x7cff,
            0x9489, 0x8500, 0xb79b, 0xa612, 0xd2ad, 0xc324, 0xf1bf, 0xe036,
            0x18c1, 0x0948, 0x3bd3, 0x2a5a, 0x5ee5, 0x4f6c, 0x7df7, 0x6c7e,
            0xa50a, 0xb483, 0x8618, 0x9791, 0xe32e, 0xf2a7, 0xc03c, 0xd1b5,
            0x2942, 0x38cb, 0x0a50, 0x1bd9, 0x6f66, 0x7eef, 0x4c74, 0x5dfd,
            0xb58b, 0xa402, 0x9699, 0x8710, 0xf3af, 0xe226, 0xd0bd, 0xc134,
            0x39c3, 0x284a, 0x1ad1, 0x0b58, 0x7fe7, 0x6e6e, 0x5cf5, 0x4d7c,
            0xc60c, 0xd785, 0xe51e, 0xf497, 0x8028, 0x91a1, 0xa33a, 0xb2b3,
            0x4a44, 0x5bcd, 0x6956, 0x78df, 0x0c60, 0x1de9, 0x2f72, 0x3efb,
            0xd68d, 0xc704, 0xf59f, 0xe416, 0x90a9, 0x8120, 0xb3bb, 0xa232,
            0x5ac5, 0x4b4c, 0x79d7, 0x685e, 0x1ce1, 0x0d68, 0x3ff3, 0x2e7a,
            0xe70e, 0xf687, 0xc41c, 0xd595, 0xa12a, 0xb0a3, 0x8238, 0x93b1,
            0x6b46, 0x7acf, 0x4854, 0x59dd, 0x2d62, 0x3ceb, 0x0e70, 0x1ff9,
            0xf78f, 0xe606, 0xd49d, 0xc514, 0xb1ab, 0xa022, 0x92b9, 0x8330,
            0x7bc7, 0x6a4e, 0x58d5, 0x495c, 0x3de3, 0x2c6a, 0x1ef1, 0x0f78
        )

        /** Common ACARS label descriptions (ARINC 618). */
        val LABEL_DESCRIPTIONS = mapOf(
            "H1" to "OOOI / Gate departure & arrival",
            "Q0" to "Automatic position report",
            "QM" to "Meteorological report",
            "SA" to "PDC (Pre-Departure Clearance)",
            "10" to "Weather report",
            "20" to "ATIS",
            "45" to "ATC clearance",
            "80" to "Oceanic clearance",
            "AA" to "Airplane acknowledgment",
            "B6" to "FMS loading",
            "5Z" to "ATC message",
            "BA" to "FANS-1/A CPDLC",
            "H2" to "Out/In times",
            "_d" to "ACARS test",
            "__" to "Free text"
        )
    }

    // ── Public message type ───────────────────────────────────────────────────

    data class AcarsMessage(
        val registration: String,    // Aircraft tail number, e.g. "N12345"
        val flightId: String,        // Flight ID extracted from text, e.g. "AA1234"
        val label: String,           // 2-char message label
        val blockId: Char,           // Block sequence number
        val text: String,            // Decoded message body (parity stripped, printable)
        val ack: Boolean,            // true = ACK, false = NAK or uplink
        val more: Boolean,           // true = more blocks follow this one
        val timestamp: Long = System.currentTimeMillis()
    )

    private val _messages = MutableSharedFlow<AcarsMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<AcarsMessage> = _messages.asSharedFlow()

    // ── AFSK demodulator ──────────────────────────────────────────────────────

    private val samplesPerBit = sampleRate / BAUD_RATE
    private val markBpf  = IirBpf(MARK_HZ,  sampleRate, q = 10.0)
    private val spaceBpf = IirBpf(SPACE_HZ, sampleRate, q = 10.0)
    private var samplesSinceBit = 0.0
    private var lastRawBit      = false

    // ── Bit accumulator ───────────────────────────────────────────────────────

    /** Raw bit count within the current byte (0..7). */
    private var bitCount    = 0
    /** Shift register accumulating bits LSb-first; bit 7 = parity when full. */
    private var currentByte = 0
    /** Previous AFSK symbol for NRZI differential decode. */
    private var prevBit     = false

    // ── Frame state machine ───────────────────────────────────────────────────

    private enum class State { PREKEY, SYNC, SOH1, TXT, CRC1, CRC2 }

    private var state      = State.PREKEY
    /** Consecutive 0xFF bytes seen in PREKEY state. */
    private var ffCount    = 0
    /** Position within the SYNC byte sequence: 1 = expecting '*', 2–3 = SYN. */
    private var syncCount  = 0
    /**
     * Raw frame bytes (parity MSb intact) accumulated from after SOH through
     * and including the terminating ETX/ETB byte.
     */
    private val frameBytes = mutableListOf<Byte>()
    /** First CRC byte as received (with parity). */
    private var crcByte0   = 0.toByte()
    /** Second CRC byte as received (with parity). */
    private var crcByte1   = 0.toByte()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Feed demodulated AM audio into the ACARS bit detector.
     * [samples] must be at [sampleRate], mono, normalised −1…1.
     */
    fun feed(samples: FloatArray) {
        val halfBit = samplesPerBit / 2.0
        for (s in samples) {
            val mPow = markBpf.process(s)
            val sPow = spaceBpf.process(s)
            val rawBit = mPow > sPow    // mark (2400 Hz) = true

            // Re-arm bit clock to mid-eye on every mark↔space transition.
            if (rawBit != lastRawBit) samplesSinceBit = halfBit
            lastRawBit = rawBit

            samplesSinceBit++
            if (samplesSinceBit >= samplesPerBit) {
                samplesSinceBit -= samplesPerBit
                onBit(rawBit)
            }
        }
    }

    // ── Internal bit pipeline ─────────────────────────────────────────────────

    private fun onBit(rawBit: Boolean) {
        // NRZI: no-transition → 1, transition → 0
        val dataBit = (rawBit == prevBit)
        prevBit = rawBit

        // Accumulate LSb-first; after 8 shifts bit-0 = first received bit,
        // bit-7 = last received bit (the odd-parity check bit).
        currentByte = (currentByte ushr 1) or (if (dataBit) 0x80 else 0)
        if (++bitCount < 8) return
        bitCount = 0

        val b = currentByte.toByte()
        currentByte = 0
        onByte(b)
    }

    private fun resetState() {
        state     = State.PREKEY
        ffCount   = 0
        syncCount = 0
        frameBytes.clear()
    }

    /**
     * State machine driven by fully assembled bytes (raw, parity in MSb).
     * Mirrors PREKEY/SYNC/SOH1/TXT/CRC1/CRC2 states from acarsdec/acars.c.
     */
    private fun onByte(b: Byte) {
        when (state) {

            // ── PREKEY ──────────────────────────────────────────────────────
            // Wait for ≥ PREKEY_MIN consecutive 0xFF bytes (all-mark preamble,
            // no NRZI transitions → all decoded bits are 1).
            // On seeing '+' with parity (0xAB), advance to SYNC.
            // Fallback: if '+' was missed but SYN arrives, enter SYNC mid-sequence.
            State.PREKEY -> {
                when (b) {
                    0xFF.toByte() -> { ffCount++; return }

                    PLUS_RAW -> if (ffCount >= PREKEY_MIN) {
                        // Consumed '+'; next expected byte is '*'
                        state = State.SYNC; syncCount = 1; ffCount = 0; return
                    }

                    SYN_RAW -> if (ffCount >= PREKEY_MIN) {
                        // Missed '+' and '*'; treat this as the first SYN
                        state = State.SYNC; syncCount = 2; ffCount = 0; return
                    }
                }
                ffCount = 0   // any other byte resets the preamble counter
            }

            // ── SYNC ─────────────────────────────────────────────────────────
            // Strict sequential check of the remaining sync bytes.
            // syncCount: 1 → expect '*', 2 → expect first SYN, 3 → expect second SYN.
            State.SYNC -> {
                val expected = when (syncCount) {
                    1    -> STAR_RAW
                    2, 3 -> SYN_RAW
                    else -> { resetState(); return }
                }
                if (b != expected) { resetState(); return }
                syncCount++
                if (syncCount == 4) state = State.SOH1
            }

            // ── SOH1 ─────────────────────────────────────────────────────────
            State.SOH1 -> {
                if (b != SOH_RAW) { resetState(); return }
                state = State.TXT
                frameBytes.clear()
            }

            // ── TXT ──────────────────────────────────────────────────────────
            // Accumulate raw bytes (parity intact) until ETX or ETB.
            // The terminator is included — CRC covers it.
            State.TXT -> {
                frameBytes.add(b)
                if (frameBytes.size > 260) { resetState(); return }
                if (b == ETX_RAW || b == ETB_RAW) state = State.CRC1
            }

            // ── CRC1 / CRC2 ──────────────────────────────────────────────────
            State.CRC1 -> { crcByte0 = b; state = State.CRC2 }

            State.CRC2 -> {
                crcByte1 = b
                tryDecode(frameBytes.toByteArray(), crcByte0, crcByte1)
                resetState()
            }
        }
    }

    // ── Frame validation and field extraction ─────────────────────────────────

    private fun tryDecode(raw: ByteArray, crc0: Byte, crc1: Byte) {
        try {
            // Minimum: mode(1)+addr(7)+ack(1)+label(2)+bid(1)+STX(1)+ETX(1) = 14 bytes
            if (raw.size < 14) return

            // raw[12] must be STX (0x02; parity already odd so MSb = 0)
            if (raw[12] != STX_RAW) return

            // ── CRC-16/KERMIT residue check (matches acarsdec/acars.c blk_thread) ──
            // Feed raw frame bytes (parity included) then both CRC bytes through the
            // algorithm. A valid frame yields residue = 0x0000.
            var crc = 0
            for (byte in raw) crc = crc16Update(crc, byte)
            crc = crc16Update(crc, crc0)
            crc = crc16Update(crc, crc1)
            if (crc != 0) {
                Log.v(TAG, "ACARS CRC fail residue=0x%04X len=%d".format(crc, raw.size))
                return
            }

            // ── Field extraction — strip parity MSb with AND 0x7F ─────────────

            // raw[1..7]: registration (7 bytes, dot/space padded)
            val regChars = CharArray(7) { i -> (raw[1 + i].toInt() and 0x7F).toChar() }
            val registration = String(regChars)
                .trim('.', ' ')
                .filter { it.isLetterOrDigit() || it == '-' }

            // raw[8]: ACK/NAK
            val ackFlag = (raw[8].toInt() and 0x7F) == 0x06  // 0x06 = ACK

            // raw[9..10]: 2-char label
            // Convention from acarsdec: DEL (stripped value 0x7F) in byte 2 → 'd'
            val lb0 = (raw[9].toInt()  and 0x7F).toByte()
            val lb1v = raw[10].toInt() and 0x7F
            val lb1 = (if (lb1v == 0x7F) 'd'.code else lb1v).toByte()
            val label = String(byteArrayOf(lb0, lb1), Charsets.ISO_8859_1)

            // raw[11]: block ID
            val blockId = (raw[11].toInt() and 0x7F).toChar()

            // raw[13..size-2]: message text (raw[size-1] is the ETX/ETB terminator)
            val etxIdx = raw.size - 1
            val msgText = if (etxIdx > 13) {
                val sb = StringBuilder(etxIdx - 13)
                for (i in 13 until etxIdx) {
                    val ch = raw[i].toInt() and 0x7F
                    if (ch >= 0x20 || ch == 0x0A || ch == 0x0D) sb.append(ch.toChar())
                }
                sb.toString().trim()
            } else ""

            // For downlink blocks (bid '0'-'9'): text layout per acarsdec outputmsg():
            //   bytes 0..3  = message number (4 chars)
            //   bytes 4..9  = flight ID     (6 chars)
            val isDownlink = blockId in '0'..'9'
            val flightId = when {
                isDownlink && msgText.length >= 10 ->
                    msgText.substring(4, 10).filter { it.isLetterOrDigit() }
                isDownlink && msgText.length >= 6  ->
                    msgText.take(6).filter { it.isLetterOrDigit() }
                else -> ""
            }

            // Block ID '/' = squitter (standalone, no further blocks)
            val more = blockId != '/'

            val msg = AcarsMessage(
                registration = registration,
                flightId     = flightId,
                label        = label,
                blockId      = blockId,
                text         = msgText,
                ack          = ackFlag,
                more         = more
            )
            Log.d(TAG, "ACARS ok  reg=%-8s lbl=%s bid=%c txt=%s"
                .format(msg.registration, msg.label, msg.blockId, msg.text.take(60)))
            _messages.tryEmit(msg)

        } catch (e: Exception) {
            Log.v(TAG, "ACARS decode exception: ${e.message}")
        }
    }

    // ── CRC helper ────────────────────────────────────────────────────────────

    /**
     * CRC-16/KERMIT single-byte update.
     * Matches update_crc16(crc, c) from acarsdec/syndrom.h:
     *   ((crc) >> 8) ^ crc_ccitt_table[((crc) ^ (c)) & 0xff]
     */
    private fun crc16Update(crc: Int, b: Byte): Int =
        (crc ushr 8) xor CRC_TABLE[(crc xor (b.toInt() and 0xFF)) and 0xFF]

    // ── IIR Bandpass Filter ───────────────────────────────────────────────────
    //
    // Biquad bandpass, Q = 10 (sharper than APRS Q=5; needed to resolve
    // 1200 Hz and 2400 Hz tones that are only one octave apart).
    //
    // Asymmetric envelope detector:
    //   Attack  τ ≈ 0.2 ms  (~10 samples at 48 kHz) — tracks tone onset quickly
    //   Decay   τ ≈ 1.0 ms  (~48 samples, ≈ 2.4 bit periods) — smooth without lag
    //
    private inner class IirBpf(centerHz: Double, sampleRate: Int, q: Double) {
        private val w0    = 2.0 * PI * centerHz / sampleRate
        private val alpha = sin(w0) / (2.0 * q)
        private val b0    =  alpha.toFloat()
        private val a0    = (1.0 + alpha).toFloat()
        private val a1    = (-2.0 * cos(w0)).toFloat()
        private val a2    = (1.0 - alpha).toFloat()
        private var x1    = 0f; private var x2 = 0f
        private var y1    = 0f; private var y2 = 0f
        private var env   = 0f

        // α = exp(−1 / (sampleRate · τ_seconds))
        private val attackCoeff = exp(-1.0 / (sampleRate * 0.0002)).toFloat() // τ ≈ 0.2 ms
        private val decayCoeff  = exp(-1.0 / (sampleRate * 0.001 )).toFloat() // τ ≈ 1.0 ms

        fun process(x: Float): Float {
            val y = (b0 * x - b0 * x2 - a1 * y1 - a2 * y2) / a0
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            val power = y * y
            val coeff = if (power > env) attackCoeff else decayCoeff
            env = coeff * env + (1f - coeff) * power
            return env
        }
    }
}
