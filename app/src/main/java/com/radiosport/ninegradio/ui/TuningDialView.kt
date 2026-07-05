package com.radiosport.ninegradio.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.radiosport.ninegradio.dsp.FrequencyStepManager
import kotlin.math.*

/**
 * Horizontal "3D film-strip" tuning dial with protocol-aware intelligent step
 * sizing.
 *
 * Visually this renders as a horizontal drum/cylinder of frequency-step
 * tick marks viewed slightly from above: ticks near the centre are tall,
 * bright and full-width; ticks toward the left/right edges shrink, dim and
 * compress horizontally (a cheap per-tick perspective transform) to suggest
 * the drum curving away from the viewer — like an analogue radio tuning
 * scale wrapped around a cylinder.
 *
 * Tuning is swipe left/right ONLY:
 *  - Swipe left  → frequency decreases (drum rotates so higher ticks slide
 *                  in from the right)
 *  - Swipe right → frequency increases
 *  - Fling continues the rotation with friction-based deceleration
 *  - Long press toggles fine/coarse step mode (unchanged)
 *
 * All public API is unchanged from the previous rotary implementation so
 * existing wiring (MainActivity, ControlsTabManager) continues to work
 * without modification:
 *  - onStep: ((deltaHz: Long) -> Unit)?
 *  - demodMode: String
 *  - currentFreqHz: Long
 *  - fineMode: Boolean
 *  - overrideStepHz: Long?
 *  - onFineModeChanged: ((Boolean) -> Unit)?
 *  - activeStepHzPublic(): Long
 *  - setOnFineModeChangedListener(l)
 *  - syncStep()
 */
