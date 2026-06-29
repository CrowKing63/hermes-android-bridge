package com.nick.hermesbridge.ws

import android.util.Log
import com.nick.hermesbridge.handler.DeviceHandler
import com.nick.hermesbridge.handler.AppHandler
import com.nick.hermesbridge.model.ConnectionState
import com.nick.hermesbridge.model.DeviceInfo
import com.nick.hermesbridge.model.JsonRpcError
import com.nick.hermesbridge.model.JsonRpcEvent
import com.nick.hermesbridge.model.JsonRpcRequest
import com.nick.hermesbridge.model.JsonRpcResponse
import com.nick.hermesbridge.model.RpcErrorCodes
import com.nick.hermesbridge.service.AccessibilityBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Manages WebSocket connection to Nick's bridge server.
 *
 * Features:
 * - Auto-reconnect with exponential backoff (1s → 2s → 4s → ... → 30s max)
 * - Heartbeat via ping frames (OkHttp built-in, every 30s)
 * - JSON-RPC 2.0 message framing
 * - Bidirectional: send commands + receive events
 */
class WebSocketManager(
    private val serverUrl: String,
    private val deviceInfo: DeviceInfo,
    private val scope: CoroutineScope,
    private val onStateChange: (ConnectionState) -> Unit = {}
) {
    companion object {
        private const val TAG = "HermesWS"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val INITIAL_RETRY_DELAY_MS = 1_000L
        private const val MAX_RETRY_DELAY_MS = 30_000L
        private const val REQUEST_TIMEOUT_MS = 30_000L
    }

    // JSON config: lenient parsing, ignore unknown keys
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var retryDelay = INITIAL_RETRY_DELAY_MS
    private var reconnectJob: Job? = null
    private val messageId = AtomicInteger(1)
    @Volatile
    private var isDestroyed = false

    // Pending requests waiting for responses
    private val pendingRequests = mutableMapOf<Int, ((JsonRpcResponse) -> Unit)>()

    // JsonRpcHandler — created lazily when context is available
    private var handler: JsonRpcHandler? = null

    /**
     * Connect to the WebSocket server.
     */
    fun connect() {
        if (isDestroyed) return
        Log.i(TAG, "Connecting to $serverUrl")
        onStateChange(ConnectionState.CONNECTING)

        val request = Request.Builder()
            .url(serverUrl)
            .header("X-Device-ID", deviceInfo.deviceId)
            .header("X-Device-Model", deviceInfo.model)
            .build()

        webSocket = client.newWebSocket(request, createListener())
    }

    /**
     * Disconnect and cleanup all resources.
     */
    fun disconnect() {
        isDestroyed = true
        reconnectJob?.cancel()
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        pendingRequests.clear()
        onStateChange(ConnectionState.DISCONNECTED)
        Log.i(TAG, "Disconnected")
    }

    /**
     * Send a JSON-RPC event (no response expected).
     */
    fun sendEvent(method: String, params: Map<String, Any?>) {
        val paramsJson = mapToJsonObject(params)
        val event = JsonRpcEvent(method = method, params = paramsJson)
        val message = json.encodeToString(JsonRpcEvent.serializer(), event)
        sendRaw(message)
    }

    /**
     * Send a JSON-RPC request and await response.
     */
    suspend fun call(method: String, params: Map<String, Any?>? = null): JsonRpcResponse {
        val id = messageId.getAndIncrement()
        val paramsJson = params?.let { mapToJsonObject(it) }
        val request = JsonRpcRequest(id = id, method = method, params = paramsJson)
        val message = json.encodeToString(JsonRpcRequest.serializer(), request)

        if (!sendRaw(message)) {
            return JsonRpcResponse(
                id = id,
                error = JsonRpcError(code = -32000, message = "Not connected")
            )
        }

        return suspendCancellableCoroutine { cont ->
            pendingRequests[id] = { response ->
                cont.resume(response)
            }
            scope.launch {
                delay(REQUEST_TIMEOUT_MS)
                if (pendingRequests.remove(id) != null) {
                    cont.resume(
                        JsonRpcResponse(
                            id = id,
                            error = JsonRpcError(code = -32000, message = "Request timeout")
                        )
                    )
                }
            }
        }
    }

    // ── Internal ──

    @Synchronized
    private fun sendRaw(message: String): Boolean {
        Log.d(TAG, ">> $message")
        return webSocket?.send(message) ?: false
    }

    private fun mapToJsonObject(map: Map<String, Any?>): JsonObject {
        return buildJsonObject {
            map.forEach { (key, value) ->
                when (value) {
                    null -> return@forEach
                    is String -> put(key, JsonPrimitive(value))
                    is Number -> put(key, JsonPrimitive(value))
                    is Boolean -> put(key, JsonPrimitive(value))
                    is List<*> -> put(key, json.encodeToString(
                        kotlinx.serialization.json.JsonArray.serializer(),
                        kotlinx.serialization.json.JsonArray(value.map { JsonPrimitive(it.toString()) })
                    ).let { json.parseToJsonElement(it) as kotlinx.serialization.json.JsonArray }
                    else -> put(key, JsonPrimitive(value.toString()))
                }
            }
        }
    }

    // ── WebSocket Listener ──

    private fun createListener(): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket opened")
                retryDelay = INITIAL_RETRY_DELAY_MS
                onStateChange(ConnectionState.AUTHENTICATING)

                // Send device registration
                sendEvent("device.register", mapOf(
                    "deviceId" to deviceInfo.deviceId,
                    "model" to deviceInfo.model,
                    "manufacturer" to deviceInfo.manufacturer,
                    "androidVersion" to deviceInfo.androidVersion,
                    "sdkVersion" to deviceInfo.sdkVersion,
                    "capabilities" to deviceInfo.capabilities
                ))
                onStateChange(ConnectionState.CONNECTED)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleInbound(text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                handleInbound(bytes.utf8())
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code — $reason")
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code — $reason")
                onStateChange(ConnectionState.DISCONNECTED)
                scheduleReconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message ?: "unknown"}")
                onStateChange(ConnectionState.ERROR)
                scheduleReconnect()
            }
        }
    }

    // ── Message Dispatch ──

    private fun handleInbound(text: String) {
        Log.d(TAG, "<< $text")
        try {
            val element = json.parseToJsonElement(text)
            val obj = element.jsonObject

            when {
                // Response: has "id" and ("result" or "error")
                obj.containsKey("id") && (obj.containsKey("result") || obj.containsKey("error")) -> {
                    val response = json.decodeFromString(JsonRpcResponse.serializer(), text)
                    response.id?.let { id ->
                        pendingRequests.remove(id)?.invoke(response)
                    }
                }
                // Request from server: has "id" and "method"
                obj.containsKey("id") && obj.containsKey("method") -> {
                    val request = json.decodeFromString(JsonRpcRequest.serializer(), text)
                    handleRequest(request)
                }
                // Server event: "method" without "id"
                else -> {
                    Log.d(TAG, "Server event: ${obj["method"]?.jsonPrimitive?.content ?: "?"}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
        }
    }

    private fun handleRequest(request: JsonRpcRequest) {
        // Build handler on first use
        if (handler == null) {
            val app = com.nick.hermesbridge.HermesApp.get()
            handler = JsonRpcHandler(app)
        }

        val h = handler ?: run {
            sendErrorResponse(request.id, RpcErrorCodes.INTERNAL_ERROR, "Handler not initialized")
            return
        }

        val response = h.handle(request)
        sendRaw(json.encodeToString(JsonRpcResponse.serializer(), response))
    }

    private fun sendErrorResponse(id: Int?, code: Int, message: String) {
        val resp = JsonRpcResponse(
            id = id,
            error = JsonRpcError(code = code, message = message)
        )
        sendRaw(json.encodeToString(JsonRpcResponse.serializer(), resp))
    }

    // ── Reconnect Logic ──

    private fun scheduleReconnect() {
        if (isDestroyed) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            Log.i(TAG, "Reconnecting in ${retryDelay}ms...")
            delay(retryDelay)
            retryDelay = (retryDelay * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
            if (!isDestroyed) connect()
        }
    }
}
