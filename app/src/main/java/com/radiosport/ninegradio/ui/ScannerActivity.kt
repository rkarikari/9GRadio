package com.radiosport.ninegradio.ui

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.*
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.*
import com.radiosport.ninegradio.R
import com.radiosport.ninegradio.RtlSdrApplication
import com.radiosport.ninegradio.data.SignalLog
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.dsp.FrequencyStepManager
import com.radiosport.ninegradio.scanner.FrequencyScanner
import com.radiosport.ninegradio.usb.RtlSdrDevice
import com.radiosport.ninegradio.usb.RtlSdrService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Advanced scanner UI.
 *
 * Builds on [FrequencyScanner]'s adaptive-squelch / peak-hold / priority /
 * lockout engine:
 *  - "Adaptive" squelch toggle lets the user scan without hand-tuning an
 *    absolute dBFS threshold per band.
 *  - Every confirmed hit is persisted to [SignalLog] so it survives past the
 *    activity's lifetime and feeds any future "activity history" view.
 *  - Long-pressing a hit in the log locks that frequency out of the current
 *    scan (birdie / pager site suppression) instead of only being able to
 *    tune to it.
 *  - A "Busiest" button surfaces the scanner's live top-frequencies ranking.
 *  - A "Search" mode button runs a fast wideband discovery sweep separate
 *    from the channelized scan, for finding unknown activity.
 */
class ScannerActivity : AppCompatActivity() {

    private companion object {
        /** Number of stored scan-parameter presets offered in the Memory spinner. */
        const val MEMORY_SLOT_COUNT = 5
    }

    private val viewModel: MainViewModel by viewModels()
    private var scanner: FrequencyScanner? = null
    private val hitLog = mutableListOf<String>()
    private val hitLogFreqs = mutableListOf<Long>()
    private lateinit var hitAdapter: ArrayAdapter<String>

    private val db by lazy { (application as RtlSdrApplication).database }

    // ─── Service binding ──────────────────────────────────────────────────────

