package com.radiosport.ninegradio

import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import kotlin.math.*

// ─── View extensions ─────────────────────────────────────────────────────────

fun View.show() { visibility = View.VISIBLE }
fun View.hide() { visibility = View.GONE }
fun View.invisible() { visibility = View.INVISIBLE }

// ─── Context extensions ───────────────────────────────────────────────────────

fun Context.toast(msg: String, long: Boolean = false) =
    Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

fun Context.toast(@StringRes res: Int, long: Boolean = false) =
    Toast.makeText(this, res, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

fun Context.color(@ColorRes res: Int): Int = ContextCompat.getColor(this, res)

val Number.dp: Float get() = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_DIP, this.toFloat(), Resources.getSystem().displayMetrics)

val Number.sp: Float get() = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_SP, this.toFloat(), Resources.getSystem().displayMetrics)

// ─── Frequency formatting ─────────────────────────────────────────────────────

/**
 * Format a frequency in Hz as a human-readable string.
 * e.g. 145_500_000 → "145.500 MHz"
 */
fun Long.toFreqString(): String = when {
    this >= 1_000_000_000L -> "${"%.4f".format(this / 1e9)} GHz"
    this >= 1_000_000L     -> "${"%.4f".format(this / 1e6)} MHz"
    this >= 1_000L         -> "${"%.3f".format(this / 1e3)} kHz"
    else                   -> "$this Hz"
}

fun Long.toFreqStringShort(): String = when {
    this >= 1_000_000_000L -> "${"%.3f".format(this / 1e9)}G"
    this >= 1_000_000L     -> "${"%.3f".format(this / 1e6)}M"
    this >= 1_000L         -> "${"%.1f".format(this / 1e3)}k"
    else                   -> "$this"
}

/**
 * Parse a frequency string ("145.5 MHz", "145500000", "145.5M") to Hz.
 */
fun String.parseFrequencyHz(): Long? {
    val s = this.trim().uppercase()
    val num = s.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return null
    return when {
        s.contains("GHZ") || s.endsWith("G") -> (num * 1e9).toLong()
        s.contains("MHZ") || s.endsWith("M") -> (num * 1e6).toLong()
        s.contains("KHZ") || s.endsWith("K") -> (num * 1e3).toLong()
        num < 30_000                          -> (num * 1e6).toLong()  // assume MHz if small number
        else                                  -> num.toLong()
    }
}

// ─── dB / Power ───────────────────────────────────────────────────────────────

fun Float.toDb(): Float    = if (this > 0f) 20f * log10(this) else -200f
fun Float.fromDb(): Float  = 10f.pow(this / 20f)
fun Double.toDb(): Float   = if (this > 0.0) (20.0 * log10(this)).toFloat() else -200f
fun Float.powerToDb(): Float = if (this > 0f) 10f * log10(this) else -200f
fun Float.powerFromDb(): Float = 10f.pow(this / 10f)

// ─── ByteArray extensions ─────────────────────────────────────────────────────

/**
 * Convert RTL-SDR uint8 IQ bytes to normalised float array.
 */
fun ByteArray.toFloatIq(): FloatArray {
    val out = FloatArray(size)
    for (i in indices) out[i] = ((this[i].toInt() and 0xFF) - 127.5f) / 128f
    return out
}

/**
 * Compute RMS power of IQ byte buffer.
 */
fun ByteArray.iqRmsPower(): Float {
    var power = 0.0
    val n = size / 2
    for (i in 0 until n) {
        val I = ((this[2*i].toInt()   and 0xFF) - 127.5) / 128.0
        val Q = ((this[2*i+1].toInt() and 0xFF) - 127.5) / 128.0
        power += I*I + Q*Q
    }
    return (power / n).toFloat()
}

// ─── FloatArray DSP helpers ───────────────────────────────────────────────────

fun FloatArray.rms(): Float = sqrt(map { it * it }.average().toFloat())

fun FloatArray.normalise(targetPeak: Float = 1f): FloatArray {
    val peak = maxOf(map { abs(it) }.maxOrNull() ?: 1f, 1e-10f)
    return FloatArray(size) { this[it] * targetPeak / peak }
}

fun FloatArray.clip(minVal: Float = -1f, maxVal: Float = 1f): FloatArray =
    FloatArray(size) { this[it].coerceIn(minVal, maxVal) }

fun FloatArray.powerDb(): Float = rms().powerToDb()

// ─── Int / Long bit manipulation ─────────────────────────────────────────────

fun Int.toBin(bits: Int = 32): String = toString(2).padStart(bits, '0')
fun Int.toHex(digits: Int = 8): String = toString(16).uppercase().padStart(digits, '0')
fun Long.toBin(bits: Int = 32): String = toString(2).padStart(bits, '0')

// ─── Time helpers ─────────────────────────────────────────────────────────────

fun Long.toHhMmSs(): String {
    val h = this / 3_600_000L
    val m = (this % 3_600_000L) / 60_000L
    val s = (this % 60_000L) / 1_000L
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
    else "%d:%02d".format(m, s)
}

fun Long.toFileSizeString(): String = when {
    this >= 1_073_741_824L -> "${"%.2f".format(this / 1_073_741_824.0)} GB"
    this >= 1_048_576L     -> "${"%.2f".format(this / 1_048_576.0)} MB"
    this >= 1_024L         -> "${"%.1f".format(this / 1_024.0)} kB"
    else                   -> "$this B"
}
