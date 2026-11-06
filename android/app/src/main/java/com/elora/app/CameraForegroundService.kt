package com.elora.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class CameraForegroundService : Service() {
    companion object {
        private const val CHANNEL_ID = "elora_camera"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.elora.app.STOP_CAMERA"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCameraService()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // The native WebRTC camera engine will be connected in the next implementation step.
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, CameraForegroundService::class.java).apply {
            action = ACTION_STOP
        }
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
                CHANNEL_ID,
                "ELORA camera",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows while ELORA camera access is active."
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun stopCameraService() {
        // Stop the camera/WebRTC tracks here when the native streaming engine is added.
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
