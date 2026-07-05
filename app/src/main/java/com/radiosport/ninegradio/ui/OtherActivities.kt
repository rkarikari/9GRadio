package com.radiosport.ninegradio.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.*
import androidx.recyclerview.widget.*
import androidx.recyclerview.widget.ListAdapter
import com.radiosport.ninegradio.BuildConfig
import com.radiosport.ninegradio.R
import com.radiosport.ninegradio.data.RecordingMeta
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// ═════════════════════════════════════════════════════════════════════════════
//  SETTINGS ACTIVITY
// ═════════════════════════════════════════════════════════════════════════════

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Settings"
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}

class SettingsFragment : PreferenceFragmentCompat() {

    // ─── Service binding (for live FFT / hardware propagation) ───────────────

    private var sdrService: com.radiosport.ninegradio.usb.RtlSdrService? = null
    private var serviceBound = false

    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName, binder: android.os.IBinder) {
            sdrService = (binder as com.radiosport.ninegradio.usb.RtlSdrService.LocalBinder).getService()
            serviceBound = true
            // Apply current preference values to the running engine immediately on connect
            applyAllPrefsToEngine(preferenceScreen.sharedPreferences ?: return)
        }
        override fun onServiceDisconnected(name: android.content.ComponentName) {
            sdrService = null
            serviceBound = false
        }
    }

    // ─── Live preference listener ─────────────────────────────────────────────

    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        val svc = sdrService
        val activity = activity ?: return@OnSharedPreferenceChangeListener
        when (key) {
            // ── FFT / Spectrum ─────────────────────────────────────────────
            "pref_fft_size" -> {
                val size = prefs.getString(key, "2048")?.toIntOrNull() ?: 2048
                // Route through svc.setFftSize() so pendingFftSize is updated
                // and the setting survives the next device reconnect.
                svc?.setFftSize(size)
            }
            "pref_fft_window" -> {
                val wt = prefs.getString(key, "BLACKMAN_HARRIS") ?: "BLACKMAN_HARRIS"
                try {
                    svc?.dspEngine?.setFftWindow(
                        com.radiosport.ninegradio.dsp.FftEngine.WindowType.valueOf(wt)
                    )
                } catch (_: IllegalArgumentException) {}
            }
            "pref_fft_smoothing" -> {
                val pct = prefs.getInt(key, 30)
                svc?.dspEngine?.setFftSmoothing(pct / 100f)
            }
            "pref_peak_hold" -> {
                val on = prefs.getBoolean(key, true)
                svc?.dspEngine?.fftEngine?.showPeakHold = on
            }
            // ── RF / Tuner ─────────────────────────────────────────────────
            "pref_sample_rate" -> {
                val rate = prefs.getString(key, "1920000")?.toIntOrNull() ?: 1_920_000
                svc?.setSampleRate(rate)
            }
            "pref_ppm" -> {
                val ppm = prefs.getInt(key, 0)
                svc?.setPpm(ppm)
                // Keep the RF-tab slider's store ("rtlsdr_prefs"/"ppm") and
                // MainViewModel._ppm in sync with the Settings screen value so
                // the slider reflects the Settings change and the value is
                // correctly restored on the next app start.
                activity.getSharedPreferences("rtlsdr_prefs", android.content.Context.MODE_PRIVATE)
                    .edit().putInt("ppm", ppm).apply()
            }
            "pref_tuner_agc_default" -> {
                val on = prefs.getBoolean(key, false)
                svc?.setTunerAgc(on)
            }
            "pref_hardware_agc_default" -> {
                val on = prefs.getBoolean(key, false)
                svc?.setHardwareAgc(on)
            }
            "pref_default_gain" -> {
                val gain = prefs.getInt(key, 26)
                svc?.setGain(gain)
            }
            "pref_squelch_default" -> {
                // Stored as positive integer (UI shows ×−1), convert back to negative dBFS
                val sq = prefs.getInt(key, 100)
                svc?.setSquelch(-(sq.toFloat()))
            }
            "pref_bias_tee_default" -> {
                val on = prefs.getBoolean(key, false)
                svc?.setBiasTee(on)
            }
            "pref_default_demod" -> {
                val name = prefs.getString(key, "NFM") ?: "NFM"
                try {
                    svc?.setDemodMode(com.radiosport.ninegradio.dsp.DemodMode.valueOf(name))
                } catch (_: IllegalArgumentException) {}
            }
            "pref_direct_sampling_default" -> {
                val mode = prefs.getString(key, "0")?.toIntOrNull() ?: 0
                svc?.setDirectSampling(mode)
            }
            // ── Display — stored in default prefs; MainActivity picks up on onResume ──
            // We send a broadcast so MainActivity can update its views without delay.
            "pref_fft_size",
            "pref_spectrum_theme",
            "pref_waterfall_palette",
            "pref_db_min",
            "pref_db_max",
            "pref_waterfall_speed" -> {
                activity.sendBroadcast(android.content.Intent(ACTION_DISPLAY_PREFS_CHANGED)
                    .setPackage(activity.packageName))
            }
            // ── System ────────────────────────────────────────────────────
            "pref_keep_screen_on" -> {
                val on = prefs.getBoolean(key, true)
                if (on) activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else   activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            // ── Audio DSP ─────────────────────────────────────────────────
            "pref_dc_block" -> {
                val on = prefs.getBoolean(key, true)
                svc?.setDcBlock(on)
            }
            "pref_freq_offset" -> {
                val hz = prefs.getString(key, "0")?.toIntOrNull() ?: 0
                svc?.setFreqOffset(hz)
            }
            "pref_audio_sink_rate" -> {
                val rate = prefs.getString(key, "44100")?.toIntOrNull()
                    ?: com.radiosport.ninegradio.dsp.DspEngine.DEFAULT_AUDIO_SINK_RATE
                svc?.setAudioSinkRate(rate)
                // Mirror into "rtlsdr_prefs"/"audioSinkRate" so the RF panel's
                // audio-output chip row (MainViewModel.audioSinkRate) reflects
                // a change made here on the Settings screen.
                activity.getSharedPreferences("rtlsdr_prefs", android.content.Context.MODE_PRIVATE)
                    .edit().putInt("audioSinkRate", rate).apply()
            }
            // ── External Decoder (UDP PCM16LE audio pipe) ────────────────────
            "pref_external_decoder_enabled" -> {
                svc?.setExternalDecoderEnabled(prefs.getBoolean(key, false))
            }
            "pref_external_decoder_host",
            "pref_external_decoder_port" -> {
                val host = prefs.getString("pref_external_decoder_host",
                    com.radiosport.ninegradio.dsp.ExternalDecoderStream.DEFAULT_HOST)
                    ?: com.radiosport.ninegradio.dsp.ExternalDecoderStream.DEFAULT_HOST
                val port = prefs.getString("pref_external_decoder_port",
                    com.radiosport.ninegradio.dsp.ExternalDecoderStream.DEFAULT_PORT.toString())
                    ?.toIntOrNull() ?: com.radiosport.ninegradio.dsp.ExternalDecoderStream.DEFAULT_PORT
                svc?.setExternalDecoderTarget(host, port)
            }
        }
    }

    private fun applyAllPrefsToEngine(prefs: android.content.SharedPreferences) {
        val svc = sdrService ?: return
        // FFT size / decimation / AGC / gain are all per-protocol state, restored
        // from the active mode's snapshot by MainViewModel.restoreSettingsForMode()
        // when the mode was last set (or left at whatever the running engine
        // currently has). "pref_fft_size", "pref_decimation",
        // "pref_tuner_agc_default", and "pref_hardware_agc_default" are *factory
        // defaults* used only the first time a protocol is used — once a per-mode
        // snapshot exists it is the source of truth.
        //
        // Re-applying these global Settings defaults here on every Settings-screen
        // bind would silently overwrite the active protocol's FFT size (which
        // resets the FftEngine's smoothed/peak-hold arrays — visible as a sudden
        // decimation/resolution change), AGC mode, and gain (via
        // setTunerAgcEnabled()/setHardwareAgcEnabled()'s "push current _gain on
        // AGC-off" side effect) — exactly the symptom reported: opening Settings
        // changes the current protocol's decimation/gain/squelch.
        //
        // Only settings that are NOT part of the per-mode snapshot (FFT window,
        // smoothing, peak-hold display toggle, audio DSP, audio sink rate) are
        // safe to re-apply unconditionally below.
        try {
            svc.dspEngine?.setFftWindow(
                com.radiosport.ninegradio.dsp.FftEngine.WindowType.valueOf(
                    prefs.getString("pref_fft_window", "BLACKMAN_HARRIS") ?: "BLACKMAN_HARRIS"
                )
            )
        } catch (_: IllegalArgumentException) {}
        svc.dspEngine?.setFftSmoothing(prefs.getInt("pref_fft_smoothing", 30) / 100f)
        svc.dspEngine?.fftEngine?.showPeakHold = prefs.getBoolean("pref_peak_hold", true)
        // Audio DSP
        svc.setDcBlock(prefs.getBoolean("pref_dc_block", true))
        svc.setFreqOffset(prefs.getString("pref_freq_offset", "0")?.toIntOrNull() ?: 0)
        // Audio output sample rate.
        //
        // This setting is exposed in two places that historically wrote to two
        // *different* SharedPreferences stores:
        //   • RF panel chip row → MainViewModel → "rtlsdr_prefs" / "audioSinkRate" (Int)
        //   • Settings "Audio Output Sample Rate" ListPreference → default prefs /
        //     "pref_audio_sink_rate" (String, defaultValue "44100")
        //
        // If the user only ever changed the RF panel chip, "pref_audio_sink_rate"
        // is still its unset default of 44100. Applying that here on every
        // Settings-screen bind would silently revert the engine (and tear down/
        // rebuild the AudioTrack + resampler chain — an audible glitch) back to
        // 44.1 kHz even though the RF panel still shows e.g. "24k" selected.
        //
        // setAudioSinkRate() now keeps both stores in sync going forward, but for
        // installs where they had already diverged we prefer the RF-panel value
        // ("audioSinkRate") whenever it's present and differs from the Settings
        // ListPreference's value.
        val rtlSdrPrefs = requireActivity().getSharedPreferences("rtlsdr_prefs", android.content.Context.MODE_PRIVATE)
        val settingsSinkRate = prefs.getString("pref_audio_sink_rate",
            com.radiosport.ninegradio.dsp.DspEngine.DEFAULT_AUDIO_SINK_RATE.toString())
            ?.toIntOrNull() ?: com.radiosport.ninegradio.dsp.DspEngine.DEFAULT_AUDIO_SINK_RATE
        val rfPanelSinkRate = if (rtlSdrPrefs.contains("audioSinkRate"))
            rtlSdrPrefs.getInt("audioSinkRate", settingsSinkRate) else settingsSinkRate
        svc.setAudioSinkRate(rfPanelSinkRate)
        if (rfPanelSinkRate != settingsSinkRate) {
            prefs.edit().putString("pref_audio_sink_rate", rfPanelSinkRate.toString()).apply()
        }
        // PPM correction is a global device setting (not per-mode) so it is safe
        // to restore here on every Settings-screen bind.  Merge the two stores:
        // prefer "rtlsdr_prefs"/"ppm" (the RF-tab slider) if present, otherwise
        // fall back to "pref_ppm" (the Settings SeekBarPreference).
        val rfPanelPpm = if (rtlSdrPrefs.contains("ppm")) rtlSdrPrefs.getInt("ppm", 0) else null
        val settingsPpm = if (prefs.contains("pref_ppm")) prefs.getInt("pref_ppm", 0) else null
        val effectivePpm = rfPanelPpm ?: settingsPpm ?: 0
        svc.setPpm(effectivePpm)
        // Sync both stores so they stay in agreement going forward.
        if (rfPanelPpm != effectivePpm)
            rtlSdrPrefs.edit().putInt("ppm", effectivePpm).apply()
        if (settingsPpm != effectivePpm)
            prefs.edit().putInt("pref_ppm", effectivePpm).apply()
        // External decoder UDP pipe -- independent of per-mode snapshots, safe
        // to re-apply unconditionally on every connect (mirrors pref_dc_block).
        svc.setExternalDecoderTarget(
            prefs.getString("pref_external_decoder_host",
                com.radiosport.ninegradio.dsp.ExternalDecoderStream.DEFAULT_HOST)
                ?: com.radiosport.ninegradio.dsp.ExternalDecoderStream.DEFAULT_HOST,
            prefs.getString("pref_external_decoder_port",
                com.radiosport.ninegradio.dsp.ExternalDecoderStream.DEFAULT_PORT.toString())
                ?.toIntOrNull() ?: com.radiosport.ninegradio.dsp.ExternalDecoderStream.DEFAULT_PORT
        )
        svc.setExternalDecoderEnabled(prefs.getBoolean("pref_external_decoder_enabled", false))
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onStart() {
        super.onStart()
        requireContext().bindService(
            android.content.Intent(requireContext(), com.radiosport.ninegradio.usb.RtlSdrService::class.java),
            serviceConnection,
            android.content.Context.BIND_AUTO_CREATE
        )
        preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun onStop() {
        super.onStop()
        preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(prefListener)
        if (serviceBound) {
            requireContext().unbindService(serviceConnection)
            serviceBound = false
        }
    }

    // ─── Preference setup ─────────────────────────────────────────────────────

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        // ── Populate list entries that aren't backed by arrays.xml ──────────
        findPreference<ListPreference>("pref_sample_rate")?.apply {
            if (entries.isNullOrEmpty()) {
                entries = arrayOf(
                    "240 kS/s  (÷5)", "288 kS/s  (÷6)",
                    "912 kS/s  (÷19)", "960 kS/s  (÷20)",
                    "1.024 MS/s", "1.152 MS/s (÷24)", "1.200 MS/s (÷25)",
                    "1.296 MS/s (÷27)", "1.440 MS/s (÷30)",
                    "1.536 MS/s (÷32)", "1.680 MS/s (÷35)",
                    "1.824 MS/s (÷38)", "1.920 MS/s (÷40)",
                    "2.016 MS/s (÷42)", "2.048 MS/s", "2.160 MS/s (÷45)",
                    "2.256 MS/s (÷47)", "2.400 MS/s (÷50)"
                )
                entryValues = arrayOf(
                    "240000","288000",
                    "912000","960000",
                    "1024000","1152000","1200000",
                    "1296000","1440000",
                    "1536000","1680000",
                    "1824000","1920000",
                    "2016000","2048000","2160000",
                    "2256000","2400000"
                )
            }
        }
        // Fix: pref_default_demod (not pref_demod_mode) matches the XML key
        findPreference<ListPreference>("pref_default_demod")?.apply {
            if (entries.isNullOrEmpty()) {
                val modes = com.radiosport.ninegradio.dsp.DemodMode.values()
                entries     = modes.map { it.displayName }.toTypedArray()
                entryValues = modes.map { it.name }.toTypedArray()
            }
        }

        // ── Click actions ────────────────────────────────────────────────────

        findPreference<Preference>("pref_clear_recordings")?.setOnPreferenceClickListener {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Clear All Recordings")
                .setMessage("Delete all recording metadata? (Files on disk are NOT deleted.)")
                .setPositiveButton("Clear") { _, _ ->
                    lifecycleScope.launch {
                        val app = requireActivity().application
                            as com.radiosport.ninegradio.RtlSdrApplication
                        val recs = app.database.recordingMetaDao().getAll().first()
                        recs.forEach { app.database.recordingMetaDao().delete(it) }
                        android.widget.Toast.makeText(
                            requireContext(),
                            "Cleared ${recs.size} recording entries",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }

        findPreference<Preference>("pref_export_settings")?.setOnPreferenceClickListener {
            val prefs = requireActivity().getSharedPreferences("rtlsdr_prefs",
                android.content.Context.MODE_PRIVATE)
            val json = com.google.gson.GsonBuilder().setPrettyPrinting().create()
                .toJson(prefs.all)
            val file = java.io.File(requireActivity().getExternalFilesDir(null),
                "ninegradio_settings.json")
            file.parentFile?.mkdirs()
            file.writeText(json)
            android.widget.Toast.makeText(requireContext(),
                "Settings exported to ${file.name}", android.widget.Toast.LENGTH_LONG).show()
            true
        }

        findPreference<Preference>("pref_import_settings")?.setOnPreferenceClickListener {
            val file = java.io.File(requireActivity().getExternalFilesDir(null), "ninegradio_settings.json")
            if (!file.exists()) {
                android.widget.Toast.makeText(requireContext(),
                    "No exported settings file found (${file.name})", android.widget.Toast.LENGTH_LONG).show()
                return@setOnPreferenceClickListener true
            }
            try {
                val json = file.readText()
                val type = object : com.google.gson.reflect.TypeToken<Map<String, Any>>() {}.type
                val map: Map<String, Any> = com.google.gson.Gson().fromJson(json, type)
                val editor = requireActivity().getSharedPreferences("rtlsdr_prefs",
                    android.content.Context.MODE_PRIVATE).edit()
                map.forEach { (k, v) ->
                    when (v) {
                        is String  -> editor.putString(k, v)
                        is Boolean -> editor.putBoolean(k, v)
                        is Double  -> editor.putFloat(k, v.toFloat())
                        is Number  -> editor.putInt(k, v.toInt())
                        else -> {}
                    }
                }
                editor.apply()
                android.widget.Toast.makeText(requireContext(),
                    "Settings imported from ${file.name}", android.widget.Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(requireContext(),
                    "Import failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
            true
        }

        findPreference<Preference>("pref_export_memory")?.setOnPreferenceClickListener {
            lifecycleScope.launch {
                val app = requireActivity().application as com.radiosport.ninegradio.RtlSdrApplication
                val channels = app.database.memoryChannelDao().getAll().first()
                val json = com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(channels)
                val file = java.io.File(requireActivity().getExternalFilesDir(null),
                    "memory_channels_export.json")
                file.parentFile?.mkdirs()
                file.writeText(json)
                android.widget.Toast.makeText(requireContext(),
                    "Exported ${channels.size} channels to ${file.name}",
                    android.widget.Toast.LENGTH_LONG).show()
            }
            true
        }

        findPreference<Preference>("pref_import_memory")?.setOnPreferenceClickListener {
            val file = java.io.File(requireActivity().getExternalFilesDir(null),
                "memory_channels_export.json")
            if (!file.exists()) {
                android.widget.Toast.makeText(requireContext(),
                    "No export file found (${file.name})", android.widget.Toast.LENGTH_LONG).show()
                return@setOnPreferenceClickListener true
            }
            lifecycleScope.launch {
                try {
                    val json = file.readText()
                    val type = object : com.google.gson.reflect.TypeToken<
                        List<com.radiosport.ninegradio.data.MemoryChannel>>() {}.type
                    val channels: List<com.radiosport.ninegradio.data.MemoryChannel> =
                        com.google.gson.Gson().fromJson(json, type)
                    val app = requireActivity().application as com.radiosport.ninegradio.RtlSdrApplication
                    channels.forEach { ch ->
                        app.database.memoryChannelDao().insert(ch.copy(id = 0))
                    }
                    android.widget.Toast.makeText(requireContext(),
                        "Imported ${channels.size} channels", android.widget.Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    android.widget.Toast.makeText(requireContext(),
                        "Import failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
            true
        }

        findPreference<Preference>("pref_device_info")?.setOnPreferenceClickListener {
            // Delegate to the unified Device Info / source-selection dialog in MainActivity.
            // If this fragment is hosted inside SettingsActivity, go back to MainActivity first.
            val activity = requireActivity()
            if (activity is MainActivity) {
                activity.showDeviceInfoDialog()
            } else {
                // SettingsActivity — go back to main and let it handle on resume,
                // or simply finish and let MainActivity open via the menu.
                activity.finish()
            }
            true
        }

        findPreference<Preference>("pref_about")?.apply {
            // BuildConfig.VERSION_NAME and VERSION_CODE are generated from the single
            // versionName / versionCode declaration in app/build.gradle, ensuring this
            // display always matches the Android system version and the release APK name.
            summary = "9GRadio v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})\n" +
                    "Supports: RTL2832U + R828D\n" +
                    "TCXO | Direct Sampling | Bias Tee\n" +
                    "© ${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)} Open Source"
        }
    }

    companion object {
        const val ACTION_DISPLAY_PREFS_CHANGED = "com.radiosport.ninegradio.DISPLAY_PREFS_CHANGED"
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  RECORDING ACTIVITY
// ═════════════════════════════════════════════════════════════════════════════

class RecordingActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: RecordingAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recording)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Recordings"

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerRecordings)
        val tvTotalSize  = findViewById<TextView>(R.id.tvTotalSize)

        adapter = RecordingAdapter(
            onPlay = { meta -> playRecording(meta) },
            onShare = { meta -> shareRecording(meta) },
            onDelete = { meta ->
                File(meta.filePath).delete()
                lifecycleScope.launch {
                    (application as com.radiosport.ninegradio.RtlSdrApplication)
                        .database.recordingMetaDao().delete(meta)
                }
            }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        lifecycleScope.launch {
            viewModel.recordings.collectLatest { recs ->
                adapter.submitList(recs)
                val total = recs.sumOf { it.fileSizeBytes }
                tvTotalSize.text = "Total: ${"%.2f".format(total / 1e6)} MB  (${recs.size} files)"
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun playRecording(meta: RecordingMeta) {
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

    private fun shareRecording(meta: RecordingMeta) {
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
}

class RecordingAdapter(
    private val onPlay: (RecordingMeta) -> Unit,
    private val onShare: (RecordingMeta) -> Unit,
    private val onDelete: (RecordingMeta) -> Unit
) : ListAdapter<RecordingMeta, RecordingAdapter.VH>(DiffCb()) {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName: TextView = v.findViewById(R.id.tvRecName)
        val tvInfo: TextView = v.findViewById(R.id.tvRecInfo)
        val tvSize: TextView = v.findViewById(R.id.tvRecSize)
        val tvDate: TextView = v.findViewById(R.id.tvRecDate)
        val btnPlay: View    = v.findViewById(R.id.btnRecPlay)
        val btnShare: View   = v.findViewById(R.id.btnRecShare)
        val btnDelete: View  = v.findViewById(R.id.btnRecDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, vt: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val rec = getItem(pos)
        val file = File(rec.filePath)
        h.tvName.text  = file.name
        h.tvInfo.text  = "${"%.4f".format(rec.frequencyHz / 1e6)} MHz  ${rec.demodMode}  ${rec.sampleRate / 1000} kS/s"
        h.tvSize.text  = "${"%.2f".format(rec.fileSizeBytes / 1e6)} MB"
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(rec.createdAt))
        h.tvDate.text  = "[${rec.type}]  $dateStr"
        h.btnPlay.setOnClickListener { onPlay(rec) }
        h.btnShare.setOnClickListener { onShare(rec) }
        h.btnDelete.setOnClickListener { onDelete(rec) }
    }

    class DiffCb : DiffUtil.ItemCallback<RecordingMeta>() {
        override fun areItemsTheSame(a: RecordingMeta, b: RecordingMeta) = a.id == b.id
        override fun areContentsTheSame(a: RecordingMeta, b: RecordingMeta) = a == b
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  SPECTRUM FULL-SCREEN ACTIVITY
// ═════════════════════════════════════════════════════════════════════════════

class SpectrumActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContentView(R.layout.activity_spectrum_fullscreen)

        val specView = findViewById<SpectrumView>(R.id.spectrumViewFull)
        val wfView   = findViewById<WaterfallView>(R.id.waterfallViewFull)

        specView.onFrequencyClick = { hz -> viewModel.setFrequency(hz) }
        wfView.onFrequencyClick   = { hz -> viewModel.setFrequency(hz) }

        // Force the waterfall to always follow the spectrum's zoom/pan.
        // WaterfallView's own pinch/pan gesture handling is disabled (see
        // WaterfallView's "Gesture detectors" section), so SpectrumView is
        // the single source of truth here too -- without this wiring,
        // pinching/panning specView here would zoom the spectrum while
        // wfView stayed at its previous zoom/pan, making the two visibly
        // drift out of line with each other (same issue this fixes in
        // MainActivity for the main, non-fullscreen views).
        specView.onZoomPanChanged = { zoom, offsetHz ->
            wfView.setZoomPan(zoom, offsetHz)
        }

        lifecycleScope.launch {
            viewModel.spectrumData.collectLatest { data ->
                specView.updateSpectrum(data, viewModel.peakData.value)
                wfView.addLine(data)
            }
        }
        lifecycleScope.launch {
            viewModel.centerFreqHz.collectLatest { hz ->
                specView.setCenterFrequency(hz)
                wfView.setCenterFrequency(hz)
            }
        }

        // Tap to exit
        specView.setOnLongClickListener { finish(); true }
    }
}


// ═════════════════════════════════════════════════════════════════════════════
//  ACARS ACTIVITY  — complete VHF aircraft datalink monitor
// ═════════════════════════════════════════════════════════════════════════════

class AcarsActivity : AppCompatActivity() {

    companion object {
        private val CHANNELS = listOf(
            131_725_000L to "131.725",   // primary / default
            131_825_000L to "131.825",   // secondary default
            129_125_000L to "129.125",
            130_025_000L to "130.025",
            130_425_000L to "130.425",
            130_450_000L to "130.450",
            131_125_000L to "131.125",
            131_550_000L to "131.550"
        )
        private val PRIMARY_HZ = 131_725_000L
        private const val MAX_MESSAGES = 500
        private val TS_FMT = SimpleDateFormat("HH:mm:ss", Locale.US)
    }

    private val viewModel: MainViewModel by viewModels()

    // All decoded messages (newest first) and active filter
    private val allMessages = mutableListOf<com.radiosport.ninegradio.dsp.AcarsDecoder.AcarsMessage>()
    private var activeFilter: String? = null   // null = all; label string = specific

    // Service binding
    private var sdrService: com.radiosport.ninegradio.usb.RtlSdrService? = null
    private var serviceBound = false
    private var msgJob: Job? = null
    private var statsJob: Job? = null

    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName, binder: android.os.IBinder) {
            sdrService = (binder as com.radiosport.ninegradio.usb.RtlSdrService.LocalBinder).getService()
            serviceBound = true
            subscribeMessages()
            subscribeStats()
        }
        override fun onServiceDisconnected(name: android.content.ComponentName) {
            sdrService = null; serviceBound = false
        }
    }

    // Views
    private lateinit var listView: ListView
    private lateinit var tvStatus: android.widget.TextView
    private lateinit var tvFreq: android.widget.TextView
    private lateinit var tvCount: android.widget.TextView
    private lateinit var tvStats: android.widget.TextView
    private lateinit var tvFilterLabel: android.widget.TextView
    private lateinit var tvEmpty: android.widget.TextView
    private lateinit var viewLamp: android.view.View
    private lateinit var adapter: AcarsMessageAdapter

    private var currentFreqHz = PRIMARY_HZ

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_acars)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "ACARS Monitor"

        // Wire views
        listView      = findViewById(R.id.listAcarsMessages)
        tvStatus      = findViewById(R.id.tvAcarsStatus)
        tvFreq        = findViewById(R.id.tvAcarsFreq)
        tvCount       = findViewById(R.id.tvAcarsCount)
        tvStats       = findViewById(R.id.tvAcarsStats)
        tvFilterLabel = findViewById(R.id.tvAcarsFilter)
        tvEmpty       = findViewById(R.id.tvAcarsEmpty)
        viewLamp      = findViewById(R.id.viewSignalLamp)

        adapter = AcarsMessageAdapter(this)
        listView.adapter = adapter
        listView.setOnItemClickListener  { _, _, pos, _ -> showDetail(adapter.getItem(pos) ?: return@setOnItemClickListener) }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            copyToClipboard(adapter.getItem(pos) ?: return@setOnItemLongClickListener false)
            true
        }

        // Channel buttons (131.725 first / default, 131.825 second)
        wireChannelButton(R.id.btnCh131725, 131_725_000L)
        wireChannelButton(R.id.btnCh131825, 131_825_000L)
        wireChannelButton(R.id.btnCh129125, 129_125_000L)
        wireChannelButton(R.id.btnCh130025, 130_025_000L)
        wireChannelButton(R.id.btnCh130425, 130_425_000L)
        wireChannelButton(R.id.btnCh130450, 130_450_000L)
        wireChannelButton(R.id.btnCh131125, 131_125_000L)
        wireChannelButton(R.id.btnCh131550, 131_550_000L)

        // Utility buttons
        findViewById<android.widget.Button>(R.id.btnAcarsClear).setOnClickListener { clearMessages() }
        findViewById<android.widget.Button>(R.id.btnAcarsExport).setOnClickListener { exportMessages() }

        // Filter chips
        wireFilterChip(R.id.chipAll,   null)
        wireFilterChip(R.id.chipH1,    "H1")
        wireFilterChip(R.id.chipQ0,    "Q0")
        wireFilterChip(R.id.chipQM,    "QM")
        wireFilterChip(R.id.chipSA,    "SA")
        wireFilterChip(R.id.chipOther, "__OTHER__")

        // Initial tune
        tuneChannel(PRIMARY_HZ)

        bindService(
            Intent(this, com.radiosport.ninegradio.usb.RtlSdrService::class.java),
            serviceConnection, BIND_AUTO_CREATE
        )

        updateEmptyState()
    }

    override fun onDestroy() {
        super.onDestroy()
        msgJob?.cancel(); statsJob?.cancel()
        if (serviceBound) { unbindService(serviceConnection); serviceBound = false }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ─── Service subscriptions ────────────────────────────────────────────────

    private fun subscribeMessages() {
        msgJob?.cancel()
        msgJob = lifecycleScope.launch {
            sdrService?.dspEngine?.acarsDecoder?.messages?.collect { msg ->
                allMessages.add(0, msg)
                if (allMessages.size > MAX_MESSAGES) allMessages.removeLast()
                refreshList()
                updateCount()
            }
        }
    }

    private fun subscribeStats() {
        statsJob?.cancel()
        val svc = sdrService ?: return
        statsJob = lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(
                svc.connectionState,
                svc.statsFlow
            ) { state, stats -> state to stats }
            .collect { (state, stats) ->
                val connected = state is
                    com.radiosport.ninegradio.usb.RtlSdrService.ConnectionState.Connected
                if (!connected) {
                    tvStatus.text = "No device"
                    tvStatus.setTextColor(0xFF884422.toInt())
                    viewLamp.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF333333.toInt())
                } else if (stats.squelchOpen) {
                    tvStatus.text = "SIGNAL \u25b2  ${"%.0f".format(stats.signalDb)} dBFS"
                    tvStatus.setTextColor(0xFF44FF88.toInt())
                    viewLamp.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF00FF44.toInt())
                } else {
                    tvStatus.text = "Listening\u2026"
                    tvStatus.setTextColor(0xFF44FF88.toInt())
                    viewLamp.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF334455.toInt())
                }
            }
        }
    }

    // ─── Tuning ───────────────────────────────────────────────────────────────

    private fun tuneChannel(hz: Long) {
        currentFreqHz = hz
        viewModel.setFrequency(hz)
        // setDemodMode() saves the previous mode and restores any saved ACARS snapshot.
        // On first use we seed the protocol-required defaults.
        val firstUse = !viewModel.hasModeSnapshot(com.radiosport.ninegradio.dsp.DemodMode.ACARS)
        viewModel.setDemodMode(com.radiosport.ninegradio.dsp.DemodMode.ACARS)
        if (firstUse) {
            val acarsPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            // 240 kS/s = 48 000 × 5 — lowest low-rate band entry that is an exact
            // factor of the 48 kHz audio output rate, giving perfect integer decimation.
            // Wide enough for the 25 kHz ACARS channel while minimising USB bandwidth.
            viewModel.setSampleRate(240_000)
            viewModel.setTunerAgc(acarsPrefs.getBoolean("pref_tuner_agc_default", false))
            viewModel.setHardwareAgc(acarsPrefs.getBoolean("pref_hardware_agc_default", false))
            if (!acarsPrefs.getBoolean("pref_tuner_agc_default", false))
                viewModel.setGain(acarsPrefs.getInt("pref_default_gain", 26))
        }
        val mhz = "%.3f".format(hz / 1e6)
        tvFreq.text = "$mhz MHz"
        title = "ACARS Monitor — $mhz MHz"
        // Update button highlights
        highlightChannelButton(hz)
    }

    private fun wireChannelButton(resId: Int, hz: Long) {
        findViewById<android.widget.Button>(resId).setOnClickListener { tuneChannel(hz) }
    }

    private fun highlightChannelButton(activeHz: Long) {
        val buttons = mapOf(
            R.id.btnCh131725 to 131_725_000L,
            R.id.btnCh131825 to 131_825_000L,
            R.id.btnCh129125 to 129_125_000L,
            R.id.btnCh130025 to 130_025_000L,
            R.id.btnCh130425 to 130_425_000L,
            R.id.btnCh130450 to 130_450_000L,
            R.id.btnCh131125 to 131_125_000L,
            R.id.btnCh131550 to 131_550_000L
        )
        for ((id, hz) in buttons) {
            val btn = findViewById<android.widget.Button>(id)
            val isActive = hz == activeHz
            btn.setTextColor(if (isActive) 0xFFFFFFFF.toInt() else 0xFF00CCFF.toInt())
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (isActive) 0x8800AACC.toInt() else android.graphics.Color.TRANSPARENT
            )
        }
    }

    // ─── Filtering ────────────────────────────────────────────────────────────

    private fun wireFilterChip(resId: Int, label: String?) {
        findViewById<android.widget.Button>(resId).setOnClickListener {
            activeFilter = label
            tvFilterLabel.text = "Showing: ${label ?: "all"}"
            refreshList()
        }
    }

    private fun filteredMessages(): List<com.radiosport.ninegradio.dsp.AcarsDecoder.AcarsMessage> {
        val f = activeFilter ?: return allMessages
        return if (f == "__OTHER__") {
            val known = setOf("H1","Q0","QM","SA","10","20","45","80","AA","B6","5Z","_d","__")
            allMessages.filter { it.label !in known }
        } else {
            allMessages.filter { it.label == f }
        }
    }

    private fun refreshList() {
        adapter.clear()
        adapter.addAll(filteredMessages())
        adapter.notifyDataSetChanged()
        updateEmptyState()
    }

    private fun updateCount() {
        tvCount.text = "${allMessages.size} msgs"
        val breakdown = allMessages.groupBy { it.label }
            .entries.sortedByDescending { it.value.size }
            .take(4).joinToString("  ") { "${it.key}:${it.value.size}" }
        tvStats.text = if (breakdown.isNotEmpty()) breakdown else "Tap message for details · Long-press to copy"
    }

    private fun updateEmptyState() {
        val empty = adapter.isEmpty
        tvEmpty.visibility  = if (empty) android.view.View.VISIBLE else android.view.View.GONE
        listView.visibility = if (empty) android.view.View.GONE    else android.view.View.VISIBLE
    }

    // ─── Actions ──────────────────────────────────────────────────────────────

    private fun clearMessages() {
        allMessages.clear()
        adapter.clear()
        adapter.notifyDataSetChanged()
        tvCount.text = "0 msgs"
        tvStats.text = "Cleared"
        updateEmptyState()
    }

    private fun exportMessages() {
        if (allMessages.isEmpty()) {
            android.widget.Toast.makeText(this, "No messages to export", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val text = buildString {
            appendLine("# ACARS Log — ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}")
            appendLine("# Channel: ${"%.3f".format(currentFreqHz / 1e6)} MHz  |  ${allMessages.size} messages")
            appendLine()
            for (msg in allMessages.reversed()) {
                val ts = TS_FMT.format(Date(msg.timestamp))
                val desc = com.radiosport.ninegradio.dsp.AcarsDecoder.LABEL_DESCRIPTIONS[msg.label] ?: "Unknown"
                appendLine("[$ts] ${msg.registration.padEnd(8)} [${msg.label}] $desc")
                if (msg.flightId.isNotBlank()) appendLine("  Flight: ${msg.flightId}")
                appendLine("  ${msg.text}")
                appendLine()
            }
        }
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("ACARS Log", text))
        android.widget.Toast.makeText(this, "${allMessages.size} messages copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun copyToClipboard(msg: com.radiosport.ninegradio.dsp.AcarsDecoder.AcarsMessage) {
        val text = formatMessageFull(msg)
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("ACARS", text))
        android.widget.Toast.makeText(this, "Copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
    }

    // ─── Detail dialog ────────────────────────────────────────────────────────

    private fun showDetail(msg: com.radiosport.ninegradio.dsp.AcarsDecoder.AcarsMessage) {
        val labelDesc = com.radiosport.ninegradio.dsp.AcarsDecoder.LABEL_DESCRIPTIONS[msg.label] ?: "Unknown label"
        android.app.AlertDialog.Builder(this)
            .setTitle("${msg.registration}  [${msg.label}]")
            .setMessage(formatMessageFull(msg))
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy") { _, _ -> copyToClipboard(msg) }
            .show()
    }

    private fun formatMessageFull(msg: com.radiosport.ninegradio.dsp.AcarsDecoder.AcarsMessage): String {
        val ts   = TS_FMT.format(Date(msg.timestamp))
        val desc = com.radiosport.ninegradio.dsp.AcarsDecoder.LABEL_DESCRIPTIONS[msg.label] ?: "Unknown"
        return buildString {
            appendLine("Time:         $ts")
            appendLine("Registration: ${msg.registration}")
            if (msg.flightId.isNotBlank()) appendLine("Flight ID:    ${msg.flightId}")
            appendLine("Label:        ${msg.label}  ($desc)")
            appendLine("Block ID:     ${msg.blockId}")
            appendLine("ACK:          ${if (msg.ack) "Yes" else "No"}")
            appendLine("More blocks:  ${if (msg.more) "Yes" else "No"}")
            appendLine()
            appendLine("Message body:")
            appendLine(msg.text.ifBlank { "(empty)" })
        }.trim()
    }
}

// ─── ACARS message adapter ────────────────────────────────────────────────────

class AcarsMessageAdapter(context: android.content.Context) :
    android.widget.ArrayAdapter<com.radiosport.ninegradio.dsp.AcarsDecoder.AcarsMessage>(
        context, R.layout.item_acars_message
    ) {

    private val inflater = android.view.LayoutInflater.from(context)
    private val tsFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
        val view = convertView ?: inflater.inflate(R.layout.item_acars_message, parent, false)
        val msg  = getItem(position) ?: return view

        val tvTime     = view.findViewById<android.widget.TextView>(R.id.tvMsgTime)
        val tvLabel    = view.findViewById<android.widget.TextView>(R.id.tvMsgLabel)
        val tvReg      = view.findViewById<android.widget.TextView>(R.id.tvMsgReg)
        val tvFlight   = view.findViewById<android.widget.TextView>(R.id.tvMsgFlight)
        val tvAck      = view.findViewById<android.widget.TextView>(R.id.tvMsgAck)
        val tvLabelDesc= view.findViewById<android.widget.TextView>(R.id.tvMsgLabelDesc)
        val tvText     = view.findViewById<android.widget.TextView>(R.id.tvMsgText)

        tvTime.text   = tsFormat.format(Date(msg.timestamp))
        tvLabel.text  = msg.label
        tvReg.text    = msg.registration.ifBlank { "?" }
        tvFlight.text = if (msg.flightId.isNotBlank()) "✈ ${msg.flightId}" else ""
        tvAck.text    = if (msg.ack) "ACK" else ""

        // Label description
        val desc = com.radiosport.ninegradio.dsp.AcarsDecoder.LABEL_DESCRIPTIONS[msg.label]
        tvLabelDesc.text    = desc ?: ""
        tvLabelDesc.visibility = if (desc != null) android.view.View.VISIBLE else android.view.View.GONE

        // Message text (show blank placeholder if empty)
        tvText.text = msg.text.trim().ifBlank { "(no text body)" }

        // Color-code label badge by message type
        val (badgeColor, textColor) = when (msg.label) {
            "H1"                     -> 0x8800AA44.toInt() to 0xFF44FF88.toInt()  // gate report — green
            "Q0"                     -> 0x880022AA.toInt() to 0xFF44AAFF.toInt()  // position — blue
            "QM"                     -> 0x88AA5500.toInt() to 0xFFFFAA44.toInt()  // weather — amber
            "SA"                     -> 0x88AA0066.toInt() to 0xFFFF88CC.toInt()  // PDC — pink
            "5Z", "45", "80"         -> 0x88550000.toInt() to 0xFFFF4444.toInt()  // ATC — red
            "_d"                     -> 0x88222222.toInt() to 0xFFAAAAAA.toInt()  // test — grey
            else                     -> 0x88003366.toInt() to 0xFF00CCFF.toInt()  // default — cyan
        }
        tvLabel.setBackgroundColor(badgeColor)
        tvLabel.setTextColor(textColor)

        // Alternate row background
        view.setBackgroundColor(
            if (position % 2 == 0) 0xFF0A1118.toInt() else 0xFF0D1520.toInt()
        )

        return view
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  BOOKMARK ACTIVITY — full RFAnalyzer-style bookmark manager
//  Features: bookmark lists (groups), search, favorites filter, add/edit/delete,
//  color markers, mode tag, import/export JSON, spectrum integration
// ═════════════════════════════════════════════════════════════════════════════

class BookmarkActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FREQ_HZ = "extra_freq_hz"
        // List-filter sentinel values used in getFiltered() DAO query:
        //   -1 = show ALL lists    -2 = show uncategorized only    >=0 = specific list id
        private const val FILTER_ALL            = -1L
        private const val FILTER_UNCATEGORIZED  = -2L
    }

    private val viewModel: MainViewModel by viewModels()

    // ── Views ────────────────────────────────────────────────────────────────
    private lateinit var recycler: androidx.recyclerview.widget.RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var etSearch: EditText
    private lateinit var btnFilterFavorites: ImageButton
    private lateinit var listChipsContainer: LinearLayout
    private lateinit var fabAdd: View

    // ── State ────────────────────────────────────────────────────────────────
    private var currentSearch        = ""
    private var filterFavoritesOnly  = false
    private var selectedListId       = FILTER_ALL   // -1 = All

    private var allLists: List<com.radiosport.ninegradio.data.BookmarkList> = emptyList()

    // ── Adapter ──────────────────────────────────────────────────────────────
    private lateinit var adapter: BookmarkAdapter
    private var filterJob: kotlinx.coroutines.Job? = null

    // ── Import/Export launchers ──────────────────────────────────────────────
    private val exportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) doExport(uri)
    }
    private val importLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) doImport(uri)
    }

    // ────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bookmark)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Bookmarks"

        recycler            = findViewById(R.id.recyclerBookmarks)
        tvEmpty             = findViewById(R.id.tvEmpty)
        etSearch            = findViewById(R.id.etSearch)
        btnFilterFavorites  = findViewById(R.id.btnFilterFavorites)
        listChipsContainer  = findViewById(R.id.listChipsContainer)
        fabAdd              = findViewById(R.id.fabAddBookmark)

        // Recycler + adapter
        adapter = BookmarkAdapter(
            onTune   = { bm -> tuneAndFinish(bm.frequencyHz) },
            onEdit   = { bm -> showEditDialog(bm) },
            onDelete = { bm -> confirmDelete(bm) },
            onFav    = { bm -> viewModel.toggleBookmarkFavorite(bm) }
        )
        recycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        recycler.adapter = adapter

        // Swipe-to-delete
        val swipe = object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
            0, androidx.recyclerview.widget.ItemTouchHelper.LEFT or
               androidx.recyclerview.widget.ItemTouchHelper.RIGHT
        ) {
            override fun onMove(rv: androidx.recyclerview.widget.RecyclerView,
                                vh: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                                t:  androidx.recyclerview.widget.RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: androidx.recyclerview.widget.RecyclerView.ViewHolder, dir: Int) {
                val bm = adapter.currentList.getOrNull(vh.adapterPosition) ?: return
                confirmDelete(bm)
                adapter.notifyItemChanged(vh.adapterPosition)
            }
        }
        androidx.recyclerview.widget.ItemTouchHelper(swipe).attachToRecyclerView(recycler)

        // Search
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                currentSearch = e?.toString()?.trim() ?: ""
                resubscribe()
            }
        })

        // Favorites toggle
        btnFilterFavorites.setOnClickListener {
            filterFavoritesOnly = !filterFavoritesOnly
            btnFilterFavorites.setImageResource(
                if (filterFavoritesOnly) R.drawable.ic_star_filled else R.drawable.ic_star_outline
            )
            btnFilterFavorites.setColorFilter(
                if (filterFavoritesOnly) android.graphics.Color.parseColor("#FFAA00")
                else android.graphics.Color.parseColor("#88FFFFFF")
            )
            resubscribe()
        }

        // FAB: add new bookmark
        fabAdd.setOnClickListener { showEditDialog(null) }

        // Observe bookmark lists for chip row
        lifecycleScope.launch {
            viewModel.bookmarkLists.collect { lists ->
                allLists = lists
                rebuildChips(lists)
            }
        }

        // Initial subscription
        resubscribe()
    }

    // ── Options menu ─────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 10, 0, "New Bookmark List")
        menu.add(0, 11, 0, "Manage Lists")
        menu.add(0, 20, 0, "Export JSON")
        menu.add(0, 21, 0, "Import JSON")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            10 -> { showListEditDialog(null); true }
            11 -> { showManageListsDialog(); true }
            20 -> { exportLauncher.launch("9gradio_bookmarks.json"); true }
            21 -> { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ── Chip row (list selector) ──────────────────────────────────────────────

    private fun rebuildChips(lists: List<com.radiosport.ninegradio.data.BookmarkList>) {
        listChipsContainer.removeAllViews()

        fun makeChip(label: String, listId: Long): TextView {
            return TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(android.graphics.Color.WHITE)
                val active = (listId == selectedListId)
                setBackgroundResource(if (active) R.drawable.bg_chip_active else R.drawable.bg_chip)
                val pad = (10 * resources.displayMetrics.density).toInt()
                val vPad = (6 * resources.displayMetrics.density).toInt()
                setPadding(pad, vPad, pad, vPad)
                val margin = (4 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = margin }
                setOnClickListener {
                    selectedListId = listId
                    rebuildChips(allLists)
                    resubscribe()
                }
            }
        }

        listChipsContainer.addView(makeChip("All", FILTER_ALL))
        listChipsContainer.addView(makeChip("Uncategorized", FILTER_UNCATEGORIZED))
        lists.forEach { bl ->
            listChipsContainer.addView(makeChip(bl.name, bl.id))
        }
    }

    // ── Data subscription ────────────────────────────────────────────────────

    private fun resubscribe() {
        filterJob?.cancel()
        filterJob = lifecycleScope.launch {
            viewModel.getBookmarksFiltered(currentSearch, filterFavoritesOnly, selectedListId)
                .collect { bms ->
                    adapter.submitList(bms)
                    tvEmpty.visibility = if (bms.isEmpty()) View.VISIBLE else View.GONE
                }
        }
    }

    // ── Tune ─────────────────────────────────────────────────────────────────

    private fun tuneAndFinish(freqHz: Long) {
        setResult(RESULT_OK, android.content.Intent().putExtra(EXTRA_FREQ_HZ, freqHz))
        finish()
    }

    // ── Add / Edit dialog ────────────────────────────────────────────────────

    private fun showEditDialog(existing: com.radiosport.ninegradio.data.Bookmark?) {
        val view = layoutInflater.inflate(R.layout.dialog_bookmark_edit, null)
        val etLabel    = view.findViewById<EditText>(R.id.etBmLabel)
        val etFreq     = view.findViewById<EditText>(R.id.etBmFreq)
        val spinMode   = view.findViewById<Spinner>(R.id.spinnerBmMode)
        val spinList   = view.findViewById<Spinner>(R.id.spinnerBmList)
        val etNotes    = view.findViewById<EditText>(R.id.etBmNotes)
        val colorSwatch = view.findViewById<View>(R.id.viewBmColorPreview)
        val btnPickColor = view.findViewById<android.widget.Button>(R.id.btnBmPickColor)
        val etSquelch  = view.findViewById<EditText>(R.id.etBmSquelch)

        var pickedColor = existing?.color ?: 0xFF2196F3.toInt()
        colorSwatch.setBackgroundColor(pickedColor)

        // Pre-fill
        if (existing != null) {
            etLabel.setText(existing.label)
            etFreq.setText("%.6f".format(existing.frequencyHz / 1e6))
            etNotes.setText(existing.notes)
            if (existing.squelch > -100f) etSquelch.setText("%.0f".format(existing.squelch))
        } else {
            etFreq.setText("%.6f".format(viewModel.centerFreqHz.value / 1e6))
            etLabel.setText("%.3f MHz".format(viewModel.centerFreqHz.value / 1e6))
        }

        // Demod mode spinner
        val modeNames = listOf("(none)") + com.radiosport.ninegradio.dsp.DemodMode.values().map { it.displayName }
        spinMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modeNames)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        if (existing != null && existing.demodMode.isNotEmpty()) {
            val idx = com.radiosport.ninegradio.dsp.DemodMode.values()
                .indexOfFirst { it.name == existing.demodMode }.let { if (it < 0) 0 else it + 1 }
            spinMode.setSelection(idx)
        }

        // Bookmark list spinner
        val listLabels = listOf("— None (Uncategorized) —") + allLists.map { it.name }
        spinList.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, listLabels)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        if (existing?.bookmarkListId != null) {
            val idx = allLists.indexOfFirst { it.id == existing.bookmarkListId }
            if (idx >= 0) spinList.setSelection(idx + 1)
        }

        // Color picker
        btnPickColor.setOnClickListener {
            showSimpleColorPicker(pickedColor) { c ->
                pickedColor = c
                colorSwatch.setBackgroundColor(c)
            }
        }

        val title = if (existing == null) "Add Bookmark" else "Edit Bookmark"
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val label = etLabel.text.toString().trim().ifBlank { "Unlabeled" }
                val freqMhz = etFreq.text.toString().toDoubleOrNull()
                if (freqMhz == null) { Toast.makeText(this, "Invalid frequency", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                val freqHz = (freqMhz * 1_000_000).toLong()
                val modeIdx = spinMode.selectedItemPosition
                val demodMode = if (modeIdx == 0) "" else com.radiosport.ninegradio.dsp.DemodMode.values()[modeIdx - 1].name
                val listIdx = spinList.selectedItemPosition
                val listId = if (listIdx == 0) null else allLists.getOrNull(listIdx - 1)?.id
                val notes = etNotes.text.toString().trim()
                val squelch = etSquelch.text.toString().toFloatOrNull() ?: -100f

                if (existing == null) {
                    viewModel.addBookmark(freqHz, label, demodMode, notes, pickedColor, listId)
                } else {
                    viewModel.updateBookmark(
                        existing.copy(frequencyHz = freqHz, label = label, demodMode = demodMode,
                            notes = notes, color = pickedColor, bookmarkListId = listId, squelch = squelch)
                    )
                }
            }
            .setNegativeButton("Cancel", null)
            .also { if (existing != null) it.setNeutralButton("Delete") { _, _ -> confirmDelete(existing) } }
            .show()
    }

    // ── Bookmark List dialogs ────────────────────────────────────────────────

    private fun showListEditDialog(existing: com.radiosport.ninegradio.data.BookmarkList?) {
        val view = layoutInflater.inflate(R.layout.dialog_bookmark_list_edit, null)
        val etName  = view.findViewById<EditText>(R.id.etListName)
        val etNotes = view.findViewById<EditText>(R.id.etListNotes)
        val swatch  = view.findViewById<View>(R.id.viewListColorPreview)
        val btnPick = view.findViewById<android.widget.Button>(R.id.btnListPickColor)

        var pickedColor = existing?.color ?: 0xFF2196F3.toInt()
        swatch.setBackgroundColor(pickedColor)
        existing?.let { etName.setText(it.name); etNotes.setText(it.notes) }

        btnPick.setOnClickListener {
            showSimpleColorPicker(pickedColor) { c -> pickedColor = c; swatch.setBackgroundColor(c) }
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (existing == null) "New Bookmark List" else "Edit List")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val name = etName.text.toString().trim().ifBlank { "Unnamed List" }
                val notes = etNotes.text.toString().trim()
                if (existing == null) viewModel.addBookmarkList(name, notes, pickedColor)
                else viewModel.updateBookmarkList(existing.copy(name = name, notes = notes, color = pickedColor))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showManageListsDialog() {
        if (allLists.isEmpty()) {
            Toast.makeText(this, "No lists created yet. Use 'New Bookmark List'.", Toast.LENGTH_SHORT).show()
            return
        }
        val names = allLists.map { it.name }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Manage Lists")
            .setItems(names) { _, idx ->
                val list = allLists[idx]
                val opts = arrayOf("Edit", "Delete (keep bookmarks)", "Delete (delete bookmarks)")
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(list.name)
                    .setItems(opts) { _, opt ->
                        when (opt) {
                            0 -> showListEditDialog(list)
                            1 -> viewModel.deleteBookmarkList(list, deleteContents = false)
                            2 -> androidx.appcompat.app.AlertDialog.Builder(this)
                                    .setTitle("Delete list and all ${list.name} bookmarks?")
                                    .setPositiveButton("Delete All") { _, _ ->
                                        viewModel.deleteBookmarkList(list, deleteContents = true)
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                        }
                    }
                    .show()
            }
            .setNeutralButton("New List") { _, _ -> showListEditDialog(null) }
            .show()
    }

    // ── Delete confirmation ───────────────────────────────────────────────────

    private fun confirmDelete(bm: com.radiosport.ninegradio.data.Bookmark) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Delete bookmark?")
            .setMessage("\"${bm.label}\" will be removed.")
            .setPositiveButton("Delete") { _, _ -> viewModel.deleteBookmark(bm) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Simple color picker ───────────────────────────────────────────────────

    private fun showSimpleColorPicker(current: Int, onPick: (Int) -> Unit) {
        val colors = intArrayOf(
            0xFF2196F3.toInt(), 0xFF00BCD4.toInt(), 0xFF4CAF50.toInt(),
            0xFF8BC34A.toInt(), 0xFFFFC107.toInt(), 0xFFFF9800.toInt(),
            0xFFF44336.toInt(), 0xFFE91E63.toInt(), 0xFF9C27B0.toInt(),
            0xFF3F51B5.toInt(), 0xFF009688.toInt(), 0xFFFFFFFF.toInt()
        )
        val labels = arrayOf(
            "Blue", "Cyan", "Green", "Light Green", "Amber",
            "Orange", "Red", "Pink", "Purple", "Indigo", "Teal", "White"
        )
        var selectedIdx = colors.indexOfFirst { it == current }.takeIf { it >= 0 } ?: 0
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.select_dialog_singlechoice, labels) {
            override fun getView(pos: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val tv = super.getView(pos, convertView, parent) as TextView
                tv.setCompoundDrawablesWithIntrinsicBounds(null,
                    android.graphics.drawable.ColorDrawable(colors[pos]).also {
                        // wrap in a sized drawable
                    }, null, null)
                tv.setTextColor(colors[pos])
                return tv
            }
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Pick Color")
            .setSingleChoiceItems(labels, selectedIdx) { _, which -> selectedIdx = which }
            .setPositiveButton("OK") { _, _ -> onPick(colors[selectedIdx]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Import / Export ───────────────────────────────────────────────────────

    private fun doExport(uri: android.net.Uri) {
        lifecycleScope.launch {
            val bms = viewModel.bookmarks.first()
            val result = com.radiosport.ninegradio.data.DataExporter.exportBookmarks(bms, uri, this@BookmarkActivity)
            result.fold(
                onSuccess = { n -> Toast.makeText(this@BookmarkActivity, "Exported $n bookmarks", Toast.LENGTH_SHORT).show() },
                onFailure = { e -> Toast.makeText(this@BookmarkActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show() }
            )
        }
    }

    private fun doImport(uri: android.net.Uri) {
        lifecycleScope.launch {
            val result = com.radiosport.ninegradio.data.DataExporter.importBookmarks(uri, this@BookmarkActivity)
            result.fold(
                onSuccess = { bms ->
                    viewModel.importBookmarks(bms)
                    Toast.makeText(this@BookmarkActivity, "Imported ${bms.size} bookmarks", Toast.LENGTH_SHORT).show()
                },
                onFailure = { e -> Toast.makeText(this@BookmarkActivity, "Import failed: ${e.message}", Toast.LENGTH_LONG).show() }
            )
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  BOOKMARK ADAPTER
// ═════════════════════════════════════════════════════════════════════════════

private class BookmarkAdapter(
    private val onTune:   (com.radiosport.ninegradio.data.Bookmark) -> Unit,
    private val onEdit:   (com.radiosport.ninegradio.data.Bookmark) -> Unit,
    private val onDelete: (com.radiosport.ninegradio.data.Bookmark) -> Unit,
    private val onFav:    (com.radiosport.ninegradio.data.Bookmark) -> Unit,
) : androidx.recyclerview.widget.ListAdapter<
        com.radiosport.ninegradio.data.Bookmark,
        BookmarkAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : androidx.recyclerview.widget.DiffUtil.ItemCallback<com.radiosport.ninegradio.data.Bookmark>() {
            override fun areItemsTheSame(a: com.radiosport.ninegradio.data.Bookmark,
                                         b: com.radiosport.ninegradio.data.Bookmark) = a.id == b.id
            override fun areContentsTheSame(a: com.radiosport.ninegradio.data.Bookmark,
                                             b: com.radiosport.ninegradio.data.Bookmark) = a == b
        }
    }

    inner class VH(v: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {
        val swatch:   View        = v.findViewById(R.id.viewColorSwatch)
        val tvLabel:  TextView    = v.findViewById(R.id.tvBookmarkLabel)
        val tvFreq:   TextView    = v.findViewById(R.id.tvBookmarkFreq)
        val tvMode:   TextView    = v.findViewById(R.id.tvBookmarkMode)
        val tvList:   TextView    = v.findViewById(R.id.tvBookmarkList)
        val tvNotes:  TextView    = v.findViewById(R.id.tvBookmarkNotes)
        val btnFav:   ImageButton = v.findViewById(R.id.btnFavorite)
        val btnTune:  android.widget.Button = v.findViewById(R.id.btnTune)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val v = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_bookmark, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val bm = getItem(pos)
        h.swatch.setBackgroundColor(bm.color)
        h.tvLabel.text = bm.label
        h.tvFreq.text  = "%.3f MHz".format(bm.frequencyHz / 1e6)

        // Mode tag
        if (bm.demodMode.isNotEmpty()) {
            h.tvMode.text = bm.demodMode
            h.tvMode.visibility = View.VISIBLE
        } else {
            h.tvMode.visibility = View.GONE
        }

        // Notes preview
        if (bm.notes.isNotEmpty()) {
            h.tvNotes.text = bm.notes
            h.tvNotes.visibility = View.VISIBLE
        } else {
            h.tvNotes.visibility = View.GONE
        }

        // Favorite star
        h.btnFav.setImageResource(
            if (bm.favorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline
        )
        h.btnFav.setOnClickListener { onFav(bm) }

        // Tune
        h.btnTune.setOnClickListener { onTune(bm) }

        // Long-press: edit
        h.itemView.setOnLongClickListener { onEdit(bm); true }
        h.itemView.setOnClickListener { onTune(bm) }
    }
}

