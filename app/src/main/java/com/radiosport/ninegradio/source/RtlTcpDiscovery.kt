package com.radiosport.ninegradio.source

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Discovers rtl_tcp / rtl_tcp_andro servers on the local network.
 *
 * Three parallel strategies run simultaneously:
 *
 * 1. **NSD / mDNS** — listens for `_rtl_tcp._tcp.local` service announcements.
 *    SDR-specific apps (like SDR++) advertise this; rtl_tcp_andro itself does not
 *    but third-party wrappers sometimes do.
 *
 * 2. **UDP broadcast probe** — sends a short `RTL0` probe to the subnet broadcast
 *    address on common rtl_tcp ports (1234, 1235, 2000).  Some wrapper scripts echo
 *    the RTL0 magic back; the probe also forces ARP/multicast resolution which makes
 *    the TCP connect in step 3 faster.
 *
 * 3. **TCP connect + magic handshake** — the authoritative check.  Tries each
 *    candidate host:port, connects, and reads the 4-byte `RTL0` magic.  If
 *    confirmed, adds the entry to [discovered].
 *
 * Discovered servers are accumulated in [discovered] (a StateFlow) so the UI can
 * update reactively as results arrive.
 */
class RtlTcpDiscovery(private val context: Context) {

    companion object {
        private const val TAG = "RtlTcpDiscovery"

        /** mDNS service type used by SDR++ and some rtl_tcp wrappers. */
        private const val NSD_SERVICE_TYPE = "_rtl_tcp._tcp"

        /** Ports to probe — 1234 is the rtl_tcp default; 2000 is used by some Docker images. */
        val PROBE_PORTS = intArrayOf(1234, 1235, 2000, 4711)

        /** RTL0 magic bytes. */
        private val RTL0_MAGIC = "RTL0".toByteArray(Charsets.US_ASCII)

        /** Timeout for a single TCP connect-and-magic attempt. */
        private const val CONNECT_TIMEOUT_MS = 800

        /** How long we listen for NSD announcements. */
        private const val NSD_LISTEN_MS = 5_000L
    }

    data class DiscoveredServer(
        val host: String,
        val port: Int,
        val name: String,           // display label
        val via: String             // "NSD", "UDP probe", "localhost", "LAN scan"
    ) {
        val displayLabel: String get() = "$name  ($host:$port)  [$via]"
    }

    private val _discovered = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val discovered: StateFlow<List<DiscoveredServer>> = _discovered.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val seen = mutableSetOf<String>() // "host:port" dedup keys
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Public API ────────────────────────────────────────────────────────────

    fun startScan() {
        if (_scanning.value) return
        _scanning.value = true
        seen.clear()
        _discovered.value = emptyList()

        scope.launch {
            try {
                // Run all strategies in parallel
                val jobs = listOf(
                    launch { scanLocalhost() },
                    launch { scanViaNsd() },
                    launch { scanViaUdpProbe() }
                )
                jobs.joinAll()
            } finally {
                _scanning.value = false
            }
        }
    }

    fun stopScan() {
        scope.coroutineContext.cancelChildren()
        nsdManager?.stopServiceDiscovery(discoveryListener)
        nsdManager = null
        _scanning.value = false
    }

    // ── Strategy 1: localhost (covers rtl_tcp_andro on the same device) ───────

    private suspend fun scanLocalhost() {
        val localhost = listOf("127.0.0.1", "localhost")
        for (host in localhost) {
            for (port in PROBE_PORTS) {
                tryConnect(host, port, "RTLSDR Driver (localhost)", "localhost")
            }
        }
    }

    // ── Strategy 2: NSD / mDNS ───────────────────────────────────────────────

    @Volatile private var nsdManager: NsdManager? = null

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(st: String, e: Int) {
            Log.w(TAG, "NSD start failed $e")
        }
        override fun onStopDiscoveryFailed(st: String, e: Int) {}
        override fun onDiscoveryStarted(st: String) { Log.d(TAG, "NSD started: $st") }
        override fun onDiscoveryStopped(st: String) { Log.d(TAG, "NSD stopped") }
        override fun onServiceLost(info: NsdServiceInfo) {}

