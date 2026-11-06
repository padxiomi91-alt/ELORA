package com.elora.app

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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

    fun connect() {
        if (!serverUrl.startsWith("wss://") && !serverUrl.startsWith("ws://")) {
            listener.onFailure("Signaling URL must start with wss://")
            return
        }
        val request = Request.Builder().url(serverUrl).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
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
                listener.onDisconnected(reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onFailure(t.localizedMessage ?: "Signaling connection failed.")
            }
        })
    }

    fun createRoom(): Boolean = send("CREATE_ROOM")

    fun joinRoom(code: String): Boolean {
        if (!code.matches(Regex("\\d{6}"))) return false
        return send("JOIN_ROOM", "code" to code)
    }

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

    private fun send(type: String, vararg fields: Pair<String, Any>): Boolean {
        val message = JSONObject().put("type", type)
        fields.forEach { (key, value) -> message.put(key, value) }
        return socket?.send(message.toString()) ?: false
    }

    fun disconnect() {
        socket?.close(1000, "ELORA disconnected")
        socket = null
    }
}
