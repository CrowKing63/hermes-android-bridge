# Code Review: Hermes Android Bridge — Phase 1

## CRITICAL

### 1. DeviceHandler.getNetworkStatus() — WifiInfo.ipv4 property doesn't exist
**File:** `DeviceHandler.kt` lines 139-142

```kotlin
val ip = wifiInfo?.ipv4  // property doesn't exist on any Android version
```

**Fix:**
```kotlin
val linkProps = cm.getLinkProperties(network)
val ipAddress = linkProps?.linkAddresses
    ?.firstOrNull { it.address is java.net.Inet4Address }
    ?.address?.hostAddress ?: "0.0.0.0"
```

### 2. NotificationService — super.getActiveNotifications() clarity
**File:** `NotificationService.kt` line 44

If Android ROM has bugged NotificationListenerService, getActiveNotifications() inside onListenerConnected can deadlock on some Samsung/Xiaomi devices.

**Fix:** Run in background thread with timeout:
```kotlin
withContext(Dispatchers.IO) {
    val current = super.getActiveNotifications()
    ...
}
```

### 3. WebSocketManager — OkHttp pingInterval + server ping_interval double up
**File:** `WebSocketManager.kt` line 65

Both OkHttp (.pingInterval(30s)) and websockets library (ping_interval=30) send PING frames. Waste of battery.

**Fix:** Remove one — prefer application-level heartbeat (can carry semantic meaning).

---

## HIGH

### 4. HermesApp — no thread safety on webSocketManager
**File:** `HermesApp.kt` lines 47-69

`var webSocketManager` has no synchronization. Race between connect/disconnect → leak or NPE.

### 5. DeviceHandler — unused imports (lines 19-24)

Status, StorageStatus, SystemStatus, AppInfo, LocationData — imported but handler returns JsonObject. Dead code.

### 6. MainActivity — HermesApp.get() race with Application.onCreate
**File:** `MainActivity.kt` line 40

If activity created before Application.onCreate finishes → crash.

### 7. WebSocketManager.pendingRequests — not thread-safe
**File:** `WebSocketManager.kt` line 78

mutableMapOf accessed from listener thread and coroutine scope. Use ConcurrentHashMap.

---

## MEDIUM

### 8. AccessibilityBridge — AccessibilityNodeInfo recycle on exception
**File:** `AccessibilityBridge.kt` lines 97-102

If serializeNode throws, child.recycle() skipped → memory leak.

### 9. JsonRpcHandler — notification.list returns stub
Documented as Phase 2 but should clearly TODO-mark.

### 10. Python server — asyncio.get_event_loop() deprecation
**File:** `ws_server.py` line 164

Use `asyncio.get_running_loop().create_future()` inside async function.

### 11. AndroidManifest — usesCleartextTraffic=true for all domains
Add network_security_config.xml to restrict to local IPs only.

---

## LOW

### 12. ScreenNode imported but unused in AccessibilityBridge

### 13. Missing Gradle Wrapper (gradlew) — needed for CI and reproducible builds

### 14. CPU usage reading at single point is meaningless — needs two samples

### 15. test_connection.py doesn't verify actual device handler responses

---

| Severity | Count | Action |
|----------|-------|--------|
| CRITICAL | 3 | Fix before PC build |
| HIGH | 4 | Fix before first device test |
| MEDIUM | 4 | Fix for v1.0 quality |
| LOW | 3 | Nice to have |
