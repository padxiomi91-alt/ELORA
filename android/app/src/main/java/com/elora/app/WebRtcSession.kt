package com.elora.app

import android.content.Context
import org.json.JSONObject
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * First WebRTC media layer for ELORA.
 * Signaling is supplied by the caller; this class never contacts a server itself.
 */
class WebRtcSession(
    context: Context,
    private val onSignal: (JSONObject) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onRemoteVideo: (VideoTrack) -> Unit
) {
    private val appContext = context.applicationContext
    private val egl = EglBase.create()
    private val factory: PeerConnectionFactory
    private val peer: PeerConnection
    private val videoSource: VideoSource
    private val capturer: CameraVideoCapturer
    private val textureHelper: SurfaceTextureHelper
    private val localTrack: VideoTrack
    private var closed = false

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()
        )
        val configuration = PeerConnection.RTCConfiguration(iceServers)
        peer = factory.createPeerConnection(configuration, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                onStatus("Connection: " + state.name)
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(candidate: IceCandidate) {
                onSignal(JSONObject()
                    .put("kind", "candidate")
                    .put("sdpMid", candidate.sdpMid)
                    .put("sdpMLineIndex", candidate.sdpMLineIndex)
                    .put("candidate", candidate.sdp))
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: org.webrtc.MediaStream) {}
            override fun onRemoveStream(stream: org.webrtc.MediaStream) {}
            override fun onDataChannel(channel: org.webrtc.DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: org.webrtc.RtpReceiver, streams: Array<out org.webrtc.MediaStream>) {
                val track = receiver.track()
                if (track is VideoTrack) onRemoteVideo(track)
            }
            override fun onTrack(transceiver: org.webrtc.RtpTransceiver) {
                val track = transceiver.receiver.track()
                if (track is VideoTrack) onRemoteVideo(track)
            }
        }) ?: error("Could not create WebRTC peer connection")

        val enumerator = Camera2Enumerator(appContext)
        val cameraName = enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: error("No camera was found")
        capturer = enumerator.createCapturer(cameraName, null)
            ?: error("Could not open the camera")
        textureHelper = SurfaceTextureHelper.create("ELORA-Capture", egl.eglBaseContext)
            ?: error("Could not create camera texture")
        videoSource = factory.createVideoSource(false)
        capturer.initialize(textureHelper, appContext, videoSource.capturerObserver)
        capturer.startCapture(1280, 720, 24)
        localTrack = factory.createVideoTrack("ELORA_VIDEO", videoSource)
        peer.addTrack(localTrack, listOf("ELORA_STREAM"))
        onStatus("Camera media engine ready.")
    }

    fun createOffer() {
        peer.createOffer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) {
                peer.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        onSignal(JSONObject().put("kind", "offer").put("sdp", description.description))
                    }
                    override fun onSetFailure(error: String) { onStatus("Offer setup failed: $error") }
                    override fun onCreateSuccess(description: SessionDescription) {}
                    override fun onCreateFailure(error: String) {}
                }, description)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String) { onStatus("Offer failed: $error") }
            override fun onSetFailure(error: String) {}
        }, MediaConstraints())
    }

    fun acceptSignal(message: JSONObject) {
        when (message.optString("kind")) {
            "offer" -> {
                val description = SessionDescription(SessionDescription.Type.OFFER, message.getString("sdp"))
                peer.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        peer.createAnswer(object : SdpObserver {
                            override fun onCreateSuccess(answer: SessionDescription) {
                                peer.setLocalDescription(object : SdpObserver {
                                    override fun onSetSuccess() {
                                        onSignal(JSONObject().put("kind", "answer").put("sdp", answer.description))
                                    }
                                    override fun onSetFailure(error: String) { onStatus("Answer setup failed: $error") }
                                    override fun onCreateSuccess(description: SessionDescription) {}
                                    override fun onCreateFailure(error: String) {}
                                }, answer)
                            }
                            override fun onSetSuccess() {}
                            override fun onCreateFailure(error: String) { onStatus("Answer failed: $error") }
                            override fun onSetFailure(error: String) {}
                        }, MediaConstraints())
                    }
                    override fun onSetFailure(error: String) { onStatus("Offer rejected: $error") }
                    override fun onCreateSuccess(description: SessionDescription) {}
                    override fun onCreateFailure(error: String) {}
                }, description)
            }
            "answer" -> {
                val description = SessionDescription(SessionDescription.Type.ANSWER, message.getString("sdp"))
                peer.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() { onStatus("Remote answer received.") }
                    override fun onSetFailure(error: String) { onStatus("Answer rejected: $error") }
                    override fun onCreateSuccess(description: SessionDescription) {}
                    override fun onCreateFailure(error: String) {}
                }, description)
            }
            "candidate" -> {
                val candidate = IceCandidate(
                    message.optString("sdpMid"),
                    message.optInt("sdpMLineIndex"),
                    message.getString("candidate")
                )
                peer.addIceCandidate(candidate)
            }
        }
    }

    fun switchCamera() {
        capturer.switchCamera(null)
    }

    fun close() {
        if (closed) return
        closed = true
        try { capturer.stopCapture() } catch (_: Exception) {}
        capturer.dispose()
        localTrack.dispose()
        videoSource.dispose()
        textureHelper.dispose()
        peer.close()
        peer.dispose()
        factory.dispose()
        egl.release()
        onStatus("WebRTC session closed.")
    }
}
