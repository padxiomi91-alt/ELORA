package com.elora.app

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture

/**
 * Small WebSocket transport for the ELORA signaling protocol.
 * This exchanges connection setup messages only; video is not sent through this socket.
 */
class SignalingClient(
    private val serverUrl: String,
    private val listener: Listener
) {
    interface Listener {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onMessage(message: JSONObject)
        fun onFailure(error: String)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null
    private val heartbeatExecutor = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var heartbeat: ScheduledFuture<*>? = null

    private fun startHeartbeat() {
        heartbeat?.cancel(false)
        heartbeat = heartbeatExecutor.scheduleAtFixedRate(
            { send("PING") }, 60, 60, TimeUnit.SECONDS
        )
    }

    private fun stopHeartbeat() {
        heartbeat?.cancel(false)
        heartbeat = null
    }

    fun connect() {
        if (!serverUrl.startsWith("wss://") && !serverUrl.startsWith("ws://")) {
            listener.onFailure("Signaling URL must start with wss://")
            return
        }
        val request = Request.Builder().url(serverUrl).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                startHeartbeat()
                listener.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    listener.onMessage(JSONObject(text))
                } catch (_: Exception) {
                    listener.onFailure("Received an invalid server message.")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                stopHeartbeat()
                listener.onDisconnected(reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                stopHeartbeat()
                listener.onFailure(t.localizedMessage ?: "Signaling connection failed.")
            }
        })
    }

    fun createRoom(ownerId: String): Boolean = send("CREATE_ROOM", "ownerId" to ownerId)

    fun joinRoom(code: String, deviceId: String): Boolean {
        if (!code.matches(Regex("\\d{6}"))) return false
        return send("JOIN_ROOM", "code" to code, "deviceId" to deviceId)
    }

    fun authenticateTrustedDevice(credential: String?): Boolean =
        send("AUTH_TRUST", "trustCredential" to (credential ?: ""))

    fun approveViewer(viewerId: String, ownerSecret: String): Boolean =
        send("APPROVE_VIEWER", "viewerId" to viewerId, "ownerSecret" to ownerSecret)

    fun declineViewer(viewerId: String, ownerSecret: String): Boolean =
        send("DECLINE_VIEWER", "viewerId" to viewerId, "ownerSecret" to ownerSecret)

    fun sendSignal(payload: JSONObject, ownerSecret: String? = null): Boolean {
        val fields = mutableListOf<Pair<String, Any>>("payload" to payload)
        if (ownerSecret != null) fields += "ownerSecret" to ownerSecret
        return send("SIGNAL", *fields.toTypedArray())
    }

    fun closeRoom(ownerSecret: String): Boolean =
        send("CLOSE_ROOM", "ownerSecret" to ownerSecret)

    fun revokeDevice(deviceId: String, ownerSecret: String): Boolean =
        send("REVOKE_DEVICE", "deviceId" to deviceId, "ownerSecret" to ownerSecret)

    private fun send(type: String, vararg fields: Pair<String, Any>): Boolean {
        val message = JSONObject().put("type", type)
        fields.forEach { (key, value) -> message.put(key, value) }
        return socket?.send(message.toString()) ?: false
    }

    fun disconnect() {
        stopHeartbeat()
        socket?.close(1000, "ELORA disconnected")
        socket = null
        heartbeatExecutor.shutdownNow()
    }
}
