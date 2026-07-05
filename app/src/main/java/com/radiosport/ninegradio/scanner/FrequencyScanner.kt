package com.radiosport.ninegradio.scanner

import android.util.Log
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.source.IqSource
import com.radiosport.ninegradio.source.RtlSdrDeviceSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Frequency scanner that sweeps across a range looking for signals above squelch.
 * Supports memory channel scanning and custom range scanning.
 *
 * [signalLevelProvider] supplies the dBFS signal level to compare against
 * [ScanConfig.squelchDb]. If not provided, falls back to
 * [RtlSdrDevice.readSignalStrength] — the RTL2832U's wideband digital-IF AGC
 * register. That register reflects the *entire* sampled bandwidth (often
 * 1–2 MHz), not the narrow channel being scanned, so for narrowband signals
 * (e.g. PMR446's 12.5 kHz NFM channels within a multi-MHz capture) it is
 * dominated by whatever is loudest across the whole band and essentially
 * never reflects the tuned channel's actual level — active channels are
 * silently missed. Callers should pass a provider backed by the DSP engine's
 * FFT-peak signal level (DspEngine.statsFlow.value.signalDb), which is
 * computed from the live IQ stream and is in the same dBFS scale as the
 * spectrum display and squelch slider.
 */
class FrequencyScanner(
    private val device: IqSource,
    private val signalLevelProvider: (() -> Float)? = null
) {

    companion object {
        private const val TAG = "FrequencyScanner"
    }

    enum class ScanState { IDLE, SCANNING, PAUSED, STOPPED }

    data class ScanConfig(
        val startFreqHz: Long,
        val stopFreqHz: Long,
        val stepHz: Long = 12_500L,
        val squelchDb: Float = -100f,
        val dwellTimeMs: Long = 200L,
        val resumeTimeMs: Long = 3000L,
        val skipActiveMs: Long = 500L,
        val mode: DemodMode = DemodMode.NFM,
        val scanUp: Boolean = true
    )

    data class ScanStatus(
        val state: ScanState = ScanState.IDLE,
        val currentFreqHz: Long = 0L,
        val activeFreqHz: Long = 0L,
        val signalDb: Float = -120f,
        val hitsFound: Int = 0,
        val progress: Float = 0f
    )

    private val _status = MutableStateFlow(ScanStatus())
    val status: StateFlow<ScanStatus> = _status.asStateFlow()

    private val _activeFrequencies = MutableSharedFlow<Long>(extraBufferCapacity = 32)
    val activeFrequencies: SharedFlow<Long> = _activeFrequencies.asSharedFlow()

    /** Current dBFS signal level — narrowband FFT-peak if a provider was given,
     *  otherwise the last reported signal strength from the source status. */
    private fun readSignalLevel(): Float =
        signalLevelProvider?.invoke()
            ?: (device as? RtlSdrDeviceSource)?.device?.readSignalStrength()
            ?: device.statusFlow.value.signalStrengthDb

    private var scanJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun startScan(config: ScanConfig) {
        if (scanJob?.isActive == true) stopScan()
        scanJob = scope.launch {
            runScan(config)
        }
        Log.i(TAG, "Scan started: ${config.startFreqHz / 1e6} - ${config.stopFreqHz / 1e6} MHz")
    }

    fun stopScan() {
        scanJob?.cancel()
        _status.value = _status.value.copy(state = ScanState.STOPPED)
        Log.i(TAG, "Scan stopped")
    }

    fun pauseScan() {
        _status.value = _status.value.copy(state = ScanState.PAUSED)
    }

    fun resumeScan() {
        _status.value = _status.value.copy(state = ScanState.SCANNING)
    }

    private suspend fun runScan(config: ScanConfig) {
        val range = config.stopFreqHz - config.startFreqHz
        var currentFreq = config.startFreqHz
        var hitsFound = 0
        _status.value = ScanStatus(state = ScanState.SCANNING, currentFreqHz = currentFreq)

        while (currentCoroutineContext().isActive) {
            if (_status.value.state == ScanState.PAUSED) {
                delay(100)
                continue
            }

            // Tune to current frequency
            device.setCenterFrequency(currentFreq)
            delay(config.dwellTimeMs)

            // Sample signal strength
            val signalDb = readSignalLevel()
            val progress = (currentFreq - config.startFreqHz).toFloat() / range

            _status.value = _status.value.copy(
                currentFreqHz = currentFreq,
                signalDb = signalDb,
                progress = progress,
                hitsFound = hitsFound
            )

            // Check squelch
            if (signalDb > config.squelchDb) {
                hitsFound++
                _activeFrequencies.emit(currentFreq)
                _status.value = _status.value.copy(
                    activeFreqHz = currentFreq,
                    hitsFound = hitsFound
                )
                Log.d(TAG, "Signal found at ${currentFreq / 1e6} MHz: $signalDb dB")
                // Dwell on active frequency
                delay(config.resumeTimeMs)
            }

            // Advance frequency
            currentFreq = if (config.scanUp) {
                val next = currentFreq + config.stepHz
                if (next > config.stopFreqHz) config.startFreqHz else next
            } else {
                val prev = currentFreq - config.stepHz
                if (prev < config.startFreqHz) config.stopFreqHz else prev
            }
        }

        _status.value = _status.value.copy(state = ScanState.STOPPED)
    }

    /**
     * Scan a list of specific frequencies (memory scan).
     */
    fun startMemoryScan(
        frequencies: List<Long>,
        squelchDb: Float,
        dwellTimeMs: Long,
        resumeTimeMs: Long
    ) {
        if (frequencies.isEmpty()) return
        scanJob?.cancel()
        scanJob = scope.launch {
            var idx = 0
            var hitsFound = 0
            _status.value = ScanStatus(state = ScanState.SCANNING)

            while (isActive) {
                if (_status.value.state == ScanState.PAUSED) {
                    delay(100); continue
                }
                val freq = frequencies[idx % frequencies.size]
                device.setCenterFrequency(freq)
                delay(dwellTimeMs)

                val signalDb = readSignalLevel()
                _status.value = _status.value.copy(
                    currentFreqHz = freq,
                    signalDb = signalDb,
                    progress = idx.toFloat() / frequencies.size
                )

                if (signalDb > squelchDb) {
                    hitsFound++
                    _activeFrequencies.emit(freq)
                    _status.value = _status.value.copy(activeFreqHz = freq, hitsFound = hitsFound)
                    delay(resumeTimeMs)
                }

                idx = (idx + 1) % frequencies.size
            }
        }
    }

    fun isScanning(): Boolean = scanJob?.isActive == true

    fun destroy() {
        scanJob?.cancel()
        scope.cancel()
    }
}
