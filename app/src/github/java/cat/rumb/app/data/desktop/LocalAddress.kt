package cat.rumb.app.data.desktop

import java.net.Inet4Address
import java.net.NetworkInterface

/** Finds the phone's LAN IPv4 so the desktop web server can advertise a reachable URL. Pure JVM. */
object LocalAddress {

    /**
     * First site-local IPv4 (192.168.x / 10.x / 172.16-31.x) on an up, non-loopback interface —
     * typically the WiFi `wlan0`. Null when there is no LAN connectivity. No permission needed.
     */
    fun wifiIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}
