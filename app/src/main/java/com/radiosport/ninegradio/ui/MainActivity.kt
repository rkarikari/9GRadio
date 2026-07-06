package com.radiosport.ninegradio.ui

import android.Manifest
import android.content.*
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.hardware.usb.*
import android.os.*
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.chip.Chip
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.radiosport.ninegradio.R
import com.radiosport.ninegradio.toExactMhzString
import com.radiosport.ninegradio.data.FrequencyDatabase
import com.radiosport.ninegradio.data.RecordingMeta
import org.json.JSONObject
import com.radiosport.ninegradio.databinding.ActivityMainBinding
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.dsp.DspEngine
import com.radiosport.ninegradio.usb.RtlSdrDevice
import com.radiosport.ninegradio.usb.RtlSdrService
import com.radiosport.ninegradio.source.IqSource
import com.radiosport.ninegradio.source.RtlSdrDriverApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.radiosport.ninegradio.dsp.FrequencyStepManager
import com.radiosport.ninegradio.dsp.AprsDecoder

/** Vocoder status label — mbelib-neo is statically linked and always active. */
private const val VOCODER_STATUS_TEXT = "Vocoder: mbelib-neo built-in active"

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_PERMISSIONS = 1001
    }

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private var sdrService: RtlSdrService? = null
    private var bound = false
    private var vmBound = false

    // Saved nav-bar inset so applyExpandedOffset can include it in the
    // single paddingBottom call it makes on the NestedScrollView.
    private var navBarHeightPx = 0

    // Track pending permission receiver so it can be cleaned up in onDestroy.
    private var pendingPermReceiver: BroadcastReceiver? = null

    /**
     * Set to true while an intentional USB → Rtl-sdr Driver switch is in progress.
     * During this window, [handleUsbDeviceAttached] is a no-op so that the USB
     * re-enumeration Android fires after we release the interface does not
     * immediately pull the source back to USB and undo the switch.
     * Cleared in onResume() once the TCP connection to the driver app is initiated.
     */
    private var suppressUsbAttach = false


    // Long-press repeat state for frequency +/− buttons.
    private var freqRepeatJob: Job? = null
    private var freqRepeatFired = false

    // Tabbed controls drawer
    private lateinit var pagerAdapter: ControlsPagerAdapter
    private lateinit var ctrlViews: ControlsTabManager

    // Snap-to-channel preference (synced with the Tune tab switch)
    private var snapToChannel: Boolean = true

    // Sample-rate / audio-sink chip label caches, populated once in
    // setupControlsPanelDeferred() and reused by syncTabControls() so the
    // RF tab's chip-selection highlight can be refreshed without rebuilding
    // the chip rows every time the tab is brought back into view.
    private var sampleRateLabelsCache: List<String> = emptyList()
    private var audioSinkLabelsCache: List<String> = emptyList()

    // ─── APRS drawer tab state ────────────────────────────────────────────────
    private val aprsPackets      = mutableListOf<Pair<AprsDecoder.AprsPacket, String>>()
    private val aprsDisplayList  = mutableListOf<String>()
    private var aprsListAdapter: ArrayAdapter<String>? = null
    private var aprsCollectJob: Job? = null

    // ─── ACARS drawer tab state ───────────────────────────────────────────────
    private val acarsMessages     = mutableListOf<com.radiosport.ninegradio.dsp.AcarsDecoder.AcarsMessage>()
    private val acarsDisplayList  = mutableListOf<String>()
    private var acarsListAdapter: ArrayAdapter<String>? = null
    private var acarsCollectJob: Job? = null

    // ─── DMR drawer tab state ─────────────────────────────────────────────────
    // dmrCalls groups consecutive frames of the same transmission into one
    // "call" entry (see RecentCallTracker) -- the drawer's list is a recent-
    // calls list, not a recent-frames list, for every digital voice mode
    // (APRS is a packet mode, not voice, and keeps its own separate packet
    // list untouched -- see aprsPackets above).
    private val dmrCalls        = com.radiosport.ninegradio.dsp.RecentCallTracker()
    private val dmrDisplayList  = mutableListOf<String>()
    private var dmrListAdapter: ArrayAdapter<String>? = null
    private var dmrCollectJob: Job? = null
    private var dmrFrameCount = 0

    // ─── Dig drawer tab state ─────────────────────────────────────────────────
    private val digCalls        = com.radiosport.ninegradio.dsp.RecentCallTracker()
    private val digDisplayList  = mutableListOf<String>()
    private var digListAdapter: ArrayAdapter<String>? = null
    private var digCollectJob: Job? = null
    private var digFrameCount = 0

    // ─── YSF drawer tab state ─────────────────────────────────────────────────
    private val ysfCalls        = com.radiosport.ninegradio.dsp.RecentCallTracker()
    private val ysfDisplayList  = mutableListOf<String>()
    private var ysfListAdapter: ArrayAdapter<String>? = null
    private var ysfCollectJob: Job? = null
    private var ysfFrameCount = 0

    // ─── D-STAR drawer tab state ──────────────────────────────────────────────
    private val dstarCalls        = com.radiosport.ninegradio.dsp.RecentCallTracker()
    private val dstarDisplayList  = mutableListOf<String>()
    private var dstarListAdapter: ArrayAdapter<String>? = null
    private var dstarCollectJob: Job? = null
    private var dstarFrameCount = 0

    // ─── Scan drawer tab state ────────────────────────────────────────────────
    // Unlike the protocol tabs above, this tab isn't tied to a DemodMode -- it
    // is shown/hidden on demand via showScanTab()/hideScanTab(), invoked from
    // the Settings tab's Scanner button and the main FAB.
    private var scanTabScanner: com.radiosport.ninegradio.scanner.FrequencyScanner? = null
    private val scanTabHitLog = mutableListOf<String>()
    private val scanTabHitFreqs = mutableListOf<Long>()
    private var scanTabHitAdapter: ArrayAdapter<String>? = null
    private val scanTabChannelRows = mutableListOf<com.radiosport.ninegradio.scanner.FrequencyScanner.ChannelEntry>()
    private var scanTabChannelAdapter: ScanChannelTableAdapter? = null
    private var scanTabChannelTableJob: Job? = null
    private var scanTabStatusJob: Job? = null
    private var scanTabHitsJob: Job? = null

    /**
     * Everything [configureSpectrumForScan] and the scan handlers' own
     * `svc.setDemodMode` / `svc.setSquelch` calls are about to override,
     * captured right before a scan/search starts so it can all be put back
     * exactly as it was once the scan stops.
     *
     * This matters because none of those calls go through
     * [MainViewModel.setDemodMode] (which is what normally snapshots a
     * mode's settings on the way out) -- scanning deliberately bypasses it so
     * starting a scan doesn't itself count as "switching modes". But that
     * also means nothing else will restore these values afterward, and if
     * the user switches modes *while* the scan-tuned sample rate/decimation/
     * FFT size/frequency are still live, [MainViewModel.setDemodMode] would
     * snapshot *those* into the current mode's saved settings, silently
     * overwriting whatever the user had actually configured for it. Taking
     * our own snapshot and restoring it the moment the scan ends closes that
     * window.
     */
    private data class ScanRfSnapshot(
        val centerFreqHz: Long,
        val sampleRate: Int,
        val decimation: Int,
        val fftSize: Int,
        val frameAveraging: Int,
        val autoRange: Boolean,
        val demodMode: DemodMode,
        val squelchDb: Float,
        val demodChannelBwHz: Long
    )
    private var scanTabPreScanSnapshot: ScanRfSnapshot? = null

    /** Capture the live RF/display state the instant before a scan/search
     *  starts. No-op (keeps the existing snapshot) if a scan is already
     *  running, so Search-while-scanning style re-entry can't clobber the
     *  original pre-scan snapshot with already-scan-tuned values. */
    private fun captureRfSnapshotBeforeScan() {
        if (scanTabPreScanSnapshot != null) return
        val displayPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        scanTabPreScanSnapshot = ScanRfSnapshot(
            centerFreqHz = viewModel.centerFreqHz.value,
            sampleRate = viewModel.sampleRate.value,
            decimation = viewModel.decimation.value,
            fftSize = viewModel.fftSize.value,
            frameAveraging = displayPrefs.getString("pref_frame_averaging", "1")?.toIntOrNull() ?: 1,
            autoRange = displayPrefs.getBoolean("pref_auto_range", false),
            demodMode = viewModel.demodMode.value,
            squelchDb = viewModel.squelch.value,
            demodChannelBwHz = channelHighlightHz(viewModel.demodMode.value, viewModel.ifBandwidthHz.value)
        )
    }

    /** Put back everything [captureRfSnapshotBeforeScan] captured. Called
     *  once a scan/search actually stops (Stop/Reset/Close) -- never mid-scan,
     *  so pausing doesn't lose the live scan state. Safe to call with no
     *  snapshot pending (e.g. the tab was closed without ever starting a scan). */
    private fun restoreRfSnapshotAfterScan() {
        val snap = scanTabPreScanSnapshot ?: return
        scanTabPreScanSnapshot = null

        val svc = sdrService
        svc?.setDemodMode(snap.demodMode)
        viewModel.setSquelch(snap.squelchDb)

        if (snap.sampleRate != viewModel.sampleRate.value) {
            viewModel.setSampleRate(snap.sampleRate)
            val idx = RtlSdrDevice.SAMPLE_RATES.indexOfFirst { it == viewModel.sampleRate.value }
            if (idx >= 0) {
                sampleRateLabelsCache.getOrNull(idx)?.let { ctrlViews.tvSampleRateLabel?.text = it }
                updateSampleRateChips(idx)
            }
        }
        if (snap.decimation != viewModel.decimation.value) {
            viewModel.setDecimation(snap.decimation)
            val decimationFactors = listOf(1, 2, 4, 8, 16, 32, 64)
            decimationFactors.indexOf(snap.decimation).takeIf { it >= 0 }
                ?.let { ctrlViews.spinnerDecimation?.setSelection(it, false) }
        }
        val effectiveRate = viewModel.sampleRate.value / viewModel.decimation.value.coerceAtLeast(1)
        binding.spectrumView.setSampleRate(effectiveRate)
        binding.waterfallView.setSampleRate(effectiveRate)

        if (snap.fftSize != viewModel.fftSize.value) {
            viewModel.setFftSize(snap.fftSize)
            val fftSizes = listOf(256, 512, 1024, 2048, 4096, 8192)
            fftSizes.indexOf(snap.fftSize).takeIf { it >= 0 }
                ?.let { ctrlViews.spinnerFftSize?.setSelection(it, false) }
        }

        svc?.dspEngine?.fftEngine?.frameAveragingCount = snap.frameAveraging
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            .edit().putString("pref_frame_averaging", snap.frameAveraging.toString()).apply()
        val frameAvgValues = listOf(1, 2, 4, 8, 16, 32)
        frameAvgValues.indexOf(snap.frameAveraging).takeIf { it >= 0 }
            ?.let { ctrlViews.spinnerFrameAvg?.setSelection(it, false) }

        binding.spectrumView.setAutoRange(snap.autoRange)
        ctrlViews.switchAutoRange?.isChecked = snap.autoRange
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            .edit().putBoolean("pref_auto_range", snap.autoRange).apply()

        viewModel.setFrequency(snap.centerFreqHz)
        binding.spectrumView.setCenterFrequency(hardwareCenterFreqHz(snap.centerFreqHz, snap.demodMode))
        binding.spectrumView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(snap.centerFreqHz, snap.demodMode))
        binding.waterfallView.setCenterFrequency(hardwareCenterFreqHz(snap.centerFreqHz, snap.demodMode))
        binding.waterfallView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(snap.centerFreqHz, snap.demodMode))

        binding.spectrumView.setSquelch(snap.squelchDb)
        binding.spectrumView.setDemodChannelBandwidth(
            snap.demodChannelBwHz, channelHighlightSideband(snap.demodMode))
    }
    private var scanTabScanning = false
    private var scanTabPaused = false
    private var scanTabSearching = false

    // ─── Rec tab recordings list state ───────────────────────────────────────
    // Displays the same recordings the standalone RecordingActivity shows, but
    // inline at the bottom of the Rec tab so they're visible without leaving
    // the drawer. Container view stays GONE until at least one recording exists.
    private var recordingsTabAdapter: RecordingAdapter? = null

    // Launcher for vocoder plugin file picker (ACTION_OPEN_DOCUMENT).
    // Using registerForActivityResult avoids the deprecated startActivityForResult path
    // and correctly holds the URI grant across the picker round-trip.
    // Launcher for BookmarkActivity -- receives the selected frequency via setResult.
    private val bookmarkLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val hz = result.data?.getLongExtra(BookmarkActivity.EXTRA_FREQ_HZ, -1L) ?: -1L
            if (hz > 0L) {
                viewModel.setFrequency(hz)
                binding.frequencyView.setFrequency(hz)
                binding.spectrumView.setCenterFrequency(hardwareCenterFreqHz(hz))
                binding.spectrumView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(hz))
                binding.spectrumView.setDemodDialFrequency(hz)
                binding.waterfallView.setCenterFrequency(hardwareCenterFreqHz(hz))
                binding.waterfallView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(hz))
                showSnackbar("Tuned to ${"%.3f".format(hz / 1e6)} MHz")
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            sdrService = (binder as RtlSdrService.LocalBinder).getService()
            bound = true
            // If device is already connected (ViewModel connected it before us), apply settings now.
            if (viewModel.connectionState.value is RtlSdrService.ConnectionState.Connected) {
                if (viewModel.isV4 && !viewModel.allowOutOfBand.value) {
                    viewModel.setAllowOutOfBand(true)
                    ctrlViews.switchOutOfBound?.isChecked = true
                }
                applyAllSettings()
                return   // device already open -- do NOT call handleUsbDeviceAttached again
            }
            // Only scan for already-attached devices if the ViewModel has NOT already
            // initiated a connection.  If the ViewModel is in Connecting state the race
            // is already won -- wait for the connectionState observer to call applyAllSettings.
            if (viewModel.connectionState.value is RtlSdrService.ConnectionState.Disconnected) {
                val usbManager = getSystemService(UsbManager::class.java)
                usbManager.deviceList.values
                    .firstOrNull { RtlSdrDevice.isSupported(it) }
                    ?.let { handleUsbDeviceAttached(it) }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            sdrService = null; bound = false
        }
    }

    // USB receiver for hotplug detection.
    // Must use RECEIVER_EXPORTED: ACTION_USB_DEVICE_ATTACHED / DETACHED are system broadcasts
    // sent from outside the app process, so RECEIVER_NOT_EXPORTED silently drops them on API 31+.
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val dev = intent.usbDevice()
                    if (dev != null && RtlSdrDevice.isSupported(dev)) {
                        handleUsbDeviceAttached(dev)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    showSnackbar("RTL-SDR disconnected")
                    updateConnectionUi(false)
                }
                UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> {}
            }
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Enable edge-to-edge so the layout extends behind system bars.
        // We then apply insets manually to the chip bar so it's never hidden
        // by the navigation bar on Android 15+ (target SDK 35 enforces edge-to-edge).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Use the navigation-bar inset to size the collapsed peek height so the
        // tab bar is always fully clear of the system navigation bar. Note: we
        // intentionally do NOT add this as bottom padding on controlTabLayout --
        // it has a fixed height (44dp), and on devices with a large gesture-nav
        // inset that padding can consume the entire height, clipping the tab
        // labels to invisible. The extra peekHeight margin alone is enough to
        // keep the tab bar above the nav bar.
        ViewCompat.setOnApplyWindowInsetsListener(binding.controlTabLayout) { view, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            // peekHeight: handle(4) + marginTop(6) + marginBottom(2) + tab bar(44) = ~56dp
            // Add nav bar inset as breathing room below the tab bar.
            val density = resources.displayMetrics.density
            val peekPx = (56 * density).toInt() + navBar.bottom
            val behavior = BottomSheetBehavior.from(binding.controlsBottomSheet)
            behavior.peekHeight = peekPx
            // Save for use in applyExpandedOffset -- do NOT set sheet paddingBottom here;
            // applyExpandedOffset owns that to avoid the two callbacks fighting.
            navBarHeightPx = navBar.bottom
            insets
        }

        setupToolbar()
        setupSpectrumView()
        setupWaterfallView()
        setupFrequencyView()
        setupControlsPanel()
        setupBottomSheet()
        setupFabMenu()
        observeViewModel()

        // Request dangerous runtime permissions the app needs.
        // RECORD_AUDIO  - declared in manifest; request so AudioEngine works.
        // POST_NOTIFICATIONS (API 33+) - required to show the foreground-service
        //   notification on Android 13 and above.
        requestInitialPermissions()

        // Bind to SDR service
        val serviceIntent = Intent(this, RtlSdrService::class.java).setAction(RtlSdrService.ACTION_START)
        startForegroundService(serviceIntent)
        vmBound = bindService(Intent(this, RtlSdrService::class.java), viewModel.serviceConnection, BIND_AUTO_CREATE)
        bindService(Intent(this, RtlSdrService::class.java), serviceConnection, BIND_AUTO_CREATE)

        // Register USB receiver.
        // RECEIVER_EXPORTED is required: USB attach/detach broadcasts are sent by
        // the system process, which is outside this app's UID. RECEIVER_NOT_EXPORTED
        // silently drops cross-UID broadcasts on API 31+.
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

        // Handle launch-from-USB intent (app started cold by USB attach)
        intent?.usbDevice()?.let {
            if (RtlSdrDevice.isSupported(it)) handleUsbDeviceAttached(it)
        }
    }

    /**
     * Request dangerous permissions needed at launch.
     * Only requests each one if not already granted.
     */
    private fun requestInitialPermissions() {
        val needed = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }

        // POST_NOTIFICATIONS is a runtime permission starting from Android 13 (API 33).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            permissions.forEachIndexed { i, perm ->
                if (grantResults.getOrElse(i) { PackageManager.PERMISSION_DENIED }
                        == PackageManager.PERMISSION_DENIED) {
                    when (perm) {
                        Manifest.permission.RECORD_AUDIO ->
                            showSnackbar("Audio permission denied - audio output will be silent")
                        Manifest.permission.POST_NOTIFICATIONS ->
                            showSnackbar("Notification permission denied - service notification hidden")
                    }
                }
            }
        }
    }

    /**
     * singleTask activities receive subsequent USB_DEVICE_ATTACHED intents here
     * (not in onCreate) when the app is already in the foreground.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.usbDevice()?.let {
            if (RtlSdrDevice.isSupported(it) && !suppressUsbAttach) handleUsbDeviceAttached(it)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cancel any pending timed-shutdown countdown.
        shutdownRunnable?.let { shutdownHandler.removeCallbacks(it) }
        shutdownRunnable = null
        stopAprsCollect()
        stopDmrCollect()
        stopYsfCollect()
        stopDstarCollect()
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
        if (vmBound) {
            unbindService(viewModel.serviceConnection)
            vmBound = false
        }
        unregisterReceiver(usbReceiver)
        // Clean up any pending USB permission receiver to avoid leaks.
        pendingPermReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
            pendingPermReceiver = null
        }
    }

    override fun onResume() {
        super.onResume()
        applyDisplayPreferences()
        ContextCompat.registerReceiver(
            this, displayPrefsReceiver,
            android.content.IntentFilter(SettingsFragment.ACTION_DISPLAY_PREFS_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Re-push the decimation factor to the DSP engine every time the app
        // returns from the background.  The service may have lost the pending
        // value while the process was suspended (the pending field is in-memory
        // only and is not re-applied when the engine restarts without a full
        // service unbind/rebind cycle).  viewModel.decimation is persisted in
        // SharedPreferences and is always the authoritative source of truth.
        val savedDecim = viewModel.decimation.value
        sdrService?.setFftDecimation(savedDecim)
        // Also ensure the spinner reflects the correct position (it may have
        // been reset to 0 if the fragment was recreated while backgrounded).
        val decimationFactors = listOf(1, 2, 4, 8, 16, 32, 64)
        val idx = decimationFactors.indexOf(savedDecim).takeIf { it >= 0 } ?: 0
        ctrlViews.spinnerDecimation?.setSelection(idx, false)
        // Update displayed effective sample rate immediately.
        val effectiveRate = viewModel.sampleRate.value / savedDecim.coerceAtLeast(1)
        binding.spectrumView.setSampleRate(effectiveRate)
        binding.waterfallView.setSampleRate(effectiveRate)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(displayPrefsReceiver)
        // Safety net: capture whatever is currently in the scan tab's fields
        // even if the user edited them but never pressed Start/Search before
        // backgrounding or leaving the app, so those edits aren't lost.
        if (ctrlViews.listScanTabHits != null) saveLastScanTabSettings()
    }

    /**
     * Called when the RTLSDR Driver app (DeviceOpenActivity) returns.
     * RESULT_OK means the rtl_tcp server is running -- connect immediately.
     * RESULT_CANCELED means the user cancelled or an error occurred.
     */
    @Deprecated("Using legacy onActivityResult for rtl_tcp_andro iqsrc:// protocol")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != RtlSdrDriverApp.REQUEST_CODE) return

        suppressUsbAttach = false

        if (resultCode == android.app.Activity.RESULT_OK) {
            showSnackbar("Rtl-sdr Driver ready -- connecting…")
            viewModel.connectTcp(RtlSdrDriverApp.DEFAULT_ADDRESS, RtlSdrDriverApp.DEFAULT_PORT)
        } else {
            // Driver returned failure -- read the error code if available
            val errorId = data?.getIntExtra("marto.rtl_tcp_andro.RtlTcpExceptionId", -1) ?: -1
            val reason = if (errorId >= 0) " (error $errorId)" else ""
            showSnackbar("Rtl-sdr Driver failed to start$reason")
            // Revert the switch back to USB in the open dialog (if still showing)
            // This is best-effort; the switch state is local to the dialog.
        }
    }

    /** Receiver for real-time display-preference changes from SettingsFragment. */
    private val displayPrefsReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
            applyDisplayPreferences()
        }
    }

    /**
     * Reads all display-related preferences (theme, palette, dB range, waterfall speed)
     * from the default SharedPreferences and applies them to the Views.
     * Called on every onResume and on broadcast from SettingsFragment.
     */
    private fun applyDisplayPreferences() {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)

        // FFT size -- synchronise Settings pref with ViewModel and drawer spinner.
        // pref_fft_size (default prefs) is the authoritative source when changed
        // from Settings; we push it into the ViewModel so the drawer spinner and
        // DspEngine/FftEngine are all kept in step.
        val fftSizes = listOf(256, 512, 1024, 2048, 4096, 8192)
        val fftSizeFromSettings = prefs.getString("pref_fft_size", null)?.toIntOrNull()
        if (fftSizeFromSettings != null && fftSizeFromSettings != viewModel.fftSize.value) {
            viewModel.setFftSize(fftSizeFromSettings)
            val idx = fftSizes.indexOf(fftSizeFromSettings).takeIf { it >= 0 } ?: -1
            if (idx >= 0) ctrlViews.spinnerFftSize?.setSelection(idx, false)
        }

        // Decimation -- synchronise Settings pref with ViewModel and drawer spinner.
        val decimationFactors = listOf(1, 2, 4, 8, 16, 32, 64)
        val decimFromSettings = prefs.getString("pref_decimation", null)?.toIntOrNull()
        if (decimFromSettings != null && decimFromSettings != viewModel.decimation.value) {
            viewModel.setDecimation(decimFromSettings)
            val dIdx = decimationFactors.indexOf(decimFromSettings).takeIf { it >= 0 } ?: -1
            if (dIdx >= 0) ctrlViews.spinnerDecimation?.setSelection(dIdx, false)
            // Update views immediately with new effective rate
            val effectiveRate = viewModel.sampleRate.value / decimFromSettings.coerceAtLeast(1)
            binding.spectrumView.setSampleRate(effectiveRate)
            binding.waterfallView.setSampleRate(effectiveRate)
        }

        // Spectrum theme
        val theme = prefs.getString("pref_spectrum_theme", "Futuristic") ?: "Futuristic"
        binding.spectrumView.setTheme(theme)
        viewModel.setSpectrumTheme(theme)

        // dB range: preferences store pref_db_min as positive ×−1, pref_db_max as 0..n
        // These are now also saved/restored per-protocol by MainViewModel.
        val dbMinRaw  = prefs.getInt("pref_db_min", 120)
        val dbMaxRaw  = prefs.getInt("pref_db_max", 0)
        val dbMin     = -(dbMinRaw.toFloat()).coerceIn(10f, 200f)
        val dbMax     = dbMaxRaw.toFloat().coerceIn(-100f, 50f)
        binding.spectrumView.setDbRange(dbMin, dbMax)
        binding.waterfallView.setDynamicRange(dbMin, dbMax)
        // Sync the Display-tab sliders if the tab is already inflated
        ctrlViews.sliderSpecFloor?.value = dbMin
        ctrlViews.tvSpecFloorValue?.text = dbMin.toInt().toString()
        ctrlViews.sliderSpecCeiling?.value = dbMax
        ctrlViews.tvSpecCeilingValue?.text = dbMax.toInt().toString()

        // Frame averaging: also per-protocol, pick up the restored pref
        val restoredFrameAvg = prefs.getString("pref_frame_averaging", "1")?.toIntOrNull() ?: 1
        sdrService?.dspEngine?.fftEngine?.frameAveragingCount = restoredFrameAvg

        // Waterfall palette
        val paletteName = prefs.getString("pref_waterfall_palette", "RAINBOW") ?: "RAINBOW"
        try {
            val palette = WaterfallView.Companion.Palette.valueOf(paletteName)
            binding.waterfallView.setPalette(palette)
            viewModel.setWaterfallPalette(palette)
        } catch (_: IllegalArgumentException) {}

        // Waterfall speed
        val speed = prefs.getInt("pref_waterfall_speed", 25)
        binding.waterfallView.setSpeed(speed)

        // Peak hold
        val peakHold = prefs.getBoolean("pref_peak_hold", true)
        binding.spectrumView.setShowPeakHold(peakHold)
        sdrService?.dspEngine?.fftEngine?.showPeakHold = peakHold

        // FFT smoothing (propagate to engine in case MainActivity was paused while in Settings)
        val smoothing = prefs.getInt("pref_fft_smoothing", 30)
        sdrService?.dspEngine?.setFftSmoothing(smoothing / 100f)

        // ── Advanced spectrum / waterfall preferences ──────────────────────────

        // Frame averaging (N-frame power accumulation before display)
        val frameAvg = prefs.getString("pref_frame_averaging", "1")?.toIntOrNull() ?: 1
        sdrService?.dspEngine?.fftEngine?.frameAveragingCount = frameAvg

        // Peak-hold decay rate (SeekBar 1-20 → 0.001-0.020 dB/frame)
        val peakDecay = prefs.getInt("pref_peak_decay", 2)
        sdrService?.dspEngine?.fftEngine?.peakDecayRate = peakDecay * 0.001f

        // Noise floor line
        val showNf = prefs.getBoolean("pref_show_noise_floor", true)
        binding.spectrumView.setShowNoiseFloor(showNf)

        // Peak annotations
        val showAnnotations = prefs.getBoolean("pref_show_peak_annotations", true)
        binding.spectrumView.setShowPeakAnnotations(showAnnotations)

        // Touch crosshair
        val crosshair = prefs.getBoolean("pref_crosshair_enabled", true)
        binding.spectrumView.setCrosshairEnabled(crosshair)

        // Spectrum fill opacity (0-100 → 0.0-1.0)
        val fillOpacity = prefs.getInt("pref_fill_opacity", 73)
        binding.spectrumView.setFillOpacity(fillOpacity / 100f)

        // Auto dB range
        val autoRange = prefs.getBoolean("pref_auto_range", false)
        binding.spectrumView.setAutoRange(autoRange)

        // Waterfall brightness: SeekBar 0-100 → −0.5..+0.5
        val wfBrightness = prefs.getInt("pref_waterfall_brightness", 50)
        binding.waterfallView.setBrightness((wfBrightness - 50) / 100f)

        // Waterfall contrast: SeekBar 5-150 → 0.1..3.0
        val wfContrast = prefs.getInt("pref_waterfall_contrast", 50)
        binding.waterfallView.setContrast(wfContrast / 50f)

        // Waterfall auto-stretch
        val wfAutoStretch = prefs.getBoolean("pref_waterfall_auto_stretch", false)
        binding.waterfallView.setAutoStretch(wfAutoStretch)

        // Waterfall time ruler
        val wfTimestamp = prefs.getBoolean("pref_waterfall_show_timestamp", true)
        binding.waterfallView.setShowTimestamp(wfTimestamp)

        // Waterfall centre-frequency marker
        val wfTunerMarker = prefs.getBoolean("pref_waterfall_show_tuner_marker", true)
        binding.waterfallView.setShowTunerMarker(wfTunerMarker)

        // Keep screen on
        val keepOn = prefs.getBoolean("pref_keep_screen_on", true)
        if (keepOn) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // The toolbar's overflow ("3-dot") menu has been removed. Every action it
    // used to provide now has a button elsewhere: Settings tab (Settings,
    // Debug Panel, Scanner, Recordings, Device Info, Freq Database, ADS-B
    // Bookmarks), the Bookmark/Memory/Scan FABs on the
    // main screen, or — for APRS/ACARS — simply selecting that protocol from
    // the Mode tab's chip group, which already navigates to its drawer tab
    // automatically (see observeAprsMode/observeAcarsMode). "Connect rtl_tcp…"
    // duplicated the Device Info button's action exactly and needed no replacement.

    // ─── UI Setup ─────────────────────────────────────────────────────────────

    private fun setupToolbar() {
        // A decor ActionBar is already present when the theme still supplies one (e.g. DarkActionBar).
        // Calling setSupportActionBar() in that case throws IllegalStateException, so only
        // replace it with our Toolbar when the window decor hasn't created one yet.
        if (supportActionBar == null) {
            setSupportActionBar(binding.toolbar)
        }
        supportActionBar?.title = "9GRadio"

        // Apply top inset (status bar) to the AppBarLayout so content isn't hidden
        // behind the status bar when running edge-to-edge.
        val appBar = binding.toolbar.parent as? android.view.ViewGroup ?: binding.toolbar
        ViewCompat.setOnApplyWindowInsetsListener(appBar) { view, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.setPadding(view.paddingLeft, statusBar.top, view.paddingRight, view.paddingBottom)
            insets
        }
    }

    private fun setupSpectrumView() {
        binding.spectrumView.apply {
            setTheme("Futuristic")
            onFrequencyClick = { hz ->
                viewModel.setFrequency(hz)
                binding.frequencyView.setFrequency(hz)
                binding.spectrumView.setCenterFrequency(hardwareCenterFreqHz(hz))
                binding.spectrumView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(hz))
                binding.spectrumView.setDemodDialFrequency(hz)
                binding.waterfallView.setCenterFrequency(hardwareCenterFreqHz(hz))
                binding.waterfallView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(hz))
                if (::ctrlViews.isInitialized) ctrlViews.tuningDial?.currentFreqHz = hz
            }
        }
    }

    private fun setupWaterfallView() {
        binding.waterfallView.apply {
            onFrequencyClick = { hz ->
                viewModel.setFrequency(hz)
                binding.frequencyView.setFrequency(hz)
                binding.spectrumView.setCenterFrequency(hardwareCenterFreqHz(hz))
                binding.spectrumView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(hz))
                binding.spectrumView.setDemodDialFrequency(hz)
                binding.waterfallView.setCenterFrequency(hardwareCenterFreqHz(hz))
                binding.waterfallView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(hz))
                if (::ctrlViews.isInitialized) ctrlViews.tuningDial?.currentFreqHz = hz
            }
        }
    }

    private fun setupFrequencyView() {
        binding.frequencyView.onFrequencyChanged = { hz ->
            viewModel.setFrequency(hz)
            binding.spectrumView.setCenterFrequency(hardwareCenterFreqHz(hz))
            binding.spectrumView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(hz))
            binding.spectrumView.setDemodDialFrequency(hz)
            binding.waterfallView.setCenterFrequency(hardwareCenterFreqHz(hz))
            binding.waterfallView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(hz))
        }

        // Long-press → manual frequency entry dialog.
        binding.frequencyView.onLongPress = { currentHz ->
            showFrequencyEditDialog(currentHz)
        }

        // Step up/down buttons -- single tap steps once; hold accelerates.
        setupFreqButton(binding.btnFreqUp,   isUp = true)
        setupFreqButton(binding.btnFreqDown, isUp = false)
    }

    /**
     * Shows a dialog letting the user type a frequency in MHz.
     * Accepts decimal input (e.g. "145.500" or "433.920").
     * On confirm the value is clamped to the RTL-SDR range and applied.
     */
    private fun showFrequencyEditDialog(currentHz: Long) {
        val editText = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "MHz (e.g. 145.500)"
            setText("%.6f".format(currentHz / 1_000_000.0).trimEnd('0').trimEnd('.'))
            textSize = 18f
            setPadding(48, 32, 48, 16)
            selectAll()
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Enter Frequency")
            .setMessage("Type frequency in MHz")
            .setView(editText)
            .setPositiveButton("Tune") { _, _ ->
                val mhz = editText.text.toString().toDoubleOrNull()
                if (mhz != null && mhz > 0) {
                    val hz = (mhz * 1_000_000).toLong()
                    viewModel.setFrequency(hz)
                    binding.frequencyView.setFrequency(hz)
                    binding.spectrumView.setCenterFrequency(hardwareCenterFreqHz(hz))
                    binding.spectrumView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(hz))
                    binding.spectrumView.setDemodDialFrequency(hz)
                    binding.waterfallView.setCenterFrequency(hardwareCenterFreqHz(hz))
                    binding.waterfallView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(hz))
                    showSnackbar("Tuned to ${"%.3f".format(hz / 1e6)} MHz")
                } else {
                    showSnackbar("Invalid frequency")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
            .also { dialog ->
                // Auto-show keyboard
                editText.postDelayed({
                    editText.requestFocus()
                    val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    imm?.showSoftInput(editText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }, 100)
            }
    }

    /**
     * Attaches both click and long-press-repeat behaviour to a frequency step button.
     *
     * Short tap  → one step (same as before).
     * Hold 400ms → starts repeating at 150 ms / step.
     * Hold 1.6 s → accelerates to 60 ms / step (10× effective speed).
     * Release    → stops immediately.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupFreqButton(button: View, isUp: Boolean) {
        val step: () -> Unit = {
            if (isUp) binding.frequencyView.stepUp() else binding.frequencyView.stepDown()
        }
        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    freqRepeatFired = false
                    freqRepeatJob?.cancel()
                    freqRepeatJob = lifecycleScope.launch {
                        delay(400L)               // initial hold threshold
                        var count = 0
                        while (isActive) {
                            freqRepeatFired = true
                            step()
                            count++
                            delay(if (count < 8) 150L else 60L)   // 150 ms → 60 ms after ~1.2 s
                        }
                    }
                    false   // pass through so onClick also fires on a short tap
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    freqRepeatJob?.cancel()
                    freqRepeatJob = null
                    false
                }
                else -> false
            }
        }
        // Click fires on every touch-up; suppress it when repeat already handled the stepping.
        button.setOnClickListener { if (!freqRepeatFired) step() }
    }

    private fun setupDemodModeChips() {
        val modes = listOf(
            DemodMode.AM, DemodMode.NFM, DemodMode.WFM, DemodMode.WFM_STEREO,
            DemodMode.USB, DemodMode.LSB, DemodMode.CW, DemodMode.CWR,
            DemodMode.DSB, DemodMode.APRS, DemodMode.RAW,
            DemodMode.DMR, DemodMode.YSF, DemodMode.DSTAR, DemodMode.DIG
        )
        val chipGroup = ctrlViews.chipGroupDemod ?: return
        chipGroup.removeAllViews()
        modes.forEach { mode ->
            val chip = Chip(this).apply {
                text = mode.displayName
                isCheckable = true
                tag = mode
                setOnClickListener {
                    viewModel.setDemodMode(mode)
                    syncFrequencyDisplay()
                }
            }
            chipGroup.addView(chip)
        }
        // Reflect the currently active mode immediately (e.g. after rotation
        // or returning from another tab where the chips were just inflated).
        selectDemodChip(viewModel.demodMode.value)

        // Shutdown button + timer dial setup.
        setupShutdownControls()
    }

    // ── Shutdown timer ────────────────────────────────────────────────────────

    /** Options shown in the timer spinner: label → delay in ms (null = immediate). */
    private val shutdownOptions: List<Pair<String, Long?>> = listOf(
        "Now"    to null,
        "0.5 hr" to (30  * 60 * 1000L),
        "1 hr"   to (60  * 60 * 1000L),
        "1.5 hr" to (90  * 60 * 1000L),
        "2 hr"   to (120 * 60 * 1000L),
        "2.5 hr" to (150 * 60 * 1000L),
        "3 hr"   to (180 * 60 * 1000L),
    )

    private val shutdownHandler = Handler(Looper.getMainLooper())
    private var shutdownRunnable: Runnable? = null
    private var shutdownTargetMs: Long = 0L

    /** Called once after the ViewPager has inflated the mode tab. */
    private fun setupShutdownControls() {
        val spinner  = ctrlViews.spinnerShutdownTimer ?: return
        val btn      = ctrlViews.btnShutdown          ?: return
        val statusTv = ctrlViews.tvShutdownTimerStatus

        // Populate the spinner.
        val labels = shutdownOptions.map { it.first }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.setSelection(0, false)   // default = "Now"

        btn.setOnClickListener {
            val delay = shutdownOptions[spinner.selectedItemPosition].second
            if (delay == null) {
                // Immediate – cancel any pending timer and confirm.
                cancelShutdownTimer(statusTv)
                confirmShutdown()
            } else {
                // Timed – either schedule or, if already running for same slot, cancel.
                if (shutdownRunnable != null) {
                    cancelShutdownTimer(statusTv)
                } else {
                    scheduleShutdown(delay, shutdownOptions[spinner.selectedItemPosition].first, statusTv)
                }
            }
        }
    }

    private fun scheduleShutdown(delayMs: Long, label: String, statusTv: android.widget.TextView?) {
        shutdownTargetMs = System.currentTimeMillis() + delayMs
        val ticker = object : Runnable {
            override fun run() {
                val remaining = shutdownTargetMs - System.currentTimeMillis()
                if (remaining <= 0) {
                    shutdownRunnable = null
                    statusTv?.text = "Disconnects the SDR device and closes the app."
                    ctrlViews.btnShutdown?.text = "Shutdown"
                    performShutdown()
                    return
                }
                val mins = (remaining / 60000).toInt()
                val secs = ((remaining % 60000) / 1000).toInt()
                statusTv?.text = "Shutting down in %d:%02d — tap Shutdown to cancel.".format(mins, secs)
                ctrlViews.btnShutdown?.text = "Cancel"
                shutdownHandler.postDelayed(this, 1000)
            }
        }
        shutdownRunnable = ticker
        shutdownHandler.post(ticker)
        statusTv?.text = "Shutdown scheduled in $label. Tap Shutdown to cancel."
        ctrlViews.btnShutdown?.text = "Cancel"
    }

    private fun cancelShutdownTimer(statusTv: android.widget.TextView?) {
        shutdownRunnable?.let { shutdownHandler.removeCallbacks(it) }
        shutdownRunnable = null
        statusTv?.text = "Disconnects the SDR device and closes the app."
        ctrlViews.btnShutdown?.text = "Shutdown"
    }

    /**
     * Prompts the user to confirm immediate shutdown, then gracefully stops
     * the SDR service and exits the activity.
     */
    private fun confirmShutdown() {
        AlertDialog.Builder(this)
            .setTitle("Shut down 9GRadio?")
            .setMessage("This will disconnect the SDR device and close the app.")
            .setPositiveButton("Shutdown") { _, _ -> performShutdown() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performShutdown() {
        // Stop the foreground service: disconnects the USB device, cancels the
        // notification, and stops itself (RtlSdrService.ACTION_STOP).
        val stopIntent = Intent(this, RtlSdrService::class.java).setAction(RtlSdrService.ACTION_STOP)
        startService(stopIntent)

        // Unbind now so onDestroy doesn't race the service teardown.
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
        if (vmBound) {
            unbindService(viewModel.serviceConnection)
            vmBound = false
        }

        // Close the activity and remove the task from recents -- a graceful,
        // full app shutdown rather than just backgrounding it.
        finishAndRemoveTask()
    }

    private fun setupControlsPanel() {
        setupTabsDrawer()
        // ViewPager2 inflates tab views on first layout pass.
        // Defer all ctrlViews.* wiring until the pager has inflated its pages.
        binding.controlViewPager.post { setupControlsPanelDeferred() }
    }

    @Suppress("LongMethod")
    private fun setupControlsPanelDeferred() {
        // Gain slider -- range and label depend on the active IQ source.
        // For USB, the table is RtlSdrDevice.GAIN_TABLE_DB_TENTHS (29 steps).
        // For TCP (rtl_tcp_andro), the server reports its own gain count in the
        // magic header, and the RtlTcpSource resolves the matching table.
        // We query the live source's getGainCount() so the slider always matches
        // the actual hardware regardless of source type.
        val gainCount = (sdrService?.source?.getGainCount()
            ?: viewModel.getService()?.source?.getGainCount()
            ?: RtlSdrDevice.GAIN_TABLE_DB_TENTHS.size)
            .coerceAtLeast(1)

        ctrlViews.sliderGain!!.apply {
            valueFrom = 0f
            valueTo = (gainCount - 1).toFloat()
            stepSize = 1f
            val savedIndex = viewModel.gainIndex.value.toFloat().coerceIn(valueFrom, valueTo)
            value = savedIndex
            syncGainLabel(savedIndex.toInt())
            setLabelFormatter { v ->
                val db = gainDbAtIndex(v.toInt())
                "${"%.1f".format(db)} dB"
            }
            addOnChangeListener { _, v, fromUser ->
                if (fromUser) {
                    viewModel.setGain(v.toInt())
                    syncGainLabel(v.toInt())
                }
            }
        }

        // Tuner AGC switch -- controls R82xx LNA/Mixer AGC (disables manual gain slider).
        ctrlViews.switchTunerAgc!!.setOnCheckedChangeListener(null)
        ctrlViews.switchTunerAgc!!.isChecked = viewModel.tunerAgcEnabled.value
        ctrlViews.sliderGain!!.isEnabled = !viewModel.tunerAgcEnabled.value
        ctrlViews.switchTunerAgc!!.setOnCheckedChangeListener { _, checked ->
            viewModel.setTunerAgc(checked)
            ctrlViews.sliderGain!!.isEnabled = !checked
        }

        // Hardware AGC switch -- controls RTL2832U digital IF AGC (independent of tuner AGC).
        ctrlViews.switchHardwareAgc!!.setOnCheckedChangeListener(null)
        ctrlViews.switchHardwareAgc!!.isChecked = viewModel.hardwareAgcEnabled.value
        ctrlViews.switchHardwareAgc!!.setOnCheckedChangeListener { _, checked ->
            viewModel.setHardwareAgc(checked)
        }

        // Squelch slider
        ctrlViews.sliderSquelch!!.apply {
            valueFrom = -120f; valueTo = 0f; value = viewModel.squelch.value.coerceIn(-120f, 0f)
            addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    viewModel.setSquelch(value)
                    ctrlViews.tvSquelchValue!!.text = "${value.toInt()} dB"
                    binding.spectrumView.setSquelch(value)
                }
            }
        }

        // Volume slider
        ctrlViews.sliderVolume!!.apply {
            valueFrom = 0f; valueTo = 2f; value = 1f
            addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    viewModel.setVolume(value)
                    ctrlViews.tvVolumeValue!!.text = "${(value * 100).toInt()}%"
                }
            }
        }

        // Bias tee switch
        ctrlViews.switchBiasTee!!.setOnCheckedChangeListener { _, checked ->
            viewModel.setBiasTee(checked)
        }

        // Noise Blanker switch
        ctrlViews.switchNoiseBlanker!!.isChecked = viewModel.noiseBlankerEnabled.value
        ctrlViews.switchNoiseBlanker!!.setOnCheckedChangeListener { _, checked ->
            viewModel.setNoiseBlanker(checked)
        }

        // Noise Reducer switch
        ctrlViews.switchNoiseReducer!!.isChecked = viewModel.noiseReducerEnabled.value
        ctrlViews.switchNoiseReducer!!.setOnCheckedChangeListener { _, checked ->
            viewModel.setNoiseReducer(checked)
        }

        // ── IF Bandwidth / Protocol Filter slider ─────────────────────────────
        // The slider is an *index* into BW_STEPS -- a fixed list of standard radio
        // bandwidths covering CW (100 Hz) through AM/NFM/WFM (200 kHz).
        //
        // CW filters follow IARU/contest-receiver conventions:
        //   100, 150, 200, 250, 300, 400, 500 Hz (narrow CW)
        //   600, 800 Hz (wide CW / RTTY)
        // SSB / data filters follow ITU-R M.1131 and standard receiver practice:
        //   1.0, 1.2, 1.5, 1.8, 2.0, 2.4, 2.7, 3.0, 3.3, 3.6 kHz
        // AM / NFM / wider protocols:
        //   4, 5, 6, 8, 10, 12.5, 15, 25, 50, 100, 200 kHz
        //
        // Each mode exposes only the slice of BW_STEPS that makes sense for it.
        // The slider valueFrom/valueTo are updated dynamically on every mode change.
        //
        // "Auto" means the mode's defaultBwHz is in use (slider snaps to that index
        // but the stored value in the ViewModel is 0 / sentinel).
        // Initialise slider for the current mode.
        val savedIfBw    = viewModel.ifBandwidthHz.value
        val initialMode  = viewModel.demodMode.value
        val initialBw    = if (savedIfBw > 0) savedIfBw
                           else initialMode.defaultBwHz.coerceAtLeast(DspEngine.MIN_IF_BANDWIDTH_HZ)
        // Set stepSize once; valueFrom/valueTo are set inside applyBwRange.
        ctrlViews.sliderIfBandwidth!!.stepSize = 1f
        applyBwRange(initialMode, initialBw)
        ctrlViews.tvIfBandwidthValue!!.text = formatIfBw(initialBw, savedIfBw == 0)

        ctrlViews.sliderIfBandwidth!!.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val hz   = BW_STEPS[value.toInt().coerceIn(0, BW_STEPS.size - 1)]
                val mode = viewModel.demodMode.value
                val isDefault = (hz == mode.defaultBwHz.coerceAtLeast(DspEngine.MIN_IF_BANDWIDTH_HZ))
                viewModel.setIfBandwidth(if (isDefault) 0 else hz)
                ctrlViews.tvIfBandwidthValue!!.text = formatIfBw(hz, isDefault)
                // Update spectrum highlight immediately for real-time visual feedback.
                val highlightHz = channelHighlightHz(mode, if (isDefault) 0 else hz)
                binding.spectrumView.setDemodChannelBandwidth(highlightHz, channelHighlightSideband(mode))
                val pfLabel = if (isDefault)
                    "Auto (${formatIfBw(mode.defaultBwHz.coerceAtLeast(DspEngine.MIN_IF_BANDWIDTH_HZ), false)}, mode default)"
                else
                    formatIfBw(hz, false)
                com.radiosport.ninegradio.debug.DebugBus.setGlobal(
                    com.radiosport.ninegradio.debug.DebugBus.KEY_PROTOCOL_FILTER, pfLabel)
            }
        }

        // Sync slider range AND position whenever mode or bandwidth changes.
        lifecycleScope.launch {
            viewModel.ifBandwidthHz.collect { hz ->
                val mode      = viewModel.demodMode.value
                val defaultBw = mode.defaultBwHz.coerceAtLeast(DspEngine.MIN_IF_BANDWIDTH_HZ)
                val effective = if (hz > 0) hz else defaultBw
                val isAuto    = hz == 0
                applyBwRange(mode, effective)
                ctrlViews.tvIfBandwidthValue!!.text = formatIfBw(effective, isAuto)
                binding.spectrumView.setDemodChannelBandwidth(channelHighlightHz(mode, hz), channelHighlightSideband(mode))
                val pfLabel = if (isAuto)
                    "Auto (${formatIfBw(defaultBw, false)}, mode default)"
                else
                    formatIfBw(effective, false)
                com.radiosport.ninegradio.debug.DebugBus.setGlobal(
                    com.radiosport.ninegradio.debug.DebugBus.KEY_PROTOCOL_FILTER, pfLabel)
            }
        }

        // Sample rate selector -- horizontal scrollable row of chip buttons.
        // NumberPicker was invisible (black-on-black) because it ignores android:textColor
        // in XML and inherits the window background for its internal dividers.
        // Chip buttons give full colour control without any theme overrides.
        val sampleRateLabels = RtlSdrDevice.SAMPLE_RATES.map { rate ->
            val divLabel = if (rate % DspEngine.DEFAULT_AUDIO_RATE == 0)
                " ÷${rate / DspEngine.DEFAULT_AUDIO_RATE}" else ""
            if (rate >= 1_000_000) "${"%.3f".format(rate / 1e6)}M$divLabel"
            else "${"%.0f".format(rate / 1e3)}k$divLabel"
        }
        val savedIdx = RtlSdrDevice.SAMPLE_RATES.indexOfFirst { it == viewModel.sampleRate.value }
            .let { if (it >= 0) it else RtlSdrDevice.SAMPLE_RATES.indexOfFirst { r -> r == 1_920_000 }.coerceAtLeast(0) }

        sampleRateLabelsCache = sampleRateLabels

        ctrlViews.layoutSampleRateChips!!.removeAllViews()
        val dp = resources.displayMetrics.density
        sampleRateLabels.forEachIndexed { idx, label ->
            val btn = android.widget.TextView(this).apply {
                text = label
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins((3 * dp).toInt(), 0, (3 * dp).toInt(), 0) }
                setOnClickListener {
                    val rate = RtlSdrDevice.SAMPLE_RATES[idx]
                    ctrlViews.tvSampleRateLabel!!.text = sampleRateLabels[idx]
                    viewModel.setSampleRate(rate)
                    val effectiveRate = rate / viewModel.decimation.value.coerceAtLeast(1)
                    binding.spectrumView.setSampleRate(effectiveRate)
                    binding.waterfallView.setSampleRate(effectiveRate)
                    // Refresh WFM channel highlight -- wfmIfRate depends on device rate
                    binding.spectrumView.setDemodChannelBandwidth(
                        channelHighlightHz(viewModel.demodMode.value, viewModel.ifBandwidthHz.value),
                        channelHighlightSideband(viewModel.demodMode.value))
                    updateSampleRateChips(idx)
                    // Scroll selected chip into view
                    (ctrlViews.layoutSampleRateChips!!.parent as? android.widget.HorizontalScrollView)
                        ?.smoothScrollTo(this.left - (40 * dp).toInt(), 0)
                }
            }
            ctrlViews.layoutSampleRateChips!!.addView(btn)
        }
        ctrlViews.tvSampleRateLabel!!.text = sampleRateLabels[savedIdx]
        updateSampleRateChips(savedIdx)
        // Scroll to show the selected chip on first render
        ctrlViews.layoutSampleRateChips!!.post {
            val selected = ctrlViews.layoutSampleRateChips!!.getChildAt(savedIdx)
            (ctrlViews.layoutSampleRateChips!!.parent as? android.widget.HorizontalScrollView)
                ?.scrollTo((selected?.left ?: 0) - (40 * dp).toInt(), 0)
        }

        // ── Audio sink rate chip row ──────────────────────────────────────────
        val audioSinkRates  = DspEngine.AUDIO_SINK_RATES
        val audioSinkLabels = listOf("48k", "44.1k", "32k", "24k", "22k", "16k")
        val savedSinkIdx = audioSinkRates.indexOfFirst { it == viewModel.audioSinkRate.value }
            .let { if (it >= 0) it else 0 }

        audioSinkLabelsCache = audioSinkLabels

        ctrlViews.layoutAudioSinkRateChips!!.removeAllViews()
        audioSinkLabels.forEachIndexed { idx, label ->
            val btn = android.widget.TextView(this).apply {
                text = label
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins((3 * dp).toInt(), 0, (3 * dp).toInt(), 0) }
                setOnClickListener {
                    val rate = audioSinkRates[idx]
                    ctrlViews.tvAudioSinkRateLabel!!.text = audioSinkLabels[idx]
                    viewModel.setAudioSinkRate(rate)
                    updateAudioSinkChips(idx)
                    (ctrlViews.layoutAudioSinkRateChips!!.parent
                            as? android.widget.HorizontalScrollView)
                        ?.smoothScrollTo(this.left - (40 * dp).toInt(), 0)
                }
            }
            ctrlViews.layoutAudioSinkRateChips!!.addView(btn)
        }
        ctrlViews.tvAudioSinkRateLabel!!.text = audioSinkLabels[savedSinkIdx]
        updateAudioSinkChips(savedSinkIdx)
        ctrlViews.layoutAudioSinkRateChips!!.post {
            val sel = ctrlViews.layoutAudioSinkRateChips!!.getChildAt(savedSinkIdx)
            (ctrlViews.layoutAudioSinkRateChips!!.parent as? android.widget.HorizontalScrollView)
                ?.scrollTo((sel?.left ?: 0) - (40 * dp).toInt(), 0)
        }

        // Direct sampling spinner
        ctrlViews.spinnerDirectSampling!!.apply {
            adapter = ArrayAdapter(context,
                android.R.layout.simple_spinner_item,
                listOf("Off (Tuner)", "I-Branch (HF)", "Q-Branch (HF - V4)"))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    viewModel.setDirectSampling(pos)
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

        // PPM correction slider (−200 … +200 ppm, step 1)
        val savedPpm = viewModel.ppm.value.toFloat().coerceIn(-50f, 50f)
        ctrlViews.sliderPpm!!.value = savedPpm
        ctrlViews.tvPpmValue!!.text = "${savedPpm.toInt()} ppm"
        ctrlViews.sliderPpm!!.setLabelFormatter { v -> "${v.toInt()} ppm" }
        ctrlViews.sliderPpm!!.addOnChangeListener { _, v, fromUser ->
            val ppm = v.toInt()
            ctrlViews.tvPpmValue!!.text = "$ppm ppm"
            if (fromUser) {
                viewModel.setPpm(ppm)
                binding.spectrumView.setPpmCorrection(ppm)
                binding.waterfallView.setPpmCorrection(ppm)
            }
        }

        // IQ Record button
        ctrlViews.btnRecordIq!!.setOnClickListener {
            if (viewModel.dspStats.value.isRecordingIq) {
                viewModel.stopIqRecording()
                ctrlViews.btnRecordIq!!.text = "Record IQ"
                ctrlViews.btnRecordIq!!.setBackgroundColor(0xFF444444.toInt())
            } else {
                val path = getIqFilePath()
                viewModel.startIqRecording(path)
                ctrlViews.btnRecordIq!!.text = "Stop IQ"
                ctrlViews.btnRecordIq!!.setBackgroundColor(0xFFCC0000.toInt())
                showSnackbar("Recording IQ to: $path")
            }
        }

        // Audio Record button
        ctrlViews.btnRecordAudio!!.setOnClickListener {
            if (viewModel.dspStats.value.isRecordingAudio) {
                viewModel.stopAudioRecording()
                ctrlViews.btnRecordAudio!!.text = "Record Audio"
                ctrlViews.btnRecordAudio!!.setBackgroundColor(0xFF444444.toInt())
            } else {
                val path = getAudioFilePath()
                viewModel.startAudioRecording(path)
                ctrlViews.btnRecordAudio!!.text = "Stop Audio"
                ctrlViews.btnRecordAudio!!.setBackgroundColor(0xFFCC0000.toInt())
                showSnackbar("Recording audio to: $path")
            }
        }

        // Recordings list, pinned to the bottom of the Rec tab below the
        // controls above. Reuses the same RecordingAdapter (play/share/delete)
        // as the standalone RecordingActivity. The container is GONE until
        // viewModel.recordings actually has entries, and flips back to GONE
        // if the list is ever emptied (e.g. all recordings deleted).
        ctrlViews.recyclerRecordingsTab?.layoutManager = LinearLayoutManager(this)
        recordingsTabAdapter = RecordingAdapter(
            onPlay = { meta -> playRecordingFromTab(meta) },
            onShare = { meta -> shareRecordingFromTab(meta) },
            onDelete = { meta ->
                File(meta.filePath).delete()
                lifecycleScope.launch {
                    (application as com.radiosport.ninegradio.RtlSdrApplication)
                        .database.recordingMetaDao().delete(meta)
                }
            }
        )
        ctrlViews.recyclerRecordingsTab?.adapter = recordingsTabAdapter
        lifecycleScope.launch {
            viewModel.recordings.collectLatest { recs ->
                recordingsTabAdapter?.submitList(recs)
                ctrlViews.containerRecordingsTabList?.visibility =
                    if (recs.isNotEmpty()) View.VISIBLE else View.GONE
            }
        }

        // FFT size spinner -- sync both directions between drawer and Settings.
        // pref_fft_size (default SharedPreferences, used by the Settings ListPreference)
        // and the ViewModel's "fftSize" key (rtlsdr_prefs) must always agree.
        // On first setup we write whichever source has a non-default value into
        // the other so the two UIs are consistent from the very first open.
        val fftSizes = listOf(256, 512, 1024, 2048, 4096, 8192)
        val defaultPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        // If the Settings pref has been set previously, honour it; otherwise seed
        // it from the ViewModel's persisted value so Settings shows the right selection.
        val settingsPrefSize = defaultPrefs.getString("pref_fft_size", null)?.toIntOrNull()
        if (settingsPrefSize == null) {
            // First run or Settings never opened -- seed pref_fft_size from ViewModel
            defaultPrefs.edit().putString("pref_fft_size", viewModel.fftSize.value.toString()).apply()
        } else if (settingsPrefSize != viewModel.fftSize.value) {
            // Settings was changed while app was killed -- propagate to ViewModel
            viewModel.setFftSize(settingsPrefSize)
        }
        ctrlViews.spinnerFftSize!!.apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item,
                fftSizes.map { it.toString() })
            // Use the ViewModel's persisted FFT size (defaults to 256 on first run)
            val savedFftIdx = fftSizes.indexOf(viewModel.fftSize.value).takeIf { it >= 0 } ?: 0
            setSelection(savedFftIdx, false)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val size = fftSizes[pos]
                    viewModel.setFftSize(size)
                    // Keep the Settings page pref_fft_size in sync so the two entry
                    // points (drawer spinner and Settings list) always show the same value.
                    androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                        .edit().putString("pref_fft_size", size.toString()).apply()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

        // Decimation spinner -- narrows the FFT displayed bandwidth.
        // Values: Off=1, ÷2, ÷4, ÷8, ÷16, ÷32, ÷64.
        // The effective sample rate passed to SpectrumView/WaterfallView becomes
        // deviceSampleRate / decimationFactor, showing a proportionally narrower band.
        //
        // Source of truth: viewModel.decimation (persisted in "rtlsdr_prefs" / "fftDecimation").
        // The stale "pref_decimation" String in defaultSharedPreferences is NOT consulted here
        // to avoid overwriting the ViewModel's correctly-restored value on every tab open.
        val decimationFactors = listOf(1, 2, 4, 8, 16, 32, 64)
        val decimationLabels  = listOf("Off", "÷2 (½ BW)", "÷4 (¼ BW)", "÷8 (⅛ BW)", "÷16", "÷32", "÷64")
        ctrlViews.spinnerDecimation!!.apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, decimationLabels)
            // Set the saved selection BEFORE attaching the listener so the programmatic
            // setSelection() call does not trigger onItemSelected and reset the ViewModel.
            val savedDecimIdx = decimationFactors.indexOf(viewModel.decimation.value).takeIf { it >= 0 } ?: 0
            setSelection(savedDecimIdx, false)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val factor = decimationFactors[pos]
                    // Guard: ignore spurious callback if the position hasn't actually changed
                    if (factor == viewModel.decimation.value) return
                    viewModel.setDecimation(factor)
                    // Immediately update views with the new effective sample rate
                    val effectiveRate = viewModel.sampleRate.value / factor.coerceAtLeast(1)
                    binding.spectrumView.setSampleRate(effectiveRate)
                    binding.waterfallView.setSampleRate(effectiveRate)
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

        // Waterfall palette spinner — human-readable labels aligned to enum ordinals
        val paletteLabels = listOf(
            "Rainbow", "Heat", "Grayscale", "Blue-White", "Purple-Yellow",
            "Viridis", "Inferno", "Magma", "Turbo", "Solar", "Night Vision"
        )
        ctrlViews.spinnerPalette!!.apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, paletteLabels)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    viewModel.setWaterfallPalette(WaterfallView.Companion.Palette.values()[pos])
                    binding.waterfallView.setPalette(WaterfallView.Companion.Palette.values()[pos])
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

        // Spectrum theme spinner — all 10 themes
        val themes = listOf(
            "Classic", "Futuristic", "Amber", "Grayscale", "Purple",
            "Solar", "Neon", "Ice", "Midnight", "Sakura"
        )
        ctrlViews.spinnerTheme!!.apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, themes)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    viewModel.setSpectrumTheme(themes[pos])
                    binding.spectrumView.setTheme(themes[pos])
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

        // Peak Hold switch
        val displayPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val savedPeakHold = displayPrefs.getBoolean("pref_peak_hold", true)
        ctrlViews.switchPeakHold!!.isChecked = savedPeakHold
        ctrlViews.switchPeakHold!!.setOnCheckedChangeListener { _, checked ->
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putBoolean("pref_peak_hold", checked).apply()
            binding.spectrumView.setShowPeakHold(checked)
            val fft = sdrService?.dspEngine?.fftEngine
            if (checked) {
                fft?.resetPeakHold()
                fft?.showPeakHold = true
            } else {
                fft?.showPeakHold = false
                fft?.resetPeakHold()
            }
        }

        // ── Spectrum advanced controls ─────────────────────────────────────────

        // Fill Opacity slider
        val savedFillOpacity = displayPrefs.getInt("pref_fill_opacity", 73) / 100f
        ctrlViews.sliderFillOpacity?.value = savedFillOpacity
        ctrlViews.tvFillOpacityValue?.text = "${(savedFillOpacity * 100).toInt()}%"
        ctrlViews.sliderFillOpacity?.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            ctrlViews.tvFillOpacityValue?.text = "${(value * 100).toInt()}%"
            binding.spectrumView.setFillOpacity(value)
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putInt("pref_fill_opacity", (value * 100).toInt()).apply()
        }

        // Spectrum Floor slider (−200..−10 dBFS, step 1)
        val savedFloorRaw = displayPrefs.getInt("pref_db_min", 120)   // stored as positive
        val savedFloor = -(savedFloorRaw.toFloat()).coerceIn(10f, 200f)
        ctrlViews.sliderSpecFloor?.value = savedFloor
        ctrlViews.tvSpecFloorValue?.text = savedFloor.toInt().toString()
        ctrlViews.sliderSpecFloor?.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            ctrlViews.tvSpecFloorValue?.text = value.toInt().toString()
            val ceiling = ctrlViews.sliderSpecCeiling?.value ?: 0f
            binding.spectrumView.setDbRange(value, ceiling)
            binding.waterfallView.setDynamicRange(value, ceiling)
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putInt("pref_db_min", (value * -1f).toInt()).apply()
        }

        // Spectrum Ceiling slider (−100..+50 dBFS, step 1)
        val savedCeilingRaw = displayPrefs.getInt("pref_db_max", 0)
        val savedCeiling = savedCeilingRaw.toFloat().coerceIn(-100f, 50f)
        ctrlViews.sliderSpecCeiling?.value = savedCeiling
        ctrlViews.tvSpecCeilingValue?.text = savedCeiling.toInt().toString()
        ctrlViews.sliderSpecCeiling?.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            ctrlViews.tvSpecCeilingValue?.text = value.toInt().toString()
            val floor = ctrlViews.sliderSpecFloor?.value ?: -120f
            binding.spectrumView.setDbRange(floor, value)
            binding.waterfallView.setDynamicRange(floor, value)
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putInt("pref_db_max", value.toInt()).apply()
        }

        // Auto dB Range switch
        val savedAutoRange = displayPrefs.getBoolean("pref_auto_range", false)
        ctrlViews.switchAutoRange?.isChecked = savedAutoRange
        ctrlViews.switchAutoRange?.setOnCheckedChangeListener { _, checked ->
            binding.spectrumView.setAutoRange(checked)
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putBoolean("pref_auto_range", checked).apply()
        }

        // Reset dB Range button — restores nominal −120..0 dBFS and turns Auto off
        ctrlViews.btnResetDbRange?.setOnClickListener {
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            prefs.edit()
                .putInt("pref_db_min", 120)   // stored as positive; loader negates it → −120 dBFS
                .putInt("pref_db_max", 0)
                .putBoolean("pref_auto_range", false)
                .apply()
            binding.spectrumView.setAutoRange(false)
            binding.spectrumView.setDbRange(-120f, 0f)
            binding.waterfallView.setDynamicRange(-120f, 0f)
            ctrlViews.switchAutoRange?.isChecked = false
            ctrlViews.sliderSpecFloor?.value = -120f
            ctrlViews.tvSpecFloorValue?.text = "-120"
            ctrlViews.sliderSpecCeiling?.value = 0f
            ctrlViews.tvSpecCeilingValue?.text = "0"
        }

        // Noise Floor switch
        val savedNoiseFloor = displayPrefs.getBoolean("pref_show_noise_floor", true)
        ctrlViews.switchNoiseFloor?.isChecked = savedNoiseFloor
        ctrlViews.switchNoiseFloor?.setOnCheckedChangeListener { _, checked ->
            binding.spectrumView.setShowNoiseFloor(checked)
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putBoolean("pref_show_noise_floor", checked).apply()
        }

        // Peak Decay slider
        val savedPeakDecay = displayPrefs.getInt("pref_peak_decay", 2).toFloat()
        ctrlViews.sliderPeakDecay?.value = savedPeakDecay.coerceIn(1f, 20f)
        ctrlViews.tvPeakDecayValue?.text = savedPeakDecay.toInt().toString()
        ctrlViews.sliderPeakDecay?.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            ctrlViews.tvPeakDecayValue?.text = value.toInt().toString()
            sdrService?.dspEngine?.fftEngine?.peakDecayRate = value * 0.001f
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putInt("pref_peak_decay", value.toInt()).apply()
        }

        // Peak Annotations switch
        val savedAnnotations = displayPrefs.getBoolean("pref_show_peak_annotations", true)
        ctrlViews.switchPeakAnnotations?.isChecked = savedAnnotations
        ctrlViews.switchPeakAnnotations?.setOnCheckedChangeListener { _, checked ->
            binding.spectrumView.setShowPeakAnnotations(checked)
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putBoolean("pref_show_peak_annotations", checked).apply()
        }

        // Touch Crosshair switch
        val savedCrosshair = displayPrefs.getBoolean("pref_crosshair_enabled", true)
        ctrlViews.switchCrosshair?.isChecked = savedCrosshair
        ctrlViews.switchCrosshair?.setOnCheckedChangeListener { _, checked ->
            binding.spectrumView.setCrosshairEnabled(checked)
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putBoolean("pref_crosshair_enabled", checked).apply()
        }

        // ── Waterfall advanced controls ────────────────────────────────────────

        // Brightness slider (range −0.5..+0.5, stored as 0..100 centred on 50)
        val savedBrightness = (displayPrefs.getInt("pref_waterfall_brightness", 50) - 50) / 100f
        ctrlViews.sliderWfBrightness?.value = savedBrightness.coerceIn(-0.5f, 0.5f)
        ctrlViews.tvWfBrightnessValue?.text = "%+.2f".format(savedBrightness)
        ctrlViews.sliderWfBrightness?.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            ctrlViews.tvWfBrightnessValue?.text = "%+.2f".format(value)
            binding.waterfallView.setBrightness(value)
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putInt("pref_waterfall_brightness", ((value + 0.5f) * 100).toInt()).apply()
        }

        // Contrast slider (range 0.2..3.0, stored as 5..150 centred on 50=1.0×)
        val savedContrast = displayPrefs.getInt("pref_waterfall_contrast", 50) / 50f
        ctrlViews.sliderWfContrast?.value = savedContrast.coerceIn(0.2f, 3.0f)
        ctrlViews.tvWfContrastValue?.text = "%.1f×".format(savedContrast)
        ctrlViews.sliderWfContrast?.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            ctrlViews.tvWfContrastValue?.text = "%.1f×".format(value)
            binding.waterfallView.setContrast(value)
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putInt("pref_waterfall_contrast", (value * 50).toInt()).apply()
        }

        // Auto-Stretch switch
        val savedAutoStretch = displayPrefs.getBoolean("pref_waterfall_auto_stretch", false)
        ctrlViews.switchAutoStretch?.isChecked = savedAutoStretch
        ctrlViews.switchAutoStretch?.setOnCheckedChangeListener { _, checked ->
            binding.waterfallView.setAutoStretch(checked)
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putBoolean("pref_waterfall_auto_stretch", checked).apply()
        }

        // Waterfall Pause switch
        ctrlViews.switchWfPause?.isChecked = false
        ctrlViews.switchWfPause?.setOnCheckedChangeListener { _, checked ->
            binding.waterfallView.setPaused(checked)
        }

        // Time Ruler switch
        val savedTimeRuler = displayPrefs.getBoolean("pref_waterfall_show_timestamp", true)
        ctrlViews.switchTimeRuler?.isChecked = savedTimeRuler
        ctrlViews.switchTimeRuler?.setOnCheckedChangeListener { _, checked ->
            binding.waterfallView.setShowTimestamp(checked)
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putBoolean("pref_waterfall_show_timestamp", checked).apply()
        }

        // Centre Marker switch
        val savedCentreMarker = displayPrefs.getBoolean("pref_waterfall_show_tuner_marker", true)
        ctrlViews.switchCentreMarker?.isChecked = savedCentreMarker
        ctrlViews.switchCentreMarker?.setOnCheckedChangeListener { _, checked ->
            binding.waterfallView.setShowTunerMarker(checked)
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putBoolean("pref_waterfall_show_tuner_marker", checked).apply()
        }

        // ── FFT advanced controls ──────────────────────────────────────────────

        // Frame Averaging spinner
        val frameAvgLabels = listOf("Off", "×2 (−3dB)", "×4 (−6dB)", "×8 (−9dB)", "×16 (−12dB)", "×32 (−15dB)")
        val frameAvgValues = listOf(1, 2, 4, 8, 16, 32)
        ctrlViews.spinnerFrameAvg?.apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, frameAvgLabels)
            val savedAvg = displayPrefs.getString("pref_frame_averaging", "1")?.toIntOrNull() ?: 1
            setSelection(frameAvgValues.indexOf(savedAvg).takeIf { it >= 0 } ?: 0, false)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val count = frameAvgValues[pos]
                    sdrService?.dspEngine?.fftEngine?.frameAveragingCount = count
                    androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                        .edit().putString("pref_frame_averaging", count.toString()).apply()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
    }   // end setupDisplayTab

    /** Highlights the selected sample-rate chip; reusable from setup and from syncTabControls(). */
    private fun updateSampleRateChips(selectedIdx: Int) {
        val chips = ctrlViews.layoutSampleRateChips ?: return
        for (i in 0 until chips.childCount) {
            val btn = chips.getChildAt(i) as? android.widget.TextView ?: continue
            val sel = (i == selectedIdx)
            btn.setTextColor(if (sel) 0xFF0D1117.toInt() else 0xFF00CCFF.toInt())
            btn.background = if (sel) {
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF00CCFF.toInt()); cornerRadius = 20f * resources.displayMetrics.density
                }
            } else {
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(0x00000000); cornerRadius = 20f * resources.displayMetrics.density
                    setStroke((1f * resources.displayMetrics.density).toInt(), 0x5500CCFF.toInt())
                }
            }
        }
    }

    /** Highlights the selected audio-sink-rate chip; reusable from setup and from syncTabControls(). */
    private fun updateAudioSinkChips(selectedIdx: Int) {
        val row = ctrlViews.layoutAudioSinkRateChips ?: return
        for (i in 0 until row.childCount) {
            val btn = row.getChildAt(i) as? android.widget.TextView ?: continue
            val sel = (i == selectedIdx)
            btn.setTextColor(if (sel) 0xFF0D1117.toInt() else 0xFF00CCFF.toInt())
            btn.background = if (sel) {
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF00CCFF.toInt())
                    cornerRadius = 20f * resources.displayMetrics.density
                }
            } else {
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(0x00000000)
                    cornerRadius = 20f * resources.displayMetrics.density
                    setStroke((1f * resources.displayMetrics.density).toInt(), 0x5500CCFF.toInt())
                }
            }
        }
    }

    /**
     * Re-syncs every control on tab [position] from the ViewModel's authoritative
     * state. Called from the TabLayout's onTabSelected listener every time a tab
     * is brought into view -- previously, controls (sliders, switches, spinners,
     * chip rows) were only ever pushed to the UI once at first setup or when the
     * control itself was moved, so a value changed on another tab (or by mode
     * switching, reconnect, etc.) kept showing the old position until the user
     * touched it directly. This guarantees every drawer tab shows fresh state
     * the instant it's selected, with no functional side effects (listeners are
     * detached before values are set and reattached after, so re-syncing never
     * fires a duplicate "user changed this" event back into the ViewModel).
     */
    private fun syncTabControls(position: Int) {
        if (!::ctrlViews.isInitialized) return
        val v = viewModel
        when (position) {
            ControlsPagerAdapter.TAB_MODE -> {
                selectDemodChip(v.demodMode.value)
            }

            ControlsPagerAdapter.TAB_TUNE -> {
                val dial = ctrlViews.tuningDial
                dial?.currentFreqHz = v.centerFreqHz.value
                dial?.demodMode     = v.demodMode.value.name

                ctrlViews.switchSnap?.setOnCheckedChangeListener(null)
                ctrlViews.switchSnap?.isChecked = snapToChannel
                ctrlViews.switchSnap?.setOnCheckedChangeListener { _, checked -> snapToChannel = checked }

                ctrlViews.switchFineTune?.setOnCheckedChangeListener(null)
                ctrlViews.switchFineTune?.isChecked = dial?.fineMode ?: false
                ctrlViews.switchFineTune?.setOnCheckedChangeListener { _, checked -> dial?.fineMode = checked }

                // Recommended step depends on current frequency/mode -- rebuild so the
                // checked chip reflects where the dial actually is right now.
                setupStepChips()
            }

            ControlsPagerAdapter.TAB_RF -> {
                val svc = viewModel.getService() ?: sdrService

                // Gain slider -- range may have changed if the IQ source changed.
                ctrlViews.sliderGain?.let { slider ->
                    val gainCount = (svc?.source?.getGainCount() ?: RtlSdrDevice.GAIN_TABLE_DB_TENTHS.size)
                        .coerceAtLeast(1)
                    slider.valueFrom = 0f
                    slider.valueTo = (gainCount - 1).toFloat()
                    val idx = v.gainIndex.value.toFloat().coerceIn(slider.valueFrom, slider.valueTo)
                    slider.value = idx
                    syncGainLabel(idx.toInt())
                }

                ctrlViews.switchTunerAgc?.setOnCheckedChangeListener(null)
                ctrlViews.switchTunerAgc?.isChecked = v.tunerAgcEnabled.value
                ctrlViews.sliderGain?.isEnabled = !v.tunerAgcEnabled.value
                ctrlViews.switchTunerAgc?.setOnCheckedChangeListener { _, checked ->
                    v.setTunerAgc(checked)
                    ctrlViews.sliderGain?.isEnabled = !checked
                }

                ctrlViews.switchHardwareAgc?.setOnCheckedChangeListener(null)
                ctrlViews.switchHardwareAgc?.isChecked = v.hardwareAgcEnabled.value
                ctrlViews.switchHardwareAgc?.setOnCheckedChangeListener { _, checked -> v.setHardwareAgc(checked) }

                ctrlViews.sliderSquelch?.value = v.squelch.value.coerceIn(-120f, 0f)
                ctrlViews.tvSquelchValue?.text = "${v.squelch.value.toInt()} dB"

                ctrlViews.sliderVolume?.value = v.volume.value.coerceIn(0f, 2f)
                ctrlViews.tvVolumeValue?.text = "${(v.volume.value * 100).toInt()}%"

                ctrlViews.switchBiasTee?.setOnCheckedChangeListener(null)
                ctrlViews.switchBiasTee?.isChecked = v.biasTee.value
                ctrlViews.switchBiasTee?.setOnCheckedChangeListener { _, checked -> v.setBiasTee(checked) }

                ctrlViews.switchNoiseBlanker?.setOnCheckedChangeListener(null)
                ctrlViews.switchNoiseBlanker?.isChecked = v.noiseBlankerEnabled.value
                ctrlViews.switchNoiseBlanker?.setOnCheckedChangeListener { _, checked -> v.setNoiseBlanker(checked) }

                ctrlViews.switchNoiseReducer?.setOnCheckedChangeListener(null)
                ctrlViews.switchNoiseReducer?.isChecked = v.noiseReducerEnabled.value
                ctrlViews.switchNoiseReducer?.setOnCheckedChangeListener { _, checked -> v.setNoiseReducer(checked) }

                // IF bandwidth slider -- range/position depend on the active mode.
                val mode      = v.demodMode.value
                val savedBw   = v.ifBandwidthHz.value
                val defaultBw = mode.defaultBwHz.coerceAtLeast(DspEngine.MIN_IF_BANDWIDTH_HZ)
                val effective = if (savedBw > 0) savedBw else defaultBw
                applyBwRange(mode, effective)
                ctrlViews.tvIfBandwidthValue?.text = formatIfBw(effective, savedBw == 0)

                ctrlViews.spinnerDirectSampling?.setSelection(v.directSampling.value, false)

                ctrlViews.sliderPpm?.value = v.ppm.value.toFloat().coerceIn(-50f, 50f)
                ctrlViews.tvPpmValue?.text = "${v.ppm.value} ppm"
                binding.spectrumView.setPpmCorrection(v.ppm.value)
                binding.waterfallView.setPpmCorrection(v.ppm.value)

                if (sampleRateLabelsCache.isNotEmpty()) {
                    val idx = RtlSdrDevice.SAMPLE_RATES.indexOfFirst { it == v.sampleRate.value }.coerceAtLeast(0)
                    ctrlViews.tvSampleRateLabel?.text = sampleRateLabelsCache.getOrNull(idx)
                    updateSampleRateChips(idx)
                }
                if (audioSinkLabelsCache.isNotEmpty()) {
                    val idx = DspEngine.AUDIO_SINK_RATES.indexOfFirst { it == v.audioSinkRate.value }.coerceAtLeast(0)
                    ctrlViews.tvAudioSinkRateLabel?.text = audioSinkLabelsCache.getOrNull(idx)
                    updateAudioSinkChips(idx)
                }
            }

            ControlsPagerAdapter.TAB_DISPLAY -> {
                val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)

                // FFT / Decimation spinners
                val fftSizes = listOf(256, 512, 1024, 2048, 4096, 8192)
                fftSizes.indexOf(v.fftSize.value).takeIf { it >= 0 }?.let {
                    ctrlViews.spinnerFftSize?.setSelection(it, false)
                }
                val decimationFactors = listOf(1, 2, 4, 8, 16, 32, 64)
                decimationFactors.indexOf(v.decimation.value).takeIf { it >= 0 }?.let {
                    ctrlViews.spinnerDecimation?.setSelection(it, false)
                }

                // Waterfall palette
                ctrlViews.spinnerPalette?.setSelection(v.waterfallPalette.value.ordinal, false)

                // Spectrum theme — full 10-theme list
                val themes = listOf(
                    "Classic", "Futuristic", "Amber", "Grayscale", "Purple",
                    "Solar", "Neon", "Ice", "Midnight", "Sakura"
                )
                themes.indexOf(v.spectrumTheme.value).takeIf { it >= 0 }?.let {
                    ctrlViews.spinnerTheme?.setSelection(it, false)
                }

                // Helper: sync a Switch without re-firing the listener
                fun syncSwitch(sw: android.widget.Switch?, checked: Boolean,
                               listener: android.widget.CompoundButton.OnCheckedChangeListener) {
                    sw?.setOnCheckedChangeListener(null)
                    sw?.isChecked = checked
                    sw?.setOnCheckedChangeListener(listener)
                }

                syncSwitch(ctrlViews.switchPeakHold,
                    prefs.getBoolean("pref_peak_hold", true)
                ) { _, checked ->
                    prefs.edit().putBoolean("pref_peak_hold", checked).apply()
                    binding.spectrumView.setShowPeakHold(checked)
                    val fft = sdrService?.dspEngine?.fftEngine
                    if (checked) { fft?.resetPeakHold(); fft?.showPeakHold = true }
                    else { fft?.showPeakHold = false; fft?.resetPeakHold() }
                }

                syncSwitch(ctrlViews.switchAutoRange,
                    prefs.getBoolean("pref_auto_range", false)
                ) { _, checked ->
                    binding.spectrumView.setAutoRange(checked)
                    prefs.edit().putBoolean("pref_auto_range", checked).apply()
                }

                syncSwitch(ctrlViews.switchNoiseFloor,
                    prefs.getBoolean("pref_show_noise_floor", true)
                ) { _, checked ->
                    binding.spectrumView.setShowNoiseFloor(checked)
                    prefs.edit().putBoolean("pref_show_noise_floor", checked).apply()
                }

                syncSwitch(ctrlViews.switchPeakAnnotations,
                    prefs.getBoolean("pref_show_peak_annotations", true)
                ) { _, checked ->
                    binding.spectrumView.setShowPeakAnnotations(checked)
                    prefs.edit().putBoolean("pref_show_peak_annotations", checked).apply()
                }

                syncSwitch(ctrlViews.switchCrosshair,
                    prefs.getBoolean("pref_crosshair_enabled", true)
                ) { _, checked ->
                    binding.spectrumView.setCrosshairEnabled(checked)
                    prefs.edit().putBoolean("pref_crosshair_enabled", checked).apply()
                }

                syncSwitch(ctrlViews.switchAutoStretch,
                    prefs.getBoolean("pref_waterfall_auto_stretch", false)
                ) { _, checked ->
                    binding.waterfallView.setAutoStretch(checked)
                    prefs.edit().putBoolean("pref_waterfall_auto_stretch", checked).apply()
                }

                syncSwitch(ctrlViews.switchTimeRuler,
                    prefs.getBoolean("pref_waterfall_show_timestamp", true)
                ) { _, checked ->
                    binding.waterfallView.setShowTimestamp(checked)
                    prefs.edit().putBoolean("pref_waterfall_show_timestamp", checked).apply()
                }

                syncSwitch(ctrlViews.switchCentreMarker,
                    prefs.getBoolean("pref_waterfall_show_tuner_marker", true)
                ) { _, checked ->
                    binding.waterfallView.setShowTunerMarker(checked)
                    prefs.edit().putBoolean("pref_waterfall_show_tuner_marker", checked).apply()
                }

                // Slider values (no listener re-fire risk — just set value)
                val fillOpacity = prefs.getInt("pref_fill_opacity", 73) / 100f
                ctrlViews.sliderFillOpacity?.value = fillOpacity.coerceIn(0f, 1f)
                ctrlViews.tvFillOpacityValue?.text = "${(fillOpacity * 100).toInt()}%"

                val syncFloor = -(prefs.getInt("pref_db_min", 120).toFloat()).coerceIn(10f, 200f)
                ctrlViews.sliderSpecFloor?.value = syncFloor
                ctrlViews.tvSpecFloorValue?.text = syncFloor.toInt().toString()

                val syncCeiling = prefs.getInt("pref_db_max", 0).toFloat().coerceIn(-100f, 50f)
                ctrlViews.sliderSpecCeiling?.value = syncCeiling
                ctrlViews.tvSpecCeilingValue?.text = syncCeiling.toInt().toString()

                val peakDecay = prefs.getInt("pref_peak_decay", 2).toFloat().coerceIn(1f, 20f)
                ctrlViews.sliderPeakDecay?.value = peakDecay
                ctrlViews.tvPeakDecayValue?.text = peakDecay.toInt().toString()

                val brightness = (prefs.getInt("pref_waterfall_brightness", 50) - 50) / 100f
                ctrlViews.sliderWfBrightness?.value = brightness.coerceIn(-0.5f, 0.5f)
                ctrlViews.tvWfBrightnessValue?.text = "%+.2f".format(brightness)

                val contrast = prefs.getInt("pref_waterfall_contrast", 50) / 50f
                ctrlViews.sliderWfContrast?.value = contrast.coerceIn(0.2f, 3.0f)
                ctrlViews.tvWfContrastValue?.text = "%.1f×".format(contrast)

                // Frame avg spinner
                val frameAvgValues = listOf(1, 2, 4, 8, 16, 32)
                val savedAvg = prefs.getString("pref_frame_averaging", "1")?.toIntOrNull() ?: 1
                ctrlViews.spinnerFrameAvg?.setSelection(
                    frameAvgValues.indexOf(savedAvg).takeIf { it >= 0 } ?: 0, false)
            }

            ControlsPagerAdapter.TAB_RECORDING -> {
                val stats = v.dspStats.value
                if (stats.isRecordingIq) {
                    ctrlViews.btnRecordIq?.text = "Stop IQ"
                    ctrlViews.btnRecordIq?.setBackgroundColor(0xFFCC0000.toInt())
                } else {
                    ctrlViews.btnRecordIq?.text = "Record IQ"
                    ctrlViews.btnRecordIq?.setBackgroundColor(0xFF444444.toInt())
                }
                if (stats.isRecordingAudio) {
                    ctrlViews.btnRecordAudio?.text = "Stop Audio"
                    ctrlViews.btnRecordAudio?.setBackgroundColor(0xFFCC0000.toInt())
                } else {
                    ctrlViews.btnRecordAudio?.text = "Record Audio"
                    ctrlViews.btnRecordAudio?.setBackgroundColor(0xFF444444.toInt())
                }
            }

            ControlsPagerAdapter.TAB_SETTINGS -> {
                ctrlViews.switchOutOfBound?.setOnCheckedChangeListener(null)
                ctrlViews.switchOutOfBound?.isChecked = v.allowOutOfBand.value
                ctrlViews.switchOutOfBound?.setOnCheckedChangeListener { _, checked -> v.setAllowOutOfBand(checked) }
            }

            ControlsPagerAdapter.TAB_APRS -> {
                // The frequency/rate label is also kept live by the centerFreqHz/
                // sampleRate collectors in observeViewModel(); this covers the case
                // where the tab is (re)selected before those flows have re-emitted.
                if (v.demodMode.value == DemodMode.APRS) {
                    ctrlViews.tvAprsTabMode?.text =
                        "${"%.3f".format(v.centerFreqHz.value / 1e6)} MHz  |  ${v.sampleRate.value / 1000} kS/s"
                }
            }

            ControlsPagerAdapter.TAB_ACARS -> {
                // Same pattern as TAB_APRS above.
                if (v.demodMode.value == DemodMode.ACARS) {
                    ctrlViews.tvAcarsTabMode?.text =
                        "${"%.3f".format(v.centerFreqHz.value / 1e6)} MHz  |  ${v.sampleRate.value / 1000} kS/s"
                }
            }

            ControlsPagerAdapter.TAB_DMR -> {
                if (v.demodMode.value == DemodMode.DMR) {
                    ctrlViews.tvDmrTabMode?.text =
                        "${"%.3f".format(v.centerFreqHz.value / 1e6)} MHz  |  ${v.sampleRate.value / 1000} kS/s"
                    ctrlViews.tvDmrTabVocoder?.text =
                        VOCODER_STATUS_TEXT
                }
            }

            ControlsPagerAdapter.TAB_DIG -> {
                if (v.demodMode.value == DemodMode.DIG) {
                    ctrlViews.tvDigTabMode?.text =
                        "${"%.3f".format(v.centerFreqHz.value / 1e6)} MHz  |  ${v.sampleRate.value / 1000} kS/s"
                    ctrlViews.tvDigTabVocoder?.text =
                        VOCODER_STATUS_TEXT
                }
            }

            ControlsPagerAdapter.TAB_YSF -> {
                if (v.demodMode.value == DemodMode.YSF) {
                    ctrlViews.tvYsfTabMode?.text =
                        "${"%.3f".format(v.centerFreqHz.value / 1e6)} MHz  |  ${v.sampleRate.value / 1000} kS/s"
                    ctrlViews.tvYsfTabVocoder?.text =
                        VOCODER_STATUS_TEXT
                }
            }

            ControlsPagerAdapter.TAB_DSTAR -> {
                if (v.demodMode.value == DemodMode.DSTAR) {
                    ctrlViews.tvDstarTabMode?.text =
                        "${"%.3f".format(v.centerFreqHz.value / 1e6)} MHz  |  ${v.sampleRate.value / 1000} kS/s"
                    ctrlViews.tvDstarTabVocoder?.text =
                        VOCODER_STATUS_TEXT
                }
            }

            else -> Unit
        }
    }

    // ─── Tabbed controls drawer ───────────────────────────────────────────────

    /**
     * Sets up the ViewPager2 + TabLayout inside the bottom sheet.
     * Must be called before [setupControlsPanel] so that [ctrlViews] is ready
     * when the panel binds its listeners.
     *
     * ViewPager2 inflates pages lazily; the first two tabs (Mode and Tune) are
     * inflated immediately.  The pager's offscreenPageLimit is set to 5 so all
     * six tab views are created synchronously, which lets [ControlsTabManager]
     * return non-null views for every tab from the first call.
     */
    private fun setupTabsDrawer() {
        pagerAdapter = ControlsPagerAdapter()
        val pager = binding.controlViewPager
        pager.adapter = pagerAdapter
        // Keep all pages in memory so ctrlViews can access any tab immediately.
        pager.offscreenPageLimit = ControlsPagerAdapter.TAB_COUNT - 1
        // Disable nested scrolling on the RecyclerView inside VP2 so the
        // NestedScrollView (bottom sheet) handles the vertical scroll.
        pagerAdapter.attachNestedScrollWorkaround(pager)
        pager.isUserInputEnabled = false   // tabs switch via TabLayout, not swipe

        // ViewPager2's internal RecyclerView is not reliable about re-measuring
        // itself to the currently selected page's real content height once
        // several pages have been inflated (offscreenPageLimit keeps every tab
        // attached at once, and switching between already-cached pages does not
        // always trigger a fresh measure pass). Left alone, the pager's height
        // -- and therefore the drawer's scrollable range -- can stay stuck at
        // whichever tab happened to be current during the first layout pass.
        // That makes every other tab either cut off its last control or leave
        // scrollable blank space below it. Explicitly resize on every page
        // change so the scroll range always matches the visible tab.
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                pager.post { resizePagerToPage(position) }
            }
        })

        TabLayoutMediator(binding.controlTabLayout, pager) { tab, pos ->
            tab.text = ControlsPagerAdapter.TAB_TITLES[pos]
        }.attach()

        // Scroll to top of the drawer content whenever a tab label is tapped --
        // both when switching to a new tab and when re-tapping the current tab.
        binding.controlTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                binding.controlsScrollView.scrollTo(0, 0)
                syncTabControls(tab.position)
                pager.post { resizePagerToPage(tab.position) }
            }
            override fun onTabReselected(tab: TabLayout.Tab) {
                binding.controlsScrollView.scrollTo(0, 0)
                syncTabControls(tab.position)
                // Re-tapping the current tab doesn't fire ViewPager2's
                // OnPageChangeCallback (the page never changed), so refresh
                // the height here too in case this tab's content grew or
                // shrank while it was already selected.
                pager.post { resizePagerToPage(tab.position) }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
        })

        ctrlViews = ControlsTabManager(pagerAdapter)

        // Mode tab: build the demodulation-mode chip group once it's inflated.
        pager.post { setupDemodModeChips() }

        // Set up the Tune tab's tuning dial and step chips after layout is done.
        pager.post { setupTuneTab() }

        // Settings tab: open full settings + device info buttons
        pager.post { setupSettingsTab() }

        // APRS tab: wire up the embedded APRS monitor.
        pager.post { setupAprsTab() }

        // APRS tab is hidden by default; shown only when DemodMode.APRS is active.
        pager.post {
            binding.controlTabLayout.getTabAt(ControlsPagerAdapter.TAB_APRS)?.view
                ?.visibility = android.view.View.GONE
        }

        // ACARS tab: wire up the embedded ACARS monitor.
        pager.post { setupAcarsTab() }

        // ACARS tab is hidden by default; shown only when DemodMode.ACARS is active.
        pager.post {
            binding.controlTabLayout.getTabAt(ControlsPagerAdapter.TAB_ACARS)?.view
                ?.visibility = android.view.View.GONE
        }

        // DMR tab: wire up the embedded DMR frame monitor.
        pager.post { setupDmrTab() }

        // DMR tab is hidden by default; shown only when DemodMode.DMR is active.
        pager.post {
            binding.controlTabLayout.getTabAt(ControlsPagerAdapter.TAB_DMR)?.view
                ?.visibility = android.view.View.GONE
        }

        // YSF tab: wire up the embedded YSF frame monitor.
        pager.post { setupYsfTab() }

        // YSF tab is hidden by default; shown only when DemodMode.YSF is active.
        pager.post {
            binding.controlTabLayout.getTabAt(ControlsPagerAdapter.TAB_YSF)?.view
                ?.visibility = android.view.View.GONE
        }

        // D-STAR tab: wire up the embedded D-STAR frame monitor.
        pager.post { setupDstarTab() }

        // D-STAR tab is hidden by default; shown only when DemodMode.DSTAR is active.
        pager.post {
            binding.controlTabLayout.getTabAt(ControlsPagerAdapter.TAB_DSTAR)?.view
                ?.visibility = android.view.View.GONE
        }

        // Dig tab: wire up the embedded Dig frame monitor.
        pager.post { setupDigTab() }

        // Dig tab is hidden by default; shown only when DemodMode.DIG is active.
        pager.post {
            binding.controlTabLayout.getTabAt(ControlsPagerAdapter.TAB_DIG)?.view
                ?.visibility = android.view.View.GONE
        }

        // Scan tab: wire up the embedded frequency scanner.
        pager.post { setupScanTab() }

        // Scan tab is hidden by default; shown only when explicitly invoked via
        // showScanTab() (Scanner button / FAB) — it isn't tied to a DemodMode
        // like the protocol tabs above, so it starts hidden and stays hidden
        // until the user asks for it.
        pager.post {
            binding.controlTabLayout.getTabAt(ControlsPagerAdapter.TAB_SCAN)?.view
                ?.visibility = android.view.View.GONE
        }

        // Catch-all: every setup*Tab() call above is itself deferred via
        // pager.post, and several of them (chips, embedded monitors) change
        // their tab's natural height once they finish building. Queuing this
        // resize last means it runs after all of them, so the pager locks in
        // the true, fully-built height of whichever tab ends up selected --
        // not a stale pre-build measurement.
        pager.post { resizePagerToCurrentPage() }
    }

    /**
     * Explicitly measures [position]'s tab page and resizes the ViewPager2 to
     * match its real content height, then keeps the outer drawer's
     * NestedScrollView in sync so its scrollable range is recalculated too.
     * See the comment on the OnPageChangeCallback in [setupTabsDrawer] for why
     * this is necessary instead of relying on ViewPager2 to size itself.
     */
    private fun resizePagerToPage(position: Int) {
        val pager = binding.controlViewPager
        val page = pagerAdapter.getViewAt(position) ?: return
        if (pager.width == 0) return

        val widthSpec = android.view.View.MeasureSpec.makeMeasureSpec(
            pager.width, android.view.View.MeasureSpec.EXACTLY
        )
        val heightSpec = android.view.View.MeasureSpec.makeMeasureSpec(
            0, android.view.View.MeasureSpec.UNSPECIFIED
        )
        page.measure(widthSpec, heightSpec)
        val target = page.measuredHeight
        if (target <= 0) return

        val lp = pager.layoutParams
        if (lp.height != target) {
            lp.height = target
            pager.layoutParams = lp
            // The outer LinearLayout (pager + nothing else now that the old
            // static spacer is gone) and the NestedScrollView above it both
            // need to re-derive their content height from the pager's new
            // height before the scroll range is correct for this tab.
            binding.controlsScrollView.requestLayout()
        }
    }

    /** Resizes the pager to whichever tab is currently selected. */
    private fun resizePagerToCurrentPage() {
        resizePagerToPage(binding.controlViewPager.currentItem)
    }

    /**
     * Wires up the Tune tab:
     * - Tuning dial with protocol-aware step
     * - Step-size chip group
     * - Band preset chips
     * - Fine-tune switch
     * - Snap-to-channel switch
     */
    private fun setupTuneTab() {
        val dial = ctrlViews.tuningDial ?: return

        // Sync dial with current frequency and mode
        dial.currentFreqHz = viewModel.centerFreqHz.value
        dial.demodMode     = viewModel.demodMode.value.name

        dial.onStep = { deltaHz ->
            val current = viewModel.centerFreqHz.value
            var next = (current + deltaHz)
            if (snapToChannel) {
                next = FrequencyStepManager.snapToChannel(next, dial.activeStepHzPublic())
            }
            viewModel.setFrequency(next)
            binding.frequencyView.setFrequency(next)
            binding.spectrumView.setCenterFrequency(hardwareCenterFreqHz(next))
            binding.spectrumView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(next))
            binding.spectrumView.setDemodDialFrequency(next)
            binding.waterfallView.setCenterFrequency(hardwareCenterFreqHz(next))
            binding.waterfallView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(next))
            dial.currentFreqHz = next
        }

        // Fine-tune switch mirrors dial's fineMode
        ctrlViews.switchFineTune?.setOnCheckedChangeListener { _, checked ->
            dial.fineMode = checked
        }
        dial.setOnFineModeChangedListener { fine ->
            ctrlViews.switchFineTune?.isChecked = fine
        }

        // Snap switch
        ctrlViews.switchSnap?.isChecked = snapToChannel
        ctrlViews.switchSnap?.setOnCheckedChangeListener { _, checked ->
            snapToChannel = checked
        }

        // Step chips: populate with common steps for current mode
        setupStepChips()

        // Band preset chips
        setupBandPresetChips()
    }

    private fun setupStepChips() {
        val chipGroup = ctrlViews.chipGroupStep ?: return
        chipGroup.removeAllViews()
        val dial = ctrlViews.tuningDial
        val currentMode = viewModel.demodMode.value.name
        val freqHz      = viewModel.centerFreqHz.value

        // Build a contextual step list: recommended step first, plus all standard options
        val recommended = FrequencyStepManager.recommendedStep(freqHz, currentMode)
        val steps = listOf(
            FrequencyStepManager.StepSize.HZ_100,
            FrequencyStepManager.StepSize.KHZ_1,
            FrequencyStepManager.StepSize.KHZ_5,
            FrequencyStepManager.StepSize.KHZ_6_25,
            FrequencyStepManager.StepSize.KHZ_8_33,
            FrequencyStepManager.StepSize.KHZ_9,
            FrequencyStepManager.StepSize.KHZ_12_5,
            FrequencyStepManager.StepSize.KHZ_25,
            FrequencyStepManager.StepSize.KHZ_100,
            FrequencyStepManager.StepSize.KHZ_200,
            FrequencyStepManager.StepSize.MHZ_1,
        )

        steps.forEach { step ->
            val chip = com.google.android.material.chip.Chip(this).apply {
                text = step.label
                isCheckable = true
                isChecked = (step == recommended)
                setTextColor(if (step == recommended) 0xFF0D1117.toInt() else 0xFF00CCFF.toInt())
                setChipBackgroundColorResource(android.R.color.transparent)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        dial?.let { d ->
                            // Override the automatic step: set fineMode off and pick this step
                            d.fineMode = false
                            d.overrideStepHz = step.hz
                            ctrlViews.switchFineTune?.isChecked = false
                        }
                    }
                }
            }
            chipGroup.addView(chip)
        }
    }

    private fun setupBandPresetChips() {
        val chipGroup = ctrlViews.chipGroupBands ?: return
        chipGroup.removeAllViews()
        FrequencyStepManager.BAND_PRESETS.forEach { preset ->
            val chip = com.google.android.material.chip.Chip(this).apply {
                text = preset.name
                isCheckable = false
                setTextColor(0xFF00CCFF.toInt())
                setChipBackgroundColorResource(android.R.color.transparent)
                setOnClickListener {
                    val hz = preset.startHz
                    viewModel.setFrequency(hz)
                    binding.frequencyView.setFrequency(hz)
                    binding.spectrumView.setCenterFrequency(hardwareCenterFreqHz(hz))
                    binding.spectrumView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(hz))
                    binding.spectrumView.setDemodDialFrequency(hz)
                    binding.waterfallView.setCenterFrequency(hardwareCenterFreqHz(hz))
                    binding.waterfallView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(hz))
                    ctrlViews.tuningDial?.currentFreqHz = hz
                    // Auto-select recommended mode if it matches a known DemodMode
                    try {
                        val mode = DemodMode.valueOf(preset.defaultMode)
                        viewModel.setDemodMode(mode)
                        selectDemodChip(mode)
                        // setDemodMode() may restore a per-mode frequency snapshot
                        // that differs from the preset's hz -- reflect whichever
                        // frequency is actually now in effect.
                        syncFrequencyDisplay()
                    } catch (_: Exception) {}
                    showSnackbar("→ ${preset.name}: ${"%.3f".format(hz / 1e6)} MHz")
                }
            }
            chipGroup.addView(chip)
        }
    }

    private fun setupSettingsTab() {
        // Out-of-bound frequency switch — restore persisted state
        ctrlViews.switchOutOfBound?.isChecked = viewModel.allowOutOfBand.value
        ctrlViews.switchOutOfBound?.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAllowOutOfBand(isChecked)
        }

        ctrlViews.btnScanner?.setOnClickListener {
            showScanTab()
        }
        ctrlViews.btnDebugPanel?.setOnClickListener {
            startActivity(android.content.Intent(this,
                com.radiosport.ninegradio.ui.DebugPanelActivity::class.java))
        }
        ctrlViews.btnDeviceInfo?.setOnClickListener {
            showDeviceInfo()
        }
        ctrlViews.btnOpenSettings?.setOnClickListener {
            startActivity(android.content.Intent(this,
                com.radiosport.ninegradio.ui.SettingsActivity::class.java))
        }
        ctrlViews.btnBookmarks?.setOnClickListener {
            bookmarkLauncher.launch(Intent(this, BookmarkActivity::class.java))
        }

        // Version footer — set from BuildConfig so the XML placeholder never goes stale.
        ctrlViews.tvAppVersion?.text = "9GRadio v${com.radiosport.ninegradio.BuildConfig.VERSION_NAME}"

    } // end setupSettingsTab

    /**
     * Push the ViewModel's current center frequency to every display that
     * shows it (main frequency readout, spectrum, waterfall, tuning dial).
     * Called whenever the in-effect frequency may have changed without the
     * normal onFrequencyChanged callback firing -- e.g. after setDemodMode()
     * restores a per-mode frequency snapshot.
     */
    private fun syncFrequencyDisplay() {
        val hz = viewModel.centerFreqHz.value
        binding.frequencyView.setFrequency(hz)
        binding.spectrumView.setCenterFrequency(hardwareCenterFreqHz(hz))
        binding.spectrumView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(hz))
        binding.spectrumView.setDemodDialFrequency(hz)
        binding.waterfallView.setCenterFrequency(hardwareCenterFreqHz(hz))
        binding.waterfallView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(hz))
        ctrlViews.tuningDial?.currentFreqHz = hz
    }

    /** Helper called from ViewModel observation to keep dial in sync with mode changes. */
    private fun onDemodModeChanged(mode: DemodMode) {
        // setDemodMode() may have restored a per-mode frequency snapshot, so
        // refresh the main frequency display (and spectrum/waterfall) to
        // reflect the frequency now actually in effect for the new protocol.
        syncFrequencyDisplay()

        ctrlViews.tuningDial?.let { dial ->
            dial.demodMode = mode.name
            dial.overrideStepHz = null  // reset any manual step override
            setupStepChips()            // refresh step chips for new mode
        }
    }

    private fun selectDemodChip(mode: DemodMode) {
        // Keep the spectrum view's Auto dB Range tier (see
        // SpectrumView.setDemodMode / AutoRangeTier) in sync with the
        // actually active protocol every time the UI reflects a mode change
        // -- this function is the single place every mode-change path
        // (chip tap, scan-tab restore, memory recall, etc.) already calls to
        // update the UI, so it's the safest single hook point rather than
        // patching every viewModel.setDemodMode()/svc.setDemodMode() call
        // site individually. Done before the chipGroup-null early return so
        // it still applies even when the Demod tab isn't the one currently
        // visible.
        binding.spectrumView.setDemodMode(mode)
        val chipGroup = ctrlViews.chipGroupDemod ?: return
        for (i in 0 until chipGroup.childCount) {
            val chip = chipGroup.getChildAt(i) as? com.google.android.material.chip.Chip ?: continue
            if (chip.tag == mode) { chip.isChecked = true; break }
        }
    }

    private fun setupBottomSheet() {
        val behavior = BottomSheetBehavior.from(binding.controlsBottomSheet)

        // isFitToContents=false is REQUIRED for expandedOffset to be honoured.
        // The default (true) ignores expandedOffset and expands to full screen height.
        behavior.isFitToContents = false

        behavior.peekHeight = (56 * resources.displayMetrics.density).toInt()
        behavior.state = BottomSheetBehavior.STATE_EXPANDED

        // Default to Tune tab on launch
        binding.controlViewPager.setCurrentItem(ControlsPagerAdapter.TAB_TUNE, false)

        fun applyExpandedOffset() {
            val wf = binding.waterfallView
            if (wf.height == 0) return

            // Find wf's top Y relative to the CoordinatorLayout (binding.root).
            var y = 0
            var v: android.view.View = wf
            while (v !== binding.root) {
                y += v.top
                v = v.parent as? android.view.View ?: break
            }

            // The sheet's top when fully expanded = waterfallTop + waterfallHeight/5.
            // This leaves the spectrum + top 4/5 of the waterfall always visible.
            val expandedOff = y + wf.height / 5
            if (expandedOff <= 0) return
            if (behavior.expandedOffset != expandedOff) {
                behavior.expandedOffset = expandedOff
            }

            // SCROLL FIX:
            // BottomSheetBehavior translates the sheet view down by expandedOffset,
            // but the inner NestedScrollView's measured height stays equal to the
            // full CoordLayout height minus the sticky header (handle + TabLayout).
            // Its scroll range = contentHeight − measuredHeight, which is short by
            // exactly expandedOffset px -- content at the bottom is unreachable.
            // Setting paddingBottom = expandedOffset + navBarHeightPx on the inner
            // NestedScrollView (clipToPadding=false) extends the effective scrollable
            // height so every control can be scrolled into view.
            // The outer LinearLayout is not a scroll view so it must NOT get this
            // padding (it would just push its children down and break the layout).
            val targetPad = expandedOff + navBarHeightPx
            val scrollView = binding.controlsScrollView
            if (scrollView.paddingBottom != targetPad) {
                scrollView.setPadding(0, 0, 0, targetPad)
            }
        }

        binding.waterfallView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyExpandedOffset()
        }
        binding.waterfallView.post { applyExpandedOffset() }
    }

    private fun setupFabMenu() {
        binding.fabScan.setOnClickListener {
            showScanTab()
        }
        binding.fabBookmark.setOnClickListener {
            bookmarkCurrentFrequency()
        }

        // Adjust FAB bottom margins to respect the navigation bar inset so they
        // don't overlap with gesture handles or button nav bars.
        val density = resources.displayMetrics.density
        val baseMargins = listOf(
            binding.fabBookmark to (160 * density).toInt(),
            binding.fabScan    to (210 * density).toInt()
        )
        // We piggyback on the controlTabLayout insets listener which fires first;
        // apply a separate listener here so FABs update independently.
        ViewCompat.setOnApplyWindowInsetsListener(binding.fabBookmark) { _, insets ->
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            baseMargins.forEach { (fab, base) ->
                (fab.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                    lp.bottomMargin = base + navBottom
                    fab.layoutParams = lp
                }
            }
            insets
        }
    }

    // ─── IF Bandwidth helpers (class-level so both setupControlsPanelDeferred ──
    // ─── and observeViewModel can reach them) ─────────────────────────────────

    private val BW_STEPS = intArrayOf(
        100, 150, 200, 250, 300, 400, 500,          // idx  0- 6: CW narrow  (Hz)
        600, 800,                                    // idx  7- 8: CW wide / RTTY
        1_000, 1_200, 1_500, 1_800, 2_000,          // idx  9-13: SSB voice
        2_400, 2_700, 3_000, 3_300, 3_600,          // idx 14-18: SSB wide / data
        4_000, 5_000, 6_000, 8_000, 10_000,         // idx 19-23: AM / utility
        12_500, 15_000, 25_000,                     // idx 24-26: NFM / ACARS
        50_000, 100_000, 200_000                     // idx 27-29: WFM / broadcast
    )

    /**
     * Per-mode allowed index range within BW_STEPS.
     * Pair(minIdx, maxIdx) -- both inclusive.
     * Only the values in `minIdx..maxIdx` will be reachable by the slider.
     */
    private fun bwRangeFor(mode: DemodMode): Pair<Int,Int> = when (mode) {
        DemodMode.CW,
        DemodMode.CWR  -> Pair(0, 8)    // 100 Hz - 800 Hz
        DemodMode.USB,
        DemodMode.LSB  -> Pair(7, 18)   // 600 Hz - 3.6 kHz
        DemodMode.DSB  -> Pair(9, 19)   // 1.0 kHz - 4 kHz
        DemodMode.AM   -> Pair(14, 24)  // 2.4 kHz - 12.5 kHz
        DemodMode.NFM,
        DemodMode.FLEX -> Pair(19, 26)  // 4 kHz - 25 kHz
        // FIX 20: range widened from (24,25)=12.5-15 kHz to match DemodMode.APRS's
        // new 48 kHz default IF rate (was incorrectly grouped with NFM's 16 kHz
        // bucket). 25 kHz - 200 kHz lets the slider reach "Auto" (48 kHz+) again.
        DemodMode.APRS -> Pair(24, 29)  // 12.5 kHz - 200 kHz (Auto sits near top)
        DemodMode.ACARS -> Pair(24, 26) // 12.5 kHz - 25 kHz
        DemodMode.FM   -> Pair(24, 27)  // 12.5 kHz - 50 kHz
        // Digital voice — all use 12.5 kHz channels (NXDN also supports 6.25 kHz
        // but 12.5 kHz is the safe minimum for the IF pre-filter on most hardware)
        DemodMode.DMR,
        DemodMode.P25,
        DemodMode.NXDN,
        DemodMode.DSTAR,
        DemodMode.YSF,
        DemodMode.M17,
        DemodMode.DPMR,
        DemodMode.DIG  -> Pair(24, 25)  // 12.5 kHz - 15 kHz
        DemodMode.WFM,
        DemodMode.WFM_STEREO -> Pair(24, 29) // 12.5 kHz - 200 kHz
        DemodMode.DRM  -> Pair(14, 21)  // 2.4 kHz - 5 kHz
        DemodMode.ADSB -> Pair(27, 29)  // 50 kHz - 200 kHz
        DemodMode.RAW  -> Pair(0,  29)  // full range -- pass-through
    }

    private fun formatIfBw(hz: Int, isAuto: Boolean): String =
        if (isAuto) "Auto"
        else if (hz < 1_000) "${hz} Hz"
        else if (hz % 1_000 == 0) "${hz / 1_000} kHz"
        else "${"%.1f".format(hz / 1_000.0)} kHz"

    /** Return the index in BW_STEPS nearest to [hz], clamped to [range]. */
    private fun nearestBwIdx(hz: Int, range: Pair<Int,Int> = Pair(0, BW_STEPS.size - 1)): Int {
        var best = range.first
        var bestDist = Int.MAX_VALUE
        for (i in range.first..range.second) {
            val d = kotlin.math.abs(BW_STEPS[i] - hz)
            if (d < bestDist) { bestDist = d; best = i }
        }
        return best
    }

    /** Apply the correct valueFrom/valueTo for [mode] and snap the thumb. */
    private fun applyBwRange(mode: DemodMode, currentHz: Int) {
        val (lo, hi) = bwRangeFor(mode)
        val slider   = ctrlViews.sliderIfBandwidth ?: return
        // Material Slider requires value to be inside [valueFrom, valueTo] before
        // changing the bounds -- clamp first, then widen/narrow.
        val idx      = nearestBwIdx(currentHz, Pair(lo, hi)).toFloat()
        // Always set value within the current range before changing bounds to
        // avoid the "value outside range" IllegalStateException.
        val safeIdx  = slider.value.coerceIn(lo.toFloat(), hi.toFloat())
        slider.value = safeIdx          // clamp first
        slider.valueFrom = lo.toFloat()
        slider.valueTo   = hi.toFloat()
        slider.value     = idx          // snap to correct position
    }

    // ─── ViewModel observation ────────────────────────────────────────────────

    /**
     * Compute the channel highlight bandwidth for the given mode + IF setting.
     *
     * For narrow modes the effective passband IS the IF bandwidth (the
     * ComplexDecimator defines the channel before the demodulator). For WFM
     * the passband equals the actual wfmIfRate for the current device rate --
     * NOT a fixed 200 kHz constant, because at low sample rates (≤ 250 kS/s)
     * the full device bandwidth IS the IF, and at high rates the rational
     * resampler in DspEngine.wfmIfRate() caps it at 250 kS/s.
     *
     * Class-level member function so it is accessible from both
     * setupControlsPanelDeferred() (sample-rate chip clicks) and
     * observeViewModel() (mode / IF-bandwidth observers).
     */
    private fun channelHighlightHz(mode: DemodMode, ifBwHz: Int): Long {
        val isNarrow = when (mode) {
            DemodMode.AM, DemodMode.NFM, DemodMode.FM,
            DemodMode.USB, DemodMode.LSB,
            DemodMode.CW, DemodMode.CWR,
            DemodMode.DSB, DemodMode.APRS,
            DemodMode.ACARS, DemodMode.FLEX -> true
            else -> false
        }
        if (!isNarrow) {
            return when (mode) {
                DemodMode.WFM, DemodMode.WFM_STEREO ->
                    // Use the actual WFM IF rate for the current device rate so the
                    // spectrum highlight correctly spans the full WFM passband at every
                    // sample rate (e.g. 240 kHz at 1.92 MS/s, capped at 250 kHz max).
                    DspEngine.wfmIfRate(viewModel.sampleRate.value).toLong()
                else -> 0L
            }
        }
        // ifBwHz == 0 means "mode default" -- use defaultBwHz (actual channel passband width)
        // NOT defaultIfRate() which is the DSP decimation rate and does not represent the
        // visible filter width shown in the spectrum display. For example, NFM defaultBwHz=12500Hz
        // (correct ±6.25kHz passband) vs defaultIfRate()=16000Hz (DSP intermediate rate).
        val bw = if (ifBwHz > 0) ifBwHz else mode.defaultBwHz.coerceAtLeast(DspEngine.MIN_IF_BANDWIDTH_HZ)
        // USB/LSB: SsbDemodulator always applies its own 3 kHz LPF internally, so the
        // visible passband is always ≤ 3 kHz regardless of the IF decimation rate the
        // slider may have set.  Cap the highlight to prevent the spectrum from ever
        // showing a wider band (e.g. the old 12 kHz IF rate) than what the demodulator
        // actually passes.
        if (mode == DemodMode.USB || mode == DemodMode.LSB) {
            return minOf(bw.toLong(), 3_000L)
        }
        return bw.toLong()
    }

    /**
     * Sideband alignment for the spectrum's channel-highlight box, passed to
     * [SpectrumView.setDemodChannelBandwidth].
     *
     * The dialed/tuned frequency in USB/LSB is one EDGE of the audio
     * passband, not its midpoint (the filter passband sits entirely above
     * the dial in USB, and entirely below it in LSB) -- unlike AM/FM/NFM/etc,
     * where the dial frequency is the channel centre.
     *
     *   USB -> +1 (passband to the right of / above the dial frequency)
     *   LSB -> -1 (passband to the left of / below the dial frequency)
     *   all other modes -> 0 (symmetric, centred on the dial frequency)
     */
    private fun channelHighlightSideband(mode: DemodMode): Int = when (mode) {
        DemodMode.USB -> 1
        DemodMode.LSB -> -1
        else -> 0
    }

    /**
     * The frequency the spectrum/waterfall *overlays* (passband highlight box,
     * dial marker, axis labels, ref markers, bookmarks) should be drawn *as if*
     * the display were centred on -- NOT necessarily the literal RTL-SDR tuner
     * frequency. Read by SpectrumView/WaterfallView.setCenterFrequency().
     *
     * This is intentionally a graphics-only value and is allowed to diverge
     * from the real hardware centre (see [trueHardwareCenterFreqHz] below,
     * which tracks the real value and keeps the FFT trace/waterfall bitmap in
     * sync with whatever this function returns). Screen pixel = width/2 is
     * always defined to be this frequency; every overlay is positioned
     * relative to it.
     *
     * Always just [dialHz] -- the literal tuned/dialed frequency shown in the
     * digit readout, for every mode including USB/LSB.
     *
     * Screen pixel = width/2 must be a single, mode-independent reference
     * point that every overlay (passband box, dial/tuner marker, axis
     * labels, ref markers, bookmarks) agrees on, or those overlays visibly
     * separate from each other and from the true-frequency-indexed FFT
     * trace/waterfall bitmap (which is always reconciled against this value
     * via [trueHardwareCenterFreqHz] -- see WaterfallView/SpectrumView's
     * hwOffsetHz math). Baking the SSB_BFO_OFFSET_HZ hardware shift into
     * this value -- with either sign -- pulls the dial away from the literal
     * screen centre by that offset (or, with a sign flip, by DOUBLE that
     * offset relative to the other sideband), which is exactly what visibly
     * shifted the passband box off-centre and off the dial/tuner marker in
     * previous versions of this function. The dial belongs at width/2 in
     * every mode; the passband box already accounts for the BFO-driven
     * sideband asymmetry on its own via [channelHighlightSideband] /
     * SpectrumView's sideband handling, and the real signal's true RF
     * position (offset from the dial by SSB_BFO_OFFSET_HZ) is handled by
     * the hwOffsetHz reconciliation in the trace/waterfall renderers -- not
     * by moving the screen centre.
     */
    private fun hardwareCenterFreqHz(dialHz: Long, mode: DemodMode = viewModel.demodMode.value): Long =
        dialHz

    /**
     * The *actual* RF frequency the RTL-SDR hardware is tuned to right now --
     * must always mirror [DspEngine.setCarrierFrequency]'s hardware formula
     * exactly (`dialHz + SSB_BFO_OFFSET_HZ` for *both* USB and LSB, symmetric,
     * per that function's doc comment; no offset for any other mode). Never
     * "correct" this to match [hardwareCenterFreqHz]'s per-sideband sign flip
     * -- the two are allowed to disagree, and disagreeing is expected in
     * USB/LSB.
     *
     * Read by SpectrumView/WaterfallView.setHardwareTunedFrequency(). Those
     * views' raw FFT trace / waterfall bitmap are bin-indexed off this real
     * tuned frequency (that's where the SDR hardware actually put the
     * signal), while every overlay is positioned relative to
     * [hardwareCenterFreqHz]. The views reconcile the two internally so the
     * trace/waterfall line up with the overlays on screen whenever the two
     * helpers disagree -- passing the same graphics-only value to both
     * setters here would defeat that and misplace the trace/waterfall
     * instead of the overlays.
     */
    private fun trueHardwareCenterFreqHz(dialHz: Long, mode: DemodMode = viewModel.demodMode.value): Long =
        when (mode) {
            DemodMode.USB, DemodMode.LSB -> dialHz + DspEngine.SSB_BFO_OFFSET_HZ
            else -> dialHz
        }

    private fun observeViewModel() {
        // Use plain collect (not collectLatest) for spectrum and waterfall.
        // collectLatest cancels the running lambda the moment a newer value arrives.
        // Spectrum updates come at ~15 fps; postInvalidateOnAnimation schedules the
        // actual draw for the next vsync -- if collectLatest cancels the lambda before
        // that vsync fires, the invalidate call is dropped and nothing ever redraws.
        // plain collect processes every emission to completion before taking the next.
        // Mirror auto-range computed values to WaterfallView so both views
        // always share the same dB scale when Auto dB Range is active.
        binding.spectrumView.onAutoRangeChanged = { floor: Float, ceiling: Float ->
            binding.waterfallView.setDynamicRange(floor, ceiling)
            // Keep the Display-tab sliders live. The Slider requires values to be
            // exact multiples of stepSize(1.0) so we must round before setting.
            ctrlViews.sliderSpecFloor?.let { sl ->
                val rounded = kotlin.math.round(floor).toFloat().coerceIn(sl.valueFrom, sl.valueTo)
                if (sl.value != rounded) sl.value = rounded
                ctrlViews.tvSpecFloorValue?.text = rounded.toInt().toString()
            }
            ctrlViews.sliderSpecCeiling?.let { sl ->
                val rounded = kotlin.math.round(ceiling).toFloat().coerceIn(sl.valueFrom, sl.valueTo)
                if (sl.value != rounded) sl.value = rounded
                ctrlViews.tvSpecCeilingValue?.text = rounded.toInt().toString()
            }
        }

        // Force the waterfall's zoom/pan to always follow the spectrum's.
        // WaterfallView used to run its own independent
        // ScaleGestureDetector/GestureDetector for pinch-zoom/pan, mirrored
        // back to SpectrumView via a matching onZoomPanChanged callback.
        // That two-way, best-effort mirroring is what allowed the waterfall
        // to advance its own zoom/pan state ahead of a round trip and
        // "stretch" out of line with the spectrum (the FFT trace and
        // waterfall bitmap ending up at different widths/offsets even
        // though the underlying frequency data was correctly aligned -- see
        // setHardwareTunedFrequency's doc comment for the *other* kind of
        // trace/waterfall misalignment this is not).
        //
        // WaterfallView's pinch-zoom gesture handling is now disabled at
        // the source (see its "Gesture detectors" section), so
        // SpectrumView is the single source of truth: only its callback is
        // wired, and it always pushes the waterfall into lock-step via
        // setZoomPan(), which no-ops when the values already match.
        // WaterfallView.onZoomPanChanged is intentionally left unwired --
        // it can no longer fire (zoom/pan gestures on the waterfall are a
        // no-op), but leaving the one-way wiring explicit here (rather than
        // a mutual mirror) guarantees the waterfall can never again drive
        // its own zoom/pan independently of the spectrum.
        binding.spectrumView.onZoomPanChanged = { zoom, offsetHz ->
            binding.waterfallView.setZoomPan(zoom, offsetHz)
        }

        lifecycleScope.launch {
            viewModel.spectrumData.collect { data ->
                binding.spectrumView.updateSpectrum(data, viewModel.peakData.value)
                binding.waterfallView.addLine(data)
            }
        }
        // deviceStatus drives the hardware-measured signal level and S-meter only.
        // Squelch open/closed is driven by dspStats below so it always reflects the
        // live value computed by DspEngine rather than a stale .value snapshot.
        lifecycleScope.launch {
            viewModel.deviceStatus.collectLatest { status ->
                updateSignalMeter(status.signalStrengthDb)
                binding.tvSignalDb.text = "${"%.1f".format(status.signalStrengthDb)} dBFS"
            }
        }
        // dspStats carries squelchOpen, signalDb, demodMode and sampleRate -- all
        // computed live inside DspEngine.processIqBlock on the DSP coroutine.
        // Reading squelchOpen here (from the emitted stats object) is always current;
        // the old code read viewModel.dspStats.value inside deviceStatus.collectLatest
        // which was a snapshot of the *previous* block's stats → always showed "CLOSED".
        lifecycleScope.launch {
            viewModel.dspStats.collect { stats ->
                binding.tvDemodMode.text = stats.demodMode
                // Display the ViewModel's authoritative sample rate (what was intentionally set)
                // rather than stats.sampleRate, which can transiently report 562 500 Hz when
                // the RTL2832U rsamp_ratio registers reset during a stream restart.
                val displayRate = viewModel.sampleRate.value
                binding.tvSampleRate.text = if (displayRate >= 1_000_000)
                    "${"%.3f".format(displayRate / 1e6)} MS/s"
                else
                    "${displayRate / 1000} kS/s"
                binding.tvSquelchStatus.text = if (stats.squelchOpen) "OPEN" else "CLOSED"
                binding.tvSquelchStatus.setTextColor(
                    if (stats.squelchOpen) 0xFF00FF00.toInt() else 0xFFFF4444.toInt()
                )
            }
        }
        lifecycleScope.launch {
            viewModel.connectionState.collectLatest { state ->
                when (state) {
                    is RtlSdrService.ConnectionState.Connected -> {
                        updateConnectionUi(true)
                        binding.tvDeviceName.text = state.deviceName
                        showSnackbar("Connected: ${state.deviceName}")
                        // Auto-enable out-of-band for RTL-SDR Blog V4 if not already on
                        if (viewModel.isV4 && !viewModel.allowOutOfBand.value) {
                            viewModel.setAllowOutOfBand(true)
                            ctrlViews.switchOutOfBound?.isChecked = true
                        }
                        // Apply all saved settings now that the device is open
                        applyAllSettings()
                    }
                    is RtlSdrService.ConnectionState.Disconnected -> updateConnectionUi(false)
                    is RtlSdrService.ConnectionState.Connecting -> {
                        binding.tvDeviceName.text = "Connecting…"
                        binding.progressConnect.isVisible = true
                    }
                    is RtlSdrService.ConnectionState.Error -> {
                        updateConnectionUi(false)
                        showSnackbar("Error: ${state.message}")
                    }
                }
            }
        }
        // (channelHighlightHz is now a private member function -- see below)

        lifecycleScope.launch {
            viewModel.demodMode.collectLatest { mode ->
                // Update chip selection
                ctrlViews.chipGroupDemod?.let { chipGroup ->
                    for (i in 0 until chipGroup.childCount) {
                        val chip = chipGroup.getChildAt(i) as? Chip ?: continue
                        chip.isChecked = (chip.tag as? DemodMode) == mode
                    }
                }
                // Switching into/out of USB or LSB re-tunes the hardware (see
                // DspEngine.setCarrierFrequency's SSB_BFO_OFFSET_HZ) even though the
                // dial frequency itself doesn't change, so the spectrum/waterfall
                // must be re-centred here too -- otherwise the FFT trace stays
                // aligned to the old hardware frequency and drifts out of the
                // passband highlight box the moment the mode switches.
                binding.spectrumView.setCenterFrequency(hardwareCenterFreqHz(viewModel.centerFreqHz.value, mode))
                binding.spectrumView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(viewModel.centerFreqHz.value, mode))
                binding.spectrumView.setDemodDialFrequency(viewModel.centerFreqHz.value)
                binding.waterfallView.setCenterFrequency(hardwareCenterFreqHz(viewModel.centerFreqHz.value, mode))
                binding.waterfallView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(viewModel.centerFreqHz.value, mode))
                // Update channel bandwidth highlight using current IF bandwidth
                val ifBw = viewModel.ifBandwidthHz.value
                binding.spectrumView.setDemodChannelBandwidth(channelHighlightHz(mode, ifBw), channelHighlightSideband(mode))
                // Re-constrain the bandwidth slider to this mode's supported range.
                // Use the current effective bandwidth (or mode default if auto).
                val defaultBw = mode.defaultBwHz.coerceAtLeast(DspEngine.MIN_IF_BANDWIDTH_HZ)
                val effectiveBw = if (ifBw > 0) ifBw else defaultBw
                applyBwRange(mode, effectiveBw)
                // Sync tuning dial and step chips with the new mode
                onDemodModeChanged(mode)
                // setDemodMode() may have just restored a per-protocol Display/RF
                // snapshot into defaultSharedPreferences (frame averaging, dB
                // floor/ceiling, theme, palette, etc.) — push it into the engine
                // and drawer UI now, otherwise it sits unapplied until some other
                // unrelated trigger happens to call applyDisplayPreferences().
                applyDisplayPreferences()
                if (::ctrlViews.isInitialized) {
                    binding.controlTabLayout.selectedTabPosition.takeIf { it >= 0 }
                        ?.let { syncTabControls(it) }
                }
                // Show/hide APRS drawer tab
                observeAprsMode(mode)
                // Show/hide ACARS drawer tab
                observeAcarsMode(mode)
                // Show/hide digital voice protocol drawer tabs
                observeDmrMode(mode)
                observeYsfMode(mode)
                observeDstarMode(mode)
            }
        }

        // Keep the APRS and ACARS tab frequency/rate labels always current.
        // Previously these labels were only written once, when the tab became
        // visible (mode switch) -- subsequent tuning (dial, band presets, step
        // chips, scanner hits, bookmark loads, freq +/- buttons) never touched
        // them, so they went stale until the user left and re-entered the mode.
        // Driving them off the centerFreqHz/sampleRate flows means every
        // frequency-changing code path is covered automatically, with no need
        // to hunt down and patch each call site individually.
        lifecycleScope.launch {
            viewModel.centerFreqHz.collect { hz ->
                val mode = viewModel.demodMode.value
                val mhz  = "%.3f".format(hz / 1e6)
                val rate = viewModel.sampleRate.value / 1000
                when (mode) {
                    DemodMode.APRS  -> ctrlViews.tvAprsTabMode?.text  = "$mhz MHz  |  $rate kS/s"
                    DemodMode.ACARS -> ctrlViews.tvAcarsTabMode?.text = "$mhz MHz  |  $rate kS/s"
                    DemodMode.DMR   -> ctrlViews.tvDmrTabMode?.text   = "$mhz MHz  |  $rate kS/s"
                    DemodMode.DIG   -> ctrlViews.tvDigTabMode?.text   = "$mhz MHz  |  $rate kS/s"
                    DemodMode.YSF   -> ctrlViews.tvYsfTabMode?.text   = "$mhz MHz  |  $rate kS/s"
                    DemodMode.DSTAR -> ctrlViews.tvDstarTabMode?.text = "$mhz MHz  |  $rate kS/s"
                    else -> Unit
                }
            }
        }
        lifecycleScope.launch {
            viewModel.sampleRate.collect { rate ->
                val mode = viewModel.demodMode.value
                val mhz  = "%.3f".format(viewModel.centerFreqHz.value / 1e6)
                val ks   = rate / 1000
                when (mode) {
                    DemodMode.APRS  -> ctrlViews.tvAprsTabMode?.text  = "$mhz MHz  |  $ks kS/s"
                    DemodMode.ACARS -> ctrlViews.tvAcarsTabMode?.text = "$mhz MHz  |  $ks kS/s"
                    DemodMode.DMR   -> ctrlViews.tvDmrTabMode?.text   = "$mhz MHz  |  $ks kS/s"
                    DemodMode.DIG   -> ctrlViews.tvDigTabMode?.text   = "$mhz MHz  |  $ks kS/s"
                    DemodMode.YSF   -> ctrlViews.tvYsfTabMode?.text   = "$mhz MHz  |  $ks kS/s"
                    DemodMode.DSTAR -> ctrlViews.tvDstarTabMode?.text = "$mhz MHz  |  $ks kS/s"
                    else -> Unit
                }
            }
        }

        // Keep the spectrum highlight in sync whenever the IF bandwidth slider moves.
        lifecycleScope.launch {
            viewModel.ifBandwidthHz.collect { ifBw ->
                val mode = viewModel.demodMode.value
                binding.spectrumView.setDemodChannelBandwidth(channelHighlightHz(mode, ifBw), channelHighlightSideband(mode))
            }
        }
        lifecycleScope.launch {
            viewModel.bookmarks.collect { bms ->
                binding.spectrumView.clearBookmarks()
                bms.forEach { bm -> binding.spectrumView.addBookmark(bm.frequencyHz, bm.label) }
            }
        }

        // Propagate FFT size changes to the DSP engine and views immediately.
        // Without this observer the engine only receives the new size via the
        // next service call, and the views only see it via the next spectrum
        // array from DspEngine -- which may be delayed or not arrive at all if
        // the device is not yet connected.
        lifecycleScope.launch {
            viewModel.fftSize.collectLatest { size ->
                // Push to engine/service in case it was set before the service bound
                // (setFftSize already guards against no-op when size is unchanged).
                sdrService?.setFftSize(size)
                // Inform views of the new effective sample rate (accounting for decimation)
                // so they pre-warm their internal state (e.g. peak-hold arrays).
                val effectiveRate = viewModel.sampleRate.value / viewModel.decimation.value.coerceAtLeast(1)
                binding.spectrumView.setSampleRate(effectiveRate)
                binding.waterfallView.setSampleRate(effectiveRate)
            }
        }

        // Propagate decimation changes to the DSP engine and views immediately.
        lifecycleScope.launch {
            viewModel.decimation.collectLatest { factor ->
                sdrService?.setFftDecimation(factor)
                val effectiveRate = viewModel.sampleRate.value / factor.coerceAtLeast(1)
                binding.spectrumView.setSampleRate(effectiveRate)
                binding.waterfallView.setSampleRate(effectiveRate)
            }
        }
    }

    // ─── USB handling ─────────────────────────────────────────────────────────

    private fun handleUsbDeviceAttached(device: UsbDevice) {
        // While a USB → Rtl-sdr Driver switch is in progress, Android re-enumerates the
        // dongle after we release the interface and fires ACTION_USB_DEVICE_ATTACHED.
        // Suppress that event so it doesn't immediately reconnect USB and undo the switch.
        if (suppressUsbAttach) {
            android.util.Log.i("MainActivity", "handleUsbDeviceAttached suppressed during driver switch")
            return
        }
        val usbManager = getSystemService(UsbManager::class.java)
        if (!usbManager.hasPermission(device)) {
            // FLAG_MUTABLE is required (API 31+) so the system can inject
            // EXTRA_PERMISSION_GRANTED and EXTRA_DEVICE into the broadcast.
            // FLAG_IMMUTABLE silently drops those extras, so permission always
            // appears denied.
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT

            // Android 14+ (API 34) bans FLAG_MUTABLE with an implicit Intent.
            // Making the Intent explicit (setPackage) satisfies the restriction while
            // keeping FLAG_MUTABLE so the system can inject EXTRA_PERMISSION_GRANTED
            // and EXTRA_DEVICE into the delivered broadcast.
            val permissionIntent = PendingIntent.getBroadcast(
                this, 0,
                Intent("com.radiosport.ninegradio.USB_PERMISSION").setPackage(packageName),
                flags
            )

            val permReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        viewModel.connectDevice(device)
                    } else {
                        showSnackbar("USB permission denied")
                    }
                    try { unregisterReceiver(this) } catch (_: Exception) {}
                    if (pendingPermReceiver === this) pendingPermReceiver = null
                }
            }

            // RECEIVER_EXPORTED is required here: on Android 12+ (API 31+), UsbManager
            // sends the permission result broadcast with the system UID as the caller.
            // RECEIVER_NOT_EXPORTED silently drops broadcasts from outside the app UID,
            // which means the permission dialog result is never delivered and the device
            // never connects. Using RECEIVER_EXPORTED is safe because the custom action
            // string limits exposure, and a spoofed grant cannot actually open the device.
            //
            // Clean up any previous pending receiver before registering a new one.
            pendingPermReceiver?.let {
                try { unregisterReceiver(it) } catch (_: Exception) {}
            }
            pendingPermReceiver = permReceiver
            ContextCompat.registerReceiver(
                this,
                permReceiver,
                IntentFilter("com.radiosport.ninegradio.USB_PERMISSION"),
                ContextCompat.RECEIVER_EXPORTED
            )

            usbManager.requestPermission(device, permissionIntent)
        } else {
            viewModel.connectDevice(device)
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Returns gain in dB for [index], resolved against the active source's gain table.
     * Falls back to the USB device table (the common R820T table) when no source is connected.
     */
    private fun gainDbAtIndex(index: Int): Float {
        val src = sdrService?.source ?: viewModel.getService()?.source
        return if (src != null) {
            // IqSource.getGainDb() returns the dB for the *current* index, not for an
            // arbitrary index.  Ask for the dB via getGainDb() only when index matches,
            // otherwise fall back to the USB lookup table (both sources use the same
            // R820T values in practice).
            if (index == src.getGainIndex()) src.getGainDb()
            else RtlSdrDevice.GAIN_TABLE_DB_TENTHS.getOrElse(index) { 0 } / 10f
        } else {
            RtlSdrDevice.GAIN_TABLE_DB_TENTHS.getOrElse(index) { 0 } / 10f
        }
    }

    /** Update the gain dB label from a gain-table index. */
    private fun syncGainLabel(index: Int) {
        val db = gainDbAtIndex(index)
        if (::ctrlViews.isInitialized) ctrlViews.tvGainValue?.text = "${"%.1f".format(db)} dB"
    }

    private fun applyAllSettings() {
        val v = viewModel
        val svc = viewModel.getService() ?: sdrService ?: return
        val ctrl = if (::ctrlViews.isInitialized) ctrlViews else null
        val sliderGain = ctrl?.sliderGain
        val switchTunerAgc = ctrl?.switchTunerAgc
        val switchHardwareAgc = ctrl?.switchHardwareAgc
        svc.setFrequency(v.centerFreqHz.value)
        svc.setSampleRate(v.sampleRate.value)
        svc.setTunerAgc(v.tunerAgcEnabled.value)
        svc.setHardwareAgc(v.hardwareAgcEnabled.value)
        // Guard: RtlSdrDevice.setGain() writes LNA/Mixer-auto=OFF unconditionally;
        // calling it when tuner AGC is enabled would override the AGC mode just set.
        if (!v.tunerAgcEnabled.value) svc.setGain(v.gainIndex.value)
        // Sync gain UI to the authoritative ViewModel values (persisted across restarts).
        // Recalibrate the slider range using the active source's gain count -- the TCP
        // source may report a different number of steps than the USB device table.
        val tunerAgc = v.tunerAgcEnabled.value
        val hwAgc    = v.hardwareAgcEnabled.value
        if (sliderGain != null) {
            val gainCount = svc.source?.getGainCount()
                ?.coerceAtLeast(1)
                ?: RtlSdrDevice.GAIN_TABLE_DB_TENTHS.size
            sliderGain.valueFrom = 0f
            sliderGain.valueTo = (gainCount - 1).toFloat()
            sliderGain.setLabelFormatter { vv ->
                "${"%.1f".format(gainDbAtIndex(vv.toInt()))} dB"
            }
        }
        switchTunerAgc?.setOnCheckedChangeListener(null)
        switchTunerAgc?.isChecked = tunerAgc
        sliderGain?.isEnabled = !tunerAgc
        switchTunerAgc?.setOnCheckedChangeListener { _, checked ->
            viewModel.setTunerAgc(checked)
            sliderGain?.isEnabled = !checked
        }
        switchHardwareAgc?.setOnCheckedChangeListener(null)
        switchHardwareAgc?.isChecked = hwAgc
        switchHardwareAgc?.setOnCheckedChangeListener { _, checked ->
            viewModel.setHardwareAgc(checked)
        }
        val gainIdx = v.gainIndex.value.toFloat()
            .coerceIn(sliderGain?.valueFrom ?: 0f, sliderGain?.valueTo ?: 28f)
        sliderGain?.value = gainIdx
        syncGainLabel(gainIdx.toInt())
        svc.setBiasTee(v.biasTee.value)
        svc.setDirectSampling(v.directSampling.value)
        svc.setPpm(v.ppm.value)
        binding.spectrumView.setPpmCorrection(v.ppm.value)
        binding.waterfallView.setPpmCorrection(v.ppm.value)
        ctrlViews.sliderPpm?.value = v.ppm.value.toFloat().coerceIn(-50f, 50f)
        ctrlViews.tvPpmValue?.text = "${v.ppm.value} ppm"
        svc.setSquelch(v.squelch.value)
        svc.setAudioVolume(v.volume.value)
        svc.setDemodMode(v.demodMode.value)
        // DspEngine.setDemodMode() unconditionally resets its internal
        // ifBandwidthHz to the mode default (or the -1 "auto" sentinel) as
        // part of switching demodulators -- even when the mode hasn't
        // actually changed (e.g. on reconnect). Without re-applying the
        // user's saved Protocol Filter width here, the "Width" slider on the
        // RF tab keeps showing the user's chosen value while the DSP engine
        // silently falls back to the mode default, making the control
        // appear to do nothing after a reconnect.
        if (v.ifBandwidthHz.value > 0) {
            svc.setIfBandwidth(v.ifBandwidthHz.value)
        }
        svc.setFftDecimation(v.decimation.value)
        val hz = v.centerFreqHz.value
        val rate = v.sampleRate.value
        val effectiveRate = rate / v.decimation.value.coerceAtLeast(1)
        binding.frequencyView.setFrequency(hz)
        binding.spectrumView.setCenterFrequency(hardwareCenterFreqHz(hz))
        binding.spectrumView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(hz))
        binding.spectrumView.setDemodDialFrequency(hz)
        binding.waterfallView.setCenterFrequency(hardwareCenterFreqHz(hz))
        binding.waterfallView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(hz))
        binding.spectrumView.setSampleRate(effectiveRate)
        binding.waterfallView.setSampleRate(effectiveRate)
        ctrlViews.tuningDial?.currentFreqHz = hz
    }

    private fun updateConnectionUi(connected: Boolean) {
        binding.progressConnect.isVisible = false
        binding.statusIndicator.setImageResource(
            if (connected) R.drawable.ic_connected else R.drawable.ic_disconnected
        )
        binding.tvDeviceName.text = if (connected) "9GRadio" else "Not Connected"

        // When disconnected, clear the live-data widgets so stale readings
        // (−120.0 dBFS, 0 kS/s) don't persist after the device is unplugged.
        if (!connected) {
            binding.tvSignalDb.text      = "--- dBFS"
            binding.tvSampleRate.text    = "0 kS/s"
            binding.tvSquelchStatus.text = "CLOSED"
            binding.tvSquelchStatus.setTextColor(0xFFFF4444.toInt())
            updateSignalMeter(-120f)
        }
    }

    private fun updateSignalMeter(db: Float) {
        val progress = ((db + 120f) / 120f * 100).toInt().coerceIn(0, 100)
        binding.signalMeter.progress = progress
        binding.signalMeter.progressDrawable?.setLevel(progress * 100)
    }

    /** Public entry point used by Settings drawer preference and overflow menu item. */
    fun showDeviceInfoDialog() = showDeviceInfo()

    /**
     * Device Information / Source-selection dialog.
     *
     * Layout:
     *  ┌─ current source info ──────────────────────────────────┐
     *  │ [divider]                                               │
     *  │ Source:  [ USB ]──────────[ Rtl-sdr Driver ]           │  ← toggle fires immediately
     *  │                                                         │
     *  │ (USB panel -- visible when toggle = USB)                 │
     *  │   Detected USB device / "no device found"              │
     *  │                                                         │
     *  │ (Driver panel -- visible when toggle = Rtl-sdr Driver)  │
     *  │   driver app status / install prompt                   │
     *  │                                                         │
     *  │ [divider]                                               │
     *  │ External rtl_tcp server (optional)                     │
     *  │   [scan results radio group]                           │
     *  │   Host: ____________  Port: ______                     │
     *  │                              [ Connect to external ]   │
     *  └────────────────────────────────────────────────────────┘
     *
     * The toggle switch performs the source transition on its own -- no extra
     * button press needed.  "Connect" is reserved for manual external TCP only.
     */
    private fun showDeviceInfo() {
        val dp  = resources.displayMetrics.density
        val pad = (16 * dp).toInt()

        val svc = sdrService
        val src = svc?.source
        val isDriverMode = src is com.radiosport.ninegradio.source.RtlTcpSource
        val isUsbMode    = src is com.radiosport.ninegradio.source.RtlSdrDeviceSource || src == null

        val driverInstalled = RtlSdrDriverApp.isInstalled(this)
        val driverLabel     = RtlSdrDriverApp.getAppLabel(this)

        // ── Build current-source info string ──────────────────────────────────
        val info = svc?.device?.getDeviceInfo() ?: run {
            if (src != null)
                "Source: ${src.getSourceName()}\n" +
                "Sample rate: ${src.getSampleRate() / 1000} kS/s\n" +
                "Frequency: ${"%.4f".format(src.getCenterFrequency() / 1e6)} MHz"
            else "No device connected"
        }

        // ── Root layout ───────────────────────────────────────────────────────
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        // Current source info
        root.addView(android.widget.TextView(this).apply {
            text = info
            setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Body2)
            val ta = obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
            setTextColor(ta.getColor(0, 0xFF000000.toInt()))
            ta.recycle()
            setPadding(0, 0, 0, (10 * dp).toInt())
        })

        // Divider
        root.addView(android.view.View(this).apply {
            setBackgroundColor(0x22888888)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
            ).also { it.bottomMargin = (10 * dp).toInt() }
        })

        // ── Source toggle row (USB ↔ Rtl-sdr Driver) ─────────────────────────
        val toggleRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (4 * dp).toInt())
        }
        val tvToggleLabel = android.widget.TextView(this).apply {
            text = if (isDriverMode) "Source: Rtl-sdr Driver" else "Source: USB"
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val switchSource = android.widget.Switch(this).apply {
            isChecked = isDriverMode
            textOff = "USB"
            textOn  = "Driver"
        }
        toggleRow.addView(tvToggleLabel)
        toggleRow.addView(switchSource)
        root.addView(toggleRow)

        // Status line below the toggle
        val tvStatus = android.widget.TextView(this).apply {
            text = when {
                isDriverMode -> "📡  $driverLabel  (active)"
                else -> {
                    val devs = viewModel.detectUsbDevices()
                    if (devs.isNotEmpty()) "🔌  ${com.radiosport.ninegradio.usb.RtlSdrDevice.getDeviceName(devs.first())}"
                    else "No USB RTL-SDR device detected"
                }
            }
            setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Body2)
            val ta = obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
            setTextColor(ta.getColor(0, 0xFF000000.toInt()))
            ta.recycle()
            setPadding(0, 0, 0, (6 * dp).toInt())
        }
        root.addView(tvStatus)

        // ── Toggle listener ───────────────────────────────────────────────────
        var ignoreNext = false
        switchSource.setOnCheckedChangeListener { _, useDriver ->
            if (ignoreNext) { ignoreNext = false; return@setOnCheckedChangeListener }
            tvToggleLabel.text = if (useDriver) "Source: Rtl-sdr Driver" else "Source: USB"

            if (useDriver) {
                // USB → Rtl-sdr Driver
                if (!driverInstalled) {
                    showSnackbar("Rtl-sdr Driver not installed")
                    ignoreNext = true
                    switchSource.isChecked = false
                    tvToggleLabel.text = "Source: USB"
                    RtlSdrDriverApp.openPlayStore(this)
                    return@setOnCheckedChangeListener
                }

                val freq    = viewModel.centerFreqHz.value
                val rate    = viewModel.sampleRate.value
                val gainTdB = sdrService?.source?.getGainDb()?.times(10)?.toInt() ?: 240
                val ppm     = viewModel.ppm.value
                val biast   = viewModel.biasTee.value
                val wasUsb  = sdrService?.source is com.radiosport.ninegradio.source.RtlSdrDeviceSource

                // Suppress USB re-attach broadcasts while the dongle is handed to the driver app.
                suppressUsbAttach = true
                sdrService?.disconnectSource()

                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                val usbSettleMs = if (wasUsb) 400L else 0L

                showSnackbar(if (wasUsb) "Releasing USB… launching $driverLabel" else "Launching $driverLabel…")
                tvStatus.text = "📡  $driverLabel  (launching…)"

                // Wait for USB to settle before launching (only if we just released a USB device).
                handler.postDelayed({
                    val launched = RtlSdrDriverApp.launchAsServer(
                        activity     = this,
                        frequencyHz  = freq,
                        sampleRate   = rate.toLong(),
                        gainTenthsDb = gainTdB,
                        ppm          = ppm,
                        biasTee      = biast
                    )
                    if (!launched) {
                        suppressUsbAttach = false
                        showSnackbar("Could not launch $driverLabel")
                        ignoreNext = true
                        switchSource.isChecked = false
                        tvToggleLabel.text = "Source: USB"
                        tvStatus.text = "Could not launch $driverLabel"
                        return@postDelayed
                    }
                    // Driver app is now in foreground -- user grants USB permission there.
                    // onActivityResult(REQUEST_CODE, RESULT_OK) fires when it is ready.
                    showSnackbar("Grant USB access in $driverLabel, then return here")
                }, usbSettleMs)

            } else {
                // Rtl-sdr Driver → USB
                sdrService?.disconnectSource()
                val detected = viewModel.detectUsbDevices()
                if (detected.isEmpty()) {
                    showSnackbar("No USB RTL-SDR device found")
                    tvStatus.text = "No USB RTL-SDR device detected"
                    ignoreNext = true
                    switchSource.isChecked = true
                    tvToggleLabel.text = "Source: Rtl-sdr Driver"
                } else {
                    val dev = detected.first()
                    val name = com.radiosport.ninegradio.usb.RtlSdrDevice.getDeviceName(dev)
                    tvStatus.text = "🔌  $name"
                    showSnackbar("Switching to USB: $name")
                    viewModel.connectDevice(dev)
                }
            }
        }

        // ── Vocoder status + optional download ───────────────────────────────────
        root.addView(android.view.View(this).apply {
            setBackgroundColor(0x22888888)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
            ).also { it.topMargin = (8 * dp).toInt(); it.bottomMargin = (8 * dp).toInt() }
        })

        // ── Vocoder status ─────────────────────────────────────────────────────
        root.addView(android.view.View(this).apply {
            setBackgroundColor(0x22888888)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
            ).also { it.topMargin = (8 * dp).toInt(); it.bottomMargin = (8 * dp).toInt() }
        })

        root.addView(android.widget.TextView(this).apply {
            text = "🎙  $VOCODER_STATUS_TEXT"
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            val ta = obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
            setTextColor(ta.getColor(0, 0xFF888888.toInt()))
            ta.recycle()
            setPadding(0, 0, 0, (6 * dp).toInt())
        })

        AlertDialog.Builder(this)
            .setTitle("Device Information")
            .setView(root)
            .setNeutralButton("🔬 Run Diagnostic") { _, _ -> /* handled below via view click */ }
            .setPositiveButton("Close", null)
            .create()
            .also { dlg ->
                dlg.show()
                // Wire "Run Diagnostic" — we grab the button after show() so we can replace it
                dlg.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { btn ->
                    val btn = btn as android.widget.Button
                    val src = sdrService?.source ?: run {
                        showSnackbar("No active source — connect a device first"); return@setOnClickListener
                    }
                    val dsp = sdrService?.dspEngine ?: run {
                        showSnackbar("DSP engine not running"); return@setOnClickListener
                    }
                    btn.isEnabled = false
                    btn.text = "⏳ Running…"
                    lifecycleScope.launch {
                        val diagnostic = com.radiosport.ninegradio.dsp.SourceDiagnostic(dsp, src)
                        val report = withContext(kotlinx.coroutines.Dispatchers.Default) {
                            diagnostic.run()
                        }
                        btn.isEnabled = true
                        btn.text = "🔬 Run Diagnostic"
                        showDiagnosticReport(report)
                    }
                }
            }
    }


    /**
     * Display a [SourceDiagnostic.Report] in a scrollable, copyable dialog.
     * Called on the main thread after the diagnostic coroutine completes.
     */
    private fun showDiagnosticReport(report: com.radiosport.ninegradio.dsp.SourceDiagnostic.Report) {
        val dp  = resources.displayMetrics.density
        val pad = (12 * dp).toInt()

        // Scrollable monospace text body
        val tv = android.widget.TextView(this).apply {
            text     = report.formattedText
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 10.5f
            setPadding(pad, pad, pad, pad)
            setTextIsSelectable(true)
        }
        val scroll = android.widget.ScrollView(this).apply {
            addView(tv)
            isVerticalScrollBarEnabled = true
        }

        // Verdict colour banner at the top
        val verdictColor = when (report.verdict.level) {
            com.radiosport.ninegradio.dsp.SourceDiagnostic.Level.PASS -> 0xFF1B5E20.toInt()
            com.radiosport.ninegradio.dsp.SourceDiagnostic.Level.WARN -> 0xFFE65100.toInt()
            com.radiosport.ninegradio.dsp.SourceDiagnostic.Level.FAIL -> 0xFFB71C1C.toInt()
            else                                                       -> 0xFF1A237E.toInt()
        }
        val verdictIcon = when (report.verdict.level) {
            com.radiosport.ninegradio.dsp.SourceDiagnostic.Level.PASS -> "✅"
            com.radiosport.ninegradio.dsp.SourceDiagnostic.Level.WARN -> "⚠️"
            com.radiosport.ninegradio.dsp.SourceDiagnostic.Level.FAIL -> "❌"
            else -> "ℹ️"
        }
        val verdictBanner = android.widget.TextView(this).apply {
            text = "$verdictIcon  ${report.verdict.level.name}"
            setBackgroundColor(verdictColor)
            setTextColor(0xFFFFFFFF.toInt())
            textSize   = 13f
            setPadding(pad, (8 * dp).toInt(), pad, (8 * dp).toInt())
        }

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(verdictBanner)
            addView(scroll)
        }

        AlertDialog.Builder(this)
            .setTitle("Source Diagnostic")
            .setView(root)
            .setPositiveButton("Close", null)
            .setNeutralButton("📋 Copy") { _, _ ->
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText(
                    "9GRadio Diagnostic", report.formattedText))
                showSnackbar("Diagnostic report copied to clipboard")
            }
            .show()
            .also { dlg ->
                // Set dialog height to 80% of screen height for comfortable reading
                dlg.window?.setLayout(
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    (resources.displayMetrics.heightPixels * 0.80f).toInt()
                )
            }
    }

    private fun showFrequencyDatabase() {
        val categories = FrequencyDatabase.commonFrequencies.groupBy { it.category }
        val items = categories.flatMap { (cat, entries) ->
            listOf("── $cat ──") + entries.map { "  ${it.name}\n  ${it.frequencyHz / 1e6} MHz  [${it.mode}]" }
        }.toTypedArray()
        val freqMap = categories.values.flatten().associateBy {
            "  ${it.name}\n  ${it.frequencyHz / 1e6} MHz  [${it.mode}]"
        }
        AlertDialog.Builder(this)
            .setTitle("Frequency Database")
            .setItems(items) { _, which ->
                val item = items[which]
                freqMap[item]?.let { entry ->
                    viewModel.setFrequency(entry.frequencyHz)
                    viewModel.setDemodMode(DemodMode.valueOf(entry.mode).let {
                        try { DemodMode.valueOf(entry.mode) } catch (e: Exception) { DemodMode.NFM }
                    })
                    binding.frequencyView.setFrequency(entry.frequencyHz)
                    showSnackbar("Tuned to ${entry.name}")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun bookmarkCurrentFrequency() {
        val hz = viewModel.centerFreqHz.value
        val input = EditText(this).apply {
            hint = "Bookmark label"
            setText("${"%.3f".format(hz / 1e6)} MHz")
        }
        AlertDialog.Builder(this)
            .setTitle("Add Bookmark")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val label = input.text.toString().ifBlank { "${"%.3f".format(hz / 1e6)} MHz" }
                val mode = viewModel.demodMode.value.name
                viewModel.addBookmark(hz, label, demodMode = mode)
                showSnackbar("Bookmark added: $label")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getIqFilePath(): String {
        val dir = File(getExternalFilesDir(null), "IQ")
        dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val mhz = "%.3f".format(viewModel.centerFreqHz.value / 1e6)
        val rate = "${viewModel.sampleRate.value / 1000}kSps"
        return File(dir, "iq_${mhz}MHz_${rate}_$ts.iq").absolutePath
    }

    private fun getAudioFilePath(): String {
        val dir = File(getExternalFilesDir(null), "Audio")
        dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val mhz = "%.3f".format(viewModel.centerFreqHz.value / 1e6)
        return File(dir, "audio_${mhz}MHz_$ts.wav").absolutePath
    }

    private fun showSnackbar(msg: String) {
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
    }

    // ─── Rec tab recordings list helpers ────────────────────────────────────
    // Same behavior as RecordingActivity.playRecording()/shareRecording();
    // duplicated here rather than shared because RecordingActivity is a
    // separate standalone Activity with its own Context.

    private fun playRecordingFromTab(meta: RecordingMeta) {
        if (meta.type == "AUDIO") {
            val uri = FileProvider.getUriForFile(this,
                "$packageName.fileprovider", File(meta.filePath))
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "audio/wav")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } else {
            Toast.makeText(this, "IQ files require an external SDR app to play", Toast.LENGTH_LONG).show()
        }
    }

    private fun shareRecordingFromTab(meta: RecordingMeta) {
        val file = File(meta.filePath)
        if (!file.exists()) { Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show(); return }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = if (meta.type == "AUDIO") "audio/wav" else "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share ${file.name}"
        ))
    }

    // ─── APRS drawer tab ─────────────────────────────────────────────────────

    /**
     * Wires the APRS tab controls.  Called once after the ViewPager has
     * inflated [fragment_tab_aprs.xml].
     *
     * The APRS tab is hidden by default.  It becomes visible (and packet
     * collection starts) when the user selects [DemodMode.APRS] from the
     * Mode chip group; it hides again when any other mode is selected.
     * [observeAprsMode] drives that transition from the demodMode observer.
     *
     * No frequency or demod-mode changes are made here — those are handled
     * entirely by the normal mode-selection path in the Mode tab.
     */
    private fun setupAprsTab() {
        val listView = ctrlViews.listAprsTabPackets ?: return
        aprsListAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, aprsDisplayList)
        listView.adapter = aprsListAdapter

        listView.setOnItemClickListener { _, _, pos, _ ->
            val (pkt, ch) = aprsPackets.getOrNull(pos) ?: return@setOnItemClickListener
            android.app.AlertDialog.Builder(this)
                .setTitle("[$ch] ${pkt.source} → ${pkt.destination}")
                .setMessage(buildString {
                    appendLine("Channel:   $ch")
                    appendLine("Source:    ${pkt.source}")
                    appendLine("Dest:      ${pkt.destination}")
                    appendLine("Path:      ${pkt.path}")
                    if (pkt.latitude  != null) appendLine("Lat:  ${"%.6f".format(pkt.latitude)}°")
                    if (pkt.longitude != null) appendLine("Lon:  ${"%.6f".format(pkt.longitude)}°")
                    appendLine("Payload:   ${pkt.payload.take(120)}")
                    appendLine("Comment:   ${pkt.comment}")
                    appendLine("Raw:       ${pkt.rawFrame.take(60)}…")
                })
                .setPositiveButton("OK", null)
                .show()
        }

        // Reflect the current mode immediately in case APRS is already active.
        updateAprsTabVisibility(viewModel.demodMode.value)
    }

    /**
     * Shows or hides the APRS tab and starts/stops packet collection
     * based on whether [mode] is [DemodMode.APRS].
     *
     * Called from the demodMode observer in [observeViewModel] every time
     * the mode changes, and from [setupAprsTab] once on first inflate.
     */
    fun observeAprsMode(mode: DemodMode) {
        updateAprsTabVisibility(mode)
    }

    private fun updateAprsTabVisibility(mode: DemodMode) {
        val tabLayout = binding.controlTabLayout
        val aprsTabIndex = ControlsPagerAdapter.TAB_APRS

        if (mode == DemodMode.APRS) {
            // Show the APRS tab
            tabLayout.getTabAt(aprsTabIndex)?.view?.visibility = android.view.View.VISIBLE
            // Navigate to it
            binding.controlViewPager.setCurrentItem(aprsTabIndex, true)
            ctrlViews.tvAprsTabStatus?.text = "Listening…"
            ctrlViews.tvAprsTabMode?.text   = "${"%.3f".format(viewModel.centerFreqHz.value / 1e6)} MHz  |  ${viewModel.sampleRate.value / 1000} kS/s"
            startAprsCollect()
        } else {
            // Stop collection and hide the tab
            stopAprsCollect()
            tabLayout.getTabAt(aprsTabIndex)?.view?.visibility = android.view.View.GONE
        }
    }

    private fun startAprsCollect() {
        aprsCollectJob?.cancel()
        val decoder = sdrService?.dspEngine?.aprsDecoder ?: return
        aprsCollectJob = lifecycleScope.launch {
            decoder.packets.collectLatest { pkt ->
                aprsPackets.add(0, Pair(pkt, "APRS"))
                if (aprsPackets.size > 200) aprsPackets.removeLast()

                val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date(pkt.timestamp))
                val entry = buildString {
                    append("[$ts] ${pkt.source}>${pkt.destination}")
                    if (pkt.latitude != null && pkt.longitude != null)
                        append("  ${"%.4f".format(pkt.latitude)},${"%.4f".format(pkt.longitude)}")
                    append("\n${pkt.comment.take(60)}")
                }
                aprsDisplayList.add(0, entry)
                if (aprsDisplayList.size > 200) aprsDisplayList.removeLast()
                aprsListAdapter?.notifyDataSetChanged()
                ctrlViews.tvAprsTabCount?.text  = "Packets: ${aprsPackets.size}"
                ctrlViews.tvAprsTabStatus?.text = "Last: ${pkt.source}"

                // Auto-display the packet detail dialog on reception, unless
                // the user has disabled this in Settings (pref_aprs_auto_display).
                val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
                val autoDisplay = prefs.getBoolean("pref_aprs_auto_display", true)
                if (autoDisplay) {
                    showAprsPacketAutoDialog(pkt, "APRS")
                }
            }
        }
    }

    /** Auto-dismissing APRS packet detail dialog shown for 5 seconds on reception. */
    private var aprsAutoDialog: android.app.AlertDialog? = null
    private var aprsAutoDialogJob: Job? = null

    private fun showAprsPacketAutoDialog(pkt: com.radiosport.ninegradio.dsp.AprsDecoder.AprsPacket, channelTag: String) {
        // Dismiss any existing auto-dialog so only the latest packet is shown.
        aprsAutoDialog?.dismiss()
        aprsAutoDialogJob?.cancel()

        val message = buildString {
            appendLine("Source:    ${pkt.source}")
            appendLine("Dest:      ${pkt.destination}")
            appendLine("Path:      ${pkt.path}")
            if (pkt.latitude  != null) appendLine("Lat:  ${"%.6f".format(pkt.latitude)}\u00b0")
            if (pkt.longitude != null) appendLine("Lon:  ${"%.6f".format(pkt.longitude)}\u00b0")
            appendLine("Payload:   ${pkt.payload.take(120)}")
            appendLine("Comment:   ${pkt.comment}")
            appendLine("Raw:       ${pkt.rawFrame.take(60)}\u2026")
            appendLine()
            append("(auto-closes in 5 s)")
        }

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("[$channelTag] ${pkt.source} \u2192 ${pkt.destination}")
            .setMessage(message)
            .setPositiveButton("OK") { d, _ -> d.dismiss() }
            .create()
        dialog.show()
        aprsAutoDialog = dialog

        // Auto-dismiss after 5 seconds.
        aprsAutoDialogJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(5_000)
            dialog.dismiss()
        }
    }

    private fun stopAprsCollect() {
        aprsCollectJob?.cancel()
        aprsCollectJob = null
        aprsAutoDialogJob?.cancel()
        aprsAutoDialogJob = null
        aprsAutoDialog?.dismiss()
        aprsAutoDialog = null
        ctrlViews.tvAprsTabStatus?.text = "Idle"
        ctrlViews.tvAprsTabMode?.text   = "Not started"
    }

    // ─── ACARS drawer tab ──────────────────────────────────────────────────────

    /**
     * Wires the ACARS tab controls. Called once after the ViewPager has
     * inflated [fragment_tab_acars.xml].
     *
     * Hidden by default; becomes visible when [DemodMode.ACARS] is selected,
     * exactly like the APRS tab above. Selecting ACARS from the Mode tab's
     * chip group used to launch the separate, full-screen AcarsActivity; it
     * now opens this embedded drawer tab instead, matching APRS's behavior.
     */
    private fun setupAcarsTab() {
        val listView = ctrlViews.listAcarsTabMessages ?: return
        acarsListAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, acarsDisplayList)
        listView.adapter = acarsListAdapter

        listView.setOnItemClickListener { _, _, pos, _ ->
            val msg = acarsMessages.getOrNull(pos) ?: return@setOnItemClickListener
            android.app.AlertDialog.Builder(this)
                .setTitle("${msg.registration}  ·  ${msg.flightId}")
                .setMessage(buildString {
                    appendLine("Registration: ${msg.registration}")
                    appendLine("Flight ID:    ${msg.flightId}")
                    appendLine("Label:        ${msg.label}")
                    appendLine("Block ID:     ${msg.blockId}")
                    appendLine("Type:         ${if (msg.ack) "ACK" else "Message"}${if (msg.more) " (more follows)" else ""}")
                    appendLine()
                    appendLine(msg.text)
                })
                .setPositiveButton("OK", null)
                .show()
        }

        // Reflect the current mode immediately in case ACARS is already active.
        updateAcarsTabVisibility(viewModel.demodMode.value)
    }

    /**
     * Shows or hides the ACARS tab and starts/stops message collection
     * based on whether [mode] is [DemodMode.ACARS].
     *
     * Called from the demodMode observer in [observeViewModel] every time
     * the mode changes, and from [setupAcarsTab] once on first inflate.
     */
    fun observeAcarsMode(mode: DemodMode) {
        updateAcarsTabVisibility(mode)
    }

    private fun updateAcarsTabVisibility(mode: DemodMode) {
        val tabLayout = binding.controlTabLayout
        val acarsTabIndex = ControlsPagerAdapter.TAB_ACARS

        if (mode == DemodMode.ACARS) {
            // Show the ACARS tab
            tabLayout.getTabAt(acarsTabIndex)?.view?.visibility = android.view.View.VISIBLE
            // Navigate to it
            binding.controlViewPager.setCurrentItem(acarsTabIndex, true)
            ctrlViews.tvAcarsTabStatus?.text = "Listening…"
            ctrlViews.tvAcarsTabMode?.text   = "${"%.3f".format(viewModel.centerFreqHz.value / 1e6)} MHz  |  ${viewModel.sampleRate.value / 1000} kS/s"
            startAcarsCollect()
        } else {
            // Stop collection and hide the tab
            stopAcarsCollect()
            tabLayout.getTabAt(acarsTabIndex)?.view?.visibility = android.view.View.GONE
        }
    }

    private fun startAcarsCollect() {
        acarsCollectJob?.cancel()
        val decoder = sdrService?.dspEngine?.acarsDecoder ?: return
        acarsCollectJob = lifecycleScope.launch {
            decoder.messages.collectLatest { msg ->
                acarsMessages.add(0, msg)
                if (acarsMessages.size > 200) acarsMessages.removeLast()

                val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date(msg.timestamp))
                val entry = buildString {
                    append("[$ts] ${msg.registration} ${msg.flightId} (${msg.label})")
                    if (msg.text.isNotBlank()) append("\n${msg.text.take(60)}")
                }
                acarsDisplayList.add(0, entry)
                if (acarsDisplayList.size > 200) acarsDisplayList.removeLast()
                acarsListAdapter?.notifyDataSetChanged()
                ctrlViews.tvAcarsTabCount?.text  = "Messages: ${acarsMessages.size}"
                ctrlViews.tvAcarsTabStatus?.text = "Last: ${msg.registration}"
            }
        }
    }

    private fun stopAcarsCollect() {
        acarsCollectJob?.cancel()
        acarsCollectJob = null
        ctrlViews.tvAcarsTabStatus?.text = "Idle"
        ctrlViews.tvAcarsTabMode?.text   = "Not started"
    }


    // ═════════════════════════════════════════════════════════════════════════
    //  DMR DRAWER TAB
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Formats the elapsed time of a call (lastTimestamp - firstTimestamp) as
     * a compact duration string, e.g. "0s", "45s", "2m 1s", "1h 3m" -- used
     * by every digital-voice "Recent Calls" list (DMR/Dig/YSF/D-STAR) in
     * place of a start–end time range, which said the same thing far less
     * compactly and didn't read at a glance as "how long was this call".
     */
    private fun formatCallDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else  -> "${s}s"
        }
    }

    /**
     * Wires the DMR tab ListView. Called once after the ViewPager has
     * inflated [fragment_tab_dmr.xml]. Hidden by default; becomes visible
     * when [DemodMode.DMR] is selected, mirroring the APRS/ACARS pattern.
     */
    private fun setupDmrTab() {
        val listView = ctrlViews.listDmrTabFrames ?: return
        dmrListAdapter = ArrayAdapter(this, R.layout.item_recent_call, android.R.id.text1, dmrDisplayList)
        listView.adapter = dmrListAdapter

        listView.setOnItemClickListener { _, _, pos, _ ->
            val call = dmrCalls.calls.getOrNull(pos) ?: return@setOnItemClickListener
            val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            val start = fmt.format(java.util.Date(call.firstTimestamp))
            val duration = formatCallDuration(call.lastTimestamp - call.firstTimestamp)
            android.app.AlertDialog.Builder(this)
                .setTitle("DMR Call  [$start - $duration]")
                .setMessage(buildString {
                    appendLine("Protocol:  ${call.protocol}")
                    appendLine("Type:      ${call.frameType}")
                    appendLine("Src ID:    ${call.srcId}")
                    appendLine("Dst ID:    ${call.dstId}")
                    appendLine("Group:     ${call.isGroup}")
                    appendLine("Encrypted: ${call.encrypted}")
                    appendLine("Emergency: ${call.emergency}")
                    if (call.talkerAlias.isNotBlank()) appendLine("Alias:     ${call.talkerAlias}")
                    appendLine("RSSI:      ${"%.1f".format(call.rssi)} dBm")
                    appendLine("SNR:       ${"%.1f".format(call.snr)} dB")
                    appendLine("Frames:    ${call.frameCount}")
                    appendLine("PCM:       ${call.pcmSamples} samples")
                })
                .setPositiveButton("OK", null)
                .show()
        }

        updateDmrTabVisibility(viewModel.demodMode.value)
    }

    fun observeDmrMode(mode: com.radiosport.ninegradio.dsp.DemodMode) {
        updateDmrTabVisibility(mode)
    }

    private fun updateDmrTabVisibility(mode: com.radiosport.ninegradio.dsp.DemodMode) {
        val tabLayout = binding.controlTabLayout

        // DMR tab: visible only for DemodMode.DMR
        val dmrTabIndex = ControlsPagerAdapter.TAB_DMR
        if (mode == com.radiosport.ninegradio.dsp.DemodMode.DMR) {
            tabLayout.getTabAt(dmrTabIndex)?.view?.visibility = android.view.View.VISIBLE
            binding.controlViewPager.setCurrentItem(dmrTabIndex, true)
            ctrlViews.tvDmrTabStatus?.text  = "Listening…"
            ctrlViews.tvDmrTabMode?.text    = "${"%.3f".format(viewModel.centerFreqHz.value / 1e6)} MHz  |  ${viewModel.sampleRate.value / 1000} kS/s"
            ctrlViews.tvDmrTabVocoder?.text = VOCODER_STATUS_TEXT
            startDmrCollect()
        } else {
            stopDmrCollect()
            tabLayout.getTabAt(dmrTabIndex)?.view?.visibility = android.view.View.GONE
        }

        // Dig tab: visible only for DemodMode.DIG
        val digTabIndex = ControlsPagerAdapter.TAB_DIG
        if (mode == com.radiosport.ninegradio.dsp.DemodMode.DIG) {
            tabLayout.getTabAt(digTabIndex)?.view?.visibility = android.view.View.VISIBLE
            binding.controlViewPager.setCurrentItem(digTabIndex, true)
            ctrlViews.tvDigTabStatus?.text  = "Auto-detect…"
            ctrlViews.tvDigTabMode?.text    = "${"%.3f".format(viewModel.centerFreqHz.value / 1e6)} MHz  |  ${viewModel.sampleRate.value / 1000} kS/s"
            ctrlViews.tvDigTabVocoder?.text = VOCODER_STATUS_TEXT
            startDigCollect()
        } else {
            stopDigCollect()
            tabLayout.getTabAt(digTabIndex)?.view?.visibility = android.view.View.GONE
        }
    }

    private fun startDmrCollect() {
        dmrCollectJob?.cancel()
        val decoder = sdrService?.dspEngine?.digitalVoiceDecoder ?: return
        dmrCollectJob = lifecycleScope.launch {
            decoder.frames.collect { frame ->
                if (frame.protocol != com.radiosport.ninegradio.dsp.DigitalFrame.Protocol.DMR) return@collect
                val isNewCall = dmrCalls.addFrame(frame)
                val call = dmrCalls.calls.first()
                dmrFrameCount++

                val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                val timeLabel = "${fmt.format(java.util.Date(call.firstTimestamp))} - " +
                    formatCallDuration(call.lastTimestamp - call.firstTimestamp)
                val entry = buildString {
                    append("[$timeLabel] ${call.frameType}")
                    if (call.srcId != 0) append("  ${call.srcId}→${call.dstId}")
                    if (call.talkerAlias.isNotBlank()) append("  \"${call.talkerAlias.take(12)}\"")
                    if (call.encrypted) append("  🔒")
                    if (call.emergency) append("  🆘")
                    append("  ×${call.frameCount}")
                }
                // A continuing call replaces its existing row in place; a new
                // call inserts a new row at the top -- this is what turns the
                // list from one-row-per-frame into one-row-per-call.
                if (isNewCall) {
                    dmrDisplayList.add(0, entry)
                    if (dmrDisplayList.size > 200) dmrDisplayList.removeLast()
                } else {
                    dmrDisplayList[0] = entry
                }
                dmrListAdapter?.notifyDataSetChanged()

                ctrlViews.tvDmrTabCount?.text  = "Frames: $dmrFrameCount"
                ctrlViews.tvDmrTabStatus?.text = "Active"
                if (frame.srcId != 0) {
                    ctrlViews.tvDmrTabCallInfo?.text =
                        "Last: ${frame.srcId} → ${frame.dstId}  (${if (frame.isGroup) "Group" else "Private"})"
                }
            }
        }
    }
    private fun stopDmrCollect() {
        dmrCollectJob?.cancel()
        dmrCollectJob = null
        ctrlViews.tvDmrTabStatus?.text = "Idle"
        ctrlViews.tvDmrTabMode?.text   = "Not started"
    }

    private fun setupDigTab() {
        val listView = ctrlViews.listDigTabFrames ?: return
        digListAdapter = ArrayAdapter(this, R.layout.item_recent_call, android.R.id.text1, digDisplayList)
        listView.adapter = digListAdapter

        listView.setOnItemClickListener { _, _, pos, _ ->
            val call = digCalls.calls.getOrNull(pos) ?: return@setOnItemClickListener
            val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            val start = fmt.format(java.util.Date(call.firstTimestamp))
            val duration = formatCallDuration(call.lastTimestamp - call.firstTimestamp)
            android.app.AlertDialog.Builder(this)
                .setTitle("Dig Call  [$start - $duration]")
                .setMessage(buildString {
                    appendLine("Protocol:  ${call.protocol}")
                    appendLine("Type:      ${call.frameType}")
                    appendLine("Src ID:    ${call.srcId}")
                    appendLine("Dst ID:    ${call.dstId}")
                    appendLine("Group:     ${call.isGroup}")
                    appendLine("Encrypted: ${call.encrypted}")
                    appendLine("Emergency: ${call.emergency}")
                    if (call.talkerAlias.isNotBlank()) appendLine("Alias:     ${call.talkerAlias}")
                    appendLine("RSSI:      ${"%.1f".format(call.rssi)} dBm")
                    appendLine("SNR:       ${"%.1f".format(call.snr)} dB")
                    appendLine("Frames:    ${call.frameCount}")
                    appendLine("PCM:       ${call.pcmSamples} samples")
                })
                .setPositiveButton("OK", null)
                .show()
        }

        updateDmrTabVisibility(viewModel.demodMode.value)
    }

    private fun startDigCollect() {
        digCollectJob?.cancel()
        val decoder = sdrService?.dspEngine?.digitalVoiceDecoder ?: return
        digCollectJob = lifecycleScope.launch {
            decoder.frames.collect { frame ->
                // DIG (auto-detect) mode: accept frames from any known protocol
                if (frame.protocol == com.radiosport.ninegradio.dsp.DigitalFrame.Protocol.UNKNOWN) return@collect
                val isNewCall = digCalls.addFrame(frame)
                val call = digCalls.calls.first()
                digFrameCount++

                val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                val timeLabel = "${fmt.format(java.util.Date(call.firstTimestamp))} - " +
                    formatCallDuration(call.lastTimestamp - call.firstTimestamp)
                val entry = buildString {
                    append("[${call.protocol.name}] ")
                    append("[$timeLabel] ${call.frameType}")
                    if (call.srcId != 0) append("  ${call.srcId}→${call.dstId}")
                    if (call.talkerAlias.isNotBlank()) append("  \"${call.talkerAlias.take(12)}\"")
                    if (call.encrypted) append("  🔒")
                    if (call.emergency) append("  🆘")
                    append("  ×${call.frameCount}")
                }
                if (isNewCall) {
                    digDisplayList.add(0, entry)
                    if (digDisplayList.size > 200) digDisplayList.removeLast()
                } else {
                    digDisplayList[0] = entry
                }
                digListAdapter?.notifyDataSetChanged()

                ctrlViews.tvDigTabCount?.text  = "Frames: $digFrameCount"
                ctrlViews.tvDigTabStatus?.text = "Detected: ${frame.protocol.name}"
                if (frame.srcId != 0) {
                    ctrlViews.tvDigTabCallInfo?.text =
                        "Last: ${frame.srcId} → ${frame.dstId}  (${if (frame.isGroup) "Group" else "Private"})"
                }
            }
        }
    }
    private fun stopDigCollect() {
        digCollectJob?.cancel()
        digCollectJob = null
        ctrlViews.tvDigTabStatus?.text = "Idle"
        ctrlViews.tvDigTabMode?.text   = "Not started"
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  YSF DRAWER TAB
    // ═════════════════════════════════════════════════════════════════════════

    private fun setupYsfTab() {
        val listView = ctrlViews.listYsfTabFrames ?: return
        ysfListAdapter = ArrayAdapter(this, R.layout.item_recent_call, android.R.id.text1, ysfDisplayList)
        listView.adapter = ysfListAdapter

        listView.setOnItemClickListener { _, _, pos, _ ->
            val call = ysfCalls.calls.getOrNull(pos) ?: return@setOnItemClickListener
            val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            val start = fmt.format(java.util.Date(call.firstTimestamp))
            val duration = formatCallDuration(call.lastTimestamp - call.firstTimestamp)
            android.app.AlertDialog.Builder(this)
                .setTitle("YSF Call  [$start - $duration]")
                .setMessage(buildString {
                    appendLine("Protocol:  ${call.protocol}")
                    appendLine("Type:      ${call.frameType}")
                    appendLine("Src ID:    ${call.srcId}")
                    appendLine("Dst ID:    ${call.dstId}")
                    appendLine("Encrypted: ${call.encrypted}")
                    if (call.talkerAlias.isNotBlank()) appendLine("Alias:     ${call.talkerAlias}")
                    appendLine("RSSI:      ${"%.1f".format(call.rssi)} dBm")
                    appendLine("SNR:       ${"%.1f".format(call.snr)} dB")
                    appendLine("Frames:    ${call.frameCount}")
                    appendLine("PCM:       ${call.pcmSamples} samples")
                })
                .setPositiveButton("OK", null)
                .show()
        }

        updateYsfTabVisibility(viewModel.demodMode.value)
    }

    fun observeYsfMode(mode: com.radiosport.ninegradio.dsp.DemodMode) {
        updateYsfTabVisibility(mode)
    }

    private fun updateYsfTabVisibility(mode: com.radiosport.ninegradio.dsp.DemodMode) {
        val tabLayout = binding.controlTabLayout
        val tabIndex  = ControlsPagerAdapter.TAB_YSF
        if (mode == com.radiosport.ninegradio.dsp.DemodMode.YSF) {
            tabLayout.getTabAt(tabIndex)?.view?.visibility = android.view.View.VISIBLE
            binding.controlViewPager.setCurrentItem(tabIndex, true)
            ctrlViews.tvYsfTabStatus?.text  = "Listening…"
            ctrlViews.tvYsfTabMode?.text    = "${"%.3f".format(viewModel.centerFreqHz.value / 1e6)} MHz  |  ${viewModel.sampleRate.value / 1000} kS/s"
            ctrlViews.tvYsfTabVocoder?.text = VOCODER_STATUS_TEXT
            startYsfCollect()
        } else {
            stopYsfCollect()
            tabLayout.getTabAt(tabIndex)?.view?.visibility = android.view.View.GONE
        }
    }

    private fun startYsfCollect() {
        ysfCollectJob?.cancel()
        val decoder = sdrService?.dspEngine?.digitalVoiceDecoder ?: return
        ysfCollectJob = lifecycleScope.launch {
            decoder.frames.collect { frame ->
                if (frame.protocol != com.radiosport.ninegradio.dsp.DigitalFrame.Protocol.YSF) return@collect
                val isNewCall = ysfCalls.addFrame(frame)
                val call = ysfCalls.calls.first()
                ysfFrameCount++

                val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                val timeLabel = "${fmt.format(java.util.Date(call.firstTimestamp))} - " +
                    formatCallDuration(call.lastTimestamp - call.firstTimestamp)
                val entry = buildString {
                    append("[$timeLabel] ${call.frameType}")
                    if (call.srcId != 0) append("  ${call.srcId}→${call.dstId}")
                    if (call.talkerAlias.isNotBlank()) append("  \"${call.talkerAlias.take(12)}\"")
                    if (call.encrypted) append("  🔒")
                    append("  ×${call.frameCount}")
                }
                if (isNewCall) {
                    ysfDisplayList.add(0, entry)
                    if (ysfDisplayList.size > 200) ysfDisplayList.removeLast()
                } else {
                    ysfDisplayList[0] = entry
                }
                ysfListAdapter?.notifyDataSetChanged()

                ctrlViews.tvYsfTabCount?.text  = "Frames: $ysfFrameCount"
                ctrlViews.tvYsfTabStatus?.text = "Active"
                if (frame.srcId != 0) {
                    ctrlViews.tvYsfTabCallInfo?.text =
                        "Last: ${frame.srcId} → ${frame.dstId}"
                }
            }
        }
    }

    private fun stopYsfCollect() {
        ysfCollectJob?.cancel()
        ysfCollectJob = null
        ctrlViews.tvYsfTabStatus?.text = "Idle"
        ctrlViews.tvYsfTabMode?.text   = "Not started"
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  D-STAR DRAWER TAB
    // ═════════════════════════════════════════════════════════════════════════

    private fun setupDstarTab() {
        val listView = ctrlViews.listDstarTabFrames ?: return
        dstarListAdapter = ArrayAdapter(this, R.layout.item_recent_call, android.R.id.text1, dstarDisplayList)
        listView.adapter = dstarListAdapter

        listView.setOnItemClickListener { _, _, pos, _ ->
            val call = dstarCalls.calls.getOrNull(pos) ?: return@setOnItemClickListener
            val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            val start = fmt.format(java.util.Date(call.firstTimestamp))
            val duration = formatCallDuration(call.lastTimestamp - call.firstTimestamp)
            android.app.AlertDialog.Builder(this)
                .setTitle("D-STAR Call  [$start - $duration]")
                .setMessage(buildString {
                    appendLine("Protocol:  ${call.protocol}")
                    appendLine("Type:      ${call.frameType}")
                    appendLine("Src ID:    ${call.srcId}")
                    appendLine("Dst ID:    ${call.dstId}")
                    appendLine("Encrypted: ${call.encrypted}")
                    if (call.talkerAlias.isNotBlank()) appendLine("Callsign:  ${call.talkerAlias}")
                    appendLine("RSSI:      ${"%.1f".format(call.rssi)} dBm")
                    appendLine("SNR:       ${"%.1f".format(call.snr)} dB")
                    appendLine("Frames:    ${call.frameCount}")
                    appendLine("PCM:       ${call.pcmSamples} samples")
                })
                .setPositiveButton("OK", null)
                .show()
        }

        updateDstarTabVisibility(viewModel.demodMode.value)
    }

    fun observeDstarMode(mode: com.radiosport.ninegradio.dsp.DemodMode) {
        updateDstarTabVisibility(mode)
    }

    private fun updateDstarTabVisibility(mode: com.radiosport.ninegradio.dsp.DemodMode) {
        val tabLayout = binding.controlTabLayout
        val tabIndex  = ControlsPagerAdapter.TAB_DSTAR
        if (mode == com.radiosport.ninegradio.dsp.DemodMode.DSTAR) {
            tabLayout.getTabAt(tabIndex)?.view?.visibility = android.view.View.VISIBLE
            binding.controlViewPager.setCurrentItem(tabIndex, true)
            ctrlViews.tvDstarTabStatus?.text  = "Listening…"
            ctrlViews.tvDstarTabMode?.text    = "${"%.3f".format(viewModel.centerFreqHz.value / 1e6)} MHz  |  ${viewModel.sampleRate.value / 1000} kS/s"
            ctrlViews.tvDstarTabVocoder?.text = VOCODER_STATUS_TEXT
            startDstarCollect()
        } else {
            stopDstarCollect()
            tabLayout.getTabAt(tabIndex)?.view?.visibility = android.view.View.GONE
        }
    }

    private fun startDstarCollect() {
        dstarCollectJob?.cancel()
        val decoder = sdrService?.dspEngine?.digitalVoiceDecoder ?: return
        dstarCollectJob = lifecycleScope.launch {
            decoder.frames.collect { frame ->
                if (frame.protocol != com.radiosport.ninegradio.dsp.DigitalFrame.Protocol.DSTAR) return@collect
                val isNewCall = dstarCalls.addFrame(frame)
                val call = dstarCalls.calls.first()
                dstarFrameCount++

                val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                val timeLabel = "${fmt.format(java.util.Date(call.firstTimestamp))} - " +
                    formatCallDuration(call.lastTimestamp - call.firstTimestamp)
                val entry = buildString {
                    append("[$timeLabel] ${call.frameType}")
                    if (call.talkerAlias.isNotBlank()) append("  ${call.talkerAlias.take(8)}")
                    if (call.srcId != 0) append("  ${call.srcId}→${call.dstId}")
                    if (call.encrypted) append("  🔒")
                    append("  ×${call.frameCount}")
                }
                if (isNewCall) {
                    dstarDisplayList.add(0, entry)
                    if (dstarDisplayList.size > 200) dstarDisplayList.removeLast()
                } else {
                    dstarDisplayList[0] = entry
                }
                dstarListAdapter?.notifyDataSetChanged()

                ctrlViews.tvDstarTabCount?.text  = "Frames: $dstarFrameCount"
                ctrlViews.tvDstarTabStatus?.text = "Active"
                if (frame.talkerAlias.isNotBlank()) {
                    ctrlViews.tvDstarTabCallInfo?.text = "Last callsign: ${frame.talkerAlias}"
                } else if (frame.srcId != 0) {
                    ctrlViews.tvDstarTabCallInfo?.text = "Last: ${frame.srcId} → ${frame.dstId}"
                }
            }
        }
    }

    private fun stopDstarCollect() {
        dstarCollectJob?.cancel()
        dstarCollectJob = null
        ctrlViews.tvDstarTabStatus?.text = "Idle"
        ctrlViews.tvDstarTabMode?.text   = "Not started"
    }

    // ─── Scan drawer tab ──────────────────────────────────────────────────────
    //
    // The Scan tab is a hidden tab exactly like APRS/ACARS/DMR/YSF/D-STAR/Dig
    // above, with one difference: those tabs reveal themselves automatically
    // when their DemodMode is selected, whereas this one has no associated
    // DemodMode -- it only becomes visible when explicitly invoked via
    // [showScanTab] (wired to the Settings tab's Scanner button and the main
    // FAB in [setupSettingsTab]/[setupFabMenu]), and hides again via
    // [hideScanTab] (its own Close button, or when the drawer needs it gone).
    //
    // This is the app's only frequency-scanning UI -- a separate standalone
    // ScannerActivity previously duplicated this same functionality and has
    // been removed to avoid the two implementations drifting out of sync.

    /**
     * Pick a device sample rate for an upcoming scan.
     *
     * [FrequencyScanner] captures one wideband IQ block per hop and detects
     * every channel that falls inside it (see [FrequencyScanner.captureBlock]),
     * exactly like SDRangel's own Frequency Scanner plugin, whose docs note
     * it "will typically try to set the device centre frequency in order to
     * scan as many frequencies simultaneously as possible" --
     * https://github.com/f4exb/sdrangel/blob/master/plugins/channelrx/freqscanner/readme.md.
     * The wider the capture, the fewer retunes are needed to cover
     * [startHz]..[stopHz], so the sweep both completes faster and spends more
     * time per hop dwelling on each block (better sensitivity per retune).
     *
     * Pick the smallest available rate that both (a) covers the whole
     * requested range in one block if that range is modest, and (b) still
     * spans a healthy number of channel steps (at least 40) so multiple
     * candidate channels are actually being scanned per hop rather than one at
     * a time. Capped at the device's fastest supported rate.
     */
    private fun chooseScanSampleRate(startHz: Long, stopHz: Long, stepHz: Long): Int {
        val range = (stopHz - startHz).coerceAtLeast(1L)
        val target = minOf(range, maxOf(stepHz * 40L, 1_000_000L))
        return RtlSdrDevice.SAMPLE_RATES.firstOrNull { it >= target }
            ?: RtlSdrDevice.SAMPLE_RATES.last()
    }

    /**
     * Pick the smallest FFT size that still resolves individual scan
     * channels cleanly. A bin wider than the channel step would blur two
     * adjacent channels together (a hit on one frequency reading as if it
     * were on its neighbour too); a much narrower bin than necessary only
     * burns CPU/time on every capture without adding any real accuracy for
     * this purpose. Target a bin width of at most a quarter of [stepHz], the
     * smallest FFT size in the app's supported set that achieves it.
     */
    private fun chooseScanFftSize(sampleRateHz: Int, stepHz: Long): Int {
        val fftSizes = intArrayOf(256, 512, 1024, 2048, 4096, 8192)
        val targetBinHz = maxOf(stepHz / 4.0, 1.0)
        return fftSizes.firstOrNull { sampleRateHz.toDouble() / it <= targetBinHz } ?: fftSizes.last()
    }

    /**
     * Pick a frame-averaging count that smooths the noise floor without
     * blurring together samples from different frequencies. The spectrum
     * only stays on one retuned frequency for [dwellMs] before the scanner
     * hops on, so averaging must fit comfortably inside that window (at
     * ~15 fps / ~66 ms per frame) or it will still be blending in frames
     * from the *previous* hop's frequency when the next hit is evaluated.
     * Short dwells (fast search sweeps) get no averaging at all; longer
     * dwells get progressively more, capped well under what the dwell time
     * can actually contain.
     */
    /**
     * Pick a frame-averaging count that actually gets used for the configured
     * dwell time, instead of silently defaulting to "off" (1) for anything
     * short of ~8 live-spectrum frames per dwell.  At the ~66 ms/frame the
     * live spectrum runs at, a typical 200 ms scan dwell only fits ~3 frames
     * -- the previous >=8/>=4 thresholds meant averaging stayed OFF for that
     * (and shorter) dwell times, so Auto dB Range's noise-floor read was
     * never actually sanitized by Frame Avg. the way the scan tab intended.
     * Lowered so even a short dwell still gets at least 2x averaging.
     *
     * This is now safe to apply aggressively because [FftEngine.resetSpectrumAveraging]
     * is called on every scanner retune (see FrequencyScanner's onRetune hook,
     * wired below), which discards any partial accumulation from the
     * *previous* frequency -- frames from different hops can no longer be
     * incoherently blended together, so a higher averaging count only ever
     * cleans up noise within a single, stable-frequency dwell.
     */
    private fun chooseScanFrameAveraging(dwellMs: Long): Int {
        val framesPerDwell = dwellMs / 66L
        return when {
            framesPerDwell >= 6 -> 4
            framesPerDwell >= 2 -> 2
            else -> 1
        }
    }

    /**
     * Auto-configure the live spectrum/waterfall so they accurately reflect
     * what's about to be scanned: a sample rate wide enough to show several
     * channel steps at once (see [chooseScanSampleRate]), an initial center
     * frequency matching the very first capture block
     * [FrequencyScanner] will actually tune to (half a bandwidth into the
     * range, mirroring its own startup math), and the configured squelch
     * threshold drawn as the spectrum's squelch line. Called once, right
     * before a scan/search starts -- the sweep's own retunes are then
     * mirrored to the display live via [attachScanTabCollectors].
     *
     * Also tunes the rest of the display pipeline for scanning specifically,
     * rather than leaving whatever the user last picked for manual listening:
     *   - FFT size:      big enough to resolve individual channels ([chooseScanFftSize]).
     *   - Decimation:    forced off, so the displayed bandwidth always matches
     *                    the scanner's actual capture width -- a decimated
     *                    view would silently hide part of every block.
     *   - Frame average: light, dwell-proportional smoothing to cut noise
     *                    without blurring frequencies together ([chooseScanFrameAveraging]).
     *   - Auto dB range: turned on, since a sweep crosses bands with very
     *                    different noise floors/activity levels that no
     *                    single fixed dB range would suit for all of them.
     */
    private fun configureSpectrumForScan(startHz: Long, stopHz: Long, stepHz: Long, sqlDb: Float, dwellMs: Long) {
        val rate = chooseScanSampleRate(startHz, stopHz, stepHz)
        if (rate != viewModel.sampleRate.value) {
            viewModel.setSampleRate(rate)
            val idx = RtlSdrDevice.SAMPLE_RATES.indexOfFirst { it == viewModel.sampleRate.value }
            if (idx >= 0) {
                sampleRateLabelsCache.getOrNull(idx)?.let { ctrlViews.tvSampleRateLabel?.text = it }
                updateSampleRateChips(idx)
            }
        }

        // Decimation off: the displayed bandwidth must match the scanner's
        // actual capture width so nothing it's inspecting is hidden from view.
        if (viewModel.decimation.value != 1) {
            viewModel.setDecimation(1)
            val decimationFactors = listOf(1, 2, 4, 8, 16, 32, 64)
            ctrlViews.spinnerDecimation?.setSelection(decimationFactors.indexOf(1), false)
        }
        val effectiveRate = viewModel.sampleRate.value / viewModel.decimation.value.coerceAtLeast(1)
        binding.spectrumView.setSampleRate(effectiveRate)
        binding.waterfallView.setSampleRate(effectiveRate)

        val fftSize = chooseScanFftSize(effectiveRate, stepHz)
        if (fftSize != viewModel.fftSize.value) {
            viewModel.setFftSize(fftSize)
            val fftSizes = listOf(256, 512, 1024, 2048, 4096, 8192)
            fftSizes.indexOf(fftSize).takeIf { it >= 0 }
                ?.let { ctrlViews.spinnerFftSize?.setSelection(it, false) }
        }

        val frameAvg = chooseScanFrameAveraging(dwellMs)
        sdrService?.dspEngine?.fftEngine?.frameAveragingCount = frameAvg
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            .edit().putString("pref_frame_averaging", frameAvg.toString()).apply()
        val frameAvgValues = listOf(1, 2, 4, 8, 16, 32)
        frameAvgValues.indexOf(frameAvg).takeIf { it >= 0 }
            ?.let { ctrlViews.spinnerFrameAvg?.setSelection(it, false) }

        binding.spectrumView.setAutoRange(true)
        ctrlViews.switchAutoRange?.isChecked = true
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            .edit().putBoolean("pref_auto_range", true).apply()

        // Same "half a bandwidth into the range" starting point FrequencyScanner
        // itself uses, so the very first frame drawn already matches reality
        // instead of showing wherever the radio happened to be tuned before
        // Start Scan was pressed.
        val initialCenterHz = minOf(startHz + viewModel.sampleRate.value / 2, stopHz)
        viewModel.setFrequency(initialCenterHz)
        binding.spectrumView.setCenterFrequency(hardwareCenterFreqHz(initialCenterHz))
        binding.spectrumView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(initialCenterHz))
        binding.waterfallView.setCenterFrequency(hardwareCenterFreqHz(initialCenterHz))
        binding.waterfallView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(initialCenterHz))

        binding.spectrumView.setSquelch(sqlDb)
        binding.spectrumView.setDemodChannelBandwidth(stepHz)
    }

    /** Out-of-the-box scan tab defaults, used only the very first time the app
     *  runs (or if the persisted last-used settings are ever cleared/corrupt).
     *  After that, [restoreLastScanTabSettings] / [saveLastScanTabSettings]
     *  take over and the user's own last configuration wins. */
    private object ScanTabDefaults {
        const val START_MHZ = "446.00625"
        const val STOP_MHZ = "446.19375"
        const val STEP_HZ = "12500"
        const val SQUELCH_DB = "-60"
        const val DWELL_MS = "200"
    }

    /** SharedPreferences backing the *automatic* "last used scan settings"
     *  persistence -- distinct from the user-managed 5-slot memory bank
     *  (`scan_memory_slots`) further down in [setupScanTab]. */
    private val scanTabLastSettingsPrefs by lazy {
        getSharedPreferences("scan_last_settings", MODE_PRIVATE)
    }

    private fun saveLastScanTabSettings() {
        scanTabLastSettingsPrefs.edit()
            .putString("startMhz", ctrlViews.etScanTabStart?.text?.toString())
            .putString("stopMhz", ctrlViews.etScanTabStop?.text?.toString())
            .putString("stepHz", ctrlViews.etScanTabStep?.text?.toString())
            .putString("squelchDb", ctrlViews.etScanTabSquelch?.text?.toString())
            .putString("dwellMs", ctrlViews.etScanTabDwell?.text?.toString())
            .putInt("modeIdx", ctrlViews.spinnerScanTabMode?.selectedItemPosition ?: 0)
            .putBoolean("scanDown", ctrlViews.switchScanTabDirection?.isChecked ?: false)
            .putBoolean("adaptive", ctrlViews.switchScanTabAdaptive?.isChecked ?: false)
            .putBoolean("holdOnSignal", ctrlViews.switchScanTabHoldOnSignal?.isChecked ?: true)
            .apply()
    }

    private fun restoreLastScanTabSettings() {
        val prefs = scanTabLastSettingsPrefs
        ctrlViews.etScanTabStart?.setText(prefs.getString("startMhz", ScanTabDefaults.START_MHZ))
        ctrlViews.etScanTabStop?.setText(prefs.getString("stopMhz", ScanTabDefaults.STOP_MHZ))
        ctrlViews.etScanTabStep?.setText(prefs.getString("stepHz", ScanTabDefaults.STEP_HZ))
        ctrlViews.etScanTabSquelch?.setText(prefs.getString("squelchDb", ScanTabDefaults.SQUELCH_DB))
        ctrlViews.etScanTabDwell?.setText(prefs.getString("dwellMs", ScanTabDefaults.DWELL_MS))
        ctrlViews.spinnerScanTabMode?.setSelection(prefs.getInt("modeIdx", viewModel.demodMode.value.ordinal))
        ctrlViews.switchScanTabDirection?.isChecked = prefs.getBoolean("scanDown", false)
        ctrlViews.switchScanTabAdaptive?.isChecked = prefs.getBoolean("adaptive", false)
        ctrlViews.switchScanTabHoldOnSignal?.isChecked = prefs.getBoolean("holdOnSignal", true)
    }

    private fun setupScanTab() {
        val listView = ctrlViews.listScanTabHits ?: return

        // Scanner details (start/stop/step/squelch/dwell/mode/direction/adaptive/
        // memory) are collapsed by default; only the toggle header, Start Scan
        // controls, and status/results are visible until the user expands it.
        ctrlViews.llScanTabDetails?.isVisible = false
        ctrlViews.tvScanTabDetailsChevron?.text = "\u25B6"
        ctrlViews.rowScanTabDetailsToggle?.setOnClickListener {
            val details = ctrlViews.llScanTabDetails
            val expanded = details?.isVisible == true
            details?.isVisible = !expanded
            ctrlViews.tvScanTabDetailsChevron?.text = if (expanded) "\u25B6" else "\u25BC"
        }

        val modes = DemodMode.values().map { it.displayName }
        ctrlViews.spinnerScanTabMode?.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_item, modes)

        // Restore whatever scan parameters were last used (separate from the
        // manual 5-slot memory bank above -- this is the *automatic* "keep my
        // last scan setup" persistence). On first-ever launch, when nothing
        // has been saved yet, fall back to sensible defaults rather than
        // leaving the fields at whatever hardcoded values used to be baked
        // into the layout.
        restoreLastScanTabSettings()

        // Scan parameter memory slots -- stash/recall a full parameter set so the user
        // doesn't have to retype range/step/squelch/dwell/mode each session.
        val scanTabMemPrefs = getSharedPreferences("scan_memory_slots", MODE_PRIVATE)
        val scanTabMemSlotCount = 5
        val scanTabMemSlotLabels = (1..scanTabMemSlotCount).map { slot ->
            if (scanTabMemPrefs.getString("slot_$slot", null) != null) "Slot $slot (saved)" else "Slot $slot (empty)"
        }.toMutableList()
        val scanTabMemSlotAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, scanTabMemSlotLabels)
        ctrlViews.spinnerScanTabMemorySlot?.adapter = scanTabMemSlotAdapter

        fun refreshScanTabMemSlotLabels() {
            for (i in 0 until scanTabMemSlotCount) {
                val slot = i + 1
                val saved = scanTabMemPrefs.getString("slot_$slot", null)
                scanTabMemSlotAdapter.remove(scanTabMemSlotAdapter.getItem(i))
                scanTabMemSlotAdapter.insert(if (saved != null) "Slot $slot (saved)" else "Slot $slot (empty)", i)
            }
            scanTabMemSlotAdapter.notifyDataSetChanged()
        }

        ctrlViews.btnScanTabMemSave?.setOnClickListener {
            val slot = (ctrlViews.spinnerScanTabMemorySlot?.selectedItemPosition ?: 0) + 1
            val params = JSONObject().apply {
                put("startMhz", ctrlViews.etScanTabStart?.text?.toString() ?: "")
                put("stopMhz", ctrlViews.etScanTabStop?.text?.toString() ?: "")
                put("stepHz", ctrlViews.etScanTabStep?.text?.toString() ?: "")
                put("squelchDb", ctrlViews.etScanTabSquelch?.text?.toString() ?: "")
                put("dwellMs", ctrlViews.etScanTabDwell?.text?.toString() ?: "")
                put("modeIdx", ctrlViews.spinnerScanTabMode?.selectedItemPosition ?: 0)
                put("scanDown", ctrlViews.switchScanTabDirection?.isChecked ?: false)
                put("adaptive", ctrlViews.switchScanTabAdaptive?.isChecked ?: false)
                put("holdOnSignal", ctrlViews.switchScanTabHoldOnSignal?.isChecked ?: true)
            }
            scanTabMemPrefs.edit().putString("slot_$slot", params.toString()).apply()
            refreshScanTabMemSlotLabels()
            Snackbar.make(binding.root, "Saved scan parameters to slot $slot", Snackbar.LENGTH_SHORT).show()
        }

        ctrlViews.btnScanTabMemLoad?.setOnClickListener {
            val slot = (ctrlViews.spinnerScanTabMemorySlot?.selectedItemPosition ?: 0) + 1
            val raw = scanTabMemPrefs.getString("slot_$slot", null)
            if (raw == null) {
                Snackbar.make(binding.root, "Slot $slot is empty", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            try {
                val params = JSONObject(raw)
                ctrlViews.etScanTabStart?.setText(params.getString("startMhz"))
                ctrlViews.etScanTabStop?.setText(params.getString("stopMhz"))
                ctrlViews.etScanTabStep?.setText(params.getString("stepHz"))
                ctrlViews.etScanTabSquelch?.setText(params.getString("squelchDb"))
                ctrlViews.etScanTabDwell?.setText(params.getString("dwellMs"))
                ctrlViews.spinnerScanTabMode?.setSelection(params.optInt("modeIdx", 0))
                ctrlViews.switchScanTabDirection?.isChecked = params.optBoolean("scanDown", false)
                ctrlViews.switchScanTabAdaptive?.isChecked = params.optBoolean("adaptive", false)
                ctrlViews.switchScanTabHoldOnSignal?.isChecked = params.optBoolean("holdOnSignal", true)
                Snackbar.make(binding.root, "Loaded scan parameters from slot $slot", Snackbar.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Snackbar.make(binding.root, "Slot $slot is corrupted", Snackbar.LENGTH_SHORT).show()
            }
        }

        scanTabHitAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, scanTabHitLog)
        listView.adapter = scanTabHitAdapter
        listView.setOnItemClickListener { _, _, pos, _ ->
            val hz = scanTabHitFreqs.getOrNull(pos) ?: return@setOnItemClickListener
            viewModel.setFrequency(hz)
        }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            val hz = scanTabHitFreqs.getOrNull(pos) ?: return@setOnItemLongClickListener true
            AlertDialog.Builder(this)
                .setTitle("${hz.toExactMhzString()} MHz")
                .setMessage("Lock this frequency out of the current scan? It will be skipped until the scan is stopped.")
                .setPositiveButton("Lock out") { _, _ ->
                    scanTabScanner?.lockout(hz)
                    Snackbar.make(binding.root, "Locked out ${hz.toExactMhzString()} MHz", Snackbar.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }

        // Tabulated channel view -- one row per configured channel,
        // refreshed in place as the scan runs (see attachScanTabCollectors'
        // collector on sc.channelTable below), rather than an append-only
        // log like listScanTabHits above. Uses a dedicated multi-column
        // adapter (ScanChannelTableAdapter) instead of a plain string list
        // so each field lines up under its own labeled column.
        val channelListView = ctrlViews.listScanTabChannelTable
        scanTabChannelAdapter = ScanChannelTableAdapter(this, scanTabChannelRows)
        channelListView?.adapter = scanTabChannelAdapter
        channelListView?.setOnItemClickListener { _, _, pos, _ ->
            val hz = scanTabChannelRows.getOrNull(pos)?.freqHz ?: return@setOnItemClickListener
            viewModel.setFrequency(hz)
        }
        channelListView?.setOnItemLongClickListener { _, _, pos, _ ->
            val hz = scanTabChannelRows.getOrNull(pos)?.freqHz ?: return@setOnItemLongClickListener true
            AlertDialog.Builder(this)
                .setTitle("${hz.toExactMhzString()} MHz")
                .setMessage("Lock this frequency out of the current scan? It will be skipped until the scan is stopped.")
                .setPositiveButton("Lock out") { _, _ ->
                    scanTabScanner?.lockout(hz)
                    Snackbar.make(binding.root, "Locked out ${hz.toExactMhzString()} MHz", Snackbar.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }

        fun resetScanTabControls() {
            scanTabScanning = false
            scanTabPaused = false
            scanTabSearching = false
            ctrlViews.btnScanTabStartStop?.text = "Start Scan"
            ctrlViews.btnScanTabPause?.text = "Pause"
            ctrlViews.btnScanTabPause?.isVisible = false
            ctrlViews.btnScanTabSearch?.text = "Search Mode"
            ctrlViews.btnScanTabSearch?.isEnabled = true
            ctrlViews.btnScanTabStartStop?.isEnabled = true
            ctrlViews.progressScanTab?.isVisible = false
        }

        fun scanTabSource(): Pair<RtlSdrService, IqSource>? {
            val svc = sdrService
            if (svc == null) {
                Snackbar.make(binding.root, "No RTL-SDR connected", Snackbar.LENGTH_SHORT).show()
                return null
            }
            val source = svc.source
            if (source == null) {
                Snackbar.make(binding.root, "No IQ source connected", Snackbar.LENGTH_SHORT).show()
                return null
            }
            return svc to source
        }

        fun signalLevelProviderFor(svc: RtlSdrService, source: IqSource): () -> Float = {
            // Channel-specific dBFS at the tuned (DC) frequency -- NOT the
            // wideband spectrum peak (statsFlow.signalDb). The wideband peak
            // reflects the strongest signal anywhere across the whole
            // captured window (e.g. 2 MHz), so as the scanner retuned
            // channel to channel it barely changed and Squelch dB ended up
            // gating on an essentially constant, channel-irrelevant value.
            // narrowbandCenterDb averages only the FFT bins around the tuned
            // centre frequency, so it actually reflects the channel
            // currently being probed. See FrequencyScanner / DspEngine
            // squelch fix notes above (DspEngine.narrowbandCenterDb).
            svc.dspEngine?.statsFlow?.value?.narrowbandCenterDb ?: source.statusFlow.value.signalStrengthDb
        }

        fun attachScanTabCollectors(sc: com.radiosport.ninegradio.scanner.FrequencyScanner) {
            scanTabStatusJob?.cancel()
            scanTabStatusJob = lifecycleScope.launch {
                sc.status.collectLatest { status ->
                    ctrlViews.progressScanTab?.progress = (status.progress * 100).toInt()
                    ctrlViews.tvScanTabCurrentFreq?.text = "${status.currentFreqHz.toExactMhzString()} MHz"
                    ctrlViews.tvScanTabHits?.text = "Hits: ${status.hitsFound}"
                    ctrlViews.tvScanTabSignal?.text = "${"%.1f".format(status.signalDb)} dB"
                    ctrlViews.tvScanTabNoiseFloor?.text = "Floor: ${"%.1f".format(status.noiseFloorDb)} dB"
                    ctrlViews.tvScanTabRate?.text = "${"%.1f".format(status.channelsPerSecond)} ch/s"

                    // FrequencyScanner retunes the raw IqSource directly every
                    // hop (see captureBlock), so the spectrum/waterfall's own
                    // notion of "where the hardware is" goes stale the moment
                    // a scan starts unless it's told about every retune too.
                    // These are display-only setters -- they don't re-issue a
                    // hardware tune command themselves -- so they can't race
                    // with the scanner, which is the sole owner of the device
                    // frequency while a scan is running.
                    if (status.currentFreqHz > 0L) {
                        binding.spectrumView.setCenterFrequency(hardwareCenterFreqHz(status.currentFreqHz))
                        binding.spectrumView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(status.currentFreqHz))
                        binding.waterfallView.setCenterFrequency(hardwareCenterFreqHz(status.currentFreqHz))
                        binding.waterfallView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(status.currentFreqHz))
                    }
                    if (status.activeFreqHz > 0L) {
                        binding.spectrumView.setDemodDialFrequency(status.activeFreqHz)
                    }
                }
            }
            scanTabHitsJob?.cancel()
            scanTabHitsJob = lifecycleScope.launch {
                sc.hits.collect { hit ->
                    // FrequencyScanner retunes the raw IqSource directly for its own
                    // wideband FFT captures (see FrequencyScanner.captureBlock), entirely
                    // bypassing DspEngine.setCarrierFrequency. That means the *live* demod
                    // chain (DspEngine -> AudioEngine) was never actually told to move to
                    // the frequency the scanner just declared a hit on -- it kept
                    // demodulating whatever dial frequency was tuned before Start Scan was
                    // pressed. The scan tab's spectrum/waterfall dial cosmetically jumped to
                    // the hit (setDemodDialFrequency above) making it *look* locked, but no
                    // audio ever came out because the hardware carrier/BFO math in DspEngine
                    // never got the retune. Route the hit frequency through
                    // RtlSdrService.setFrequency() (-> DspEngine.setCarrierFrequency()) so
                    // the live receiver actually tunes there and decodes audio while the
                    // scanner dwells on this hit, matching how real scanners (and e.g.
                    // SDRangel's channel-follows-detection behaviour) sync their live
                    // demodulator to whatever the scan engine just found.
                    sdrService?.setFrequency(hit.freqHz)

                    val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(hit.timestampMs))
                    val tag = when (hit.source) {
                        com.radiosport.ninegradio.scanner.FrequencyScanner.HitSource.PRIORITY -> " ★"
                        com.radiosport.ninegradio.scanner.FrequencyScanner.HitSource.SEARCH -> " ?"
                        else -> ""
                    }
                    val entry = "[$ts]  ${hit.freqHz.toExactMhzString()} MHz  " +
                        "${"%.0f".format(hit.signalDb)}dB$tag"
                    scanTabHitLog.add(0, entry)
                    scanTabHitFreqs.add(0, hit.freqHz)
                    if (scanTabHitLog.size > 300) {
                        scanTabHitLog.removeAt(scanTabHitLog.size - 1)
                        scanTabHitFreqs.removeAt(scanTabHitFreqs.size - 1)
                    }
                    scanTabHitAdapter?.notifyDataSetChanged()

                    // Persist every confirmed hit, same as the standalone scanner.
                    val db = (application as com.radiosport.ninegradio.RtlSdrApplication).database
                    lifecycleScope.launch {
                        db.signalLogDao().insert(
                            com.radiosport.ninegradio.data.SignalLog(
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

            scanTabChannelTableJob?.cancel()
            scanTabChannelTableJob = lifecycleScope.launch {
                sc.channelTable.collectLatest { rows ->
                    scanTabChannelRows.clear()
                    scanTabChannelRows.addAll(rows)
                    scanTabChannelAdapter?.notifyDataSetChanged()
                }
            }
        }

        ctrlViews.btnScanTabStartStop?.setOnClickListener {
            if (!scanTabScanning) {
                val (svc, source) = scanTabSource() ?: return@setOnClickListener

                val startMhz = ctrlViews.etScanTabStart?.text?.toString()?.toDoubleOrNull() ?: 136.0
                val stopMhz  = ctrlViews.etScanTabStop?.text?.toString()?.toDoubleOrNull() ?: (startMhz + 38.0)
                val startHz = (startMhz * 1_000_000).toLong()
                    .coerceIn(RtlSdrDevice.MIN_FREQ_HZ, RtlSdrDevice.MAX_FREQ_HZ)
                val stopHz  = (stopMhz * 1_000_000).toLong()
                    .coerceIn(startHz + 1, RtlSdrDevice.MAX_FREQ_HZ)
                val stepHz  = ctrlViews.etScanTabStep?.text?.toString()?.toLongOrNull() ?: 12_500L
                val snappedStartHz = FrequencyStepManager.snapToChannel(startHz, stepHz)
                    .coerceIn(RtlSdrDevice.MIN_FREQ_HZ, RtlSdrDevice.MAX_FREQ_HZ)
                val sqlDb   = ctrlViews.etScanTabSquelch?.text?.toString()?.toFloatOrNull() ?: -80f
                val dwellMs = ctrlViews.etScanTabDwell?.text?.toString()?.toLongOrNull() ?: 200L
                val modeIdx = ctrlViews.spinnerScanTabMode?.selectedItemPosition ?: 0
                val mode    = DemodMode.values().getOrElse(modeIdx) { DemodMode.NFM }
                val adaptive = ctrlViews.switchScanTabAdaptive?.isChecked ?: false
                val holdOnSignal = ctrlViews.switchScanTabHoldOnSignal?.isChecked ?: true

                // Remember these as the "last used" scan settings so they're
                // restored automatically next time the scan tab is opened,
                // regardless of whether the user ever touches the manual
                // memory-slot bank above.
                saveLastScanTabSettings()

                // The scanner retunes the raw IqSource directly (bypassing
                // DspEngine.setCarrierFrequency), but DspEngine keeps demodulating
                // and playing audio continuously through the retunes using whatever
                // mode/squelch was active *before* Start Scan was pressed. Without
                // this sync, a user who picks e.g. NFM in the scan tab's mode
                // spinner would still hear (or fail to hear) audio demodulated in
                // the previously-selected mode on every hit, and the live squelch
                // gate would be completely unrelated to the scanner's own
                // detection threshold. Apply the scan's mode/squelch to the live
                // demod chain before the sweep starts so audio on a hit is
                // actually correct for the mode the user configured.
                captureRfSnapshotBeforeScan()
                svc.setDemodMode(mode)
                svc.setSquelch(sqlDb)

                configureSpectrumForScan(snappedStartHz, stopHz, stepHz, sqlDb, dwellMs)

                scanTabScanner = com.radiosport.ninegradio.scanner.FrequencyScanner(
                    source, signalLevelProvider = signalLevelProviderFor(svc, source),
                    // Keep the live spectrum's frame averaging/smoothing from
                    // blending frames across the scanner's retunes -- see
                    // FftEngine.resetSpectrumAveraging() for why that matters
                    // to Auto dB Range specifically.
                    onRetune = { svc.dspEngine?.fftEngine?.resetSpectrumAveraging() }
                ).also { sc ->
                    sc.startScan(com.radiosport.ninegradio.scanner.FrequencyScanner.ScanConfig(
                        startFreqHz = snappedStartHz,
                        stopFreqHz  = stopHz,
                        stepHz = stepHz,
                        squelchDb = sqlDb,
                        adaptiveSquelch = adaptive,
                        holdOnSignal = holdOnSignal,
                        dwellTimeMs = dwellMs,
                        mode = mode,
                        scanUp = !(ctrlViews.switchScanTabDirection?.isChecked ?: false),
                        label = "${"%.3f".format(startMhz)}-${"%.3f".format(stopMhz)} MHz"
                    ))
                    attachScanTabCollectors(sc)
                }
                scanTabScanning = true
                ctrlViews.btnScanTabStartStop?.text = "Stop Scan"
                ctrlViews.btnScanTabPause?.isVisible = true
                ctrlViews.progressScanTab?.isVisible = true
                ctrlViews.btnScanTabSearch?.isEnabled = false
            } else {
                scanTabScanner?.stopScan()
                scanTabScanner = null
                ctrlViews.btnScanTabSearch?.isEnabled = true
                resetScanTabControls()
                restoreRfSnapshotAfterScan()
            }
        }

        ctrlViews.btnScanTabPause?.setOnClickListener {
            if (!scanTabPaused) {
                scanTabScanner?.pauseScan()
                ctrlViews.btnScanTabPause?.text = "Resume"
                scanTabPaused = true
            } else {
                scanTabScanner?.resumeScan()
                ctrlViews.btnScanTabPause?.text = "Pause"
                scanTabPaused = false
            }
        }

        ctrlViews.btnScanTabSearch?.setOnClickListener {
            if (!scanTabSearching) {
                val (svc, source) = scanTabSource() ?: return@setOnClickListener
                val startMhz = ctrlViews.etScanTabStart?.text?.toString()?.toDoubleOrNull() ?: 136.0
                val stopMhz  = ctrlViews.etScanTabStop?.text?.toString()?.toDoubleOrNull() ?: (startMhz + 38.0)
                val startHz = (startMhz * 1_000_000).toLong().coerceIn(RtlSdrDevice.MIN_FREQ_HZ, RtlSdrDevice.MAX_FREQ_HZ)
                val stopHz  = (stopMhz * 1_000_000).toLong().coerceIn(startHz + 1, RtlSdrDevice.MAX_FREQ_HZ)
                val modeIdx = ctrlViews.spinnerScanTabMode?.selectedItemPosition ?: 0
                val mode    = DemodMode.values().getOrElse(modeIdx) { DemodMode.NFM }
                // Same squelch controls the regular scan uses — search mode
                // previously ignored these entirely and always ran with a
                // forced adaptive threshold, which is why it picked up noise
                // regardless of what was set here.
                val sqlDb   = ctrlViews.etScanTabSquelch?.text?.toString()?.toFloatOrNull() ?: -80f
                val adaptive = ctrlViews.switchScanTabAdaptive?.isChecked ?: false

                saveLastScanTabSettings()

                // See the matching comment in the Start Scan handler: keep the
                // live demod chain in sync with what the scan tab is configured
                // for, so audio on a hit is intelligible.
                captureRfSnapshotBeforeScan()
                svc.setDemodMode(mode)
                svc.setSquelch(sqlDb)

                // Search mode has no user-facing step field; it hunts within
                // whatever channel raster FrequencyScanner's search default
                // uses, so fall back to the regular scan tab's step field
                // (or a sane default) purely to size the capture width below.
                val searchStepHz = ctrlViews.etScanTabStep?.text?.toString()?.toLongOrNull() ?: 12_500L
                configureSpectrumForScan(startHz, stopHz, searchStepHz, sqlDb, dwellMs = 40L)

                scanTabScanner = com.radiosport.ninegradio.scanner.FrequencyScanner(
                    source, signalLevelProvider = signalLevelProviderFor(svc, source),
                    onRetune = { svc.dspEngine?.fftEngine?.resetSpectrumAveraging() }
                ).also { sc ->
                    sc.startSearch(
                        startFreqHz = startHz, stopFreqHz = stopHz, mode = mode,
                        squelchDb = sqlDb, adaptiveSquelch = adaptive
                    )
                    attachScanTabCollectors(sc)
                }
                scanTabSearching = true
                scanTabScanning = true
                ctrlViews.btnScanTabSearch?.text = "Stop Search"
                ctrlViews.btnScanTabStartStop?.isEnabled = false
                ctrlViews.progressScanTab?.isVisible = true
            } else {
                scanTabScanner?.stopScan()
                scanTabScanner = null
                ctrlViews.btnScanTabStartStop?.isEnabled = true
                resetScanTabControls()
                restoreRfSnapshotAfterScan()
            }
        }

        ctrlViews.btnScanTabReset?.setOnClickListener {
            // Stop any in-progress scan/search so the reset state is clean.
            scanTabScanner?.stopScan()
            scanTabScanner?.destroy()
            scanTabScanner = null
            ctrlViews.btnScanTabStartStop?.isEnabled = true
            ctrlViews.btnScanTabSearch?.isEnabled = true
            resetScanTabControls()
            restoreRfSnapshotAfterScan()

            // Clear the scan results list.
            scanTabHitLog.clear()
            scanTabHitFreqs.clear()
            scanTabHitAdapter?.notifyDataSetChanged()

            // Clear the tabulated channel view too.
            scanTabChannelRows.clear()
            scanTabChannelAdapter?.notifyDataSetChanged()

            // Reset the live status readouts.
            ctrlViews.progressScanTab?.progress = 0
            ctrlViews.tvScanTabCurrentFreq?.text = "---"
            ctrlViews.tvScanTabHits?.text = "Hits: 0"
            ctrlViews.tvScanTabSignal?.text = "--- dB"
            ctrlViews.tvScanTabNoiseFloor?.text = "Floor: --- dB"
            ctrlViews.tvScanTabRate?.text = "--- ch/s"

            Snackbar.make(binding.root, "Scan list reset", Snackbar.LENGTH_SHORT).show()
        }

        ctrlViews.btnScanTabBusiest?.setOnClickListener {
            val sc = scanTabScanner
            if (sc == null) {
                Snackbar.make(binding.root, "Start a scan first", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val top = sc.topFrequencies(15)
            if (top.isEmpty()) {
                Snackbar.make(binding.root, "No hits recorded yet", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val message = top.joinToString("\n") { (hz, count) ->
                "${hz.toExactMhzString()} MHz  —  $count hit${if (count == 1) "" else "s"}"
            }
            AlertDialog.Builder(this)
                .setTitle("Busiest Frequencies")
                .setMessage(message)
                .setPositiveButton("Close", null)
                .show()
        }

        ctrlViews.btnScanTabClose?.setOnClickListener { hideScanTab() }
    }

    /**
     * Reveals the Scan tab and navigates to it. Mirrors [updateAprsTabVisibility]
     * et al., except there is no associated DemodMode driving it -- it is only
     * ever called from an explicit user action (Scanner button / FAB).
     */
    private fun showScanTab() {
        val tabLayout = binding.controlTabLayout
        val scanTabIndex = ControlsPagerAdapter.TAB_SCAN
        tabLayout.getTabAt(scanTabIndex)?.view?.visibility = android.view.View.VISIBLE
        binding.controlViewPager.setCurrentItem(scanTabIndex, true)
        ctrlViews.etScanTabStart?.setText(viewModel.centerFreqHz.value.toExactMhzString())
    }

    /**
     * Stops any running scan and hides the Scan tab again. If the currently
     * selected tab is Scan, falls back to the Tune tab so the drawer never
     * ends up parked on a hidden tab.
     */
    private fun hideScanTab() {
        scanTabScanner?.stopScan()
        scanTabScanner?.destroy()
        scanTabScanner = null
        scanTabStatusJob?.cancel()
        scanTabHitsJob?.cancel()
        scanTabScanning = false
        scanTabPaused = false
        scanTabSearching = false
        restoreRfSnapshotAfterScan()
        ctrlViews.btnScanTabStartStop?.text = "Start Scan"
        ctrlViews.btnScanTabPause?.isVisible = false
        ctrlViews.progressScanTab?.isVisible = false

        val tabLayout = binding.controlTabLayout
        val scanTabIndex = ControlsPagerAdapter.TAB_SCAN
        if (binding.controlViewPager.currentItem == scanTabIndex) {
            binding.controlViewPager.setCurrentItem(ControlsPagerAdapter.TAB_TUNE, false)
        }
        tabLayout.getTabAt(scanTabIndex)?.view?.visibility = android.view.View.GONE
    }

} // end MainActivity

/**
 * Returns the [UsbDevice] extra from this intent using the API-level-appropriate
 * overload of [Intent.getParcelableExtra].
 *
 * The single-type-parameter form `getParcelableExtra<T>()` is deprecated since
 * API 33 (Android 13) and returns null on API 33+ release builds, breaking USB
 * hotplug detection.  The two-parameter form `getParcelableExtra(key, Class<T>)`
 * is required on API 33+.
 */
private fun Intent.usbDevice(): UsbDevice? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    else
        @Suppress("DEPRECATION")
        getParcelableExtra(UsbManager.EXTRA_DEVICE)

/**
 * Adapter for the scan tab's tabulated channel view (see FrequencyScanner's
 * `channelTable`). Binds each [com.radiosport.ninegradio.scanner.FrequencyScanner.ChannelEntry]
 * field to its own fixed-width column in item_scan_channel_row.xml, rather
 * than flattening the row into one string — so frequency, power, floor,
 * hits, and age each stay in their own labeled column (matching the header
 * in item_scan_channel_header.xml) instead of running together.
 */
private class ScanChannelTableAdapter(
    context: android.content.Context,
    rows: List<com.radiosport.ninegradio.scanner.FrequencyScanner.ChannelEntry>
) : ArrayAdapter<com.radiosport.ninegradio.scanner.FrequencyScanner.ChannelEntry>(
    context, R.layout.item_scan_channel_row, rows
) {
    private class ViewHolder(view: View) {
        val status: TextView = view.findViewById(R.id.tvColStatus)
        val freq: TextView = view.findViewById(R.id.tvColFreq)
        val power: TextView = view.findViewById(R.id.tvColPower)
        val floor: TextView = view.findViewById(R.id.tvColFloor)
        val hits: TextView = view.findViewById(R.id.tvColHits)
        val age: TextView = view.findViewById(R.id.tvColAge)
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_scan_channel_row, parent, false)
        val holder = (view.tag as? ViewHolder) ?: ViewHolder(view).also { view.tag = it }
        val row = getItem(position) ?: return view

        val measured = row.lastUpdatedMs > 0L
        holder.status.text = if (row.active) "\u25CF" else "\u25CB"
        holder.status.setTextColor(if (row.active) 0xFF44FF44.toInt() else 0xFF666666.toInt())
        holder.freq.text = row.freqHz.toExactMhzString()
        holder.power.text = if (measured) "%.1f".format(row.lastPowerDb) else "---"
        holder.floor.text = if (measured) "%.1f".format(row.lastNoiseFloorDb) else "---"
        holder.hits.text = row.hitCount.toString()
        holder.age.text = if (measured)
            "${(System.currentTimeMillis() - row.lastUpdatedMs) / 1000}s"
        else "never"
        // Highlight the whole row while it's actively above threshold, same
        // sense as the ● marker, so an active channel is easy to spot at a
        // glance in a long table.
        view.setBackgroundColor(if (row.active) 0x332C6E2E else 0x0012161C)
        return view
    }
}
