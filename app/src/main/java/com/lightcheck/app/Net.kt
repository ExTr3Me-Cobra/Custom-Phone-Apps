package com.lightcheck.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/** Something that answered on the home Wi-Fi. */
data class Found(val ip: String, val name: String, val how: String, val brand: String)

/**
 * Finding devices on the home Wi-Fi and checking they still answer. A device counts as there if
 * it answers a ping, accepts or refuses a connection on a port smart lights use (a refusal still
 * means something answered), or answers WiZ's local protocol. A light with its power cut at the
 * switch does none of these.
 */
object Net {

    /** Ports smart lights and their hubs listen on, with the brand each usually means. */
    private val PORTS = linkedMapOf(
        9999 to "TP-Link Kasa / Tapo",
        6668 to "Tuya / Smart Life",
        55443 to "Yeelight",
        80 to "",
        443 to "",
        8080 to ""
    )

    private val NAME_BRANDS = listOf(
        "wiz" to "WiZ", "tuya" to "Tuya / Smart Life", "smartlife" to "Tuya / Smart Life", "kasa" to "TP-Link Kasa",
        "tapo" to "TP-Link Tapo", "hs1" to "TP-Link Kasa", "kl1" to "TP-Link Kasa", "lb1" to "TP-Link Kasa",
        "lifx" to "LIFX", "govee" to "Govee", "yeelink" to "Yeelight", "yeelight" to "Yeelight", "philips-hue" to "Philips Hue Bridge",
        "hue" to "Philips Hue", "sengled" to "Sengled", "feit" to "Feit", "geeni" to "Geeni (Tuya)", "cync" to "GE Cync",
        "nanoleaf" to "Nanoleaf", "esp" to "ESP chip (many cheap smart lights)", "wemo" to "Wemo", "meross" to "Meross"
    )

    fun wifiNetwork(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    class WifiState(val ssid: String?, val ip: String?, val prefix: Int, val gateway: String?)

    /** The Wi-Fi's name (null if Android won't say: no location access), the phone's address and the router's. */
    fun wifiState(context: Context): WifiState? {
        val network = wifiNetwork(context) ?: return null
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val link = cm.getLinkProperties(network)
        val address = link?.linkAddresses?.firstOrNull { it.address is Inet4Address }
        val gateway = link?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        val info = cm.getNetworkCapabilities(network)?.transportInfo as? WifiInfo
        @Suppress("DEPRECATION")
        val ssid = (info?.ssid?.takeIf { it != WifiManager.UNKNOWN_SSID }
            ?: context.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid?.takeIf { it != WifiManager.UNKNOWN_SSID })
            ?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        return WifiState(ssid, address?.address?.hostAddress, address?.prefixLength ?: 24, gateway)
    }

    /** A fingerprint of the home router (its address and how its web page introduces itself). */
    suspend fun routerFingerprint(context: Context): String = withContext(Dispatchers.IO) {
        val state = wifiState(context) ?: return@withContext ""
        val gateway = state.gateway ?: return@withContext ""
        val network = wifiNetwork(context)
        val header = runCatching {
            val socket = network?.socketFactory?.createSocket() ?: Socket()
            socket.use {
                it.connect(InetSocketAddress(gateway, 80), 1500)
                it.soTimeout = 1500
                it.getOutputStream().write("HEAD / HTTP/1.0\r\nHost: $gateway\r\n\r\n".toByteArray())
                it.getInputStream().bufferedReader().readLines()
                    .firstOrNull { line -> line.startsWith("Server:", ignoreCase = true) || line.startsWith("WWW-Authenticate:", ignoreCase = true) }
                    .orEmpty().trim()
            }
        }.getOrDefault("")
        "$gateway $header".trim()
    }

    /** Every address on the home Wi-Fi to look at: the phone's /24 (at most 254). */
    private fun addressesToScan(state: WifiState): List<String> {
        val own = state.ip ?: return emptyList()
        val parts = own.split(".")
        if (parts.size != 4) return emptyList()
        val base = parts.take(3).joinToString(".")
        return (1..254).map { "$base.$it" }.filter { it != own }
    }

    /** Looks at every address on the home Wi-Fi; [onProgress] gets (done, total). */
    suspend fun scan(context: Context, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<Found> = coroutineScope {
        val state = wifiState(context) ?: return@coroutineScope emptyList()
        val network = wifiNetwork(context)
        val targets = addressesToScan(state)
        val gate = Semaphore(48)
        var done = 0
        targets.map { ip ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    probe(network, ip, thorough = true).also {
                        synchronized(this@coroutineScope) { done++ }
                        onProgress(done, targets.size)
                    }
                }
            }
        }.awaitAll().filterNotNull().sortedBy { it.ip.substringAfterLast('.').toIntOrNull() ?: 0 }
    }

