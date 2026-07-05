package com.radiosport.ninegradio.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/**
 * Large frequency readout with:
 *  - GHz / MHz / kHz / Hz digit groups
 *  - Tap a digit group to select it
 *  - Scroll on selected group to increment/decrement
 *  - Step-size indicator
 *  - Programmatic setFrequency / getFrequency
 */
class FrequencyView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onFrequencyChanged: ((Long) -> Unit)? = null

    /** Invoked when the user long-presses any digit group — use this to show a manual-entry dialog. */
    var onLongPress: ((currentHz: Long) -> Unit)? = null

    private var frequencyHz: Long = 100_000_000L   // 100 MHz default
    private var selectedGroup = 1  // 0=GHz, 1=MHz, 2=kHz, 3=Hz

    private val bgPaint = Paint().apply {
        color = 0xFF0D1117.toInt()
        style = Paint.Style.FILL
    }
    private val digitPaint = Paint().apply {
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    private val separatorPaint = Paint().apply {
        color = 0xFF444444.toInt()
        strokeWidth = 1f
    }
    private val selectionPaint = Paint().apply {
        color = 0x3300CCFF
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint().apply {
        color = 0xFF666688.toInt()
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                selectGroupAt(e.x); return true
            }
            override fun onLongPress(e: MotionEvent) {
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                onLongPress?.invoke(frequencyHz)
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                val step = groupStep(selectedGroup)
                val delta = if (dy > 0) step else -step
                setFrequency((frequencyHz + delta).coerceIn(500_000L, 1_766_000_000L))
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val step = groupStep(selectedGroup)
                val ticks = (vy / 200).toInt().coerceIn(-20, 20)
                setFrequency((frequencyHz + step * ticks).coerceIn(500_000L, 1_766_000_000L))
                return true
            }
        })

    // ─── Public API ───────────────────────────────────────────────────────────

    fun setFrequency(hz: Long) {
        frequencyHz = hz
        invalidate()
        onFrequencyChanged?.invoke(hz)
    }

    fun getFrequency(): Long = frequencyHz

    fun stepUp()   { setFrequency((frequencyHz + groupStep(selectedGroup)).coerceAtMost(1_766_000_000L)) }
    fun stepDown() { setFrequency((frequencyHz - groupStep(selectedGroup)).coerceAtLeast(500_000L)) }

    private fun groupStep(group: Int): Long = when (group) {
        0 -> 1_000_000_000L
        1 -> 1_000_000L
        2 -> 1_000L
        3 -> 1L
        else -> 1_000L
    }

    // ─── Drawing ──────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRoundRect(0f, 0f, w, h, 12f, 12f, bgPaint)

        val ghz  =  frequencyHz / 1_000_000_000L
        val mhz  = (frequencyHz % 1_000_000_000L) / 1_000_000L
        val khz  = (frequencyHz % 1_000_000L) / 1_000L
        val hz   =  frequencyHz % 1_000L

        val groups = listOf(
            Pair("GHz", "%1d".format(ghz)),
            Pair("MHz", "%03d".format(mhz)),
            Pair("kHz", "%03d".format(khz)),
            Pair("Hz",  "%03d".format(hz))
        )

        val groupCount = 4
        val segW = w / groupCount
        val centerY = h / 2f + 8f

        for (i in groups.indices) {
            val cx = segW * i + segW / 2f
            val segRight = segW * (i + 1)

            // Highlight selected group
            if (i == selectedGroup) {
                canvas.drawRect(segW * i + 4f, 4f, segRight - 4f, h - 4f, selectionPaint)
            }

            // Value digits
            digitPaint.color = when {
                i == selectedGroup -> 0xFF00EEFF.toInt()
                i < selectedGroup -> 0xFF88AACC.toInt()
                else -> 0xFF446688.toInt()
            }
            digitPaint.textSize = when (i) {
                0 -> h * 0.30f
                1 -> h * 0.42f
                2 -> h * 0.34f
                else -> h * 0.28f
            }
            canvas.drawText(groups[i].second, cx, centerY, digitPaint)

            // Unit label below digits
            labelPaint.textSize = h * 0.16f
            canvas.drawText(groups[i].first, cx, h - 6f, labelPaint)

            // Separator dot (between groups, except last)
            if (i < groupCount - 1) {
                val dotX = segRight - 2f
                separatorPaint.color = 0xFF445566.toInt()
                separatorPaint.strokeWidth = 3f
                canvas.drawCircle(dotX, centerY - digitPaint.textSize * 0.4f, 5f, separatorPaint)
            }
        }

        // Step size indicator
        val stepLabel = when (selectedGroup) {
            0 -> "Step: 1 GHz"
            1 -> "Step: 1 MHz"
            2 -> "Step: 1 kHz"
            3 -> "Step: 1 Hz"
            else -> ""
        }
        labelPaint.textAlign = Paint.Align.RIGHT
        labelPaint.textSize = 22f
        canvas.drawText(stepLabel, w - 8f, 20f, labelPaint)

        // Long-press hint (subtle, bottom-left)
        labelPaint.textAlign = Paint.Align.LEFT
        labelPaint.textSize = 18f
        canvas.drawText("⌛ hold to type", 8f, h - 6f, labelPaint)

        labelPaint.textAlign = Paint.Align.CENTER
    }

    private fun selectGroupAt(x: Float) {
        selectedGroup = ((x / width * 4).toInt()).coerceIn(0, 3)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredH = (context.resources.displayMetrics.density * 72).toInt()
        val h = resolveSize(desiredH, heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }
}
