package com.hawkeyeborescopes.viewer

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hawkeyeborescopes.viewer.databinding.ActivityMainBinding
import com.serenegiant.usb.CameraDialog
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera

class MainActivity : AppCompatActivity(), CameraDialog.CameraDialogParent {

    private lateinit var binding: ActivityMainBinding
    private var usbMonitor: USBMonitor? = null
    private var uvcCamera: UVCCamera? = null
    private var isActive = false
    private var isPreviewing = false

    companion object {
        private const val REQUEST_PERMISSION = 1
        private const val PREVIEW_WIDTH = 1280
        private const val PREVIEW_HEIGHT = 720
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        checkPermissions()
        initUSBMonitor()
    }

    private fun setupUI() {
        // Controls panel initially hidden
        binding.controlsPanel.visibility = View.GONE

        // Settings button toggles control panel
        binding.settingsButton.setOnClickListener {
            binding.controlsPanel.visibility = if (binding.controlsPanel.visibility == View.VISIBLE) {
                View.GONE
            } else {
                View.VISIBLE
            }
        }

        // Start camera button
        binding.startButton.setOnClickListener {
            if (!isActive && !isPreviewing) {
                usbMonitor?.requestPermission(getUSBDevice())
            }
        }

        // Stop camera button
        binding.stopButton.setOnClickListener {
            stopPreview()
        }

        // Capture button
        binding.captureButton.setOnClickListener {
            captureImage()
        }

        // Record button
        binding.recordButton.setOnClickListener {
            toggleRecording()
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
        }

        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.S_V2) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_PERMISSION)
        }
    }

    private fun initUSBMonitor() {
        usbMonitor = USBMonitor(this, onDeviceConnectListener)
        usbMonitor?.register()
    }

    private val onDeviceConnectListener = object : USBMonitor.OnDeviceConnectListener {
        override fun onAttach(device: UsbDevice?) {
            showStatus("USB device attached: ${device?.deviceName}")
        }

        override fun onConnect(
            device: UsbDevice?,
            ctrlBlock: USBMonitor.UsbControlBlock?,
            createNew: Boolean
        ) {
            showStatus("USB device connected")
            startPreview(ctrlBlock)
        }

        override fun onDisconnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
            showStatus("USB device disconnected")
            stopPreview()
        }

        override fun onDetach(device: UsbDevice?) {
            showStatus("USB device detached")
        }

        override fun onCancel(device: UsbDevice?) {
            showStatus("USB permission cancelled")
        }
    }

    private fun getUSBDevice(): UsbDevice? {
        val list = usbMonitor?.deviceList
        return list?.firstOrNull()
    }

    private fun startPreview(ctrlBlock: USBMonitor.UsbControlBlock?) {
        if (ctrlBlock == null || isPreviewing) return

        try {
            uvcCamera = UVCCamera()
            uvcCamera?.open(ctrlBlock)

            // Set preview size
            uvcCamera?.setPreviewSize(
                PREVIEW_WIDTH,
                PREVIEW_HEIGHT,
                UVCCamera.FRAME_FORMAT_MJPEG
            )

            // Set surface for preview
            uvcCamera?.setPreviewDisplay(binding.cameraView.surfaceTexture)
            uvcCamera?.startPreview()

            isPreviewing = true
            isActive = true
            showStatus("Camera started")

        } catch (e: Exception) {
            showStatus("Error starting camera: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun stopPreview() {
        try {
            uvcCamera?.stopPreview()
            uvcCamera?.close()
            uvcCamera?.destroy()
            uvcCamera = null
            isPreviewing = false
            isActive = false
            showStatus("Camera stopped")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun captureImage() {
        if (!isPreviewing) {
            showStatus("Start camera first")
            return
        }

        // TODO: Implement image capture
        showStatus("Image capture - Coming soon")
    }

    private fun toggleRecording() {
        if (!isPreviewing) {
            showStatus("Start camera first")
            return
        }

        // TODO: Implement video recording
        showStatus("Video recording - Coming soon")
    }

    private fun showStatus(message: String) {
        runOnUiThread {
            binding.statusText.text = message
            binding.statusText.visibility = View.VISIBLE
            binding.statusText.postDelayed({
                binding.statusText.visibility = View.GONE
            }, 2000)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPreview()
        usbMonitor?.unregister()
        usbMonitor?.destroy()
    }

    // CameraDialog.CameraDialogParent implementation
    override fun getUSBMonitor(): USBMonitor? {
        return usbMonitor
    }

    override fun onDialogResult(canceled: Boolean) {
        if (!canceled) {
            showStatus("Please grant USB permission")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    showStatus("Permissions granted")
                } else {
                    showStatus("Permissions required")
                }
            }
        }
    }
}
