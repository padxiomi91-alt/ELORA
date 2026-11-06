package com.elora.app

import android.content.Context
import org.json.JSONObject
import org.webrtc.VideoTrack

/**
 * Coordinates room pairing, owner approval, and WebRTC signaling.
 * Pass a deployed secure WebSocket URL (wss://...) when creating this controller.
 */
class EloraConnectionController(
    context: Context,
    private val serverUrl: String,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onStatus(message: String)
        fun onRoomCode(code: String)
        fun onViewerRequest(viewerId: String)
        fun onRemoteVideo(track: VideoTrack)
    }

    private val appContext = context.applicationContext
    private var ownerMode = false
    private var ownerSecret: String? = null
    private var pendingViewerId: String? = null
    private var webRtc: WebRtcSession? = null
    private var connected = false

    private val signaling = SignalingClient(serverUrl, object : SignalingClient.Listener {
        override fun onConnected() {
            connected = true
            callbacks.onStatus("Connected to ELORA signaling.")
            if (ownerMode) signaling.createRoom()
            else callbacks.onStatus("Joining private room…")
        }

        override fun onDisconnected(reason: String) {
            connected = false
            callbacks.onStatus("Disconnected: $reason")
        }

        override fun onFailure(error: String) {
            callbacks.onStatus(error)
        }

        override fun onMessage(message: JSONObject) {
            when (message.optString("type")) {
                "ROOM_CREATED" -> {
                    ownerSecret = message.optString("ownerSecret")
                    callbacks.onRoomCode(message.optString("code"))
                    callbacks.onStatus("Private room ready. Waiting for viewer.")
                }
                "JOINED" -> callbacks.onStatus("Request sent. Waiting for camera owner approval.")
                "VIEW_REQUEST" -> {
                    pendingViewerId = message.optString("viewerId")
                    callbacks.onViewerRequest(pendingViewerId!!)
                }
                "APPROVED" -> callbacks.onStatus("Access approved. Connecting video…")
                "DECLINED" -> callbacks.onStatus("Camera owner declined the request.")
                "SIGNAL" -> {
                    val payload = message.optJSONObject("payload")
                    if (payload != null) webRtc?.acceptSignal(payload)
                }
                "ROOM_CLOSED" -> callbacks.onStatus("Private room closed.")
                "PEER_OFFLINE" -> callbacks.onStatus("The other phone is offline.")
                "ERROR" -> callbacks.onStatus(message.optString("message", message.optString("code", "Server error")))
            }
        }
    })

    fun createCameraRoom() {
        ownerMode = true
        webRtc?.close()
        webRtc = WebRtcSession(
            appContext,
            true,
            { payload -> signaling.sendSignal(payload, ownerSecret) },
            { status -> callbacks.onStatus(status) },
            { track -> callbacks.onRemoteVideo(track) }
        )
        signaling.connect()
    }

    fun joinAsViewer(code: String) {
        if (!code.matches(Regex("\\d{6}"))) {
            callbacks.onStatus("Enter a valid six-digit room code.")
            return
        }
        ownerMode = false
        webRtc?.close()
        webRtc = WebRtcSession(
            appContext,
            false,
            { payload -> signaling.sendSignal(payload) },
            { status -> callbacks.onStatus(status) },
            { track -> callbacks.onRemoteVideo(track) }
        )
        signaling.connect()
        // JOIN_ROOM is sent after the socket opens by the small deferred flag below.
        joinCode = code
    }

    private var joinCode: String? = null

    fun approvePendingViewer(): Boolean {
        val id = pendingViewerId ?: return false
        val secret = ownerSecret ?: return false
        val approved = signaling.approveViewer(id, secret)
        if (approved) {
            pendingViewerId = null
            webRtc?.createOffer()
        }
        return approved
    }

    fun declinePendingViewer(): Boolean {
        val id = pendingViewerId ?: return false
        val secret = ownerSecret ?: return false
        val declined = signaling.declineViewer(id, secret)
        if (declined) pendingViewerId = null
        return declined
    }

    fun switchCamera() {
        webRtc?.switchCamera()
    }

    fun close() {
        val secret = ownerSecret
        if (ownerMode && secret != null) signaling.closeRoom(secret)
        webRtc?.close()
        webRtc = null
        signaling.disconnect()
        ownerSecret = null
        pendingViewerId = null
        joinCode = null
        connected = false
    }
}
