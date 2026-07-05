package com.radiosport.ninegradio.dsp

import kotlin.math.*

/**
 * RTL-SDR V4 gain calibration and noise figure estimation (9GRadio).
 *
 * The V4 (R828D) has a known gain curve that can be used to:
 *  - Convert raw ADC power readings to calibrated dBm
 *  - Estimate system noise figure
 *  - Auto-set optimal gain for a given scenario
 *  - Show calibrated S-meter readings
 *
 * Reference: RTL-SDR V4 datasheet, empirical measurements
 */
object GainCalibration {

    // Noise figure of R828D at various frequencies (approximate, dB)
    private val NF_TABLE_MHZ = mapOf(
        30    to  8.5f,
        100   to  5.2f,
        200   to  4.8f,
        433   to  5.5f,
        868   to  6.8f,
        915   to  7.0f,
        1090  to  8.2f,
        1296  to  9.5f,
        1575  to 10.8f,
        1700  to 12.0f
    )

    // Thermal noise floor: kTB = -174 dBm/Hz at 290K
    private const val KTB_DBM_HZ = -174.0f

    /**
     * Convert raw ADC power (linear) to calibrated dBm.
     * Accounts for gain setting and estimated system noise figure.
     *
     * @param adcPower   Linear power from FFT/RMS (normalised 0..1 scale)
     * @param gainDb     Current gain in dB
     * @param freqHz     Tuned frequency (for NF correction)
     * @param bwHz       Measurement bandwidth (for noise power)
     * @return           Estimated signal power in dBm
     */
    fun adcPowerToDbm(adcPower: Float, gainDb: Float, freqHz: Long, bwHz: Int): Float {
        if (adcPower <= 0f) return -200f
        val adcDbfs = 20f * log10(adcPower)           // dBFS (0 = ADC full-scale)
        val adcFullScaleDbm = -10f                     // RTL-SDR ADC FS ≈ -10 dBm
        val nf = noiseFigureAtFreq(freqHz)
        val correctedDbm = adcDbfs + adcFullScaleDbm - gainDb + nf
        return correctedDbm
    }

    /**
     * Calculate minimum detectable signal (MDS) for current settings.
     *
     * @param gainDb   Gain in dB
     * @param freqHz   Frequency
     * @param bwHz     Signal bandwidth
     * @return         MDS in dBm
     */
    fun minimumDetectableSignal(gainDb: Float, freqHz: Long, bwHz: Int): Float {
        val nf = noiseFigureAtFreq(freqHz)
        val noiseFloor = KTB_DBM_HZ + 10f * log10(bwHz.toFloat()) + nf
        // MDS ≈ noise floor + SNR_min (6 dB for basic detection)
        return noiseFloor + 6f
    }

    /**
     * Suggest optimal gain for a given scenario.
     *
     * @param scenario  Use case
     * @param freqHz    Frequency
     * @return          Recommended gain index (0..28)
     */
    fun suggestGain(scenario: GainScenario, freqHz: Long): Int {
        val freqMHz = freqHz / 1_000_000.0
        return when (scenario) {
            GainScenario.STRONG_LOCAL_FM   -> 0   // Avoid ADC saturation
            GainScenario.BROADCAST_FM      -> 3
            GainScenario.VHF_NFM           -> when {
                freqMHz < 150  -> 12
                freqMHz < 450  -> 10
                else           -> 14
            }
            GainScenario.WEAK_SIGNAL_HF    -> 28  // Max gain for HF
            GainScenario.SATELLITE_APT     -> 22  // NOAA/Meteor satellites
            GainScenario.ADSB_1090         -> 16  // ADS-B sweet spot
            GainScenario.APRS              -> 14
            GainScenario.GENERIC_SEARCH    -> 20  // Good all-round starting point
        }
    }

    /**
     * Estimate whether current gain is causing ADC saturation.
     *
     * @param iqData  Raw uint8 IQ buffer
     * @return        Saturation percentage (0..100)
     */
    fun estimateSaturation(iqData: ByteArray): Float {
        var saturated = 0
        for (b in iqData) {
            val v = b.toInt() and 0xFF
            if (v <= 5 || v >= 250) saturated++
        }
        return saturated.toFloat() / iqData.size * 100f
    }

    /**
     * Estimate IMD (Intermodulation Distortion) risk from peak-to-average ratio.
     */
    fun estimateImrRisk(iqData: ByteArray): ImrRisk {
        val floats = FloatArray(iqData.size) { ((iqData[it].toInt() and 0xFF) - 127.5f) / 128f }
        val peak = floats.maxOf { abs(it) }
        val rms  = sqrt(floats.map { it * it }.average().toFloat())
        val par  = if (rms > 0) 20f * log10(peak / rms) else 0f
        return when {
            par > 15f -> ImrRisk.HIGH
            par > 10f -> ImrRisk.MEDIUM
            else      -> ImrRisk.LOW
        }
    }

    /** Noise figure interpolated from table */
    fun noiseFigureAtFreq(freqHz: Long): Float {
        val mhz = (freqHz / 1_000_000).toInt()
        val keys = NF_TABLE_MHZ.keys.sorted()
        if (mhz <= keys.first()) return NF_TABLE_MHZ[keys.first()]!!
        if (mhz >= keys.last())  return NF_TABLE_MHZ[keys.last()]!!
        val lo = keys.filter { it <= mhz }.last()
        val hi = keys.filter { it >= mhz }.first()
        if (lo == hi) return NF_TABLE_MHZ[lo]!!
        val frac = (mhz - lo).toFloat() / (hi - lo)
        return NF_TABLE_MHZ[lo]!! + frac * (NF_TABLE_MHZ[hi]!! - NF_TABLE_MHZ[lo]!!)
    }

    /** Gain index to dB */
    fun gainIndexToDb(index: Int): Float =
        com.radiosport.ninegradio.usb.RtlSdrDevice.GAIN_TABLE_DB_TENTHS
            .getOrElse(index) { 0 } / 10f

    /** dBFS to S-unit string */
    fun dbfsToSUnit(dbfs: Float): String = when {
        dbfs < -120 -> "S0"
        dbfs < -113 -> "S1"
        dbfs < -106 -> "S2"
        dbfs < -99  -> "S3"
        dbfs < -92  -> "S4"
        dbfs < -85  -> "S5"
        dbfs < -78  -> "S6"
        dbfs < -71  -> "S7"
        dbfs < -64  -> "S8"
        dbfs < -57  -> "S9"
        dbfs < -47  -> "S9+10"
        dbfs < -37  -> "S9+20"
        dbfs < -27  -> "S9+30"
        dbfs < -17  -> "S9+40"
        dbfs < -7   -> "S9+50"
        else        -> "S9+60"
    }

    enum class GainScenario {
        STRONG_LOCAL_FM, BROADCAST_FM, VHF_NFM,
        WEAK_SIGNAL_HF, SATELLITE_APT, ADSB_1090,
        APRS, GENERIC_SEARCH
    }

    enum class ImrRisk { LOW, MEDIUM, HIGH }
}
