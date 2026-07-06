package com.radiosport.ninegradio.ui

import com.radiosport.ninegradio.debug.DebugBus
import com.radiosport.ninegradio.dsp.DemodMode
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.*

/**
 * Real-time FFT spectrum display with:
 * ─── Core ─────────────────────────────────────────────────────────────────────
 *  • Selectable color themes (Classic, Futuristic, Amber, Grayscale, Purple,
 *    Solar, Neon, Ice, Midnight, Sakura)
 *  • Gradient-filled area under the spectrum trace (configurable opacity)
 *  • Major + minor dB grid with frequency labels
 *  • Zoom / pan via pinch and drag; double-tap resets
 *  • Peak-hold trace with configurable decay
 *  • Click-to-tune callback
 *  • Bookmark frequency markers
 *  • Squelch threshold line
 *  • Demodulated channel bandwidth highlight
 *
 * ─── Advanced ─────────────────────────────────────────────────────────────────
 *  • Live crosshair cursor: shows exact frequency + dBFS while finger is on screen
 *  • Noise floor line: estimated noise floor (15th percentile of visible bins)
 *    drawn as a subtle dashed line for instant noise-floor visibility
 *  • Peak annotations: top-3 local spectral maxima labelled with frequency and dBFS
 *  • Minor grid lines at half-step intervals (5 dB) for precise level reading
 *  • Auto dB range: dynamically scale the display window to the incoming data
 *  • Configurable fill opacity
 *  • Long-press: locks a persistent reference marker at that frequency
 */
class SpectrumView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object {
        private const val GRID_DB_STEP       = 10f
        private const val MINOR_DB_STEP      = 5f
        private const val AXIS_H             = 24f
        private const val LABEL_RIGHT_MARGIN = 64f

        // See WaterfallView.ZERO_DIM_GRACE_MS — same rationale: a 0×0 size for
        // the first frame or two after attach is expected, not a fault.
        private const val ZERO_DIM_GRACE_MS = 1500L

