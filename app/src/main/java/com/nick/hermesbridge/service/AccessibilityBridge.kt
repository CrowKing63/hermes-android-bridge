package com.nick.hermesbridge.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.nick.hermesbridge.model.ScreenNode
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Accessibility service for screen interaction via Nick.
 *
 * Capabilities when enabled:
 * - Read screen content (text from UI tree)
 * - Tap at coordinates
 * - Swipe between coordinates
 * - Type text into focused input
 */
class AccessibilityBridge : AccessibilityService() {

    companion object {
        private const val TAG = "HermesA11y"
        private const val TAP_DURATION_MS = 50L
        private const val SWIPE_DURATION_MS = 300L

        @Volatile
        var instance: AccessibilityBridge? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
        Log.i(TAG, "Accessibility service destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used — we only act on demand, not on events
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    // ── Screen Content ──

    /**
     * Get the root accessibility node of the current window.
     */
    fun getRootNode(): AccessibilityNodeInfo? {
        return rootInActiveWindow
    }

    /**
     * Serialize the current UI tree to a JSON-compatible structure.
     */
    fun getScreenContent(): List<Map<String, Any?>> {
        val root = rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<Map<String, Any?>>()
        try {
            serializeNode(root, result)
        } catch (e: Exception) {
            Log.e(TAG, "Error serializing screen content: ${e.message}")
        } finally {
            root.recycle()
        }
        return result
    }

    private fun serializeNode(node: AccessibilityNodeInfo, out: MutableList<Map<String, Any?>>, depth: Int = 0) {
        if (depth > 50) return // safety limit

        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)

        out.add(mapOf(
            "className" to (node.className?.toString() ?: ""),
            "text" to node.text?.toString(),
            "contentDescription" to node.contentDescription?.toString(),
            "bounds" to "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}",
            "clickable" to node.isClickable,
            "focusable" to node.isFocusable
        ))

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                serializeNode(child, out, depth + 1)
                child.recycle()
            }
        }
    }

    // ── Touch Actions ──

    /**
     * Tap at screen coordinates (x, y).
     */
    fun tap(x: Int, y: Int, callback: ((Boolean) -> Unit)? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "Gestures require API 24+")
            callback?.invoke(false)
            return
        }

        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
            .build()

        val result = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback?.invoke(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                callback?.invoke(false)
            }
        }, null)

        Log.d(TAG, "Tap at ($x,$y) result=$result")
    }

    /**
     * Swipe from (x1,y1) to (x2,y2) with given duration in ms.
     */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = SWIPE_DURATION_MS, callback: ((Boolean) -> Unit)? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            callback?.invoke(false)
            return
        }

        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback?.invoke(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                callback?.invoke(false)
            }
        }, null)

        Log.d(TAG, "Swipe ($x1,$y1) → ($x2,$y2) duration=${durationMs}ms")
    }

    /**
     * Type text into the currently focused input field.
     */
    fun typeText(text: String, callback: ((Boolean) -> Unit)? = null) {
        val root = rootInActiveWindow
        val focusedNode = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        if (focusedNode == null) {
            Log.w(TAG, "No focused input node found")
            callback?.invoke(false)
            root?.recycle()
            return
        }

        val arguments = android.os.Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }

        val success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        Log.d(TAG, "Type text: success=$success")
        focusedNode.recycle()
        root?.recycle()
        callback?.invoke(success)
    }

    /**
     * Perform global action (BACK, HOME, RECENTS, etc.)
     */
    fun performGlobalAction(action: Int): Boolean {
        val result = performGlobalAction(action)
        Log.d(TAG, "Global action $action result=$result")
        return result
    }

    /**
     * Find and tap a node by visible text.
     */
    fun tapByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNodeWithText(root, text)
        if (node != null) {
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            val cx = bounds.centerX()
            val cy = bounds.centerY()
            node.recycle()
            root.recycle()
            tap(cx, cy)
            return true
        }
        root.recycle()
        return false
    }

    private fun findNodeWithText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text?.toString()?.contains(text, ignoreCase = true) == true) {
            return node
        }
        if (node.contentDescription?.toString()?.contains(text, ignoreCase = true) == true) {
            return node
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                val result = findNodeWithText(child, text)
                if (result != null) return result
                child.recycle()
            }
        }
        return null
    }
}
