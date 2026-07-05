package com.radiosport.ninegradio.dsp

/**
 * Frequency step and band preset manager.
 * Provides standard step sizes per band/mode and quick-access band presets.
 */
object FrequencyStepManager {

    enum class StepSize(val hz: Long, val label: String) {
        HZ_1      (1L,          "1 Hz"),
        HZ_10     (10L,         "10 Hz"),
        HZ_100    (100L,        "100 Hz"),
        HZ_500    (500L,        "500 Hz"),
        KHZ_1     (1_000L,      "1 kHz"),
        KHZ_2_5   (2_500L,      "2.5 kHz"),
        KHZ_5     (5_000L,      "5 kHz"),
        KHZ_6_25  (6_250L,      "6.25 kHz"),
        KHZ_8_33  (8_330L,      "8.33 kHz"),   // AM aviation channel spacing
        KHZ_9     (9_000L,      "9 kHz"),       // AM broadcast (ITU R1/R3)
        KHZ_10    (10_000L,     "10 kHz"),      // AM broadcast (ITU R2)
        KHZ_12_5  (12_500L,     "12.5 kHz"),    // NFM standard
        KHZ_25    (25_000L,     "25 kHz"),      // Wide NFM / some aviation
        KHZ_50    (50_000L,     "50 kHz"),
        KHZ_100   (100_000L,    "100 kHz"),
        KHZ_200   (200_000L,    "200 kHz"),     // FM broadcast
        MHZ_1     (1_000_000L,  "1 MHz"),
        MHZ_10    (10_000_000L, "10 MHz")
    }

    data class BandPreset(
        val name: String,
        val startHz: Long,
        val stopHz: Long,
        val defaultMode: String,
        val defaultStep: StepSize,
        val description: String,
        // Offset of the channel grid's first channel from [startHz], for bands
        // whose channel centres are not aligned to the band edge.
        // e.g. PMR446: band starts at 446.000 MHz but channel 1 sits at
        // 446.00625 MHz (a +6.25 kHz offset), with subsequent channels every
        // 12.5 kHz (446.00625, 446.01875, 446.03125 … 446.19375 — 16 channels).
        val channelOffsetHz: Long = 0L
    )

    val BAND_PRESETS = listOf(
        BandPreset("MW AM Broadcast",  530_000L,    1_710_000L, "AM",  StepSize.KHZ_9,   "Medium wave AM"),
        BandPreset("Shortwave (SW)",   2_300_000L,  26_100_000L,"AM",  StepSize.KHZ_5,   "SW broadcast"),
        BandPreset("HF Amateur",       1_800_000L,  30_000_000L,"USB", StepSize.KHZ_1,   "HF ham bands"),
        BandPreset("VHF Marine",       156_000_000L,162_000_000L,"NFM",StepSize.KHZ_25,  "Marine VHF"),
        BandPreset("VHF Air",          108_000_000L,136_000_000L,"AM", StepSize.KHZ_8_33,"Aviation VHF"),
        BandPreset("NOAA Weather",     162_400_000L,162_550_000L,"NFM",StepSize.KHZ_25,  "NOAA WX"),
        BandPreset("2m Ham",           144_000_000L,148_000_000L,"NFM",StepSize.KHZ_12_5,"2 metre amateur"),
        BandPreset("APRS (NA)",        144_390_000L,144_390_000L,"NFM",StepSize.KHZ_12_5,"APRS 144.390"),
        BandPreset("70cm Ham",         420_000_000L,450_000_000L,"NFM",StepSize.KHZ_12_5,"70 cm amateur"),
        BandPreset("UHF PMR446",       446_000_000L,446_200_000L,"NFM",StepSize.KHZ_12_5,"PMR446",
            channelOffsetHz = 6_250L),  // ch1 = 446.00625 MHz, 16 channels @ 12.5 kHz spacing
        BandPreset("ISM 433 MHz",      433_050_000L,434_790_000L,"NFM",StepSize.KHZ_25,  "ISM 433 MHz"),
        BandPreset("ISM 868 MHz",      868_000_000L,868_600_000L,"NFM",StepSize.KHZ_25,  "ISM 868 MHz (EU)"),
        BandPreset("ISM 915 MHz",      902_000_000L,928_000_000L,"NFM",StepSize.KHZ_25,  "ISM 915 MHz (NA)"),
        BandPreset("GSM 900 DL",       935_000_000L,960_000_000L,"NFM",StepSize.KHZ_200, "GSM 900 downlink"),
        BandPreset("LTE 1800 DL",    1_710_000_000L,1_760_000_000L,"NFM",StepSize.KHZ_200,"LTE Band 3 downlink"),
        BandPreset("L-Band GPS",     1_575_420_000L,1_575_420_000L,"RAW",StepSize.MHZ_1, "GPS L1"),
        BandPreset("ADS-B",        1_090_000_000L,1_090_000_000L,"ADSB",StepSize.MHZ_1,  "ADS-B 1090 MHz"),
        BandPreset("ACARS",          129_125_000L, 136_900_000L,"ACARS",StepSize.KHZ_25, "Aircraft ACARS"),
        BandPreset("FM Broadcast",    87_500_000L, 108_000_000L,"WFM", StepSize.KHZ_200, "FM stereo broadcast"),
        BandPreset("NOAA Satellites", 137_000_000L, 138_000_000L,"NFM",StepSize.KHZ_25,  "NOAA APT/LRPT")
    )

