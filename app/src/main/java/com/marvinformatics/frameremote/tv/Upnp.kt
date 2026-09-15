package com.marvinformatics.frameremote.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * UPnP RenderingControl on port 9197. This is the fast path for absolute
 * volume: measured 14-50 ms round trips on the real TV, and the 0-100 scale
 * maps 1:1 to the number the TV shows on screen.
 */
class Upnp {

    private val client = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private val xml = "text/xml; charset=\"utf-8\"".toMediaType()

    private suspend fun soap(ip: String, action: String, args: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = """
                    <?xml version="1.0" encoding="utf-8"?>
                    <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                    <s:Body><u:$action xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1"><InstanceID>0</InstanceID><Channel>Master</Channel>$args</u:$action></s:Body>
                    </s:Envelope>
                """.trimIndent()
                val req = Request.Builder()
                    .url("http://$ip:9197/upnp/control/RenderingControl1")
                    .header("SOAPACTION", "\"urn:schemas-upnp-org:service:RenderingControl:1#$action\"")
                    .post(body.toRequestBody(xml))
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                }
            }.getOrNull()
        }

    suspend fun getVolume(ip: String): Int? =
        soap(ip, "GetVolume", "")?.let { Regex("<CurrentVolume>(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    suspend fun setVolume(ip: String, volume: Int): Boolean =
        soap(ip, "SetVolume", "<DesiredVolume>${volume.coerceIn(0, 100)}</DesiredVolume>") != null

    suspend fun getMute(ip: String): Boolean? =
        soap(ip, "GetMute", "")?.let { Regex("<CurrentMute>(\\d)").find(it)?.groupValues?.get(1)?.let { v -> v == "1" } }

    suspend fun setMute(ip: String, mute: Boolean): Boolean =
        soap(ip, "SetMute", "<DesiredMute>${if (mute) 1 else 0}</DesiredMute>") != null
}
