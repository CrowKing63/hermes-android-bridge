# Hermes Android Bridge

Android bridge app for [Hermes Agent](https://hermes-agent.nousresearch.com) — lets your AI agent (running in Termux on your Android phone) communicate with the phone's notifications, UI, sensors, and apps.

## Why?

You use Apple Vision Pro primarily. Your Android phone runs Hermes Agent (Nick) 24/7 inside Termux. You need a way for Nick to read and interact with the phone so you can control it remotely — via Discord DM, via the web, or any Hermes-connected channel.

## Architecture

```
Apple Vision Pro               Android Phone
  (Discord/WEB)                  ┌─────────────────────────�
      │                          │  Hermes Bridge App      │
      │                          │  (Kotlin)               │
   Nick ◄──── Discord ─────►    │  ├─ NotificationService  │
  (Hermes Agent)                │  ├─ AccessibilityBridge  │
  PRoot Ubuntu                  │  ├─ WebSocketManager    │
  Termux                        │  └─ BootReceiver        │
      ▲                         └──────────�──────────────┘
      │ WebSocket (port 8765)              │
      └─────────────────────────────────────�
```

## Features (Phase 1)

- ✅ WebSocket connection to Nick (auto-reconnect, heartbeat)
- ✅ Device registration with capabilities
- ✅ Notification capture & forward
- ✅ Device status: battery, storage, network, system
- ✅ App list & launch
- ✅ Screen content reading (accessibility tree)
- ✅ Screen tap & swipe gestures
- ✅ Type text into focused input
- ✅ Boot auto-start
- ✅ Settings UI for server URL & permissions

## Installation

### 1. Install dependencies

```bash
# On Nick (PRoot Ubuntu / Termux)
pip install websockets
```

### 2. Start the server

```bash
cd ~/hermes-android-bridge
python server/ws_server.py
# Listening on ws://0.0.0.0:8765/bridge
```

### 3. Build & install the APK

```bash
cd ~/hermes-android-bridge
./scripts/build_apk.sh
# Output: app/build/outputs/apk/debug/app-debug.apk
```

Install on phone:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 4. Configure on phone

1. Open **Hermes Bridge** app
2. Enter Nick's IP address: `ws://192.168.x.x:8765/bridge`
3. Tap **Connect**
4. Grant permissions:
   - **Notification Access** → Settings → Special app access → Notification access → Hermes Bridge ON
   - **Accessibility Service** → Settings → Accessibility → Hermes Bridge ON
   - **Battery Optimization** → Settings → Apps → Hermes Bridge → "Unrestricted"

## Protocol

Uses **JSON-RPC 2.0** over WebSocket. See [docs/PROTOCOL.md](docs/PROTOCOL.md) for full spec.

### Example: Read battery status

**Request (Nick → Phone
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "device.battery",
  "params": {}
}
```

**Response (Phone → Nick):**
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "level": 87,
    "isCharging": true,
    "temperature": 32.5,
    "health": "good",
    "plugged": "usb"
  }
}
```

### Example: Notification event (Phone → Nick)

**Event:**
```json
{
  "jsonrpc": "2.0",
  "method": "notification.posted",
  "params": {
    "packageName": "com.discord",
    "appName": "Discord",
    "title": "Nick",
    "text": "BTC buy signal at �85,000,000",
    "postedAt": 1719000001,
    "isClearable": true
  }
}
```

## Project Structure

```
hermes-android-bridge/
├── app/                    ← Kotlin Android app
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/nick/hermesbridge/
│       │   ├── HermesApp.kt
│       │   ├── ws/          ← WebSocket + RPC handler
│       │   ├── service/     ← NotificationListenerService, AccessibilityService, BootReceiver
│       │   ├── handler/     ← DeviceHandler, AppHandler, etc.
│       │   ├── model/       ← Data classes, RPC types
│       │   └── ui/          ← MainActivity, ScreenCaptureActivity
│       └── res/             ← Layouts, values, XML configs
├── server/
│   └── ws_server.py         ← Python WebSocket relay server
├── docs/
│   ├── PROTOCOL.md
│   └── SETUP.md
├── scripts/
│   └── build_apk.sh
├── AGENTS.md                ← Architecture overview
└── TASKS.md                 ← Development task breakdown
```

## Development Phases

| Phase | Focus | Status |
|-------|-------|--------|
| 1 | Skeleton + WS + Notifications + Device Status | ✅ Done |
| 2 | Screen interaction + App control | � Next |
| 3 | Media + Camera + Sensors | � Planned |
| 4 | Offline mode (MQTT fallback) | � Planned |
| 5 | Encryption + Auth + Play Store prep | ⏳ Planned |

## License

MIT — Open source, free for all.
