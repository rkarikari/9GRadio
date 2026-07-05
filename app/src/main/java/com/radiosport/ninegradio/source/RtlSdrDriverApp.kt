package com.radiosport.ninegradio.source

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

/**
 * Detects the "RTLSDR Driver" app (rtl_tcp_andro by Signalware Ltd) and
 * provides helpers to launch it as a TCP server.
 *
 * The app exposes a `DeviceOpenActivity` that accepts an `iqsrc://` URI
 * with these query-style space-separated arguments:
 *
 *   -a <bind address>   TCP listen address (use 127.0.0.1 for local-only)
 *   -p <port>           TCP port (default 1234)
 *   -s <samplerate>     Sample rate in Hz
 *   -f <frequency>      Initial centre frequency in Hz
 *   -g <gain>           Gain in tenths of dB (e.g. 496 = 49.6 dB)
 *   -P <ppm>            Frequency correction in PPM
 *   -T <biast>          Bias-tee: 0=off, 1=on
 *
 * Example: `iqsrc://-a 127.0.0.1 -p 1234 -s 2048000 -f 100000000 -g 24 -P 0`
 */
object RtlSdrDriverApp {

    private const val TAG = "RtlSdrDriverApp"

    /**
     * Known package names for RTL-SDR driver apps that expose the iqsrc:// interface.
     *
     *  - com.sdrtouch.rtlsdr  — "RTLSDR Driver" by Signalware / SDRTouch (original)
     *  - marto.rtl_tcp_andro  — "Rtl-sdr driver" by martinmarinov (fork, same protocol)
     *
     * The list is checked in order; the first installed package wins.
     */
    private val KNOWN_PACKAGES = listOf(
        "com.sdrtouch.rtlsdr",
        "marto.rtl_tcp_andro"
    )

    /** Package name of the RTLSDR Driver / rtl_tcp_andro app (kept for binary compat). */
    const val PACKAGE = "com.sdrtouch.rtlsdr"

    /** Activity that accepts the iqsrc:// intent to start the TCP server. */
    private const val DEVICE_OPEN_ACTIVITY_SDRTOUCH = "com.sdrtouch.rtlsdr.DeviceOpenActivity"
    private const val DEVICE_OPEN_ACTIVITY_MARTO    = "marto.rtl_tcp_andro.DeviceOpenActivity"

    /** Main/launcher activity for each variant. */
    private const val STREAM_ACTIVITY_SDRTOUCH = "com.sdrtouch.rtlsdr.StreamActivity"
    private const val STREAM_ACTIVITY_MARTO    = "marto.rtl_tcp_andro.StreamActivity"

    /** Default bind address — loopback keeps the stream on-device. */
    const val DEFAULT_ADDRESS = "127.0.0.1"
    const val DEFAULT_PORT    = 1234

    // ── Detection ─────────────────────────────────────────────────────────────

    /**
     * Returns the package name of the first installed RTL-SDR driver app, or null if none found.
     */
    fun installedPackage(context: Context): String? {
        val pm = context.packageManager
        return KNOWN_PACKAGES.firstOrNull { pkg ->
            try { pm.getPackageInfo(pkg, 0); true } catch (_: PackageManager.NameNotFoundException) { false }
        }
    }

    /**
     * Returns true if any supported RTL-SDR driver app is installed.
     * Uses PackageManager; no special permission required.
     */
    fun isInstalled(context: Context): Boolean = installedPackage(context) != null

    /**
     * Returns the displayed app label, or "RTLSDR Driver" if no driver app is installed.
     */
    fun getAppLabel(context: Context): String {
        val pkg = installedPackage(context) ?: return "RTLSDR Driver"
        return try {
            val pm   = context.packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            "RTLSDR Driver"
        }
    }

    // ── Launch ────────────────────────────────────────────────────────────────