        private val COLOR_THEMES = mapOf(
            "Classic"    to ColorTheme(0xFF00FF00.toInt(), 0xFF00CC00.toInt(), 0xFF001800.toInt()),
            "Futuristic" to ColorTheme(0xFF00CCFF.toInt(), 0xFF0080FF.toInt(), 0xFF001122.toInt()),
            "Amber"      to ColorTheme(0xFFFF8800.toInt(), 0xFFFF4400.toInt(), 0xFF220800.toInt()),
            "Grayscale"  to ColorTheme(0xFFCCCCCC.toInt(), 0xFF888888.toInt(), 0xFF111111.toInt()),
            "Purple"     to ColorTheme(0xFFCC44FF.toInt(), 0xFF7700CC.toInt(), 0xFF110022.toInt()),
            // ── New themes ───────────────────────────────────────────────────
            "Solar"      to ColorTheme(0xFFFF6600.toInt(), 0xFFFF3300.toInt(), 0xFF1A0500.toInt()),
            "Neon"       to ColorTheme(0xFFFF00FF.toInt(), 0xFFCC00CC.toInt(), 0xFF110011.toInt()),
            "Ice"        to ColorTheme(0xFFCCF4FF.toInt(), 0xFF55AAFF.toInt(), 0xFF000C14.toInt()),
            "Midnight"   to ColorTheme(0xFF4488FF.toInt(), 0xFF2255CC.toInt(), 0xFF000510.toInt()),
            "Sakura"     to ColorTheme(0xFFFFAACC.toInt(), 0xFFFF6699.toInt(), 0xFF1A0010.toInt())
        )
    }

    data class ColorTheme(val spectrumColor: Int, val fillColor: Int, val bgColor: Int)

    // ─── Demod channel bandwidth ──────────────────────────────────────────────
    private var demodChannelBwHz: Long = 200_000L
    // Sideband alignment for the channel highlight:
    //   0  -> symmetric, centred on the dialed/tuned frequency (AM, FM, NFM, ...)
    //  +1  -> USB: passband extends to the RIGHT (above) the dialed frequency,
    //         i.e. the tuned frequency is the LEFT edge of the highlight.
    //  -1  -> LSB: passband extends to the LEFT (below) the dialed frequency,
    //         i.e. the tuned frequency is the RIGHT edge of the highlight.
    private var demodChannelSideband: Int = 0
    fun setDemodChannelBandwidth(bwHz: Long, sideband: Int = 0) {
        demodChannelBwHz = bwHz
        demodChannelSideband = sideband
        postInvalidate()
    }

    // The dialed/tuned frequency the passband highlight is anchored to, in
    // absolute RF Hz. This is DELIBERATELY a separate field from
    // [centerFreqHz]/[setCenterFrequency] rather than reusing it: for USB/LSB
    // the caller (MainActivity) points [centerFreqHz] at the SDR hardware's
    // *actual* tuned frequency (dial + the SSB BFO offset the DSP engine
    // applies internally) so the FFT trace/waterfall line up with reality,
    // but the passband edge must still be anchored to the *dial* frequency
    // the user actually sees and typed in -- the two differ by the BFO
    // offset in USB/LSB. Reusing centerFreqHz for both would either misplace
    // the FFT trace (if it held the dial value) or misplace the passband box
    // (if it held the hardware value, since drawChannelHighlight would then
    // be measuring the box relative to the wrong anchor).
    private var demodDialFreqHz: Long = 0L
    fun setDemodDialFrequency(hz: Long) {
        demodDialFreqHz = hz
        postInvalidate()
    }

    // ─── Callbacks ────────────────────────────────────────────────────────────
    var onFrequencyClick: ((freqHz: Long) -> Unit)? = null

    // ─── dB display range ─────────────────────────────────────────────────────
    private var dbMin = -120f
    private var dbMax = 0f

    // ─── Display state ────────────────────────────────────────────────────────
    private var spectrumData = FloatArray(256) { -100f }
    private var peakData     = FloatArray(256) { -100f }

    // Note: Path objects are intentionally NOT shared across draw calls.
    // onDraw() runs on the UI thread while updateSpectrum() triggers
    // postInvalidateOnAnimation() from a DSP/background thread. Using shared
    // Path instances caused a native SIGSEGV in SkPath.rewind() / extent_recycle
    // when the draw thread accessed a Path while another frame was still in
    // progress. Each draw call now allocates fresh local Path objects to
    // eliminate this data race entirely.
    //
    // Path allocation is lightweight (no native heap) and these paths are small
    // (one vertex per FFT bin, typically 256–1024), so GC pressure is negligible
    // at typical spectrum update rates (≤ 60 fps).

    private var centerFreqHz  : Long   = 100_000_000L
    // The real RF frequency the SDR hardware is tuned to right now -- may
    // differ from [centerFreqHz] in USB/LSB (see setHardwareTunedFrequency).
    // Defaults to centerFreqHz's initial value so the two agree until the
    // caller starts driving them separately.
    private var hwTunedFreqHz : Long   = centerFreqHz
    private var sampleRateHz  : Int    = 1_920_000
    private var ppmCorrection : Int    = 0
    private var tuningOffsetHz: Double = 0.0
    private var squelchDb     : Float  = -100f
    private var showPeak      = true
    private var showGrid      = true
    private var showBookmarks = true
    private var theme = COLOR_THEMES["Futuristic"]!!

    // ─── Zoom / pan ───────────────────────────────────────────────────────────
    private var freqOffset        : Long  = 0L
    private var zoomFactor               = 1.0f
    private var displayBandwidthHz: Long  = sampleRateHz.toLong()

    // ─── Bookmarks ────────────────────────────────────────────────────────────
    private val bookmarks = mutableMapOf<Long, String>()

    // ─── Advanced feature state ───────────────────────────────────────────────

    // Fill opacity control (0f = invisible, 1f = fully opaque at top)
    private var fillOpacityAlpha = 0xBB   // default ~73%

    // ─── Auto dB range — "true intelligence" noise-floor-aware algorithm ──────
    //
    // Design goals (spec):
    //   1. Noise floor barely visible            -> floor sits just under the
    //      noise so only the top sliver of noise peaks poke above the axis.
    //   2. Strongest signal >= 70 % of max height -> ceiling is set so the
    //      99th-percentile signal peak lands at >= 70 % of the visible span,
    //      i.e. no more than 30 % headroom above the peak.
    //   3. Waterfall noise floor close to black    -> the floor value handed
    //      to WaterfallView (via onAutoRangeChanged) is the actual noise
    //      floor, so the palette's darkest colour lands on the noise, not
    //      some point far below it.
    //   4. Settle within 5 s of enabling, then STOP adjusting -> a one-shot
    //      "acquisition" phase runs for AUTO_RANGE_SETTLE_MS after
    //      setAutoRange(true); during that window the range snaps to target
    //      with a fast EMA. Once acquired, the range freezes (does not
    //      continually creep) unless the signal picture changes materially
    //      (noise floor or required ceiling drifting by more than
    //      AUTO_RANGE_REACQUIRE_DELTA_DB), which re-arms a fresh 5 s
    //      acquisition instead of adjusting forever.
    //   5. No discernible signal -> default to a fixed 20 dB span above the
    //      noise floor (noise floor barely visible at the bottom, nothing to
    //      anchor a "70 % height" target to).
    //   6. Per-protocol fit -> the "full view of the signal" target (floor
    //      margin / peak-height fraction / no-signal span) is derived from
    //      the ACTIVE protocol's own spectrum-display characteristics
    //      (DemodMode.defaultBwHz / defaultDecimation -- the same per-mode
    //      values 9GRadio already uses to decide how narrow/sensitive a
    //      mode's spectrum view should be), not one fixed rule for every
    //      mode. A 500 Hz CW tone and a 200 kHz WFM channel do not want the
    //      same headroom to show their "full signal" without clipping.
    //   7. NEVER re-range on carrier loss -> once a target has been reached
    //      for a real signal, the range holds it exactly, indefinitely, even
    //      after that signal disappears. Auto dB Range only ever moves to
    //      accommodate a newly *received* signal; it must never shrink back
    //      down just because the carrier went away. Re-acquisition still
    //      happens when a *different* signal actually appears that needs a
    //      materially different range.
    private var autoRangeEnabled = false
    private var autoDbMin = -120f                  // current displayed floor
    private var autoDbMax = 0f                     // current displayed ceiling

    // Acquisition state: while true, the range is actively snapping toward
    // the freshly computed target; once the window elapses acquisition ends
    // and the range holds steady.
    private var autoRangeAcquiring = false
    private var autoRangeAcquireStartMs = 0L
    private val AUTO_RANGE_SETTLE_MS = 5000L       // requirement 4: <= 5 s to settle

    // Last target computed, used to detect "the picture changed enough to
    // justify re-acquiring" once we're in the held (non-acquiring) state.
    // Only ever updated from a hasSignal=true reading -- see requirement 7.
    private var lastTargetFloor   = Float.NaN
    private var lastTargetCeiling = Float.NaN
    // If the noise floor or required ceiling drifts by more than this many dB
    // while holding, treat it as a new scene and re-run acquisition rather
    // than silently drifting forever (requirement 4: settle-then-stop, but
    // still correct for genuine condition changes like retuning to a
    // different real signal).
    private val AUTO_RANGE_REACQUIRE_DELTA_DB = 6f

    // A "signal" must clear the noise floor by at least this many dB
    // (99th-percentile bin vs. 15th-percentile noise-floor estimate) to be
    // considered real and not just noise scatter. When this is false, Auto
    // dB Range does not touch the current range at all (requirement 7) --
    // it does NOT fall back to a "no signal" span, since that fallback was
    // exactly the mechanism that made the display shrink back down on
    // carrier loss. The very first time autoRange is enabled with no signal
    // yet present, the seeded manual range from setAutoRange() is used as-is
    // until a real signal arrives to size against.
    //
    // Deliberately higher than a bare "distinguishable from floor" threshold:
    // during an active scan the live spectrum feed re-evaluates this every
    // single hop, most of which are empty or near-empty channels -- a low
    // bar here let ordinary noise bumps get misclassified as "a signal",
    // each one re-triggering acquisition against a fresh, noise-driven
    // target. Common-sense margin against that: require real separation.
    private val AUTO_RANGE_SIGNAL_ABOVE_NOISE_DB = 9f
    // Absolute floor for the visible span so the scale never collapses tight
    // enough to visually magnify ordinary noise texture. Deliberately more
    // generous than the old 15 dB value -- a marginal signal (or a scanner
    // hop briefly landing on a near-empty channel) that only just clears
    // AUTO_RANGE_SIGNAL_ABOVE_NOISE_DB must still get a comfortably wide
    // span, not a tight one that makes noise scatter look like real activity.
    private val AUTO_RANGE_MIN_SPAN = 25f

    // ── requirement 6: per-protocol Auto dB Range fit ──────────────────────
    //
    // Three tiers, matching the same narrowband/medium/wideband grouping
    // DemodMode.defaultDecimation already uses to decide spectral
    // resolution per protocol:
    //
    //   NARROW  (heaviest decimation, tightest channels: CW/CWR, USB/LSB,
    //            NFM, APRS, DPMR, NXDN, digital voice, FLEX)
    //     -> smallest floor margin (noise floor sits closest to the axis --
    //        these are typically weak, narrow signals where every dB of
    //        headroom below the true floor wastes vertical resolution) and
    //        the highest peak-height fraction (the signal itself is narrow
    //        in frequency but should fill the display's height).
    //   MEDIUM  (AM, DSB, ACARS, DRM)
    //     -> the previous single-rule defaults, unchanged.
    //   WIDE    (FM, WFM, WFM Stereo, RAW IQ, ADS-B -- full-bandwidth /
    //            undecimated modes)
    //     -> larger floor margin and lower peak-height fraction, giving more
    //        headroom so a spectrally wide signal's shoulders/sidebands
    //        aren't clipped by a display sized for a narrow tone.
    //
    // Note: there is deliberately no "no-signal span" parameter here (the
    // pre-existing single rule set had one) -- see requirement 7 above.
    // Since Auto dB Range now never adjusts when no signal is present, a
    // per-tier no-signal span would never be read.
    private enum class AutoRangeTier(
        val floorMarginDb: Float,
        val peakHeightFraction: Float
    ) {
        NARROW(floorMarginDb = 1f,  peakHeightFraction = 0.80f),
        MEDIUM(floorMarginDb = 2f,  peakHeightFraction = 0.70f),
        WIDE  (floorMarginDb = 4f,  peakHeightFraction = 0.60f)
    }

    private fun tierFor(mode: DemodMode?): AutoRangeTier = when (mode) {
        DemodMode.CW, DemodMode.CWR,
        DemodMode.USB, DemodMode.LSB,
        DemodMode.NFM, DemodMode.APRS,
        DemodMode.DPMR, DemodMode.NXDN,
        DemodMode.DMR, DemodMode.P25, DemodMode.DSTAR,
        DemodMode.YSF, DemodMode.M17, DemodMode.DIG,
        DemodMode.FLEX
            -> AutoRangeTier.NARROW
        DemodMode.FM, DemodMode.WFM, DemodMode.WFM_STEREO,
        DemodMode.RAW, DemodMode.ADSB
            -> AutoRangeTier.WIDE
        else /* AM, DSB, ACARS, DRM, or unset */
            -> AutoRangeTier.MEDIUM
    }

    // The demod protocol currently active, driving tierFor() above. Set via
    // setDemodMode() by the caller whenever the user changes mode; null
    // (MEDIUM tier / previous fixed defaults) until first set.
    private var currentDemodMode: DemodMode? = null

    /**
     * Tell the spectrum view which protocol is active so Auto dB Range can
     * apply that protocol's own fit (see [AutoRangeTier] / [tierFor]) instead
     * of one fixed rule for every mode. Changing mode is treated as a new
     * scene: a fresh acquisition window is armed so the range actually
     * re-fits to the new protocol's target rather than holding the previous
     * mode's frozen range indefinitely.
     */
    fun setDemodMode(mode: DemodMode) {
        if (mode == currentDemodMode) return
        currentDemodMode = mode
        if (autoRangeEnabled) {
            autoRangeAcquiring = true
            autoRangeAcquireStartMs = System.currentTimeMillis()
            lastTargetFloor = Float.NaN
            lastTargetCeiling = Float.NaN
        }
    }

    /** Fired after each auto-range update; caller mirrors the range to WaterfallView. */
    var onAutoRangeChanged: ((floor: Float, ceiling: Float) -> Unit)? = null

    // Fired whenever a pinch/pan gesture changes zoomFactor or freqOffset (including
    // double-tap reset). SpectrumView and WaterfallView each have their own independent
    // ScaleGestureDetector/GestureDetector, so without this the two views can drift out
    // of sync (different zoom levels or pan positions), which shows up as the FFT trace
    // and waterfall bitmap appearing at different widths/positions even though the
    // underlying data is correctly aligned. The caller mirrors this to WaterfallView
    // (and vice versa) via setZoomPan() to keep both views showing the same window.
    var onZoomPanChanged: ((zoomFactor: Float, freqOffsetHz: Long) -> Unit)? = null

    // Set by setZoomPan() while applying a mirrored update from the other view, so this
    // view's own gesture callbacks don't re-fire onZoomPanChanged and bounce the change
    // back and forth between the two views.
    private var suppressZoomPanCallback = false

    // See WaterfallView.viewCreatedAtMs for rationale.
    private val viewCreatedAtMs = System.currentTimeMillis()

    // Noise floor display
    private var showNoiseFloor     = true
    private var noiseFloorOverride : Float? = null   // set by API; null = auto-compute
    private var estimatedNoiseFloor = -120f

    // Peak annotations (auto-detected local maxima)
    private var showPeakAnnotations = true
    data class SpectralPeak(val binIndex: Int, val db: Float, val freqHz: Long)
    private var annotatedPeaks = emptyList<SpectralPeak>()
    // Min separation between annotated peaks (as fraction of visible bandwidth)
    private val PEAK_MIN_SEP_FRACTION = 0.08f
    private val PEAK_MIN_LOCAL_DB     = 6f   // must exceed neighbours by this amount

    // Crosshair (live finger tracking)
    private var showCrosshairEnabled = true
    private var crosshairX = -1f
    private var crosshairY = -1f
    private var crosshairVisible = false

    // Long-press reference marker (persistent)
    private var refMarkerFreqHz: Long? = null
    private var refMarkerDb: Float?    = null

    // ─── Gradient fill ────────────────────────────────────────────────────────
    private var fillGradient: LinearGradient? = null

    private fun rebuildGradient() {
        val h = height.toFloat()
        if (h <= 0f) return
        val bottomY  = h - AXIS_H
        val base     = theme.fillColor and 0x00FFFFFF
        val topColor = base or (fillOpacityAlpha shl 24)
        val botColor = base or (0x00 shl 24)
        fillGradient = LinearGradient(0f, 0f, 0f, bottomY, topColor, botColor, Shader.TileMode.CLAMP)
        fillPaint.shader = fillGradient
    }

    // ─── Paints ───────────────────────────────────────────────────────────────

    private val bgPaint = Paint().apply { style = Paint.Style.FILL }

    private val spectrumPaint = Paint().apply {
        style       = Paint.Style.STROKE
        strokeWidth = 1.5f
        isAntiAlias = true
    }
    private val fillPaint = Paint().apply {
        style       = Paint.Style.FILL
        isAntiAlias = true
    }
    private val gridPaint = Paint().apply {
        style       = Paint.Style.STROKE
        strokeWidth = 0.5f
        color       = 0x33FFFFFF
    }
    private val minorGridPaint = Paint().apply {
        style       = Paint.Style.STROKE
        strokeWidth = 0.5f
        color       = 0x18FFFFFF   // much subtler than major grid
        pathEffect  = DashPathEffect(floatArrayOf(4f, 6f), 0f)
    }
    private val textPaint = Paint().apply {
        isAntiAlias = true
        textSize    = 26f
        color       = 0xAAFFFFFF.toInt()
    }
    private val peakPaint = Paint().apply {
        style       = Paint.Style.STROKE
        strokeWidth = 1.2f
        color       = 0xFFFF4444.toInt()
        isAntiAlias = true
    }
    private val squelchPaint = Paint().apply {
        style       = Paint.Style.STROKE
        strokeWidth = 2f
        color       = 0xFFFFAA00.toInt()
        pathEffect  = DashPathEffect(floatArrayOf(10f, 5f), 0f)
    }
    private val channelHighlightPaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0x3000CCFF.toInt()
        isAntiAlias = false
    }
    private val channelEdgePaint = Paint().apply {
        style       = Paint.Style.STROKE
        strokeWidth = 2.5f
        color       = 0xCC00CCFF.toInt()
        isAntiAlias = true
    }
    private val channelTickPaint = Paint().apply {
        style       = Paint.Style.STROKE
        strokeWidth = 4f
        color       = 0xFF00FFFF.toInt()
        isAntiAlias = true
    }
    private val bookmarkLinePaint = Paint().apply {
        style       = Paint.Style.STROKE
        strokeWidth = 1.5f
        color       = 0xCCFFFF00.toInt()
        isAntiAlias = true
    }
    private val bookmarkDotPaint = Paint().apply {
        style       = Paint.Style.FILL
        color       = 0xFFFFFF00.toInt()
        isAntiAlias = true
    }

    // ── Advanced feature paints ───────────────────────────────────────────────

    // Noise floor line
    private val noiseFloorPaint = Paint().apply {
        style       = Paint.Style.STROKE
        strokeWidth = 1.5f
        color       = 0xAA66FF88.toInt()   // muted green
        pathEffect  = DashPathEffect(floatArrayOf(8f, 5f), 0f)
        isAntiAlias = true
    }

    // Crosshair lines
    private val crosshairLinePaint = Paint().apply {
        style       = Paint.Style.STROKE
        strokeWidth = 1f
        color       = 0x99FFFFFF.toInt()
        pathEffect  = DashPathEffect(floatArrayOf(6f, 4f), 0f)
        isAntiAlias = false
    }
    // Crosshair tooltip background
    private val tooltipBgPaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0xCC000000.toInt()
    }
    private val tooltipTextPaint = Paint().apply {
        isAntiAlias = true
        textSize    = 24f
        color       = 0xFFFFFFFF.toInt()
        typeface    = Typeface.MONOSPACE
    }

    // Peak annotation
    private val peakAnnotPaint = Paint().apply {
        style       = Paint.Style.STROKE
        strokeWidth = 1.5f
        color       = 0xCCFF8800.toInt()
        isAntiAlias = true
    }
    private val peakAnnotFillPaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0x99FF8800.toInt()
    }
    private val peakAnnotTextPaint = Paint().apply {
        isAntiAlias = true
        textSize    = 19f
        color       = 0xFFFFCC00.toInt()
    }

    // Reference marker (long-press)
    private val refMarkerPaint = Paint().apply {
        style       = Paint.Style.STROKE
        strokeWidth = 2f
        color       = 0xFFFF3366.toInt()
        pathEffect  = DashPathEffect(floatArrayOf(8f, 4f), 0f)
        isAntiAlias = true
    }
    private val refMarkerDotPaint = Paint().apply {
        style       = Paint.Style.FILL
        color       = 0xFFFF3366.toInt()
        isAntiAlias = true
    }

    // ─── Gesture detectors ────────────────────────────────────────────────────
    // NOTE: Pinch-zoom and drag-pan are intentionally DISABLED on this view.
    // Zoom/pan served no purpose that justified the risk it introduced: it
    // re-renders the same underlying spectrum/waterfall data at a different
    // scale, and only stays usable if SpectrumView and WaterfallView agree
    // on zoomFactor/freqOffset at every frame. WaterfallView's own gesture
    // handling is already disabled (see its "Gesture detectors" section)
    // and forced to mirror this view's state via setZoomPan() -- but that
    // still depended on this view's zoom/pan being driven at all. Removing
    // it here instead of just downstream guarantees neither view can ever
    // shift out of line with the other again, in every screen that hosts
    // this pair (main screen and the fullscreen SpectrumActivity alike).
    // onScale/onScroll/onDoubleTap below are retained only as no-op stubs
    // so this file's structure stays easy to diff against the previous
    // version; they are never wired to touch input.
    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean = false
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent,
                                  dx: Float, dy: Float): Boolean = false
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                onFrequencyClick?.invoke(pixelToFrequency(e.x)); return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean = false
            override fun onLongPress(e: MotionEvent) {
                // Lock a persistent reference marker at this frequency/level
                refMarkerFreqHz = pixelToFrequency(e.x)
                val bottomY = (height - AXIS_H)
                refMarkerDb = if (height > 0) {
                    dbMax - (e.y / bottomY) * (dbMax - dbMin)
                } else null
                postInvalidate()
            }
        })

    // ─── Public API ───────────────────────────────────────────────────────────

    fun updateSpectrum(data: FloatArray, peak: FloatArray? = null) {
        spectrumData = data.copyOf()
        peak?.let { peakData = it.copyOf() }

        // ── Noise floor estimation (15th percentile of current spectrum) ──────
        // Computed up-front (rather than after auto-range) because the
        // auto-range floor target below must never sit above this value --
        // otherwise the noise-floor line is pushed below the visible
        // dB-range and disappears off the bottom of the display.
        if (noiseFloorOverride != null) {
            estimatedNoiseFloor = noiseFloorOverride!!
        } else if (data.size >= 10) {
            val sorted15 = data.copyOf().also { it.sort() }
            val idx = (sorted15.size * 0.15f).toInt().coerceIn(0, sorted15.size - 1)
            estimatedNoiseFloor = sorted15[idx]
        }

        // ── Auto dB range — "true intelligence" noise-floor-aware algorithm ──
        // See field-block comment above for the full spec. Summary:
        //   floor    = noise floor + tier's floor margin      (barely visible, near-black on waterfall)
        //   ceiling  = peak such that peak sits at >= tier's peak-height
        //              fraction of span
        //   tier     = derived from the active protocol (see AutoRangeTier / tierFor)
        //   timing   = snaps to target within 5 s of enabling, then holds
        //              steady (re-acquires only if a genuine signal's
        //              picture changes significantly).
        //   CARRIER LOSS: if no signal is currently distinguishable from the
        //   noise floor, the range is NOT touched at all -- it holds exactly
        //   whatever it last showed for the most recent real signal,
        //   indefinitely. Auto dB Range only ever adjusts to accommodate a
        //   newly received signal, never to a signal's disappearance.
        if (autoRangeEnabled && data.isNotEmpty()) {
            val sorted = data.copyOf().also { it.sort() }
            val n = sorted.size

            // Signal peak: 99th-percentile (rejects impulse spikes / birdies).
            val p99idx = (n * 0.99f).toInt().coerceIn(0, n - 1)
            val peakDb = sorted[p99idx]

            // Noise floor: reuse the 15th-percentile estimate computed above
            // (estimatedNoiseFloor), which is exactly what the noise-floor
            // line itself is drawn from -- guarantees floor and indicator agree.
            val noiseDb = estimatedNoiseFloor

            // Is there a real signal distinguishable from the noise floor?
            val hasSignal = (peakDb - noiseDb) >= AUTO_RANGE_SIGNAL_ABOVE_NOISE_DB

            if (hasSignal) {
                val tier = tierFor(currentDemodMode)

                // requirement 1 + 3: floor sits just under the noise so the
                // noise is barely visible above the axis / near-black on the
                // waterfall, per this protocol's own margin.
                val targetFloor = noiseDb - tier.floorMarginDb

                // requirement 2 + 6: solve for ceiling such that the peak
                // sits at exactly the protocol's target peak-height fraction
                // of the span:
                //   (peakDb - targetFloor) / (ceiling - targetFloor) = fraction
                //   ceiling = targetFloor + (peakDb - targetFloor) / fraction
                val raw = targetFloor + (peakDb - targetFloor) / tier.peakHeightFraction

                // Hard floor on the span, common-sense guard against
                // magnifying noise: a peak that only barely cleared
                // AUTO_RANGE_SIGNAL_ABOVE_NOISE_DB (e.g. a weak/marginal hit,
                // or a scanner hop landing on a nearly-empty channel) would
                // otherwise solve for a very TIGHT ceiling close to the
                // floor -- and a tight span makes ordinary noise texture
                // look like dramatic activity even though nothing genuinely
                // strong is present. AUTO_RANGE_MIN_SPAN is anchored to
                // targetFloor (itself pinned just above the actual measured
                // noise floor), not to whatever autoDbMin currently happens
                // to be, so this guard holds regardless of history.
                val targetCeiling = max(raw, targetFloor + AUTO_RANGE_MIN_SPAN)

                // ── Acquisition / hold state machine (requirement 4) ────────
                val nowMs = System.currentTimeMillis()
                val floorMoved = lastTargetFloor.isNaN() ||
                    abs(targetFloor - lastTargetFloor) > AUTO_RANGE_REACQUIRE_DELTA_DB
                val ceilingMoved = lastTargetCeiling.isNaN() ||
                    abs(targetCeiling - lastTargetCeiling) > AUTO_RANGE_REACQUIRE_DELTA_DB

                if (!autoRangeAcquiring && (floorMoved || ceilingMoved)) {
                    // Scene changed materially while holding steady (e.g. a
                    // different, stronger/weaker real signal appeared) --
                    // re-arm a fresh, bounded acquisition window rather than
                    // drifting forever.
                    autoRangeAcquiring = true
                    autoRangeAcquireStartMs = nowMs
                }

                lastTargetFloor = targetFloor
                lastTargetCeiling = targetCeiling

                if (autoRangeAcquiring) {
                    val elapsedMs = nowMs - autoRangeAcquireStartMs
                    if (elapsedMs >= AUTO_RANGE_SETTLE_MS) {
                        // Settle window elapsed: snap exactly to target and stop
                        // adjusting (requirement 4 -- "no more than 5 s to set,
                        // not continually adjusting forever").
                        autoDbMin = targetFloor
                        autoDbMax = targetCeiling
                        autoRangeAcquiring = false
                    } else {
                        // Linear "time-remaining" convergence: move a fixed
                        // fraction of the *remaining* distance in the
                        // *remaining* time, recomputed every frame. This
                        // guarantees the range reaches the target by
                        // AUTO_RANGE_SETTLE_MS regardless of the actual frame
                        // rate, while still being a smooth (non-jumpy)
                        // approach rather than a single hard step.
                        val remainingMs = (AUTO_RANGE_SETTLE_MS - elapsedMs).coerceAtLeast(1L)
                        val frameIntervalMs = 67f
                        val stepFraction = (frameIntervalMs / remainingMs.toFloat()).coerceIn(0.05f, 1f)
                        autoDbMin += stepFraction * (targetFloor - autoDbMin)
                        autoDbMax += stepFraction * (targetCeiling - autoDbMax)
                    }
                }
                // else: holding steady -- range is intentionally NOT touched
                // here, satisfying "not continually adjusting forever".

                // Guard: enforce minimum span so the scale never collapses.
                if (autoDbMax - autoDbMin < AUTO_RANGE_MIN_SPAN) {
                    autoDbMax = autoDbMin + AUTO_RANGE_MIN_SPAN
                }

                dbMin = autoDbMin
                dbMax = autoDbMax
                rebuildGradient()
                onAutoRangeChanged?.invoke(dbMin, dbMax)
            }
            // else: no distinguishable signal this frame -- carrier loss (or
            // no carrier yet). Auto dB Range must NEVER adjust to this: hold
            // the last range exactly as it was for the most recent real
            // signal. Do not touch autoDbMin/autoDbMax, lastTargetFloor/
            // Ceiling, dbMin/dbMax, or the acquisition state at all. If
            // acquisition was already in progress toward a real signal that
            // has since vanished mid-acquisition, let it keep completing
            // toward that already-computed target rather than aborting --
            // simplest and safest is to just do nothing here, since
            // lastTargetFloor/Ceiling and the acquiring flag are only ever
            // set from a hasSignal=true branch.
        }


        // ── Peak annotation detection ─────────────────────────────────────────
        if (showPeakAnnotations && data.size >= 5) {
            val minSepBins = ((data.size * PEAK_MIN_SEP_FRACTION).toInt()).coerceAtLeast(3)
            annotatedPeaks = detectTopPeaks(data, maxPeaks = 3,
                minSepBins = minSepBins, minLocalGainDb = PEAK_MIN_LOCAL_DB)
        }

        postInvalidateOnAnimation()
        DebugBus.tick(DebugBus.STAGE_SPECTRUM_VIEW)
        val snap = DebugBus.snapshot()[DebugBus.STAGE_SPECTRUM_VIEW]
        if (snap.status == DebugBus.StageStatus.IDLE) {
            DebugBus.setStatus(DebugBus.STAGE_SPECTRUM_VIEW, DebugBus.StageStatus.OK,
                "${data.size} bins  waiting for rate…")
        }
        if (snap.counter % 15L == 0L) {
            DebugBus.setDetail(DebugBus.STAGE_SPECTRUM_VIEW,
                "${data.size} bins  ${"%.1f".format(snap.ratePerSec)} fps  " +
                "CF: ${"%.3f".format(centerFreqHz / 1e6)} MHz")
        }
        val vw = width; val vh = height
        if (vw <= 0 || vh <= 0) {
            DebugBus.setExtra(DebugBus.STAGE_SPECTRUM_VIEW, DebugBus.EXTRA_SPEC_VIEW_WH,
                "⚠ ${vw}×${vh}px — NOT MEASURED")
            // A 0×0 size right after construction is the normal "not laid out
            // yet" blip, not a fault -- only escalate to ERROR (and log it) once
            // it has persisted past the startup grace window.
            val withinStartupGrace = System.currentTimeMillis() - viewCreatedAtMs < ZERO_DIM_GRACE_MS
            if (!withinStartupGrace && snap.status != DebugBus.StageStatus.ERROR) {
                DebugBus.setStatus(DebugBus.STAGE_SPECTRUM_VIEW, DebugBus.StageStatus.ERROR,
                    "View has zero dimensions (${vw}×${vh}) — cannot render")
            }
        } else {
            DebugBus.setExtra(DebugBus.STAGE_SPECTRUM_VIEW, DebugBus.EXTRA_SPEC_VIEW_WH,
                "${vw}×${vh}px")
            // Recovered: clear any earlier zero-dimension ERROR latch so a
            // resolved startup blip doesn't keep showing as an ongoing fault.
            if (snap.status == DebugBus.StageStatus.ERROR) {
                DebugBus.setStatus(DebugBus.STAGE_SPECTRUM_VIEW, DebugBus.StageStatus.OK,
                    "${data.size} bins  recovered — view size ${vw}×${vh}")
            }
        }
        DebugBus.setExtra(DebugBus.STAGE_SPECTRUM_VIEW, DebugBus.EXTRA_SPEC_DB_RANGE,
            "${"%.0f".format(dbMin)}..${"%.0f".format(dbMax)} dBFS")
        if (data.isNotEmpty()) {
            val dataMin = data.minOrNull() ?: 0f
            val dataMax = data.maxOrNull() ?: 0f
            val rangeStr = "${"%.0f".format(dataMin)}..${"%.0f".format(dataMax)} dBFS"
            val allBelow = dataMax < dbMin
            val allAbove = dataMin > dbMax
            // NOTE: "below display floor" is the *normal* state when there is no
            // RF traffic (the noise floor simply sits under the current dB
            // window) — it is not a fault, so it is reported here as plain
            // informational text rather than flagged with a warning marker.
            // See DebugPanelActivity.buildFaultList(), which intentionally does
            // NOT surface this as an ERROR/WARN fault item for the same reason.
            DebugBus.setExtra(DebugBus.STAGE_SPECTRUM_VIEW, DebugBus.EXTRA_SPEC_DATA_RANGE,
                if (allBelow) "$rangeStr (below display floor — no signal/idle)"
                else if (allAbove) "$rangeStr (above display ceiling)"
                else rangeStr)
        }
    }

    fun setCenterFrequency(hz: Long) {
        val delta = hz - centerFreqHz
        centerFreqHz = hz
        val binHz = if (sampleRateHz > 0 && spectrumData.isNotEmpty())
            sampleRateHz.toDouble() / spectrumData.size else 1.0
        tuningOffsetHz = (tuningOffsetHz + delta) % binHz
        postInvalidate()
    }

    /**
     * The real RF frequency the SDR hardware is tuned to right now (i.e. the
     * frequency FFT bin `n/2` actually represents). In USB/LSB this is
     * `dial + SSB_BFO_OFFSET_HZ` for *both* sidebands (symmetric hardware
     * shift -- see DspEngine.setCarrierFrequency), which is NOT the same
     * value [setCenterFrequency] receives for LSB (that one intentionally
     * flips sign so the passband/dial-marker overlays land in the right
     * place -- see MainActivity.hardwareCenterFreqHz's doc comment).
     *
     * binToPixel() uses this value -- not [centerFreqHz] -- to place FFT
     * bins, so the trace stays correctly positioned relative to those
     * overlays even when the two frequencies disagree.
     */
    fun setHardwareTunedFrequency(hz: Long) {
        if (hz == hwTunedFreqHz) return
        hwTunedFreqHz = hz
        postInvalidate()
    }

    /**
     * Applies a zoom/pan state mirrored from the other view (WaterfallView), without
     * re-firing [onZoomPanChanged] back at it -- see [suppressZoomPanCallback].
     */
    fun setZoomPan(zoom: Float, offsetHz: Long) {
        val clampedZoom = zoom.coerceIn(1f, 32f)
        val clampedOffset = offsetHz.coerceIn(-sampleRateHz / 2L, sampleRateHz / 2L)
        if (clampedZoom == zoomFactor && clampedOffset == freqOffset) return
        suppressZoomPanCallback = true
        zoomFactor = clampedZoom
        displayBandwidthHz = (sampleRateHz / zoomFactor).toLong()
        freqOffset = clampedOffset
        suppressZoomPanCallback = false
        postInvalidate()
    }

    fun setPpmCorrection(ppm: Int)      { ppmCorrection = ppm; postInvalidate() }

    fun setSampleRate(rate: Int) {
        sampleRateHz = rate
        displayBandwidthHz = (rate / zoomFactor).toLong()
        tuningOffsetHz = 0.0
        postInvalidate()
    }

    fun setSquelch(db: Float)           { squelchDb = db; postInvalidate() }

    fun setTheme(name: String) {
        theme = COLOR_THEMES[name] ?: theme
        rebuildGradient()
        postInvalidate()
    }

    fun setDbRange(minDb: Float, maxDb: Float) {
        if (minDb < maxDb) { dbMin = minDb; dbMax = maxDb; postInvalidate() }
    }

    fun setShowPeakHold(show: Boolean)  { showPeak = show }
    fun setShowGrid(show: Boolean)      { showGrid = show }

    fun addBookmark(freqHz: Long, label: String) { bookmarks[freqHz] = label; postInvalidate() }
    fun removeBookmark(freqHz: Long)             { bookmarks.remove(freqHz); postInvalidate() }
    fun clearBookmarks()                         { bookmarks.clear(); postInvalidate() }

    // ── New advanced API ──────────────────────────────────────────────────────

    /**
     * Control the opacity of the gradient fill under the spectrum trace.
     * [alpha] in 0..1 (0 = hidden, 1 = fully opaque at the top of the trace).
     */
    fun setFillOpacity(alpha: Float) {
        fillOpacityAlpha = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        rebuildGradient(); postInvalidate()
    }

    /**
     * Enable automatic dB range scaling that tracks the incoming signal level.
     * When enabled, [setDbRange] calls are ignored until this is turned off.
     */
    fun setAutoRange(enable: Boolean) {
        if (enable && !autoRangeEnabled) {
            // Seed from the current manual range so the first auto frame does
            // not visually snap from the uninitialised 0/−120 defaults --
            // acquisition still completes within AUTO_RANGE_SETTLE_MS from
            // this seed.
            autoDbMin = dbMin
            autoDbMax = dbMax
            // Arm a fresh acquisition window: the display will converge onto
            // the intelligent target within AUTO_RANGE_SETTLE_MS and then
            // hold steady rather than adjusting forever.
            autoRangeAcquiring = true
            autoRangeAcquireStartMs = System.currentTimeMillis()
            lastTargetFloor = Float.NaN
            lastTargetCeiling = Float.NaN
        }
        autoRangeEnabled = enable
    }

    /** Show/hide the estimated noise floor line. */
    fun setShowNoiseFloor(show: Boolean) { showNoiseFloor = show; postInvalidate() }

    /**
     * Override the auto-computed noise floor with an externally supplied value
     * (e.g. from FftEngine.getNoiseFloor() averaged to a scalar).
     * Pass null to revert to auto-computation.
     */
    fun setNoiseFloorDb(db: Float?)     { noiseFloorOverride = db; postInvalidate() }

    /** Show/hide automatically detected peak frequency labels. */
    fun setShowPeakAnnotations(show: Boolean) { showPeakAnnotations = show; postInvalidate() }

    /** Enable/disable the live crosshair shown while a finger is on the display. */
    fun setCrosshairEnabled(enable: Boolean) { showCrosshairEnabled = enable }

    /** Clear the long-press reference marker. */
    fun clearRefMarker() { refMarkerFreqHz = null; refMarkerDb = null; postInvalidate() }

    // ─── Size change ──────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        rebuildGradient()
    }

    // ─── Drawing ──────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        bgPaint.color = theme.bgColor
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        if (showGrid)  drawGrid(canvas, w, h)
        if (demodChannelBwHz > 0) drawChannelHighlight(canvas, w, h)
        if (squelchDb > dbMin)    drawSquelch(canvas, w, h)
        if (showNoiseFloor)       drawNoiseFloor(canvas, w, h)
        drawSpectrum(canvas, w, h)
        if (showPeak && peakData.isNotEmpty()) drawPeak(canvas, w, h)
        if (showBookmarks && bookmarks.isNotEmpty()) drawBookmarks(canvas, w, h)
        refMarkerFreqHz?.let { drawRefMarker(canvas, w, h, it, refMarkerDb) }
        if (showPeakAnnotations) drawPeakAnnotations(canvas, w, h)
        drawFrequencyAxis(canvas, w, h)
        if (crosshairVisible && showCrosshairEnabled) drawCrosshair(canvas, w, h)
    }

    private fun drawGrid(canvas: Canvas, w: Float, h: Float) {
        val bottomY = h - AXIS_H
        // Minor grid lines first (below major) at 5 dB intervals
        var db = dbMin
        while (db <= dbMax) {
            val y = dbToY(db, bottomY)
            canvas.drawLine(0f, y, w, y, minorGridPaint)
            db += MINOR_DB_STEP
        }
        // Major grid lines at 10 dB intervals
        db = dbMin
        while (db <= dbMax) {
            val y = dbToY(db, bottomY)
            canvas.drawLine(0f, y, w, y, gridPaint)
            db += GRID_DB_STEP
        }
        // Vertical frequency dividers
        for (i in 1..4) {
            val x = w * i / 5f
            canvas.drawLine(x, 0f, x, bottomY, gridPaint)
        }
        // dB labels (right side, every other major step)
        textPaint.textSize = 22f
        db = dbMin
        while (db <= dbMax) {
            val y = dbToY(db, bottomY)
            if (y > 14f && y < bottomY - 4f) {
                canvas.drawText("${db.toInt()} dB", w - LABEL_RIGHT_MARGIN, y - 3f, textPaint)
            }
            db += GRID_DB_STEP * 2f
        }
        textPaint.textSize = 26f
    }

    private fun drawSpectrum(canvas: Canvas, w: Float, h: Float) {
        val n = spectrumData.size
        if (n < 2) return

        spectrumPaint.color = theme.spectrumColor
        val bottomY = h - AXIS_H

        // Local Path objects per draw call — avoids SIGSEGV from concurrent
        // access to shared Path instances between the UI thread (onDraw) and
        // background spectrum updates (postInvalidateOnAnimation).
        val specPath = Path()
        val fillPath = Path()

        var lastX  = 0f
        var started = false

        for (i in 0 until n) {
            val x = binToPixel(i, n, w)
            if (x < -1f || x > w + 1f) continue
            val y = dbToY(spectrumData[i], bottomY)
            if (!started) {
                specPath.moveTo(x, y)
                fillPath.moveTo(x, bottomY)
                fillPath.lineTo(x, y)
                started = true
            } else {
                specPath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
            lastX = x
        }
        if (!started) return

        fillPath.lineTo(lastX, bottomY)
        fillPath.close()
        if (fillGradient == null) rebuildGradient()
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(specPath, spectrumPaint)
    }

    private fun drawPeak(canvas: Canvas, w: Float, h: Float) {
        val n = peakData.size
        if (n < 2) return
        // Local Path — see drawSpectrum() for the rationale on why shared
        // Path instances are not used here.
        val path = Path()
        var started = false
        val bottomY = h - AXIS_H
        for (i in 0 until n) {
            val x = binToPixel(i, n, w)
            if (x < -1f || x > w + 1f) continue
            val y = dbToY(peakData[i], bottomY)
            if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
        }
        if (started) canvas.drawPath(path, peakPaint)
    }

    private fun drawNoiseFloor(canvas: Canvas, w: Float, h: Float) {
        val db = estimatedNoiseFloor
        if (db <= dbMin || db >= dbMax) return
        val y = dbToY(db, h - AXIS_H)
        canvas.drawLine(0f, y, w, y, noiseFloorPaint)
        val saved = textPaint.color
        textPaint.color    = 0xAA66FF88.toInt()
        textPaint.textSize = 19f
        canvas.drawText("NF ${db.toInt()} dB", 4f, y - 3f, textPaint)
        textPaint.color    = saved
        textPaint.textSize = 26f
    }

    /**
     * Draw the live finger crosshair with an info tooltip.
     */
    private fun drawCrosshair(canvas: Canvas, w: Float, h: Float) {
        val x = crosshairX.coerceIn(0f, w)
        val y = crosshairY.coerceIn(0f, h - AXIS_H)
        val bottomY = h - AXIS_H

        // Vertical and horizontal dashed lines
        canvas.drawLine(x, 0f, x, bottomY, crosshairLinePaint)
        canvas.drawLine(0f, y, w, y, crosshairLinePaint)

        // Tooltip: frequency + dBFS
        val freqHz  = pixelToFrequency(x)
        val db      = dbMax - (y / bottomY) * (dbMax - dbMin)
        val freqStr = formatFrequency(freqHz)
        val dbStr   = "${"%.1f".format(db)} dB"
        val line1W  = tooltipTextPaint.measureText(freqStr)
        val line2W  = tooltipTextPaint.measureText(dbStr)
        val boxW    = maxOf(line1W, line2W) + 12f
        val boxH    = tooltipTextPaint.textSize * 2.4f
        val textH   = tooltipTextPaint.textSize

        // Place tooltip above finger, mirrored if near edge
        val PADDING = 8f
        var bx = x + PADDING
        if (bx + boxW > w) bx = x - boxW - PADDING
        var by = y - boxH - PADDING
        if (by < 0f) by = y + PADDING

        val rx = RectF(bx, by, bx + boxW, by + boxH)
        canvas.drawRoundRect(rx, 6f, 6f, tooltipBgPaint)
        canvas.drawText(freqStr, bx + 6f, by + textH,           tooltipTextPaint)
        canvas.drawText(dbStr,   bx + 6f, by + textH * 2.1f,    tooltipTextPaint)
    }

    /**
     * Draw up to 3 peak annotation markers: a small downward triangle + label.
     */
    private fun drawPeakAnnotations(canvas: Canvas, w: Float, h: Float) {
        val bottomY = h - AXIS_H
        val n       = spectrumData.size
        if (n < 2) return

        for (peak in annotatedPeaks) {
            val x  = binToPixel(peak.binIndex, n, w)
            if (x < 0f || x > w) continue
            val y  = dbToY(peak.db, bottomY).coerceIn(8f, bottomY - 30f)

            // Small downward triangle cap
            val tri = Path().apply {
                val TS = 7f
                moveTo(x,        y + TS)
                lineTo(x - TS,   y)
                lineTo(x + TS,   y)
                close()
            }
            canvas.drawPath(tri, peakAnnotFillPaint)
            canvas.drawPath(tri, peakAnnotPaint)

            // Vertical dotted guide line
            canvas.drawLine(x, y + 8f, x, bottomY, peakAnnotPaint)

            // Label: freq + dBFS
            val freq  = formatFrequency(peak.freqHz)
            val label = "$freq\n${peak.db.toInt()}dB"
            val lw    = peakAnnotTextPaint.measureText(freq)
            var lx    = x - lw / 2f
            if (lx < 2f) lx = 2f
            if (lx + lw > w - 2f) lx = w - lw - 2f
            val ly    = (y - 8f).coerceAtLeast(peakAnnotTextPaint.textSize + 2f)
            canvas.drawText(freq, lx, ly, peakAnnotTextPaint)
            canvas.drawText("${peak.db.toInt()}dB", lx, ly + peakAnnotTextPaint.textSize + 2f, peakAnnotTextPaint)
        }
    }

    private fun drawRefMarker(canvas: Canvas, w: Float, h: Float, freqHz: Long, db: Float?) {
        val x = freqToPixel(freqHz, w)
        if (x < 0f || x > w) return
        val bottomY = h - AXIS_H

        canvas.drawLine(x, 0f, x, bottomY, refMarkerPaint)
        canvas.drawCircle(x, 10f, 6f, refMarkerDotPaint)

        // db level horizontal line
        db?.let {
            val y = dbToY(it, bottomY).coerceIn(0f, bottomY)
            canvas.drawLine(0f, y, w, y, refMarkerPaint)
            canvas.drawCircle(x, y, 5f, refMarkerDotPaint)
        }

        // Label
        val savedColor = textPaint.color
        textPaint.color    = 0xFFFF3366.toInt()
        textPaint.textSize = 20f
        val labelStr = formatFrequency(freqHz) + (db?.let { "  ${it.toInt()}dB" } ?: "")
        canvas.drawText(labelStr, x + 5f, 22f, textPaint)
        textPaint.color    = savedColor
        textPaint.textSize = 26f
    }

    private fun drawChannelHighlight(canvas: Canvas, w: Float, h: Float) {
        // This must mirror exactly what SsbDemodulator actually does in DSP:
        // the BFO mixes the dial frequency down to 0 Hz baseband, then a
        // single 0..bw Hz lowpass is applied -- there is NO high-pass / low-cut
        // gap in this codebase's demodulator (see Demodulators.kt SsbDemodulator:
        // LPF_CUTOFF = 3000.0, applied directly with no low-cut stage). That
        // means the true passband is:
        //   USB: [dial, dial + bw]   -- dial is the LEFT/lower edge
        //   LSB: [dial - bw, dial]   -- dial is the RIGHT/upper edge
        // The dial frequency is a hard EDGE of the passband, not its centre
        // and not offset from the edge by any guard gap. Drawing the box
        // centred on dial +/- bw/2 (as a prior version of this function did)
        // is WRONG -- it shifts the box so the dial itself, and the signal
        // right at the edge nearest the dial, sit partly outside the box.
        // For symmetric modes (AM/FM/NFM/...) the passband genuinely is
        // centred on the dial, so that case is unchanged.
        val fullBw  = demodChannelBwHz.toDouble()
        val halfBw  = fullBw / 2.0
        // Anchor to the dial frequency, NOT centerFreqHz -- see the comment on
        // demodDialFreqHz. Falls back to centerFreqHz if the caller never set a
        // dial value explicitly (e.g. symmetric-only modes that predate this field).
        val dialHz  = if (demodDialFreqHz != 0L) demodDialFreqHz else centerFreqHz
        val centre  = freqToPixel(dialHz, w)
        val bottomY = h - AXIS_H

        val MIN_HALF_PX = 10f
        val fullPx = (fullBw / displayBandwidthHz * w).toFloat()

        val fillL: Float
        val fillR: Float
        val edgeL: Float
        val edgeR: Float
        when (demodChannelSideband) {
            1 -> { // USB: dial is the left/lower edge, passband extends right/up
                val edgePx = fullPx.coerceAtLeast(MIN_HALF_PX * 2f)
                edgeL = centre.coerceAtLeast(0f)
                edgeR = (centre + edgePx).coerceAtMost(w)
                fillL = centre.coerceAtLeast(0f)
                fillR = (centre + fullPx).coerceAtMost(w)
            }
            -1 -> { // LSB: dial is the right/upper edge, passband extends left/down
                val edgePx = fullPx.coerceAtLeast(MIN_HALF_PX * 2f)
                edgeL = (centre - edgePx).coerceAtLeast(0f)
                edgeR = centre.coerceAtMost(w)
                fillL = (centre - fullPx).coerceAtLeast(0f)
                fillR = centre.coerceAtMost(w)
            }
            else -> { // Symmetric: centred on the dial frequency
                val halfPx     = (halfBw / displayBandwidthHz * w).toFloat()
                val edgeHalfPx = halfPx.coerceAtLeast(MIN_HALF_PX)
                edgeL = (centre - edgeHalfPx).coerceAtLeast(0f)
                edgeR = (centre + edgeHalfPx).coerceAtMost(w)
                fillL = (centre - halfPx).coerceAtLeast(0f)
                fillR = (centre + halfPx).coerceAtMost(w)
            }
        }
        if (fillR > fillL) canvas.drawRect(fillL, 0f, fillR, bottomY, channelHighlightPaint)

        canvas.drawLine(edgeL, 0f, edgeL, bottomY, channelEdgePaint)
        canvas.drawLine(edgeR, 0f, edgeR, bottomY, channelEdgePaint)
        val TICK = 8f
        canvas.drawLine(edgeL - TICK, 0f,      edgeL + TICK, 0f,      channelTickPaint)
        canvas.drawLine(edgeL - TICK, bottomY, edgeL + TICK, bottomY, channelTickPaint)
        canvas.drawLine(edgeR - TICK, 0f,      edgeR + TICK, 0f,      channelTickPaint)
        canvas.drawLine(edgeR - TICK, bottomY, edgeR + TICK, bottomY, channelTickPaint)

        val bwLabel = when {
            demodChannelBwHz >= 1_000_000L -> "${"%.2f".format(demodChannelBwHz / 1e6)}MHz"
            demodChannelBwHz >= 1_000L     -> "${"%.1f".format(demodChannelBwHz / 1e3)}kHz"
            else                            -> "${demodChannelBwHz}Hz"
        }
        val savedColor = textPaint.color
        val savedSize  = textPaint.textSize
        textPaint.color    = 0xFF00FFFF.toInt()
        textPaint.textSize = 22f
        val labelW = textPaint.measureText(bwLabel)
        val labelX = centre.coerceIn(labelW / 2f + 4f, w - labelW / 2f - 4f)
        canvas.drawText(bwLabel, labelX - labelW / 2f, 22f, textPaint)
        textPaint.color    = savedColor
        textPaint.textSize = savedSize
    }

    private fun drawSquelch(canvas: Canvas, w: Float, h: Float) {
        val y = dbToY(squelchDb, h - AXIS_H)
        canvas.drawLine(0f, y, w, y, squelchPaint)
        val saved = textPaint.color
        textPaint.color    = 0xFFFFAA00.toInt()
        textPaint.textSize = 22f
        canvas.drawText("SQL ${squelchDb.toInt()} dB", 6f, y - 4f, textPaint)
        textPaint.color    = saved
        textPaint.textSize = 26f
    }

    private fun drawBookmarks(canvas: Canvas, w: Float, h: Float) {
        val bottomY = h - AXIS_H
        for ((freqHz, label) in bookmarks) {
            val x = freqToPixel(freqHz, w)
            if (x < 0f || x > w) continue
            canvas.drawLine(x, 0f, x, bottomY, bookmarkLinePaint)
            canvas.drawCircle(x, 8f, 5f, bookmarkDotPaint)
            val saved = textPaint.color
            textPaint.color    = 0xFFFFFF00.toInt()
            textPaint.textSize = 20f
            canvas.drawText(label, x + 4f, 20f, textPaint)
            textPaint.color    = saved
            textPaint.textSize = 26f
        }
    }

    private fun drawFrequencyAxis(canvas: Canvas, w: Float, h: Float) {
        textPaint.textSize = 22f
        val y         = h - 3f
        val numLabels = 5
        for (i in 0..numLabels) {
            val frac  = i.toFloat() / numLabels
            val x     = w * frac
            val hz    = pixelToFrequency(x)
            val label = formatFrequency(hz)
            val lw    = textPaint.measureText(label)
            val drawX = x.coerceIn(lw / 2 + 2f, w - lw / 2 - 2f)
            canvas.drawText(label, drawX, y, textPaint)
        }
        textPaint.textSize = 26f
    }

    // ─── Coordinate helpers ───────────────────────────────────────────────────

    private fun dbToY(db: Float, maxY: Float): Float {
        val clamped = db.coerceIn(dbMin, dbMax)
        return maxY * (dbMax - clamped) / (dbMax - dbMin)
    }

    // Single source of truth for every frequency<->pixel conversion in this
    // view. Previously the FFT trace (binToPixel), the axis labels
    // (pixelToFrequency), and the overlays (drawChannelHighlight/
    // drawRefMarker/drawBookmarks) each re-implemented this math slightly
    // differently -- binToPixel applied tuningOffsetHz but not ppmCorrection,
    // pixelToFrequency applied ppmCorrection but not tuningOffsetHz, and the
    // overlays applied neither. tuningOffsetHz in particular can be as large
    // as one FFT bin (sampleRateHz / spectrumData.size, e.g. ~4 kHz at
    // 1.024 MS/s with a 256-point display FFT), so that mismatch showed up
    // as the trace/waterfall being visibly offset from the passband box and
    // reference markers. All three now route through relHzToPixel /
    // pixelToRelHz so they are always in agreement.
    private fun relHzToPixel(relHz: Double, w: Float): Float {
        val ppmHz = centerFreqHz.toDouble() * ppmCorrection / 1_000_000.0
        val adjRelHz = relHz - ppmHz - freqOffset - tuningOffsetHz
        return (w / 2.0 + adjRelHz / displayBandwidthHz * w).toFloat()
    }

    private fun pixelToRelHz(x: Float, w: Float): Double {
        val ppmHz = centerFreqHz.toDouble() * ppmCorrection / 1_000_000.0
        return (x - w / 2.0) / w * displayBandwidthHz + ppmHz + freqOffset + tuningOffsetHz
    }

    /** Absolute frequency (Hz) -> screen x. */
    private fun freqToPixel(freqHz: Long, w: Float): Float =
        relHzToPixel((freqHz - centerFreqHz).toDouble(), w)

    private fun binToPixel(bin: Int, totalBins: Int, w: Float): Float {
        // Bin totalBins/2 is where the FFT is actually centred: the REAL
        // hardware/RF tuner frequency (hwTunedFreqHz), not necessarily the
        // dial/display-centre frequency (centerFreqHz). In USB/LSB these two
        // differ by DspEngine.SSB_BFO_OFFSET_HZ (the BFO shift applied to the
        // hardware tune so the demod carrier lands where the sideband select
        // expects it) -- see setHardwareTunedFrequency's doc comment. Ignoring
        // that offset here silently shifted the whole trace/waterfall by that
        // same amount relative to the passband box and dial marker, which are
        // correctly positioned off centerFreqHz -- exactly the "signal falls
        // partly outside the box" symptom.
        //
        // Fix, following the gqrx model: resolve the true absolute frequency
        // for this bin once, then feed it through the same relHzToPixel used
        // by every overlay -- no separate/duplicated offset math.
        val binRelHz = (bin.toDouble() + 0.5 - totalBins / 2.0) / totalBins.toDouble() * sampleRateHz
        val binFreqHz = hwTunedFreqHz + binRelHz.toLong()
        return freqToPixel(binFreqHz, w)
    }

    private fun pixelToFrequency(x: Float): Long {
        return centerFreqHz + Math.round(pixelToRelHz(x, width.toFloat()))
    }

    private fun formatFrequency(hz: Long) = when {
        abs(hz) >= 1_000_000_000L -> "${"%.2f".format(hz / 1e9)}G"
        abs(hz) >= 1_000_000L    -> "${"%.3f".format(hz / 1e6)}M"
        abs(hz) >= 1_000L        -> "${"%.1f".format(hz / 1e3)}k"
        else                      -> "${hz} Hz"
    }

    // ─── Peak detection helper ────────────────────────────────────────────────

    /**
     * Detect local spectral peaks in [data].
     * A bin qualifies as a local maximum when it exceeds both its left and right
     * neighbours by at least [minLocalGainDb] dB.
     */
    private fun detectTopPeaks(
        data          : FloatArray,
        maxPeaks      : Int,
        minSepBins    : Int,
        minLocalGainDb: Float
    ): List<SpectralPeak> {
        val n   = data.size
        val candidates = mutableListOf<Pair<Int, Float>>()   // (bin, dBFS)
        val look = 2  // look ±look bins for local max to reduce noise sensitivity
        for (i in look until n - look) {
            val v = data[i]
            var isMax = true
            for (d in 1..look) {
                if (data[i - d] >= v - minLocalGainDb || data[i + d] >= v - minLocalGainDb) {
                    isMax = false; break
                }
            }
            if (isMax) candidates += Pair(i, v)
        }
        candidates.sortByDescending { it.second }

        val selected = mutableListOf<SpectralPeak>()
        for ((bin, db) in candidates) {
            if (selected.size >= maxPeaks) break
            if (selected.any { abs(it.binIndex - bin) < minSepBins }) continue
            val freqHz = pixelToFrequency(binToPixel(bin, n, width.toFloat()))
            selected += SpectralPeak(bin, db, freqHz)
        }
        return selected
    }

    // ─── Touch ────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Track crosshair position for single-finger touches
        if (showCrosshairEnabled) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    crosshairX = event.x; crosshairY = event.y; crosshairVisible = true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1) {
                        crosshairX = event.x; crosshairY = event.y
                    } else {
                        crosshairVisible = false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    crosshairVisible = false
                }
            }
        }
        // scaleDetector is intentionally not fed touch events: zoom/pan is
        // disabled entirely on this view (see the "Gesture detectors"
        // section above).
        gestureDetector.onTouchEvent(event)
        return true
    }
}
