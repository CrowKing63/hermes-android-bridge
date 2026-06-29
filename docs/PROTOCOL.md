# Hermes Bridge — Protocol Specification

## Overview

JSON-RPC 2.0 over WebSocket. The Android phone connects outbound to Nick's WebSocket server
at `ws://<NICK_IP>:8765/bridge`.

## Connection Lifecycle

```
Phone                        Nick Server
  │                             │
  ├──── WebSocket Connect ──────►│
  │     Headers:                 │
  │       X-Device-ID            │
  │       X-Device-Model         │
  │                             │
  │�─── Connection Accepted ────�
  │                             │
  ├─ device.register event ────►│  (announce capabilities)
  │                             │
  │◄── Bidirectional Messaging ─�
  │                             │
  ├─ notification.posted ─────►│  (phone push events)
  │◄── device.battery request ──�  (server requests)
  ├─ device.battery response ──►│
```

## Message Types

### Request (server → phone or phone → server)
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "device.battery",
  "params": {}
}
```

### Response
```json
{
": "2.0",
  "id": 1,
  "result": { "level": 87, "isCharging": false }
}
```

### Error Response
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "error": {
    "code": -32601,
    "message": "Method not found: device.foobar"
  }
}
```

### Event (no id, no response expected)
```json
{
  "jsonrpc": "2.0",
  "method": "notification.posted",
  "params": { ... }
}
```

## Methods

### notification.list
**Params:** `{ "limit"?: number, "since"?: number, "package"?: string }`
**Result:** `{ "notifications": Notification[] }`

### notification.subscribe
**Params:** `{ "packages": string[] }`
**Result:** `{ "subscribed": string[], "status": "ok" }`

### notification.clear
**Params:** `{ "key"?: string }`
**Result:** `{ "cleared": boolean }`

### device.battery
**Result:** `{ "level": number, "isCharging": boolean, "temperature": number, "health": string, "plugged": string }`

### device.storage
**Result:** `{ "internalTotal": number, "internalFree": number, "externalTotal"?, "externalFree"? }`

### device.network
**Result:** `{ "wifiConnected": boolean, "ssid": string, "ipAddress": string, "signalStrength": number, "isAvailable": boolean, "isMetered": boolean }`

### device.system
**Result:** `{ "uptimeSeconds": number, "availableMemory": number, "totalMemory": number, "cpuUsage": number, "batteryLevel": number }`

### screen.read
**Result:** `{ "nodes": ScreenNode[] }`

### screen.tap
**Params:** `{ "x": number, "y": number }`
**Result:** `{ "tapped": true, "x": number, "y": number }`

### screen.swipe
**Params:** `{ "x1": number, "y1": number, "x2": number, "y2": number, "duration"?: number }`
**Result:** `{ "swiped": true }`

### screen.type
**Params:** `{ "text": string }`
**Result:** `{ "typed": true, "length": number }`

### screen.screenshot
**Result:** `{ "image": string (base64), "format": "png" }`

### app.list
**Params:** `{ "system"?: boolean }`
**Result:** `{ "apps": AppInfo[], "count": number }`

### app.launch
**Params:** `{ "package": string }`
**Result:** `{ "launched": boolean, "package": string }`

### app.current
**Result:** `{ "package": string, "appName": string }`

### media.volume
**Params:** `{ "level"?: number }` — if no level, returns current level
**Result:** `{ "level": number }`

### media.tts
**Params:** `{ "text": string }`
**Result:** `{ "spoken": true }`

### camera.snap
**Params:** `{ "facing": "front" | "back" }`
**Result:** `{ "image": string (base64), "format": "jpg" }`

### sensor.location
**Result:** `{ "latitude": number, "longitude": number, "accuracy": number, "altitude": number, "timestamp": number }`

### sensor.compass
**Result:** `{ "azimuth": number, "pitch": number, "roll": number, "timestamp": number }`

## Error Codes

| Code | Message | Meaning |
|------|---------|---------|
| -32700 | Parse error | Invalid JSON |
| -32600 | Invalid request | Malformed request |
| -32601 | Method not found | Unknown method |
| -32602 | Invalid params | Missing/wrong params |
| -32603 | Internal error | Server internal |
| -32001 | Permission denied | Device permission missing |
| -32002 | Device busy | Concurrent operation |
| -32003 | Not supported | Capability unavailable |
| -32010 | Screen capture required | MediaProjection not granted |
| -32011 | Location permission denied | GPS permission missing |
| -32012 | Camera permission denied | Camera permission missing |

## Events (Phone → Server)

| Event | Description |
|-------|-------------|
| `device.register` | Sent on connection with capabilities |
| `device.ping` | Heartbeat |
| `notification.posted` | New notification received |
| `notification.removed` | Notification dismissed |
| `device.battery_change` | Battery level changed significantly |
