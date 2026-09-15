package com.marvinformatics.frameremote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.marvinformatics.frameremote.data.SettingsRepository
import com.marvinformatics.frameremote.data.TvConfig
import com.marvinformatics.frameremote.tv.DiscoveredTv
import com.marvinformatics.frameremote.tv.RemoteSocket
import com.marvinformatics.frameremote.tv.RestApi
import com.marvinformatics.frameremote.tv.Ssdp
import com.marvinformatics.frameremote.tv.Upnp
import com.marvinformatics.frameremote.tv.Wol
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

object TizenApps {
    // Both ids verified against a QN65LS03DAGXZD (2024 Frame): the REST POST
    // launch returns 200/true and the app comes to the foreground.
    const val PLEX = "3201512006963"
    const val YOUTUBE = "111299001912"
}

data class UiState(
    val config: TvConfig = TvConfig(),
    val loaded: Boolean = false,
    val reachable: Boolean = false,
    val powerState: String = "unknown",
    val volume: Int? = null,
    val muted: Boolean = false,
    val socketState: RemoteSocket.State = RemoteSocket.State.DISCONNECTED,
    val discovering: Boolean = false,
    val discovered: List<DiscoveredTv> = emptyList(),
    val toast: String? = null,
)

class TvViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsRepository(app)
    private val rest = RestApi()
    private val upnp = Upnp()
    private val ssdp = Ssdp(rest)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val socket = RemoteSocket(
        clientName = "FrameRemoteAndroid",
        onToken = { token -> viewModelScope.launch { settings.saveToken(token) } },
        onState = { s -> _state.update { it.copy(socketState = s) } },
    )

    private val volumeRequests = MutableSharedFlow<Int>(extraBufferCapacity = 64)
    private var pollJob: Job? = null
    /** While the user is dragging the slider, polling must not fight the thumb. */
    private var volumeTouchedAt = 0L

    init {
        viewModelScope.launch {
            settings.config.collect { cfg ->
                val previous = _state.value.config
                _state.update { it.copy(config = cfg, loaded = true) }
                if (cfg.ip.isNotBlank() && (cfg.ip != previous.ip || cfg.token != previous.token)) {
                    socket.disconnect()
                    connectSocket()
                }
            }
        }
        // Conflate slider movements: only the latest value is sent, ~30ms apart.
        viewModelScope.launch {
            volumeRequests.collectLatest { v ->
                delay(30)
                withIp { ip -> upnp.setVolume(ip, v) }
            }
        }
    }

    fun onResume() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (true) {
                refreshStatus()
                delay(4000)
            }
        }
        connectSocket()
    }

    fun onPause() {
        pollJob?.cancel()
        pollJob = null
        socket.disconnect()
    }

    private suspend fun refreshStatus() {
        val ip = _state.value.config.ip.takeIf { it.isNotBlank() } ?: return
        val info = rest.deviceInfo(ip)
        _state.update {
            it.copy(reachable = info != null, powerState = info?.powerState ?: "off")
        }
        // Opportunistically keep the MAC in sync for Wake-on-LAN.
        if (info != null && info.wifiMac.isNotBlank() && info.wifiMac != _state.value.config.mac) {
            settings.saveMac(info.wifiMac)
        }
        if (info != null) {
            val vol = upnp.getVolume(ip)
            val mute = upnp.getMute(ip)
            _state.update {
                it.copy(
                    volume = if (System.currentTimeMillis() - volumeTouchedAt > 1500) vol ?: it.volume else it.volume,
                    muted = mute ?: it.muted,
                )
            }
        }
    }

    private fun connectSocket() {
        val cfg = _state.value.config
        if (cfg.ip.isNotBlank()) socket.ensureConnected(cfg.ip, cfg.token)
    }

    private inline fun withIp(block: (String) -> Unit) {
        _state.value.config.ip.takeIf { it.isNotBlank() }?.let(block)
    }

    // ---- Controls -------------------------------------------------------

    fun sendKey(key: String) {
        connectSocket()
        socket.sendKey(key)
    }

    /** Power: KEY_POWER while the TV answers, Wake-on-LAN when it does not. */
    fun power() {
        viewModelScope.launch {
            val cfg = _state.value.config
            if (_state.value.reachable) {
                sendKey("KEY_POWER")
            } else if (cfg.mac.isNotBlank()) {
                toast("Sending Wake-on-LAN…")
                Wol.wake(cfg.mac, cfg.ip.takeIf { it.isNotBlank() })
                // Give the TV a moment, then re-check.
                repeat(10) {
                    delay(2000)
                    refreshStatus()
                    if (_state.value.reachable) {
                        toast("TV is awake")
                        connectSocket()
                        return@launch
                    }
                }
                toast("TV did not answer — is network standby enabled?")
            } else {
                toast("TV unreachable and no MAC configured for Wake-on-LAN")
            }
        }
    }

    fun setVolume(v: Int) {
        volumeTouchedAt = System.currentTimeMillis()
        _state.update { it.copy(volume = v) }
        volumeRequests.tryEmit(v)
    }

    fun toggleMute() {
        viewModelScope.launch {
            val target = !_state.value.muted
            _state.update { it.copy(muted = target) }
            withIp { ip -> viewModelScope.launch { upnp.setMute(ip, target) } }
        }
    }

    fun launchApp(appId: String, label: String) {
        viewModelScope.launch {
            val ip = _state.value.config.ip.takeIf { it.isNotBlank() } ?: return@launch
            val ok = rest.launchApp(ip, appId)
            if (!ok) {
                // Firmware fallback: launch over the remote WebSocket.
                connectSocket()
                socket.launchApp(appId)
            }
            toast("Launching $label…")
        }
    }

    // ---- Setup ----------------------------------------------------------

    fun discover() {
        viewModelScope.launch {
            _state.update { it.copy(discovering = true, discovered = emptyList()) }
            val found = ssdp.discover()
            _state.update { it.copy(discovering = false, discovered = found) }
            if (found.isEmpty()) toast("No TV found — is it awake? You can enter the IP manually.")
        }
    }

    fun selectTv(tv: DiscoveredTv) {
        viewModelScope.launch {
            settings.saveTv(tv.ip, tv.mac, tv.name.ifBlank { tv.modelName })
        }
    }

    fun saveManual(ip: String, mac: String) {
        viewModelScope.launch {
            val info = rest.deviceInfo(ip)
            val name = info?.name?.ifBlank { null } ?: "Samsung TV"
            val resolvedMac = mac.ifBlank { info?.wifiMac ?: "" }
            settings.saveTv(ip, resolvedMac, name)
            if (info == null) toast("Saved, but the TV did not answer on $ip")
        }
    }

    fun forgetPairing() {
        viewModelScope.launch {
            settings.clearToken()
            socket.disconnect()
            toast("Pairing token cleared — the TV will prompt again")
        }
    }

    private fun toast(msg: String) {
        _state.update { it.copy(toast = msg) }
    }

    fun consumeToast() {
        _state.update { it.copy(toast = null) }
    }

    override fun onCleared() {
        socket.disconnect()
    }
}
