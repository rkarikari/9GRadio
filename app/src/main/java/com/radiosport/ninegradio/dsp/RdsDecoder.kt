package com.radiosport.ninegradio.dsp

import kotlin.math.*
import kotlinx.coroutines.flow.*

/**
 * RDS (Radio Data System / RBDS) decoder.
 *
 * Decodes the 57 kHz RDS subcarrier present on broadcast FM stations.
 * Extracts: PI code, PS name, RadioText (RT), PTY, TP/TA flags,
 *           Alternative Frequencies (AF) list.
 *
 * Feeds off the already-demodulated wideband FM audio at 240 kHz.
 */
class RdsDecoder(private val sampleRate: Int = 240_000) {

    companion object {
        const val RDS_FREQ      = 57_000.0   // Hz (3rd harmonic of 19 kHz pilot)
        const val RDS_BAUD_RATE =   1_187.5  // bps
        const val BLOCK_SIZE    =        26  // bits per RDS block

        val PTY_NAMES = listOf(
            "None","News","Current Affairs","Information","Sport","Education",
            "Drama","Culture","Science","Varied","Pop Music","Rock Music",
            "Easy Listening","Light Classical","Serious Classical","Other Music",
            "Weather","Finance","Children's Programmes","Social Affairs","Religion",
            "Phone In","Travel","Leisure","Jazz Music","Country Music",
            "National Music","Oldies Music","Folk Music","Documentary","Alarm Test","Alarm"
        )
    }

    data class RdsData(
        val piCode: Int        = 0,       // Programme Identification
        val psName: String     = "",      // Programme Service Name (8 chars)
        val radioText: String  = "",      // RadioText (64 chars)
        val pty: Int           = 0,       // Programme Type
        val tp: Boolean        = false,   // Traffic Programme
        val ta: Boolean        = false,   // Traffic Announcement
        val afList: List<Double> = emptyList(),  // Alternative Frequencies (MHz)
        val musicSpeech: Boolean = false,
        val stereo: Boolean    = false,
        val artificial: Boolean= false,
        val compressed: Boolean= false
    )

    // Public observable state
    private val _dataFlow = MutableStateFlow(RdsData())
    val dataFlow: StateFlow<RdsData> = _dataFlow.asStateFlow()

    // Internal decode state
    private val psChars   = CharArray(8)  { ' ' }
    private val rtChars   = CharArray(64) { ' ' }
    private var piCode    = 0
    private var ptyCode   = 0
    private var tp = false; private var ta = false
    private val afFreqs   = mutableListOf<Double>()

    // Bit synchroniser
    private var bitPhase  = 0.0
    private val samplesPerBit = sampleRate / RDS_BAUD_RATE
    private var prevSample = 0f
    private val bitBuffer = mutableListOf<Int>()

    // RDS offset words for syndrome checking
    private val offsets = mapOf(
        "A" to 0b0011111100, "B" to 0b0110011000,
        "C" to 0b0101101000, "Cp" to 0b1101010000,
        "D" to 0b0110110100
    )
    private val generatorPoly = 0b10110111001  // G(x) = x^10 + x^8 + x^7 + x^5 + x^4 + x^3 + x^0

    // Current data
    var currentData: RdsData = RdsData()
        private set

    var onDataChanged: ((RdsData) -> Unit)? = null

    /**
     * Feed samples from the wide-FM demodulator (at [sampleRate]).
     * Internally extracts the 57 kHz RDS subcarrier, clock-recovers,
     * and decodes blocks.
     */
    fun feedAudio(samples: FloatArray) {
        val step = 2.0 * PI * RDS_FREQ / sampleRate
        var phase = 0.0

        for (sample in samples) {
            // Mix down to baseband
            val i = sample * cos(phase).toFloat()
            phase += step
            if (phase > 2 * PI) phase -= 2 * PI

            // Low-pass filter (simple one-pole IIR)
            val iFiltered = lowpass(i)

            // Clock recovery via zero-crossing
            bitPhase += 1.0
            if (prevSample * iFiltered < 0 || bitPhase >= samplesPerBit) {
                if (bitPhase >= samplesPerBit * 0.4) {
                    val bit = if (iFiltered > 0) 1 else 0
                    bitBuffer.add(bit)
                    processBit()
                }
                bitPhase -= samplesPerBit
            }
            prevSample = iFiltered
        }
    }

    private var lpfY = 0f
    private val lpfAlpha = (1.0 / (1.0 + 2 * PI * 2400.0 / sampleRate)).toFloat()
    private fun lowpass(x: Float): Float {
        lpfY = lpfAlpha * lpfY + (1f - lpfAlpha) * x
        return lpfY
    }

