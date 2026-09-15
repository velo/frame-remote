package com.marvinformatics.frameremote.tv

import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/** One-shot connectivity probe of the remote-control WebSocket, for diagnostics. */
object WsProbe {

    suspend fun test(ip: String, token: String, clientName: String): String {
        val start = System.currentTimeMillis()
        val result = CompletableDeferred<String>()
        val name = Base64.encodeToString(clientName.toByteArray(), Base64.NO_WRAP or Base64.NO_PADDING)
        var url = "wss://$ip:8002/api/v2/channels/samsung.remote.control?name=$name"
        if (token.isNotBlank()) url += "&token=$token"
        val ws = TvTls.clientFor(ip).newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching {
                        when (JSONObject(text).optString("event")) {
                            "ms.channel.connect" ->
                                result.complete("ok — connected in ${System.currentTimeMillis() - start} ms")
                            "ms.channel.unauthorized" ->
                                result.complete("FAILED — TV refused the pairing (re-pair from settings)")
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    result.complete("FAILED — ${t.message ?: t.javaClass.simpleName}")
                }
            },
        )
        val outcome = withTimeoutOrNull(6000) { result.await() }
            ?: "FAILED — no answer in 6 s (TV asleep, or Allow prompt waiting on the TV screen)"
        ws.cancel()
        return outcome
    }
}
