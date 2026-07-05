package com.radiosport.ninegradio.ui

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.*
import android.os.Bundle
import android.os.IBinder
import android.view.*
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.radiosport.ninegradio.R
import com.radiosport.ninegradio.dsp.AdsbDecoder
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.usb.RtlSdrService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * Full-screen ADS-B radar display.
 * Auto-tunes RTL-SDR to 1090 MHz and collects raw IQ magnitude from the
 * DspEngine's iqMagnitudeFlow — the correct input for the Mode-S preamble
 * detector (2 samples/µs at 2.048 MS/s).
 */
class AdsbActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var radarView: AdsbRadarView
    private lateinit var tvAircraftCount: TextView
    private lateinit var tvStatus: TextView

    private val decoder = AdsbDecoder()
    private val aircraft = HashMap<String, AdsbDecoder.AdsbFrame>()
    private val aircraftList = mutableListOf<String>()
    private lateinit var listAdapter: android.widget.ArrayAdapter<String>

    // ─── Service binding for IQ magnitude flow ────────────────────────────────

    private var sdrService: RtlSdrService? = null
    private var serviceBound = false
    private var iqFeedJob: Job? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            sdrService = (binder as RtlSdrService.LocalBinder).getService()
            serviceBound = true
            startIqFeed()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            iqFeedJob?.cancel()
            sdrService = null
            serviceBound = false
        }
    }

    /** Feed raw IQ magnitude (not FFT) to the ADS-B preamble detector,
     *  and subscribe to the service's own connectionState so the status bar
     *  always reflects reality (avoids the Activity-scoped ViewModel issue). */
    private fun startIqFeed() {
        iqFeedJob?.cancel()
        val svc = sdrService ?: return
        iqFeedJob = lifecycleScope.launch {
            launch {
                svc.connectionState.collectLatest { state ->
                    tvStatus.text = when (state) {
                        is RtlSdrService.ConnectionState.Connected  -> "LIVE — 1090 MHz"
                        is RtlSdrService.ConnectionState.Connecting -> "Connecting…"
                        else -> "No Device"
                    }
                }
            }
            svc.dspEngine?.iqMagnitudeFlow?.collect { mag ->
                decoder.feed(mag)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_adsb)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "ADS-B Radar (1090 MHz)"

        bindService(Intent(this, RtlSdrService::class.java), serviceConnection, BIND_AUTO_CREATE)

        radarView       = findViewById(R.id.radarView)
        tvAircraftCount = findViewById(R.id.tvAircraftCount)
        tvStatus        = findViewById(R.id.tvAdsbStatus)

        val listAircraft = findViewById<android.widget.ListView>(R.id.listAircraft)
        listAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, aircraftList)
        listAircraft.adapter = listAdapter
        listAircraft.setOnItemClickListener { _, _, pos, _ ->
            val entry = aircraftList.getOrNull(pos) ?: return@setOnItemClickListener
            android.app.AlertDialog.Builder(this)
                .setTitle("Aircraft Detail")
                .setMessage(entry.replace(" | ", "\n"))
                .setPositiveButton("OK", null).show()
        }

        // Auto-tune to 1090 MHz and switch to ADS-B mode.
        // setDemodMode() saves the previous mode's settings and restores any
        // previously saved ADS-B settings.  On first use (no snapshot yet) we
        // apply the protocol-required defaults so they become the ADS-B baseline.
        viewModel.setFrequency(AdsbDecoder.ADSB_FREQ_HZ)
        viewModel.setDemodMode(DemodMode.ADSB)
        if (!viewModel.hasModeSnapshot(DemodMode.ADSB)) {
            // First-ever ADS-B launch: seed protocol-required defaults.
            // 1.920 MS/s = 48 000 × 40 — perfect integer decimation to the 48 kHz
            // audio output rate; provides ample bandwidth for the 1.090 GHz Mode-S
            // channel and is within the RTL-SDR's main band.
            val adsbPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            viewModel.setSampleRate(1_920_000)
            viewModel.setTunerAgc(adsbPrefs.getBoolean("pref_tuner_agc_default", false))
            viewModel.setHardwareAgc(adsbPrefs.getBoolean("pref_hardware_agc_default", false))
            if (!adsbPrefs.getBoolean("pref_tuner_agc_default", false))
                viewModel.setGain(adsbPrefs.getInt("pref_default_gain", 26))
        }

        // Collect decoded frames
        lifecycleScope.launch {
            decoder.frames.collectLatest { frame ->
                aircraft[frame.icao24] = frame
                // Remove stale tracks (>2 min old)
                val cutoff = System.currentTimeMillis() - 120_000L
                aircraft.entries.removeAll { it.value.timestamp < cutoff }
                radarView.updateAircraft(aircraft.values.toList())
                tvAircraftCount.text = "Aircraft: ${aircraft.size}"

                // Update text list
                aircraftList.clear()
                aircraft.values
                    .sortedByDescending { it.altitude ?: -1 }
                    .forEach { ac ->
                        val callsign = ac.callsign?.trim()?.ifBlank { ac.icao24 } ?: ac.icao24
                        val alt = ac.altitude?.let { "FL${it / 100}" } ?: "??"
                        val pos = if (ac.latitude != null && ac.longitude != null)
                            "${"%.2f".format(ac.latitude)},${"%.2f".format(ac.longitude)}"
                        else "no pos"
                        val spd = ac.velocity?.let { "${it}kt" } ?: ""
                        aircraftList.add("$callsign | $alt | $pos $spd")
                    }
                listAdapter.notifyDataSetChanged()
            }
        }

        // Status indicator — read connectionState directly from the bound service.
        // viewModel.connectionState is scoped to this Activity's own ViewModel instance
        // (separate from MainActivity's), so it always starts Disconnected and shows
        // "No Device" even when a device is live.  We start the observer in
        // startIqFeed() once sdrService is available instead.
    }

    override fun onDestroy() {
        super.onDestroy()
        iqFeedJob?.cancel()
        if (serviceBound) { unbindService(serviceConnection); serviceBound = false }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}