    /**
     * Launch the RTLSDR Driver app so it starts its rtl_tcp server and
     * listens on [address]:[port].
     *
     * The `iqsrc://` intent triggers [DEVICE_OPEN_ACTIVITY], which:
     *  1. Asks the user to grant USB access (if not already granted).
     *  2. Opens the RTL-SDR dongle.
     *  3. Starts the rtl_tcp TCP server on [address]:[port].
     *  4. Returns to the background — 9GRadio can then connect via TCP.
     *
     * @param sampleRate  Hint passed to the driver (it may override it).
     * @param frequencyHz Initial tune frequency hint.
     * @param gainTenthsDb  Gain in tenths of dB (e.g. 496 = 49.6 dB).  0 = AGC.
     * @param ppm         Frequency correction.
     * @param biasTee     Bias-tee on/off.
     * @return true if the intent was dispatched successfully.
     */
    /**
     * Request code for startActivityForResult.
     * RESULT_OK in onActivityResult means the driver server is running — connect TCP then.
     */
    const val REQUEST_CODE = 1234

    /**
     * Launch the RTLSDR Driver app using startActivityForResult with a bare iqsrc:// URI.
     *
     * RESULT_OK is returned by DeviceOpenActivity once USB permission is granted and the
     * rtl_tcp server is listening on [address]:[port].  The caller must implement
     * onActivityResult and call connectTcp() on RESULT_OK.
     *
     * IMPORTANT: bare ACTION_VIEW intent — do NOT setClassName or FLAG_ACTIVITY_NEW_TASK.
     * Those prevent the result callback from firing.
     */
    fun launchAsServer(
        activity: android.app.Activity,
        address: String    = DEFAULT_ADDRESS,
        port: Int          = DEFAULT_PORT,
        sampleRate: Long   = 2_048_000L,
        frequencyHz: Long  = 100_000_000L,
        gainTenthsDb: Int  = 24,
        ppm: Int           = 0,
        biasTee: Boolean   = false
    ): Boolean {
        if (!isInstalled(activity)) {
            Log.w(TAG, "No supported RTLSDR Driver app installed")
            return false
        }

        val uri = buildIqSrcUri(address, port, sampleRate, frequencyHz, gainTenthsDb, ppm, biasTee)

        // Bare ACTION_VIEW — let Android resolve the correct installed driver variant.
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))

        return try {
            activity.startActivityForResult(intent, REQUEST_CODE)
            Log.i(TAG, "startActivityForResult iqsrc: $uri")
            true
        } catch (e: android.content.ActivityNotFoundException) {
            Log.e(TAG, "No activity found for iqsrc:// intent", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch RTLSDR Driver", e)
            false
        }
    }

    /**
     * Open the RTLSDR Driver app's own main UI (StreamActivity).
     * Use this when the user just wants to open the app, not start a server session.
     */
    fun launchMainUi(context: Context): Boolean {
        val pkg = installedPackage(context) ?: return false
        val streamActivity = if (pkg == "marto.rtl_tcp_andro")
            STREAM_ACTIVITY_MARTO else STREAM_ACTIVITY_SDRTOUCH
        return try {
            val intent = Intent().apply {
                setClassName(pkg, streamActivity)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open RTLSDR Driver UI", e)
            false
        }
    }

    /**
     * Open the Google Play Store page for the RTLSDR Driver (if not installed).
     */
    fun openPlayStore(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=$PACKAGE")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { context.startActivity(intent) } catch (_: Exception) {}
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun buildIqSrcUri(
        address: String, port: Int, sampleRate: Long,
        frequencyHz: Long, gainTenthsDb: Int, ppm: Int, biasTee: Boolean
    ): String = buildString {
        append("iqsrc://")
        append("-a $address ")
        append("-p $port ")
        append("-s $sampleRate ")
        append("-f $frequencyHz ")
        append("-g $gainTenthsDb ")
        append("-P $ppm ")
        append("-T ${if (biasTee) 1 else 0}")
    }
}