class TuningDialView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Public API ────────────────────────────────────────────────────────────

    /** Called when the dial produces a frequency change. */
    var onStep: ((deltaHz: Long) -> Unit)? = null

    /** Current demod mode string — set from MainActivity whenever mode changes. */
    var demodMode: String = "NFM"
        set(v) { field = v; computeStep(); invalidate() }

    /** Current frequency — used for band-aware step selection. */
    var currentFreqHz: Long = 100_000_000L
        set(v) { field = v; computeStep(); invalidate() }

    /** Fine-tune mode: step is 10× smaller than protocol step. */
    var fineMode: Boolean = false
        set(v) { field = v; computeStep(); onFineModeChanged?.invoke(v); invalidate() }

    /** Optional manual step override set by step-size chip selection. Cleared on mode change. */
    var overrideStepHz: Long? = null
        set(v) { field = v; computeStep(); invalidate() }

    /** Called when [fineMode] toggles via long-press gesture. */
    var onFineModeChanged: ((Boolean) -> Unit)? = null

    /** Expose the computed step for use in snap-to-channel logic. */
    fun activeStepHzPublic(): Long = activeStepHz

    fun setOnFineModeChangedListener(l: (Boolean) -> Unit) { onFineModeChanged = l }

    // ── Internal state ────────────────────────────────────────────────────────

    // Horizontal drum offset, in pixels, accumulated from swipe deltas.
    private var drumOffsetPx: Float = 0f
    private var pxPerStep: Float = 48f         // pixels of horizontal drag = 1 step
    private var accumulatedPx: Float = 0f      // fractional pixels since last step
    private var activeStepHz: Long = 100_000L  // actual Hz per step (recomputed)
    private var protocolStepHz: Long = 100_000L

    // ── Paint ─────────────────────────────────────────────────────────────────

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF0D1117.toInt()
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        shader = null  // set in onSizeChanged — vertical gradient for the "cylinder" body
    }
    private val trackRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = 0xFF1A3A4A.toInt()
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFF334455.toInt()
    }
    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0xFF00AACC.toInt()
    }
    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFF4444.toInt()
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
        color = 0xFFAABBCC.toInt()
    }
    private val stepLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
        color = 0xFF00CCFF.toInt()
        isFakeBoldText = true
    }
    private val fineLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
        color = 0xFFFFAA00.toInt()
        isFakeBoldText = true
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL)
        color = 0x4400CCFF
    }
    private val edgeFadePaint = Paint().apply {
        style = Paint.Style.FILL
    }
    private val centrePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0xFFFF4444.toInt()
        alpha = 200
    }

    // ── Geometry ──────────────────────────────────────────────────────────────
    private var cx = 0f; private var cy = 0f
    private var trackLeft = 0f; private var trackRight = 0f
    private var trackTop = 0f; private var trackBottom = 0f
    private var trackW = 0f; private var trackH = 0f

    // ── Gesture ───────────────────────────────────────────────────────────────
    private var velocityX: Float = 0f
    private var isFling = false

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                fineMode = !fineMode
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent,
                                  distanceX: Float, distanceY: Float): Boolean {
                // Horizontal-swipe only: vertical motion (distanceY) is
                // ignored entirely so this view never fights a parent
                // ScrollView for vertical drags.
                roll(distanceX)
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent,
                                 velocityX: Float, velocityY: Float): Boolean {
                this@TuningDialView.velocityX = velocityX
                isFling = true
                postFling()
                return true
            }
        })

    // ── Init ──────────────────────────────────────────────────────────────────
    init { computeStep() }

    // ── Public helpers ────────────────────────────────────────────────────────

    /** Called to sync the step label without triggering a frequency change. */
    fun syncStep() { computeStep(); invalidate() }

    // ── Step logic ────────────────────────────────────────────────────────────

    private fun computeStep() {
        protocolStepHz = overrideStepHz
            ?: FrequencyStepManager.recommendedStep(currentFreqHz, demodMode).hz
        activeStepHz   = if (fineMode) maxOf(1L, protocolStepHz / 10) else protocolStepHz
    }

    /**
     * Roll the drum horizontally by [dx] pixels (from GestureDetector's
     * onScroll distanceX, where positive = finger moved left).
     *
     * A left swipe (finger moves left, distanceX > 0) rolls the drum so that
     * higher-frequency ticks slide into view from the right — i.e. frequency
     * increases. This matches the natural "drag the scale to bring the next
     * value into view" gesture for a horizontal tuning strip.
     */
    private fun roll(dx: Float) {
        drumOffsetPx += dx
        accumulatedPx += dx

        val stepsRaw = (accumulatedPx / pxPerStep).toInt()
        if (stepsRaw != 0) {
            accumulatedPx -= stepsRaw * pxPerStep
            val delta = stepsRaw * activeStepHz
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onStep?.invoke(delta)
        }
        invalidate()
    }

    private fun postFling() {
        if (!isFling) return
        velocityX *= 0.85f
        if (abs(velocityX) < 50f) { isFling = false; return }
        // onScroll's distanceX has the opposite sign convention to fling
        // velocity (a fast rightward fling has positive velocityX but should
        // behave like a negative distanceX), so negate here.
        roll(-velocityX * 0.016f)
        postDelayed({ postFling() }, 16)
    }

    // ── Size ──────────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f; cy = h / 2f

        // The "drum" track occupies the full width with small side margins,
        // and a band in the vertical centre of the view.
        val marginX = w * 0.02f
        trackLeft = marginX
        trackRight = w - marginX
        trackTop = h * 0.18f
        trackBottom = h * 0.74f
        trackW = trackRight - trackLeft
        trackH = trackBottom - trackTop

        // Vertical gradient across the track gives the drum body a curved,
        // cylindrical "3D" highlight: bright band near the top-third (where
        // a light source would catch a horizontal cylinder), darker at the
        // extremes.
        trackPaint.shader = LinearGradient(
            0f, trackTop, 0f, trackBottom,
            intArrayOf(
                0xFF101C2C.toInt(),
                0xFF24405C.toInt(),
                0xFF152230.toInt(),
                0xFF0A1018.toInt()
            ),
            floatArrayOf(0f, 0.32f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )

        // pxPerStep scales with width so the gesture feel is consistent
        // across phone/tablet sizes — roughly 1 step per 6% of view width.
        pxPerStep = (w * 0.06f).coerceIn(28f, 80f)
    }

    // ── Draw ──────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()

        // Background
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Glow shadow behind the drum
        canvas.drawRoundRect(
            trackLeft - 6f, trackTop - 6f, trackRight + 6f, trackBottom + 6f,
            trackH * 0.18f, trackH * 0.18f, shadowPaint
        )

        // Drum body
        canvas.drawRoundRect(
            trackLeft, trackTop, trackRight, trackBottom,
            trackH * 0.16f, trackH * 0.16f, trackPaint
        )
        canvas.drawRoundRect(
            trackLeft, trackTop, trackRight, trackBottom,
            trackH * 0.16f, trackH * 0.16f, trackRimPaint
        )

        // Clip tick rendering to the drum body so perspective-scaled ticks
        // never spill outside the rounded rect.
        canvas.save()
        canvas.clipRect(trackLeft, trackTop, trackRight, trackBottom)

        // Tick marks: an evenly-spaced horizontal scale, scrolled by
        // drumOffsetPx and rendered with a per-tick perspective transform so
        // ticks near the centre appear tall/full-width/bright and ticks near
        // the left/right edges appear short/compressed/dim — the visual
        // illusion of a cylinder curving away from the viewer.
        val tickSpacingPx = pxPerStep
        val totalTicks = (trackW / tickSpacingPx).toInt() + 4
        val halfW = trackW / 2f

        // Normalise drumOffsetPx into [0, tickSpacingPx) for the base phase,
        // then iterate a window of ticks either side of centre.
        val phase = drumOffsetPx % tickSpacingPx
        val startIndex = -(totalTicks / 2) - 1
        for (i in startIndex until startIndex + totalTicks + 2) {
            // Base x position of this tick if the drum were flat (no perspective)
            val flatX = cx + i * tickSpacingPx - phase

            // Distance from centre, normalised to [-1, 1] across the half-width
            // — used as the perspective parameter "u".
            val u = ((flatX - cx) / halfW)

            // Perspective scale: 1.0 at centre, shrinking toward the edges.
            // A gentle cosine falloff gives a smooth cylindrical curve.
            val scale = cos(u.coerceIn(-1f, 1f) * (PI / 2.0).toFloat() * 0.92f)
                .coerceIn(0.18f, 1f)

            // Horizontal compression toward the edges: ticks bunch together
            // as they approach the rim of the drum, like a real cylinder.
            val perspectiveX = cx + (flatX - cx) * (0.55f + 0.45f * scale)

            if (perspectiveX < trackLeft - tickSpacingPx ||
                perspectiveX > trackRight + tickSpacingPx) continue

            // Which step index does this tick represent, relative to the
            // centre (pointer) tick? Centre tick = step 0.
            val isMajor = ((i % 5) + 5) % 5 == 0

            val tickH = trackH * (0.15f + 0.55f * scale) * (if (isMajor) 1f else 0.6f)
            val alpha = (60 + (195 * scale)).toInt().coerceIn(40, 255)

            val paint = if (isMajor) majorTickPaint else tickPaint
            paint.alpha = alpha

            val midY = (trackTop + trackBottom) / 2f
            canvas.drawLine(
                perspectiveX, midY - tickH / 2f,
                perspectiveX, midY + tickH / 2f,
                paint
            )

            // Frequency label under major ticks near the centre, fading out
            // toward the edges along with the tick itself.
            if (isMajor && scale > 0.55f) {
                val labelHz = currentFreqHz + i.toLong() * activeStepHz
                labelPaint.alpha = alpha
                labelPaint.textSize = (trackH * 0.16f * scale).coerceAtLeast(1f)
                canvas.drawText(
                    formatHz(labelHz), perspectiveX, trackBottom - trackH * 0.06f, labelPaint
                )
            }
        }
        // Restore full alpha on shared paints so other draw calls aren't affected
        majorTickPaint.alpha = 255
        tickPaint.alpha = 255
        labelPaint.alpha = 255

        canvas.restore()

        // Centre pointer: a downward triangle above the drum, indicating the
        // currently-tuned frequency sits at the centre tick.
        val pSize = trackH * 0.16f
        val pPath = Path().apply {
            moveTo(cx, trackTop - 2f)
            lineTo(cx - pSize * 0.6f, trackTop - pSize * 1.4f)
            lineTo(cx + pSize * 0.6f, trackTop - pSize * 1.4f)
            close()
        }
        canvas.drawPath(pPath, pointerPaint)

        // Centre highlight line through the drum, marking the active frequency.
        canvas.drawLine(cx, trackTop + 2f, cx, trackBottom - 2f, centrePaint)

        // Left/right edge fade overlays — reinforce the "wrapping cylinder"
        // illusion by darkening toward the extremes of the drum.
        edgeFadePaint.shader = LinearGradient(
            trackLeft, 0f, trackLeft + trackW * 0.18f, 0f,
            intArrayOf(0xFF0D1117.toInt(), 0x000D1117),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(trackLeft, trackTop, trackLeft + trackW * 0.18f, trackBottom, edgeFadePaint)
        edgeFadePaint.shader = LinearGradient(
            trackRight - trackW * 0.18f, 0f, trackRight, 0f,
            intArrayOf(0x000D1117, 0xFF0D1117.toInt()),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(trackRight - trackW * 0.18f, trackTop, trackRight, trackBottom, edgeFadePaint)

        // Step label below the drum
        val stepStr = formatHz(activeStepHz)
        stepLabelPaint.textSize = h * 0.075f
        canvas.drawText("STEP $stepStr", cx, trackBottom + h * 0.10f, stepLabelPaint)

        // Fine / Coarse label at bottom of view
        val modeLabel = if (fineMode) "⊙ FINE  (hold to toggle)" else "◎ COARSE  (hold to toggle)"
        val modePaint = if (fineMode) fineLabelPaint else stepLabelPaint
        modePaint.textSize = h * 0.055f
        canvas.drawText(modeLabel, cx, h - 6f, modePaint)

        // Mode / band label at top
        val bandLabel = FrequencyStepManager.bestPreset(currentFreqHz)?.name ?: demodMode
        labelPaint.textSize = h * 0.06f
        labelPaint.color = 0xFF445566.toInt()
        canvas.drawText(bandLabel, cx, labelPaint.textSize + 2f, labelPaint)
        labelPaint.color = 0xFFAABBCC.toInt()

        // Swipe hint arrows either side of centre, reinforcing the
        // horizontal-only gesture.
        labelPaint.textSize = h * 0.07f
        labelPaint.color = 0xFF445566.toInt()
        val arrowY = (trackTop + trackBottom) / 2f + labelPaint.textSize * 0.35f
        canvas.drawText("◀", trackLeft + trackW * 0.05f, arrowY, labelPaint)
        canvas.drawText("▶", trackRight - trackW * 0.05f, arrowY, labelPaint)
        labelPaint.color = 0xFFAABBCC.toInt()
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isFling = false
                // The dial lives inside vertically-scrolling containers
                // (ScrollView / NestedScrollView). Claim the gesture stream so
                // a horizontal swipe on the dial is never stolen by the parent
                // as a page scroll. Vertical drags are ignored by roll()
                // entirely (only distanceX is used), so releasing the claim on
                // UP/CANCEL lets the parent resume normal vertical scrolling
                // immediately afterwards.
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        gestureDetector.onTouchEvent(event)
        return true
    }

    // ── Measure ───────────────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = context.resources.displayMetrics.density
        // Horizontal strip: wide and short, unlike the previous square dial.
        val desiredW = (320 * density).toInt()
        val desiredH = (140 * density).toInt()
        val w = resolveSize(desiredW, widthMeasureSpec)
        val h = resolveSize(desiredH, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun formatHz(hz: Long): String = when {
        hz >= 1_000_000L -> "%.3g MHz".format(hz / 1_000_000.0)
        hz >= 1_000L     -> "%.3g kHz".format(hz / 1_000.0)
        else             -> "$hz Hz"
    }
}
