package com.marvinformatics.frameremote.tv

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * The Frame's art-app WebSocket channel (com.samsung.art-app on 8002).
 * Reads the Art Mode state and the artwork currently on display. Wire format
 * matches samsungtvws: requests go out as ms.channel.emit / art_app_request
 * with a JSON-string payload, replies come back as d2d_service_message events.
 *
 * Thumbnails do not travel over the WebSocket: the TV answers with a
 * one-shot TCP endpoint and streams the file there, framed as
 *   [4-byte big-endian header length]{"fileID":"MY_F1816","fileType":"jpg","fileLength":42163,"num":0,"total":1}[file bytes]
 */
class ArtSocket(
    private val onArtMode: (Boolean) -> Unit,
    private val onArtwork: (contentId: String, jpeg: ByteArray) -> Unit = { _, _ -> },
) {
    private var client: OkHttpClient? = null
    private var clientIp: String? = null
    private var ws: WebSocket? = null
    private var connected = false
    private var shownContentId: String? = null
    private var fetchingContentId: String? = null
    private val lock = Any()

    fun ensureConnected(ip: String, token: String, clientName: String) {
        synchronized(lock) {
            if (ws != null) return
            if (clientIp != ip) {
                client = TvTls.clientFor(ip)
                clientIp = ip
                shownContentId = null
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
                                requestArtwork()
                            }
                            "d2d_service_message" -> onServiceMessage(ip, JSONObject(msg.optString("data")))
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped()
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped()
            })
        }
    }

    private fun onServiceMessage(ip: String, payload: JSONObject) {
        val event = payload.optString("event")
        val value = payload.optString("value")
        when {
            event.contains("artmode") && (value == "on" || value == "off") -> onArtMode(value == "on")
            event == "get_current_artwork" -> payload.optString("content_id").takeIf { it.isNotBlank() }?.let(::fetchIfChanged)
            event == "get_thumbnail_list" -> {
                val contentId = payload.optString("content_id")
                val conn = JSONObject(payload.optString("conn_info"))
                thread(name = "art-thumbnail") { receiveThumbnail(ip, contentId, conn) }
            }
            event == "error" -> synchronized(lock) { fetchingContentId = null }
        }
    }

    private fun fetchIfChanged(contentId: String) {
        synchronized(lock) {
            if (contentId == shownContentId || contentId == fetchingContentId) return
            fetchingContentId = contentId
        }
        val id = UUID.randomUUID().toString()
        send(JSONObject()
            .put("request", "get_thumbnail_list")
            .put("content_id_list", JSONArray().put(JSONObject().put("content_id", contentId)))
            .put("conn_info", JSONObject()
                .put("d2d_mode", "socket")
                .put("connection_id", Random.nextLong(4L * 1024 * 1024 * 1024))
                .put("id", id))
            .put("id", id)
            .put("request_id", id))
    }

    private fun receiveThumbnail(tvIp: String, contentId: String, conn: JSONObject) {
        val jpeg = runCatching {
            TvTls.socketTo(
                tvIp = tvIp,
                ip = conn.getString("ip"),
                port = conn.getString("port").toInt(),
                secured = conn.optBoolean("secured"),
                timeoutMs = 5000,
            ).use { socket ->
                val input = DataInputStream(socket.getInputStream())
                val header = ByteArray(input.readInt()).also(input::readFully)
                val length = JSONObject(String(header)).getInt("fileLength")
                ByteArray(length).also(input::readFully)
            }
        }.getOrNull()
        synchronized(lock) {
            fetchingContentId = null
            if (jpeg != null) shownContentId = contentId
        }
        if (jpeg != null) onArtwork(contentId, jpeg)
    }

    private fun dropped() {
        synchronized(lock) {
            ws = null
            connected = false
            fetchingContentId = null
        }
    }

    fun requestArtMode() = send(request("get_artmode_status"))

    fun requestArtwork() = send(request("get_current_artwork"))

    private fun request(name: String): JSONObject {
        val id = UUID.randomUUID().toString()
        return JSONObject().put("request", name).put("id", id).put("request_id", id)
    }

    private fun send(inner: JSONObject) {
        val socket: WebSocket?
        synchronized(lock) {
            socket = if (connected) ws else null
        }
        if (socket == null) return
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
            fetchingContentId = null
        }
    }
}
