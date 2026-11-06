package com.elora.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), EloraConnectionController.Callbacks {
    private val cameraRequest = 41
    private val notificationRequest = 42
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private var cameraProvider: ProcessCameraProvider? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var receiverRegistered = false
    private var connection: EloraConnectionController? = null // viewer-side session
    private var cameraService: CameraForegroundService? = null
    private var cameraServiceBound = false
    private var pendingCameraStart = false

    private val cameraServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, binder: IBinder?) {
            val serviceBinder = binder as? CameraForegroundService.LocalBinder ?: return
            cameraService = serviceBinder.service()
            cameraServiceBound = true
            cameraService?.setCallbacks(this@MainActivity)
            if (pendingCameraStart) {
                pendingCameraStart = false
                cameraService?.startCameraRoom(savedSignalingUrl())
                statusText.text = "Opening private camera room…"
            }
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            cameraService?.setCallbacks(null)
            cameraService = null
            cameraServiceBound = false
        }
    }
    private var remoteVideoTrack: org.webrtc.VideoTrack? = null
    private lateinit var roomCodeText: TextView
    private lateinit var codeInput: android.widget.EditText
    private lateinit var serverUrlInput: android.widget.EditText
    private lateinit var remoteRenderer: org.webrtc.SurfaceViewRenderer
    private var rendererEgl: org.webrtc.EglBase? = null

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            connection?.close()
            connection = null
            cameraService?.setCallbacks(null)
            cameraService = null
            if (cameraServiceBound) {
                unbindService(cameraServiceConnection)
                cameraServiceBound = false
            }
            stopPreview()
            statusText.text = "Camera stopped."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = "ELORA"
            textSize = 30f
            setPadding(0, 24, 0, 8)
        }
        val note = TextView(this).apply {
            text = "Private camera • Live viewer connection"
            textSize = 16f
        }
        previewView = PreviewView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        remoteRenderer = org.webrtc.SurfaceViewRenderer(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 260)
            rendererEgl = org.webrtc.EglBase.create()
            init(rendererEgl!!.eglBaseContext, null)
            setEnableHardwareScaler(true)
        }
        roomCodeText = TextView(this).apply { textSize = 22f; setPadding(0, 8, 0, 8) }
        codeInput = android.widget.EditText(this).apply {
            hint = "Enter 6-digit camera code"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }
        serverUrlInput = android.widget.EditText(this).apply {
            hint = "Secure server URL (wss://...)"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(savedSignalingUrl())
        }
        val saveServer = Button(this).apply {
            text = "SAVE SERVER ADDRESS"
            setOnClickListener { saveServerAddress() }
        }
        statusText = TextView(this).apply {
            text = "Camera is off."
            textSize = 14f
            setPadding(0, 12, 0, 12)
        }
        val start = Button(this).apply {
            text = "START CAMERA"
            setOnClickListener { requestCameraAndStart() }
        }
        val switch = Button(this).apply {
            text = "SWITCH FRONT / BACK"
            setOnClickListener { switchCamera() }
        }
        val join = Button(this).apply {
            text = "VIEW ANOTHER PHONE"
            setOnClickListener { joinCameraRoom() }
        }
        val stop = Button(this).apply {
            text = "STOP / DISCONNECT"
            setOnClickListener { stopCamera() }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 20)
            addView(title)
            addView(note)
            addView(remoteRenderer)
            addView(statusText)
            addView(roomCodeText)
            addView(serverUrlInput)
            addView(saveServer)
            addView(start)
            addView(codeInput)
            addView(join)
            addView(switch)
            addView(stop)
        })

        val filter = IntentFilter(CameraForegroundService.ACTION_CAMERA_STOPPED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stopReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(stopReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun savedSignalingUrl(): String {
        return getSharedPreferences("elora_settings", MODE_PRIVATE)
            .getString("signaling_url", BuildConfig.ELORA_SIGNALING_URL)
            ?.trim().orEmpty()
    }

    private fun saveServerAddress() {
        val value = serverUrlInput.text.toString().trim()
        if (!value.startsWith("wss://")) {
            statusText.text = "Use a secure WebSocket address beginning with wss://."
            return
        }
        getSharedPreferences("elora_settings", MODE_PRIVATE)
            .edit().putString("signaling_url", value).apply()
        statusText.text = "Secure server address saved on this phone."
    }

    private fun requestCameraAndStart() {
        if (!savedSignalingUrl().startsWith("wss://")) {
            statusText.text = "Secure ELORA server is not deployed yet."
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), cameraRequest)
            return
        }
        startCameraService()
    }

    private fun startCameraService() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), notificationRequest)
            return
        }
        launchCameraService()
    }

    private fun launchCameraService() {
        val serverUrl = savedSignalingUrl()
        if (!serverUrl.startsWith("wss://")) {
            statusText.text = "Enter a secure ELORA server address first."
            return
        }
        val intent = Intent(this, CameraForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        connection?.close()
        connection = null
        pendingCameraStart = true
        if (cameraServiceBound && cameraService != null) {
            pendingCameraStart = false
            cameraService?.setCallbacks(this)
            cameraService?.startCameraRoom(serverUrl)
            statusText.text = "Opening private camera room…"
        } else {
            bindService(intent, cameraServiceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun startPreview() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                cameraProvider = providerFuture.get()
                bindSelectedCamera()
            } catch (error: Exception) {
                statusText.text = "Camera could not start: ${error.localizedMessage}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindSelectedCamera() {
        val provider = cameraProvider ?: return
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        if (!provider.hasCamera(selector)) {
            statusText.text = "This camera is not available on this phone."
            return
        }
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, preview)
            statusText.text = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                "Rear camera active."
            } else {
                "Front camera active."
            }
        } catch (error: Exception) {
            statusText.text = "Could not open camera: ${error.localizedMessage}"
        }
    }

    private fun switchCamera() {
        if (cameraService != null) { cameraService?.switchCamera(); return }
        if (connection != null) { connection?.switchCamera(); return }
        if (cameraProvider == null) {
            statusText.text = "Start the camera first."
            return
        }
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        bindSelectedCamera()
    }

    private fun joinCameraRoom() {
        if (!savedSignalingUrl().startsWith("wss://")) {
            statusText.text = "Secure ELORA server is not deployed yet."
            return
        }
        val code = codeInput.text.toString().trim()
        if (!code.matches(Regex("\\d{6}"))) {
            statusText.text = "Enter the six-digit code shown on the camera phone."
            return
        }
        connection?.close()
        connection = EloraConnectionController(this, savedSignalingUrl(), this)
        connection?.joinAsViewer(code)
        statusText.text = "Connecting to camera phone…"
    }

    private fun stopPreview() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        previewView.setBackgroundColor(android.graphics.Color.BLACK)
    }

    private fun stopCamera() {
        connection?.close()
        connection = null
        remoteVideoTrack?.removeSink(remoteRenderer)
        remoteVideoTrack = null
        remoteRenderer.clearImage()
        stopPreview()
        cameraService?.setCallbacks(null)
        cameraService?.stopCameraSession()
        cameraService = null
        pendingCameraStart = false
        if (cameraServiceBound) {
            unbindService(cameraServiceConnection)
            cameraServiceBound = false
        }
        stopService(Intent(this, CameraForegroundService::class.java))
        statusText.text = "Disconnected. Camera stopped."
        roomCodeText.text = ""
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == cameraRequest && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCameraService()
        } else if (requestCode == cameraRequest) {
            Toast.makeText(this, "Camera permission is required for ELORA", Toast.LENGTH_LONG).show()
        } else if (requestCode == notificationRequest) {
            launchCameraService()
        }
    }


    override fun onStatus(message: String) {
        runOnUiThread { if (::statusText.isInitialized) statusText.text = message }
    }

    override fun onRoomCode(code: String) {
        runOnUiThread {
            roomCodeText.text = "PRIVATE CODE\n$code"
            statusText.text = "Share this code only with the phone you trust."
        }
    }

    override fun onViewerRequest(viewerId: String) {
        runOnUiThread {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("New viewer request")
                .setMessage("Allow this phone to view your camera? Approval trusts this device for future sessions.")
                .setPositiveButton("APPROVE") { _, _ ->
                    val approved = cameraService?.approvePendingViewer() ?: (connection?.approvePendingViewer() == true)
                    if (approved) statusText.text = "Approved. Connecting video…"
                }
                .setNegativeButton("DECLINE") { _, _ ->
                    if (cameraService != null) cameraService?.declinePendingViewer() else connection?.declinePendingViewer()
                    statusText.text = "Viewer request declined."
                }
                .show()
        }
    }

    override fun onRemoteVideo(track: org.webrtc.VideoTrack) {
        runOnUiThread {
            remoteVideoTrack?.removeSink(remoteRenderer)
            remoteVideoTrack = track
            track.addSink(remoteRenderer)
            statusText.text = "Live video connected."
        }
    }

    override fun onDestroy() {
        // Camera publishing belongs to the foreground service and survives Activity recreation.
        connection?.close()
        connection = null
        cameraService?.setCallbacks(null)
        if (cameraServiceBound) {
            unbindService(cameraServiceConnection)
            cameraServiceBound = false
        }
        stopPreview()
        remoteVideoTrack?.removeSink(remoteRenderer)
        remoteRenderer.release()
        rendererEgl?.release()
        if (receiverRegistered) unregisterReceiver(stopReceiver)
        super.onDestroy()
    }
}
