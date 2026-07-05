package com.radiosport.ninegradio.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

/**
 * Analog-style S-meter with logarithmic dBFS scale.
 * S1–S9 at 6 dB per S-unit, then S9+10dB to S9+60dB.
 */
class SMeterView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var signalDb = -120f   // current dBFS
    private var peakDb   = -120f
    private var peakDecayTimer = 0
    private val peakDecayRate = 2   // frames

    // Segment colors
    private val segGreen  = 0xFF00CC44.toInt()
    private val segYellow = 0xFFDDCC00.toInt()
    private val segRed    = 0xFFCC2200.toInt()

    private val bgPaint = Paint().apply { color = 0xFF161B22.toInt(); style = Paint.Style.FILL }
    private val segPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val textPaint = Paint().apply {
        color = 0xAAFFFFFF.toInt(); textSize = 18f
        isAntiAlias = true; textAlign = Paint.Align.CENTER
    }
    private val peakPaint = Paint().apply {
        color = 0xFFFFFFFF.toInt(); strokeWidth = 2f; style = Paint.Style.STROKE
    }

    // S-meter scale: S1=−93 dBFS ... S9=−45 dBFS, then +10 each
    private val sLevels = buildSScale()
    private val labels = listOf("1","2","3","4","5","6","7","8","9","+10","+20","+30","+40","+50","+60")

    fun setSignalDb(db: Float) {
        signalDb = db
        if (db > peakDb) {
            peakDb = db
            peakDecayTimer = 60
        } else {
            if (peakDecayTimer-- <= 0) peakDb -= 0.5f
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val numSeg = sLevels.size
        val segW = (w - 20f) / numSeg
        val segH = h * 0.55f
        val segBottom = h * 0.80f

        for (i in 0 until numSeg) {
            val db = sLevels[i]
            val lit = signalDb >= db
            val segColor = when {
                i < 9  -> segGreen
                i < 12 -> segYellow
                else   -> segRed
            }
            segPaint.color = if (lit) segColor else (segColor and 0x00FFFFFF or 0x22000000)
            val x = 10f + i * segW + 1f
            val segHeight = segH * (0.4f + 0.6f * ((i + 1).toFloat() / numSeg))
            canvas.drawRect(x, segBottom - segHeight, x + segW - 2f, segBottom, segPaint)

            // Label under segment (every other)
            if (i % 2 == 0 || i >= 9) {
                textPaint.color = if (lit) 0xCCFFFFFF.toInt() else 0x44FFFFFF
                textPaint.textSize = 14f
                canvas.drawText(
                    labels.getOrElse(i) { "" },
                    x + segW / 2f,
                    h - 4f,
                    textPaint
                )
            }
        }

        // Peak indicator
        val peakX = dbToX(peakDb, w)
        if (peakX > 0f) {
            canvas.drawLine(peakX, h * 0.2f, peakX, segBottom, peakPaint)
        }

        // dBFS readout
        textPaint.color = 0xCCFFFFFF.toInt()
        textPaint.textSize = 20f
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("${"%.1f".format(signalDb)} dBFS", w - 4f, 20f, textPaint)
        textPaint.textAlign = Paint.Align.CENTER

        // S-value label
        val sVal = dbToSValue(signalDb)
        textPaint.color = 0xFF00CCFF.toInt()
        textPaint.textSize = 22f
        canvas.drawText(sVal, 40f, 22f, textPaint)
    }

    private fun dbToX(db: Float, w: Float): Float {
        val minDb = sLevels.first().toFloat()
        val maxDb = sLevels.last().toFloat()
        val norm = ((db - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)
        return 10f + norm * (w - 20f)
    }

    private fun dbToSValue(db: Float): String = when {
        db < -93 -> "S0"
        db < -87 -> "S1"
        db < -81 -> "S2"
        db < -75 -> "S3"
        db < -69 -> "S4"
        db < -63 -> "S5"
        db < -57 -> "S6"
        db < -51 -> "S7"
        db < -45 -> "S8"
        db < -35 -> "S9"
        db < -25 -> "S9+10"
        db < -15 -> "S9+20"
        db < -5  -> "S9+30"
        else     -> "S9+40"
    }

    private fun buildSScale(): FloatArray {
        // S1=−93, S2=−87, ..., S9=−45, then +10 dB each to S9+60
        val scale = mutableListOf<Float>()
        for (s in 1..9) scale.add(-93f + (s - 1) * 6f)
        for (plus in listOf(10, 20, 30, 40, 50, 60)) scale.add(-45f + plus)
        return scale.toFloatArray()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val desiredH = (40 * context.resources.displayMetrics.density).toInt()
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(desiredH, MeasureSpec.EXACTLY))
    }
}
