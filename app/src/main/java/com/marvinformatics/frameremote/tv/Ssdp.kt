package com.marvinformatics.frameremote.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

data class DiscoveredTv(
    val ip: String,
    val name: String,
    val modelName: String,
    val mac: String,
)

/**
 * SSDP discovery. Samsung TVs answer an M-SEARCH for MediaRenderer:1 with a
 * LOCATION header pointing at their DMR description (port 9197). Each hit is
 * cross-checked against the Tizen REST API on 8001 to confirm it is a
 * Samsung TV and to auto-fill the name and MAC.
 *
 * Only works while the TV is awake — a TV in standby is undiscoverable,
 * which is why the app persists IP and MAC instead of discovering on demand.
 */
class Ssdp(private val restApi: RestApi) {

    suspend fun discover(timeoutMs: Int = 3000): List<DiscoveredTv> = withContext(Dispatchers.IO) {
        val ips = linkedSetOf<String>()
        runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = 500
                val msg = ("M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                        "\r\n").toByteArray()
                val group = InetAddress.getByName("239.255.255.250")
                repeat(2) { socket.send(DatagramPacket(msg, msg.size, group, 1900)) }

                val buf = ByteArray(2048)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val response = String(buf, 0, packet.length)
                        val location = Regex("(?im)^LOCATION:\\s*(\\S+)").find(response)?.groupValues?.get(1)
                        val ip = location?.let { Regex("//([0-9.]+)[:/]").find(it)?.groupValues?.get(1) }
                            ?: packet.address.hostAddress
                        if (ip != null) ips.add(ip)
                    } catch (_: SocketTimeoutException) {
                        // keep waiting until the deadline
                    }
                }
            }
        }

        ips.mapNotNull { ip ->
            restApi.deviceInfo(ip)?.let { info ->
                DiscoveredTv(ip = ip, name = info.name, modelName = info.modelName, mac = info.wifiMac)
            }
        }
    }
}
