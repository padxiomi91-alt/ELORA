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
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Native WebRTC peer for ELORA.
 * publishCamera=true is the camera phone; false is a receive-only viewer.
 * Signaling is supplied by the caller.
 */
class WebRtcSession(
    context: Context,
    private val publishCamera: Boolean,
    private val onSignal: (JSONObject) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onLocalVideo: (VideoTrack) -> Unit,
    private val onRemoteVideo: (VideoTrack) -> Unit
) {
    private val appContext = context.applicationContext
    private val egl = EglBase.create()
    private val factory: PeerConnectionFactory
    private val peer: PeerConnection
    private var videoSource: VideoSource? = null
    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var localTrack: VideoTrack? = null
    private var remoteDescriptionSet = false
    private val pendingCandidates = mutableListOf<IceCandidate>()
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
        peer = factory.createPeerConnection(PeerConnection.RTCConfiguration(iceServers),
            object : PeerConnection.Observer {
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
                    (receiver.track() as? VideoTrack)?.let(onRemoteVideo)
                }
                override fun onTrack(transceiver: RtpTransceiver) {
                    (transceiver.receiver.track() as? VideoTrack)?.let(onRemoteVideo)
                }
            }) ?: error("Could not create WebRTC peer connection")

        if (publishCamera) {
            val enumerator = Camera2Enumerator(appContext)
            val cameraName = enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }
                ?: enumerator.deviceNames.firstOrNull()
                ?: error("No camera was found")
            val camera = enumerator.createCapturer(cameraName, null)
                ?: error("Could not open the camera")
            val texture = SurfaceTextureHelper.create("ELORA-Capture", egl.eglBaseContext)
                ?: error("Could not create camera texture")
            val source = factory.createVideoSource(false)
            camera.initialize(texture, appContext, source.capturerObserver)
            camera.startCapture(1280, 720, 24)
            val track = factory.createVideoTrack("ELORA_VIDEO", source)
            peer.addTrack(track, listOf("ELORA_STREAM"))
            capturer = camera
            textureHelper = texture
            videoSource = source
            localTrack = track
            onLocalVideo(track)
            onStatus("Camera media engine ready.")
        } else {
            peer.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
            onStatus("Viewer ready. Waiting for camera video.")
        }
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
                        remoteDescriptionSet = true
                        flushCandidates()
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
                    override fun onSetSuccess() {
                        remoteDescriptionSet = true
                        flushCandidates()
                        onStatus("Remote answer received.")
                    }
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
                if (remoteDescriptionSet) peer.addIceCandidate(candidate)
                else pendingCandidates.add(candidate)
            }
        }
    }

    private fun flushCandidates() {
        pendingCandidates.forEach { peer.addIceCandidate(it) }
        pendingCandidates.clear()
    }

    fun switchCamera() {
        capturer?.switchCamera(null)
    }

    fun close() {
        if (closed) return
        closed = true
        try { capturer?.stopCapture() } catch (_: Exception) {}
        capturer?.dispose()
        localTrack?.dispose()
        videoSource?.dispose()
        textureHelper?.dispose()
        peer.close()
        peer.dispose()
        factory.dispose()
        egl.release()
        onStatus("WebRTC session closed.")
    }
}
