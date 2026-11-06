package com.elora.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val cameraRequest = 41

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = "ELORA"
            textSize = 30f
            setPadding(0, 24, 0, 8)
        }
        val note = TextView(this).apply {
            text = "Private camera • Android native foundation"
            textSize = 16f
        }
        val start = Button(this).apply {
            text = "START CAMERA SERVICE"
            setOnClickListener { requestCameraAndStart() }
        }
        val stop = Button(this).apply {
            text = "STOP CAMERA"
            setOnClickListener {
                stopService(Intent(this@MainActivity, CameraForegroundService::class.java))
                Toast.makeText(this@MainActivity, "ELORA camera service stopped", Toast.LENGTH_SHORT).show()
            }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 24)
            addView(title)
            addView(note)
            addView(start)
            addView(stop)
        })
    }

    private fun requestCameraAndStart() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), cameraRequest)
            return
        }
        startCameraService()
    }

    private fun startCameraService() {
        val intent = Intent(this, CameraForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == cameraRequest && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCameraService()
        } else if (requestCode == cameraRequest) {
            Toast.makeText(this, "Camera permission is required for ELORA", Toast.LENGTH_LONG).show()
        }
    }
}
