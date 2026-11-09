package com.elora.app

import android.content.Context
import io.livekit.android.renderer.SurfaceViewRenderer
import io.livekit.android.room.track.VideoTrack
import org.json.JSONObject

/**
 * Coordinates private pairing and owner approval. LiveKit carries video only after
 * the signaling service issues a role-limited, short-lived media token.
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
        fun onInitializeVideoRenderer(initializer: (SurfaceViewRenderer) -> Unit)
    }

    private val appContext = context.applicationContext
    private val secureStore = EloraSecureStore(appContext)
    private var ownerMode = false
    private var ownerSecret: String? = null
    private var pendingViewerId: String? = null
    private var liveKit: LiveKitSession? = null
    private var joinCode: String? = null
    private var roomCode: String? = null

    private lateinit var signaling: SignalingClient

    init {
        signaling = SignalingClient(serverUrl, object : SignalingClient.Listener {
            override fun onConnected() {
                callbacks.onStatus("Connected to ELORA private pairing.")
                if (ownerMode) signaling.createRoom(secureStore.ownerId())
                else {
                    val code = joinCode
                    if (code != null) signaling.joinRoom(code, secureStore.deviceId())
                    else callbacks.onStatus("Enter a room code to join.")
                }
            }

            override fun onDisconnected(reason: String) {
                EloraDiagnostics.record("Signaling disconnected: $reason")
                callbacks.onStatus("Disconnected: $reason")
            }

            override fun onFailure(error: String) {
                EloraDiagnostics.record("Signaling failure: $error")
                callbacks.onStatus(error)
            }

            override fun onMessage(message: JSONObject) {
                val type = message.optString("type")
                EloraDiagnostics.record("Signaling message: $type" +
                    if (type == "ERROR") " code=${message.optString("code")} message=${message.optString("message")}" else "")
                when (type) {
                    "ROOM_CREATED" -> {
                        ownerSecret = message.optString("ownerSecret")
                        roomCode = message.optString("code")
                        callbacks.onRoomCode(message.optString("code"))
                        callbacks.onStatus("Private room ready. Waiting for viewer.")
                    }
                    "JOINED" -> {
                        val ownerId = message.optString("ownerId").takeIf { it.isNotBlank() }
                        val credential = ownerId?.let { secureStore.trustCredential(it) }
                        signaling.authenticateTrustedDevice(credential)
                        callbacks.onStatus(if (credential == null)
                            "First-time pairing. Waiting for camera owner approval."
                        else "Checking this trusted device…")
                    }
                    "TRUST_CREDENTIAL" -> {
                        val ownerId = message.optString("ownerId")
                        val credential = message.optString("credential")
                        if (ownerId.isNotBlank() && credential.isNotBlank()) {
                            secureStore.saveTrustCredential(ownerId, credential)
                            callbacks.onStatus("This phone is now trusted for future sessions.")
                        }
                    }
                    "VIEW_REQUEST" -> {
                        pendingViewerId = message.optString("viewerId")
                        callbacks.onViewerRequest(pendingViewerId!!)
                    }
                    "APPROVED" -> {
                        callbacks.onStatus(if (message.optBoolean("trusted"))
                            "Trusted device verified. Connecting secure video…"
                        else "Access approved. Connecting secure video…")
                        connectLiveKit(message, publish = false)
                    }
                    "APPROVAL_CONFIRMED" -> {
                        callbacks.onStatus("Owner approval confirmed. Starting secure camera…")
                        connectLiveKit(message, publish = true)
                    }
                    "VIEWER_AUTO_APPROVED" -> {
                        callbacks.onStatus("Trusted viewer verified. Starting secure camera…")
                        connectLiveKit(message, publish = true)
                    }
                    "DECLINED" -> callbacks.onStatus("Camera owner declined the request.")
                    "DEVICE_REVOKED" -> callbacks.onStatus("Trusted device revoked. It must be approved again.")
                    "ROOM_CLOSED" -> {
                        liveKit?.close()
                        liveKit = null
                        callbacks.onStatus("Private room closed.")
                    }
                    "PEER_OFFLINE" -> callbacks.onStatus("The other phone is offline.")
                    "ERROR" -> callbacks.onStatus(message.optString("message", message.optString("code", "Server error")))
                }
            }
        })
    }

    private fun connectLiveKit(message: JSONObject, publish: Boolean) {
        val url = message.optString("livekitUrl")
        val token = message.optString("livekitToken")
        if (url.isBlank() || token.isBlank()) {
            callbacks.onStatus("Secure video service is not configured on the ELORA server yet.")
            EloraDiagnostics.record("LiveKit token missing from approved signaling response")
            return
        }
        if (publish != ownerMode) {
            callbacks.onStatus("ELORA role check failed. Reopen the private room.")
            return
        }
        liveKit?.close()
        val session = LiveKitSession(
            appContext,
            publish,
            { status -> callbacks.onStatus(status) },
            { track -> callbacks.onLocalVideo(track) },
            { track -> callbacks.onRemoteVideo(track) },
            { initializer -> callbacks.onInitializeVideoRenderer(initializer) }
        )
        liveKit = session
        session.connect(url, token)
    }

    fun createCameraRoom() {
        EloraDiagnostics.record("Creating camera publisher session")
        ownerMode = true
        liveKit?.close()
        liveKit = null
        signaling.connect()
    }

    fun joinAsViewer(code: String) {
        if (!code.matches(Regex("\\d{6}"))) {
            callbacks.onStatus("Enter a valid six-digit room code.")
            return
        }
        ownerMode = false
        liveKit?.close()
        liveKit = null
        joinCode = code
        signaling.connect()
    }

    fun approvePendingViewer(): Boolean {
        EloraDiagnostics.record("Approve viewer action requested")
        val id = pendingViewerId ?: return false
        val secret = ownerSecret ?: return false
        val approved = signaling.approveViewer(id, secret)
        if (approved) pendingViewerId = null
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
        liveKit?.switchCamera()
    }

    fun close() {
        EloraDiagnostics.record("Connection controller closing; ownerMode=$ownerMode")
        val secret = ownerSecret
        if (ownerMode && secret != null) signaling.closeRoom(secret)
        liveKit?.close()
        liveKit = null
        signaling.disconnect()
        ownerSecret = null
        pendingViewerId = null
        joinCode = null
        roomCode = null
    }
}
