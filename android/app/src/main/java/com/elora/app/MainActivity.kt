package com.elora.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
    private var connection: EloraConnectionController? = null
    private var remoteVideoTrack: org.webrtc.VideoTrack? = null
    private lateinit var roomCodeText: TextView
    private lateinit var codeInput: android.widget.EditText
    private lateinit var remoteRenderer: org.webrtc.SurfaceViewRenderer
    private var rendererEgl: org.webrtc.EglBase? = null

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
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
            text = "Native camera preview • Front / rear switching"
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
            addView(previewView)
            addView(remoteRenderer)
            addView(statusText)
            addView(roomCodeText)
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

    private fun requestCameraAndStart() {
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
        val intent = Intent(this, CameraForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        if (!BuildConfig.ELORA_SIGNALING_URL.startsWith("wss://")) {
            statusText.text = "Secure ELORA server is not deployed yet."
            stopService(intent)
            return
        }
        connection?.close()
        connection = EloraConnectionController(this, BuildConfig.ELORA_SIGNALING_URL, this)
        connection?.createCameraRoom()
        statusText.text = "Opening private camera room…"
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
        if (!BuildConfig.ELORA_SIGNALING_URL.startsWith("wss://")) {
            statusText.text = "Secure ELORA server is not deployed yet."
            return
        }
        val code = codeInput.text.toString().trim()
        if (!code.matches(Regex("\\d{6}"))) {
            statusText.text = "Enter the six-digit code shown on the camera phone."
            return
        }
        connection?.close()
        connection = EloraConnectionController(this, BuildConfig.ELORA_SIGNALING_URL, this)
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
            statusText.text = "Private room ready. Share this code with your viewer."
            Toast.makeText(this, "ELORA code: $code", Toast.LENGTH_LONG).show()
        }
    }

    override fun onViewerRequest(viewerId: String) {
        runOnUiThread {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("New viewer request")
                .setMessage("Allow this phone to view your camera? Approval trusts this device for future sessions.")
                .setPositiveButton("APPROVE") { _, _ ->
                    if (connection?.approvePendingViewer() == true) statusText.text = "Approved. Connecting video…"
                }
                .setNegativeButton("DECLINE") { _, _ ->
                    connection?.declinePendingViewer()
                    statusText.text = "Viewer request declined."
                }
                .show()
        }
    }

    override fun onRemoteVideo(track: org.webrtc.VideoTrack) {
        runOnUiThread {
            remoteVideoTrack?.let { trackToRemove -> previewView.visibility = android.view.View.VISIBLE }
            remoteVideoTrack = track
            statusText.text = "Live video connected."
        }
    }

    override fun onDestroy() {
        stopPreview()
        if (receiverRegistered) unregisterReceiver(stopReceiver)
        super.onDestroy()
    }
}
