package com.elora.app

import android.content.Context
import org.json.JSONObject
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.SoftwareVideoDecoderFactory
import org.webrtc.SoftwareVideoEncoderFactory
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
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Native WebRTC peer for ELORA.
 * publishCamera=true is the camera phone; false is a receive-only viewer.
 * Signaling is supplied by the caller.
 */
private object EloraWebRtcRuntime {
    @Volatile private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions()
        )
        initialized = true
    }
}

class WebRtcSession(
    context: Context,
    private val publishCamera: Boolean,
    private val onSignal: (JSONObject) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onLocalVideo: (VideoTrack) -> Unit,
    private val onRemoteVideo: (VideoTrack) -> Unit
) {
    private val appContext = context.applicationContext
    private lateinit var egl: EglBase
    private lateinit var factory: PeerConnectionFactory
    private lateinit var peer: PeerConnection
    private var videoSource: VideoSource? = null
    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var localTrack: VideoTrack? = null
    private var remoteDescriptionSet = false
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private val closed = AtomicBoolean(false)
    private val offerStarted = AtomicBoolean(false)
    private val answerStarted = AtomicBoolean(false)
    private val remoteOfferAccepted = AtomicBoolean(false)
    // Serialize peer creation, SDP, ICE and disposal on one dedicated thread.
    private val peerThread = HandlerThread("ELORA-WebRTC").apply { start() }
    private val peerHandler = Handler(peerThread.looper)

    private fun runPeerSync(operation: () -> Unit) {
        if (Thread.currentThread() === peerThread) { operation(); return }
        val latch = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        peerHandler.post {
            try { operation() } catch (error: Throwable) { failure.set(error) }
            finally { latch.countDown() }
        }
        if (!latch.await(20, TimeUnit.SECONDS)) throw IllegalStateException("Timed out initializing WebRTC peer thread")
        failure.get()?.let { throw it }
    }

    private fun dispatchPeerOperation(operation: () -> Unit) {
        if (closed.get()) return
        peerHandler.post { if (!closed.get()) operation() }
    }

    init {
        EloraDiagnostics.record(if (publishCamera) "WebRTC publisher initialization started" else "WebRTC viewer initialization started")
        EloraWebRtcRuntime.initialize(appContext)
        runPeerSync {
        egl = EglBase.create()
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(SoftwareVideoEncoderFactory())
            .setVideoDecoderFactory(SoftwareVideoDecoderFactory())
            .createPeerConnectionFactory()

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()
        )
        peer = factory.createPeerConnection(PeerConnection.RTCConfiguration(iceServers),
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    EloraDiagnostics.record("ICE connection state: " + state.name)
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
            // Prefer Camera2 where the device reports support; otherwise use Camera1.
            // This keeps older and vendor-customized Android camera stacks usable.
            val enumerator: org.webrtc.CameraEnumerator = if (Camera2Enumerator.isSupported(appContext)) {
                Camera2Enumerator(appContext)
            } else {
                Camera1Enumerator(true)
            }
            val cameraName = enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }
                ?: enumerator.deviceNames.firstOrNull()
                ?: error("No camera was found")
            val camera = enumerator.createCapturer(cameraName, null)
                ?: error("Could not open the camera")
            val texture = SurfaceTextureHelper.create("ELORA-Capture", egl.eglBaseContext)
                ?: error("Could not create camera texture")
            val source = factory.createVideoSource(false)
            camera.initialize(texture, appContext, source.capturerObserver)
            EloraDiagnostics.record("Starting conservative software WebRTC capture 640x480@15")
            camera.startCapture(640, 480, 15)
            EloraDiagnostics.record("Camera capture started")
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
        EloraDiagnostics.record("WebRTC peer and media initialized on dedicated thread")
        }
    }

    fun createOffer() = dispatchPeerOperation {
        if (!offerStarted.compareAndSet(false, true)) {
            EloraDiagnostics.record("Duplicate WebRTC offer request ignored")
            return@dispatchPeerOperation
        }
        EloraDiagnostics.record("WebRTC createOffer called")
        peer.createOffer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) {
                dispatchPeerOperation {
                    peer.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            if (!closed.get()) onSignal(JSONObject().put("kind", "offer").put("sdp", description.description))
                        }
                        override fun onSetFailure(error: String) { if (!closed.get()) onStatus("Offer setup failed: $error") }
                        override fun onCreateSuccess(description: SessionDescription) {}
                        override fun onCreateFailure(error: String) {}
                    }, description)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String) {
                EloraDiagnostics.record("WebRTC offer creation callback failed: $error")
                onStatus("Offer failed: $error")
            }
            override fun onSetFailure(error: String) {}
        }, MediaConstraints())
    }

    fun acceptSignal(message: JSONObject) = dispatchPeerOperation {
        EloraDiagnostics.record("WebRTC signal received: " + message.optString("kind"))
        when (message.optString("kind")) {
            "offer" -> {
                if (!remoteOfferAccepted.compareAndSet(false, true)) {
                    EloraDiagnostics.record("Duplicate remote offer ignored")
                    return@dispatchPeerOperation
                }
                val description = SessionDescription(SessionDescription.Type.OFFER, message.getString("sdp"))
                peer.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        dispatchPeerOperation {
                            remoteDescriptionSet = true
                            flushCandidates()
                            if (!answerStarted.compareAndSet(false, true)) return@dispatchPeerOperation
                            peer.createAnswer(object : SdpObserver {
                                override fun onCreateSuccess(answer: SessionDescription) {
                                    dispatchPeerOperation {
                                        peer.setLocalDescription(object : SdpObserver {
                                            override fun onSetSuccess() {
                                                if (!closed.get()) onSignal(JSONObject().put("kind", "answer").put("sdp", answer.description))
                                            }
                                            override fun onSetFailure(error: String) { if (!closed.get()) onStatus("Answer setup failed: $error") }
                                            override fun onCreateSuccess(description: SessionDescription) {}
                                            override fun onCreateFailure(error: String) {}
                                        }, answer)
                                    }
                                }
                                override fun onSetSuccess() {}
                                override fun onCreateFailure(error: String) { if (!closed.get()) onStatus("Answer failed: $error") }
                                override fun onSetFailure(error: String) {}
                            }, MediaConstraints())
                        }
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
                        dispatchPeerOperation {
                            remoteDescriptionSet = true
                            flushCandidates()
                            onStatus("Remote answer received.")
                        }
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

    fun switchCamera() = dispatchPeerOperation { capturer?.switchCamera(null) }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        EloraDiagnostics.record("WebRTC session closing; publisher=$publishCamera")
        // Dispose on the same thread used for peer creation and all peer operations.
        val cleanup = Runnable {
            // Detach the native peer before releasing its media resources.
            try { peer.close() } catch (error: Throwable) {
                EloraDiagnostics.record("Peer close failed: " + error.javaClass.simpleName)
            }
            try { capturer?.stopCapture() } catch (error: Throwable) {
                EloraDiagnostics.record("Camera stop failed: " + error.javaClass.simpleName)
            }
            try { localTrack?.setEnabled(false) } catch (_: Throwable) {}
            try { localTrack?.dispose() } catch (_: Throwable) {}
            try { videoSource?.dispose() } catch (_: Throwable) {}
            try { capturer?.dispose() } catch (_: Throwable) {}
            try { textureHelper?.dispose() } catch (_: Throwable) {}
            try { peer.dispose() } catch (_: Throwable) {}
            try { factory.dispose() } catch (_: Exception) {}
            try { egl.release() } catch (_: Exception) {}
            onStatus("WebRTC session closed.")
        }
        peerHandler.post {
            try { cleanup.run() }
            finally { peerThread.quitSafely() }
        }
    }
}
