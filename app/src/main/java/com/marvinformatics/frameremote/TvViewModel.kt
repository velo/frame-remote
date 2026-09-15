package com.marvinformatics.frameremote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.marvinformatics.frameremote.data.SettingsRepository
import com.marvinformatics.frameremote.data.TvConfig
import com.marvinformatics.frameremote.tv.DiscoveredTv
import com.marvinformatics.frameremote.tv.WifiNet
import com.marvinformatics.frameremote.tv.WsProbe
import com.marvinformatics.frameremote.tv.ArtSocket
import com.marvinformatics.frameremote.tv.RemoteSocket
import com.marvinformatics.frameremote.tv.RestApi
import com.marvinformatics.frameremote.tv.Ssdp
import com.marvinformatics.frameremote.tv.Upnp
import com.marvinformatics.frameremote.tv.Wol
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

data class DiagReport(
    val wifi: Boolean,
    val ip: String,
    val mac: String,
    val paired: Boolean,
    val rest: String,
    val upnp: String,
    val ws: String,
    val ssdp: String,
)

/** How volume is being controlled right now. */
enum class VolumeControl {
    UNKNOWN,
    /** UPnP absolute volume — the headline feature. */
    ABSOLUTE,
    /** UPnP refused (e.g. HTTP 401): stepping with KEY_VOLUP/KEY_VOLDOWN instead. */
    KEYS,
}

data class UiState(
    val config: TvConfig = TvConfig(),
    val loaded: Boolean = false,
    val reachable: Boolean = false,
    val powerState: String = "unknown",
    /** null = unknown (art channel not answering) */
    val artMode: Boolean? = null,
    /**
     * Foreground state of the launchable apps (null = unknown). Drives the
     * amber highlight only — a transient poll failure keeps the previous
     * value, a sustained one settles back to unknown/grey. There is no Home
     * equivalent: the firmware exposes no home-screen app id (all candidates
     * 404), and inferring "home" from everything-else-off also matches live
     * TV or any untracked app, so Home is deliberately never highlighted —
     * a highlight that lies is worse than none.
     */
    val plexActive: Boolean? = null,
    val youtubeActive: Boolean? = null,
    val volume: Int? = null,
    val muted: Boolean = false,
    val socketState: RemoteSocket.State = RemoteSocket.State.DISCONNECTED,
    /** The TV silently refuses this client identity; Re-pair mints a new one. */
    val pairingRefused: Boolean = false,
    val volumeControl: VolumeControl = VolumeControl.UNKNOWN,
    val discovering: Boolean = false,
    val discovered: List<DiscoveredTv> = emptyList(),
    /** Why the last discovery found nothing — shown inline, not just a toast. */
    val discoveryError: String? = null,
    val wifi: Boolean = true,
    val diag: DiagReport? = null,
    val diagRunning: Boolean = false,
    val toast: String? = null,
)

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

class TvViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        /** Base of the WS identity; legacy installs paired under the bare base. */
        const val CLIENT_NAME_BASE = "FrameRemoteAndroid"

        // 5 hex chars keep the full name's length a multiple of 3, so its
        // base64 form needs no padding — the exact shape verified on the TV.
        fun mintClientName(): String =
            CLIENT_NAME_BASE + "-" + (1..5).map { "0123456789abcdef".random() }.joinToString("")
    }

    private val settings = SettingsRepository(app)
    private val rest = RestApi()
    private val upnp = Upnp()
    private val ssdp = Ssdp(app, rest)
    private val wifiNet = WifiNet(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val socket = RemoteSocket(
        onToken = { token ->
            viewModelScope.launch { settings.saveToken(token) }
            toast("Paired with the TV")
        },
        onState = { s ->
            _state.update {
                it.copy(
                    socketState = s,
                    pairingRefused = if (s == RemoteSocket.State.CONNECTED) false else it.pairingRefused,
                )
            }
        },
        onError = { msg ->
            if (msg.contains("refused")) {
                _state.update { it.copy(pairingRefused = true) }
                toast("TV refused this remote — see Settings for how to fix it")
            }
        },
    )

    private val artSocket = ArtSocket(
        onArtMode = { on -> _state.update { it.copy(artMode = on) } },
    )

    private val volumeRequests = MutableSharedFlow<Int>(extraBufferCapacity = 64)
    private var pollJob: Job? = null
    /** While the user is dragging the slider, polling must not fight the thumb. */
    private var volumeTouchedAt = 0L
    /** Consecutive app-status poll failures; >= 3 settles the highlight to unknown. */
    private var plexFails = 0
    private var youtubeFails = 0

    init {
        viewModelScope.launch {
            settings.config.collect { cfg ->
                if (cfg.clientName.isBlank()) {
                    // First run mints a device-unique identity; an upgrade that
                    // already holds a token keeps the legacy bare name so the
                    // existing pairing stays valid.
                    settings.saveClientName(
                        if (cfg.token.isNotBlank()) CLIENT_NAME_BASE else mintClientName(),
                    )
                    return@collect // re-collected with the name set
                }
                val previous = _state.value.config
                _state.update { it.copy(config = cfg, loaded = true) }
                if (cfg.ip.isNotBlank() &&
                    (cfg.ip != previous.ip || cfg.token != previous.token || cfg.clientName != previous.clientName)
                ) {
                    socket.disconnect()
                    artSocket.disconnect()
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
        // Pin every socket to Wi-Fi: with mobile data on and a LAN without
        // internet, Android's default network is cellular and nothing on the
        // LAN would ever answer. Fail loudly when there is no Wi-Fi at all.
        val onWifi = wifiNet.bindProcessToWifi()
        _state.update { it.copy(wifi = onWifi) }
        if (!onWifi) toast("Not on Wi-Fi — connect the phone to the TV's network")
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
        artSocket.disconnect()
        wifiNet.unbindProcess()
    }

    private suspend fun refreshStatus() {
        val ip = _state.value.config.ip.takeIf { it.isNotBlank() } ?: return
        val info = rest.deviceInfo(ip)
        _state.update {
            it.copy(
                reachable = info != null,
                powerState = info?.powerState ?: "off",
                artMode = if (info == null) null else it.artMode,
                plexActive = if (info == null) null else it.plexActive,
                youtubeActive = if (info == null) null else it.youtubeActive,
            )
        }
        // Opportunistically keep the MAC in sync for Wake-on-LAN.
        if (info != null && info.wifiMac.isNotBlank() && info.wifiMac != _state.value.config.mac) {
            settings.saveMac(info.wifiMac)
        }
        if (info != null) {
            val cfg = _state.value.config
            if (cfg.clientName.isNotBlank()) {
                artSocket.ensureConnected(ip, cfg.token, cfg.clientName)
                artSocket.requestArtMode()
            }
            // One concurrent burst per poll: volume, mute, and the two app
            // foreground probes (~30 ms each on the LAN).
            val (vol, mute, plexVis, ytVis) = coroutineScope {
                val volD = async { upnp.getVolume(ip) }
                val plexD = async { rest.appVisible(ip, TizenApps.PLEX) }
                val ytD = async { rest.appVisible(ip, TizenApps.YOUTUBE) }
                val v = volD.await()
                val muteD = async { if (v != null) upnp.getMute(ip) else null }
                Quad(v, muteD.await(), plexD.await(), ytD.await())
            }
            plexFails = if (plexVis == null) plexFails + 1 else 0
            youtubeFails = if (ytVis == null) youtubeFails + 1 else 0
            _state.update {
                it.copy(
                    // The TV is reachable, so a UPnP failure here means the
                    // absolute-volume service refused us (observed: HTTP 401
                    // while unauthorized). Degrade to key stepping honestly
                    // instead of showing a slider that does nothing.
                    volumeControl = if (vol != null) VolumeControl.ABSOLUTE else VolumeControl.KEYS,
                    volume = if (System.currentTimeMillis() - volumeTouchedAt > 1500) vol ?: it.volume else it.volume,
                    muted = mute ?: it.muted,
                    plexActive = when {
                        plexVis != null -> plexVis
                        plexFails >= 3 -> null
                        else -> it.plexActive
                    },
                    youtubeActive = when {
                        ytVis != null -> ytVis
                        youtubeFails >= 3 -> null
                        else -> it.youtubeActive
                    },
                )
            }
        }
    }

    private fun connectSocket() {
        val cfg = _state.value.config
        if (cfg.ip.isNotBlank() && cfg.clientName.isNotBlank()) {
            socket.ensureConnected(cfg.ip, cfg.token, cfg.clientName)
        }
    }

    private inline fun withIp(block: (String) -> Unit) {
        _state.value.config.ip.takeIf { it.isNotBlank() }?.let(block)
    }

    // ---- Controls -------------------------------------------------------

    fun sendKey(key: String) {
        connectSocket()
        socket.sendKey(key)
    }

    /**
     * KEY_POWER on a Frame is an Art Mode toggle, verified both directions on
     * a 2024 LS03D: PowerState stays "on" throughout, and KEY_POWEROFF is a
     * silent no-op on this firmware. Deep standby is only reachable with a
     * physical long-press, so this button says what it really does.
     */
    fun toggleArtMode() {
        sendKey("KEY_POWER")
        viewModelScope.launch {
            // The toggle takes a moment; re-read state so the button label follows.
            delay(1500)
            artSocket.requestArtMode()
            delay(2000)
            artSocket.requestArtMode()
        }
    }

    /**
     * Wake-on-LAN for a TV that is genuinely powered off. Implemented to
     * spec (magic-packet burst, ports 9/7, broadcast + directed + unicast)
     * but NEVER observed waking this TV, because with Art Mode enabled a
     * Frame never enters deep standby from software. Requires the TV's
     * network standby ("Power On with Mobile") setting.
     */
    fun wakeTv() {
        viewModelScope.launch {
            val cfg = _state.value.config
            if (cfg.mac.isBlank()) {
                toast("No MAC configured for Wake-on-LAN")
                return@launch
            }
            toast("Sending Wake-on-LAN…")
            Wol.wake(cfg.mac, cfg.ip.takeIf { it.isNotBlank() })
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
        }
    }

    fun setVolume(v: Int) {
        volumeTouchedAt = System.currentTimeMillis()
        _state.update { it.copy(volume = v) }
        volumeRequests.tryEmit(v)
    }

    fun volumeUp() = sendKey("KEY_VOLUP")

    fun volumeDown() = sendKey("KEY_VOLDOWN")

    fun toggleMute() {
        if (_state.value.volumeControl == VolumeControl.KEYS) {
            sendKey("KEY_MUTE")
            _state.update { it.copy(muted = !it.muted) }
            return
        }
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
            _state.update { it.copy(discovering = true, discovered = emptyList(), discoveryError = null) }
            val result = ssdp.discover()
            _state.update {
                it.copy(discovering = false, discovered = result.tvs, discoveryError = result.error)
            }
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

    /**
     * Recovery action: mint a NEW client identity, drop the stored token and
     * reconnect. Once a Samsung TV has denied a client name it refuses that
     * name silently forever — reconnecting under the same identity never
     * prompts again. A fresh name is a fresh device, so the TV shows its
     * Allow prompt and we end PAIRED, not unpaired.
     */
    fun refreshPairing() {
        viewModelScope.launch {
            settings.saveClientName(mintClientName())
            settings.clearToken()
            delay(150) // let the config collector observe the changes
            socket.disconnect()
            artSocket.disconnect()
            _state.update { it.copy(pairingRefused = false) }
            connectSocket()
            toast("Look at the TV — accept the Allow prompt")
        }
    }

    /** Destructive-only variant: clears the token and stays disconnected. */
    fun forgetPairing() {
        viewModelScope.launch {
            settings.clearToken()
            socket.disconnect()
            toast("Pairing token cleared")
        }
    }

    fun runDiagnostics() {
        viewModelScope.launch {
            _state.update { it.copy(diagRunning = true) }
            val cfg = _state.value.config
            val wifi = wifiNet.wifiNetwork() != null
            var restR = "skipped — no TV configured"
            var upnpR = restR
            var wsR = restR
            if (cfg.ip.isNotBlank()) {
                val t0 = System.currentTimeMillis()
                val info = rest.deviceInfo(cfg.ip)
                restR = if (info != null)
                    "ok — PowerState ${info.powerState} (${System.currentTimeMillis() - t0} ms)"
                else "FAILED — ${rest.lastError ?: "no response"}"
                val t1 = System.currentTimeMillis()
                val vol = upnp.getVolume(cfg.ip)
                upnpR = if (vol != null)
                    "ok — volume $vol (${System.currentTimeMillis() - t1} ms)"
                else "FAILED — ${upnp.lastError ?: "no response"}"
                wsR = WsProbe.test(cfg.ip, cfg.token, cfg.clientName.ifBlank { CLIENT_NAME_BASE })
            }
            val sd = ssdp.discover()
            val ssdpR = sd.error ?: "ok — found ${sd.tvs.joinToString { "${it.name} (${it.ip})" }}"
            _state.update {
                it.copy(
                    diagRunning = false,
                    diag = DiagReport(
                        wifi = wifi,
                        ip = cfg.ip,
                        mac = cfg.mac,
                        paired = cfg.token.isNotBlank(),
                        rest = restR,
                        upnp = upnpR,
                        ws = wsR,
                        ssdp = ssdpR,
                    ),
                )
            }
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
        artSocket.disconnect()
    }
}