    private var sdrService: RtlSdrService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            sdrService = (binder as RtlSdrService.LocalBinder).getService()
            serviceBound = true
        }
        override fun onServiceDisconnected(name: ComponentName) {
            sdrService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scanner)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Frequency Scanner"

        // Bind to service
        bindService(
            Intent(this, RtlSdrService::class.java),
            serviceConnection,
            BIND_AUTO_CREATE
        )

        val btnStartStop     = findViewById<Button>(R.id.btnScanStartStop)
        val btnReset         = findViewById<Button>(R.id.btnScanReset)
        val btnPause         = findViewById<Button>(R.id.btnScanPause)
        val btnSearch        = findViewById<Button>(R.id.btnScanSearch)
        val btnBusiest       = findViewById<Button>(R.id.btnScanBusiest)
        val spinnerMemSlot   = findViewById<Spinner>(R.id.spinnerScanMemorySlot)
        val btnMemSave       = findViewById<Button>(R.id.btnScanMemSave)
        val btnMemLoad       = findViewById<Button>(R.id.btnScanMemLoad)
        val etStartFreq      = findViewById<EditText>(R.id.etScanStart)
        val etStopFreq       = findViewById<EditText>(R.id.etScanStop)
        val etStep           = findViewById<EditText>(R.id.etScanStep)
        val etSquelch        = findViewById<EditText>(R.id.etScanSquelch)
        val etDwell          = findViewById<EditText>(R.id.etScanDwell)
        val spinnerMode      = findViewById<Spinner>(R.id.spinnerScanMode)
        val progressScan     = findViewById<ProgressBar>(R.id.progressScan)
        val tvCurrentFreq    = findViewById<TextView>(R.id.tvScanCurrentFreq)
        val tvHits           = findViewById<TextView>(R.id.tvScanHits)
        val tvSignal         = findViewById<TextView>(R.id.tvScanSignal)
        val tvNoiseFloor     = findViewById<TextView>(R.id.tvScanNoiseFloor)
        val tvRate           = findViewById<TextView>(R.id.tvScanRate)
        val listHits         = findViewById<ListView>(R.id.listScanHits)
        val switchDirection  = findViewById<Switch>(R.id.switchScanDirection)
        val switchAdaptive   = findViewById<Switch>(R.id.switchScanAdaptive)

        // Pre-fill from current ViewModel state
        etStartFreq.setText("%.4f".format(viewModel.centerFreqHz.value / 1e6))
        etSquelch.setText("-80")
        etDwell.setText("200")
        etStep.setText("12500")

        // Mode spinner
        val modes = DemodMode.values().map { it.displayName }
        spinnerMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modes)
        spinnerMode.setSelection(viewModel.demodMode.value.ordinal)

        // Scan parameter memory slots — lets the user stash and recall a full
        // set of scan parameters (range/step/squelch/dwell/mode/direction/
        // adaptive) without retyping them each session.
        val memPrefs = getSharedPreferences("scan_memory_slots", MODE_PRIVATE)
        val memSlotLabels = (1..MEMORY_SLOT_COUNT).map { slot ->
            val saved = memPrefs.getString("slot_$slot", null)
            if (saved != null) "Slot $slot (saved)" else "Slot $slot (empty)"
        }
        val memSlotAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, memSlotLabels.toMutableList())
        spinnerMemSlot.adapter = memSlotAdapter

        fun refreshMemSlotLabels() {
            for (i in 0 until MEMORY_SLOT_COUNT) {
                val slot = i + 1
                val saved = memPrefs.getString("slot_$slot", null)
                memSlotAdapter.getItem(i)
                memSlotAdapter.remove(memSlotAdapter.getItem(i))
                memSlotAdapter.insert(if (saved != null) "Slot $slot (saved)" else "Slot $slot (empty)", i)
            }
            memSlotAdapter.notifyDataSetChanged()
        }

        btnMemSave.setOnClickListener {
            val slot = spinnerMemSlot.selectedItemPosition + 1
            val params = JSONObject().apply {
                put("startMhz", etStartFreq.text.toString())
                put("stopMhz", etStopFreq.text.toString())
                put("stepHz", etStep.text.toString())
                put("squelchDb", etSquelch.text.toString())
                put("dwellMs", etDwell.text.toString())
                put("modeIdx", spinnerMode.selectedItemPosition)
                put("scanDown", switchDirection.isChecked)
                put("adaptive", switchAdaptive.isChecked)
            }
            memPrefs.edit().putString("slot_$slot", params.toString()).apply()
            refreshMemSlotLabels()
            Toast.makeText(this, "Saved scan parameters to slot $slot", Toast.LENGTH_SHORT).show()
        }

        btnMemLoad.setOnClickListener {
            val slot = spinnerMemSlot.selectedItemPosition + 1
            val raw = memPrefs.getString("slot_$slot", null)
            if (raw == null) {
                Toast.makeText(this, "Slot $slot is empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            try {
                val params = JSONObject(raw)
                etStartFreq.setText(params.getString("startMhz"))
                etStopFreq.setText(params.getString("stopMhz"))
                etStep.setText(params.getString("stepHz"))
                etSquelch.setText(params.getString("squelchDb"))
                etDwell.setText(params.getString("dwellMs"))
                spinnerMode.setSelection(params.optInt("modeIdx", 0))
                switchDirection.isChecked = params.optBoolean("scanDown", false)
                switchAdaptive.isChecked = params.optBoolean("adaptive", false)
                Toast.makeText(this, "Loaded scan parameters from slot $slot", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Slot $slot is corrupted", Toast.LENGTH_SHORT).show()
            }
        }

        // Hit log adapter
        hitAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, hitLog)
        listHits.adapter = hitAdapter
        listHits.setOnItemClickListener { _, _, pos, _ ->
            val hz = hitLogFreqs.getOrNull(pos) ?: return@setOnItemClickListener
            viewModel.setFrequency(hz)
            finish()
        }
        listHits.setOnItemLongClickListener { _, _, pos, _ ->
            val hz = hitLogFreqs.getOrNull(pos) ?: return@setOnItemLongClickListener true
            AlertDialog.Builder(this)
                .setTitle("${"%.4f".format(hz / 1e6)} MHz")
                .setMessage("Lock this frequency out of the current scan? It will be skipped until the scan is stopped.")
                .setPositiveButton("Lock out") { _, _ ->
                    scanner?.lockout(hz)
                    Toast.makeText(this, "Locked out ${"%.4f".format(hz / 1e6)} MHz", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }

        var scanning = false
        var paused = false
        var searching = false

        fun resetControlsToIdle() {
            scanning = false
            paused = false
            searching = false
            btnStartStop.text = "Start Scan"
            btnPause.text = "Pause"
            btnPause.isVisible = false
            btnSearch.text = "Search Mode"
            progressScan.isVisible = false
        }

        fun getSourceOrToast(): Pair<RtlSdrService, com.radiosport.ninegradio.source.IqSource>? {
            val svc = sdrService
            if (svc == null) {
                Toast.makeText(this, "No RTL-SDR connected", Toast.LENGTH_SHORT).show()
                return null
            }
            val source = svc.source
            if (source == null) {
                Toast.makeText(this, "No IQ source connected", Toast.LENGTH_SHORT).show()
                return null
            }
            return svc to source
        }

        fun buildSignalLevelProvider(svc: RtlSdrService, source: com.radiosport.ninegradio.source.IqSource): () -> Float = {
            // FFT-peak dBFS from the live IQ stream — same scale as the
            // spectrum display and squelch slider. The device's raw AGC
            // register (the previous default) reflects the entire wideband
            // capture, not the narrow channel being scanned, so narrowband
            // signals (e.g. PMR446's 12.5 kHz channels) were never detected.
            svc.dspEngine?.statsFlow?.value?.signalDb ?: source.statusFlow.value.signalStrengthDb
        }

        fun attachCollectors(sc: FrequencyScanner) {
            lifecycleScope.launch {
                sc.status.collectLatest { status ->
                    progressScan.progress = (status.progress * 100).toInt()
                    tvCurrentFreq.text = "${"%.4f".format(status.currentFreqHz / 1e6)} MHz"
                    tvHits.text = "Hits: ${status.hitsFound}"
                    tvSignal.text = "${"%.1f".format(status.signalDb)} dB"
                    tvNoiseFloor.text = "Floor: ${"%.1f".format(status.noiseFloorDb)} dB"
                    tvRate.text = "${"%.1f".format(status.channelsPerSecond)} ch/s"
                }
            }
            lifecycleScope.launch {
                sc.hits.collect { hit ->
                    val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                        .format(java.util.Date(hit.timestampMs))
                    val tag = when (hit.source) {
                        FrequencyScanner.HitSource.PRIORITY -> " ★"
                        FrequencyScanner.HitSource.SEARCH -> " ?"
                        else -> ""
                    }
                    val entry = "[$ts]  ${"%.4f".format(hit.freqHz / 1e6)} MHz  " +
                        "${"%.0f".format(hit.signalDb)}dB$tag"
                    hitLog.add(0, entry)
                    hitLogFreqs.add(0, hit.freqHz)
                    if (hitLog.size > 300) {
                        hitLog.removeAt(hitLog.size - 1)
                        hitLogFreqs.removeAt(hitLogFreqs.size - 1)
                    }
                    hitAdapter.notifyDataSetChanged()

                    // Persist every confirmed hit so it survives this
                    // activity's lifetime and can feed a future history view.
                    lifecycleScope.launch {
                        db.signalLogDao().insert(
                            SignalLog(
                                frequencyHz = hit.freqHz,
                                demodMode = hit.mode.name,
                                signalDb = hit.signalDb,
                                timestamp = hit.timestampMs,
                                notes = hit.source.name.lowercase()
                            )
                        )
                    }
                }
            }
        }

        btnStartStop.setOnClickListener {
            if (!scanning) {
                val (svc, source) = getSourceOrToast() ?: return@setOnClickListener

                val startMhz = etStartFreq.text.toString().toDoubleOrNull() ?: 136.0
                val stopMhz  = etStopFreq.text.toString().toDoubleOrNull()
                    ?: (startMhz + 38.0)
                val startHz = (startMhz * 1_000_000).toLong()
                    .coerceIn(RtlSdrDevice.MIN_FREQ_HZ, RtlSdrDevice.MAX_FREQ_HZ)
                val stopHz  = (stopMhz * 1_000_000).toLong()
                    .coerceIn(startHz + 1, RtlSdrDevice.MAX_FREQ_HZ)
                val stepHz  = etStep.text.toString().toLongOrNull() ?: 12_500L
                // Snap the start to the nearest channel-grid point for this step
                // size (using the matching band preset's channel offset, if any).
                // Without this, e.g. PMR446 entered as 446.000–446.200 @ 12.5 kHz
                // sweeps 446.0000, 446.0125, 446.0250 … — exactly 6.25 kHz off
                // every real channel center (446.00625, 446.01875 … 446.19375)
                // and never detects any traffic.
                val snappedStartHz = FrequencyStepManager
                    .snapToChannel(startHz, stepHz)
                    .coerceIn(RtlSdrDevice.MIN_FREQ_HZ, RtlSdrDevice.MAX_FREQ_HZ)
                val sqlDb   = etSquelch.text.toString().toFloatOrNull() ?: -80f
                val dwellMs = etDwell.text.toString().toLongOrNull() ?: 200L
                val modeIdx = spinnerMode.selectedItemPosition
                val mode    = DemodMode.values().getOrElse(modeIdx) { DemodMode.NFM }
                val adaptive = switchAdaptive.isChecked

                scanner = FrequencyScanner(source, signalLevelProvider = buildSignalLevelProvider(svc, source))
                    .also { sc ->
                        sc.startScan(FrequencyScanner.ScanConfig(
                            startFreqHz = snappedStartHz,
                            stopFreqHz  = stopHz,
                            stepHz = stepHz,
                            squelchDb = sqlDb,
                            adaptiveSquelch = adaptive,
                            dwellTimeMs = dwellMs,
                            mode = mode,
                            scanUp = !switchDirection.isChecked,
                            label = "${"%.3f".format(startMhz)}-${"%.3f".format(stopMhz)} MHz"
                        ))
                        attachCollectors(sc)
                    }
                scanning = true
                btnStartStop.text = "Stop Scan"
                btnPause.isVisible = true
                progressScan.isVisible = true
                btnSearch.isEnabled = false
            } else {
                scanner?.stopScan()
                scanner = null
                btnSearch.isEnabled = true
                resetControlsToIdle()
            }
        }

        btnReset.setOnClickListener {
            // Stop any in-progress scan/search so the reset state is clean.
            scanner?.stopScan()
            scanner?.destroy()
            scanner = null
            btnStartStop.isEnabled = true
            btnSearch.isEnabled = true
            resetControlsToIdle()

            // Clear the scan results list.
            hitLog.clear()
            hitLogFreqs.clear()
            hitAdapter.notifyDataSetChanged()

            // Reset the live status readouts.
            progressScan.progress = 0
            tvCurrentFreq.text = "---"
            tvHits.text = "Hits: 0"
            tvSignal.text = "--- dB"
            tvNoiseFloor.text = "Floor: --- dB"
            tvRate.text = "--- ch/s"

            Toast.makeText(this, "Scan list reset", Toast.LENGTH_SHORT).show()
        }

        btnPause.setOnClickListener {
            if (!paused) {
                scanner?.pauseScan()
                btnPause.text = "Resume"
                paused = true
            } else {
                scanner?.resumeScan()
                btnPause.text = "Pause"
                paused = false
            }
        }

        // Fast wideband search mode — separate from the channelized sweep,
        // for finding unknown activity before setting up a scan list.
        btnSearch.setOnClickListener {
            if (!searching) {
                val (svc, source) = getSourceOrToast() ?: return@setOnClickListener
                val startMhz = etStartFreq.text.toString().toDoubleOrNull() ?: 136.0
                val stopMhz  = etStopFreq.text.toString().toDoubleOrNull() ?: (startMhz + 38.0)
                val startHz = (startMhz * 1_000_000).toLong().coerceIn(RtlSdrDevice.MIN_FREQ_HZ, RtlSdrDevice.MAX_FREQ_HZ)
                val stopHz  = (stopMhz * 1_000_000).toLong().coerceIn(startHz + 1, RtlSdrDevice.MAX_FREQ_HZ)
                val modeIdx = spinnerMode.selectedItemPosition
                val mode    = DemodMode.values().getOrElse(modeIdx) { DemodMode.NFM }

                scanner = FrequencyScanner(source, signalLevelProvider = buildSignalLevelProvider(svc, source))
                    .also { sc ->
                        sc.startSearch(startFreqHz = startHz, stopFreqHz = stopHz, mode = mode)
                        attachCollectors(sc)
                    }
                searching = true
                scanning = true
                btnSearch.text = "Stop Search"
                btnStartStop.isEnabled = false
                progressScan.isVisible = true
            } else {
                scanner?.stopScan()
                scanner = null
                btnStartStop.isEnabled = true
                resetControlsToIdle()
            }
        }

        btnBusiest.setOnClickListener {
            val sc = scanner
            if (sc == null) {
                Toast.makeText(this, "Start a scan first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val top = sc.topFrequencies(15)
            if (top.isEmpty()) {
                Toast.makeText(this, "No hits recorded yet", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val message = top.joinToString("\n") { (hz, count) ->
                "${"%.4f".format(hz / 1e6)} MHz  —  $count hit${if (count == 1) "" else "s"}"
            }
            AlertDialog.Builder(this)
                .setTitle("Busiest Frequencies")
                .setMessage(message)
                .setPositiveButton("Close", null)
                .show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scanner?.stopScan()
        scanner?.destroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