        override fun onServiceFound(info: NsdServiceInfo) {
            Log.d(TAG, "NSD found: ${info.serviceName}")
            nsdManager?.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, e: Int) {
                    Log.w(TAG, "NSD resolve failed $e for ${i.serviceName}")
                }
                override fun onServiceResolved(i: NsdServiceInfo) {
                    val host = i.host?.hostAddress ?: return
                    val port = i.port
                    val name = i.serviceName ?: "rtl_tcp"
                    scope.launch { tryConnect(host, port, name, "NSD") }
                }
            })
        }
    }

    private suspend fun scanViaNsd() {
        val mgr = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsdManager = mgr
        try {
            mgr.discoverServices(NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            delay(NSD_LISTEN_MS)
        } catch (e: Exception) {
            Log.w(TAG, "NSD error: ${e.message}")
        } finally {
            try { mgr.stopServiceDiscovery(discoveryListener) } catch (_: Exception) {}
            nsdManager = null
        }
    }

    // ── Strategy 3: UDP broadcast probe + LAN subnet scan ────────────────────

    private suspend fun scanViaUdpProbe() {
        val wifiMgr = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return

        @Suppress("DEPRECATION")
        val dhcpInfo = wifiMgr.dhcpInfo ?: return

        // Derive broadcast address from IP + netmask
        val ipInt   = dhcpInfo.ipAddress
        val maskInt = dhcpInfo.netmask
        val broadcastInt = (ipInt and maskInt) or maskInt.inv()

        fun intToIp(i: Int): String {
            return "${i and 0xFF}.${(i shr 8) and 0xFF}.${(i shr 16) and 0xFF}.${(i shr 24) and 0xFF}"
        }

        val broadcastAddr = intToIp(broadcastInt)
        val myIp          = intToIp(ipInt)
        val networkBase   = intToIp(ipInt and maskInt)

        Log.d(TAG, "UDP probe: my=$myIp broadcast=$broadcastAddr network=$networkBase")

        // Send RTL0 probe to broadcast on each port; any echo confirms a server.
        // Whether or not we get a UDP reply, we'll still TCP-verify candidates.
        val udpCandidates = mutableSetOf<Pair<String, Int>>()

        withContext(Dispatchers.IO) {
            try {
                val socket = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 300
                }
                socket.use { sock ->
                    for (port in PROBE_PORTS) {
                        val probe = DatagramPacket(
                            RTL0_MAGIC, RTL0_MAGIC.size,
                            InetAddress.getByName(broadcastAddr), port
                        )
                        try { sock.send(probe) } catch (_: Exception) {}
                    }
                    // Listen briefly for any UDP echo
                    val buf = ByteArray(64)
                    val reply = DatagramPacket(buf, buf.size)
                    val deadline = System.currentTimeMillis() + 1_000L
                    while (System.currentTimeMillis() < deadline) {
                        try {
                            sock.receive(reply)
                            val replyHost = reply.address?.hostAddress ?: continue
                            // Check for RTL0 magic in reply
                            if (buf.size >= 4 &&
                                buf[0] == 'R'.code.toByte() && buf[1] == 'T'.code.toByte() &&
                                buf[2] == 'L'.code.toByte() && buf[3] == '0'.code.toByte()) {
                                val replyPort = reply.port.let {
                                    // The reply port may be ephemeral; use the probe port
                                    PROBE_PORTS.firstOrNull() ?: 1234
                                }
                                udpCandidates.add(replyHost to replyPort)
                                Log.d(TAG, "UDP echo from $replyHost:$replyPort")
                            }
                        } catch (_: Exception) { break }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "UDP probe error: ${e.message}")
            }
        }

        // TCP-verify all UDP candidates
        for ((host, port) in udpCandidates) {
            tryConnect(host, port, "RTLSDR Driver", "UDP probe")
        }

        // Subnet scan: /24 assumed (256 hosts × 4 ports = 1024 probes, parallelised)
        val prefix = myIp.substringBeforeLast(".")
        val myLastOctet = myIp.substringAfterLast(".").toIntOrNull() ?: 1

        withContext(Dispatchers.IO) {
            val probeJobs = mutableListOf<Job>()
            for (last in 1..254) {
                if (last == myLastOctet) continue   // skip ourselves
                val host = "$prefix.$last"
                for (port in PROBE_PORTS) {
                    probeJobs += launch {
                        tryConnect(host, port, "RTLSDR Driver", "LAN scan")
                    }
                }
            }
            probeJobs.joinAll()
        }
    }

    // ── TCP connect + RTL0 magic verify ───────────────────────────────────────

    private fun tryConnect(host: String, port: Int, name: String, via: String) {
        val key = "$host:$port"
        synchronized(seen) { if (!seen.add(key)) return }

        try {
            val socket = Socket()
            socket.use {
                it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                it.soTimeout = 1_000
                val buf = ByteArray(4)
                var read = 0
                val deadline = System.currentTimeMillis() + 1_000L
                while (read < 4 && System.currentTimeMillis() < deadline) {
                    val n = it.getInputStream().read(buf, read, 4 - read)
                    if (n < 0) return
                    read += n
                }
                if (read < 4) return
                if (buf[0] != 'R'.code.toByte() || buf[1] != 'T'.code.toByte() ||
                    buf[2] != 'L'.code.toByte() || buf[3] != '0'.code.toByte()) return

                // Confirmed rtl_tcp server
                Log.i(TAG, "Found rtl_tcp @ $host:$port via $via")
                val displayName = when {
                    host == "127.0.0.1" || host == "localhost" -> "RTLSDR Driver (this device)"
                    name != "RTLSDR Driver" -> name
                    else -> "RTLSDR Driver"
                }
                val server = DiscoveredServer(host, port, displayName, via)
                _discovered.value = (_discovered.value + server)
                    .distinctBy { "${it.host}:${it.port}" }
            }
        } catch (_: Exception) {
            // Not reachable or not an rtl_tcp server — silently skip
        }
    }
}
