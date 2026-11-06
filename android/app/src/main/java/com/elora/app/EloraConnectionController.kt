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
        fun onLocalVideo(track: VideoTrack)
        fun onRemoteVideo(track: VideoTrack)
    }

    private val appContext = context.applicationContext
    private val secureStore = EloraSecureStore(appContext)
    private var joinedOwnerId: String? = null
    private var ownerMode = false
    private var ownerSecret: String? = null
    private var pendingViewerId: String? = null
    private var webRtc: WebRtcSession? = null
    private var connected = false

    private lateinit var signaling: SignalingClient

    init {
        signaling = SignalingClient(serverUrl, object : SignalingClient.Listener {
        override fun onConnected() {
            connected = true
            callbacks.onStatus("Connected to ELORA signaling.")
            if (ownerMode) signaling.createRoom(secureStore.ownerId())
            else {
                val code = joinCode
                if (code != null) signaling.joinRoom(code, secureStore.deviceId())
                else callbacks.onStatus("Enter a room code to join.")
            }
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
                "JOINED" -> {
                    joinedOwnerId = message.optString("ownerId").takeIf { it.isNotBlank() }
                    val credential = joinedOwnerId?.let { secureStore.trustCredential(it) }
                    signaling.authenticateTrustedDevice(credential)
                    callbacks.onStatus(if (credential == null) "First-time pairing. Waiting for camera owner approval." else "Checking this trusted device…")
                }
                "TRUST_CREDENTIAL" -> {
                    val ownerId = message.optString("ownerId")
                    val credential = message.optString("credential")
                    if (ownerId.isNotBlank() && credential.isNotBlank()) {
                        secureStore.saveTrustCredential(ownerId, credential)
                        callbacks.onStatus("This phone is now trusted. Future connections won't need approval.")
                    }
                }
                "VIEW_REQUEST" -> {
                    pendingViewerId = message.optString("viewerId")
                    callbacks.onViewerRequest(pendingViewerId!!)
                }
                "APPROVED" -> callbacks.onStatus(
                    if (message.optBoolean("trusted")) "Trusted device verified. Connecting video…"
                    else "Access approved. Connecting video…"
                )
                "VIEWER_AUTO_APPROVED" -> {
                    callbacks.onStatus("A previously trusted phone reconnected. Starting video…")
                    webRtc?.createOffer()
                }
                "DECLINED" -> callbacks.onStatus("Camera owner declined the request.")
                "SIGNAL" -> {
                    val payload = message.optJSONObject("payload")
                    if (payload != null) {
                        try {
                            webRtc?.acceptSignal(payload)
                        } catch (error: Throwable) {
                            callbacks.onStatus("Video negotiation failed: ${error.javaClass.simpleName}: ${error.localizedMessage ?: "unknown error"}")
                            android.util.Log.e("ELORA", "Incoming WebRTC signal failed", error)
                        }
                    }
                }
                "DEVICE_REVOKED" -> callbacks.onStatus("Trusted device revoked. It must be approved again.")
                "ROOM_CLOSED" -> callbacks.onStatus("Private room closed.")
                "PEER_OFFLINE" -> callbacks.onStatus("The other phone is offline.")
                "ERROR" -> callbacks.onStatus(message.optString("message", message.optString("code", "Server error")))
            }
        }
        })
    }

    fun createCameraRoom() {
        ownerMode = true
        webRtc?.close()
        webRtc = WebRtcSession(
            appContext,
            true,
            { payload -> signaling.sendSignal(payload, ownerSecret) },
            { status -> callbacks.onStatus(status) },
            { track -> callbacks.onLocalVideo(track) },
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
            { _ -> },
            { track -> callbacks.onRemoteVideo(track) }
        )
        joinCode = code
        signaling.connect()
    }

    private var joinCode: String? = null

    fun approvePendingViewer(): Boolean {
        val id = pendingViewerId ?: return false
        val secret = ownerSecret ?: return false
        val approved = signaling.approveViewer(id, secret)
        if (approved) {
            pendingViewerId = null
            // Allow the server to finish applying approval before the SDP offer is sent.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    webRtc?.createOffer()
                } catch (error: Throwable) {
                    callbacks.onStatus("Video offer failed: ${error.javaClass.simpleName}: ${error.localizedMessage ?: "unknown error"}")
                    android.util.Log.e("ELORA", "Creating approved viewer offer failed", error)
                }
            }, 250)
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

    fun revokeTrustedDevice(deviceId: String): Boolean {
        val secret = ownerSecret ?: return false
        return signaling.revokeDevice(deviceId, secret)
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
        joinedOwnerId = null
        connected = false
    }
}
