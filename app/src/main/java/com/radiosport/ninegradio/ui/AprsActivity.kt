package com.radiosport.ninegradio.ui

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Paint
import android.os.Bundle
import android.os.IBinder
import android.view.*
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.radiosport.ninegradio.R
import com.radiosport.ninegradio.dsp.AprsDecoder
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.dsp.DspEngine
import com.radiosport.ninegradio.usb.RtlSdrService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * APRS monitoring activity.
 *
 * Frequency strategy
 * ──────────────────
 * APRS uses two worldwide channels:
 *   • 144.390 MHz — North America (NA)
 *   • 144.800 MHz — Europe / Australia (EU)
 *
 * Gap between them = 410 kHz; midpoint = 144.595 MHz.
 * To capture both simultaneously the device must be tuned to the midpoint
 * and operating at a sample rate ≥ 820 kHz (so each channel, ±205 kHz from
 * centre, lies inside the passband).
 *
 * Dual-watch mode (sample rate ≥ 820 kHz)
 * ────────────────────────────────────────
 * The device is tuned to 144.595 MHz.  DspEngine.dualAprsFlow produces two
 * independent NFM-demodulated audio streams — one per channel — via a
 * per-channel NCO mix → polyphase decimation → FM discriminator → resample
 * pipeline that runs in parallel with the normal DSP chain.  Two AprsDecoder
 * instances decode the streams independently; their packets are distinguished
 * by a "channel" tag added to the display entry.
 *
 * Single-channel fallback (sample rate < 820 kHz)
 * ────────────────────────────────────────────────
 * Only one channel can be received at a time.  The NA/EU buttons always tune
 * directly to that frequency (forceSingle = true), overriding dual-watch if it
 * was active.  The default channel is EU 144.800 MHz — the worldwide APRS
 * frequency used outside North America.
 *
 * Settings preservation
 * ─────────────────────
 * This activity ONLY changes the centre frequency (and the demod mode to NFM
 * — the protocol APRS uses — so the squelch/spectrum path is sensible).
 * Every other setting — sample rate, gain mode, gain index, tuner AGC,
 * hardware AGC, squelch, volume, noise blanker, noise reducer, PPM correction
 * — is read from the MainViewModel (which mirrors the user's persisted prefs)
 * and left unchanged on the device.  On exit no settings are restored because
 * none were changed.
 *
 * Threading model
 * ───────────────
 * FIX 23: AprsDecoder.feed() is a synchronous per-sample DSP loop (see
 * ProtocolDecoders.kt FIX 21 for why it must not be a suspend function).
 * Calling it from lifecycleScope.launch — which defaults to Dispatchers.Main
 * — blocks the UI thread for the full duration of each audio block, causing
 * dropped blocks (audioFlow uses DROP_OLDEST) and potential ANR.
 *
 * Single-channel fix: instead of maintaining duplicate decoderNA/EU instances
 * that re-decode audio on the Main thread, the activity now subscribes to
 * DspEngine.aprsDecoder.packets directly.  DspEngine already decodes audio
 * on the DSP thread (Dispatchers.Default, FIX 21) and the resulting packets
 * are observed here on the Main thread for UI updates — no DSP on Main.
 *
 * Dual-watch fix: dualAprsFlow arrives as pre-decoded audio that must be fed
 * to the Activity's own NA/EU decoder instances (DspEngine has no built-in
 * dual-watch decoder).  Those feed() calls now run on Dispatchers.Default to
 * keep the UI thread free.  Only the resulting packets flow back to Main for
 * display.
 */
class AprsActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    // Dual-watch only: Activity-owned decoders for the two APRS channels.
    // In single-channel mode these are unused; packets come from DspEngine.aprsDecoder.
    private val decoderNA = AprsDecoder()   // North America 144.390 MHz
    private val decoderEU = AprsDecoder()   // Europe/AU 144.800 MHz

    private val packets   = mutableListOf<DisplayPacket>()
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var tvStatus: TextView
    private lateinit var tvMode: TextView

    /** Lightweight wrapper so we can show which channel a packet came from. */
    private data class DisplayPacket(
        val pkt: AprsDecoder.AprsPacket,
        val channel: String   // "NA" or "EU"
    )

    // ─── Service binding ──────────────────────────────────────────────────────

    private var sdrService: RtlSdrService? = null
    private var serviceBound = false
    private var audioFeedJob: Job? = null

    /** True when the device sample rate is wide enough to receive both channels. */
    private var dualWatchActive = false

    /** The demod mode that was active before we switched to APRS; restored on exit. */
    private var previousMode: DemodMode = DemodMode.NFM

    /** Centre frequency currently commanded to the device. */
    private var currentCentreHz = DspEngine.APRS_MIDPOINT_HZ

    /**
     * Single-channel frequency selected by the user via the NA/EU buttons.
     * Only relevant in single-channel fallback mode.
     * Defaults to 144.800 MHz (EU/worldwide) — the primary APRS frequency.
     */
    private var singleChannelHz = DspEngine.APRS_EU_HZ

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            sdrService = (binder as RtlSdrService.LocalBinder).getService()
            serviceBound = true
            // Save the current mode so we can restore it when the user leaves.
            previousMode = viewModel.demodMode.value
            // Switch to APRS mode — selects AprsDemodulator (raw FM discriminator,
            // no post-discriminator AGC/LPF/limiter) and gates aprsDecoder.feed().
            // Without this, NfmDemodulator's voice AGC equalises both AFSK tones
            // to the same amplitude, destroying the per-channel level information
            // that AfskBandpassFilter.processAgc() needs to compensate for the
            // 3–6 dB de-emphasis imbalance between 1200 Hz and 2200 Hz.
            viewModel.setDemodMode(DemodMode.APRS)
            // forceSingle = true: open on the default frequency (144.800 MHz) without
            // triggering the dual-watch retune to the midpoint (144.595 MHz).
            // The user's spectrum stays exactly where it was before opening this activity.
            applyFrequencyAndMode(forceSingle = true)
            startAudioFeed()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            audioFeedJob?.cancel()
            sdrService = null
            serviceBound = false
            dualWatchActive = false
        }
    }

    // ─── Frequency / mode logic ───────────────────────────────────────────────

    /**
     * Tune the device and activate the appropriate receive path.
     *
     * APRS uses NFM (Narrow FM) as its modulation protocol.  The demodulation
     * mode is set to [DemodMode.APRS] in [onServiceConnected] before this
     * function is called; DspEngine gates aprsDecoder.feed() on that mode flag,
     * so audio actually reaches the decoder.
     *
     * Only the centre frequency and dual-watch path are changed here.  All other
     * hardware and DSP settings (sample rate, gain, squelch, noise blanker/
     * reducer, PPM…) are left exactly as the user configured them in the main
     * screen.
     *
     * @param forceSingle When true (e.g. user pressed a frequency button),
     *   bypass the dual-watch check and tune directly to [singleChannelHz].
     *   This ensures pressing NA/EU always switches to that exact frequency.
     *
     * Called from both onCreate (before service bind, so a second call comes
     * from onServiceConnected) and whenever the user presses NA/EU.
     */
    private fun applyFrequencyAndMode(forceSingle: Boolean = false) {
        val svc = sdrService ?: return
        val deviceRate = viewModel.sampleRate.value
        val canDualWatch = !forceSingle && deviceRate >= DspEngine.DUAL_APRS_MIN_SAMPLE_RATE

        if (canDualWatch) {
            // ── Dual-watch: tune to midpoint, activate both decoder chains ────
            currentCentreHz = DspEngine.APRS_MIDPOINT_HZ
            svc.setFrequency(currentCentreHz)
            svc.enableDualAprs(currentCentreHz)
            dualWatchActive = true

            title = "APRS Monitor — Dual Watch (NA + EU)"
            tvMode.text = "Dual Watch: 144.390 + 144.800 MHz  |  ${deviceRate / 1000} kS/s"
        } else {
            // ── Single-channel: tune directly to singleChannelHz ─────────────
            svc.disableDualAprs()
            dualWatchActive = false
            currentCentreHz = singleChannelHz
            svc.setFrequency(currentCentreHz)

            val label = if (singleChannelHz == DspEngine.APRS_NA_HZ) "NA 144.390" else "EU 144.800"
            title = "APRS Monitor — $label MHz"
            tvMode.text = "Single channel: ${"%.3f".format(currentCentreHz / 1e6)} MHz  |  " +
                          "${deviceRate / 1000} kS/s  (≥820 kS/s for dual-watch)"
        }

        // Keep both decoders' sample-rate caches in sync with the audio rate
        // fed to them (always DEFAULT_AUDIO_RATE = 48 000 Hz after resampling).
        decoderNA.setSampleRate(DspEngine.DEFAULT_AUDIO_RATE)
        decoderEU.setSampleRate(DspEngine.DEFAULT_AUDIO_RATE)
    }

    // ─── Audio collection ─────────────────────────────────────────────────────

    private fun startAudioFeed() {
        audioFeedJob?.cancel()
        val svc = sdrService ?: return

        audioFeedJob = lifecycleScope.launch {

            // Status label — reacts immediately to connect/disconnect transitions.
            launch {
                svc.connectionState.collectLatest { state ->
                    when (state) {
                        is RtlSdrService.ConnectionState.Connected -> {
                            tvStatus.text = "Listening…"
                            svc.statsFlow.collect { stats ->
                                tvStatus.text = if (stats.squelchOpen) "SIGNAL ▲" else "Listening…"
                            }
                        }
                        is RtlSdrService.ConnectionState.Connecting ->
                            tvStatus.text = "Connecting…"
                        else ->
                            tvStatus.text = "No Device"
                    }
                }
            }

            // ── Dual-watch audio feed ─────────────────────────────────────────
            // FIX 23: feed() is a synchronous per-sample DSP loop and must NOT
            // run on the Main thread.  Dispatch to Dispatchers.Default so the
            // UI thread stays free and no audio blocks are dropped due to the
            // Main thread being busy rendering.
            launch(Dispatchers.Default) {
                svc.dualAprsFlow.collect { (audioNA, audioEU) ->
                    // Each pair arrives at 48 kHz; feed to the respective decoder.
                    // Running on Default: both feed() calls are synchronous and
                    // sequential within this coroutine — no concurrent access.
                    decoderNA.feed(audioNA)
                    decoderEU.feed(audioEU)
                }
            }

            // ── Single-channel: subscribe to DspEngine.aprsDecoder.packets ────
            // FIX 23: In single-channel mode DspEngine already decodes audio on
            // its DSP thread (Dispatchers.Default, FIX 21) via aprsDecoder.feed()
            // at the same rate as IQ blocks arrive.  Re-decoding the same audio
            // in the Activity via audioFlow.collect on Main is redundant AND
            // harmful: the per-sample DSP loop blocks the UI thread and
            // audioFlow's DROP_OLDEST policy silently discards blocks whenever
            // Main is busy, breaking the AFSK bit-clock in the Activity-side
            // decoders even when DspEngine.aprsDecoder is working perfectly.
            //
            // Fix: subscribe to DspEngine.aprsDecoder.packets directly.  The
            // packets are already decoded — we only need to display them.
            // Packet observation runs on Main (lifecycleScope default) so UI
            // updates are safe without withContext(Main).
            //
            // This coroutine and the dual-watch coroutine above both stay alive
            // for the lifetime of audioFeedJob.  In dual-watch mode only the
            // dualAprsFlow coroutine produces useful work (DspEngine.aprsDecoder
            // is fed the midpoint audio, not a pure APRS channel); in single-
            // channel mode only this coroutine produces useful work (dualAprsFlow
            // emits nothing when dual-watch is disabled).
            launch {
                val engineDecoder = svc.dspEngine?.aprsDecoder ?: return@launch
                engineDecoder.packets.collect { pkt ->
                    if (!dualWatchActive) {
                        // Determine channel tag from the frequency the device is
                        // currently tuned to.
                        val tag = if (currentCentreHz == DspEngine.APRS_EU_HZ) "EU" else "NA"
                        onPacketReceived(pkt, tag)
                    }
                }
            }
        }
    }

    // ─── Packet display helper ────────────────────────────────────────────────

    /**
     * Add a decoded packet to the display list and map.
     * Must be called from the Main thread (all callers are in Main-dispatched coroutines).
     */
    private fun onPacketReceived(pkt: AprsDecoder.AprsPacket, channelTag: String) {
        val dp = DisplayPacket(pkt, channelTag)
        packets.add(0, dp)
        if (packets.size > 200) packets.removeLast()

        val entry = buildString {
            val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date(pkt.timestamp))
            append("[$ts][$channelTag] ${pkt.source}>${pkt.destination}")
            if (pkt.latitude != null && pkt.longitude != null) {
                append("  ${"%.4f".format(pkt.latitude)},${"%.4f".format(pkt.longitude)}")
            }
            append("\n${pkt.comment.take(50)}")
        }
        displayList.add(0, entry)
        if (displayList.size > 200) displayList.removeLast()
        adapter.notifyDataSetChanged()

        tvPacketCnt?.text = "Packets: ${packets.size}"
        aprsMapView?.addPacket(pkt)
    }

    // Views referenced from onPacketReceived — lateinit set in onCreate.
    private lateinit var displayList: MutableList<String>
    private var tvPacketCnt: TextView? = null
    private var aprsMapView: AprsMapView? = null

    // ─── onCreate ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_aprs)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val listView    = findViewById<ListView>(R.id.listAprsPackets)
        tvStatus        = findViewById<TextView>(R.id.tvAprsStatus)
        tvMode          = findViewById<TextView>(R.id.tvAprsMode)
        tvPacketCnt     = findViewById<TextView>(R.id.tvAprsCount)
        val btnNA       = findViewById<Button>(R.id.btnAprsNA)
        val btnEU       = findViewById<Button>(R.id.btnAprsEU)
        aprsMapView     = findViewById<AprsMapView>(R.id.aprsMapView)

        displayList = mutableListOf()
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, displayList)
        listView.adapter = adapter

        // NA / EU buttons always tune to that specific frequency.
        // forceSingle = true bypasses the dual-watch check so pressing a button
        // reliably switches the receiver to exactly 144.390 or 144.800 MHz.
        btnNA.setOnClickListener {
            singleChannelHz = DspEngine.APRS_NA_HZ
            applyFrequencyAndMode(forceSingle = true)
        }
        btnEU.setOnClickListener {
            singleChannelHz = DspEngine.APRS_EU_HZ
            applyFrequencyAndMode(forceSingle = true)
        }

        // ── Dual-watch packet display ─────────────────────────────────────────
        // FIX 23: Collect from the Activity's own NA/EU decoders (fed on
        // Dispatchers.Default above) back onto Main for UI updates.
        fun collectDualWatchPackets(decoder: AprsDecoder, channelTag: String) {
            lifecycleScope.launch {
                decoder.packets.collect { pkt ->
                    if (dualWatchActive) {
                        onPacketReceived(pkt, channelTag)
                    }
                }
            }
        }

        collectDualWatchPackets(decoderNA, "NA")
        collectDualWatchPackets(decoderEU, "EU")

        listView.setOnItemClickListener { _, _, pos, _ ->
            val dp = packets.getOrNull(pos) ?: return@setOnItemClickListener
            showPacketDetail(dp)
        }

        // Bind to the existing service — applyFrequencyAndMode() fires from
        // onServiceConnected once we know the service (and thus device) is live.
        bindService(Intent(this, RtlSdrService::class.java), serviceConnection, BIND_AUTO_CREATE)
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        audioFeedJob?.cancel()
        // Cleanly disable the dual-APRS DSP path so its per-channel NCO/decimator/
        // resampler objects are released and the engine stops emitting dualAprsFlow.
        sdrService?.disableDualAprs()
        // Restore the demod mode the user had before opening this activity.
        viewModel.setDemodMode(previousMode)
        if (serviceBound) { unbindService(serviceConnection); serviceBound = false }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun showPacketDetail(dp: DisplayPacket) {
        val pkt = dp.pkt
        android.app.AlertDialog.Builder(this)
            .setTitle("[${dp.channel}] ${pkt.source} → ${pkt.destination}")
            .setMessage(buildString {
                appendLine("Channel:   ${dp.channel}")
                appendLine("Source:    ${pkt.source}")
                appendLine("Dest:      ${pkt.destination}")
                appendLine("Path:      ${pkt.path}")
                if (pkt.latitude  != null) appendLine("Lat:       ${"%.6f".format(pkt.latitude)}°")
                if (pkt.longitude != null) appendLine("Lon:       ${"%.6f".format(pkt.longitude)}°")
                appendLine("Payload:   ${pkt.payload.take(120)}")
                appendLine("Comment:   ${pkt.comment}")
                appendLine("Raw:       ${pkt.rawFrame.take(60)}…")
            })
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}

