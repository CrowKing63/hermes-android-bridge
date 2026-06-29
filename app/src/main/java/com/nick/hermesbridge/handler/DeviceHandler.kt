package com.nick.hermesbridge.handler

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.app.ActivityManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.nick.hermesbridge.model.BatteryStatus
import com.nick.hermesbridge.model.NetworkStatus
import com.nick.hermesbridge.model.StorageStatus
import com.nick.hermesbridge.model.SystemStatus
import com.nick.hermesbridge.model.AppInfo
import com.nick.hermesbridge.model.LocationData
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.TimeZone

/**
 * Handles device.* commands:
 * - device.battery
 * - device.storage
 * - device.network
 * - device.system
 */
class DeviceHandler(private val context: Context) {

    /**
     * Dispatch a device.* command. Returns JsonObject result or throws.
     */
    fun handle(method: String, params: JsonObject?): JsonObject {
        return when (method) {
            "device.battery" -> getBatteryStatus()
            "device.storage" -> getStorageStatus()
            "device.network" -> getNetworkStatus()
            "device.system" -> getSystemStatus()
            else -> throw IllegalArgumentException("Unknown method: $method")
        }
    }

    // ── Battery ──

    fun getBatteryStatus(): JsonObject {
        val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return buildJsonObject { put("error", "Battery info unavailable") }

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL

        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f

        val health = when (intent.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            else -> "unknown"
        }

        val plugged = when (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> "unplugged"
        }

        return buildJsonObject {
            put("level", pct)
            put("isCharging", isCharging)
            put("temperature", temp)
            put("health", health)
            put("plugged", plugged)
        }
    }

    // ── Storage ──

    fun getStorageStatus(): JsonObject {
        val internalStat = StatFs(Environment.getDataDirectory().path)
        val internalTotal = internalStat.totalBytes
        val internalFree = internalStat.availableBytes

        var externalTotal: Long? = null
        var externalFree: Long? = null

        if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            val externalStat = StatFs(Environment.getExternalStorageDirectory().path)
            externalTotal = externalStat.totalBytes
            externalFree = externalStat.availableBytes
        }

        return buildJsonObject {
            put("internalTotal", internalTotal)
            put("internalFree", internalFree)
            externalTotal?.let { put("externalTotal", it) }
            externalFree?.let { put("externalFree", it) }
        }
    }

    // ── Network ──

    @Suppress("DEPRECATION")
    fun getNetworkStatus(): JsonObject {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val wifiConnected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        val ssid: String?
        val signalStrength: Int
        val ipAddress: String

        if (wifiConnected) {
            val wifiInfo = wm.connectionInfo
            ssid = wifiInfo?.ssid?.removeSurrounding("\"")
            signalStrength = wifiInfo?.rssi ?: -1
            val ip = wifiInfo?.ipv4
            ipAddress = ip?.let {
                "${(it and 0xFF)}.${(it shr 8 and 0xFF)}.${(it shr 16 and 0xFF)}.${(it shr 24 and 0xFF)}"
            } ?: "0.0.0.0"
        } else {
            ssid = null
            signalStrength = -1
            ipAddress = try {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                var addr = "0.0.0.0"
                for (intf in interfaces) {
                    for (a in intf.inetAddresses) {
                        if (!a.isLoopbackAddress && a is java.net.Inet4Address) {
                            addr = a.hostAddress ?: "0.0.0.0"
                        }
                    }
                }
                addr
            } catch (e: Exception) {
                "0.0.0.0"
            }
        }

        return buildJsonObject {
            put("wifiConnected", wifiConnected)
            put("ssid", ssid ?: "")
            put("ipAddress", ipAddress)
            put("signalStrength", signalStrength)
            put("isAvailable", network != null)
            put("isMetered", caps?.let { !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) } ?: true)
        }
    }

    // ── System ──

    fun getSystemStatus(): JsonObject {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        // Uptime
        val uptimeMs = SystemClock.elapsedRealtime()

        // CPU usage (rough estimate)
        val cpuUsage = try {
            val pid = android.os.Process.myPid()
            val reader = java.io.RandomAccessFile("/proc/stat", "r")
            val load = reader.readLine()
            reader.close()
            // Very rough — real CPU usage requires sampling over time
            val toks = load.split("\\s+".toRegex()).drop(1).mapNotNull { it.toLongOrNull() }
            if (toks.size >= 4) {
                val idle = toks[3]
                val total = toks.sum()
                if (total > 0) ((total - idle).toFloat() / total * 100) else 0f
            } else 0f
        } catch (e: Exception) {
            -1f
        }

        return buildJsonObject {
            put("uptimeSeconds", uptimeMs / 1000)
            put("availableMemory", memInfo.availMem)
            put("totalMemory", memInfo.totalMem)
            put("cpuUsage", cpuUsage)
            put("batteryLevel", -1) // filled by getBatteryStatus context
        }
    }

    // ── App List ──

    fun getAppList(includeSystem: Boolean = false): JsonObject {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { includeSystem || (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { info ->
                mapOf(
                    "packageName" to info.packageName,
                    "appName" to pm.getApplicationLabel(info).toString(),
                    "isSystemApp" to ((info.flags and ApplicationInfo.FLAG_SYSTEM) != 0),
                    "versionName" to (try { pm.getPackageInfo(info.packageName, 0).versionName } catch (e: Exception) { "unknown" })
                )
            }

        return buildJsonObject {
            putJsonArray("apps") {
                apps.forEach { app ->
                    add(buildJsonObject {
                        app.forEach { (k, v) -> put(k, JsonPrimitive(v.toString())) }
                    })
                }
            }
        }
    }

    // ── Location ──

    fun getLocation(): JsonObject {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

        for (provider in providers) {
            try {
                if (lm.isProviderEnabled(provider)) {
                    val location: Location? = lm.getLastKnownLocation(provider)
                    if (location != null) {
                        return buildJsonObject {
                            put("latitude", location.latitude)
                            put("longitude", location.longitude)
                            put("accuracy", location.accuracy)
                            put("altitude", location.altitude)
                            put("timestamp", location.time)
                        }
                    }
                }
            } catch (e: SecurityException) {
                // Permission not granted
            }
        }

        return buildJsonObject {
            put("error", "Location unavailable — no provider or permission denied")
        }
    }
}
