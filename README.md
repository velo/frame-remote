# Frame Remote

A native Android remote for Samsung Frame TVs (Tizen) that talks to the TV
**directly over your LAN**. No Samsung account, no SmartThings, no cloud
round-trip, no telemetry — just HTTP, UPnP, WebSocket and Wake-on-LAN
straight to the TV. Commands land in ~20 ms, which is the whole point.

Built for and verified against a 2024 65" The Frame (`QN65LS03DAGXZD`,
Wi-Fi/BT API 2.0.25), but it should work on most recent Tizen TVs.

## Features

- **Absolute volume slider** (0–100, maps 1:1 to the TV's on-screen number)
  plus mute — via UPnP `RenderingControl`, the API SmartThings hides
- **Power** — off/art-mode via the remote WebSocket, power-on from standby
  via Wake-on-LAN
- **D-pad navigation** — up/down/left/right, OK, back, home
- **One-tap app launch** — Plex and YouTube by Tizen app id
- **SSDP discovery** — finds the TV on first run and auto-fills its IP and
  MAC; manual entry as fallback
- Dark, one-handed, thumb-reachable layout

## Install (Obtainium)

Get [Obtainium](https://github.com/ImranR98/Obtainium) and add this app URL:

```
https://github.com/velo/frame-remote
```

Obtainium picks up the APK attached to the latest GitHub release and keeps
it updated. You can also just download the APK from the
[releases page](https://github.com/velo/frame-remote/releases) and sideload it.

## First run

1. Make sure the phone is on the same LAN as the TV.
2. Open the app — it searches for the TV via SSDP. Tap your TV when it shows
   up (or enter its IP manually; the MAC is auto-filled from the TV).
3. Press any navigation key. The TV pops an **"Allow"** prompt for
   *FrameRemoteAndroid* — accept it once. The pairing token is stored on the
   phone and the prompt never comes back. ("Forget pairing" in settings
   clears it.)

## Power-on needs network standby

The TV's APIs only answer while it is awake, so powering **on** is done with
a Wake-on-LAN magic packet to the TV's MAC address. For that to work, enable
the TV setting usually called **Power On with Mobile** / **Network Standby**
(`Settings → General → Network → Expert Settings`). Wired connections are
the most reliable. Powering **off** (a short press, which on a Frame drops
into Art Mode — same as the physical remote) goes over the WebSocket.

## How it talks to the TV

| Function | Transport |
|---|---|
| Reachability / power state / device info | `GET http://tv:8001/api/v2/` |
| Absolute volume + mute | UPnP SOAP, `http://tv:9197/upnp/control/RenderingControl1` |
| Keys (nav, power, back, home) | `wss://tv:8002/api/v2/channels/samsung.remote.control` |
| App launch | `POST http://tv:8001/api/v2/applications/<appId>` (WS `ed.apps.launch` fallback) |
| Power on | Wake-on-LAN magic packet (UDP 9/7) |

The TV's WebSocket uses a self-signed certificate; the app relaxes TLS
verification **only** for the configured TV host, never globally.

Firmware quirks this app works around (observed on 2.0.25): the
`applications` list endpoint returns 404 and `app_list` over the WebSocket
never answers — so apps are launched by known id (Plex `3201512006963`,
YouTube `111299001912`) instead of enumerating.

## Building

```
./gradlew assembleDebug
```

CI builds a signed release APK on every `v*` tag and attaches it to a GitHub
Release. Signing uses the `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS` and `KEY_PASSWORD` repository secrets.

## Privacy

The app makes no network connections except to the TV's LAN address you
configure (and LAN broadcasts for discovery/wake). Nothing is collected,
nothing phones home.

## License

MIT
