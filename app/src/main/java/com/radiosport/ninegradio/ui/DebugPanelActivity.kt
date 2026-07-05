package com.radiosport.ninegradio.ui

import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.os.*
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.radiosport.ninegradio.debug.DebugBus
import com.radiosport.ninegradio.debug.DebugBus.StageStatus
import com.radiosport.ninegradio.usb.RtlSdrService
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Debug Panel — monitors every stage in the RTL-SDR → Audio/Waterfall/Spectrum chain.
 *
 * Key diagnostic sections:
 *  1. ⚡ FAULT CHECKLIST  — auto-analysis of "no audio" and "no signal display" root causes
 *  2. IQ → AUDIO CHAIN   — Source → Quadrature IF → Rational Resampler → Sink rates
 *  3. PIPELINE CHAIN      — per-stage status with live extras (AudioTrack state, view dims, etc.)
 *  4. LIVE DSP METRICS    — service-level stats + audio routing + display health
 *  5. DIAGNOSTIC LOG      — errors & warnings only (last 200)
 */
class DebugPanelActivity : AppCompatActivity() {

    companion object {
        private const val REFRESH_MS = 500L

        // Colour palette (GitHub dark theme family)
        private const val C_BG          = "#0D1117"
        private const val C_CARD        = "#161B22"
        private const val C_DIVIDER     = "#21262D"
        private const val C_TEXT_DIM    = "#6E7681"
        private const val C_TEXT_NORMAL = "#C9D1D9"
        private const val C_TEXT_BRIGHT = "#E6EDF3"
        private const val C_GREEN       = "#56D364"
        private const val C_GREEN_BG    = "#0D2818"
        private const val C_AMBER       = "#E3B341"
        private const val C_AMBER_BG    = "#2D2011"
        private const val C_RED         = "#F85149"
        private const val C_RED_BG      = "#2D1111"
        private const val C_BLUE        = "#388BFD"
        private const val C_BLUE_DIM    = "#1C2A42"
        private const val C_EXTRA_KEY   = "#79C0FF"
        private const val C_EXTRA_VAL   = "#8B949E"

        // GNU Radio Companion–style block header colours (per block category)
        private const val C_GRC_SRC     = "#82C4E8"  // RF/IO sources
        private const val C_GRC_FILTER  = "#A8D8B9"  // Filters / decimators
        private const val C_GRC_DEMOD   = "#FFD9A0"  // Demodulators
        private const val C_GRC_RESAMP  = "#D8B8F0"  // Resamplers
        private const val C_GRC_SINK    = "#FF9E9E"  // Audio sinks

        // GNU Radio Companion–style wire/port colours (per stream data type)
        private const val C_PORT_COMPLEX = "#3DA5D9" // complex<float32> streams
        private const val C_PORT_FLOAT   = "#FF8C42" // float32 streams
    }

    // ── Fault analysis ────────────────────────────────────────────────────────

    private enum class FaultSev { CRITICAL, ERROR, WARN, OK, INFO }

    private data class FaultItem(val sev: FaultSev, val category: String, val message: String)

    // ── Service binding ───────────────────────────────────────────────────────

