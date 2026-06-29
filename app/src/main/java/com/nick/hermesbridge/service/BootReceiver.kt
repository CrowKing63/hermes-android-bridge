package com.nick.hermesbridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nick.hermesbridge.HermesApp

/**
 * Auto-start Hermes Bridge after device boot.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "HermesBoot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {

            Log.i(TAG, "Boot completed, starting HermesBridge connection...")

            val app = context.applicationContext as? HermesApp ?: return

            // Auto-connect if server URL is configured
            val serverUrl = app.getServerUrl()
            if (serverUrl.isNotEmpty()) {
                Log.i(TAG, "Auto-connecting to $serverUrl")
                app.connect(serverUrl)
            }
        }
    }
}
