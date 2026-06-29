package com.nick.hermesbridge.handler

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import com.nick.hermesbridge.model.AppInfo
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Handles app.* commands:
 * - app.list: List installed applications
 * - app.launch: Launch an app by package name
 * - app.current: Get currently running foreground app
 */
class AppHandler(private val context: Context) {

    companion object {
        private const val TAG = "HermesAppH"
    }

    /**
     * Get list of installed apps.
     */
    fun getAppList(includeSystem: Boolean = false): JsonObject {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { includeSystem || (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
            .map { info ->
                val version = try {
                    pm.getPackageInfo(info.packageName, 0).versionName ?: "unknown"
                } catch (e: Exception) {
                    "unknown"
                }
                mapOf(
                    "packageName" to info.packageName,
                    "appName" to pm.getApplicationLabel(info).toString(),
                    "isSystemApp" to ((info.flags and ApplicationInfo.FLAG_SYSTEM) != 0),
                    "versionName" to version
                )
            }

        return buildJsonObject {
            put("count", apps.size)
            putJsonArray("apps") {
                apps.forEach { app ->
                    add(buildJsonObject {
                        put("packageName", app["packageName"] ?: "")
                        put("appName", app["appName"] ?: "")
                        put("isSystemApp", app["isSystemApp"] == true)
                        put("versionName", app["versionName"] ?: "")
                    })
                }
            }
        }
    }

    /**
     * Launch an app by package name.
     */
    fun launch(packageName: String): JsonObject {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(packageName)

        return if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Log.i(TAG, "Launched: $packageName")
            buildJsonObject {
                put("launched", true)
                put("package", packageName)
            }
        } else {
            Log.w(TAG, "No launch intent for: $packageName")
            buildJsonObject {
                put("launched", false)
                put("error", "App not found or no launch intent")
                put("package", packageName)
            }
        }
    }

    /**
     * Get the current foreground app (best effort).
     */
    fun getCurrentApp(): JsonObject {
        // On modern Android, this requires UsageStatsManager
        // For now, return what we can detect
        return buildJsonObject {
            put("package", "unknown")
            put("appName", "unknown")
            put("note", "Foreground app detection requires UsageStats permission (Phase 2)")
        }
    }
}
