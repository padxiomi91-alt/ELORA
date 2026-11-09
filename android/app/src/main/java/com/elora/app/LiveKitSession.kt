package com.elora.app

import android.content.Context
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.renderer.SurfaceViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ELORA media transport backed by LiveKit.
 * The server-issued token determines whether this device may publish or subscribe.
 */
class LiveKitSession(
    context: Context,
    private val publishCamera: Boolean,
    private val onStatus: (String) -> Unit,
    private val onLocalVideo: (VideoTrack) -> Unit,
    private val onRemoteVideo: (VideoTrack) -> Unit
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val room: Room = LiveKit.create(appContext)
    private val closed = AtomicBoolean(false)
    @Volatile private var connected = false
    @Volatile private var activeRemoteTrack: VideoTrack? = null

    fun connect(url: String, token: String) {
        if (closed.get()) return
        scope.launch {
            try {
                room.events.collect { event ->
                    when (event) {
                        is RoomEvent.TrackSubscribed -> {
                            if (event.track is VideoTrack && event.track.kind == Track.Kind.VIDEO) {
                                activeRemoteTrack = event.track as VideoTrack
                                onRemoteVideo(event.track as VideoTrack)
                                onStatus("Live video connected.")
                            }
                        }
                        is RoomEvent.TrackUnsubscribed -> {
                            if (event.track is VideoTrack && event.track === activeRemoteTrack) {
                                activeRemoteTrack = null
                                onStatus("Camera video paused.")
                            }
                        }
                        is RoomEvent.Disconnected -> {
                            connected = false
                            onStatus("Live video disconnected.")
                        }
                        else -> Unit
                    }
                }
            } catch (error: Throwable) {
                if (!closed.get()) onStatus("LiveKit event listener stopped: ${error.localizedMessage ?: error.javaClass.simpleName}")
            }
        }
        scope.launch {
            try {
                room.connect(url, token)
                connected = true
                if (publishCamera) {
                    val published = room.localParticipant.setCameraEnabled(true)
                    if (!published) throw IllegalStateException("Camera track could not be published")
                    val localTrack = room.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack
                    if (localTrack != null) onLocalVideo(localTrack)
                    onStatus("Camera connected securely. Waiting for viewer video.")
                } else {
                    onStatus("Approved. Waiting for camera video…")
                }
            } catch (error: Throwable) {
                if (!closed.get()) {
                    EloraDiagnostics.record("LiveKit connection failed", error)
                    onStatus("Live video connection failed: ${error.localizedMessage ?: error.javaClass.simpleName}")
                    close()
                }
            }
        }
    }

    fun initializeRenderer(renderer: SurfaceViewRenderer) {
        room.initVideoRenderer(renderer)
    }

    fun switchCamera() {
        if (!publishCamera || closed.get()) return
        scope.launch {
            try {
                val publication = room.localParticipant.getTrackPublication(Track.Source.CAMERA)
                val track = publication?.track as? io.livekit.android.room.track.LocalVideoTrack
                val capturer = track?.capturer
                val cameraCapturer = capturer as? livekit.org.webrtc.CameraVideoCapturer
                cameraCapturer?.switchCamera(null)
            } catch (error: Throwable) {
                onStatus("Could not switch camera: ${error.localizedMessage ?: error.javaClass.simpleName}")
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            activeRemoteTrack = null
            room.disconnect()
            room.release()
        } catch (_: Throwable) {
        }
        connected = false
        scope.cancel()
    }
}
