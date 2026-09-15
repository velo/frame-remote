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
- **Art Mode toggle** — what the power button on a Frame actually does
  (see "The Frame's power model" below); the button shows the current state
- **Wake TV** — a separate, explicit Wake-on-LAN action for a TV that is
  genuinely powered off (unverified — see below)
- **D-pad navigation** — up/down/left/right, OK, back, home
- **One-tap app launch** — Plex and YouTube by Tizen app id; the button
  lights amber when that app is the one on screen (per-app REST status,
  keyed off `visible` — `running` stays true for backgrounded apps). Home
  is deliberately never highlighted: the firmware exposes no home-screen
  state, and a guessed highlight would lie
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

## The Frame's power model (read this — it will surprise you)

A Frame TV does not power off from software. Verified on a 2024 LS03D
(API 2.0.25):

- `KEY_POWER` **toggles Art Mode**, in both directions. The TV's
  `PowerState` stays `"on"` the whole time — with Art Mode enabled the
  Frame is never in deep standby; it stays fully network-alive.
- `KEY_POWEROFF` is a **silent no-op** on this firmware. The TV ignores it.
- True deep standby is reachable only physically: a long-press on the
  actual remote, or disabling Art Mode in the TV's settings.

So the app's round button is labelled what it is — an **Art Mode toggle** —
and shows the TV's current art state, read live over the TV's art-app
channel.

### Wake TV (Wake-on-LAN) — honest status: unverified

For a TV that is genuinely powered off, the app has a separate, explicit
**Wake TV** button (shown when the TV stops answering). It sends a standard
Wake-on-LAN magic-packet burst (UDP 9 and 7; global broadcast, directed
broadcast and unicast) to the TV's MAC, which the app auto-fills from the
device info. This is implemented to spec but has **never been observed
waking this TV** — precisely because no software command can put a Frame
into the standby state WoL recovers from, so it could not be tested
end-to-end. For it to have a chance, enable the TV setting usually called
**Power On with Mobile** / **Network Standby**
(`Settings → General → Network → Expert Settings`). Wired connections are
the most reliable.

## How it talks to the TV

| Function | Transport |
|---|---|
| Reachability / power state / device info | `GET http://tv:8001/api/v2/` |
| Absolute volume + mute | UPnP SOAP, `http://tv:9197/upnp/control/RenderingControl1` |
| Keys (nav, art-mode toggle, back, home) | `wss://tv:8002/api/v2/channels/samsung.remote.control` |
| Art Mode state | `wss://tv:8002/api/v2/channels/com.samsung.art-app` (`get_artmode_status`) |
| App launch | `POST http://tv:8001/api/v2/applications/<appId>` (WS `ed.apps.launch` fallback) |
| Wake TV | Wake-on-LAN magic packet (UDP 9/7) — unverified, see above |

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

## Troubleshooting

- The app pins all its traffic to **Wi-Fi** while open. With mobile data on
  and a LAN without internet access, Android would otherwise route
  everything over cellular and the TV would never answer — the classic
  "app does nothing" failure. If you are not on Wi-Fi, the app says so
  instead of failing silently.
- **Settings → Diagnostics** shows the resolved IP/MAC/pairing state and a
  **Test connection** button that exercises each transport (REST :8001,
  UPnP :9197, WebSocket :8002, SSDP) and reports per-transport results.
- **"TV refused the pairing"**: once a Samsung TV denies a device it
  refuses that client identity *silently, forever* — it will not prompt
  again for the same name. **Re-pair** in settings therefore mints a new
  client identity (e.g. `FrameRemoteAndroid-7f3a2`), drops the stored token
  and reconnects, so the TV treats it as a new device and shows the Allow
  prompt again. Normal reconnects always reuse the same identity — only
  Re-pair rotates it, so the TV's device list is not littered. If the TV
  still refuses, on the TV go to *Settings → General → External Device
  Manager → Device Connect Manager → Device List*, remove stale entries,
  and make sure *Access Notification* is not set to Off. The TV holds the
  veto here, not the app.
- **Volume slider replaced by Vol −/+ buttons**: the TV's UPnP volume
  service refused the request (observed as HTTP 401 while the TV distrusts
  the device). The app degrades to KEY_VOLUP/KEY_VOLDOWN stepping over the
  WebSocket and says so, instead of showing a dead slider. It returns to
  the absolute slider automatically once UPnP answers again — usually
  after pairing succeeds.
- Discovery needs the TV awake; manual IP entry is always available in
  settings regardless of what discovery finds.

## Privacy

The app makes no network connections except to the TV's LAN address you
configure (and LAN broadcasts for discovery/wake). Nothing is collected,
nothing phones home.

## License

MIT
