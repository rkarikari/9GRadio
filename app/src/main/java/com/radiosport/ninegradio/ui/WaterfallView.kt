package com.radiosport.ninegradio.ui

import com.radiosport.ninegradio.debug.DebugBus
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.*

/**
 * Waterfall (spectrogram) display — scrolling heatmap of FFT magnitude vs time.
 * New lines are added at the top; history scrolls downward.
 *
 * Advanced features added:
 *  ─── Palettes ──────────────────────────────────────────────────────────────
 *  • 6 new scientific palettes: VIRIDIS, INFERNO, MAGMA, TURBO, SOLAR, NIGHT_VISION
 *    All existing palettes (RAINBOW, HEAT, GRAYSCALE, BLUE_WHITE, PURPLE_YELLOW)
 *    retain their ordinal positions for backward compatibility.
 *
 *  ─── Display controls ──────────────────────────────────────────────────────
 *  • Brightness/contrast adjustment (applied at pixel-row render time)
 *  • Auto-stretch: tracks running min/max and auto-scales the display range
 *  • Pause/resume: freeze the waterfall without stopping data collection
 *  • Time ruler: elapsed-time annotations on the right edge
 *  • Tuner centre-frequency marker: vertical line at the tuned frequency
 *
 *  ─── Ring-buffer design ────────────────────────────────────────────────────
 *  Each [addLine] writes one full-bandwidth pixel row into the ring-buffer bitmap.
 *  Zoom/pan are applied exclusively at draw time, so historical rows reflect the
 *  current view state without any re-rendering.
 */
class WaterfallView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object {
        private const val DB_MIN = -120f
        private const val DB_MAX = 0f
        private const val AXIS_H = 26f

        // How long after this view is constructed a 0×0 size is treated as the
        // normal "not laid out yet" startup blip rather than a real fault. Android
        // attaches a view before the first measure/layout pass runs, so width/height
        // are legitimately 0 for the first frame or two -- that is not an error.
        private const val ZERO_DIM_GRACE_MS = 1500L

        // Fallback FFT bin count for lastBinCount, used only until the first real
        // spectrum row arrives via addLine(). MUST match SpectrumView's default
        // spectrumData size (FloatArray(256)), which is also this app's actual
        // default "fftSize" preference (see MainViewModel._fftSize).
        // setCenterFrequency() runs on connect via MainActivity.applyAllSettings()
        // -- which happens as soon as the SDR service binds, and can fire before
        // the DSP pipeline has produced its first FFT frame -- so lastBinCount can
        // legitimately still be at its fallback the first time a frequency is set.
        // If that fallback disagreed with SpectrumView's real bin count (e.g. the
        // old bare 0, which forced a nonsensical 1 Hz-per-bin fallback), this
        // view's tuningOffsetHz would wrap at a different point than
        // SpectrumView's from that very first call onward, baking in a constant
        // waterfall/spectrum misalignment for the rest of the session -- not
        // merely a slow drift, but a wrong offset from the first frequency set.
        private const val DEFAULT_FFT_BIN_COUNT = 256