/**
 * Radar sweep display showing aircraft positions on a polar grid.
 */
class AdsbRadarView @JvmOverloads constructor(
    context: android.content.Context,
    attrs: android.util.AttributeSet? = null
) : View(context, attrs) {

    private var aircraftList = listOf<AdsbDecoder.AdsbFrame>()
    private var ownLat = 0.0
    private var ownLon = 0.0
    private var rangeNm = 250.0  // display range in nautical miles

    private val bgPaint    = Paint().apply { color = 0xFF050D12.toInt(); style = Paint.Style.FILL }
    private val gridPaint  = Paint().apply { color = 0x2200FF44.toInt(); style = Paint.Style.STROKE; strokeWidth = 0.7f; isAntiAlias = true }
    private val sweepPaint = Paint().apply { color = 0x4400FF44.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f; isAntiAlias = true }
    private val dotPaint   = Paint().apply { color = 0xFF00FF88.toInt(); style = Paint.Style.FILL; isAntiAlias = true }
    private val textPaint  = Paint().apply { color = 0xCC00FF88.toInt(); textSize = 24f; isAntiAlias = true; typeface = Typeface.MONOSPACE }
    private val labelPaint = Paint().apply { color = 0x8800AAFF.toInt(); textSize = 18f; isAntiAlias = true }
    private val sweepGradPaint = Paint().apply { style = Paint.Style.FILL }

    private var sweepAngle = 0f
    private val sweepAnimator = android.animation.ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 4000
        repeatCount = android.animation.ValueAnimator.INFINITE
        interpolator = android.view.animation.LinearInterpolator()
        addUpdateListener { anim ->
            sweepAngle = anim.animatedValue as Float
            invalidate()
        }
        start()
    }

    fun updateAircraft(list: List<AdsbDecoder.AdsbFrame>) {
        aircraftList = list.filter { it.latitude != null && it.longitude != null }
        postInvalidate()
    }

    fun setOwnPosition(lat: Double, lon: Double) { ownLat = lat; ownLon = lon }
    fun setRange(nm: Double) { rangeNm = nm }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r  = minOf(cx, cy) * 0.94f

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Range rings
        for (i in 1..4) {
            val ringR = r * i / 4f
            canvas.drawCircle(cx, cy, ringR, gridPaint)
            labelPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("${(rangeNm * i / 4).toInt()} nm", cx + ringR + 4f, cy - 4f, labelPaint)
        }

        // Cardinal lines
        canvas.drawLine(cx, cy - r, cx, cy + r, gridPaint)
        canvas.drawLine(cx - r, cy, cx + r, cy, gridPaint)

        // Sweep gradient
        val sweepMatrix = Matrix().apply { postTranslate(-cx, -cy); postRotate(sweepAngle); postTranslate(cx, cy) }
        val sweepShader = SweepGradient(cx, cy,
            intArrayOf(0x0000FF44, 0x8800FF44.toInt(), 0x0000FF44), null)
        sweepShader.setLocalMatrix(sweepMatrix)
        sweepGradPaint.shader = sweepShader
        canvas.drawCircle(cx, cy, r, sweepGradPaint)

        // Sweep arm
        val sweepRad = Math.toRadians(sweepAngle.toDouble())
        canvas.drawLine(cx, cy,
            cx + (r * Math.sin(sweepRad)).toFloat(),
            cy - (r * Math.cos(sweepRad)).toFloat(),
            sweepPaint)

        // Aircraft blips
        for (ac in aircraftList) {
            val lat = ac.latitude ?: continue
            val lon = ac.longitude ?: continue

            val (px, py) = latLonToPixel(lat, lon, cx, cy, r)

            val ageSec = (System.currentTimeMillis() - ac.timestamp) / 1000L
            val alpha = (255 * (1.0 - ageSec / 120.0)).toInt().coerceIn(80, 255)
            dotPaint.alpha = alpha

            val dotR = when {
                (ac.altitude ?: 0) > 30_000 -> 7f
                (ac.altitude ?: 0) > 10_000 -> 5f
                else -> 4f
            }
            canvas.drawCircle(px, py, dotR, dotPaint)

            textPaint.alpha = (alpha * 0.8f).toInt()
            val label = ac.callsign?.trim()?.ifBlank { ac.icao24 } ?: ac.icao24
            canvas.drawText(label, px + 8f, py - 8f, textPaint)

            ac.altitude?.let {
                textPaint.alpha = (alpha * 0.5f).toInt()
                textPaint.textSize = 18f
                canvas.drawText("FL${it / 100}", px + 8f, py + 14f, textPaint)
                textPaint.textSize = 24f
            }
        }

        // Own position
        dotPaint.alpha = 255
        dotPaint.color = 0xFFFFFF00.toInt()
        canvas.drawCircle(cx, cy, 5f, dotPaint)
        dotPaint.color = 0xFF00FF88.toInt()

        // Compass labels
        textPaint.alpha = 180; textPaint.color = 0xFFCCFFCC.toInt()
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("N", cx, cy - r - 4f, textPaint)
        canvas.drawText("S", cx, cy + r + 20f, textPaint)
        canvas.drawText("W", cx - r - 4f, cy + 8f, textPaint)
        canvas.drawText("E", cx + r + 4f, cy + 8f, textPaint)
        textPaint.color = 0xFF00FF88.toInt()
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun latLonToPixel(lat: Double, lon: Double, cx: Float, cy: Float, r: Float): Pair<Float, Float> {
        val dLat = lat - ownLat
        val dLon = (lon - ownLon) * Math.cos(Math.toRadians(ownLat))
        val distNm = Math.sqrt(dLat * dLat + dLon * dLon) * 60.0
        val bearing = Math.atan2(dLon, dLat)
        val pixR = (distNm / rangeNm * r).toFloat().coerceAtMost(r)
        val px = cx + pixR * Math.sin(bearing).toFloat()
        val py = cy - pixR * Math.cos(bearing).toFloat()
        return Pair(px, py)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        sweepAnimator.cancel()
    }
}
