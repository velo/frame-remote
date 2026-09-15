package com.marvinformatics.frameremote.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class DeviceInfo(
    val name: String,
    val modelName: String,
    val powerState: String,
    val wifiMac: String,
    val tokenAuthSupport: Boolean,
)

/**
 * Samsung Tizen REST API on port 8001. Unauthenticated on-LAN endpoints.
 *
 * Firmware quirk (verified on QN65LS03DAGXZD / API 2.0.25): the applications
 * *list* endpoint is 404, but per-app GET (status) and POST (launch) both work.
 */
class RestApi {

    /** Message from the most recent failed call; null after a success. */
    @Volatile
    var lastError: String? = null
        private set

    private val client = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    suspend fun deviceInfo(ip: String): DeviceInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("http://$ip:8001/api/v2/").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    lastError = "HTTP ${resp.code}"
                    return@runCatching null
                }
                val device = JSONObject(resp.body!!.string()).getJSONObject("device")
                DeviceInfo(
                    name = device.optString("name").replace("&quot;", "\""),
                    modelName = device.optString("modelName"),
                    powerState = device.optString("PowerState", "unknown"),
                    wifiMac = device.optString("wifiMac"),
                    tokenAuthSupport = device.optString("TokenAuthSupport") == "true",
                ).also { lastError = null }
            }
        }.onFailure { lastError = it.message ?: it.javaClass.simpleName }.getOrNull()
    }

    /** Launch an app by Tizen id. Returns true when the TV acknowledged. */
    suspend fun launchApp(ip: String, appId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("http://$ip:8001/api/v2/applications/$appId")
                .post(ByteArray(0).toRequestBody(null))
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}
