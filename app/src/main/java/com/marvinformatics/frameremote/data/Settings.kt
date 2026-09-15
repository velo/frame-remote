package com.marvinformatics.frameremote.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Everything the app knows about the paired TV. All persisted, nothing hardcoded. */
data class TvConfig(
    val ip: String = "",
    val mac: String = "",
    val token: String = "",
    val name: String = "",
    /**
     * The identity this app presents on the TV's WebSocket. Persisted so
     * normal reconnects reuse the SAME name; only an explicit Re-pair mints
     * a new one (a Samsung TV that has denied a client name refuses it
     * silently forever — a fresh name is the only way to be prompted again).
     */
    val clientName: String = "",
) {
    val isConfigured: Boolean get() = ip.isNotBlank()
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val IP = stringPreferencesKey("tv_ip")
        val MAC = stringPreferencesKey("tv_mac")
        val TOKEN = stringPreferencesKey("tv_token")
        val NAME = stringPreferencesKey("tv_name")
        val CLIENT_NAME = stringPreferencesKey("client_name")
    }

    val config: Flow<TvConfig> = context.dataStore.data.map { p ->
        TvConfig(
            ip = p[Keys.IP] ?: "",
            mac = p[Keys.MAC] ?: "",
            token = p[Keys.TOKEN] ?: "",
            name = p[Keys.NAME] ?: "",
            clientName = p[Keys.CLIENT_NAME] ?: "",
        )
    }

    suspend fun saveTv(ip: String, mac: String, name: String) {
        context.dataStore.edit { p ->
            val previousIp = p[Keys.IP]
            p[Keys.IP] = ip
            p[Keys.MAC] = mac
            p[Keys.NAME] = name
            // A different TV means the old pairing token is meaningless.
            if (previousIp != null && previousIp != ip) p.remove(Keys.TOKEN)
        }
    }

    suspend fun saveMac(mac: String) {
        context.dataStore.edit { it[Keys.MAC] = mac }
    }

    suspend fun saveClientName(name: String) {
        context.dataStore.edit { it[Keys.CLIENT_NAME] = name }
    }

    suspend fun saveToken(token: String) {
        context.dataStore.edit { it[Keys.TOKEN] = token }
    }

    suspend fun clearToken() {
        context.dataStore.edit { it.remove(Keys.TOKEN) }
    }
}
