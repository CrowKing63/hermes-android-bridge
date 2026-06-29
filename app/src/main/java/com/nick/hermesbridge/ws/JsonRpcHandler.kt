package com.nick.hermesbridge.ws

import android.content.pm.PackageManager
import android.util.Log
import com.nick.hermesbridge.HermesApp
import com.nick.hermesbridge.handler.DeviceHandler
import com.nick.hermesbridge.handler.AppHandler
import com.nick.hermesbridge.model.JsonRpcError
import com.nick.hermesbridge.model.JsonRpcRequest
import com.nick.hermesbridge.model.JsonRpcResponse
import com.nick.hermesbridge.model.RpcErrorCodes
import com.nick.hermesbridge.service.AccessibilityBridge
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import kotlinx.serialization.json.double

/**
 * Dispatches incoming JSON-RPC requests to the appropriate handler.
 *
 * Routes methods by namespace:
 * - notification.*  →  NotificationService (cached notifications)
 * - device.*        →  DeviceHandler (battery, storage, network, system, location)
 * - screen.*        →  AccessibilityBridge (content, tap, swipe, type)
 * - app.*           →  AppHandler (list, launch, current)
 * - media.*         →  MediaHandler (volume, tts)
 * - camera.*        →  CameraHandler (snap)
 * - sensor.*        →  DeviceHandler (location, compass)
 */
class JsonRpcHandler(private val app: HermesApp) {

    companion object {
        private const val TAG = "HermesRPC"
    }

    private val deviceHandler = DeviceHandler(app)
    private val appHandler = AppHandler(app)

    /**
     * Handle a JSON-RPC request, return JSON-RPC response.
     */
    fun handle(request: JsonRpcRequest): JsonRpcResponse {
        return try {
            val result = dispatch(request.method, request.params)
            JsonRpcResponse(id = request.id, result = result)
        } catch (e: UnknownMethodException) {
            JsonRpcResponse(
                id = request.id,
                error = JsonRpcError(
                    code = RpcErrorCodes.METHOD_NOT_FOUND,
                    message = "Unknown method: ${request.method}"
                )
            )
        } catch (e: InvalidParamsException) {
            JsonRpcResponse(
                id = request.id,
                error = JsonRpcError(
                    code = RpcErrorCodes.INVALID_PARAMS,
                    message = e.message ?: "Invalid parameters"
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error handling ${request.method}", e)
            JsonRpcResponse(
                id = request.id,
                error = JsonRpcError(
                    code = RpcErrorCodes.INTERNAL_ERROR,
                    message = e.message ?: "Internal error"
                )
            )
        }
    }

    private fun dispatch(method: String, params: JsonObject?): JsonObject {
        return when (method) {
            // ── Notifications ──
            "notification.list" -> {
                val limit = params?.get("limit")?.jsonPrimitive?.int ?: 10
                val since = params?.get("since")?.jsonPrimitive?.long ?: 0L
                val pkg = params?.get("package")?.jsonPrimitive?.content
                // Phase 2: get from NotificationService cache
                buildJsonObject {
                    put("notifications", buildJsonObject {
                        put("cached", 0)
                        put("limit", limit)
                        put("supported", true)
                    })
                }
            }

            "notification.subscribe" -> {
                val packages = params?.get("packages")?.let { array ->
                    (array as? kotlinx.serialization.json.JsonArray)?.map { it.jsonPrimitive.content }
                } ?: emptyList()
                buildJsonObject {
                    put("subscribed", packages)
                    put("status", "ok")
                }
            }

            "notification.clear" -> {
                buildJsonObject { put("cleared", true) }
            }

            // ── Device ──
            "device.battery" -> deviceHandler.getBatteryStatus()
            "device.storage" -> deviceHandler.getStorageStatus()
            "device.network" -> deviceHandler.getNetworkStatus()
            "device.system" -> deviceHandler.getSystemStatus()

            // ── Screen (Accessibility) ──
            "screen.read" -> {
                val a11y = AccessibilityBridge.instance
                if (a11y == null) {
                    buildJsonObject { put("error", "Accessibility service not connected") }
                } else {
                    val nodes = a11y.getScreenContent()
                    buildJsonObject {
                        put("nodeCount", nodes.size)
                        // Simplified — full implementation in Phase 2
                    }
                }
            }

            "screen.tap" -> {
                val x = params?.get("x")?.jsonPrimitive?.int ?: throw InvalidParamsException("Missing x")
                val y = params?.get("y")?.jsonPrimitive?.int ?: throw InvalidParamsException("Missing y")
                val a11y = AccessibilityBridge.instance
                if (a11y == null) {
                    buildJsonObject { put("error", "Accessibility service not connected") }
                } else {
                    a11y.tap(x, y)
                    buildJsonObject { put("tapped", true); put("x", x); put("y", y) }
                }
            }

            "screen.swipe" -> {
                val x1 = params?.get("x1")?.jsonPrimitive?.int ?: throw InvalidParamsException("Missing x1")
                val y1 = params?.get("y1")?.jsonPrimitive?.int ?: throw InvalidParamsException("Missing y1")
                val x2 = params?.get("x2")?.jsonPrimitive?.int ?: throw InvalidParamsException("Missing x2")
                val y2 = params?.get("y2")?.jsonPrimitive?.int ?: throw InvalidParamsException("Missing y2")
                val a11y = AccessibilityBridge.instance
                if (a11y == null) {
                    buildJsonObject { put("error", "Accessibility service not connected") }
                } else {
                    a11y.swipe(x1, y1, x2, y2)
                    buildJsonObject { put("swiped", true) }
                }
            }

            "screen.type" -> {
                val text = params?.get("text")?.jsonPrimitive?.content ?: throw InvalidParamsException("Missing text")
                val a11y = AccessibilityBridge.instance
                if (a11y == null) {
                    buildJsonObject { put("error", "Accessibility service not connected") }
                } else {
                    a11y.typeText(text)
                    buildJsonObject { put("typed", true); put("length", text.length) }
                }
            }

            "screen.screenshot" -> {
                buildJsonObject {
                    put("status", "pending")
                    put("message", "Screen capture requires MediaProjection permission. Use ScreenCaptureActivity.")
                    put("supported", true)
                }
            }

            // ── Apps ──
            "app.list" -> {
                val includeSystem = params?.get("system")?.jsonPrimitive?.boolean ?: false
                appHandler.getAppList(includeSystem)
            }

            "app.launch" -> {
                val pkg = params?.get("package")?.jsonPrimitive?.content
                    ?: throw InvalidParamsException("Missing package")
                appHandler.launch(pkg)
            }

            "app.current" -> appHandler.getCurrentApp()

            // ── Location ──
            "sensor.location" -> deviceHandler.getLocation()

            // ── Ping ──
            "device.ping" -> buildJsonObject { put("pong", true) }

            // ── Device registration confirmation from server ──
            "device.register" -> buildJsonObject { put("status", "ok") }

            // ── Media (Phase 3) ──
            "media.volume",
            "media.tts",
            "camera.snap",
            "sensor.compass" -> {
                buildJsonObject {
                    put("status", "not_implemented")
                    put("message", "$method will be implemented in Phase 3")
                }
            }

            else -> throw UnknownMethodException(method)
        }
    }

    class UnknownMethodException(val method: String) : Exception(method)
    class InvalidParamsException(message: String) : Exception(message)
}
