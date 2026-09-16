# Zoo Remote — Android App

Native companion app for the **Zoo Code Remote Control** server (VS Code extension). Shows live task status, streams activity into a chat feed, and lets you answer asks or switch mode/model — even with the app closed, via foreground service + notifications.

UI language follows the device locale: English by default, German included (`values-de/`).

## Features

- **Live status screen:** connection indicator (green/yellow/red), chips for current mode, model profile and context-window usage; chat-style activity feed with collapsible reasoning, tool/command lines, errors and completion results.
- **Ask handling:** confirmation asks via "Approve"/"Deny", free-text answers and follow-up suggestions right from the app — same code path as the webview (`yesButtonClicked`/`noButtonClicked`/`messageResponse`).
- **Notifications:** persistent connection notification (foreground service, `dataSync`) + "Input required" notification with Approve/Deny actions — works while the app is closed. Android 13+ asks for the notification permission once.
- **Session picker:** task history per workspace (`GET /api/tasks?workspace=`), restore/open/cancel tasks, recently used workspaces openable in a new VS Code window; start new sessions with free text.
- **Mode/model switching:** picker screens (`GET /api/modes`, `GET /api/models`), optimistic UI with rollback, fresh server status adopted immediately.
- **Robust connection:** TLS fingerprint pinning (SHA-256), one-tap pairing against the plugin's 120 s window, reconnect with exponential backoff (1 s → 30 s), pull-to-refresh as HTTP fallback (`GET /api/status`), clear localized error messages for timeout, wrong fingerprint and wrong token (401 jumps back to setup).
- **Battery:** no WakeLock; OkHttp ping every 30 s (logged keepalive, pong answered automatically) — the foreground service keeps the process alive.

## Prerequisites

- Android 8.0+ (API 26), tested with targetSdk 34.
- VS Code running the Zoo Code fork **with the remote control server enabled** (branch `feature/remote-control`).
- Computer and phone on the **same LAN** (or emulator, see below).

## Pairing guide

1. **Start the server:** in VS Code Settings → "Remote Control" enable it (port defaults to `8999`). The server starts non-blocking; the **"Zoo Remote"** output channel shows:
   - the reachable LAN IP + port,
   - the **SHA-256 fingerprint** of the self-signed certificate (hex, colon-separated),
   - hints on port conflicts or certificate regeneration.
2. **One-tap pairing:** in VS Code Settings → "Remote Control" press **"Start pairing"** — a one-shot window opens for 120 s. In the app enter only **Host/IP + Port** and tap **"Pairing"**: token and fingerprint are fetched from `POST /api/pair` automatically (TOFU TLS, then verified with pinning). The manual fields remain in the "Manual input (expert)" section for re-pairing after a reset or older plugin versions.
3. **Success →** green status line with current mode/model + automatic navigation to the status screen. Failures are shown inline (timeout with live attempt counter, fingerprint mismatch incl. expected/presented values, 401).
4. **Android 13+:** allow the notification permission so ask notifications appear.

### Emulator note

The Android emulator reaches the host machine via `10.0.2.2` (NAT), not `localhost`. A real device needs the computer's LAN IP — and the computer must be reachable from the device (port forwarding, see below).

## Port forwarding / router

- The remote server listens on **TCP 8999** by default. For a phone on Wi-Fi to reach the computer, firewall/router must allow that port:
  - **Windows Firewall:** exception for `node.exe` (or an inbound TCP rule for 8999) — easiest for the private network profile.
  - **Router with client isolation** ("AP Isolation"): disable it, otherwise Wi-Fi devices can't see each other.
  - **Different subnets/VLANs:** set up a static route or port forwarding to the computer.
- Quick test from the phone: open `https://<LAN-IP>:8999/api/health` in a browser → expect `{"ok":true}` (the certificate warning is normal — this only checks reachability).
- Running on another port? Change it under VS Code "Remote Control" and use the same port in app + firewall.

## Security notes

- **Keep the token secret:** the bearer token grants full access (read status, answer asks, switch mode/model). It is shown in the output channel and settings — treat it like a password; regenerate on loss (then re-pair the app).
- **Self-signed certificate + pinning:** the app trusts exactly the one certificate whose fingerprint was captured during pairing (SHA-256 over the leaf cert, constant-time compare). A man-in-the-middle with a valid public cert still fails because its fingerprint differs. **Trade-off:** the hostname check is deliberately relaxed (the cert only carries `localhost`/`127.0.0.1` SANs; identity comes from the pin) — documented in code.
- **Certificate changed?** → the app reports "Certificate changed — re-pair?" and expects the new fingerprint from the output channel (e.g. after regeneration or a certificate repair).
- **No public internet without VPN:** the connection runs over TLS 1.2+ with pinning, but for on-the-go use a VPN is recommended — especially since the token travels as a header and the app verifies the cert by fingerprint only (not via CA).
- **Optional IP allowlist:** the server supports `zoo-code.remote.allowedIps` (empty = all IPs allowed). For long-running servers on shared networks, whitelist just your phone's IP.

## Build & tests

```bat
cd app
gradlew.bat assembleDebug    :: APK → app\build\outputs\apk\debug\
gradlew.bat test             :: JVM unit tests (API parsing)
```

Version catalog in `gradle/libs.versions.toml`; `versionName` is bumped per session (`0.x.y-sessionN`).

## Error cases (app side)

| Case | Behavior |
|------|----------|
| Host unreachable / timeout (5 s connect, 10 s read) | "No response from host:port … check firewall/port forwarding" or "Host unreachable" with the emulator hint; live counter shows how long the attempt already takes |
| Wrong fingerprint | "Certificate changed — re-pair?" with expected and presented fingerprint |
| Wrong token (HTTP 401) | Message on screen; on actions from status/mode/model screens the app jumps back to setup **with** the error message |
| Server offline while socket open | Reconnect backoff 1 s → 30 s, status "Connecting…"; auto-reconnects after server restart |
| WS auth close code (4002/4003) | Terminal error: "Wrong token (WS close code …)" — re-pairing required |
