package com.radiosport.ninegradio.ui

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.radiosport.ninegradio.R
import com.radiosport.ninegradio.dsp.*
import com.radiosport.ninegradio.usb.RtlSdrDevice
import kotlinx.coroutines.*
import kotlin.math.*

/**
 * In-app RTL-SDR performance & sanity test runner.
 *
 * Accessible via the main menu → "Run Tests".
 * Runs entirely on a background coroutine; results stream into a ScrollView
 * in real time.  No device connection required — all tests exercise the DSP
 * and driver logic in isolation.
 */
class RtlSdrTestActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView
    private lateinit var btnRun: Button
    private lateinit var btnClear: Button
    private lateinit var scrollView: ScrollView
    private lateinit var tvSummary: TextView

    private var testJob: Job? = null

    // ── Counters ──────────────────────────────────────────────────────────────
    private var passed = 0
    private var failed = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rtlsdr_test)
        supportActionBar?.apply { title = "RTL-SDR Tests"; setDisplayHomeAsUpEnabled(true) }

        tvLog      = findViewById(R.id.tvTestLog)
        btnRun     = findViewById(R.id.btnRunTests)
        btnClear   = findViewById(R.id.btnClearLog)
        scrollView = findViewById(R.id.testScrollView)
        tvSummary  = findViewById(R.id.tvTestSummary)

        btnRun.setOnClickListener   { runAllTests() }
        btnClear.setOnClickListener { clearLog() }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ─────────────────────────────────────────────────────────────────────────
    //  Test runner
    // ─────────────────────────────────────────────────────────────────────────

    private fun runAllTests() {
        testJob?.cancel()
        clearLog()
        btnRun.isEnabled = false

        testJob = lifecycleScope.launch(Dispatchers.Default) {
            log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            log("  9GRadio RTL-SDR Test Suite")
            log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")

            // ── 1. Hardware constants ─────────────────────────────────────
            section("1. Hardware Constants")
            test("XTAL frequency is 28.8 MHz") {
                RtlSdrDevice.XTAL_FREQ == 28_800_000L
            }
            test("Frequency range 500 kHz – 1766 MHz") {
                RtlSdrDevice.MIN_FREQ_HZ == 500_000L &&
                RtlSdrDevice.MAX_FREQ_HZ == 1_766_000_000L
            }
            test("HF cutoff at 28.8 MHz") {
                RtlSdrDevice.HF_CUTOFF_HZ == 28_800_000L
            }
            test("Direct-sampling constants 0/1/2") {
                RtlSdrDevice.DIRECT_SAMPLING_OFF == 0 &&
                RtlSdrDevice.DIRECT_SAMPLING_I   == 1 &&
                RtlSdrDevice.DIRECT_SAMPLING_Q   == 2
            }
            test("Bias-tee GPIO bit is 0x08") {
                RtlSdrDevice.BIAS_TEE_GPIO_BIT == 0x08
            }
            test("Gain table has 29 entries") {
                RtlSdrDevice.GAIN_TABLE_DB_TENTHS.size == 29
            }
            test("Gain table is monotonically non-decreasing") {
                val g = RtlSdrDevice.GAIN_TABLE_DB_TENTHS
                (1 until g.size).all { g[it] >= g[it - 1] }
            }
            test("Sample-rate PLL ratio in valid range for ALL rates (low-band and main-band)") {
                // All SAMPLE_RATES entries — including the low-rate band (225 001–300 000 Hz)
                // — produce rsampRatio values within the RTL2832U's valid demod register
                // range (0x0020_0000–0x0FFF_FFFF after the 0x0FFFFFFC mask).
                // Verified: 240 kS/s → 0x0E000000, 250 kS/s → 0x0CCCCCCC,
                //           288 kS/s → 0x09000000 — all within range.
                RtlSdrDevice.SAMPLE_RATES.all { rate ->
                    val ratio = (RtlSdrDevice.XTAL_FREQ * (1L shl 22) / rate) and 0x0FFFFFFCL
                    ratio in 0x0020_0000L..0x0FFF_FFFFL
                }
            }
            test("Low-rate band entries are within valid IQ range (225 001–300 000 Hz)") {
                // librtlsdr valid low-rate band: 225 001 Hz (225.001 kS/s) – 300 000 Hz (300 kS/s).
                // All nine low-band entries must be strictly within this window.
                RtlSdrDevice.SAMPLE_RATES.filter { it <= 300_000 }.all { rate ->
                    rate in 225_001..300_000
                }
            }
            test("nearestSampleRate snaps dead-zone value to nearer band boundary") {
                // A rate in the dead zone (300 001–899 999) must resolve to either the
                // top of the low band (≤ 300 000) or the bottom of the main band (≥ 900 000).
                val snapped = RtlSdrDevice.nearestSampleRate(600_000)
                snapped <= 300_000 || snapped >= 900_000
            }
            test("nearestSampleRate stays within low-rate band for low-band requests") {
                // Requesting any value ≤ 300 000 must resolve to a low-band entry (≤ 300 000),
                // never crossing into the dead zone or main band.
                listOf(225_001, 240_000, 242_000, 250_000, 256_000,
                       264_000, 272_000, 286_000, 288_000, 300_000).all { req ->
                    RtlSdrDevice.nearestSampleRate(req) <= 300_000
                }
            }
            test("nearestSampleRate stays within main band for main-band requests") {
                // Requesting any value ≥ 900 000 must resolve to a main-band entry (≥ 900 000).
                listOf(900_000, 912_000, 1_920_000, 2_400_000).all { req ->
                    RtlSdrDevice.nearestSampleRate(req) >= 900_000
                }
            }
            test("wfmIfRate: never exceeds the 250 kS/s demodulator input ceiling") {
                // For ALL device rates, wfmIfRate() must be ≤ WFM_MAX_DEMOD_RATE (250 000 Hz).
                // This is the hard "WFM is max 250k" constraint — rational resampling
                // (PolyphaseResampler/ComplexDecimator) brings any deviceRate above
                // 250 000 Hz down to ≤ 250 000 Hz, even within the low-rate band
                // (e.g. 256/264/272/286/288/300 kS/s).
                RtlSdrDevice.SAMPLE_RATES.all { rate ->
                    DspEngine.wfmIfRate(rate) <= DspEngine.WFM_MAX_DEMOD_RATE
                }
            }
            test("wfmIfRate: rates at or below target bandwidth pass through unchanged") {
                // For deviceRate ≤ WFM_TARGET_BW (200 000 Hz), wfmIfRate() returns
                // deviceRate unchanged (1:1, no resampling needed).
                RtlSdrDevice.SAMPLE_RATES.filter { it <= 200_000 }.all { rate ->
                    DspEngine.wfmIfRate(rate) == rate
                }
            }
            test("wfmIfRate: rates above 250k are decimated below the ceiling") {
                // 256/264/272/286/288/300 kS/s are all > 250 000 Hz and must be
                // brought down to ≤ 250 000 Hz via rational resampling.
                listOf(256_000, 264_000, 272_000, 286_000, 288_000, 300_000).all { rate ->
                    DspEngine.wfmIfRate(rate) <= DspEngine.WFM_MAX_DEMOD_RATE
                }
            }
            test("wfmIfRate: main-band rates produce IF within 150–250 kHz WFM window") {
                // For deviceRate > 250 000 Hz, the IF must be within the WFM passband
                // and never exceed the 250 kS/s demodulator input ceiling.
                RtlSdrDevice.SAMPLE_RATES.filter { it > 250_000 }.all { rate ->
                    DspEngine.wfmIfRate(rate) in 150_000..DspEngine.WFM_MAX_DEMOD_RATE
                }
            }

            // ── 2. Demodulation modes ─────────────────────────────────────
            section("2. Demodulation Modes")
            test("All modes have positive bandwidth") {
                DemodMode.values().all { it.defaultBwHz > 0 }
            }
            test("DRM requires direct sampling; FM/NFM/WFM/AM/ADSB do not") {
                DemodMode.DRM.requiresDirectSampling &&
                !DemodMode.FM.requiresDirectSampling &&
                !DemodMode.NFM.requiresDirectSampling &&
                !DemodMode.AM.requiresDirectSampling &&
                !DemodMode.ADSB.requiresDirectSampling
            }
            test("ADS-B pinned at 1090 MHz") {
                DemodMode.ADSB.minFreqHz == 1_090_000_000L &&
                DemodMode.ADSB.maxFreqHz == 1_090_000_000L
            }
            test("DemodulatorFactory creates correct type for every mode") {
                listOf(DemodMode.AM, DemodMode.NFM, DemodMode.WFM,
                       DemodMode.USB, DemodMode.LSB, DemodMode.CW, DemodMode.RAW)
                    .all { DemodulatorFactory.create(it).mode == it }
            }

            // ── 3. FFT engine correctness ─────────────────────────────────
            section("3. FFT Engine — Correctness")
            val fft = FftEngine(1024)
            test("Output length matches FFT size") {
                fft.processFloat(FloatArray(2048)).size == 1024
            }
            test("DC input peaks at centre bin") {
                val iq = FloatArray(2048); for (i in 0 until 1024) iq[2*i] = 0.5f
                val peak = fft.processFloat(iq).indices.maxByOrNull { fft.processFloat(iq)[it] }!!
                abs(peak - 512) <= 2
            }
            test("Zero input noise floor below −60 dBFS") {
                fft.processFloat(FloatArray(2048)).all { it < -60f }
            }
            test("All window types produce NaN-free output") {
                val iq = FloatArray(2048) { Math.random().toFloat() * 2f - 1f }
                FftEngine.WindowType.values().all { wt ->
                    fft.setWindowType(wt)
                    fft.processFloat(iq).none { it.isNaN() }
                }
            }
            test("FFT size change: 256/512/1024/2048/4096 all correct length") {
                listOf(256, 512, 1024, 2048, 4096).all { sz ->
                    fft.setFftSize(sz)
                    fft.processFloat(FloatArray(sz * 2) { 0.1f }).size == sz
                }
            }

            // ── 4. Demodulator correctness ────────────────────────────────
            section("4. Demodulators — Correctness")
            test("AM envelope is non-negative") {
                val iq = tone(1_000.0, 11025, 512)
                AmDemodulator().demodulate(iq, 11025).drop(50).all { it >= -0.01f }
            }
            test("NFM output bounded (no overflow)") {
                val iq = tone(5_000.0, 48000, 2048)
                NfmDemodulator().demodulate(iq, 48000).all { !it.isNaN() && abs(it) < 10f }
            }
            test("USB zero-input gives near-zero output") {
                SsbDemodulator(isUsb = true).demodulate(FloatArray(512), 11025)
                    .all { abs(it) < 0.01f }
            }
            test("LSB zero-input gives near-zero output") {
                SsbDemodulator(isUsb = false).demodulate(FloatArray(512), 11025)
                    .all { abs(it) < 0.01f }
            }
            test("WFM mono output length = N/2") {
                val iq = tone(75_000.0, 240_000, 4096)
                WfmDemodulator(stereo = false).demodulate(iq, 240_000).size == iq.size / 2
            }
            test("RAW demodulator produces non-negative magnitudes") {
                val iq = FloatArray(100) { 0.5f }
                RawDemodulator().demodulate(iq, 48000).all { it >= 0f }
            }

            // ── 5. Polyphase resampler ────────────────────────────────────
            section("5. Polyphase Resampler")
            test("1920k→48k output length is ~48 samples for 2048-sample input") {
                val r = PolyphaseResampler(1_920_000, 48_000)
                val n = r.process(FloatArray(2048) { sin(it * 0.01).toFloat() }).size
                n in 40..60
            }
            test("Passthrough (same rate) preserves length (±5 for filter delay)") {
                val r = PolyphaseResampler(48_000, 48_000)
                r.process(FloatArray(100) { it.toFloat() }).size in 95..105
            }
            test("No NaN or Inf in resampler output") {
                val r = PolyphaseResampler(1_920_000, 48_000)
                val out = r.process(FloatArray(8192) { (Math.random() * 2 - 1).toFloat() })
                out.none { it.isNaN() || it.isInfinite() }
            }

            // ── 6. Gain calibration ───────────────────────────────────────
            section("6. Gain Calibration")
            test("Noise figure interpolates across full frequency range") {
                listOf(500_000L, 100_000_000L, 433_000_000L, 1_090_000_000L, 1_766_000_000L)
                    .all { GainCalibration.noiseFigureAtFreq(it) in 1f..30f }
            }
            test("adcPowerToDbm returns finite value") {
                GainCalibration.adcPowerToDbm(0.01f, 30f, 144_390_000L, 12_500).isFinite()
            }
            test("MDS is below −100 dBm for 12.5 kHz BW at 144 MHz") {
                GainCalibration.minimumDetectableSignal(30f, 144_000_000L, 12_500) < -100f
            }
            test("suggestGain returns index in 0..28 for all scenarios") {
                GainCalibration.GainScenario.values().all { s ->
                    GainCalibration.suggestGain(s, 144_390_000L) in 0..28
                }
            }
            test("estimateSaturation on mid-range data returns near-zero") {
                val buf = ByteArray(1024) { 127 }   // flat mid-range → 0 % saturated
                GainCalibration.estimateSaturation(buf) < 1f
            }
            test("estimateSaturation on clipped data returns near-100 %") {
                val buf = ByteArray(1024) { 255.toByte() }
                GainCalibration.estimateSaturation(buf) > 95f
            }
            test("dbfsToSUnit covers S0…S9+60 without throwing") {
                listOf(-130f, -113f, -85f, -57f, -47f, -17f, 0f).map {
                    GainCalibration.dbfsToSUnit(it)
                }.all { it.startsWith("S") }
            }

            // ── 7. Audio DSP ──────────────────────────────────────────────
            section("7. Audio DSP")
            test("DcBlocker removes DC offset") {
                val dc = AudioDsp.DcBlocker()
                // R=0.9995 needs ~4600 samples to decay below 0.1; use 8192 to be safe
                val sig = FloatArray(8192) { 1f }
                val out = dc.processBlock(sig)
                abs(out.last()) < 0.1f
            }
            test("NoiseBlanker passes low-level signal at steady state") {
                val nb = AudioDsp.NoiseBlanker(thresholdFactor = 4f)
                // Warm up so avgPower builds above zero before we check output
                val warmup = FloatArray(512) { 0.01f }
                nb.process(warmup)
                // Now steady-state: threshold = 4×sqrt(0.0001) = 0.04 > 0.01, so signal passes
                val sig = FloatArray(512) { 0.01f }
                val out = nb.process(sig)
                out.all { abs(it - 0.01f) < 0.005f }
            }
            test("AudioAgc reduces loud signal below input level") {
                val agc = AudioDsp.AudioAgc(targetLevel = 0.5f, sampleRate = 48_000)
                val loud = FloatArray(4096) { 0.9f }
                val out = agc.process(loud)
                // Steady-state gain = sqrt(target/input) ≈ 0.745, output ≈ 0.67 < 0.9
                out.last() < 0.9f && out.last() > 0f
            }
            test("AutoNotchFilter converges on a pure tone") {
                val anf = AudioDsp.AutoNotchFilter()
                val tone = FloatArray(4096) { sin(it * 2 * PI * 1000 / 48000).toFloat() }
                val out = anf.process(tone)
                val rmsIn  = sqrt(tone.map { it*it }.average()).toFloat()
                val rmsOut = sqrt(out.takeLast(1024).map { it*it }.average()).toFloat()
                rmsOut < rmsIn * 0.5f                   // notch reduces tone by >6 dB
            }
            test("IfNotch attenuates target frequency") {
                val notch = AudioDsp.IfNotch(1_000.0, 48_000)
                val sig   = FloatArray(2048) { sin(it * 2 * PI * 1000 / 48000).toFloat() }
                val out   = notch.processBlock(sig)
                val rmsIn  = sqrt(sig.map { it*it }.average()).toFloat()
                val rmsOut = sqrt(out.map { it*it }.average()).toFloat()
                rmsOut < rmsIn * 0.5f
            }
            test("softClip keeps output in −1..1 for over-driven input") {
                val loud = FloatArray(256) { 2.0f }
                AudioDsp.softClip(loud).all { it <= 1f }
            }
            test("NoiseReducer calibrates in 20 frames and processes without throwing") {
                val nr    = AudioDsp.NoiseReducer()
                val noise = FloatArray(512) { (Math.random() * 0.02).toFloat() }
                repeat(20) { nr.calibrate(noise) }
                val out = nr.process(FloatArray(512) { sin(it * 0.1).toFloat() })
                nr.isCalibrated() && out.none { it.isNaN() }
            }

            // ── 8. Performance benchmarks ─────────────────────────────────
            section("8. Performance (real-time headroom)")
            val SR = 1_920_000   // 48 000 × 40 — standard main-band rate
            // ── Pre-build ALL objects so construction cost is NEVER included in timing ──
            // (constructing FftEngine/Demodulator/Resampler inside a perfTest lambda was
            //  the bug that caused the FFT 2048-pt test to fail: the 2048-sample window
            //  allocation + Blackman-Harris computation added ~0.2 ms per call, which at
            //  20 timed iterations pushed elapsed time above the 5 ms budget for 4× RT.)
            val fft2048     = FftEngine(2048)
            val raw2048     = ByteArray(4096) { (128 + (it % 60) - 30).toByte() }
            val nfmPerf     = NfmDemodulator()
            val nfmIqPerf   = tone(5_000.0, 48_000, 2048)
            val wfmPerf     = WfmDemodulator(stereo = false)
            val wfmIqPerf   = tone(75_000.0, 240_000, 4096)
            val resampPerf  = PolyphaseResampler(SR, 48_000)
            val resampInput = FloatArray(8192) { sin(it * 0.01).toFloat() }
            val agcPerf     = AudioDsp.AudioAgc(sampleRate = 48_000)
            val agcInput    = FloatArray(2048) { sin(it * 0.05).toFloat() }

            perfTest("FFT 2048-pt processUint8 ≥ 2× real-time",
                minThroughput = SR * 2.0, inputPerIter = 2048) {
                fft2048.processUint8(raw2048)
            }
            perfTest("NFM demodulator ≥ 10× real-time",
                minThroughput = 48_000 * 10.0, inputPerIter = 2048) {
                nfmPerf.demodulate(nfmIqPerf, 48_000)
            }
            perfTest("WFM mono demodulator ≥ 4× real-time (240 kS/s input)",
                minThroughput = 240_000 * 4.0, inputPerIter = 4096) {
                wfmPerf.demodulate(wfmIqPerf, 240_000)
            }
            perfTest("Polyphase resampler 2048k→48k ≥ 4× real-time",
                minThroughput = SR * 4.0, inputPerIter = 8192) {
                resampPerf.process(resampInput)
            }
            perfTest("AudioAgc ≥ 20× real-time",
                minThroughput = 48_000 * 20.0, inputPerIter = 2048) {
                agcPerf.process(agcInput)
            }
            val fftPipeline   = FftEngine(2048)
            val demodPipeline = NfmDemodulator()
            val rsmpPipeline  = PolyphaseResampler(SR, 48_000)
            val dcPipeline    = AudioDsp.DcBlocker()
            val rawPipeline   = ByteArray(8192) { (128 + (it % 60) - 30).toByte() }
            val iqPipeline    = tone(5_000.0, SR, 4096)

            perfTest("End-to-end NFM pipeline ≥ 4× real-time (2048 kS/s)",
                minThroughput = SR * 4.0, inputPerIter = 4096) {
                fftPipeline.processUint8(rawPipeline)
                dcPipeline.processBlock(rsmpPipeline.process(demodPipeline.demodulate(iqPipeline, SR)))
            }

            // ── 9. Sample-Rate Sequence  (5 s per rate, in sequence) ─────
            //
            // For every sample rate the RTL-SDR hardware supports, we run the
            // full DSP pipeline (FFT + NFM demod + polyphase resamp + DC block)
            // for exactly 5 seconds of wall-clock time and assert that the
            // number of IQ samples processed is ≥ 5 × rate, proving the
            // software can sustain ≥ 1× real-time at that rate continuously.
            //
            // Rates under test (from RtlSdrDevice.SAMPLE_RATES):
            //   250 kS/s, 1024 kS/s, 1536 kS/s, 1792 kS/s, 1920 kS/s,
            //   2048 kS/s, 2160 kS/s, 2560 kS/s, 2880 kS/s, 3200 kS/s
            // ─────────────────────────────────────────────────────────────
            section("9. Sample-Rate Sequence  (5 s per rate, in sequence)")
            for (rate in RtlSdrDevice.SAMPLE_RATES) {
                val rateStr = when {
                    rate < 1_000_000 -> "${rate / 1_000} kS/s"
                    rate % 1_000_000 == 0 -> "${rate / 1_000_000} MS/s"
                    else -> "${"%.3f".format(rate / 1_000_000.0)} MS/s"
                }
                // Pre-build DSP objects — construction cost excluded from timing
                val srFft    = FftEngine(2048)
                val srDemod  = NfmDemodulator()
                val srResamp = PolyphaseResampler(rate, 48_000)
                val srDc     = AudioDsp.DcBlocker()
                // 8192 bytes = 4096 complex uint8 samples per USB bulk-transfer chunk
                val srRaw    = ByteArray(8192) { (128 + (it % 60) - 30).toByte() }
                // Tone at 10 % of Nyquist (capped at 200 kHz for wide-band rates)
                val srIq     = tone(minOf(rate * 0.1, 200_000.0), rate, 4096)

                // JIT warm-up: 5 full pipeline passes before we start the clock
                repeat(5) {
                    srFft.processUint8(srRaw)
                    srDc.processBlock(srResamp.process(srDemod.demodulate(srIq, rate)))
                }

                // Timed run: process IQ until 5 seconds of wall-clock have elapsed
                val targetSamples = rate.toLong() * 5          // samples in 5 real seconds
                val deadlineNs    = System.nanoTime() + 5_000_000_000L
                var processed     = 0L
                while (System.nanoTime() < deadlineNs) {
                    srFft.processUint8(srRaw)                  // spectrum
                    srDc.processBlock(
                        srResamp.process(srDemod.demodulate(srIq, rate)))  // audio chain
                    processed += 4096                          // 4096 IQ samples per block
                }
                val pct = (processed * 100L / targetSamples).toInt()
                test("$rateStr — $pct % of 5 s quota  (${processed/1000}k / ${targetSamples/1000}k samples)") {
                    processed >= targetSamples   // pass iff we kept up with real-time
                }
            }

            // ── Summary ───────────────────────────────────────────────────
            val total = passed + failed
            log("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            log("  PASSED $passed / $total")
            if (failed > 0) log("  FAILED $failed / $total")
            log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

            withContext(Dispatchers.Main) {
                tvSummary.text = if (failed == 0) "✓ All $total tests passed"
                                 else             "✗ $failed of $total tests failed"
                tvSummary.setTextColor(
                    if (failed == 0) 0xFF00CC44.toInt() else 0xFFFF3333.toInt()
                )
                btnRun.isEnabled = true
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun section(title: String) = log("\n▸ $title")

    private suspend fun test(name: String, block: () -> Boolean) {
        val ok = try { block() } catch (e: Throwable) { false }
        if (ok) passed++ else failed++
        val icon = if (ok) "  ✓" else "  ✗"
        val color = if (ok) "" else "  ← FAIL"
        log("$icon  $name$color")
    }

    /**
     * Runs [block] 5 warm-up times then 20 timed times, asserts throughput.
     */
    private suspend fun perfTest(
        name: String,
        minThroughput: Double,
        inputPerIter: Int,
        block: () -> Unit
    ) {
        try {
            repeat(5) { block() }
            val t0 = System.nanoTime()
            repeat(20) { block() }
            val throughput = (inputPerIter.toLong() * 20) /
                             ((System.nanoTime() - t0) / 1_000_000_000.0)
            val ok = throughput >= minThroughput
            if (ok) passed++ else failed++
            val icon = if (ok) "  ✓" else "  ✗"
            val detail = "%.1f× RT".format(throughput / minThroughput)
            log("$icon  $name  [$detail]${if (!ok) "  ← FAIL" else ""}")
        } catch (e: Throwable) {
            failed++
            log("  ✗  $name  [exception: ${e.message}]")
        }
    }

    private suspend fun log(msg: String) = withContext(Dispatchers.Main) {
        tvLog.append("$msg\n")
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun clearLog() {
        tvLog.text = ""
        tvSummary.text = ""
        passed = 0; failed = 0
    }

    private fun tone(freqHz: Double, sampleRate: Int, n: Int) = FloatArray(n * 2) { idx ->
        val i = idx / 2
        val ph = 2.0 * PI * freqHz * i / sampleRate
        if (idx % 2 == 0) cos(ph).toFloat() else sin(ph).toFloat()
    }
}
