package com.marvinformatics.frameremote.tv

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID

/**
 * The Frame's art-app WebSocket channel (com.samsung.art-app on 8002).
 * Used only to READ the current Art Mode state so the toggle button can say
 * what it will do next. Wire format matches samsungtvws: requests go out as
 * ms.channel.emit / art_app_request with a JSON-string payload, replies come
 * back as d2d_service_message events.
 */
class ArtSocket(
    private val clientName: String,
    private val onArtMode: (Boolean) -> Unit,
) {
    private var client: OkHttpClient? = null
    private var clientIp: String? = null
    private var ws: WebSocket? = null
    private var connected = false
    private val lock = Any()

    fun ensureConnected(ip: String, token: String) {
        synchronized(lock) {
            if (ws != null) return
            if (clientIp != ip) {
                client = TvTls.clientFor(ip)
                clientIp = ip
            }
            val name = Base64.encodeToString(clientName.toByteArray(), Base64.NO_WRAP or Base64.NO_PADDING)
            var url = "wss://$ip:8002/api/v2/channels/com.samsung.art-app?name=$name"
            if (token.isNotBlank()) url += "&token=$token"
            ws = client!!.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching {
                        val msg = JSONObject(text)
                        when (msg.optString("event")) {
                            "ms.channel.connect", "ms.channel.ready" -> {
                                synchronized(lock) { connected = true }
                                requestArtMode()
                            }
                            "d2d_service_message" -> {
                                val payload = JSONObject(msg.optString("data"))
                                val value = payload.optString("value")
                                if (payload.optString("event").contains("artmode") && (value == "on" || value == "off")) {
                                    onArtMode(value == "on")
                                }
                            }
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped()
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped()
            })
        }
    }

    private fun dropped() {
        synchronized(lock) {
            ws = null
            connected = false
        }
    }

    fun requestArtMode() {
        val socket: WebSocket?
        synchronized(lock) {
            socket = if (connected) ws else null
        }
        if (socket == null) return
        val id = UUID.randomUUID().toString()
        val inner = JSONObject()
            .put("request", "get_artmode_status")
            .put("id", id)
            .put("request_id", id)
        val outer = JSONObject()
            .put("method", "ms.channel.emit")
            .put("params", JSONObject()
                .put("event", "art_app_request")
                .put("to", "host")
                .put("data", inner.toString()))
        socket.send(outer.toString())
    }

    fun disconnect() {
        synchronized(lock) {
            ws?.close(1000, null)
            ws = null
            connected = false
        }
    }
}