    private var sdrService: RtlSdrService? = null
    private var bound = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            sdrService = (binder as RtlSdrService.LocalBinder).getService()
            bound = true
            // Immediately repaint with service-sourced data (tuner type, mode, etc.)
            // that updateMetrics() cannot show until the service binding is available.
            // This prevents a blank metrics section on first open of the debug page.
            refreshAll()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            sdrService = null; bound = false
        }
    }

    // ── View references ───────────────────────────────────────────────────────

    private lateinit var scrollView: ScrollView
    private lateinit var pipelineContainer: LinearLayout
    private lateinit var logTextView: TextView
    private lateinit var tvSummary: TextView
    private lateinit var btnCopyLog: Button
    private lateinit var btnExportLog: Button
    private lateinit var btnResetStats: Button
    private lateinit var tvRefreshTime: TextView
    private lateinit var tvMetricsContent: TextView
    private lateinit var faultContainer: LinearLayout
    private lateinit var tvFaultHeader: TextView
    private var faultExpanded: Boolean = false
    private lateinit var logContainer: LinearLayout
    private lateinit var tvLogHeader: TextView
    private var logExpanded: Boolean = false

    // IQ→Audio chain section (rendered as a GNU Radio Companion–style flowgraph)
    private lateinit var chainSection: LinearLayout
    private lateinit var tvChainStatus: TextView
    private lateinit var grcSource: GrcBlockViews
    private lateinit var grcDecim: GrcBlockViews
    private lateinit var grcDemod: GrcBlockViews
    private lateinit var grcResamp: GrcBlockViews
    private lateinit var grcSink: GrcBlockViews
    private lateinit var wireSrcDecim: TextView
    private lateinit var wireDecimDemod: TextView
    private lateinit var wireDemodResamp: TextView
    private lateinit var wireResampSink: TextView

    // IQ Stream (Bulk Transfer) performance section
    private lateinit var tvIqPerfHeader: TextView
    private lateinit var tvIqPerfMetrics: TextView

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val stageCardViews = mutableMapOf<Int, StageCardViews>()

    private data class StageCardViews(
        val card: LinearLayout,
        val statusDot: View,
        val tvName: TextView,
        val tvStatus: TextView,
        val tvDetail: TextView,
        val tvCounter: TextView,
        val tvRate: TextView,
        val tvLastEvent: TextView,
        val tvError: TextView,
        val errorRow: View,
        val extrasContainer: LinearLayout   // key-value rows injected at runtime
    )

    /**
     * View references for a single "GNU Radio Companion block" card in the
     * IQ → AUDIO CHAIN flowgraph. Each block mimics a GRC canvas block:
     * a coloured title bar (block category), a subtitle showing the
     * underlying GR block class, a live status badge, a list of parameter
     * rows (param: value, just like the GRC block properties dialog), and
     * an output-port line showing the stream type/rate flowing out of it.
     */
    private data class GrcBlockViews(
        val card: LinearLayout,
        val dot: View,
        val tvTitle: TextView,
        val tvStatus: TextView,
        val tvType: TextView,
        val paramsContainer: LinearLayout,
        val tvPortOut: TextView
    )

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildLayout())
        supportActionBar?.apply {
            title = "Debug Panel"
            setDisplayHomeAsUpEnabled(true)
        }
        bindService(Intent(this, RtlSdrService::class.java), serviceConnection, BIND_AUTO_CREATE)
        DebugBus.log("DebugPanel", "Debug panel opened")
        // startRefresh() is called in onResume() so the loop is properly
        // started/stopped across the full activity lifecycle.
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        // Restart the polling loop (stopped in onPause) and fire an immediate
        // refresh so the UI reflects current state the moment the page becomes
        // visible — covers first open, return from protocol switch, and any
        // other back-stack navigation to this screen.
        startRefresh()
        refreshAll()
    }

    override fun onPause() {
        super.onPause()
        // Stop the polling loop while the activity is not visible to avoid
        // unnecessary CPU/battery use when the user has navigated away.
        refreshHandler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        refreshHandler.removeCallbacksAndMessages(null)
        if (bound) { unbindService(serviceConnection); bound = false }
    }

    // ── Layout construction ───────────────────────────────────────────────────

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(C_BG))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Header summary bar
        tvSummary = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#8B949E"))
            setPadding(dp(12), dp(6), dp(12), dp(4))
            typeface = Typeface.MONOSPACE
        }
        root.addView(tvSummary)

        // Action button row
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        btnResetStats = makeButton("Reset Stats", "#30363D") {
            DebugBus.resetAll()
            sdrService?.dspEngine?.aprsDecoder?.resetCounters()
        }
        btnCopyLog    = makeButton("Copy Log",    "#21262D") { copyLogToClipboard() }
        btnExportLog  = makeButton("Export Log",  "#21262D") { exportLogToFile() }
        tvRefreshTime = TextView(this).apply {
            textSize = 10f
            setTextColor(Color.parseColor("#484F58"))
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, 0, dp(8), 0)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        btnRow.addView(btnResetStats)
        btnRow.addView(btnCopyLog)
        btnRow.addView(btnExportLog)
        btnRow.addView(tvRefreshTime)
        root.addView(btnRow)
        root.addView(makeDivider())

        // ── Scrollable content ────────────────────────────────────────────────
        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val scrollContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 1. FAULT CHECKLIST
        scrollContent.addView(buildFaultPanel())
        scrollContent.addView(makeDivider())

        // 2. IQ → AUDIO CHAIN  (Source → Quadrature → Rational Resampler → Sink)
        scrollContent.addView(makeSectionHeader("IQ → AUDIO CHAIN"))
        chainSection = buildChainSection()
        scrollContent.addView(chainSection)
        scrollContent.addView(makeDivider())

        // 2b. IQ STREAM (BULK TRANSFER) PERFORMANCE
        scrollContent.addView(makeSectionHeader("IQ STREAM — BULK TRANSFER PERFORMANCE"))
        scrollContent.addView(buildIqPerfSection())
        scrollContent.addView(makeDivider())

        // 3. PIPELINE CHAIN
        scrollContent.addView(makeSectionHeader("PIPELINE CHAIN  (RTL-SDR → DSP → Audio/Spectrum)"))
        pipelineContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        buildPipelineCards()
        scrollContent.addView(pipelineContainer)

        // 4. LIVE DSP METRICS + AUDIO + DISPLAY HEALTH
        scrollContent.addView(makeDivider())
        scrollContent.addView(makeSectionHeader("LIVE DSP METRICS"))
        tvMetricsContent = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor(C_TEXT_NORMAL))
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        scrollContent.addView(tvMetricsContent)

        // 5. DIAGNOSTIC LOG  (errors & warnings only) — collapsed by default so a
        // handful of stale/transient entries (e.g. an app-start layout blip) don't
        // dominate the screen; the header always shows a live count/severity summary
        // even while collapsed, and the user can tap to expand for full detail.
        scrollContent.addView(makeDivider())
        tvLogHeader = TextView(this).apply {
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor(C_BLUE))
            setPadding(dp(12), dp(8), dp(12), dp(4))
            setBackgroundColor(Color.parseColor(C_BG))
            text = "DIAGNOSTIC LOG  — errors & warnings  (last 200)  ▼"
            isClickable = true
            isFocusable = true
            setOnClickListener {
                logExpanded = !logExpanded
                logContainer.visibility = if (logExpanded) android.view.View.VISIBLE else android.view.View.GONE
                val current = tvLogHeader.text.toString()
                val stripped = current.trimEnd().trimEnd('▲', '▼').trimEnd()
                tvLogHeader.text = "$stripped  ${if (logExpanded) "▲" else "▼"}"
            }
        }
        scrollContent.addView(tvLogHeader)
        logContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE  // collapsed by default
        }
        logTextView = TextView(this).apply {
            textSize = 10.5f
            setTextColor(Color.parseColor(C_TEXT_NORMAL))
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(6), dp(12), dp(12))
            setTextIsSelectable(true)
        }
        logContainer.addView(logTextView)
        scrollContent.addView(logContainer)

        scrollView.addView(scrollContent)
        root.addView(scrollView)
        return root
    }

    // ── Chain panel (GNU Radio Companion–style flowgraph) ──────────────────────

    /**
     * Builds the IQ→Audio chain section as a vertical stack of "GRC blocks",
     * mirroring how GNU Radio Companion renders a flowgraph: each processing
     * unit is a card with a coloured title bar (block category), a subtitle
     * naming the underlying GR block, a live OK/WARN/ERROR/IDLE status badge,
     * a list of parameter rows (just like the GRC block properties dialog),
     * and an output-port line showing the stream type + rate flowing out.
     * Thin "wires" between blocks show the live stream type/rate on that
     * connection, coloured to match the GRC convention (blue = complex,
     * orange = float).
     *
     *   ┌─ RTL-SDR Source ───────┐
     *   │ rtlsdr_source (RF/IO)  │
     *   │ samp_rate: ...         │
     *   │ out0 ▶ complex @ ...   │
     *   └────────────────────────┘
     *            │ complex64 @ ... ▼
     *   ┌─ Complex Decimator ────┐
     *   ...
     */
    private fun buildChainSection(): LinearLayout {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
            setBackgroundColor(Color.parseColor(C_BG))
        }

        // Status line at top
        tvChainStatus = TextView(this).apply {
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor(C_TEXT_DIM))
            setPadding(dp(4), 0, 0, dp(6))
            text = "Waiting for first IQ block…"
        }
        section.addView(tvChainStatus)

        grcSource = makeGrcBlock("RTL-SDR Source", "rtlsdr_source  •  RF / IO", C_GRC_SRC)
        section.addView(grcSource.card)
        wireSrcDecim = makeWire()
        section.addView(wireSrcDecim)

        grcDecim = makeGrcBlock("Complex Decimator", "polyphase_fir_filter_ccf  •  Filters / Decimators", C_GRC_FILTER)
        section.addView(grcDecim.card)
        wireDecimDemod = makeWire()
        section.addView(wireDecimDemod)

        grcDemod = makeGrcBlock("Demodulator", "nbfm_rx / wfm_rcv / am_demod  •  Demodulators", C_GRC_DEMOD)
        section.addView(grcDemod.card)
        wireDemodResamp = makeWire()
        section.addView(wireDemodResamp)

        grcResamp = makeGrcBlock("Rational Resampler", "rational_resampler_fff (PolyphaseResampler)  •  Resamplers", C_GRC_RESAMP)
        section.addView(grcResamp.card)
        wireResampSink = makeWire()
        section.addView(wireResampSink)

        grcSink = makeGrcBlock("Audio Sink", "audio_sink (AudioTrack)  •  Audio", C_GRC_SINK)
        section.addView(grcSink.card)

        return section
    }

    /** Builds one "GRC block" card: coloured title bar, subtitle, status badge, params, output port. */
    private fun makeGrcBlock(title: String, grcType: String, accentColor: String): GrcBlockViews {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(C_CARD))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, dp(2), 0, dp(2)) }
        }

        // Title bar — GRC blocks use a solid colour header per category
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor(accentColor))
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).also { it.setMargins(0, 0, dp(6), 0) }
            setBackgroundColor(Color.parseColor("#30363D"))
        }
        val tvTitle = TextView(this).apply {
            text = title
            textSize = 12.5f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#0D1117"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvStatus = TextView(this).apply {
            textSize = 9.5f
            setPadding(dp(5), dp(1), dp(5), dp(1))
            text = "IDLE"
            setTextColor(Color.parseColor("#0D1117"))
        }
        header.addView(dot); header.addView(tvTitle); header.addView(tvStatus)
        card.addView(header)

        // Subtitle — underlying GR block class / category, like the grey text
        // under a GRC block's title.
        val tvType = TextView(this).apply {
            text = grcType
            textSize = 9.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor(C_TEXT_DIM))
            setPadding(dp(8), dp(3), dp(8), dp(2))
        }
        card.addView(tvType)

        // Parameter rows — refreshed every cycle, mirrors the GRC block
        // "Properties" key/value list.
        val paramsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(2), dp(8), dp(4))
        }
        card.addView(paramsContainer)

        // Output port line — shows the stream type/rate handed to the next block
        val tvPortOut = TextView(this).apply {
            textSize = 9.5f
            typeface = Typeface.MONOSPACE
            setPadding(dp(8), dp(2), dp(8), dp(6))
            setTextColor(Color.parseColor(C_TEXT_DIM))
        }
        card.addView(tvPortOut)

        return GrcBlockViews(card, dot, tvTitle, tvStatus, tvType, paramsContainer, tvPortOut)
    }

    /** Connector "wire" between two GRC blocks, labelled with live stream type/rate. */
    private fun makeWire(): TextView = TextView(this).apply {
        text = "│"
        textSize = 9.5f
        typeface = Typeface.MONOSPACE
        setTextColor(Color.parseColor(C_TEXT_DIM))
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(4), dp(1), dp(4), dp(1))
    }

    /** Applies a live OK/WARN/ERROR/IDLE status badge + dot colour to a GRC block. */
    private fun setGrcStatus(b: GrcBlockViews, status: StageStatus) {
        val (dotColor, label, fg, bg) = when (status) {
            StageStatus.OK    -> listOf(C_GREEN, "OK",    C_GREEN, C_GREEN_BG)
            StageStatus.WARN  -> listOf(C_AMBER, "STALE", C_AMBER, C_AMBER_BG)
            StageStatus.ERROR -> listOf(C_RED,   "ERROR", C_RED,   C_RED_BG)
            StageStatus.IDLE  -> listOf("#30363D", "IDLE", "#8B949E", "#21262D")
        }
        b.dot.setBackgroundColor(Color.parseColor(dotColor))
        b.tvStatus.text = label
        b.tvStatus.setTextColor(Color.parseColor(fg))
        b.tvStatus.setBackgroundColor(Color.parseColor(bg))
    }

    /** Rebuilds (or updates in place) the parameter rows of a GRC block. */
    private fun setGrcParams(container: LinearLayout, params: List<Pair<String, String>>) {
        val needRebuild = container.childCount != params.size
        if (needRebuild) container.removeAllViews()

        for ((i, kv) in params.withIndex()) {
            val (key, value) = kv
            val row: LinearLayout
            if (needRebuild) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(1), 0, dp(1))
                }
                val tvKey = TextView(this).apply {
                    textSize = 9.5f; typeface = Typeface.MONOSPACE
                    setTextColor(Color.parseColor(C_EXTRA_KEY))
                    minWidth = dp(110)
                }
                val tvVal = TextView(this).apply {
                    textSize = 9.5f; typeface = Typeface.MONOSPACE
                    setTextColor(Color.parseColor(C_TEXT_NORMAL))
                }
                row.addView(tvKey); row.addView(tvVal)
                container.addView(row)
            } else {
                row = container.getChildAt(i) as LinearLayout
            }
            (row.getChildAt(0) as TextView).text = key
            (row.getChildAt(1) as TextView).text = value
        }
    }

    /**
     * Refreshes every "GRC block" card from the live DebugBus chain snapshot,
     * stage snapshots, and IQ-stream performance counters — called every
     * REFRESH_MS (500 ms) from refreshAll() so the flowgraph tracks the
     * pipeline in real time, just like GRC's "Variable" / probe widgets do
     * while a flowgraph is running.
     */
    private fun refreshChainSection(snapshots: List<DebugBus.StageSnapshot>) {
        val chain  = DebugBus.getChain()
        val iqPerf = DebugBus.getIqPerf()

        val srcSnap    = snapshots[DebugBus.STAGE_IQ_STREAM]
        val decimSnap  = snapshots[DebugBus.STAGE_DSP_PROCESS]
        val demodSnap  = snapshots[DebugBus.STAGE_DEMODULATOR]
        val resampSnap = snapshots[DebugBus.STAGE_AUDIO_RESAMPLE]
        val sinkSnap   = snapshots[DebugBus.STAGE_AUDIO_TRACK]
        val srcExtras  = srcSnap.extras

        // The source block always reflects live IQ-stream health, even
        // before the decimation chain has been (re)built.
        //
        // Pool health: POOL_SIZE must always exceed FLOW_DEPTH (replay + extraBufCap)
        // so the producer never laps the consumer and overwrites an in-flight buffer.
        // If POOL_SIZE <= FLOW_DEPTH the cell is highlighted in amber as a warning.
        val poolSize    = srcExtras[DebugBus.EXTRA_IQ_POOL_SIZE]?.toIntOrNull()
        val poolIdx     = srcExtras[DebugBus.EXTRA_IQ_POOL_IDX]?.toIntOrNull()
        val flowDepth   = srcExtras[DebugBus.EXTRA_IQ_FLOW_DEPTH]?.toIntOrNull()
        val inFlight    = srcExtras[DebugBus.EXTRA_IQ_IN_FLIGHT]
        val flowDrops   = srcExtras[DebugBus.EXTRA_IQ_FLOW_DROPS]?.toLongOrNull() ?: 0L
        val poolSafe    = poolSize != null && flowDepth != null && poolSize > flowDepth
        val poolLabel   = when {
            poolSize == null -> "—"
            poolSafe        -> "$poolSize  ✓  (flow depth=$flowDepth, margin=${poolSize - flowDepth})"
            else            -> "$poolSize  ⚠ ≤ flow depth=$flowDepth — OVERWRITE RISK"
        }
        val poolIdxLabel = if (poolIdx != null && poolSize != null)
            "$poolIdx / ${poolSize - 1}" else "—"
        // flow_drops > 0 means processIqBlock() is too slow to consume IQ at the
        // USB rate — the SharedFlow DROP_OLDEST policy silently discarded blocks,
        // causing gaps in the DSP pipeline and audible skipping.
        val flowDropsLabel = when {
            flowDrops == 0L -> "0  ✓"
            flowDrops < 10L -> "$flowDrops  ⚠ DSP overload — skips likely"
            else            -> "$flowDrops  ✗ SEVERE — DSP overload"
        }
        setGrcStatus(grcSource, srcSnap.status)
        setGrcParams(grcSource.paramsContainer, listOf(
            "samp_rate"    to (chain?.let { "${it.sourceHz} Hz  (%.0f kS/s)".format(it.sourceHz / 1_000.0) } ?: "—"),
            "throughput"   to "%.1f blk/s".format(srcSnap.ratePerSec),
            "usb_rating"   to iqPerf.rating,
            "drop_rate"    to "%.2f%%  (drops=${iqPerf.dropCount}, short=${iqPerf.shortReadCount})".format(iqPerf.dropRatePct),
            "flow_drops"   to flowDropsLabel,
            "pool_size"    to poolLabel,
            "pool_idx"     to poolIdxLabel,
            "subscribers"  to (inFlight ?: "—")
        ))

        if (chain == null) {
            tvChainStatus.text = "No IQ blocks processed yet — connect a device"
            tvChainStatus.setTextColor(Color.parseColor(C_TEXT_DIM))

            grcSource.tvPortOut.text = "out0 ▶ complex<float32> @ —"
            grcSource.tvPortOut.setTextColor(Color.parseColor(C_TEXT_DIM))

            setGrcStatus(grcDecim, decimSnap.status)
            setGrcParams(grcDecim.paramsContainer, listOf(
                "decimation"      to "—", "input_rate" to "—", "output_rate" to "—",
                "protocol_filter" to "—", "type" to "Polyphase FIR (windowed-sinc)"
            ))
            grcDecim.tvPortOut.text = "out0 ▶ complex<float32> @ —"
            grcDecim.tvPortOut.setTextColor(Color.parseColor(C_TEXT_DIM))

            setGrcStatus(grcDemod, demodSnap.status)
            setGrcParams(grcDemod.paramsContainer, listOf(
                "mode" to "—", "input_rate" to "—", "wfm_if_rate" to "—",
                "detail" to demodSnap.detail.ifBlank { "—" }
            ))
            grcDemod.tvPortOut.text = "out0 ▶ float32 (audio) @ —"
            grcDemod.tvPortOut.setTextColor(Color.parseColor(C_TEXT_DIM))

            setGrcStatus(grcResamp, resampSnap.status)
            setGrcParams(grcResamp.paramsContainer, listOf(
                "interpolation" to "—", "decimation" to "—", "ratio" to "—",
                "input_rate" to "—", "output_rate" to "—", "mode" to "—"
            ))
            grcResamp.tvPortOut.text = "out0 ▶ float32 (audio) @ —"
            grcResamp.tvPortOut.setTextColor(Color.parseColor(C_TEXT_DIM))

            val sinkExtras = sinkSnap.extras
            setGrcStatus(grcSink, sinkSnap.status)
            setGrcParams(grcSink.paramsContainer, listOf(
                "samp_rate"     to "—",
                "AT_INIT"       to (sinkExtras[DebugBus.EXTRA_AT_INIT] ?: "—"),
                "AT_PLAY"       to (sinkExtras[DebugBus.EXTRA_AT_PLAY] ?: "—"),
                "AT_LAST_WRITE" to (sinkExtras[DebugBus.EXTRA_AT_LAST_WRITE] ?: "—"),
                "AT_BUF_SIZE"   to (sinkExtras[DebugBus.EXTRA_AT_BUF_SIZE] ?: "—"),
                "write_fails"   to (sinkExtras[DebugBus.EXTRA_AT_WRITE_FAIL] ?: "0")
            ))
            grcSink.tvPortOut.text = "in0 ◀ float32 (audio) @ —"
            grcSink.tvPortOut.setTextColor(Color.parseColor(C_TEXT_DIM))

            for (w in listOf(wireSrcDecim, wireDecimDemod, wireDemodResamp, wireResampSink)) {
                w.text = "│"
                w.setTextColor(Color.parseColor(C_TEXT_DIM))
            }
            return
        }

        // ── Live rate labels ────────────────────────────────────────────────
        val srcRate  = "%.0f kS/s".format(chain.sourceHz / 1_000.0)
        val ifRate   = "%.1f kHz".format(chain.ifHz / 1_000.0)
        val sinkRate = "%.1f kHz".format(chain.sinkHz / 1_000.0)

        // Status summary line
        val age = System.currentTimeMillis() - chain.timestampMs
        val ageStr = if (age < 5000) "live" else DebugBus.formatElapsed(chain.timestampMs)
        tvChainStatus.text = "${chain.demodMode}  •  ${chain.summary()}  ($ageStr)"
        tvChainStatus.setTextColor(Color.parseColor(if (chain.direct) C_GREEN else C_TEXT_DIM))

        // 1. SOURCE → output port + wire to decimator
        grcSource.tvPortOut.text = "out0 ▶ complex<float32> @ $srcRate"
        grcSource.tvPortOut.setTextColor(Color.parseColor(C_PORT_COMPLEX))

        wireSrcDecim.text = "│  complex64 @ $srcRate  ▼"
        wireSrcDecim.setTextColor(Color.parseColor(C_PORT_COMPLEX))

        // 2. COMPLEX DECIMATOR
        setGrcStatus(grcDecim, decimSnap.status)
        val pfHz   = chain.protocolFilterHz
        val pfAuto = chain.protocolFilterAuto
        val pfLabel = when {
            pfAuto                -> "Auto (mode default: %.1f kHz)".format(pfHz / 1_000.0)
            pfHz >= 1_000         -> "%.1f kHz  [user]".format(pfHz / 1_000.0)
            else                  -> "$pfHz Hz  [user]"
        }
        // For WFM, compute the filter passband (≈ ifHz / 2) to verify it covers ≥100 kHz.
        // For WFM the recommended target is 200 kS/s → ±100 kHz passband; values below
        // ±75 kHz cannot faithfully pass the stereo subcarrier at 38 kHz + RDS at 57 kHz
        // and will produce "watery" / muffled audio.
        val isWfmMode = chain.demodMode.startsWith("WFM")
        val passbandHz = chain.ifHz / 2
        val passbandLabel = if (isWfmMode) {
            val warning = if (passbandHz < 75_000) "  ⚠ TOO NARROW for WFM stereo/RDS" else ""
            "±%.0f kHz (needs ≥±75 kHz for WFM)$warning".format(passbandHz / 1_000.0)
        } else {
            "±%.1f kHz".format(passbandHz / 1_000.0)
        }
        // Show rational ratio (decimP:decimQ) when available (WFM path), else integer ÷N.
        val decimLabel = when {
            chain.decimP > 0 && chain.decimQ > 0 && chain.decimQ != 1 ->
                "${chain.decimP}:${chain.decimQ}  (÷${chain.decimFactor} approx)"
            else -> "÷${chain.decimFactor}"
        }
        setGrcParams(grcDecim.paramsContainer, listOf(
            "decimation"       to decimLabel,
            "input_rate"       to "${chain.sourceHz} Hz  ($srcRate)",
            "output_rate"      to "${chain.ifHz} Hz  ($ifRate)",
            "passband"         to passbandLabel,
            "protocol_filter"  to pfLabel,
            "type"             to "Polyphase FIR (windowed-sinc, Blackman-Harris)",
            "detail"           to decimSnap.detail.ifBlank { "—" }
        ))
        grcDecim.tvPortOut.text = "out0 ▶ complex<float32> @ $ifRate"
        grcDecim.tvPortOut.setTextColor(Color.parseColor(C_PORT_COMPLEX))

        wireDecimDemod.text = "│  complex64 @ $ifRate  ▼"
        wireDecimDemod.setTextColor(Color.parseColor(C_PORT_COMPLEX))

        // 3. DEMODULATOR
        val wfmIfRate = if (chain.wfmIfHz > 0) "%.1f kHz".format(chain.wfmIfHz / 1_000.0) else "n/a (narrow mode)"
        setGrcStatus(grcDemod, demodSnap.status)
        val demodExtras = demodSnap.extras
        // APRS decode funnel — 7 stages from audio-in to decoded packet.
        // Each counter proves a different layer; zero at any stage points to the bug layer:
        //   samples=0  → decoder never called (mode/feed() wiring problem)
        //   flags=0    → AFSK demodulator not finding 0x7E patterns (bit-clock/slicer)
        //   size_rej>> → noise flags, no real frames between them
        //   decode=0   → all too short (minimum 144-bit gate)
        //   fcs_fail>> → frames found but bit errors in AFSK layer (clock/AGC/SNR)
        //   ax25_fail> → CRC passes but frame structure wrong (shouldn't happen often)
        //   decoded>0  → 
        val isAprsMode = chain.demodMode == "APRS"
        val aprsFunnelRows: List<Pair<String, String>> = if (isAprsMode) {
            val samples  = demodExtras[DebugBus.EXTRA_APRS_SAMPLES_IN]  ?: "0"
            val flags    = demodExtras[DebugBus.EXTRA_APRS_FLAG_SYNCS]  ?: "0"
            val sizeRej  = demodExtras[DebugBus.EXTRA_APRS_SIZE_REJ]    ?: "0"
            val decode   = demodExtras[DebugBus.EXTRA_APRS_RCVD]        ?: "0"
            val fcsFail  = demodExtras[DebugBus.EXTRA_APRS_FCS_FAIL]    ?: "0"
            val ax25Fail = demodExtras[DebugBus.EXTRA_APRS_AX25_FAIL]   ?: "0"
            val decoded  = demodExtras[DebugBus.EXTRA_APRS_DECODED]     ?: "0"
            listOf(
                "┌ audio_samples_in" to samples,
                "├ flag_syncs (0x7E)" to flags,
                "├ size_rejected"     to sizeRej,
                "├ decode_attempts"   to decode,
                "├ fcs_failed"        to fcsFail,
                "├ ax25_failed"       to ax25Fail,
                "└ ✓ decoded"         to decoded
            )
        } else emptyList()

        // Digital Voice decode funnel — DMR / P25 / NXDN / D-STAR / YSF / M17 / dPMR.
        // Same idea as the APRS funnel: each row is a layer of the pipeline,
        // and a zero/stuck value pinpoints exactly which layer is broken
        // rather than requiring a guess. See DebugBus's EXTRA_DV_* doc
        // comments for what each row means and how to read it.
        val DV_MODES = setOf("DMR", "P25", "NXDN", "D-STAR", "YSF", "M17", "dPMR", "Dig")
        val isDigitalVoiceMode = chain.demodMode in DV_MODES
        val dvFunnelRows: List<Pair<String, String>> = if (isDigitalVoiceMode) {
            val samples      = demodExtras[DebugBus.EXTRA_DV_SAMPLES_IN]        ?: "0"
            val feedCalls    = demodExtras[DebugBus.EXTRA_DV_FEED_CALLS]        ?: "0"
            val feedRate     = demodExtras[DebugBus.EXTRA_DV_FEED_RATE]         ?: "—"
            val feedBlockSz  = demodExtras[DebugBus.EXTRA_DV_FEED_BLOCK_SIZE]   ?: "—"
            val symbols      = demodExtras[DebugBus.EXTRA_DV_SYMBOLS]           ?: "0"
            val syncBest     = demodExtras[DebugBus.EXTRA_DV_SYNC_BEST]         ?: "—"
            val syncHits     = demodExtras[DebugBus.EXTRA_DV_SYNC_HITS]         ?: "0"
            val nativeAvail  = demodExtras[DebugBus.EXTRA_DV_NATIVE_AVAIL]      ?: "false"
            val vocoderReady = demodExtras[DebugBus.EXTRA_DV_VOCODER_READY]     ?: "false"
            val framesTotal  = demodExtras[DebugBus.EXTRA_DV_FRAMES_TOTAL]      ?: "0"
            val framesEmpty  = demodExtras[DebugBus.EXTRA_DV_FRAMES_PCM_EMPTY]  ?: "0"
            val framesSilent = demodExtras[DebugBus.EXTRA_DV_FRAMES_PCM_SILENT] ?: "0"
            val framesAudible= demodExtras[DebugBus.EXTRA_DV_FRAMES_PCM_AUDIBLE]?: "0"
            val lastRms      = demodExtras[DebugBus.EXTRA_DV_LAST_PCM_RMS]      ?: "0"
            val agcEnv       = demodExtras[DebugBus.EXTRA_DV_AGC_ENV]           ?: "—"
            val rssi         = demodExtras[DebugBus.EXTRA_DV_RSSI]              ?: "—"
            val activeSlot   = demodExtras[DebugBus.EXTRA_DV_ACTIVE_SLOT]
            val vocFlags     = demodExtras[DebugBus.EXTRA_DV_VOCODER_FLAGS]
            val frameErrors  = demodExtras[DebugBus.EXTRA_DV_FRAME_ERRORS]
            val baseRows = listOf(
                "┌ audio_samples_in"  to samples,
                "├ feed_calls"        to feedCalls,
                "├ feed_rate"         to feedRate,
                "├ feed_block_size"   to feedBlockSz,
                "├ agc_env"           to agcEnv,
                "├ rssi"              to rssi,
                "├ symbols_decoded"   to symbols,
                "├ sync_best (err/threshold)" to syncBest,
                "├ sync_hits"         to syncHits,
                "├ native_lib_loaded" to nativeAvail,
                "├ ambe_plugin_ready" to vocoderReady,
                "├ frames_to_vocoder" to framesTotal,
                "├ frames_pcm_empty"  to framesEmpty,
                "├ frames_pcm_silent" to "$framesSilent  (rms<40, last=$lastRms)",
                "${if (activeSlot != null || vocFlags != null) "├" else "└"} frames_pcm_AUDIBLE" to framesAudible
            )
            // DMR-only vocoder diagnostics: which TDMA slot is locked, and
            // cumulative TONE/ERASURE/REPEAT/MUTE hits per slot. If TONE
            // climbs while an audible beat is present, ambe3600x2400.c's
            // tone-frame synthesis is the cause; if it stays at 0, the beat
            // is coming from somewhere other than the vocoder.
            val vocoderRows = if (activeSlot != null || vocFlags != null) listOf(
                "├ dmr_active_slot"   to (activeSlot ?: "—"),
                "├ vocoder_flags (T=tone E=erasure R=repeat M=mute)" to (vocFlags ?: "—"),
                "└ frame_errors (C0=header/Tot=total, corrected)" to (frameErrors ?: "—")
            ) else emptyList()
            if (vocoderRows.isEmpty()) baseRows else baseRows + vocoderRows
        } else emptyList()
        setGrcParams(grcDemod.paramsContainer, listOf(
            "mode"        to chain.demodMode,
            "input_rate"  to ifRate,
            "wfm_if_rate" to wfmIfRate,
            "detail"      to demodSnap.detail.ifBlank { "—" }
        ) + aprsFunnelRows + dvFunnelRows)
        grcDemod.tvPortOut.text = "out0 ▶ float32 (audio) @ $ifRate"
        grcDemod.tvPortOut.setTextColor(Color.parseColor(C_PORT_FLOAT))

        wireDemodResamp.text = "│  float32 @ $ifRate  ▼"
        wireDemodResamp.setTextColor(Color.parseColor(C_PORT_FLOAT))

        // 4. RATIONAL RESAMPLER
        val resampRatio = if (chain.direct) "1/1 (bypassed)" else "${chain.resampleP} / ${chain.resampleQ}"
        val resampMode  = if (chain.direct) "Direct passthrough" else "Polyphase rational resampler"
        setGrcStatus(grcResamp, resampSnap.status)
        setGrcParams(grcResamp.paramsContainer, listOf(
            "interpolation" to "${chain.resampleP}",
            "decimation"    to "${chain.resampleQ}",
            "ratio"         to resampRatio,
            "input_rate"    to ifRate,
            "output_rate"   to sinkRate,
            "mode"          to resampMode
        ))
        grcResamp.tvPortOut.text = "out0 ▶ float32 (audio) @ $sinkRate"
        grcResamp.tvPortOut.setTextColor(Color.parseColor(C_PORT_FLOAT))

        wireResampSink.text = "│  float32 @ $sinkRate  ▼"
        wireResampSink.setTextColor(Color.parseColor(C_PORT_FLOAT))

        // 5. AUDIO SINK
        val sinkExtras = sinkSnap.extras
        setGrcStatus(grcSink, sinkSnap.status)
        setGrcParams(grcSink.paramsContainer, listOf(
            "samp_rate"     to "${chain.sinkHz} Hz  ($sinkRate)",
            "AT_INIT"       to (sinkExtras[DebugBus.EXTRA_AT_INIT] ?: "—"),
            "AT_PLAY"       to (sinkExtras[DebugBus.EXTRA_AT_PLAY] ?: "—"),
            "AT_LAST_WRITE" to (sinkExtras[DebugBus.EXTRA_AT_LAST_WRITE] ?: "—"),
            "AT_BUF_SIZE"   to (sinkExtras[DebugBus.EXTRA_AT_BUF_SIZE] ?: "—"),
            "write_fails"   to (sinkExtras[DebugBus.EXTRA_AT_WRITE_FAIL] ?: "0")
        ))
        grcSink.tvPortOut.text = "in0 ◀ float32 (audio) @ $sinkRate"
        grcSink.tvPortOut.setTextColor(Color.parseColor(C_PORT_FLOAT))
    }

    // ── IQ Stream (Bulk Transfer) performance panel ────────────────────────────

    /**
     * Builds the "IQ STREAM — BULK TRANSFER PERFORMANCE" panel: an overall
     * EXCELLENT/GOOD/FAIR/POOR rating badge, followed by the raw numbers
     * (average/worst transfer time, jitter, USB read errors / "drops",
     * short reads, and overall drop rate) used to derive it.
     */
    private fun buildIqPerfSection(): LinearLayout {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(C_BG))
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }

        tvIqPerfHeader = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor(C_TEXT_DIM))
            setBackgroundColor(Color.parseColor("#21262D"))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            text = "RATING: —  (waiting for IQ data…)"
        }
        section.addView(tvIqPerfHeader)

        tvIqPerfMetrics = TextView(this).apply {
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor(C_TEXT_NORMAL))
            setPadding(dp(8), dp(6), dp(8), dp(2))
        }
        section.addView(tvIqPerfMetrics)

        return section
    }

    /** Refreshes the IQ bulk-transfer performance rating panel from DebugBus.getIqPerf(). */
    private fun refreshIqPerfSection() {
        val perf = DebugBus.getIqPerf()

        val (ratingColor, ratingBg, ratingHint) = when (perf.rating) {
            "EXCELLENT" -> Triple(C_GREEN, C_GREEN_BG, "steady cadence, no errors")
            "GOOD"      -> Triple(C_GREEN, C_GREEN_BG, "minor jitter, no drops")
            "FAIR"      -> Triple(C_AMBER, C_AMBER_BG, "short reads or rising jitter — watch for audio gaps")
            "POOR"      -> Triple(C_RED,   C_RED_BG,   "USB stalls / high jitter — expect audio gaps & waterfall freezes")
            else        -> Triple(C_TEXT_DIM, "#21262D", "waiting for IQ data…")
        }
        tvIqPerfHeader.text = "RATING: ${perf.rating}  —  $ratingHint"
        tvIqPerfHeader.setTextColor(Color.parseColor(ratingColor))
        tvIqPerfHeader.setBackgroundColor(Color.parseColor(ratingBg))

        val sb = StringBuilder()
        sb.appendLine("  Xfer time (avg/max) : %.1f ms / %d ms".format(perf.avgXferMs, perf.maxXferMs))
        sb.appendLine("  Jitter (stddev)     : %.1f ms".format(perf.jitterMs))
        sb.appendLine("  USB read errors     : ${perf.dropCount}  (drops)")
        sb.appendLine("  Short reads         : ${perf.shortReadCount}  (partial buffers)")
        sb.appendLine("  Drop rate           : %.2f%%  (of ${perf.totalReads} reads)".format(perf.dropRatePct))
        tvIqPerfMetrics.text = sb.toString()
    }

    private fun buildFaultPanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(C_BG))
        }
        tvFaultHeader = TextView(this).apply {
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor(C_BLUE))
            setPadding(dp(12), dp(8), dp(12), dp(4))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                faultExpanded = !faultExpanded
                faultContainer.visibility = if (faultExpanded) android.view.View.VISIBLE else android.view.View.GONE
                // Update the expand/collapse indicator in the header (re-render header text with new arrow)
                val current = tvFaultHeader.text.toString()
                val stripped = current.trimEnd().trimEnd('▲', '▼').trimEnd()
                tvFaultHeader.text = "$stripped  ${if (faultExpanded) "▲" else "▼"}"
            }
        }
        panel.addView(tvFaultHeader)
        faultContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), dp(4))
            visibility = android.view.View.GONE  // collapsed by default
        }
        panel.addView(faultContainer)
        return panel
    }

    private fun refreshFaultPanel(snapshots: List<DebugBus.StageSnapshot>) {
        val faults = buildFaultList(snapshots)
        val critCount = faults.count { it.sev == FaultSev.CRITICAL }
        val errCount  = faults.count { it.sev == FaultSev.ERROR }
        val warnCount = faults.count { it.sev == FaultSev.WARN }

        // Pick the single worst fault to display in the collapsed header
        val worstFault = faults.firstOrNull { it.sev == FaultSev.CRITICAL }
            ?: faults.firstOrNull { it.sev == FaultSev.ERROR }
            ?: faults.firstOrNull { it.sev == FaultSev.WARN }

        val arrow = if (faultExpanded) "▲" else "▼"
        tvFaultHeader.text = when {
            worstFault != null ->
                "⚡ [${worstFault.category}] ${worstFault.message}  $arrow"
            else ->
                "✓ No faults detected  $arrow"
        }
        tvFaultHeader.setTextColor(Color.parseColor(
            when {
                critCount > 0 || errCount > 0 -> C_RED
                warnCount > 0                 -> C_AMBER
                else                          -> C_GREEN
            }
        ))

        faultContainer.removeAllViews()
        for (fault in faults) {
            faultContainer.addView(buildFaultRow(fault))
        }
        // Keep container visibility in sync with expanded state
        faultContainer.visibility = if (faultExpanded) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun buildFaultRow(fault: FaultItem): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(3), dp(4), dp(3))
        }
        val (sevColor, sevBg, sevLabel) = when (fault.sev) {
            FaultSev.CRITICAL -> Triple(C_RED,    C_RED_BG,   "CRITICAL")
            FaultSev.ERROR    -> Triple(C_RED,    C_RED_BG,   "ERROR   ")
            FaultSev.WARN     -> Triple(C_AMBER,  C_AMBER_BG, "WARN    ")
            FaultSev.OK       -> Triple(C_GREEN,  C_GREEN_BG, "OK      ")
            FaultSev.INFO     -> Triple(C_BLUE,   C_BLUE_DIM, "INFO    ")
        }
        val tvSev = TextView(this).apply {
            text = sevLabel
            textSize = 9.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor(sevColor))
            setBackgroundColor(Color.parseColor(sevBg))
            setPadding(dp(4), dp(1), dp(4), dp(1))
        }
        val tvCat = TextView(this).apply {
            text = " [${fault.category}] "
            textSize = 9.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor(C_TEXT_DIM))
        }
        val tvMsg = TextView(this).apply {
            text = fault.message
            textSize = 9.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor(C_TEXT_NORMAL))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(tvSev); row.addView(tvCat); row.addView(tvMsg)
        return row
    }

    /** Auto-analyses all stage data and global metrics, emits actionable fault items. */
    private fun buildFaultList(snapshots: List<DebugBus.StageSnapshot>): List<FaultItem> {
        val faults = mutableListOf<FaultItem>()
        val globals = DebugBus.getGlobals()

        val iqSnap    = snapshots[DebugBus.STAGE_IQ_STREAM]
        val audioSnap = snapshots[DebugBus.STAGE_AUDIO_TRACK]
        val specSnap  = snapshots[DebugBus.STAGE_SPECTRUM_VIEW]
        val wfSnap    = snapshots[DebugBus.STAGE_WATERFALL_VIEW]
        val dspSnap   = snapshots[DebugBus.STAGE_DSP_PROCESS]
        val demodSnap = snapshots[DebugBus.STAGE_DEMODULATOR]

        // ── AUDIO DIAGNOSIS ──────────────────────────────────────────────────
        val atInit  = audioSnap.extras[DebugBus.EXTRA_AT_INIT]  ?: ""
        val atPlay  = audioSnap.extras[DebugBus.EXTRA_AT_PLAY]  ?: ""
        val atFails = audioSnap.extras[DebugBus.EXTRA_AT_WRITE_FAIL]?.toIntOrNull() ?: 0

        when {
            atInit.contains("FAIL") || atInit.contains("UNINITIALIZED") ->
                faults += FaultItem(FaultSev.CRITICAL, "AUDIO",
                    "AudioTrack init FAILED: $atInit — no audio possible")
            atPlay.startsWith("⚠") ->
                faults += FaultItem(FaultSev.ERROR, "AUDIO",
                    "AudioTrack not playing: $atPlay")
            atPlay == "STOPPED" && audioSnap.status != StageStatus.IDLE ->
                faults += FaultItem(FaultSev.ERROR, "AUDIO",
                    "AudioTrack is STOPPED — call play() not called or track released")
            atPlay == "PAUSED" ->
                faults += FaultItem(FaultSev.WARN, "AUDIO",
                    "AudioTrack PAUSED — possibly transient audio focus loss")
        }
        if (atFails > 0) {
            val lastWrite = audioSnap.extras[DebugBus.EXTRA_AT_LAST_WRITE] ?: ""
            faults += FaultItem(FaultSev.ERROR, "AUDIO",
                "AudioTrack write() failed $atFails time(s): $lastWrite")
        }

        val sysVol = globals[DebugBus.KEY_SYS_MEDIA_VOL]
        if (sysVol != null && sysVol.startsWith("0/")) {
            faults += FaultItem(FaultSev.WARN, "AUDIO",
                "System media volume = 0 — device muted: $sysVol")
        }
        val audioFocus = globals[DebugBus.KEY_AUDIO_FOCUS]
        if (!audioFocus.isNullOrEmpty() && audioFocus != "NONE" &&
            !audioFocus.contains("GAIN")) {
            faults += FaultItem(FaultSev.WARN, "AUDIO",
                "Audio focus not held: $audioFocus — another app may have stolen audio")
        }
        if (audioFocus == "NONE") {
            faults += FaultItem(FaultSev.WARN, "AUDIO",
                "App has not requested audio focus — audio may be routed away silently")
        }
        val audioOutput = globals[DebugBus.KEY_AUDIO_OUTPUT]
        if (audioOutput != null) {
            faults += FaultItem(FaultSev.INFO, "AUDIO", "Output routing: $audioOutput")
        }

        // IQ → DSP → Demod chain sanity check
        if (iqSnap.status == StageStatus.OK && demodSnap.status == StageStatus.OK
            && audioSnap.status == StageStatus.IDLE) {
            faults += FaultItem(FaultSev.ERROR, "AUDIO",
                "IQ + Demod OK but AudioTrack IDLE — DspEngine.start() may not have been called")
        }
        if (audioSnap.status == StageStatus.OK && atFails == 0 && atPlay == "PLAYING"
            && faults.none { it.category == "AUDIO" && it.sev != FaultSev.INFO }) {
            faults += FaultItem(FaultSev.OK, "AUDIO",
                "AudioTrack PLAYING, no write failures detected")
        }

        // ── SIGNAL DISPLAY DIAGNOSIS ─────────────────────────────────────────
        val specViewWH   = specSnap.extras[DebugBus.EXTRA_SPEC_VIEW_WH]   ?: ""
        val specDbRange  = specSnap.extras[DebugBus.EXTRA_SPEC_DB_RANGE]  ?: ""
        val specDataRange = specSnap.extras[DebugBus.EXTRA_SPEC_DATA_RANGE] ?: ""
        val wfBitmapWH   = wfSnap.extras[DebugBus.EXTRA_WF_BITMAP_WH]    ?: ""

        if (specViewWH.contains("⚠")) {
            faults += FaultItem(FaultSev.CRITICAL, "DISPLAY",
                "SpectrumView zero dimensions: $specViewWH — view not laid out")
        }
        if (wfBitmapWH.contains("⚠")) {
            faults += FaultItem(FaultSev.CRITICAL, "DISPLAY",
                "WaterfallView bitmap not created: $wfBitmapWH")
        }
        // NOTE: data sitting below the display floor is the *expected* state
        // when there is no RF traffic — the noise floor simply falls under the
        // current dB window until a signal appears (or auto-range catches up).
        // That is not a fault, so it is intentionally NOT surfaced as an
        // ERROR/WARN item here; it stays visible only as plain info below via
        // the "SpectrumView: ..." INFO line so it doesn't read as a problem.
        // Data pinned above the ceiling for a sustained period genuinely can
        // indicate a clipping/overload condition worth a mention, but only
        // once the signal chain is actually live (IQ stream flowing) —
        // otherwise it's just as likely to be a transient at startup.
        if (specDataRange.contains("above display ceiling") && iqSnap.status == StageStatus.OK) {
            faults += FaultItem(FaultSev.INFO, "DISPLAY",
                "FFT data above display ceiling: $specDataRange (display: $specDbRange)")
        }
        if (iqSnap.status == StageStatus.OK && specSnap.status == StageStatus.IDLE) {
            faults += FaultItem(FaultSev.ERROR, "DISPLAY",
                "IQ stream OK but SpectrumView IDLE — spectrumFlow not subscribed in MainActivity")
        }
        if (specSnap.status == StageStatus.WARN) {
            faults += FaultItem(FaultSev.WARN, "DISPLAY",
                "SpectrumView STALE — updateSpectrum() not called for >5s")
        }
        // Dimensions info
        if (specViewWH.isNotEmpty() && !specViewWH.contains("⚠")) {
            faults += FaultItem(FaultSev.INFO, "DISPLAY",
                "SpectrumView: $specViewWH  dB window: $specDbRange  data: $specDataRange")
        }
        if (wfBitmapWH.isNotEmpty() && !wfBitmapWH.contains("⚠")) {
            faults += FaultItem(FaultSev.INFO, "DISPLAY", "WaterfallView bitmap: $wfBitmapWH")
        }
        if (specSnap.status == StageStatus.OK && wfSnap.status == StageStatus.OK
            && faults.none { it.category == "DISPLAY" &&
                it.sev in listOf(FaultSev.CRITICAL, FaultSev.ERROR, FaultSev.WARN) }) {
            faults += FaultItem(FaultSev.OK, "DISPLAY",
                "SpectrumView + WaterfallView both rendering")
        }

        // ── USB / IQ STREAM BULK-TRANSFER PERFORMANCE ─────────────────────────
        val iqPerf = DebugBus.getIqPerf()
        when (iqPerf.rating) {
            "POOR" ->
                faults += FaultItem(FaultSev.ERROR, "USB",
                    "IQ bulk-transfer rating POOR — jitter %.1fms, drops=%d, short=%d (%.1f%%)"
                        .format(iqPerf.jitterMs, iqPerf.dropCount, iqPerf.shortReadCount, iqPerf.dropRatePct))
            "FAIR" ->
                faults += FaultItem(FaultSev.WARN, "USB",
                    "IQ bulk-transfer rating FAIR — jitter %.1fms, short reads=%d (%.1f%%)"
                        .format(iqPerf.jitterMs, iqPerf.shortReadCount, iqPerf.dropRatePct))
            "EXCELLENT", "GOOD" ->
                faults += FaultItem(FaultSev.OK, "USB",
                    "IQ bulk-transfer rating ${iqPerf.rating} — jitter %.1fms, drops=%d, %d reads"
                        .format(iqPerf.jitterMs, iqPerf.dropCount, iqPerf.totalReads))
        }
        if (iqPerf.dropCount > 0L) {
            faults += FaultItem(FaultSev.WARN, "USB",
                "${iqPerf.dropCount} bulkTransfer() error/timeout return(s) since last reset — possible USB cable/hub/power issue")
        }

        // ── WFM AUDIO QUALITY ─────────────────────────────────────────────────
        // Detect the "watery / jittery" WFM audio root causes identified in the
        // analysis: insufficient IF passband width (CIC or narrow FIR), or the
        // discriminator using division-by-power instead of atan2.
        val chain = DebugBus.getChain()
        val isWfm = chain?.demodMode?.startsWith("WFM") == true
        if (isWfm && chain != null) {
            val passbandHz = chain.ifHz / 2
            when {
                passbandHz < 75_000 ->
                    faults += FaultItem(FaultSev.ERROR, "WFM",
                        "IF passband ±${passbandHz / 1_000} kHz too narrow for WFM — " +
                        "stereo subcarrier (38 kHz) and/or RDS (57 kHz) are outside the passband. " +
                        "Need ≥±75 kHz (IF rate ≥150 kHz). Current IF: ${chain.ifHz} Hz. " +
                        "Increase device sample rate or reduce decimation.")
                passbandHz < 100_000 ->
                    faults += FaultItem(FaultSev.WARN, "WFM",
                        "IF passband ±${passbandHz / 1_000} kHz marginal for WFM stereo — " +
                        "RDS (57 kHz) may be attenuated. Recommended: ±100 kHz (IF ≥200 kHz).")
                else ->
                    faults += FaultItem(FaultSev.OK, "WFM",
                        "IF passband ±${passbandHz / 1_000} kHz — adequate for WFM stereo + RDS " +
                        "(stereo@38 kHz, RDS@57 kHz both inside passband).")
            }
            // FM discriminator method: atan2-based phase differentiation (PolyphaseResampler
            // path) eliminates the division-by-(I²+Q²) near-null spike that caused crackle.
            faults += FaultItem(FaultSev.INFO, "WFM",
                "FM discriminator: atan2 phase-diff (bounded ±π, no null-spike). " +
                "Pre-decimator: Polyphase FIR (Blackman-Harris windowed-sinc), fc = interpFactor/(2×decimFactor). " +
                "Chain: ${chain.sourceHz} Hz → IF ${chain.ifHz} Hz (÷${chain.decimFactor}) → sink ${chain.sinkHz} Hz.")
        }

        return faults
    }

    // ── Pipeline cards ────────────────────────────────────────────────────────

    private fun buildPipelineCards() {
        for (i in 0 until DebugBus.NUM_STAGES) {
            pipelineContainer.addView(buildStageCard(i))
            if (i < DebugBus.NUM_STAGES - 1) pipelineContainer.addView(makeArrow())
        }
    }

    private fun buildStageCard(stageIndex: Int): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(C_CARD))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, dp(2), 0, dp(2)) }
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        // Row 1: dot + name + status badge
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10))
                .also { it.setMargins(0, 0, dp(8), 0) }
            setBackgroundColor(Color.parseColor("#30363D"))
        }
        val tvName = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor(C_TEXT_BRIGHT))
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvStatus = TextView(this).apply {
            textSize = 10f; setPadding(dp(5), dp(2), dp(5), dp(2))
            text = "IDLE"
            setTextColor(Color.parseColor("#8B949E"))
            setBackgroundColor(Color.parseColor("#21262D"))
        }
        row1.addView(dot); row1.addView(tvName); row1.addView(tvStatus)
        card.addView(row1)

        // Row 2: detail
        val tvDetail = TextView(this).apply {
            textSize = 11f; setTextColor(Color.parseColor("#8B949E"))
            typeface = Typeface.MONOSPACE; setPadding(dp(18), dp(2), 0, 0)
        }
        card.addView(tvDetail)

        // Row 3: counters / rate / last event
        val row3 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(18), dp(3), 0, 0)
        }
        val tvCounter = makeMonoSmall("cnt: 0")
        val tvRate    = makeMonoSmall("  0.0/s")
        val tvLast    = makeMonoSmall("  never")
        row3.addView(tvCounter); row3.addView(tvRate); row3.addView(tvLast)
        card.addView(row3)

        // Row 4: error
        val errorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(18), dp(3), 0, dp(2))
            visibility = View.GONE
        }
        val tvError = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor(C_RED))
            typeface = Typeface.MONOSPACE
        }
        errorRow.addView(tvError)
        card.addView(errorRow)

        // Row 5: extras container (key-value pairs, filled at refresh time)
        val extrasContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(2), 0, dp(2))
        }
        card.addView(extrasContainer)

        stageCardViews[stageIndex] = StageCardViews(
            card = card,
            statusDot = dot, tvName = tvName, tvStatus = tvStatus,
            tvDetail = tvDetail, tvCounter = tvCounter, tvRate = tvRate,
            tvLastEvent = tvLast, tvError = tvError, errorRow = errorRow,
            extrasContainer = extrasContainer
        )
        return card
    }

    // ── Refresh cycle ─────────────────────────────────────────────────────────

    private fun startRefresh() {
        refreshHandler.postDelayed(object : Runnable {
            override fun run() {
                refreshAll()
                refreshHandler.postDelayed(this, REFRESH_MS)
            }
        }, REFRESH_MS)
    }

    private fun refreshAll() {
        // ── 1. Update global metrics from AudioManager (no service needed) ───
        updateAudioManagerGlobals()

        val snapshots = DebugBus.snapshot()
        var okCount = 0; var warnCount = 0; var errCount = 0; var idleCount = 0

        for ((i, snap) in snapshots.withIndex()) {
            val views = stageCardViews[i] ?: continue
            when (snap.status) {
                StageStatus.OK    -> { okCount++;   applyOk(views, snap) }
                StageStatus.WARN  -> { warnCount++; applyWarn(views, snap) }
                StageStatus.ERROR -> { errCount++;  applyError(views, snap) }
                StageStatus.IDLE  -> { idleCount++; applyIdle(views, snap) }
            }
            refreshExtras(views, snap)
        }

        // ── 2. Summary bar ───────────────────────────────────────────────────
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        tvSummary.text = "✓ $okCount OK  ⚠ $warnCount WARN  ✗ $errCount ERR  — $idleCount IDLE   $ts"

        // ── 3. Fault checklist ───────────────────────────────────────────────
        refreshFaultPanel(snapshots)

        // ── 4. IQ → Audio chain ──────────────────────────────────────────────
        refreshChainSection(snapshots)

        // ── 4b. IQ Stream (Bulk Transfer) performance rating ─────────────────
        refreshIqPerfSection()

        // ── 5. Metrics ───────────────────────────────────────────────────────
        updateMetrics()

        // ── 6. Diagnostic log  (errors & warnings only) ───────────────────────
        //
        // "View has zero dimensions" entries are expected for the first layout
        // pass or two after a view is attached (measure/layout hasn't run yet) --
        // that's a one-off app-start blip, not a fault. WaterfallView/SpectrumView
        // now recover their stage status back to OK as soon as they get a real
        // size and render successfully (see WaterfallView.onDraw / SpectrumView.
        // onDraw), so if the *current* stage status is no longer ERROR, an old
        // zero-dimension log line is stale/resolved and would only mislead the
        // user into thinking there's an ongoing problem. Such lines are hidden
        // from this view (they remain in DebugBus.getLog() for export). A
        // zero-dimension entry whose stage is still showing ERROR right now is a
        // persistent condition and is always shown.
        val stageStatusNow: (String) -> DebugBus.StageStatus? = { tag ->
            snapshots.firstOrNull { it.name == tag }?.status
        }
        val logLines = DebugBus.getErrorLog().filter { line ->
            if ("zero dimensions" !in line) return@filter true
            val tag = Regex("""\[(?:\d{2}:\d{2}:\d{2}\.\d{3})] \[([^]]+)]""").find(line)?.groupValues?.get(1)
            val current = tag?.let(stageStatusNow)
            // Keep it only if that stage is currently still reporting an error --
            // otherwise it already recovered and this line is a resolved blip.
            current == DebugBus.StageStatus.ERROR
        }

        val errCountLog  = logLines.count { "[ERROR]" in it }
        val warnCountLog = logLines.count { "[WARN]"  in it }
        val logArrow = if (logExpanded) "▲" else "▼"
        tvLogHeader.text = if (logLines.isEmpty()) {
            "DIAGNOSTIC LOG  — no active errors or warnings  $logArrow"
        } else {
            "DIAGNOSTIC LOG  — $errCountLog error(s), $warnCountLog warning(s)  (last 200)  $logArrow"
        }

        if (logLines.isEmpty()) {
            logTextView.text = "  No errors or warnings recorded."
            logTextView.setTextColor(Color.parseColor(C_TEXT_DIM))
        } else {
            val sb = SpannableStringBuilder()
            for (line in logLines.asReversed()) {
                val color = when {
                    line.contains("[ERROR]") -> Color.parseColor(C_RED)
                    line.contains("[WARN]")  -> Color.parseColor(C_AMBER)
                    else                     -> Color.parseColor(C_TEXT_DIM)
                }
                val start = sb.length
                sb.append(line).append('\n')
                sb.setSpan(ForegroundColorSpan(color), start, sb.length - 1,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            logTextView.text = sb
        }

        tvRefreshTime.text = "↻ ${REFRESH_MS}ms"
    }

    /** Push AudioManager state into DebugBus global metrics so fault analysis can use it. */
    private fun updateAudioManagerGlobals() {
        try {
            val am = getSystemService(AudioManager::class.java) ?: return
            val vol    = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            DebugBus.setGlobal(DebugBus.KEY_SYS_MEDIA_VOL, "$vol/$maxVol")

            val output = when {
                am.isBluetoothA2dpOn    -> "Bluetooth A2DP"
                am.isWiredHeadsetOn     -> "Wired Headset"
                am.isSpeakerphoneOn     -> "Speakerphone"
                else                    -> "Speaker (earpiece)"
            }
            DebugBus.setGlobal(DebugBus.KEY_AUDIO_OUTPUT, output)
            DebugBus.setGlobal(DebugBus.KEY_BT_A2DP_CONNECTED,
                if (am.isBluetoothA2dpOn) "true" else "false")
        } catch (_: Exception) { /* security exception on some ROMs */ }
    }

    private fun updateMetrics() {
        val svc = sdrService
        val dsp = svc?.dspEngine
        val dev = svc?.device      // non-null only for USB source
        val tcpSrc = svc?.source as? com.radiosport.ninegradio.source.RtlTcpSource
        val globals = DebugBus.getGlobals()

        val sb = StringBuilder()

        // ── Service state ─────────────────────────────────────────────────────
        if (dsp != null) {
            val s = dsp.statsFlow.value
            sb.appendLine("  Connection  : ${svc.connectionState.value::class.simpleName}")
            sb.appendLine("  Demod mode  : ${s.demodMode}")
            sb.appendLine("  Sample rate : ${s.sampleRate / 1000} kS/s")
            sb.appendLine("  Signal      : ${"%.1f".format(s.signalDb)} dBFS")
            sb.appendLine("  Squelch     : ${if (s.squelchOpen) "OPEN ✓" else "CLOSED ✗"} (gate)")
            sb.appendLine("  Audio vol   : ${"%.2f".format(s.audioVolume)}")
            sb.appendLine("  IQ record   : ${if (s.isRecordingIq) "ACTIVE" else "off"}")
            sb.appendLine("  Audio rec   : ${if (s.isRecordingAudio) "ACTIVE" else "off"}")
            sb.appendLine("  FFT size    : ${dsp.fftEngine.fftSize}")
            sb.appendLine("  Buffer drops: ${s.bufferDrops}")
            dev?.statusFlow?.value?.let { st ->
                sb.appendLine("  Tuner       : ${st.tunerType}")
                sb.appendLine("  Center freq : ${"%.4f".format(st.centerFreqHz / 1e6)} MHz")
                sb.appendLine("  Gain mode   : ${if (st.gainMode == 0) "AGC" else "Manual ${st.gainDb10 / 10.0} dB"}")
                sb.appendLine("  Bias tee    : ${if (st.biasTee) "ON" else "off"}")
                sb.appendLine("  Direct samp : ${when(st.directSampling){0->"off";1->"I-branch";2->"Q-branch";else->"?"}}")
                sb.appendLine("  HW overload : ${if (st.overload) "YES ⚠" else "no"}")
            }

            // ── TCP source info (shown when USB device not present) ────────────
            tcpSrc?.let { tcp ->
                val st = tcp.statusFlow.value
                sb.appendLine("  Source      : rtl_tcp @ ${tcp.host}:${tcp.port}")
                sb.appendLine("  Center freq : ${"%.4f".format(st.centerFreqHz / 1e6)} MHz")
                sb.appendLine("  Gain mode   : ${if (st.gainMode == 0) "AGC" else "Manual ${st.gainDb10 / 10.0} dB"}")
                sb.appendLine("  Bias tee    : ${if (st.biasTee) "ON" else "off"}")
                sb.appendLine("  Direct samp : ${when(st.directSampling){0->"off";1->"I-branch";2->"Q-branch";else->"?"}}")
            }

            // ── Protocol Filter (IF bandwidth) ────────────────────────────────
            sb.appendLine()
            sb.appendLine("  ─ PROTOCOL FILTER ─")
            val chain = DebugBus.getChain()
            if (chain != null) {
                val pfHz   = chain.protocolFilterHz
                val pfAuto = chain.protocolFilterAuto
                val pfLabel = when {
                    pfAuto            -> "Auto — mode default (${"%.1f".format(pfHz / 1_000.0)} kHz)"
                    pfHz >= 1_000     -> "${"%.1f".format(pfHz / 1_000.0)} kHz  [user set]"
                    else              -> "$pfHz Hz  [user set]"
                }
                val ifHz   = chain.ifHz
                val srcHz  = chain.sourceHz
                sb.appendLine("  PF_SETTING  : $pfLabel")
                sb.appendLine("  IF_RATE_OUT : ${"%.1f".format(ifHz / 1_000.0)} kHz  (DSP decimator output)")
                sb.appendLine("  DECIM_FACTOR: ÷${chain.decimFactor}  ($srcHz Hz → $ifHz Hz)")
                sb.appendLine("  PF_SPECTRUM : ${globals[com.radiosport.ninegradio.debug.DebugBus.KEY_PROTOCOL_FILTER] ?: "—"}")
            } else {
                val pfGlobal = globals[com.radiosport.ninegradio.debug.DebugBus.KEY_PROTOCOL_FILTER]
                sb.appendLine("  PF_SETTING  : ${pfGlobal ?: "— (no IQ chain yet)"}")
                sb.appendLine("  IF_RATE_OUT : — (device not streaming)")
                sb.appendLine("  DECIM_FACTOR: —")
            }
        } else {
            sb.appendLine("  No device connected.")
        }

        // ── Audio health ──────────────────────────────────────────────────────
        sb.appendLine()
        sb.appendLine("  ─ AUDIO HEALTH ─")
        val atInit  = DebugBus.getExtras(DebugBus.STAGE_AUDIO_TRACK)[DebugBus.EXTRA_AT_INIT]  ?: "—"
        val atPlay  = DebugBus.getExtras(DebugBus.STAGE_AUDIO_TRACK)[DebugBus.EXTRA_AT_PLAY]  ?: "—"
        val atWrite = DebugBus.getExtras(DebugBus.STAGE_AUDIO_TRACK)[DebugBus.EXTRA_AT_LAST_WRITE] ?: "—"
        val atFails = DebugBus.getExtras(DebugBus.STAGE_AUDIO_TRACK)[DebugBus.EXTRA_AT_WRITE_FAIL] ?: "0"
        val atBuf   = DebugBus.getExtras(DebugBus.STAGE_AUDIO_TRACK)[DebugBus.EXTRA_AT_BUF_SIZE]   ?: "—"
        sb.appendLine("  AT_INIT      : $atInit")
        sb.appendLine("  AT_PLAY      : $atPlay")
        sb.appendLine("  AT_LAST_WRITE: $atWrite")
        sb.appendLine("  AT_WRITE_FAIL: $atFails")
        sb.appendLine("  AT_BUF_SIZE  : $atBuf")
        val sysVol    = globals[DebugBus.KEY_SYS_MEDIA_VOL]    ?: "—"
        val audioOut  = globals[DebugBus.KEY_AUDIO_OUTPUT]      ?: "—"
        val audioFocus = globals[DebugBus.KEY_AUDIO_FOCUS]      ?: "NONE (not requested)"
        val pfExport  = globals[DebugBus.KEY_PROTOCOL_FILTER]   ?: "—"
        sb.appendLine("  SYS_MEDIA_VOL: $sysVol")
        sb.appendLine("  AUDIO_OUTPUT : $audioOut")
        sb.appendLine("  AUDIO_FOCUS  : $audioFocus")
        sb.appendLine("  PROTOCOL_FILT: $pfExport")

        // ── Display health ────────────────────────────────────────────────────
        sb.appendLine()
        sb.appendLine("  ─ DISPLAY HEALTH ─")
        val specWH    = DebugBus.getExtras(DebugBus.STAGE_SPECTRUM_VIEW)[DebugBus.EXTRA_SPEC_VIEW_WH]    ?: "—"
        val specDb    = DebugBus.getExtras(DebugBus.STAGE_SPECTRUM_VIEW)[DebugBus.EXTRA_SPEC_DB_RANGE]   ?: "—"
        val specData  = DebugBus.getExtras(DebugBus.STAGE_SPECTRUM_VIEW)[DebugBus.EXTRA_SPEC_DATA_RANGE] ?: "—"
        val wfBitmap  = DebugBus.getExtras(DebugBus.STAGE_WATERFALL_VIEW)[DebugBus.EXTRA_WF_BITMAP_WH]  ?: "—"
        val wfViewWH  = DebugBus.getExtras(DebugBus.STAGE_WATERFALL_VIEW)[DebugBus.EXTRA_WF_VIEW_WH]    ?: "—"
        sb.appendLine("  SPEC_VIEW_WH : $specWH")
        sb.appendLine("  SPEC_DB_RANGE: $specDb")
        sb.appendLine("  SPEC_DATA    : $specData")
        sb.appendLine("  WF_VIEW_WH   : $wfViewWH")
        sb.appendLine("  WF_BITMAP_WH : $wfBitmap")

        tvMetricsContent.text = sb.toString()
    }

    // ── Extras display ────────────────────────────────────────────────────────

    private fun refreshExtras(views: StageCardViews, snap: DebugBus.StageSnapshot) {
        val container = views.extrasContainer
        val extras = snap.extras
        if (extras.isEmpty()) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        // Rebuild rows only when the key set changes to avoid flicker
        val needRebuild = container.childCount != extras.size
        if (needRebuild) container.removeAllViews()

        var rowIndex = 0
        for ((key, value) in extras) {
            val valueColor = when {
                value.startsWith("⚠") || value.contains("FAIL") ||
                value.contains("ERROR") || value.contains("NOT_CREATED") ||
                value.contains("UNINITIALIZED") || value.contains("×0") ||
                value.contains("0×")            -> Color.parseColor(C_AMBER)
                value.contains("PLAYING") || value.contains("OK") -> Color.parseColor(C_GREEN)
                else                            -> Color.parseColor(C_EXTRA_VAL)
            }

            val row: LinearLayout
            if (needRebuild) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(1), 0, dp(1))
                }
                val tvKey = TextView(this).apply {
                    textSize = 9.5f; typeface = Typeface.MONOSPACE
                    setTextColor(Color.parseColor(C_EXTRA_KEY))
                    minWidth = dp(90)
                }
                val tvVal = TextView(this).apply {
                    textSize = 9.5f; typeface = Typeface.MONOSPACE
                }
                row.addView(tvKey); row.addView(tvVal)
                container.addView(row)
            } else {
                row = container.getChildAt(rowIndex) as? LinearLayout ?: continue
            }

            (row.getChildAt(0) as? TextView)?.text = key
            (row.getChildAt(1) as? TextView)?.apply {
                text = value
                setTextColor(valueColor)
            }
            rowIndex++
        }
    }

    // ── Stage card styling ────────────────────────────────────────────────────

    private fun applyOk(v: StageCardViews, s: DebugBus.StageSnapshot) {
        v.card.setBackgroundColor(Color.parseColor(C_CARD))
        v.statusDot.setBackgroundColor(Color.parseColor(C_GREEN))
        v.tvName.text = s.name
        v.tvStatus.text = "OK"; v.tvStatus.setTextColor(Color.parseColor(C_GREEN))
        v.tvStatus.setBackgroundColor(Color.parseColor(C_GREEN_BG))
        v.tvDetail.text = s.detail.ifBlank { "—" }
        v.tvCounter.text = "cnt: ${s.counter}"
        v.tvRate.text    = "  ${"%.1f".format(s.ratePerSec)}/s"
        v.tvLastEvent.text = "  ${DebugBus.formatElapsed(s.lastEventMs)}"
        v.errorRow.visibility = View.GONE
    }

    private fun applyWarn(v: StageCardViews, s: DebugBus.StageSnapshot) {
        v.card.setBackgroundColor(Color.parseColor(C_CARD))
        v.statusDot.setBackgroundColor(Color.parseColor(C_AMBER))
        v.tvName.text = s.name
        v.tvStatus.text = "STALE"; v.tvStatus.setTextColor(Color.parseColor(C_AMBER))
        v.tvStatus.setBackgroundColor(Color.parseColor(C_AMBER_BG))
        v.tvDetail.text = s.detail.ifBlank { "No events for >5s" }
        v.tvCounter.text = "cnt: ${s.counter}"
        v.tvRate.text    = "  ${"%.1f".format(s.ratePerSec)}/s"
        v.tvLastEvent.text = "  ${DebugBus.formatElapsed(s.lastEventMs)}"
        v.errorRow.visibility = View.GONE
    }

    private fun applyError(v: StageCardViews, s: DebugBus.StageSnapshot) {
        v.card.setBackgroundColor(Color.parseColor("#1A0D0D"))
        v.statusDot.setBackgroundColor(Color.parseColor(C_RED))
        v.tvName.text = s.name
        v.tvStatus.text = "ERROR"; v.tvStatus.setTextColor(Color.parseColor(C_RED))
        v.tvStatus.setBackgroundColor(Color.parseColor(C_RED_BG))
        v.tvDetail.text = s.detail.ifBlank { "—" }
        v.tvCounter.text = "cnt: ${s.counter}"
        v.tvRate.text    = "  ${"%.1f".format(s.ratePerSec)}/s"
        v.tvLastEvent.text = "  ${DebugBus.formatElapsed(s.lastEventMs)}"
        if (s.errorMsg.isNotBlank()) {
            v.tvError.text = "⚡ ${s.errorMsg}"
            v.errorRow.visibility = View.VISIBLE
        } else { v.errorRow.visibility = View.GONE }
    }

    private fun applyIdle(v: StageCardViews, s: DebugBus.StageSnapshot) {
        v.card.setBackgroundColor(Color.parseColor(C_CARD))
        v.statusDot.setBackgroundColor(Color.parseColor("#30363D"))
        v.tvName.text = s.name
        v.tvStatus.text = "IDLE"; v.tvStatus.setTextColor(Color.parseColor("#484F58"))
        v.tvStatus.setBackgroundColor(Color.parseColor("#21262D"))
        v.tvDetail.text = "—"
        v.tvCounter.text = "cnt: 0"
        v.tvRate.text    = "  0.0/s"
        v.tvLastEvent.text = "  never"
        v.errorRow.visibility = View.GONE
    }

    // ── Export / copy ─────────────────────────────────────────────────────────

    private fun copyLogToClipboard() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("9GRadio Debug Log", buildExportText()))
        Toast.makeText(this, "Debug log copied", Toast.LENGTH_SHORT).show()
    }

    private fun exportLogToFile() {
        try {
            val dir = File(getExternalFilesDir(null), "Debug")
            dir.mkdirs()
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "debug_$ts.txt")
            file.writeText(buildExportText())
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share Debug Log"))
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildExportText(): String {
        val sb = StringBuilder()
        sb.appendLine("9GRadio Debug Report — ${Date()}")
        sb.appendLine("=".repeat(60))
        sb.appendLine()
        sb.appendLine("PIPELINE STAGES:")
        for (snap in DebugBus.snapshot()) {
            sb.appendLine("  ${snap.name.padEnd(20)} [${snap.status}]  ${snap.detail}")
            sb.appendLine("     cnt=${snap.counter}  rate=${"%.1f".format(snap.ratePerSec)}/s  last=${DebugBus.formatElapsed(snap.lastEventMs)}")
            if (snap.errorMsg.isNotBlank()) sb.appendLine("     ERROR: ${snap.errorMsg}")
            for ((k, v) in snap.extras) sb.appendLine("     $k: $v")
        }
        sb.appendLine()
        sb.appendLine("IQ STREAM — BULK TRANSFER PERFORMANCE:")
        val iqPerf = DebugBus.getIqPerf()
        sb.appendLine("  Rating              : ${iqPerf.rating}")
        sb.appendLine("  Xfer time (avg/max) : %.1f ms / %d ms".format(iqPerf.avgXferMs, iqPerf.maxXferMs))
        sb.appendLine("  Jitter (stddev)     : %.1f ms".format(iqPerf.jitterMs))
        sb.appendLine("  USB read errors     : ${iqPerf.dropCount}")
        sb.appendLine("  Short reads         : ${iqPerf.shortReadCount}")
        sb.appendLine("  Drop rate           : %.2f%% (of ${iqPerf.totalReads} reads)".format(iqPerf.dropRatePct))
        sb.appendLine()
        sb.appendLine("GLOBAL METRICS:")
        for ((k, v) in DebugBus.getGlobals()) sb.appendLine("  $k: $v")
        sb.appendLine()
        sb.appendLine("LIVE METRICS:")
        val dsp = sdrService?.dspEngine?.statsFlow?.value
        if (dsp != null) {
            sb.appendLine("  demod=${dsp.demodMode} rate=${dsp.sampleRate} signal=${"%.1f".format(dsp.signalDb)}dBFS squelch=${if(dsp.squelchOpen) "OPEN" else "CLOSED"}")
        }
        sb.appendLine()
        sb.appendLine("DIAGNOSTIC LOG:")
        for (line in DebugBus.getLog()) sb.appendLine(line)
        return sb.toString()
    }

    // ── View helpers ──────────────────────────────────────────────────────────

    private fun makeMonoSmall(text: String) = TextView(this).apply {
        this.text = text; textSize = 10.5f
        typeface = Typeface.MONOSPACE
        setTextColor(Color.parseColor(C_TEXT_DIM))
    }

    private fun makeSectionHeader(title: String) = TextView(this).apply {
        text = title; textSize = 10f
        typeface = Typeface.MONOSPACE
        setTextColor(Color.parseColor(C_BLUE))
        setPadding(dp(12), dp(8), dp(12), dp(4))
        setBackgroundColor(Color.parseColor(C_BG))
    }

    private fun makeDivider() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        setBackgroundColor(Color.parseColor(C_DIVIDER))
    }

    private fun makeArrow() = TextView(this).apply {
        text = "     ▼"; textSize = 14f
        setTextColor(Color.parseColor("#30363D"))
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun makeButton(label: String, bgHex: String, onClick: () -> Unit) =
        Button(this).apply {
            text = label; textSize = 11f
            setTextColor(Color.parseColor(C_TEXT_NORMAL))
            setBackgroundColor(Color.parseColor(bgHex))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(dp(4), 0, dp(4), 0) }
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener { onClick() }
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
