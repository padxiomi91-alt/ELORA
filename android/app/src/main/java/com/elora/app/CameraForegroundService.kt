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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCameraSession()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun setCallbacks(callbacks: EloraConnectionController.Callbacks?) {
        activityCallbacks = callbacks
    }

    fun startCameraRoom(serverUrl: String) {
        controller?.close()
        controller = EloraConnectionController(applicationContext, serverUrl, this)
        controller?.createCameraRoom()
    }

    fun switchCamera() {
        controller?.switchCamera()
    }

    fun approvePendingViewer(): Boolean = controller?.approvePendingViewer() == true

    fun declinePendingViewer(): Boolean = controller?.declinePendingViewer() == true

    fun stopCameraSession() {
        controller?.close()
        controller = null
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

    override fun onLocalVideo(track: org.webrtc.VideoTrack) {
        activityCallbacks?.onLocalVideo(track)
    }

    override fun onRemoteVideo(track: org.webrtc.VideoTrack) {
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
        controller?.close()
        controller = null
        if (!stopBroadcastSent) {
            sendBroadcast(Intent(ACTION_CAMERA_STOPPED).setPackage(packageName))
        }
        super.onDestroy()
    }
}