    /**
     * Whether [ip] answers, and how. [thorough] also asks for its name and brand (slower), as
     * in a scan; the nightly check only needs to know it answers.
     */
    suspend fun probe(network: Network?, ip: String, thorough: Boolean): Found? = withContext(Dispatchers.IO) {
        val ways = mutableListOf<String>()
        var brand = ""
        if (ping(ip)) ways += "ping"
        for ((port, portBrand) in PORTS) {
            when (tcp(network, ip, port)) {
                TcpResult.OPEN -> {
                    ways += "port $port"
                    if (brand.isEmpty()) brand = portBrand
                    if (!thorough) break
                }
                TcpResult.REFUSED -> if ("refused" !in ways) ways += "refused"
                TcpResult.SILENT -> Unit
            }
            if (!thorough && ways.isNotEmpty()) break
        }
        if (thorough || ways.isEmpty()) {
            if (wiz(network, ip)) {
                ways += "WiZ"
                brand = "WiZ"
            }
        }
        if (ways.isEmpty()) return@withContext null
        var name = ""
        if (thorough) {
            name = withTimeoutOrNull(1500) { runCatching { InetAddress.getByName(ip).canonicalHostName }.getOrNull() }
                ?.takeIf { it != ip }.orEmpty()
            if (brand.isEmpty() && "port 80" in ways && hueBridge(network, ip)) brand = "Philips Hue Bridge"
            if (brand.isEmpty()) brand = NAME_BRANDS.firstOrNull { name.lowercase().contains(it.first) }?.second.orEmpty()
        }
        Found(ip, name, ways.joinToString(", "), brand)
    }

    /** Whether a saved light answers (any way, at its address). */
    suspend fun answers(context: Context, bulb: Bulb): Boolean = answersDetail(context, bulb).first

    /** Whether a saved light answers, with how (or what was tried) and how long it took, for the log. */
    suspend fun answersDetail(context: Context, bulb: Bulb): Pair<Boolean, String> {
        val started = android.os.SystemClock.elapsedRealtime()
        val network = wifiNetwork(context)
        val found = probe(network, bulb.ip, thorough = false)
        val ms = android.os.SystemClock.elapsedRealtime() - started
        return if (found != null) true to "answered (${found.how}) in $ms ms"
        else false to "no answer after ${ms / 100 / 10.0} s (tried ping, ports ${PORTS.keys.joinToString("/")}, WiZ" +
                if (network == null) "; no Wi-Fi network to use)" else ")"
    }

    private fun ping(ip: String): Boolean = runCatching {
        val process = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "1", ip).redirectErrorStream(true).start()
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroy()
            false
        } else process.exitValue() == 0
    }.getOrDefault(false)

    private enum class TcpResult { OPEN, REFUSED, SILENT }

    private fun tcp(network: Network?, ip: String, port: Int): TcpResult = try {
        (network?.socketFactory?.createSocket() ?: Socket()).use { it.connect(InetSocketAddress(ip, port), 350) }
        TcpResult.OPEN
    } catch (e: ConnectException) {
        // Refused straight away: something at that address answered.
        if (e.message.orEmpty().contains("ECONNREFUSED") || e.message.orEmpty().contains("refused", ignoreCase = true)) TcpResult.REFUSED else TcpResult.SILENT
    } catch (_: Exception) {
        TcpResult.SILENT
    }

    /** WiZ lights answer {"method":"getPilot"} on UDP 38899. */
    private fun wiz(network: Network?, ip: String): Boolean = runCatching {
        DatagramSocket().use { socket ->
            network?.bindSocket(socket)
            socket.soTimeout = 400
            val message = """{"method":"getPilot","params":{}}""".toByteArray()
            socket.send(DatagramPacket(message, message.size, InetAddress.getByName(ip), 38899))
            val buffer = ByteArray(512)
            socket.receive(DatagramPacket(buffer, buffer.size))
            true
        }
    }.getOrDefault(false)

    /** A Philips Hue Bridge says who it is at /api/config. */
    private fun hueBridge(network: Network?, ip: String): Boolean = runCatching {
        (network?.socketFactory?.createSocket() ?: Socket()).use {
            it.connect(InetSocketAddress(ip, 80), 800)
            it.soTimeout = 1200
            it.getOutputStream().write("GET /api/config HTTP/1.0\r\nHost: $ip\r\n\r\n".toByteArray())
            it.getInputStream().bufferedReader().readText().contains("bridgeid")
        }
    }.getOrDefault(false)
}
