package com.hawkeyeborescopes.viewer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hawkeyeborescopes.viewer.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
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
        showStatus("App ready - Camera integration coming soon")
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
            showStatus("Camera integration coming soon - will be added in Codespaces")
        }

        // Stop camera button
        binding.stopButton.setOnClickListener {
            showStatus("Camera stop - coming soon")
        }

        // Capture button
        binding.captureButton.setOnClickListener {
            showStatus("Image capture - coming soon")
        }

        // Record button
        binding.recordButton.setOnClickListener {
            showStatus("Video recording - coming soon")
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
        // Camera cleanup will be added later
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