        /**
         * Colour palettes.
         *
         * *** ORDINAL ORDER MUST NOT CHANGE — existing 5 entries are first ***
         * MainActivity references these by position via Palette.values()[pos].
         * New palettes are appended after the original 5.
         */
        enum class Palette {
            // ── Original palettes (ordinals 0-4, must remain unchanged) ───────
            RAINBOW,
            HEAT,
            GRAYSCALE,
            BLUE_WHITE,
            PURPLE_YELLOW,
            // ── New scientific palettes (ordinals 5-10) ────────────────────────
            /** Perceptually uniform: dark purple → blue → teal → green → yellow */
            VIRIDIS,
            /** High-contrast dark: black → purple → red → orange → pale yellow */
            INFERNO,
            /** Dark perceptual: black → purple → mauve → salmon → cream */
            MAGMA,
            /** Vivid full-spectrum: dark blue → cyan → green → yellow → red */
            TURBO,
            /** Warm luminance: black → deep orange → bright yellow */
            SOLAR,
            /** Night-vision green: black → dark green → bright phosphor green */
            NIGHT_VISION
        }
    }

    // ─── Callbacks ────────────────────────────────────────────────────────────
    var onFrequencyClick: ((freqHz: Long) -> Unit)? = null

    // Fired whenever a pinch/pan gesture changes zoomFactor or freqOffsetHz (including
    // double-tap reset) -- mirrors SpectrumView.onZoomPanChanged. See that field's doc
    // comment for why this exists: SpectrumView and WaterfallView each have their own
    // independent gesture detectors and would otherwise drift out of sync.
    var onZoomPanChanged: ((zoomFactor: Float, freqOffsetHz: Long) -> Unit)? = null

    // Set while applying a mirrored update from the other view, so this view's own
    // gesture callbacks don't re-fire onZoomPanChanged and bounce the change back and
    // forth between the two views.
    private var suppressZoomPanCallback = false

    // Timestamp this instance was constructed, used to distinguish a normal
    // first-layout-pass 0×0 blip (see ZERO_DIM_GRACE_MS) from a genuinely
    // persistent zero-dimension condition.
    private val viewCreatedAtMs = System.currentTimeMillis()

    // ─── SDR state ────────────────────────────────────────────────────────────
    private var centerFreqHz  : Long   = 100_000_000L
    // The real RF frequency the SDR hardware is tuned to right now -- may
    // differ from [centerFreqHz] in USB/LSB (see setHardwareTunedFrequency).
    // Defaults to centerFreqHz's initial value so the two agree until the
    // caller starts driving them separately.
    private var hwTunedFreqHz : Long   = centerFreqHz
    private var sampleRateHz  : Int    = 1_920_000
    private var ppmCorrection : Int    = 0
    private var tuningOffsetHz: Double = 0.0
    // Number of FFT bins in the most recently received spectrum row (set in
    // addLine()). setCenterFrequency must wrap tuningOffsetHz using this --
    // the same unit SpectrumView uses (spectrumData.size) -- NOT the
    // waterfall bitmap's pixel width. Those are unrelated quantities (e.g.
    // 256 FFT bins vs. a ~1000+ px wide view); using the wrong one made
    // tuningOffsetHz wrap at a different point than SpectrumView's,
    // causing the two views' tuningOffsetHz values to drift apart after
    // repeated retunes. Defaults to DEFAULT_FFT_BIN_COUNT (see that
    // constant's doc comment) rather than 0/unset, so the very first
    // setCenterFrequency call -- which can happen before addLine() ever
    // runs -- already agrees with SpectrumView instead of silently using a
    // nonsensical 1 Hz-per-bin fallback.
    private var lastBinCount  : Int    = DEFAULT_FFT_BIN_COUNT

    // ─── Display settings ─────────────────────────────────────────────────────
    private var palette256 = buildPalette(Palette.RAINBOW)
    private var dbMin = DB_MIN
    private var dbMax = DB_MAX

    // ─── Zoom / pan ───────────────────────────────────────────────────────────
    private var zoomFactor   = 1.0f
    private var freqOffsetHz : Long = 0L
    private var displayBwHz  : Long = sampleRateHz.toLong()

    // ─── Ring-buffer bitmap ───────────────────────────────────────────────────
    private var waterfallBitmap: Bitmap? = null
    private var writeRow    = 0
    private var pixelRow: IntArray? = null

    // ─── Speed throttle ───────────────────────────────────────────────────────
    private var maxLinesPerSecond   = 0
    private var lastLineTimestampMs = 0L

    // ─── Advanced display state ───────────────────────────────────────────────

    /** Pauses waterfall scrolling when true; incoming data is still accepted. */
    private var paused = false

    /**
     * Brightness offset applied before palette lookup.
     * Range −0.5..+0.5.  Positive = brighter (shifts normalised value up).
     */
    private var brightness = 0f

    /**
     * Contrast multiplier applied around the midpoint before palette lookup.
     * 1.0 = neutral; >1 = higher contrast (stretches the centre of the range).
     */
    private var contrast = 1.0f

    /**
     * When enabled, the engine tracks the running min/max of incoming spectra
     * and automatically adjusts dbMin/dbMax every [AUTO_STRETCH_WINDOW] lines.
     */
    private var autoStretch = false
    private var autoStretchMin  = Float.MAX_VALUE
    private var autoStretchMax  = Float.MIN_VALUE
    private var autoStretchLines = 0
    private val AUTO_STRETCH_WINDOW = 60   // update every N lines (~2 s at 30 l/s)

    /** Show elapsed-time tick marks on the right edge. */
    private var showTimestamp  = true
    /** Show a vertical centre-frequency marker. */
    private var showTunerMarker = true

    // Measured lines-per-second for the time ruler
    private var measuredLps = 0f       // lines per second, estimated from timestamps
    private var lpsAccumCount = 0
    private var lpsAccumStartMs = 0L
    private var totalLinesWritten = 0L

    // ─── Paints ───────────────────────────────────────────────────────────────
    private val bitmapPaint = Paint().apply {
        isAntiAlias    = false
        isFilterBitmap = true
        isDither       = false
    }
    private val srcRectUpper = RectF()
    private val dstRectUpper = RectF()
    private val srcRectLower = RectF()
    private val dstRectLower = RectF()
    private val drawMatrix   = Matrix()

    private val axisBgPaint = Paint().apply {
        color = 0xCC000000.toInt()
        style = Paint.Style.FILL
    }
    private val axisTextPaint = Paint().apply {
        color       = 0xCCFFFFFF.toInt()
        textSize    = 20f
        isAntiAlias = true
        textAlign   = Paint.Align.CENTER
    }
    private val axisTickPaint = Paint().apply {
        color       = 0x66FFFFFF
        strokeWidth = 1f
        style       = Paint.Style.STROKE
    }

    // Tuner marker (vertical line at centre frequency)
    private val tunerMarkerPaint = Paint().apply {
        color       = 0xCC00FFCC.toInt()
        strokeWidth = 1.5f
        style       = Paint.Style.STROKE
        isAntiAlias = true
        pathEffect  = DashPathEffect(floatArrayOf(6f, 4f), 0f)
    }
    private val tunerMarkerTextPaint = Paint().apply {
        color       = 0xFF00FFCC.toInt()
        textSize    = 18f
        isAntiAlias = true
        textAlign   = Paint.Align.CENTER
    }

    // Time ruler (right edge)
    private val timeRulerPaint = Paint().apply {
        color       = 0xAA000000.toInt()
        style       = Paint.Style.FILL
    }
    private val timeRulerTextPaint = Paint().apply {
        color       = 0xCCFFFFFF.toInt()
        textSize    = 16f
        isAntiAlias = true
        textAlign   = Paint.Align.RIGHT
    }
    private val timeTickPaint = Paint().apply {
        color       = 0x66FFFFFF
        strokeWidth = 1f
        style       = Paint.Style.STROKE
    }

    // ─── Gesture detectors ────────────────────────────────────────────────────
    // NOTE: Pinch-zoom and drag-pan are intentionally DISABLED on this view.
    // WaterfallView previously ran its own independent ScaleGestureDetector/
    // GestureDetector for zoom/pan (mirrored to SpectrumView via
    // onZoomPanChanged/setZoomPan best-effort callbacks). That let the
    // waterfall's zoom/pan state advance on its own between callback
    // round-trips, which could show up as the waterfall bitmap and the FFT
    // trace drifting to different widths/offsets ("stretching" out of line
    // with each other). SpectrumView is now the single source of truth for
    // zoom/pan: all pinch/drag/double-tap gestures are handled there, and
    // this view only ever receives zoomFactor/freqOffsetHz via
    // [setZoomPan], guaranteeing the waterfall always renders in lock-step
    // with the spectrum. onScale/onScroll/onDoubleTap below are retained
    // only as no-op stubs so this file's structure stays easy to diff
    // against the previous version; they are never wired to touch input.
    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean = false
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent,
                                  dx: Float, dy: Float): Boolean = false
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                onFrequencyClick?.invoke(pixelToHz(e.x)); return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean = false
        })

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Feeds one FFT spectrum frame into the waterfall.
     * Respects [paused]: when paused, data is accepted for auto-stretch tracking
     * but not written to the ring buffer.
     */
    fun addLine(spectrum: FloatArray) {
        if (maxLinesPerSecond > 0) {
            val now = System.currentTimeMillis()
            if (now - lastLineTimestampMs < 1000L / maxLinesPerSecond) return
            lastLineTimestampMs = now
        }

        // ── Auto-stretch: track min/max before any limiting ───────────────────
        if (autoStretch && spectrum.isNotEmpty()) {
            val sMin = spectrum.minOrNull() ?: dbMin
            val sMax = spectrum.maxOrNull() ?: dbMax
            if (sMin < autoStretchMin) autoStretchMin = sMin
            if (sMax > autoStretchMax) autoStretchMax = sMax
            autoStretchLines++
            if (autoStretchLines >= AUTO_STRETCH_WINDOW) {
                // Apply with a small margin
                val margin = (autoStretchMax - autoStretchMin) * 0.05f
                dbMin = autoStretchMin - margin
                dbMax = autoStretchMax + margin
                // Slow decay: drift back toward centre to handle changing conditions
                autoStretchMin += 0.5f
                autoStretchMax -= 0.5f
                autoStretchLines = 0
            }
        }

        if (paused) {
            // Even while paused: keep DebugBus alive so the panel doesn't go idle
            DebugBus.tick(DebugBus.STAGE_WATERFALL_VIEW)
            return
        }

        // ── Lines-per-second estimation for time ruler ────────────────────────
        totalLinesWritten++
        lpsAccumCount++
        val now = System.currentTimeMillis()
        if (lpsAccumStartMs == 0L) lpsAccumStartMs = now
        val elapsed = now - lpsAccumStartMs
        if (elapsed >= 2000L && lpsAccumCount > 0) {
            measuredLps = lpsAccumCount * 1000f / elapsed
            lpsAccumCount   = 0
            lpsAccumStartMs = now
        }

        DebugBus.tick(DebugBus.STAGE_WATERFALL_VIEW)
        val snap = DebugBus.snapshot()[DebugBus.STAGE_WATERFALL_VIEW]
        if (snap.status == DebugBus.StageStatus.IDLE) {
            DebugBus.setStatus(DebugBus.STAGE_WATERFALL_VIEW, DebugBus.StageStatus.OK,
                "${spectrum.size} bins  waiting for rate…")
        }
        if (snap.counter % 15L == 0L) {
            DebugBus.setDetail(DebugBus.STAGE_WATERFALL_VIEW,
                "${spectrum.size} bins  ${"%.1f".format(snap.ratePerSec)} lines/s")
        }

        val w = width
        val h = height
        DebugBus.setExtra(DebugBus.STAGE_WATERFALL_VIEW, DebugBus.EXTRA_WF_VIEW_WH, "${w}×${h}px")

        if (w <= 0 || h <= 0) {
            DebugBus.setExtra(DebugBus.STAGE_WATERFALL_VIEW, DebugBus.EXTRA_WF_BITMAP_WH,
                "⚠ NOT_CREATED — view size ${w}×${h}")
            // A 0×0 size right after construction is the normal "not laid out
            // yet" blip, not a fault -- only escalate to ERROR (and log it) once
            // it has persisted past the startup grace window.
            val withinStartupGrace = System.currentTimeMillis() - viewCreatedAtMs < ZERO_DIM_GRACE_MS
            if (!withinStartupGrace && snap.status != DebugBus.StageStatus.ERROR) {
                DebugBus.setStatus(DebugBus.STAGE_WATERFALL_VIEW, DebugBus.StageStatus.ERROR,
                    "View has zero dimensions (${w}×${h}) — cannot render")
            }
            return
        }
        val bitmapH = (h - AXIS_H).toInt()
        if (bitmapH <= 0) {
            DebugBus.setExtra(DebugBus.STAGE_WATERFALL_VIEW, DebugBus.EXTRA_WF_BITMAP_WH,
                "⚠ NOT_CREATED — bitmapH=${bitmapH}")
            if (snap.status != DebugBus.StageStatus.ERROR) {
                DebugBus.setStatus(DebugBus.STAGE_WATERFALL_VIEW, DebugBus.StageStatus.ERROR,
                    "Bitmap height = 0 (view h=${h}, axis=${AXIS_H}) — cannot render")
            }
            return
        }
        // Recovered: the view now has a real size and is about to render
        // successfully. Clear any earlier zero-dimension ERROR latch so a
        // resolved startup blip doesn't keep showing as an ongoing fault.
        if (snap.status == DebugBus.StageStatus.ERROR) {
            DebugBus.setStatus(DebugBus.STAGE_WATERFALL_VIEW, DebugBus.StageStatus.OK,
                "${spectrum.size} bins  recovered — view size ${w}×${h}")
        }
        DebugBus.setExtra(DebugBus.STAGE_WATERFALL_VIEW, DebugBus.EXTRA_WF_BITMAP_WH, "${w}×${bitmapH}px")
        ensureBitmap(w, bitmapH)

        val wfBitmap = waterfallBitmap ?: return
        val n        = spectrum.size
        lastBinCount = n
        val range    = (dbMax - dbMin).let { if (it < 1f) 1f else it }

        if (pixelRow == null || pixelRow!!.size != w) pixelRow = IntArray(w)
        val pixels = pixelRow!!

        writeRow = if (writeRow <= 0) bitmapH - 1 else writeRow - 1

        val nF = n.toFloat()
        val wF = w.toFloat()

        // Precompute brightness/contrast values for the row
        val br = brightness
        val co = contrast

        // Bin n/2 is where the FFT is actually centred: the REAL hardware/RF
        // tuner frequency (hwTunedFreqHz), not necessarily the dial/display
        // centre (centerFreqHz). In USB/LSB these differ by
        // DspEngine.SSB_BFO_OFFSET_HZ (see setHardwareTunedFrequency's doc
        // comment / the matching fix in SpectrumView.binToPixel).
        //
        // Without correction, pixel px = w/2 (screen/dial centre) shows bin
        // n/2, whose real frequency is hwTunedFreqHz -- not centerFreqHz (the
        // dial). To make pixel w/2 show the bin whose frequency actually
        // equals centerFreqHz, shift the bin lookup by MINUS the hardware
        // offset (in bin units): if the hardware is tuned hwOffsetHz above
        // the dial, the dial's own frequency lives hwOffsetHz *below* bin n/2,
        // i.e. at a *lower* bin index, so the shift here must be negative.
        val hwOffsetHz  = hwTunedFreqHz - centerFreqHz
        val hzPerBin    = sampleRateHz.toDouble() / nF

        // NOTE: this used to map px 0..w-1 straight onto bins 0..n-1 (plus
        // binShift), which implicitly assumes the row always shows the full
        // sample-rate span with no pan/zoom applied. That's exactly what
        // relHzToPixel/pixelToRelHz (used everywhere else in this view, and
        // by SpectrumView's binToPixel for the trace) do NOT assume -- those
        // honour displayBwHz/freqOffsetHz/tuningOffsetHz/ppmCorrection.
        // Because this loop skipped all of that, the bitmap was always
        // rendered as if unzoomed/unpanned, so as soon as the user panned or
        // zoomed the spectrum (freqOffsetHz != 0 or zoomFactor != 1), the
        // waterfall kept drawing the old unpanned slice while the spectrum
        // trace above it moved -- the two fell out of sync. Routing each
        // pixel through pixelToRelHz first makes the waterfall bitmap honour
        // the exact same pan/zoom state as the trace, keeping them locked
        // together.
        for (px in 0 until w) {
            val relHz = pixelToRelHz(px.toFloat() + 0.5f, wF)
            val binF  = ((relHz - hwOffsetHz) / hzPerBin + nF / 2.0 - 0.5).toFloat()
            val bin0 = binF.toInt().coerceIn(0, n - 1)
            val bin1 = (bin0 + 1).coerceIn(0, n - 1)
            val frac = (binF - bin0).coerceIn(0f, 1f)
            val db   = spectrum[bin0] * (1f - frac) + spectrum[bin1] * frac

            // Normalise to 0..1
            var norm = ((db - dbMin) / range).coerceIn(0f, 1f)

            // Apply brightness and contrast:  (norm - 0.5) * contrast + 0.5 + brightness
            norm = ((norm - 0.5f) * co + 0.5f + br).coerceIn(0f, 1f)

            pixels[px] = palette256[(norm * 255f).toInt().coerceIn(0, 255)]
        }

        wfBitmap.setPixels(pixels, 0, w, 0, writeRow, w, 1)
        postInvalidateOnAnimation()
    }

    fun setCenterFrequency(hz: Long) {
        val delta = hz - centerFreqHz
        centerFreqHz = hz
        // Must use the FFT bin count (lastBinCount), NOT the bitmap/view
        // pixel width -- see lastBinCount's doc comment. SpectrumView wraps
        // its own tuningOffsetHz using spectrumData.size (the same FFT bin
        // count), so both views need to agree on this unit or their
        // tuningOffsetHz values drift apart after repeated retunes,
        // desyncing the waterfall from the spectrum trace.
        val binHz = if (sampleRateHz > 0 && lastBinCount > 0)
            sampleRateHz.toDouble() / lastBinCount else 1.0
        tuningOffsetHz = (tuningOffsetHz + delta) % binHz
        postInvalidate()
    }

    /**
     * The real RF frequency the SDR hardware is tuned to right now (i.e. the
     * frequency the middle column of the waterfall bitmap actually
     * represents). In USB/LSB this is `dial + SSB_BFO_OFFSET_HZ` for *both*
     * sidebands (symmetric hardware shift -- see
     * DspEngine.setCarrierFrequency), which is NOT the same value
     * [setCenterFrequency] receives for LSB (that one intentionally flips
     * sign so the passband/dial-marker overlays land in the right place --
     * see MainActivity.hardwareCenterFreqHz's doc comment).
     *
     * onDraw()'s bitmap crop and drawTunerMarker() use this value -- not
     * [centerFreqHz] -- to locate the real signal in the bitmap, so the
     * waterfall stays correctly positioned relative to those overlays even
     * when the two frequencies disagree.
     */
    fun setHardwareTunedFrequency(hz: Long) {
        if (hz == hwTunedFreqHz) return
        hwTunedFreqHz = hz
        postInvalidate()
    }

    /**
     * Applies a zoom/pan state mirrored from the other view (SpectrumView), without
     * re-firing [onZoomPanChanged] back at it -- see [suppressZoomPanCallback].
     */
    fun setZoomPan(zoom: Float, offsetHz: Long) {
        val clampedZoom = zoom.coerceIn(1f, 32f)
        val clampedOffset = offsetHz.coerceIn(-sampleRateHz / 2L, sampleRateHz / 2L)
        if (clampedZoom == zoomFactor && clampedOffset == freqOffsetHz) return
        suppressZoomPanCallback = true
        zoomFactor = clampedZoom
        displayBwHz = (sampleRateHz / zoomFactor).toLong()
        freqOffsetHz = clampedOffset
        suppressZoomPanCallback = false
        postInvalidate()
    }

    fun setPpmCorrection(ppm: Int) { ppmCorrection = ppm; postInvalidate() }

    fun setSampleRate(rate: Int) {
        sampleRateHz   = rate
        displayBwHz    = (rate / zoomFactor).toLong()
        tuningOffsetHz = 0.0
        postInvalidate()
    }

    fun setPalette(p: Palette) {
        palette256 = buildPalette(p)
        // Redraw the bitmap with the new palette? We can't easily recolour historical
        // data without storing the original float values, so just clear and start fresh.
        clearHistory()
    }

    fun setDynamicRange(minDb: Float, maxDb: Float) {
        if (minDb < maxDb) { dbMin = minDb; dbMax = maxDb }
    }

    fun setSpeed(linesPerSecond: Int) {
        maxLinesPerSecond   = linesPerSecond.coerceIn(0, 120)
        lastLineTimestampMs = 0L
    }

    fun clearHistory() {
        waterfallBitmap?.eraseColor(Color.BLACK)
        writeRow = 0
        totalLinesWritten = 0
        postInvalidate()
    }

    // ── New advanced API ──────────────────────────────────────────────────────

    /**
     * Pause or resume waterfall scrolling.
     * While paused, [addLine] data is accepted for auto-stretch tracking
     * but not rendered.
     */
    fun setPaused(pause: Boolean) { paused = pause }

    /**
     * Brightness offset applied to each pixel before palette lookup.
     * Positive values brighten (shift normalised value up); range −0.5..0.5.
     */
    fun setBrightness(value: Float) {
        brightness = value.coerceIn(-0.5f, 0.5f)
    }

    /**
     * Contrast multiplier.  Values > 1 increase contrast (stretch the centre
     * of the dynamic range); values < 1 reduce contrast.  Range 0.1..5.0.
     */
    fun setContrast(value: Float) {
        contrast = value.coerceIn(0.1f, 5.0f)
    }

    /**
     * Enable automatic dynamic range adjustment.
     * When enabled, [setDynamicRange] calls are overridden by the running
     * min/max tracked from incoming spectra.
     */
    fun setAutoStretch(enable: Boolean) {
        autoStretch = enable
        if (enable) {
            autoStretchMin  = Float.MAX_VALUE
            autoStretchMax  = Float.MIN_VALUE
            autoStretchLines = 0
        }
    }

    /** Show or hide the elapsed-time ruler on the right edge. */
    fun setShowTimestamp(show: Boolean) { showTimestamp = show; postInvalidate() }

    /** Show or hide the centre-frequency marker line. */
    fun setShowTunerMarker(show: Boolean) { showTunerMarker = show; postInvalidate() }

    // ─── Drawing ──────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val wfBitmap = waterfallBitmap ?: run { canvas.drawColor(Color.BLACK); return }
        val sw = width.toFloat()
        val sh = height.toFloat()
        val bw = wfBitmap.width
        val bh = wfBitmap.height

        // The bitmap is always written edge-to-edge across its full width
        // (see addLine(): each row already maps px 0..w-1 through
        // pixelToRelHz -- the same pan/zoom/ppm/tuningOffset-aware helper
        // SpectrumView's trace uses -- to the bin it should show), so it's
        // always drawn edge-to-edge here too -- no cropping, no stretching,
        // no clamped src/dst rects, and therefore no gap at either screen
        // edge in any mode. The pan/zoom correction happens per-pixel while
        // the row is filled, not here, so this blit stays a simple 1:1
        // scale-to-fit in every mode. Those correction terms only affect
        // axis labels beyond that (see
        // drawFrequencyAxis/drawTunerMarker below and SpectrumView's
        // pixelToFrequency), never where data is actually drawn.
        val waterfallBottom = sh - AXIS_H
        val upperRows = bh - writeRow

        if (upperRows > 0) {
            srcRectUpper.set(0f, writeRow.toFloat(), bw.toFloat(), bh.toFloat())
            dstRectUpper.set(0f, 0f, sw, upperRows.toFloat())
            drawMatrix.setRectToRect(srcRectUpper, dstRectUpper, Matrix.ScaleToFit.FILL)
            canvas.drawBitmap(wfBitmap, drawMatrix, bitmapPaint)
        }
        if (writeRow > 0) {
            srcRectLower.set(0f, 0f, bw.toFloat(), writeRow.toFloat())
            dstRectLower.set(0f, upperRows.toFloat(), sw, waterfallBottom)
            drawMatrix.setRectToRect(srcRectLower, dstRectLower, Matrix.ScaleToFit.FILL)
            canvas.drawBitmap(wfBitmap, drawMatrix, bitmapPaint)
        }

        if (showTunerMarker)            drawTunerMarker(canvas, sw, sh)
        if (showTimestamp && measuredLps > 0f) drawTimeRuler(canvas, sw, sh)
        drawFrequencyAxis(canvas, sw, sh)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        waterfallBitmap?.recycle()
        waterfallBitmap = null
        pixelRow        = null
        val bitmapH = (h - AXIS_H).toInt()
        if (w > 0 && bitmapH > 0) ensureBitmap(w, bitmapH)
    }

    private fun ensureBitmap(w: Int, bitmapH: Int) {
        if (waterfallBitmap == null ||
            waterfallBitmap!!.width  != w ||
            waterfallBitmap!!.height != bitmapH) {
            waterfallBitmap?.recycle()
            waterfallBitmap = Bitmap.createBitmap(w, bitmapH, Bitmap.Config.ARGB_8888)
            waterfallBitmap!!.eraseColor(Color.BLACK)
            writeRow = 0
        }
    }

    // ─── Overlay layers ───────────────────────────────────────────────────────

    /**
     * Dashed vertical line at the tuned/dialed frequency -- the same
     * reference every other overlay (passband box, axis, bookmarks) is
     * drawn relative to (see MainActivity.hardwareCenterFreqHz). Always
     * sits at screen centre (relHz = 0) by construction.
     *
     * This intentionally does NOT track hwTunedFreqHz (the real RF/BFO-
     * shifted hardware carrier). In USB/LSB those two differ by
     * SSB_BFO_OFFSET_HZ, and drawing the marker at the hardware carrier
     * instead of the dial pulled it visibly off-centre -- 1.5 kHz in each
     * sideband, so 3 kHz apart between LSB and USB -- even though every
     * other overlay was correctly centred on the dial. The marker's job is
     * to show the user's tuned frequency, not the internal BFO offset, so
     * it belongs at centerFreqHz (the dial) like everything else.
     */
    private fun drawTunerMarker(canvas: Canvas, sw: Float, sh: Float) {
        val ppmHz  = centerFreqHz.toDouble() * ppmCorrection / 1_000_000.0
        val dialCenterHz = centerFreqHz + Math.round(ppmHz)
        val x = relHzToPixel(0.0, sw)
        if (x < 0f || x > sw) return
        val waterfallBottom = sh - AXIS_H

        canvas.drawLine(x, 0f, x, waterfallBottom, tunerMarkerPaint)

        // Small label at top
        val label = formatHz(dialCenterHz)
        canvas.drawText(label, x, tunerMarkerTextPaint.textSize + 4f, tunerMarkerTextPaint)
    }

    /**
     * Elapsed-time ruler on the right edge of the waterfall.
     * Shows how many seconds ago each visible row was written.
     */
    private fun drawTimeRuler(canvas: Canvas, sw: Float, sh: Float) {
        val waterfallH  = sh - AXIS_H
        if (waterfallH <= 0f) return
        val lps         = measuredLps                          // lines per second
        if (lps <= 0f) return
        val secondsPerPx = 1f / (lps * (waterfallH / (waterfallBitmap?.height?.toFloat() ?: waterfallH)))

        val rulerW = 36f
        canvas.drawRect(sw - rulerW, 0f, sw, waterfallH, timeRulerPaint)

        val totalSecs = (waterfallH * secondsPerPx).toInt()
        val step = when {
            totalSecs < 10  -> 1
            totalSecs < 30  -> 5
            totalSecs < 120 -> 10
            totalSecs < 600 -> 30
            else            -> 60
        }

        var t = 0
        while (t <= totalSecs) {
            val y = (t / secondsPerPx).coerceIn(0f, waterfallH - 1f)
            canvas.drawLine(sw - rulerW, y, sw - 4f, y, timeTickPaint)
            val label = if (t < 60) "${t}s" else "${t / 60}m"
            canvas.drawText(label, sw - 6f, y + timeRulerTextPaint.textSize / 3f, timeRulerTextPaint)
            t += step
        }
    }

    // ─── Frequency axis ───────────────────────────────────────────────────────

    private fun drawFrequencyAxis(canvas: Canvas, sw: Float, sh: Float) {
        val axisTop = sh - AXIS_H
        canvas.drawRect(0f, axisTop, sw, sh, axisBgPaint)

        val divisions = 5
        for (i in 0..divisions) {
            val frac  = i.toDouble() / divisions
            val x     = (sw * frac).toFloat()
            val hz    = centerFreqHz + Math.round(pixelToRelHz(x, sw))
            val label = formatHz(hz)
            canvas.drawLine(x, axisTop, x, axisTop + 5f, axisTickPaint)
            val halfLw = axisTextPaint.measureText(label) / 2f + 2f
            val drawX  = x.coerceIn(halfLw, sw - halfLw)
            canvas.drawText(label, drawX, sh - 4f, axisTextPaint)
        }
    }

    // ─── Coordinate helpers ───────────────────────────────────────────────────
    // Single source of truth for every frequency<->pixel conversion in this
    // view -- mirrors SpectrumView.kt's relHzToPixel/pixelToRelHz exactly, so
    // the two views (and the passband box drawn on top of the spectrum) can
    // never disagree about where a given raw bin/frequency belongs on screen.
    //
    // Previously this view had its OWN set of inconsistencies: the bitmap
    // crop (visLeftHz/visRightHz below) applied tuningOffsetHz with the
    // OPPOSITE sign from SpectrumView's trace, and didn't apply ppmCorrection
    // at all, while drawFrequencyAxis/drawTunerMarker/pixelToHz applied
    // ppmCorrection but not tuningOffsetHz. Since tuningOffsetHz can be as
    // large as one FFT bin (sampleRateHz / FFT size), the net effect was a
    // waterfall image visibly offset from its own axis, from the spectrum
    // trace above it, and from the passband box.
    private fun relHzToPixel(relHz: Double, sw: Float): Float {
        val ppmHz = centerFreqHz.toDouble() * ppmCorrection / 1_000_000.0
        val adjRelHz = relHz - ppmHz - freqOffsetHz - tuningOffsetHz
        return (sw / 2.0 + adjRelHz / displayBwHz * sw).toFloat()
    }

    private fun pixelToRelHz(x: Float, sw: Float): Double {
        val ppmHz = centerFreqHz.toDouble() * ppmCorrection / 1_000_000.0
        return (x / sw - 0.5) * displayBwHz + ppmHz + freqOffsetHz + tuningOffsetHz
    }

    private fun pixelToHz(px: Float): Long {
        return centerFreqHz + Math.round(pixelToRelHz(px, width.toFloat()))
    }

    private fun formatHz(hz: Long) = when {
        abs(hz) >= 1_000_000_000L -> "${"%.2f".format(hz / 1e9)}G"
        abs(hz) >= 1_000_000L    -> "${"%.3f".format(hz / 1e6)}M"
        abs(hz) >= 1_000L        -> "${"%.1f".format(hz / 1e3)}k"
        else                      -> "${hz} Hz"
    }

    // ─── Touch ────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // scaleDetector is intentionally not fed touch events: zoom is
        // driven exclusively by SpectrumView (see the "Gesture detectors"
        // section above), so the waterfall can never zoom itself out of
        // line with the spectrum.
        gestureDetector.onTouchEvent(event)
        return true
    }

    // ─── Colour palette LUTs (256 entries each) ───────────────────────────────

    private fun buildPalette(p: Palette): IntArray {
        val lut = IntArray(256)
        for (i in 0..255) {
            val t = i / 255f
            lut[i] = when (p) {
                Palette.RAINBOW       -> rainbow(t)
                Palette.HEAT          -> heat(t)
                Palette.GRAYSCALE     -> { val v = (t * 255).toInt(); Color.rgb(v, v, v) }
                Palette.BLUE_WHITE    -> blueWhite(t)
                Palette.PURPLE_YELLOW -> purpleYellow(t)
                // ── New scientific palettes ──────────────────────────────────
                Palette.VIRIDIS       -> viridis(t)
                Palette.INFERNO       -> inferno(t)
                Palette.MAGMA         -> magma(t)
                Palette.TURBO         -> turbo(t)
                Palette.SOLAR         -> solar(t)
                Palette.NIGHT_VISION  -> nightVision(t)
            }
        }
        return lut
    }

    // ── Original palettes ─────────────────────────────────────────────────────

    private fun rainbow(t: Float): Int =
        Color.HSVToColor(floatArrayOf(
            (1f - t) * 240f, 1f,
            if (t < 0.05f) t * 20f else 1f
        ))

    private fun heat(t: Float): Int {
        val r = (t * 3f).coerceIn(0f, 1f)
        val g = (t * 3f - 1f).coerceIn(0f, 1f)
        val b = (t * 3f - 2f).coerceIn(0f, 1f)
        return Color.rgb((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
    }

    private fun blueWhite(t: Float): Int {
        val rg = (t * 2f - 1f).coerceIn(0f, 1f)
        return Color.rgb((rg * 255).toInt(), (rg * 255).toInt(), (t * 255).toInt())
    }

    private fun purpleYellow(t: Float): Int {
        val r = if (t < 0.5f) t * 2f else 1f
        val g = if (t < 0.5f) 0f    else (t - 0.5f) * 2f
        val b = if (t < 0.5f) 1f - t * 2f else 0f
        return Color.rgb((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
    }

    // ── New scientific palettes ───────────────────────────────────────────────

    /**
     * Piecewise linear interpolation through key-colour stops.
     * [ts] are the normalised positions (0..1); [rs]/[gs]/[bs] are the RGB
     * values (0..255) at each stop.  Stops must be sorted in ascending t order.
     */
    private fun piecewise(t: Float, ts: FloatArray, rs: IntArray, gs: IntArray, bs: IntArray): Int {
        val n = ts.size
        if (t <= ts[0])     return Color.rgb(rs[0],     gs[0],     bs[0])
        if (t >= ts[n - 1]) return Color.rgb(rs[n - 1], gs[n - 1], bs[n - 1])
        for (i in 1 until n) {
            if (t <= ts[i]) {
                val f  = (t - ts[i - 1]) / (ts[i] - ts[i - 1])
                val r  = (rs[i - 1] + f * (rs[i] - rs[i - 1]) + 0.5f).toInt()
                val g  = (gs[i - 1] + f * (gs[i] - gs[i - 1]) + 0.5f).toInt()
                val b  = (bs[i - 1] + f * (bs[i] - bs[i - 1]) + 0.5f).toInt()
                return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
            }
        }
        return Color.rgb(rs[n - 1], gs[n - 1], bs[n - 1])
    }

    /** Viridis: perceptually uniform, colour-blind friendly. */
    private fun viridis(t: Float) = piecewise(t,
        floatArrayOf(0f,   0.25f,  0.5f,   0.75f,  1f),
        intArrayOf  (68,   58,     32,     94,     253),
        intArrayOf  (1,    82,     144,    201,    231),
        intArrayOf  (84,   139,    141,    98,     37))

    /** Inferno: high-contrast dark-to-pale-yellow. */
    private fun inferno(t: Float) = piecewise(t,
        floatArrayOf(0f,   0.25f,  0.5f,   0.75f,  1f),
        intArrayOf  (0,    87,     188,    250,    252),
        intArrayOf  (0,    15,     55,     143,    255),
        intArrayOf  (4,    109,    84,     21,     164))

    /** Magma: dark perceptual from black to cream. */
    private fun magma(t: Float) = piecewise(t,
        floatArrayOf(0f,   0.25f,  0.5f,   0.75f,  1f),
        intArrayOf  (0,    81,     183,    252,    252),
        intArrayOf  (0,    18,     55,     137,    253),
        intArrayOf  (4,    124,    121,    97,     191))

    /** Turbo: vivid full-spectrum rainbow for high dynamic range. */
    private fun turbo(t: Float) = piecewise(t,
        floatArrayOf(0f,    0.166f, 0.333f, 0.5f,   0.666f, 0.833f, 1f),
        intArrayOf  (48,    50,     17,     58,     212,    246,    114),
        intArrayOf  (18,    92,     199,    250,    241,    119,    1),
        intArrayOf  (59,    192,    222,    109,    19,     14,     5))

    /** Solar: black → deep orange → bright yellow, good for warm-tone displays. */
    private fun solar(t: Float) = piecewise(t,
        floatArrayOf(0f,   0.4f,   0.75f,  1f),
        intArrayOf  (0,    180,    255,    255),
        intArrayOf  (0,    50,     200,    245),
        intArrayOf  (0,    0,      0,      50))

    /** Night-vision green: phosphor-green monochrome for dark environments. */
    private fun nightVision(t: Float): Int {
        val brightness = t.coerceIn(0f, 1f)
        val r = (brightness * 20).toInt().coerceIn(0, 255)
        val g = (brightness * 255).toInt().coerceIn(0, 255)
        val b = (brightness * 30).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun Float.roundToIntSafe(): Int = (this + 0.5f).toInt()
}
