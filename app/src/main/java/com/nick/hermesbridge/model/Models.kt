package com.nick.hermesbridge.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// ──────────────────────────────────────────────
// Connection State
// ──────────────────────────────────────────────

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    AUTHENTICATING,
    CONNECTED,
    ERROR
}

// ──────────────────────────────────────────────
// JSON-RPC 2.0 Base Types
// ──────────────────────────────────────────────

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Int? = null,
    val method: String,
    val params: JsonObject? = null
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: Int? = null,
    val result: JsonObject? = null,
    val error: JsonRpcError? = null
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonObject? = null
)

@Serializable
data class JsonRpcEvent(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonObject? = null
)

// ──────────────────────────────────────────────
// Device Info
// ──────────────────────────────────────────────

@Serializable
data class DeviceInfo(
    val deviceId: String,
    val model: String,
    val manufacturer: String,
    val androidVersion: String,
    val sdkVersion: Int,
    val capabilities: List<String>
)

@Serializable
data class BatteryStatus(
    val level: Int,
    val isCharging: Boolean,
    val temperature: Float,
    val health: String,
    val plugged: String
)

@Serializable
data class NetworkStatus(
    val wifiConnected: Boolean,
    val ssid: String?,
    val ipAddress: String,
    val signalStrength: Int,
    val isAvailable: Boolean,
    val isMetered: Boolean
)

@Serializable
data class StorageStatus(
    val internalTotal: Long,
    val internalFree: Long,
    val externalTotal: Long?,
    val externalFree: Long?
)

@Serializable
data class SystemStatus(
    val uptimeSeconds: Long,
    val availableMemory: Long,
    val totalMemory: Long,
    val cpuUsage: Float,
    val batteryLevel: Int
)

// ──────────────────────────────────────────────
// Notification
// ──────────────────────────────────────────────

@Serializable
data class Notification(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val isClearable: Boolean
)

// ──────────────────────────────────────────────
// App Info
// ──────────────────────────────────────────────

@Serializable
data class AppInfo(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
    val versionName: String
)

// ──────────────────────────────────────────────
// Location & Sensor
// ──────────────────────────────────────────────

@Serializable
data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val altitude: Double,
    val timestamp: Long
)

@Serializable
data class CompassData(
    val azimuth: Float,
    val pitch: Float,
    val roll: Float,
    val timestamp: Long
)

// ──────────────────────────────────────────────
// Screen
// ──────────────────────────────────────────────

@Serializable
data class ScreenNode(
    val className: String,
    val text: String?,
    val contentDescription: String?,
    val bounds: String,
    val clickable: Boolean,
    val focusable: Boolean,
    val children: List<ScreenNode> = emptyList()
)

// ──────────────────────────────────────────────
// Error codes (JSON-RPC reserved range)
// ──────────────────────────────────────────────

object RpcErrorCodes {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    // Custom application errors
    const val DEVICE_PERMISSION_DENIED = -32001
    const val DEVICE_BUSY = -32002
    const val DEVICE_NOT_SUPPORTED = -32003
    const val SCREEN_CAPTURE_REQUIRED = -32010
    const val LOCATION_PERMISSION_DENIED = -32011
    const val CAMERA_PERMISSION_DENIED = -32012
}
