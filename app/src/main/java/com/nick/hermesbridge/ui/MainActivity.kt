package com.nick.hermesbridge.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.nick.hermesbridge.HermesApp
import com.nick.hermesbridge.R
import com.nick.hermesbridge.model.ConnectionState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Main UI for Hermes Bridge.
 *
 * Allows user to:
 * - Configure WebSocket server address
 * - See connection status
 * - Grant required permissions (Notification Access, Accessibility, Battery)
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HermesUI"
        private const val REQ_NOTIFICATION = 1001
        private const val REQ_LOCATION = 1002
    }

    private lateinit var statusIndicator: ImageView
    private lateinit var statusText: TextView
    private lateinit var serverInput: EditText
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var permissionNotificationButton: Button
    private lateinit var permissionAccessibilityButton: Button
    private lateinit var permissionBatteryButton: Button

    private val app: HermesApp get() = HermesApp.get()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        observeConnectionState()

        Log.i(TAG, "MainActivity created. Device: ${app.deviceId}")
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun initViews() {
        statusIndicator = findViewById(R.id.statusIndicator)
        statusText = findViewById(R.id.statusText)
        serverInput = findViewById(R.id.serverInput)
        connectButton = findViewById(R.id.connectButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        permissionNotificationButton = findViewById(R.id.permissionNotificationButton)
        permissionAccessibilityButton = findViewById(R.id.permissionAccessibilityButton)
        permissionBatteryButton = findViewById(R.id.permissionBatteryButton)

        serverInput.setText(app.getServerUrl())

        connectButton.setOnClickListener {
            val url = serverInput.text.toString().trim()
            if (url.isNotEmpty()) {
                app.connect(url)
                Toast.makeText(this, "Connecting to $url", Toast.LENGTH_SHORT).show()
            }
        }

        disconnectButton.setOnClickListener {
            app.disconnect()
            Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show()
        }

        permissionNotificationButton.setOnClickListener {
            openSettings("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        }

        permissionAccessibilityButton.setOnClickListener {
            openSettings("android.settings.ACCESSIBILITY_SETTINGS")
        }

        permissionBatteryButton.setOnClickListener {
            openSettings("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS")
        }

        // Request runtime permissions
        requestRuntimePermissions()
    }

    private fun observeConnectionState() {
        lifecycleScope.launch {
            app.connectionState.collectLatest { state ->
                updateStatusUI(state)
            }
        }
    }

    private fun updateStatusUI(state: ConnectionState) {
        val (color, text) = when (state) {
            ConnectionState.CONNECTED -> R.color.status_connected to "Connected"
            ConnectionState.CONNECTING -> R.color.status_connecting to "Connecting..."
            ConnectionState.AUTHENTICATING -> R.color.status_connecting to "Authenticating..."
            ConnectionState.ERROR -> R.color.status_error to "Error"
            ConnectionState.DISCONNECTED -> R.color.status_disconnected to "Disconnected"
        }
        statusIndicator.setColorFilter(ContextCompat.getColor(this, color))
        statusText.text = text
    }

    private fun updateUI() {
        val state = app.connectionState.value
        updateStatusUI(state)
        serverInput.setText(app.getServerUrl())
    }

    private fun openSettings(action: String) {
        try {
            startActivity(Intent(action))
        } catch (e: Exception) {
            // Fallback to general settings
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_NOTIFICATION
                )
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_LOCATION
            )
        }
    }
}