/**
 * Simple APRS station map — plots stations on a lat/lon grid.
 */
class AprsMapView @JvmOverloads constructor(
    context: android.content.Context,
    attrs: android.util.AttributeSet? = null
) : View(context, attrs) {

    private val stations = HashMap<String, AprsDecoder.AprsPacket>()

    private val bgPaint   = Paint().apply { color = 0xFF0A1520.toInt() }
    private val gridPaint = Paint().apply { color = 0x22FFFFFF; strokeWidth = 0.5f; style = Paint.Style.STROKE }
    private val dotPaint  = Paint().apply { color = 0xFF00FF44.toInt(); style = Paint.Style.FILL; isAntiAlias = true }
    private val textPaint = Paint().apply { color = 0xCC44FFAA.toInt(); textSize = 18f; isAntiAlias = true; typeface = android.graphics.Typeface.MONOSPACE }

    // Viewport bounds
    private var latMin = -90.0; private var latMax = 90.0
    private var lonMin = -180.0; private var lonMax = 180.0

    fun addPacket(pkt: AprsDecoder.AprsPacket) {
        if (pkt.latitude == null || pkt.longitude == null) return
        stations[pkt.source] = pkt
        // Auto-fit viewport around visible stations
        val lats = stations.values.mapNotNull { it.latitude }
        val lons = stations.values.mapNotNull { it.longitude }
        if (lats.isNotEmpty()) {
            val pad = maxOf((lats.max() - lats.min()) * 0.15, 0.5)
            latMin = lats.min() - pad; latMax = lats.max() + pad
        }
        if (lons.isNotEmpty()) {
            val pad = maxOf((lons.max() - lons.min()) * 0.15, 0.75)
            lonMin = lons.min() - pad; lonMax = lons.max() + pad
        }
        postInvalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Grid lines
        for (i in 0..4) {
            val y = h * i / 4f
            canvas.drawLine(0f, y, w, y, gridPaint)
            val x = w * i / 4f
            canvas.drawLine(x, 0f, x, h, gridPaint)
        }

        // Station dots
        for ((callsign, pkt) in stations) {
            val lat = pkt.latitude ?: continue
            val lon = pkt.longitude ?: continue
            val latRange = latMax - latMin
            val lonRange = lonMax - lonMin
            if (latRange <= 0 || lonRange <= 0) continue
            val x = ((lon - lonMin) / lonRange * w).toFloat().coerceIn(0f, w)
            val y = (h - (lat - latMin) / latRange * h).toFloat().coerceIn(0f, h)
            canvas.drawCircle(x, y, 5f, dotPaint)
            canvas.drawText(callsign.take(8), x + 6f, y - 4f, textPaint)
        }

        // Station count label
        textPaint.color = 0xAAFFFFFF.toInt()
        canvas.drawText("${stations.size} stations", 8f, h - 8f, textPaint)
        textPaint.color = 0xCC44FFAA.toInt()
    }
}
