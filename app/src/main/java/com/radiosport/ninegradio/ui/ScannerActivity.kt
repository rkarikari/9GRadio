package com.radiosport.ninegradio.ui

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.*
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.*
import com.radiosport.ninegradio.R
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.scanner.FrequencyScanner
import com.radiosport.ninegradio.usb.RtlSdrDevice
import com.radiosport.ninegradio.source.IqSource
import com.radiosport.ninegradio.usb.RtlSdrService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ScannerActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var scanner: FrequencyScanner? = null
    private val hitLog = mutableListOf<String>()
    private lateinit var hitAdapter: ArrayAdapter<String>

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

        val btnStartStop    = findViewById<Button>(R.id.btnScanStartStop)
        val btnPause        = findViewById<Button>(R.id.btnScanPause)
        val etStartFreq     = findViewById<EditText>(R.id.etScanStart)
        val etStopFreq      = findViewById<EditText>(R.id.etScanStop)
        val etStep          = findViewById<EditText>(R.id.etScanStep)
        val etSquelch       = findViewById<EditText>(R.id.etScanSquelch)
        val etDwell         = findViewById<EditText>(R.id.etScanDwell)
        val spinnerMode     = findViewById<Spinner>(R.id.spinnerScanMode)
        val progressScan    = findViewById<ProgressBar>(R.id.progressScan)
        val tvCurrentFreq   = findViewById<TextView>(R.id.tvScanCurrentFreq)
        val tvHits          = findViewById<TextView>(R.id.tvScanHits)
        val tvSignal        = findViewById<TextView>(R.id.tvScanSignal)
        val listHits        = findViewById<ListView>(R.id.listScanHits)
        val switchDirection = findViewById<Switch>(R.id.switchScanDirection)

        // Pre-fill from current ViewModel state
        etStartFreq.setText("%.4f".format(viewModel.centerFreqHz.value / 1e6))
        etSquelch.setText("-80")
        etDwell.setText("200")
        etStep.setText("12500")

        // Mode spinner
        val modes = DemodMode.values().map { it.displayName }
        spinnerMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modes)
        spinnerMode.setSelection(viewModel.demodMode.value.ordinal)

        // Hit log adapter
        hitAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, hitLog)
        listHits.adapter = hitAdapter
        listHits.setOnItemClickListener { _, _, pos, _ ->
            val text = hitLog[pos]
            val mhz = text.substringAfter("@ ").substringBefore(" MHz").toDoubleOrNull()
            if (mhz != null) {
                viewModel.setFrequency((mhz * 1_000_000).toLong())
                finish()
            }
        }

        var scanning = false
        var paused = false

        btnStartStop.setOnClickListener {
            if (!scanning) {
                val svc = getSdrService()
                if (svc == null) {
                    Toast.makeText(this, "No RTL-SDR connected", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val source = svc.source
                if (source == null) {
                    Toast.makeText(this, "No IQ source connected", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

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
                val snappedStartHz = com.radiosport.ninegradio.dsp.FrequencyStepManager
                    .snapToChannel(startHz, stepHz)
                    .coerceIn(RtlSdrDevice.MIN_FREQ_HZ, RtlSdrDevice.MAX_FREQ_HZ)
                val sqlDb   = etSquelch.text.toString().toFloatOrNull() ?: -80f
                val dwellMs = etDwell.text.toString().toLongOrNull() ?: 200L
                val modeIdx = spinnerMode.selectedItemPosition
                val mode    = DemodMode.values().getOrElse(modeIdx) { DemodMode.NFM }

                scanner = FrequencyScanner(source, signalLevelProvider = {
                    // FFT-peak dBFS from the live IQ stream — same scale as the
                    // spectrum display and squelch slider. The device's raw AGC
                    // register (the previous default) reflects the entire
                    // wideband capture, not the narrow channel being scanned, so
                    // narrowband signals (e.g. PMR446's 12.5 kHz channels) were
                    // never detected.
                    svc.dspEngine?.statsFlow?.value?.signalDb ?: source.statusFlow.value.signalStrengthDb
                }).also { sc ->
                    sc.startScan(FrequencyScanner.ScanConfig(
                        startFreqHz = snappedStartHz,
                        stopFreqHz  = stopHz,
                        stepHz = stepHz,
                        squelchDb = sqlDb,
                        dwellTimeMs = dwellMs,
                        mode = mode,
                        scanUp = !switchDirection.isChecked
                    ))
                    lifecycleScope.launch {
                        sc.status.collectLatest { status ->
                            progressScan.progress = (status.progress * 100).toInt()
                            tvCurrentFreq.text = "${"%.4f".format(status.currentFreqHz / 1e6)} MHz"
                            tvHits.text = "Hits: ${status.hitsFound}"
                            tvSignal.text = "${"%.1f".format(status.signalDb)} dB"
                        }
                    }
                    lifecycleScope.launch {
                        sc.activeFrequencies.collect { hz ->
                            val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                                .format(java.util.Date())
                            val entry = "[$ts]  ${"%.4f".format(hz / 1e6)} MHz"
                            hitLog.add(0, entry)
                            if (hitLog.size > 200) hitLog.removeLast()
                            hitAdapter.notifyDataSetChanged()
                        }
                    }
                }
                scanning = true
                btnStartStop.text = "Stop Scan"
                btnPause.isVisible = true
                progressScan.isVisible = true
            } else {
                scanner?.stopScan()
                scanner = null
                scanning = false
                paused = false
                btnStartStop.text = "Start Scan"
                btnPause.text = "Pause"
                btnPause.isVisible = false
                progressScan.isVisible = false
            }
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

    private fun getSdrService(): RtlSdrService? = sdrService
}
