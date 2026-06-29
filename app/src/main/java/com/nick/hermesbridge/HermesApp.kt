package com.nick.hermesbridge

import android.app.Application
import android.content.Context
import android.os.Build
import com.nick.hermesbridge.model.DeviceInfo
import com.nick.hermesbridge.model.ConnectionState
import com.nick.hermesbridge.ws.WebSocketManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Application singleton for Hermes Bridge.
 * 
 * Responsibilities:
 * - Initialize WebSocketManager on startup
 * - Provide app-wide CoroutineScope
 * - Expose device info (model, capabilities, etc.)
 * - Manage global connection state
 */
class HermesApp : Application() {

    companion object {
        private const val TAG = "HermesBridge"
        private const val PREFS_NAME = "hermes_bridge_prefs"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_SERVER_URL = "server_url"
        private const val DEFAULT_SERVER_URL = "ws://192.168.1.100:8765/bridge"

        @Volatile
        private var instance: HermesApp? = null

        fun get(): HermesApp = instance!!
    }

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    var webSocketManager: WebSocketManager? = null
        private set

    val deviceId: String by lazy { getOrCreateDeviceId() }
    val deviceInfo: DeviceInfo by lazy { buildDeviceInfo() }

    override fun onCreate() {
        super.onCreate()
        instance = this
        android.util.Log.i(TAG, "HermesApp started. Device: ${deviceInfo.model}, ID: $deviceId")
    }

    override fun onTerminate() {
        webSocketManager?.disconnect()
        super.onTerminate()
    }

    /**
     * Initialize and connect WebSocket server.
     */
    fun connect(serverUrl: String = getServerUrl()) {
        saveServerUrl(serverUrl)
        _connectionState.value = ConnectionState.CONNECTING
        webSocketManager?.disconnect()
        webSocketManager = WebSocketManager(
            serverUrl = serverUrl,
            deviceInfo = deviceInfo,
            scope = appScope,
            onStateChange = { state -> _connectionState.value = state }
        )
        webSocketManager?.connect()
    }

    /**
     * Disconnect from WebSocket server.
     */
    fun disconnect() {
        webSocketManager?.disconnect()
        webSocketManager = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * Get saved server URL or default.
     */
    fun getServerUrl(): String {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
    }

    fun saveServerUrl(url: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER_URL, url)
            .apply()
    }

    // ── Private ──

    private fun getOrCreateDeviceId(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DEVICE_ID, null) ?: run {
            val newId = "android_${UUID.randomUUID().toString().take(8)}"
            prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
            newId
        }
    }

    private fun buildDeviceInfo(): DeviceInfo {
        val capabilities = mutableListOf(
            "notification.list", "notification.subscribe", "notification.clear",
            "device.battery", "device.storage", "device.network", "device.system",
            "screen.read", "screen.tap", "screen.swipe", "screen.type",
            "app.list", "app.launch", "app.current",
            "media.volume", "media.tts",
            "sensor.location", "sensor.compass"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            capabilities.add("screen.screenshot")
        }
        if (packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)) {
            capabilities.add("camera.snap")
        }

        return DeviceInfo(
            deviceId = deviceId,
            model = "${Build.MANUFACTURER} ${Build.MODEL}",
            manufacturer = Build.MANUFACTURER,
            androidVersion = Build.VERSION.RELEASE,
            sdkVersion = Build.VERSION.SDK_INT,
            capabilities = capabilities
        )
    }
}
