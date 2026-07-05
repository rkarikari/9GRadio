package com.radiosport.ninegradio.data

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*

/**
 * Import/export utilities for memory channels, bookmarks, and settings.
 * Supports JSON and CSV formats.
 */
object DataExporter {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    // ─── Export ───────────────────────────────────────────────────────────────

    suspend fun exportMemoryChannels(
        channels: List<MemoryChannel>,
        uri: Uri,
        context: Context,
        format: ExportFormat = ExportFormat.JSON
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                when (format) {
                    ExportFormat.JSON -> {
                        val json = gson.toJson(channels)
                        stream.write(json.toByteArray())
                    }
                    ExportFormat.CSV -> {
                        val csv = buildString {
                            appendLine("name,frequencyHz,demodMode,sampleRate,gain,squelch,biasTee,directSampling,ppm,group,notes")
                            channels.forEach { ch ->
                                appendLine("\"${ch.name}\",${ch.frequencyHz},${ch.demodMode},${ch.sampleRate},${ch.gain},${ch.squelch},${ch.biasTee},${ch.directSampling},${ch.ppmCorrection},\"${ch.group}\",\"${ch.notes}\"")
                            }
                        }
                        stream.write(csv.toByteArray())
                    }
                    ExportFormat.CHIRP_CSV -> {
                        stream.write(exportChirpCsv(channels).toByteArray())
                    }
                }
            }
            Result.success(channels.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun exportBookmarks(
        bookmarks: List<Bookmark>,
        uri: Uri,
        context: Context
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            // Export portable JSON (omit DB-internal id / bookmarkListId)
            val exportList = bookmarks.map { bm ->
                mapOf(
                    "label"       to bm.label,
                    "frequencyHz" to bm.frequencyHz,
                    "demodMode"   to bm.demodMode,
                    "bandwidth"   to bm.bandwidth,
                    "squelch"     to bm.squelch,
                    "notes"       to bm.notes,
                    "color"       to bm.color,
                    "favorite"    to bm.favorite,
                    "createdAt"   to bm.createdAt
                )
            }
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(gson.toJson(exportList).toByteArray())
            }
            Result.success(bookmarks.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Export settings map to JSON.
     */
    suspend fun exportSettings(
        prefs: android.content.SharedPreferences,
        uri: Uri,
        context: Context
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val map = prefs.all
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(gson.toJson(map).toByteArray())
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ─── Import ───────────────────────────────────────────────────────────────

    suspend fun importMemoryChannels(
        uri: Uri,
        context: Context
    ): Result<List<MemoryChannel>> = withContext(Dispatchers.IO) {
        try {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                ?: return@withContext Result.failure(IOException("Cannot read file"))
            val type = object : TypeToken<List<MemoryChannel>>() {}.type
            val channels: List<MemoryChannel> = gson.fromJson(text, type)
            Result.success(channels)
        } catch (e: Exception) {
            // Try CSV fallback
            try {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                    ?: return@withContext Result.failure(IOException("Cannot read file"))
                val channels = parseCsvChannels(text)
                Result.success(channels)
            } catch (e2: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun importBookmarks(
        uri: Uri,
        context: Context
    ): Result<List<Bookmark>> = withContext(Dispatchers.IO) {
        try {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                ?: return@withContext Result.failure(IOException("Cannot read file"))
            // Try rich map format first, then fall back to direct Bookmark deserialization
            val mapType = object : TypeToken<List<Map<String, Any>>>() {}.type
            val maps: List<Map<String, Any>> = gson.fromJson(text, mapType)
            val bookmarks = maps.map { m ->
                Bookmark(
                    label       = (m["label"] as? String) ?: "Imported",
                    frequencyHz = (m["frequencyHz"] as? Number)?.toLong() ?: 0L,
                    demodMode   = (m["demodMode"] as? String) ?: "",
                    bandwidth   = (m["bandwidth"] as? Number)?.toInt() ?: 0,
                    squelch     = (m["squelch"] as? Number)?.toFloat() ?: -100f,
                    notes       = (m["notes"] as? String) ?: "",
                    color       = (m["color"] as? Number)?.toInt() ?: 0xFF2196F3.toInt(),
                    favorite    = (m["favorite"] as? Boolean) ?: false,
                    createdAt   = (m["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
                )
            }
            Result.success(bookmarks)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun importSettings(
        uri: Uri,
        context: Context,
        prefs: android.content.SharedPreferences
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                ?: return@withContext Result.failure(IOException("Cannot read file"))
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val map: Map<String, Any> = gson.fromJson(text, type)
            val editor = prefs.edit()
            var count = 0
            map.forEach { (key, value) ->
                when (value) {
                    is String  -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Float   -> editor.putFloat(key, value)
                    is Double  -> editor.putFloat(key, value.toFloat())
                    is Int     -> editor.putInt(key, value)
                    is Long    -> editor.putLong(key, value)
                    is Number  -> editor.putInt(key, value.toInt())
                }
                count++
            }
            editor.apply()
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ─── CHIRP CSV format (compatible with many SDR apps) ─────────────────────

    fun exportChirpCsv(channels: List<MemoryChannel>): String = buildString {
        appendLine("Location,Name,Frequency,Duplex,Offset,Tone,rToneFreq,cToneFreq,DtcsCode,DtcsPolarity,Mode,TStep,Skip,Comment,URCALL,RPT1CALL,RPT2CALL,DVCODE")
        channels.forEachIndexed { idx, ch ->
            val freqMhz = ch.frequencyHz / 1_000_000.0
            val mode = when (ch.demodMode) {
                "NFM", "FM" -> "NFM"
                "WFM", "WFM_STEREO" -> "WFM"
                "AM" -> "AM"
                "USB" -> "USB"
                "LSB" -> "LSB"
                else -> "NFM"
            }
            appendLine("$idx,${ch.name},${"%.6f".format(freqMhz)},,0,,88.5,88.5,023,NN,$mode,5.00,,${ch.notes},,,,")
        }
    }

    // ─── CSV parser ───────────────────────────────────────────────────────────

    private fun parseCsvChannels(csv: String): List<MemoryChannel> {
        val lines = csv.lines().drop(1)  // skip header
        return lines.mapNotNull { line ->
            val cols = parseCsvLine(line)
            if (cols.size < 3) return@mapNotNull null
            try {
                MemoryChannel(
                    name        = cols.getOrElse(0) { "Unknown" }.trim('"'),
                    frequencyHz = cols.getOrElse(1) { "0" }.toLongOrNull() ?: 0L,
                    demodMode   = cols.getOrElse(2) { "NFM" },
                    sampleRate  = cols.getOrElse(3) { "1920000" }.toIntOrNull() ?: 1_920_000,
                    gain        = cols.getOrElse(4) { "0" }.toIntOrNull() ?: 0,
                    squelch     = cols.getOrElse(5) { "-100" }.toFloatOrNull() ?: -100f,
                    biasTee     = cols.getOrElse(6) { "false" }.toBoolean(),
                    directSampling = cols.getOrElse(7) { "0" }.toIntOrNull() ?: 0,
                    ppmCorrection  = cols.getOrElse(8) { "0" }.toIntOrNull() ?: 0,
                    group       = cols.getOrElse(9) { "Imported" }.trim('"'),
                    notes       = cols.getOrElse(10) { "" }.trim('"')
                )
            } catch (e: Exception) { null }
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var inQuotes = false
        val current = StringBuilder()
        for (c in line) {
            when {
                c == '"'         -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { result.add(current.toString()); current.clear() }
                else             -> current.append(c)
            }
        }
        result.add(current.toString())
        return result
    }

    enum class ExportFormat { JSON, CSV, CHIRP_CSV }
}