    // Sliding window trying to decode blocks
    private var syncState = SyncState.NOSYNC
    private var blockCount = 0
    private val blocks = arrayOfNulls<IntArray>(4)

    private enum class SyncState { NOSYNC, SYNC }

    private fun processBit() {
        if (bitBuffer.size < BLOCK_SIZE) return
        if (bitBuffer.size > BLOCK_SIZE * 10) bitBuffer.clear()  // runaway guard
        val word = bitBuffer.takeLast(BLOCK_SIZE)
        val data = word.take(16).fold(0) { acc, b -> (acc shl 1) or b }
        val cw   = word.drop(16).take(10).fold(0) { acc, b -> (acc shl 1) or b }

        val syndrome = computeSyndrome(data, cw)
        val blockIdx = syndromeToBlock(syndrome) ?: return

        if (blockIdx >= 0) {
            blocks[blockIdx] = intArrayOf(data)
            blockCount++
            if (blockCount == 4 && blocks.all { it != null }) {
                decodeGroup(blocks.map { it!![0] }.toIntArray())
                blocks.fill(null)
                blockCount = 0
            }
        }
    }

    private fun computeSyndrome(data: Int, checkword: Int): Int {
        var reg = 0
        val combined = (data shl 10) or checkword
        for (i in 25 downTo 0) {
            val bit = (combined shr i) and 1
            val feedback = (reg shr 9) and 1
            reg = ((reg shl 1) or bit) and 0x3FF
            if (feedback == 1) reg = reg xor generatorPoly
        }
        return reg
    }

    private fun syndromeToBlock(syndrome: Int): Int? {
        return when (syndrome) {
            offsets["A"] -> 0
            offsets["B"] -> 1
            offsets["C"] -> 2
            offsets["Cp"] -> 2
            offsets["D"] -> 3
            else -> null
        }
    }

    private fun decodeGroup(w: IntArray) {
        if (w.size < 4) return
        piCode = w[0]
        val groupType = (w[1] shr 12) and 0x0F
        val version   = (w[1] shr 11) and 0x01
        tp = (w[1] shr 10) and 1 == 1
        ptyCode = (w[1] shr 5) and 0x1F
        ta = (w[1] shr 4) and 1 == 1

        when (groupType) {
            0 -> decodeGroup0(w, version)  // Basic tuning / PS
            2 -> decodeGroup2(w, version)  // RadioText
            else -> {}
        }

        currentData = RdsData(
            piCode    = piCode,
            psName    = String(psChars).trimEnd(),
            radioText = String(rtChars).trimEnd(),
            pty       = ptyCode,
            tp        = tp,
            ta        = ta,
            afList    = afFreqs.toList()
        )
        _dataFlow.value = currentData
        onDataChanged?.invoke(currentData)
    }

    private fun decodeGroup0(w: IntArray, ver: Int) {
        val segAddr = w[1] and 0x03
        // Alternative Frequencies
        if (ver == 0) {
            val af1 = (w[2] shr 8) and 0xFF
            val af2 = w[2] and 0xFF
            if (af1 in 1..204) afFreqs.add(af1 * 0.1 + 87.6)
            if (af2 in 1..204) afFreqs.add(af2 * 0.1 + 87.6)
        }
        // PS name (2 chars per group-0 block)
        if (segAddr * 2 + 1 < psChars.size) {
            psChars[segAddr * 2]     = ((w[3] shr 8) and 0x7F).toChar()
            psChars[segAddr * 2 + 1] = (w[3] and 0x7F).toChar()
        }
    }

    private fun decodeGroup2(w: IntArray, ver: Int) {
        val segAddr = w[1] and 0x0F
        if (ver == 0) {
            // Version A: 4 chars per segment
            val base = segAddr * 4
            if (base + 3 < rtChars.size) {
                rtChars[base]     = ((w[2] shr 8) and 0x7F).toChar()
                rtChars[base + 1] = (w[2] and 0x7F).toChar()
                rtChars[base + 2] = ((w[3] shr 8) and 0x7F).toChar()
                rtChars[base + 3] = (w[3] and 0x7F).toChar()
            }
        } else {
            // Version B: 2 chars per segment
            val base = segAddr * 2
            if (base + 1 < rtChars.size) {
                rtChars[base]     = ((w[3] shr 8) and 0x7F).toChar()
                rtChars[base + 1] = (w[3] and 0x7F).toChar()
            }
        }
    }

    val ptyName: String get() = PTY_NAMES.getOrElse(ptyCode) { "Unknown" }
}
