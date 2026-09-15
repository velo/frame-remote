package com.marvinformatics.frameremote.tv

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Samsung remote-control WebSocket (wss on port 8002).
 *
 * The TV presents a self-signed certificate, so certificate checks are
 * relaxed ONLY for the client built for the configured TV host — every
 * request through this client targets that one LAN IP, and the hostname
 * verifier refuses anything else. Global TLS defaults are untouched.
 *
 * Pairing: with TokenAuthSupport the first connection pops an Allow prompt
 * on the TV and the granted token arrives in the ms.channel.connect event.
 * We persist it (via [onToken]) so the prompt never comes back.
 */
class RemoteSocket(
    /** Client name shown in the TV's device manager. */
    private val clientName: String,
    private val onToken: (String) -> Unit,
    private val onState: (State) -> Unit,
    private val onError: (String) -> Unit = {},
) {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    private var client: OkHttpClient? = null
    private var clientIp: String? = null
    private var ws: WebSocket? = null
    private var connected = false
    private val pending = ArrayDeque<String>()
    private val lock = Any()

    private fun clientFor(ip: String): OkHttpClient {
        client?.let { if (clientIp == ip) return it }
        return TvTls.clientFor(ip).also { client = it; clientIp = ip }
    }

    /** Opens the socket if needed. Safe to call before every send. */
    fun ensureConnected(ip: String, token: String) {
        synchronized(lock) {
            if (ws != null) return
            onState(State.CONNECTING)
            val name = Base64.encodeToString(clientName.toByteArray(), Base64.NO_WRAP or Base64.NO_PADDING)
            var url = "wss://$ip:8002/api/v2/channels/samsung.remote.control?name=$name"
            if (token.isNotBlank()) url += "&token=$token"
            val request = Request.Builder().url(url).build()
            ws = clientFor(ip).newWebSocket(request, object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching {
                        val msg = JSONObject(text)
                        when (msg.optString("event")) {
                            "ms.channel.connect" -> {
                                msg.optJSONObject("data")?.optString("token")
                                    ?.takeIf { it.isNotBlank() }?.let(onToken)
                                synchronized(lock) {
                                    connected = true
                                    while (pending.isNotEmpty()) webSocket.send(pending.removeFirst())
                                }
                                onState(State.CONNECTED)
                            }
                            "ms.channel.unauthorized" -> {
                                onError("TV refused the pairing — use Re-pair in settings")
                                webSocket.close(1000, null)
                            }
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    onError("Remote socket: ${t.message ?: t.javaClass.simpleName}")
                    dropped()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped()
            })
        }
    }

    private fun dropped() {
        synchronized(lock) {
            ws = null
            connected = false
            pending.clear()
        }
        onState(State.DISCONNECTED)
    }

    fun disconnect() {
        synchronized(lock) {
            ws?.close(1000, null)
            ws = null
            connected = false
            pending.clear()
        }
        onState(State.DISCONNECTED)
    }

    private fun send(json: String) {
        synchronized(lock) {
            val socket = ws
            if (socket != null && connected) socket.send(json) else pending.addLast(json)
        }
    }

    /** cmd is "Click", "Press" or "Release". */
    fun sendKey(key: String, cmd: String = "Click") {
        val msg = JSONObject()
            .put("method", "ms.remote.control")
            .put("params", JSONObject()
                .put("Cmd", cmd)
                .put("DataOfCmd", key)
                .put("Option", "false")
                .put("TypeOfRemote", "SendRemoteKey"))
        send(msg.toString())
    }

    /** WS-side app launch — fallback for firmwares without the REST launch. */
    fun launchApp(appId: String, actionType: String = "NATIVE_LAUNCH") {
        val msg = JSONObject()
            .put("method", "ms.channel.emit")
            .put("params", JSONObject()
                .put("event", "ed.apps.launch")
                .put("to", "host")
                .put("data", JSONObject()
                    .put("appId", appId)
                    .put("action_type", actionType)))
        send(msg.toString())
    }
}
