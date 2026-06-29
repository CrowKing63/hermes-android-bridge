# Hermes Android Bridge — Setup Guide

## Prerequisites

- Android phone running Termux with PRoot Ubuntu (this is Nick's environment)
- JDK 17 + Android SDK (for building APK) on your PC
- Python 3.10+ (for running the relay server)

## Step 1: Start the Server on Nick

Inside your PRoot Ubuntu (where Nick lives):

```bash
cd ~/hermes-android-bridge
pip install websockets
python server/ws_server.py
```

You should see:
```
2026-06-29 14:30:42 [INFO] HermesBridgeServer: Starting Hermes Bridge Server on 0.0.0.0:8765
2026-06-29 14:30:42 [INFO] HermesBridgeServer: Server ready. Waiting for device connections on ws://0.0.0.0:8765/bridge
```

Find your phone's local IP:
- Go to Android Settings → About Phone → Status → IP Address
- Usually `192.168.x.x`

Make sure the server is listening on that interface. Check:
```bash
ss -tlnp | grep 8765
```

## Step 2: Build & Install the APK

On your PC (with JDK 17 + Android SDK):

```bash
cd ~/hermes-android-bridge
chmod +x scripts/build_apk.sh
scripts/build_apk.sh
```

Transfer and install on phone:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Step 3: Configure the App on Phone

1. Open **Hermes Bridge** (it will show a purple status circle = Disconnected)
2. In the "WebSocket Server URL" field, enter: `ws://192.168.x.x:8765/bridge`
   (replace `192.168.x.x` with Nick's local IP)
3. Tap **Connect**
4. The status should change from red → yellow → green

## Step 4: Grant Permissions

### Notification Access
1. In Hermes Bridge app, tap "Notification Access"
2. Find "Hermes Bridge" → Toggle ON
3. Confirm warning dialog

### Accessibility Service
1. In Hermes Bridge app, tap "Accessibility Service"
2. Find "Hermes Bridge" → Toggle ON
3. Confirm warning dialog ("Hermes Bridge will be able to read screen content and perform taps")

### Battery Optimization (IMPORTANT!)
If Android puts the app to sleep, the connection will die.
1. In Hermes Bridge app, tap "Disable Battery Optimization"
2. Select "All apps" → "Hermes Bridge" → "Don't optimize"

Alternatively:
- Settings → Apps → Hermes Bridge → Battery → "Unrestricted"

## Step 5: Verify Connection

On Nick, check the server logs:
```
2026-06-29 14:35:12 [INFO] HermesBridgeServer: Device connected: android_abc12345 (Samsung SM-S918B) — total: 1
2026-06-29 14:35:12 [INFO] HermesBridgeServer: [android_abc12345] Registered ... — capabilities: ['device.battery', ...]
```

Once connected, you can query the device via WebSocket:
```bash
python scripts/test_connection.py
```

Or with Nick:
```
"Nick, read my phone's battery status"
→ sends device.battery to phone → returns level, charging, etc.
```

## Troubleshooting

| Problem | Cause | Fix |
|---------|-------|-----|
| Status stays "Connecting..." | Firewall on Nick | `sudo ufw allow 8765` or `iptables -A INPUT -p tcp --dport 8765 -j ACCEPT` |
| Status "Error" | Wrong URL/IP | Check Nick's IP: `hostname -I` on phone (not loopback) |
| No notifications appear | Permission not granted | Re-enable Notification Access in Settings |
| Taps don't work | Accessibility not active | Re-enable in Settings → Accessibility |
| Connection drops after sleep | Battery optimization | Set to "Unrestricted" |
| APK won't install | Signature conflict | Uninstall old version first |

## Network Requirements

- Nick (running in Termux) and Android phone must be on the same WiFi network
- No internet connection required (all local traffic)
- Nick needs to bind to `0.0.0.0` not `127.0.0.1` to accept connections from Android OS

If Nick is running in a different container namespace (PRoot), use:
```bash
# Run server in Termux HOST (not PRoot)
# Or set up port forwarding: Termux host forwards to PRoot
```
