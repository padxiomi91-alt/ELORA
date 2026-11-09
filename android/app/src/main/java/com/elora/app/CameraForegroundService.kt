package com.elora.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import io.livekit.android.renderer.SurfaceViewRenderer
import io.livekit.android.room.track.VideoTrack

/**
 * Owns the camera publishing session so it can outlive MainActivity while
 * Android's required camera foreground-service notification remains visible.
 */
class CameraForegroundService : Service(), EloraConnectionController.Callbacks {
    companion object {
        private const val CHANNEL_ID = "elora_camera"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.elora.app.STOP_CAMERA"
        const val ACTION_CAMERA_STOPPED = "com.elora.app.CAMERA_STOPPED"
    }

    inner class LocalBinder : Binder() {
        fun service(): CameraForegroundService = this@CameraForegroundService
    }

    private val binder = LocalBinder()
    private var controller: EloraConnectionController? = null
    @Volatile private var activityCallbacks: EloraConnectionController.Callbacks? = null
    private var stopBroadcastSent = false
    @Volatile private var activeServerUrl: String? = null

    override fun onCreate() {
        super.onCreate()
        EloraDiagnostics.record("Camera service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCameraSession()
            return START_NOT_STICKY
        }

        EloraDiagnostics.record("Camera service start command received")
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            EloraDiagnostics.record("Camera foreground mode started")
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun setCallbacks(callbacks: EloraConnectionController.Callbacks?) {
        activityCallbacks = callbacks
    }

    @Synchronized
    fun startCameraRoom(serverUrl: String) {
        EloraDiagnostics.record("Camera room startup requested")
        // Activity recreation/rebinding can request startup more than once.
        // Keep the existing publisher alive while a viewer is negotiating.
        if (controller != null && activeServerUrl == serverUrl) {
            EloraDiagnostics.record("Camera room already active; duplicate startup ignored")
            return
        }
        try {
            controller?.close()
            controller = null
            activeServerUrl = null
            val newController = EloraConnectionController(applicationContext, serverUrl, this)
            controller = newController
            activeServerUrl = serverUrl
            newController.createCameraRoom()
        } catch (error: Throwable) {
            EloraDiagnostics.record("Camera room startup failed", error)
            android.util.Log.e("ELORA", "Camera startup failed", error)
            try { controller?.close() } catch (cleanupError: Throwable) {
                android.util.Log.e("ELORA", "Camera startup cleanup failed", cleanupError)
            }
            controller = null
            activeServerUrl = null
            onStatus("Camera startup failed: ${error.javaClass.simpleName}: ${error.localizedMessage ?: "unknown error"}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    fun switchCamera() {
        controller?.switchCamera()
    }

    fun approvePendingViewer(): Boolean = controller?.approvePendingViewer() == true

    fun declinePendingViewer(): Boolean = controller?.declinePendingViewer() == true

    fun stopCameraSession() {
        EloraDiagnostics.record("Camera session stop requested")
        controller?.close()
        controller = null
        activeServerUrl = null
        if (!stopBroadcastSent) {
            stopBroadcastSent = true
            sendBroadcast(Intent(ACTION_CAMERA_STOPPED).setPackage(packageName))
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onStatus(message: String) {
        activityCallbacks?.onStatus(message)
    }

    override fun onRoomCode(code: String) {
        activityCallbacks?.onRoomCode(code)
    }

    override fun onViewerRequest(viewerId: String) {
        activityCallbacks?.onViewerRequest(viewerId)
    }

    override fun onInitializeVideoRenderer(initializer: (SurfaceViewRenderer) -> Unit) {\n        activityCallbacks?.onInitializeVideoRenderer(initializer)\n    }\n\n    override fun onLocalVideo(track: VideoTrack) {
        activityCallbacks?.onLocalVideo(track)
    }

    override fun onRemoteVideo(track: VideoTrack) {
        activityCallbacks?.onRemoteVideo(track)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, CameraForegroundService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("ELORA camera is active")
                .setContentText("Tap Stop to end camera access.")
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentIntent(openIntent)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "STOP CAMERA", stopPendingIntent)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("ELORA camera is active")
                .setContentText("Tap Stop to end camera access.")
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentIntent(openIntent)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "STOP CAMERA", stopPendingIntent)
                .build()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "ELORA camera", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows while ELORA camera access is active."
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        EloraDiagnostics.record("Camera service destroyed")
        controller?.close()
        controller = null
        activeServerUrl = null
        if (!stopBroadcastSent) {
            sendBroadcast(Intent(ACTION_CAMERA_STOPPED).setPackage(packageName))
        }
        super.onDestroy()
    }
}