    /**
     * Returns the recommended step size for a given frequency and mode.
     */
    fun recommendedStep(freqHz: Long, mode: String): StepSize {
        val mhz = freqHz / 1_000_000.0
        return when {
            mode == "AM" && mhz < 2      -> StepSize.KHZ_9
            mode == "AM" && mhz < 30     -> StepSize.KHZ_5
            mode == "AM" && mhz in 108.0..137.0 -> StepSize.KHZ_8_33
            mode in listOf("USB","LSB","CW","CWR") -> StepSize.HZ_100
            mode == "WFM" || mode == "WFM_STEREO"  -> StepSize.KHZ_200
            mode == "NFM" -> when {
                mhz < 30   -> StepSize.KHZ_5
                mhz < 87   -> StepSize.KHZ_12_5
                mhz < 500  -> StepSize.KHZ_12_5
                else       -> StepSize.KHZ_25
            }
            else -> StepSize.KHZ_12_5
        }
    }

    /**
     * Snap [freqHz] to the nearest channel grid point for [stepHz].
     *
     * Uses the [bandStartHz] of the matching [BandPreset] as the grid origin so
     * that channels defined relative to a band start (e.g. FM broadcast at
     * 87.5 MHz with 200 kHz spacing: 87.5, 87.7, 87.9, 88.1 …) round correctly.
     * Plain integer division from 0 Hz gives a 100 kHz offset for every FM
     * channel and silently snaps 87.9 MHz to 87.8 MHz.
     *
     * Falls back to 0-Hz-origin rounding when no band preset is found.
     */
    fun snapToChannel(freqHz: Long, stepHz: Long): Long {
        if (stepHz <= 0L) return freqHz
        val preset = presetsAt(freqHz).minByOrNull { it.stopHz - it.startHz }
        val origin = (preset?.startHz ?: 0L) + (preset?.channelOffsetHz ?: 0L)
        val offset = freqHz - origin
        return origin + (offset + stepHz / 2) / stepHz * stepHz
    }

    /**
     * Returns all band presets that overlap a given frequency.
     */
    fun presetsAt(freqHz: Long): List<BandPreset> =
        BAND_PRESETS.filter { freqHz in it.startHz..it.stopHz }

    /**
     * Returns the best matching preset for a given frequency.
     */
    fun bestPreset(freqHz: Long): BandPreset? =
        BAND_PRESETS.filter { freqHz in it.startHz..it.stopHz }
            .minByOrNull { it.stopHz - it.startHz }  // narrowest match

    /**
     * All unique step sizes, sorted.
     */
    val ALL_STEPS: List<StepSize> = StepSize.values().sortedBy { it.hz }
}
