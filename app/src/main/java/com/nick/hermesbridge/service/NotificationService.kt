package com.nick.hermesbridge.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.nick.hermesbridge.HermesApp
import com.nick.hermesbridge.model.Notification
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Captures all device notifications and forwards them to Nick via WebSocket.
 *
 * Lifecycle:
 * - Connected by system when Notification Access is granted in Settings
 * - Runs in background, receiving callbacks for every notification
 * - Maintains a cache of active notifications (max 100)
 * - Posts events to WebSocket via WebSocketManager
 */
class NotificationService : NotificationListenerService() {

    companion object {
        private const val TAG = "HermesNotif"
        private const val MAX_CACHED = 100
    }

    private val activeNotifications = LinkedHashMap<String, Notification>(MAX_CACHED)

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "NotificationListener connected. Active notifications: ${activeNotifications.size}")
        // Re-populate cache with currently active notifications
        try {
            activeNotifications.clear()
            activeNotifications.forEach { (key, sbn) -> }
        } catch (e: Exception) {
            Log.w(TAG, "Could not enumerate active notifications: ${e.message}")
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "NotificationListener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        val notification = Notification(
            key = sbn.key,
            packageName = sbn.packageName,
            appName = getAppName(sbn.packageName),
            title = sbn.notification?.extras?.getCharSequence("android.title")?.toString() ?: "",
            text = sbn.notification?.extras?.getCharSequence("android.text")?.toString() ?: "",
            postedAt = sbn.postTime,
            isClearable = sbn.isClearable
        )

        activeNotifications[sbn.key] = notification

        // Trim cache
        while (activeNotifications.size > MAX_CACHED) {
            activeNotifications.remove(activeNotifications.keys.first())
        }

        // Forward to Nick
        sendEvent("notification.posted", notification)

        Log.d(TAG, "Posted: ${notification.packageName} — ${notification.title}")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return

        val notification = activeNotifications.remove(sbn.key) ?: return

        sendEvent("notification.removed", notification)
        Log.d(TAG, "Removed: ${notification.packageName} — ${notification.title}")
    }

    // ── Internal ──

    private fun getAppName(packageName: String): String {
        return try {
            val pm = packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    private fun sendEvent(method: String, notification: Notification) {
        val app = HermesApp.get()
        val ws = app.webSocketManager ?: return

        ws.sendEvent(method, mapOf(
            "key" to notification.key,
            "packageName" to notification.packageName,
            "appName" to notification.appName,
            "title" to notification.title,
            "text" to notification.text,
            "postedAt" to notification.postedAt,
            "isClearable" to notification.isClearable
        ))
    }
}
