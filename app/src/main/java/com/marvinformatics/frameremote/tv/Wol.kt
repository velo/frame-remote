package com.marvinformatics.frameremote.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Wake-on-LAN. The Samsung WS/REST APIs only answer while the TV is awake,
 * so power-on from standby is a magic packet to the TV's MAC. Requires the
 * TV's "Power On with Mobile" / network standby setting.
 */
object Wol {

    fun parseMac(mac: String): ByteArray? {
        val hex = mac.replace(":", "").replace("-", "").trim()
        if (hex.length != 12 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /**
     * Sends a burst of magic packets: global broadcast, the /24 directed
     * broadcast for the TV's known IP, and the IP itself (helps when the
     * switch still has the ARP entry).
     */
    suspend fun wake(mac: String, tvIp: String?) = withContext(Dispatchers.IO) {
        val macBytes = parseMac(mac) ?: return@withContext false
        val payload = ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { macBytes[it % 6] }
        val targets = buildList {
            add("255.255.255.255")
            if (!tvIp.isNullOrBlank() && tvIp.contains('.')) {
                add(tvIp.substringBeforeLast('.') + ".255")
                add(tvIp)
            }
        }
        var sent = false
        repeat(3) {
            for (target in targets) {
                runCatching {
                    DatagramSocket().use { socket ->
                        socket.broadcast = true
                        for (port in intArrayOf(9, 7)) {
                            socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName(target), port))
                        }
                        sent = true
                    }
                }
            }
            delay(300)
        }
        sent
    }
}
