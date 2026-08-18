package com.hawkeyeborescopes.viewer

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.view.WindowManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hawkeyeborescopes.viewer.databinding.ActivityMainBinding
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.base.CameraActivity
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import android.content.res.Configuration
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

class MainActivity : CameraActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isRecording = false
    private var currentRecordingPath: String? = null
    private var isSettingsPanelOpen = false
    private var buttonHelper: UsbButtonHelper? = null
    private var mTextureView: AspectRatioTextureView? = null
    private var wasCameraOpen = false
    @Volatile private var hardwareCaptureInFlight = false
    @Volatile private var hardwareButtonCaptureInFlight = false
    @Volatile private var hardwareCaptureStartedAtMs = 0L
    @Volatile private var latestPreviewBytes: ByteArray? = null
    @Volatile private var latestPreviewWidth = 0
    @Volatile private var latestPreviewHeight = 0
    @Volatile private var latestPreviewFormatName = ""
    @Volatile private var latestPreviewTimestampMs = 0L
    @Volatile private var hasLoggedPreviewFrame = false

    // Zoom/pan state
    private var currentZoom = 1.0f
    private var currentPanX = 0.0f
    private var currentPanY = 0.0f
    private var scaleGestureDetector: ScaleGestureDetector? = null
    private var gestureDetector: GestureDetector? = null
    private var isPinching = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var isStartingCamera = false
    private var isCameraActive = false
    private var lastStopTimeMs = 0L
    private val startRetryRunnable = Runnable { doStartCamera() }
    private var isZoomModeActive = false
    private var isDragModeActive = false
    private val zoomOverlayHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // Phone layout detection
    private val isPhone: Boolean by lazy {
        resources.configuration.smallestScreenWidthDp < 600
    }

    // Phone adjust strip state
    private var isAdjustStripOpen = false
    private enum class StripSection { CAMERA, IMAGE, TRANSFORM }
    private var currentStripSection = StripSection.IMAGE
    private var currentImageControlIndex = 0

    // Mask: circular clip over the preview, for mirror-tube use.
    // maskRadiusRef is in px of a 720-tall reference frame, same units as the
    // Windows viewer's Mask Radius slider (100..360, 360 = inscribed circle).
    private var maskOn = false
    private var maskRadiusRef = MASK_RADIUS_DEFAULT

    // Touch / weighted luminance metering.
    // Frames arrive on the GL thread, the UI reads these — hence @Volatile.
    @Volatile private var meterMode = METER_OFF
    @Volatile private var meterX = 0.5f
    @Volatile private var meterY = 0.5f
    @Volatile private var meterWindow = 0.12f
    @Volatile private var targetLuma = 125
    @Volatile private var meterBusy = false
    private var meterFrameCounter = 0

    // Set on each spot tap: logs the sampled luma for both row orders once, so a
    // single tap on a known-bright area confirms RGBA_BUFFER_Y_FLIPPED on device.
    @Volatile private var meterDiagPending = false

    // Guards the two UI copies (panel + strip) against listener feedback loops.
    private var maskUiSyncing = false

    // One-shot: raw-frame spot metering misalignment warning, reset on each tap.
    @Volatile private var rawSpotWarningIssued = false
    private var lastMeterLogMs = 0L

    private val previewDataCallback = object : IPreviewDataCallBack {
        override fun onPreviewData(data: ByteArray?, width: Int, height: Int, format: IPreviewDataCallBack.DataFormat) {
            if (data == null) {
                return
            }
            if (!hasLoggedPreviewFrame) {
                hasLoggedPreviewFrame = true
                writeLog("previewDataCallback: first frame format=${format.name} size=${data.size} ${width}x${height}")
            }
            latestPreviewBytes = data.copyOf()
            latestPreviewWidth = width
            latestPreviewHeight = height
            latestPreviewFormatName = format.name
            latestPreviewTimestampMs = System.currentTimeMillis()

            meterFrame(data, width, height, format)
        }
    }

    companion object {
        private const val TAG = "HawkeyeCamera"
        private const val REQUEST_PERMISSION = 1
        private const val ZOOM_MIN = 1.0f
        private const val ZOOM_MAX = 5.0f
        private const val DPAD_PAN_STEP = 0.05f
        private const val DPAD_ZOOM_STEP = 0.25f
        private const val MOUSE_SCROLL_ZOOM_FACTOR = 0.1f
        private const val ZOOM_OVERLAY_FADE_MS = 1500L
        private const val HARDWARE_CAPTURE_INITIAL_DELAY_MS = 0L
        private const val HARDWARE_CAPTURE_RETRY_DELAY_MS = 150L
        private const val HARDWARE_CAPTURE_MAX_ATTEMPTS = 5
        private const val MIN_VALID_HARDWARE_CAPTURE_BYTES = 20_000L
        private const val MAX_PREVIEW_FRAME_AGE_MS = 1_000L
        private const val CAPTURE_FEEDBACK_DURATION_MS = 350L
        private const val MAX_VISIBLE_HARDWARE_CAPTURE_AGE_MS = 2_000L

        // --- Scope mask ---
        // Radius is expressed in px of a 720-tall reference frame so the numbers
        // match the Windows viewer's Mask Radius slider one-for-one.
        private const val MASK_REF_HEIGHT = 720f
        private const val MASK_RADIUS_MIN = 100
        private const val MASK_RADIUS_MAX = 360
        private const val MASK_RADIUS_DEFAULT = 280

        // --- Luminance metering ---
        private const val METER_OFF = "off"
        private const val METER_SPOT = "spot"
        private const val METER_CENTER = "center"
        private const val METER_EDGE = "edge"
        private val METER_MODES = listOf(METER_OFF, METER_SPOT, METER_CENTER, METER_EDGE)
        private val METER_LABELS = listOf("Off", "Spot (tap preview)", "Center disc", "Edge annulus")
        private const val LUMA_DEADBAND = 8          // no correction inside target +/- this
        private const val METER_EVERY_N_FRAMES = 5   // control step every N frames
        private const val METER_MAX_STEP = 6         // max brightness units per step
        private const val LUMA_SUBSAMPLE = 4         // sample every Nth pixel per axis
        private const val BRIGHTNESS_MIN = 0
        private const val BRIGHTNESS_MAX = 100
        private const val BRIGHTNESS_DEFAULT = 50
        private const val METER_LOG_INTERVAL_MS = 500L

        // The RGBA preview buffer comes from glReadPixels, whose origin is
        // bottom-left, so buffer row 0 is the bottom of the displayed image.
        private const val RGBA_BUFFER_Y_FLIPPED = true

        private const val PREFS_NAME = "image_adjustments"
        private const val PREF_MASK_ON = "mask_on"
        private const val PREF_MASK_RADIUS = "mask_radius"
    }

    private fun writeLog(message: String) {
        try {
            Log.d(TAG, message)
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val logFile = File(getExternalFilesDir(null), "hawkeye_debug.log")
            FileWriter(logFile, true).use { writer ->
                writer.appendLine("[$timestamp] $message")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log", e)
        }
    }

    // ========================
    // CameraActivity overrides
    // ========================

    override fun getRootView(layoutInflater: LayoutInflater): View? {
        binding = ActivityMainBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun getCameraView(): IAspectRatio {
        return AspectRatioTextureView(this).also {
            mTextureView = it
        }
    }

    override fun getCameraViewContainer(): ViewGroup {
        return binding.cameraContainer
    }

    override fun getCameraRequest(): CameraRequest {
        return CameraRequest.Builder()
            .setPreviewWidth(3840)
            .setPreviewHeight(3840)
            .setRenderMode(CameraRequest.RenderMode.OPENGL)
            .setDefaultRotateType(RotateType.ANGLE_0)
            .setAudioSource(
                if (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK
                    == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION)
                    CameraRequest.AudioSource.NONE
                else CameraRequest.AudioSource.SOURCE_AUTO
            )
            .setAspectRatioShow(false)
            .setCaptureRawImage(false)
            .setRawPreviewData(false)
            .create()
    }

    override fun onCameraState(
        self: MultiCameraClient.ICamera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {
        when (code) {
            ICameraStateCallBack.State.OPENED -> {
                writeLog("Camera OPENED")
                isStartingCamera = false
                isCameraActive = true
                wasCameraOpen = true
                showStatus("Camera ready")

                // Show camera name
                val deviceName = self.device?.productName ?: self.device?.deviceName ?: "USB Camera"
                runOnUiThread {
                    binding.cameraNameText.text = deviceName
                    binding.cameraNameText.setTextColor(
                        ContextCompat.getColor(this, R.color.text_primary))
                }
                writeLog("Camera name: $deviceName")

                // Detect actual camera resolution and update aspect ratio labels
                val actualW = self.getCameraRequest()?.previewWidth ?: cameraSourceW
                val actualH = self.getCameraRequest()?.previewHeight ?: cameraSourceH
                writeLog("Camera actual resolution: ${actualW}x${actualH}")
                if (actualW > 0 && actualH > 0 && (actualW != cameraSourceW || actualH != cameraSourceH)) {
                    cameraSourceW = actualW
                    cameraSourceH = actualH
                    runOnUiThread {
                        updateAspectLabels()
                        // Re-apply current aspect with correct crop for new resolution
                        val pos = binding.viewModeSpinner.selectedItemPosition
                        if (pos >= 0 && pos < aspectRatioTypes.size) {
                            applyAspectType(aspectRatioTypes[pos])
                        }
                    }
                }

                // Re-apply shader adjustments from current slider values
                pushShaderAdjustments()

                // Re-apply crop zoom for current aspect ratio selection
                setCropZoom(currentCropZoomX, currentCropZoomY)

                // Re-apply zoom/pan (needed after returning from gallery or resume)
                applyZoomPan()

                // Re-apply mirror/flip transform (needed after resume)
                updateTransform()
                addPreviewDataCallBack(previewDataCallback)

                // One-shot: does this camera actually respond to the exposure
                // controls the metering loop drives?
                probeExposureControls()

                // Log storage location
                if (isUsingRemovableStorage()) {
                    writeLog("Storage: USB removable drive detected")
                    showStatus("Camera ready — saving to USB")
                }

                // Start button listener for physical borescope buttons
                buttonHelper?.stop()
                buttonHelper = null
                val camera = self as? CameraUVC
                if (camera == null) {
                    writeLog("UsbButtonHelper: current camera does not expose CameraUVC bridge hooks")
                } else {
                    buttonHelper = UsbButtonHelper(
                        camera = camera,
                        usbDevice = self.device,
                        onCapturePressed = { runOnUiThread { triggerHardwareCapture(fromHardwareButton = true) } },
                        onRecordPressed = { runOnUiThread { toggleRecording() } },
                        writeLog = { msg2 -> writeLog(msg2) },
                        armRollAtStartup = isPhone
                    )
                    if (buttonHelper?.start() != true) {
                        writeLog("UsbButtonHelper: failed to start hardware bridge")
                    }
                }
            }
            ICameraStateCallBack.State.CLOSED -> {
                writeLog("Camera CLOSED")
                isCameraActive = false
                if (isStartingCamera) {
                    // CLOSED during start attempt — the delay-based start will handle retry
                    writeLog("Camera closed during start attempt — delay will handle it")
                    isStartingCamera = false
                } else {
                    showStatus("Camera disconnected")
                }
                runOnUiThread {
                    binding.cameraNameText.text = "No camera connected"
                    binding.cameraNameText.setTextColor(
                        ContextCompat.getColor(this, R.color.text_tertiary))
                }
                removePreviewDataCallBack(previewDataCallback)
                clearLatestPreviewFrame()
                hardwareCaptureInFlight = false
                hardwareButtonCaptureInFlight = false
                hardwareCaptureStartedAtMs = 0L
                buttonHelper?.stop()
                buttonHelper = null
            }
            ICameraStateCallBack.State.ERROR -> {
                writeLog("Camera ERROR: $msg")
                // First requestPermission() after closeCamera() always fails with -99.
                // Auto-retry once — the second attempt succeeds.
                if (isStartingCamera && msg?.contains("result=-99") == true) {
                    writeLog("Got -99 during start — auto-retrying in 500ms")
                    binding.cameraContainer.removeCallbacks(startRetryRunnable)
                    binding.cameraContainer.postDelayed(startRetryRunnable, 500)
                    // Keep isStartingCamera true so CLOSED callback stays quiet
                    return
                }
                isStartingCamera = false
                showStatus("Camera error: $msg")
                removePreviewDataCallBack(previewDataCallback)
                clearLatestPreviewFrame()
                hardwareCaptureInFlight = false
                hardwareButtonCaptureInFlight = false
                hardwareCaptureStartedAtMs = 0L
                buttonHelper?.stop()
                buttonHelper = null
            }
        }
    }

    // =====================
    // Lifecycle & UI setup
    // =====================

    override fun initView() {
        super.initView()
        // Keep screen on while app is active — prevents sleep from killing camera
        // Only on phone; tablet's USB hub is bandwidth-sensitive
        if (isPhone) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val sessionId = SimpleDateFormat("HHmmss", Locale.US).format(Date())
        writeLog("=== APP STARTING (libausbc 3.3.3) session=$sessionId ===")

        try {
            // Default camera source dimensions (updated when camera opens)
            cameraSourceW = 720
            cameraSourceH = 720

            checkPermissions()
            setupUI()
            writeLog("UI setup complete, waiting for camera...")
            showStatus("Ready - Connect USB camera")
        } catch (e: Exception) {
            writeLog("FATAL ERROR in initView: ${e.message}")
            writeLog("Stack trace: ${e.stackTraceToString()}")
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onStop() {
        writeLog("onStop: cleaning up camera client")
        // Save adjustment settings before stopping (D-pad doesn't trigger onStopTrackingTouch)
        saveImageControls()
        // Stop recording first to reduce USB transfer load
        if (isRecording) {
            try { captureVideoStop() } catch (_: Exception) {}
            isRecording = false
        }
        // Stop button helper before camera close to reduce USB contention
        buttonHelper?.stop()
        buttonHelper = null
        removePreviewDataCallBack(previewDataCallback)
        // Brief pause to let in-flight USB transfers drain before destroying
        // the native UVC camera object — prevents pthread_mutex crash in libuvc
        try { Thread.sleep(150) } catch (_: InterruptedException) {}
        // Now safe to tear down
        unRegisterMultiCamera()
        super.onStop()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Close the adjust strip (phone) or panel (tablet) instead of exiting the app
        if (isPhone && isAdjustStripOpen) {
            toggleAdjustStrip()
            return
        }
        if (isSettingsPanelOpen) {
            isSettingsPanelOpen = false
            binding.controlsPanel.visibility = View.GONE
            binding.settingsIcon.setColorFilter(
                ContextCompat.getColor(this, R.color.text_secondary))
            binding.settingsButton.requestFocus()
            return
        }
        super.onBackPressed()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isPhone) {
            // Re-apply aspect ratio constraints after orientation change
            val pos = binding.viewModeSpinner.selectedItemPosition
            if (pos >= 0 && pos < aspectRatioTypes.size) {
                applyAspectType(aspectRatioTypes[pos])
            }
        }
        // Keep the scope circle correct across rotation
        applyMask()
    }

    override fun onStart() {
        super.onStart()
        // On resume (not first launch), re-register the camera client.
        // On first launch, mTextureView isn't available yet — initData()'s
        // surface listener handles that case via onSurfaceTextureAvailable.
        if (wasCameraOpen && mTextureView?.isAvailable == true) {
            writeLog("onStart: resuming — re-registering camera")
            registerMultiCamera()
        }
    }

    override fun clear() {
        try {
            // Stop button listener
            buttonHelper?.stop()
            buttonHelper = null
            removePreviewDataCallBack(previewDataCallback)
            clearLatestPreviewFrame()
            hardwareCaptureInFlight = false
            hardwareButtonCaptureInFlight = false
            hardwareCaptureStartedAtMs = 0L

            // Stop recording if active
            if (isRecording) {
                captureVideoStop()
                isRecording = false
            }
        } catch (e: Exception) {
            writeLog("Error in clear: ${e.message}")
        }
        super.clear()
    }

    private fun setupUI() {
        // Controls panel initially hidden
        binding.controlsPanel.visibility = View.GONE

        // Header button click listeners
        setupHeaderButtons()

        // Settings panel controls (data layer — used on both phone and tablet)
        setupCameraControls()
        setupImageControls()
        setupTransformControls()
        setupMaskControls()
        setupCollapsibleSections()
        setupZoomPan()

        // Phone: set up the compact adjust strip
        if (isPhone) {
            setupPhoneAdjustStrip()
        }
    }

    private fun setupHeaderButtons() {
        // Capture/Still button
        binding.captureButton.setOnClickListener {
            writeLog("Capture button pressed")
            if (isRecording) {
                writeLog("Capture button: using preview-frame still path during recording")
                triggerHardwareCapture(fromHardwareButton = false)
            } else {
                doCapture()
            }
        }

        // Record/Video button
        binding.recordButton.setOnClickListener {
            writeLog("Record button pressed")
            toggleRecording()
        }

        // Gallery button - opens media browser
        binding.galleryButton.setOnClickListener {
            writeLog("Gallery button pressed")
            val intent = Intent(this, GalleryActivity::class.java)
            // Show media from the preferred storage location
            val storageBase = getPreferredStorageBase()
            intent.putExtra(GalleryActivity.EXTRA_STORAGE_DIR, storageBase.absolutePath)
            intent.putExtra(GalleryActivity.EXTRA_STORAGE_LABEL, getStorageLabel())
            startActivity(intent)
        }

        // Settings/Adjust button - toggles panel (tablet) or strip (phone)
        binding.settingsButton.setOnClickListener {
            if (isPhone) {
                toggleAdjustStrip()
            } else {
                isSettingsPanelOpen = !isSettingsPanelOpen
                binding.controlsPanel.visibility = if (isSettingsPanelOpen) View.VISIBLE else View.GONE
            }

            // Update icon color to show active state
            val open = if (isPhone) isAdjustStripOpen else isSettingsPanelOpen
            val color = if (open) {
                ContextCompat.getColor(this, R.color.hawkeye_primary)
            } else {
                ContextCompat.getColor(this, R.color.text_secondary)
            }
            binding.settingsIcon.setColorFilter(color)

            writeLog("Settings panel visibility changed to: $open")
        }
    }

    // ========================
    // Zoom / Pan
    // ========================

    private fun setupZoomPan() {
        // Pinch-to-zoom for touchscreen devices
        scaleGestureDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    isPinching = true
                    return true
                }
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    currentZoom = (currentZoom * detector.scaleFactor).coerceIn(ZOOM_MIN, ZOOM_MAX)
                    clampPan()
                    applyZoomPan()
                    return true
                }
                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    isPinching = false
                }
            })

        // Double-tap to reset (touchscreen + USB mouse)
        gestureDetector = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    resetZoomPan()
                    return true
                }

                // Single tap meters the tapped spot. onSingleTapConfirmed only
                // fires once the double-tap window has elapsed, so it never
                // competes with double-tap-to-reset above.
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    val w = binding.cameraContainer.width.toFloat()
                    val h = binding.cameraContainer.height.toFloat()
                    if (w <= 0f || h <= 0f) return false
                    if (!isCameraOpened()) return false
                    meterAt(e.x / w, e.y / h)
                    return true
                }
            })

        // Touch/mouse handling on camera container
        binding.cameraContainer.setOnTouchListener { _, event ->
            scaleGestureDetector?.onTouchEvent(event)
            gestureDetector?.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activePointerId = event.getPointerId(0)
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isPinching && currentZoom > 1.001f) {
                        val pointerIndex = event.findPointerIndex(activePointerId)
                        if (pointerIndex >= 0) {
                            val x = event.getX(pointerIndex)
                            val y = event.getY(pointerIndex)
                            val dx = x - lastTouchX
                            val dy = y - lastTouchY

                            val viewW = binding.cameraContainer.width.toFloat()
                            val viewH = binding.cameraContainer.height.toFloat()
                            if (viewW > 0 && viewH > 0) {
                                if (isPhone) {
                                    // Phone: drag always follows finger regardless of mirror/flip
                                    currentPanX += dx / (viewW * currentZoom)
                                    currentPanY -= dy / (viewH * currentZoom)
                                } else {
                                    // Tablet: invert drag when mirrored/flipped
                                    val mirrorSign = if (binding.mirrorCheckBox.isChecked) -1f else 1f
                                    val flipSign = if (binding.flipCheckBox.isChecked) -1f else 1f
                                    currentPanX -= mirrorSign * dx / (viewW * currentZoom)
                                    currentPanY -= flipSign * dy / (viewH * currentZoom)
                                }
                                clampPan()
                                applyZoomPan()
                            }

                            lastTouchX = x
                            lastTouchY = y
                        }
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    val newIndex = event.actionIndex
                    lastTouchX = event.getX(newIndex)
                    lastTouchY = event.getY(newIndex)
                    activePointerId = event.getPointerId(newIndex)
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val upIndex = event.actionIndex
                    val upId = event.getPointerId(upIndex)
                    if (upId == activePointerId) {
                        val newIndex = if (upIndex == 0) 1 else 0
                        if (newIndex < event.pointerCount) {
                            lastTouchX = event.getX(newIndex)
                            lastTouchY = event.getY(newIndex)
                            activePointerId = event.getPointerId(newIndex)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                }
            }
            true
        }

        // USB mouse scroll wheel zoom on camera container
        binding.cameraContainer.setOnGenericMotionListener { _, event ->
            if (event.action == MotionEvent.ACTION_SCROLL) {
                val scrollY = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (scrollY != 0f) {
                    currentZoom = (currentZoom + scrollY * MOUSE_SCROLL_ZOOM_FACTOR)
                        .coerceIn(ZOOM_MIN, ZOOM_MAX)
                    clampPan()
                    applyZoomPan()
                    return@setOnGenericMotionListener true
                }
            }
            false
        }

        // Zoom toolbar button — click to toggle zoom mode, long-press to reset
        binding.zoomButton.setOnClickListener {
            if (isDragModeActive) exitDragMode()
            isZoomModeActive = !isZoomModeActive
            updateZoomDragButtonStates()
        }
        binding.zoomButton.setOnLongClickListener {
            resetZoomPan()
            true
        }

        // Drag toolbar button — click to toggle drag mode, long-press to recenter
        binding.dragButton.setOnClickListener {
            if (currentZoom <= 1.001f) return@setOnClickListener // nothing to drag
            if (isZoomModeActive) exitZoomMode()
            isDragModeActive = !isDragModeActive
            updateZoomDragButtonStates()
        }
        binding.dragButton.setOnLongClickListener {
            // Recenter pan
            currentPanX = 0f
            currentPanY = 0f
            applyZoomPan()
            true
        }

        updateZoomDragButtonStates()
    }

    private fun exitZoomMode() {
        isZoomModeActive = false
        updateZoomDragButtonStates()
    }

    private fun exitDragMode() {
        isDragModeActive = false
        updateZoomDragButtonStates()
    }

    private fun resetZoomPan() {
        currentZoom = 1.0f
        currentPanX = 0.0f
        currentPanY = 0.0f
        if (isZoomModeActive) exitZoomMode()
        if (isDragModeActive) exitDragMode()
        applyZoomPan()
    }

    private fun updateZoomDragButtonStates() {
        val activeColor = ContextCompat.getColor(this, R.color.hawkeye_primary)
        val inactiveColor = ContextCompat.getColor(this, R.color.text_secondary)

        // Zoom button
        binding.zoomIcon.setColorFilter(if (isZoomModeActive) activeColor else inactiveColor)
        binding.zoomLabel.setTextColor(if (isZoomModeActive) activeColor else inactiveColor)

        // Drag button — dimmed when zoom is 1x
        val dragAvailable = currentZoom > 1.001f
        val dragColor = when {
            isDragModeActive -> activeColor
            dragAvailable -> inactiveColor
            else -> ContextCompat.getColor(this, R.color.text_disabled)
        }
        binding.dragIcon.setColorFilter(dragColor)
        binding.dragLabel.setTextColor(dragColor)
        binding.dragButton.alpha = if (dragAvailable) 1.0f else 0.4f
    }

    private fun clampPan() {
        // Total zoom per axis = cropZoom * userZoom
        val totalZoomX = currentCropZoomX * currentZoom
        val totalZoomY = currentCropZoomY * currentZoom
        if (totalZoomX <= 1.001f && totalZoomY <= 1.001f) {
            currentPanX = 0f
            currentPanY = 0f
            return
        }
        val maxPanX = if (totalZoomX > 1f) (totalZoomX - 1f) / (2f * totalZoomX) else 0f
        val maxPanY = if (totalZoomY > 1f) (totalZoomY - 1f) / (2f * totalZoomY) else 0f
        currentPanX = currentPanX.coerceIn(-maxPanX, maxPanX)
        currentPanY = currentPanY.coerceIn(-maxPanY, maxPanY)
    }

    private fun applyZoomPan() {
        setZoomPan(currentZoom, currentPanX, currentPanY)
        showZoomOverlay()
        updateZoomDragButtonStates()
    }

    private fun showZoomOverlay() {
        if (currentZoom > 1.001f) {
            binding.zoomOverlay.text = String.format("%.1fx", currentZoom)
            binding.zoomOverlay.visibility = View.VISIBLE
            zoomOverlayHandler.removeCallbacksAndMessages(null)
            zoomOverlayHandler.postDelayed({
                binding.zoomOverlay.visibility = View.GONE
            }, ZOOM_OVERLAY_FADE_MS)
        } else {
            binding.zoomOverlay.visibility = View.GONE
            zoomOverlayHandler.removeCallbacksAndMessages(null)
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            // Back button exits active zoom/drag mode before closing panels
            if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                if (isDragModeActive) { exitDragMode(); return true }
                if (isZoomModeActive) { exitZoomMode(); return true }
            }

            // D-pad handling for zoom and drag modes
            if (isZoomModeActive && !isSettingsPanelOpen) {
                when (event.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_UP,
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        currentZoom = (currentZoom + DPAD_ZOOM_STEP).coerceIn(ZOOM_MIN, ZOOM_MAX)
                        clampPan(); applyZoomPan(); return true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                        currentZoom = (currentZoom - DPAD_ZOOM_STEP).coerceIn(ZOOM_MIN, ZOOM_MAX)
                        clampPan(); applyZoomPan(); return true
                    }
                }
            }

            if (isDragModeActive && !isSettingsPanelOpen) {
                when (event.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                        currentPanX -= DPAD_PAN_STEP
                        clampPan(); applyZoomPan(); return true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        currentPanX += DPAD_PAN_STEP
                        clampPan(); applyZoomPan(); return true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                        currentPanY -= DPAD_PAN_STEP
                        clampPan(); applyZoomPan(); return true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                        currentPanY += DPAD_PAN_STEP
                        clampPan(); applyZoomPan(); return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // Aspect ratio view options — labels and crop zoom computed from camera resolution
    data class AspectRatioType(val name: String, val ratio: String, val aspectW: Float, val aspectH: Float)
    private val aspectRatioTypes = listOf(
        AspectRatioType("1:1", "1:1", 1f, 1f),
        AspectRatioType("4:3", "4:3", 4f, 3f),
        AspectRatioType("Wide", "16:9", 16f, 9f)
    )
    private var cameraSourceW = 720  // updated when camera opens
    private var cameraSourceH = 720
    private var currentCropZoomX = 1.0f
    private var currentCropZoomY = 1.0f

    private fun buildAspectLabel(type: AspectRatioType): String {
        val cameraAspect = cameraSourceW.toFloat() / cameraSourceH.toFloat()
        val viewAspect = type.aspectW / type.aspectH
        val displayW: Int
        val displayH: Int
        if (viewAspect >= cameraAspect) {
            displayW = cameraSourceW
            displayH = (cameraSourceW / viewAspect).toInt()
        } else {
            displayH = cameraSourceH
            displayW = (cameraSourceH * viewAspect).toInt()
        }
        return "${type.name} — ${displayW}×${displayH}"
    }

    private fun calcCropZoom(type: AspectRatioType): Pair<Float, Float> {
        val cameraAspect = cameraSourceW.toFloat() / cameraSourceH.toFloat()
        val viewAspect = type.aspectW / type.aspectH
        return if (viewAspect >= cameraAspect) {
            1.0f to (viewAspect / cameraAspect)
        } else {
            (cameraAspect / viewAspect) to 1.0f
        }
    }

    private fun updateAspectLabels() {
        val adapter = binding.viewModeSpinner.adapter as? ArrayAdapter<String> ?: return
        adapter.clear()
        adapter.addAll(aspectRatioTypes.map { buildAspectLabel(it) })
        adapter.notifyDataSetChanged()
    }

    private fun setupCameraControls() {
        // Aspect ratio spinner — matching Windows HawkeyeViewerPlus style
        val adapter = ArrayAdapter(
            this,
            R.layout.spinner_item,
            aspectRatioTypes.map { buildAspectLabel(it) }.toMutableList()
        )
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        binding.viewModeSpinner.adapter = adapter

        // Restore saved selection
        val savedRatio = getSharedPreferences("image_adjustments", MODE_PRIVATE)
            .getString("aspect_ratio", "1:1") ?: "1:1"
        val savedIndex = aspectRatioTypes.indexOfFirst { it.ratio == savedRatio }
        if (savedIndex >= 0) binding.viewModeSpinner.setSelection(savedIndex)
        // Apply saved ratio immediately
        applyAspectType(aspectRatioTypes[if (savedIndex >= 0) savedIndex else 0])

        binding.viewModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val type = aspectRatioTypes[position]
                applyAspectType(type)
                getSharedPreferences("image_adjustments", MODE_PRIVATE)
                    .edit().putString("aspect_ratio", type.ratio).apply()
                writeLog("Aspect ratio changed to: ${buildAspectLabel(type)}")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Start camera button - request permission to trigger camera open
        binding.startButton.setOnClickListener {
            startCameraIfNeeded()
        }

        // Stop camera button
        binding.stopButton.setOnClickListener {
            stopCameraIfRunning()
        }
    }

    private fun startCameraIfNeeded() {
        if (isCameraActive) {
            showStatus("Camera already running")
            writeLog("Start pressed but camera already open — ignoring")
            return
        }
        // Native UVC stack needs time to release after closeCamera().
        // If Start is pressed too soon after Stop, delay the actual open.
        val elapsed = System.currentTimeMillis() - lastStopTimeMs
        val minDelay = 800L
        if (lastStopTimeMs > 0 && elapsed < minDelay) {
            val wait = minDelay - elapsed
            writeLog("Start pressed ${elapsed}ms after stop — delaying ${wait}ms for USB release")
            showStatus("Starting camera...")
            isStartingCamera = true
            binding.cameraContainer.removeCallbacks(startRetryRunnable)
            binding.cameraContainer.postDelayed(startRetryRunnable, wait)
            return
        }
        doStartCamera()
    }

    private fun doStartCamera() {
        if (isCameraActive) return  // guard against stale delayed callback
        writeLog("Start button pressed, requesting device list...")
        val devices = getDeviceList()
        if (devices.isNullOrEmpty()) {
            showStatus("No USB camera found")
            writeLog("No USB devices found")
            isStartingCamera = false
        } else {
            writeLog("Found ${devices.size} USB devices, requesting permission for first...")
            isStartingCamera = true
            requestPermission(devices[0])
            showStatus("Starting camera...")
        }
    }

    private fun stopCameraIfRunning() {
        writeLog("Stop button pressed")
        // Cancel any pending start retry and mark camera inactive immediately
        // (don't wait for async CLOSED callback)
        isStartingCamera = false
        isCameraActive = false
        lastStopTimeMs = System.currentTimeMillis()
        binding.cameraContainer.removeCallbacks(startRetryRunnable)
        try {
            if (isRecording) {
                captureVideoStop()
                isRecording = false
                updateRecordingUI(false)
            }
            closeCamera()
            wasCameraOpen = false
            showStatus("Camera stopped")
        } catch (e: Exception) {
            writeLog("Error stopping camera: ${e.message}")
        }
    }

    private fun applyAspectType(type: AspectRatioType) {
        // Change view shape
        val constraintLayout = binding.root as ConstraintLayout
        val constraintSet = ConstraintSet()
        constraintSet.clone(constraintLayout)
        constraintSet.setDimensionRatio(R.id.cameraContainer, type.ratio)
        constraintSet.applyTo(constraintLayout)

        // Force immediate layout pass on phone (container is sandwiched between bars)
        if (isPhone) {
            binding.cameraContainer.requestLayout()
        }

        // Calculate and apply crop zoom from actual camera resolution
        val (cropX, cropY) = calcCropZoom(type)
        currentCropZoomX = cropX
        currentCropZoomY = cropY
        setCropZoom(currentCropZoomX, currentCropZoomY)

        // Reset pan when aspect changes (pan limits change)
        currentPanX = 0f
        currentPanY = 0f
        applyZoomPan()
    }

    // =====================
    // Software Image Adjustments (GPU shader-based)
    // Slider 0-100 mapped to shader uniform ranges
    // =====================

    // Current shader values (kept in sync with sliders)
    private var shaderBrightness = 1.0f   // 0.0-2.0, 1.0=normal
    private var shaderContrast = 1.0f     // 0.0-2.0, 1.0=normal
    private var shaderSaturation = 1.0f   // 0.0-2.0, 1.0=normal
    private var shaderHue = 0.0f          // -PI to +PI radians, 0=normal
    private var shaderGamma = 1.0f        // 0.2-3.0, 1.0=normal
    private var shaderSharpness = 1.0f    // 0.0-2.0, 1.0=moderate (slider 50)

    private val adjustPrefs by lazy {
        getSharedPreferences("image_adjustments", MODE_PRIVATE)
    }

    private fun setupImageControls() {
        // All sliders: 50 = normal/default for each control
        setupShaderSeekBar(binding.brightnessSeekBar, binding.brightnessValue, "brightness", binding.brightnessRow, binding.brightnessLabel)
        setupShaderSeekBar(binding.contrastSeekBar, binding.contrastValue, "contrast", binding.contrastRow, binding.contrastLabel)
        setupShaderSeekBar(binding.saturationSeekBar, binding.saturationValue, "saturation", binding.saturationRow, binding.saturationLabel)
        setupShaderSeekBar(binding.hueSeekBar, binding.hueValue, "hue", binding.hueRow, binding.hueLabel)
        setupShaderSeekBar(binding.gammaSeekBar, binding.gammaValue, "gamma", binding.gammaRow, binding.gammaLabel)
        setupShaderSeekBar(binding.sharpnessSeekBar, binding.sharpnessValue, "sharpness", binding.sharpnessRow, binding.sharpnessLabel)

        // Defaults button (with confirmation)
        binding.defaultsButton.setOnClickListener {
            confirmResetDefaults()
        }

        // Restore last saved adjustments (or sync defaults to shader on first launch)
        restoreImageControls()
        // Always push current values to shader so field defaults and slider defaults match
        pushShaderAdjustments()
    }

    private fun setupShaderSeekBar(seekBar: SeekBar, valueText: android.widget.TextView, name: String,
                                    row: android.view.View, label: android.widget.TextView) {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                valueText.text = progress.toString()
                if (fromUser) {
                    applyShaderAdjustment(name, progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                saveImageControls()
            }
        })
        // Prevent D-pad left/right from moving focus when seekbar is at min/max
        seekBar.setOnKeyListener { v, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                val sb = v as SeekBar
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT && sb.progress <= 0) {
                    true // consume — don't move focus
                } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && sb.progress >= sb.max) {
                    true // consume — don't move focus
                } else false
            } else false
        }
        // Highlight row when seekbar gets D-pad focus (TV remote navigation)
        seekBar.setOnFocusChangeListener { _, hasFocus ->
            row.isSelected = hasFocus
            label.setTextColor(resources.getColor(
                if (hasFocus) R.color.hawkeye_primary else R.color.text_secondary, theme))
        }
    }

    private fun applyShaderAdjustment(name: String, sliderValue: Int) {
        // All sliders: 0-100 where 50 = no change (1.0x)
        when (name) {
            "brightness" -> shaderBrightness = sliderValue / 50.0f           // 0→0.0, 50→1.0, 100→2.0
            "contrast" -> shaderContrast = sliderValue / 50.0f               // 0→0.0, 50→1.0, 100→2.0
            "saturation" -> shaderSaturation = sliderValue / 50.0f           // 0→0.0, 50→1.0, 100→2.0
            "hue" -> shaderHue = ((sliderValue - 50) / 50.0f) * Math.PI.toFloat()  // 0→-π, 50→0, 100→+π
            "gamma" -> {
                // Piecewise: 0→0.2, 50→1.0, 100→3.0
                shaderGamma = if (sliderValue <= 50) {
                    0.2f + (sliderValue / 50.0f) * 0.8f   // 0→0.2, 50→1.0
                } else {
                    1.0f + ((sliderValue - 50) / 50.0f) * 2.0f  // 50→1.0, 100→3.0
                }
            }
            "sharpness" -> {
                // 0→0.0 (none), 50→1.0 (moderate), 100→2.0 (strong)
                shaderSharpness = sliderValue / 50.0f
            }
        }
        pushShaderAdjustments()
    }

    private fun pushShaderAdjustments() {
        setImageAdjustments(shaderBrightness, shaderContrast, shaderSaturation, shaderHue, shaderGamma, shaderSharpness)
        // Update the still capture applier so captureImageInternal produces WYSIWYG stills
        updateCaptureAdjustmentApplier()
    }

    private fun updateCaptureAdjustmentApplier() {
        val b = shaderBrightness
        val c = shaderContrast
        val s = shaderSaturation
        val g = shaderGamma
        val mirror = binding.mirrorCheckBox.isChecked
        val flip = binding.flipCheckBox.isChecked
        val needsColor = Math.abs(b - 1.0f) > 0.01f || Math.abs(c - 1.0f) > 0.01f ||
            Math.abs(s - 1.0f) > 0.01f || Math.abs(g - 1.0f) > 0.01f
        // If all defaults and no transform, remove applier
        if (!needsColor && !mirror && !flip) {
            setImageAdjustmentApplier(null)
            return
        }
        setImageAdjustmentApplier(object : com.jiangdg.ausbc.MultiCameraClient.ICamera.ImageAdjustmentApplier {
            override fun applyToFile(path: String) {
                applyStillAdjustments(path)
            }
        })
    }

    private fun saveImageControls() {
        adjustPrefs.edit()
            .putInt("brightness", binding.brightnessSeekBar.progress)
            .putInt("contrast", binding.contrastSeekBar.progress)
            .putInt("saturation", binding.saturationSeekBar.progress)
            .putInt("hue", binding.hueSeekBar.progress)
            .putInt("gamma", binding.gammaSeekBar.progress)
            .putInt("sharpness", binding.sharpnessSeekBar.progress)
            .putBoolean("mirror", binding.mirrorCheckBox.isChecked)
            .putBoolean("flip", binding.flipCheckBox.isChecked)
            .apply()
    }

    private fun restoreImageControls() {
        val hasSaved = adjustPrefs.contains("brightness")
        if (!hasSaved) return  // First launch — use XML defaults (all 50)

        val b = adjustPrefs.getInt("brightness", 50)
        val c = adjustPrefs.getInt("contrast", 50)
        val s = adjustPrefs.getInt("saturation", 50)
        val h = adjustPrefs.getInt("hue", 50)
        val g = adjustPrefs.getInt("gamma", 50)
        val sh = adjustPrefs.getInt("sharpness", 50)
        val savedMirror = adjustPrefs.getBoolean("mirror", false)
        val savedFlip = adjustPrefs.getBoolean("flip", false)

        binding.brightnessSeekBar.progress = b
        binding.contrastSeekBar.progress = c
        binding.saturationSeekBar.progress = s
        binding.hueSeekBar.progress = h
        binding.gammaSeekBar.progress = g
        binding.sharpnessSeekBar.progress = sh

        binding.brightnessValue.text = b.toString()
        binding.contrastValue.text = c.toString()
        binding.saturationValue.text = s.toString()
        binding.hueValue.text = h.toString()
        binding.gammaValue.text = g.toString()
        binding.sharpnessValue.text = sh.toString()
        binding.mirrorCheckBox.isChecked = savedMirror
        binding.flipCheckBox.isChecked = savedFlip

        // Apply restored values to shader
        applyShaderAdjustment("brightness", b)
        applyShaderAdjustment("contrast", c)
        applyShaderAdjustment("saturation", s)
        applyShaderAdjustment("hue", h)
        applyShaderAdjustment("gamma", g)
        applyShaderAdjustment("sharpness", sh)
    }

    private fun confirmResetDefaults() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Reset to Defaults")
            .setMessage("Reset all image adjustments and transforms to defaults?")
            .setPositiveButton("Reset") { _, _ ->
                resetImageControlsToDefaults()
                // Sync strip display if on phone
                if (isPhone) {
                    if (currentStripSection == StripSection.IMAGE) updateStripImageControl()
                    binding.stripMirrorCheckBox?.isChecked = false
                    binding.stripFlipCheckBox?.isChecked = false
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resetImageControlsToDefaults() {
        // Reset all sliders to 50 (1.0x / no change)
        binding.brightnessSeekBar.progress = 50
        binding.contrastSeekBar.progress = 50
        binding.saturationSeekBar.progress = 50
        binding.hueSeekBar.progress = 50
        binding.gammaSeekBar.progress = 50
        binding.sharpnessSeekBar.progress = 50

        // Reset shader values
        shaderBrightness = 1.0f
        shaderContrast = 1.0f
        shaderSaturation = 1.0f
        shaderHue = 0.0f
        shaderGamma = 1.0f
        shaderSharpness = 1.0f
        pushShaderAdjustments()

        // Update value labels
        binding.brightnessValue.text = "50"
        binding.contrastValue.text = "50"
        binding.saturationValue.text = "50"
        binding.hueValue.text = "50"
        binding.gammaValue.text = "50"
        binding.sharpnessValue.text = "50"

        // Save defaults
        saveImageControls()

        showStatus("Reset to defaults")
    }

    private fun setupTransformControls() {
        // Mirror checkbox (horizontal flip)
        binding.mirrorCheckBox.setOnCheckedChangeListener { _, isChecked ->
            writeLog("Mirror: $isChecked")
            saveImageControls()
            updateTransform()
            updateCaptureAdjustmentApplier()
        }

        // Flip checkbox (vertical flip)
        binding.flipCheckBox.setOnCheckedChangeListener { _, isChecked ->
            writeLog("Flip: $isChecked")
            saveImageControls()
            updateTransform()
            updateCaptureAdjustmentApplier()
        }
    }

    private fun updateTransform() {
        if (!isCameraOpened()) return
        val mirror = binding.mirrorCheckBox.isChecked
        val flip = binding.flipCheckBox.isChecked
        val rotateType = when {
            mirror && flip -> RotateType.ANGLE_180      // both = 180° rotation
            mirror -> RotateType.FLIP_LEFT_RIGHT         // horizontal mirror
            flip -> RotateType.FLIP_UP_DOWN              // vertical flip
            else -> RotateType.ANGLE_0                   // no transform
        }
        writeLog("Setting transform: $rotateType (mirror=$mirror, flip=$flip)")
        setRotateType(rotateType)
    }

    // ==================== Scope mask + luminance metering ====================

    /**
     * Wires the scope + metering controls. These exist twice: in the tablet
     * settings panel and in the phone adjust strip (the phone's Settings button
     * opens the strip, not the panel, so the strip copy is the one that matters
     * on a handset). Both drive the same state through the set* helpers below,
     * and syncMaskUi pushes state back to whichever views are present.
     */
    private fun setupMaskControls() {
        restoreMaskSettings()

        // --- Scope mode toggle ---
        val scopeToggle = { _: android.widget.CompoundButton, checked: Boolean ->
            if (!maskUiSyncing) setMaskEnabled(checked)
        }
        binding.maskModeCheckBox.setOnCheckedChangeListener(scopeToggle)
        binding.stripMaskCheckBox?.setOnCheckedChangeListener(scopeToggle)

        // --- Mask radius. SeekBar has no android:min below API 26, so the bar is
        // 0..260 and MASK_RADIUS_MIN is added to get the 100..360 slider value. ---
        val radiusListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || maskUiSyncing) return
                setMaskRadius(progress + MASK_RADIUS_MIN)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) { saveMaskSettings() }
        }
        binding.maskRadiusSeekBar.setOnSeekBarChangeListener(radiusListener)
        binding.stripMaskRadiusSeekBar?.setOnSeekBarChangeListener(radiusListener)

        // --- Metering mode ---
        val meterAdapter = {
            ArrayAdapter(this, R.layout.spinner_item, METER_LABELS)
                .also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }
        }
        val meterListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (maskUiSyncing || position !in METER_MODES.indices) return
                setMeterMode(METER_MODES[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.meterModeSpinner.adapter = meterAdapter()
        binding.meterModeSpinner.onItemSelectedListener = meterListener
        binding.stripMeterSpinner?.adapter = meterAdapter()
        binding.stripMeterSpinner?.onItemSelectedListener = meterListener

        // --- Reset exposure ---
        binding.resetExposureButton.setOnClickListener { resetExposure() }
        binding.stripResetExposureButton?.setOnClickListener { resetExposure() }

        // The container is resized by aspect-ratio changes as well as rotation;
        // re-query the outline whenever its bounds move so the circle follows.
        binding.cameraContainer.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (maskOn && (r - l != or - ol || b - t != ob - ot)) {
                binding.cameraContainer.invalidateOutline()
            }
        }

        syncMaskUi()
        applyMask()
    }

    private fun setMaskEnabled(on: Boolean) {
        maskOn = on
        applyMask()
        syncMaskUi()
        saveMaskSettings()
        writeLog("Mask ${if (on) "on" else "off"} (radius=$maskRadiusRef)")
    }

    private fun setMaskRadius(value: Int) {
        maskRadiusRef = value.coerceIn(MASK_RADIUS_MIN, MASK_RADIUS_MAX)
        applyMask()
        syncMaskUi()
    }

    private fun setMeterMode(mode: String) {
        meterMode = mode
        meterFrameCounter = 0
        syncMaskUi()
        writeLog("Metering mode -> $mode (target=$targetLuma)")
        if (mode == METER_OFF) {
            updateMeterStatus("Metering off — tap the preview to meter a spot")
        }
    }

    /** Pushes current scope/metering state into both UI copies without re-entering listeners. */
    private fun syncMaskUi() {
        maskUiSyncing = true
        try {
            binding.maskModeCheckBox.isChecked = maskOn
            binding.stripMaskCheckBox?.isChecked = maskOn

            val progress = maskRadiusRef - MASK_RADIUS_MIN
            binding.maskRadiusSeekBar.progress = progress
            binding.stripMaskRadiusSeekBar?.progress = progress
            binding.maskRadiusValue.text = maskRadiusRef.toString()
            binding.stripMaskRadiusValue?.text = maskRadiusRef.toString()

            val index = METER_MODES.indexOf(meterMode).coerceAtLeast(0)
            if (binding.meterModeSpinner.selectedItemPosition != index) {
                binding.meterModeSpinner.setSelection(index)
            }
            binding.stripMeterSpinner?.let {
                if (it.selectedItemPosition != index) it.setSelection(index)
            }
        } finally {
            maskUiSyncing = false
        }
    }

    /**
     * Applies (or clears) the circular clip on the preview container.
     *
     * Clipping the container rather than the TextureView is deliberate: zoom,
     * pan, crop and mirror/flip are all GL-side in this app (see base_vertex.glsl),
     * so the container is never itself transformed. The circle therefore stays
     * put while the image zooms and pans underneath it, like a real scope.
     * UI thread only.
     */
    private fun applyMask() {
        val container = binding.cameraContainer
        if (!maskOn) {
            container.clipToOutline = false
            container.outlineProvider = ViewOutlineProvider.BACKGROUND
            container.invalidateOutline()
            return
        }
        container.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val w = view.width
                val h = view.height
                if (w <= 0 || h <= 0) {
                    outline.setRect(0, 0, w, h)
                    return
                }
                // Radius scales off the shorter side so the circle always fits.
                val r = (min(w, h) * (maskRadiusRef / MASK_REF_HEIGHT)).toInt()
                    .coerceIn(1, min(w, h) / 2)
                val cx = w / 2
                val cy = h / 2
                outline.setOval(cx - r, cy - r, cx + r, cy + r)
            }
        }
        container.clipToOutline = true
        container.invalidateOutline()
    }

    private fun saveMaskSettings() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(PREF_MASK_ON, maskOn)
            .putInt(PREF_MASK_RADIUS, maskRadiusRef)
            .apply()
    }

    private fun restoreMaskSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        maskOn = prefs.getBoolean(PREF_MASK_ON, false)
        maskRadiusRef = prefs.getInt(PREF_MASK_RADIUS, MASK_RADIUS_DEFAULT)
            .coerceIn(MASK_RADIUS_MIN, MASK_RADIUS_MAX)
    }

    /**
     * Point spot metering at a tap.
     *
     * (fx, fy) are fractions of the preview container. The RGBA preview buffer we
     * meter is the output of the capture render pass, which already has zoom, pan,
     * crop and mirror/flip baked in — it is framed exactly like what is on screen.
     * So the tap fraction maps straight through with no inverse transform, and the
     * only correction needed is the glReadPixels bottom-up row order.
     */
    private fun meterAt(fx: Float, fy: Float) {
        meterX = fx.coerceIn(0f, 1f)
        meterY = fy.coerceIn(0f, 1f)
        meterMode = METER_SPOT
        meterFrameCounter = 0
        meterDiagPending = true
        rawSpotWarningIssued = false
        // Keep both spinner copies in sync when metering is driven by a tap.
        syncMaskUi()
        writeLog("meterAt x=%.3f y=%.3f".format(meterX, meterY))
        showMeterReticle(fx, fy)
    }

    /** Brief visual confirmation of where metering was pointed. */
    private fun showMeterReticle(fx: Float, fy: Float) {
        val container = binding.cameraContainer
        if (container.width <= 0 || container.height <= 0) return
        binding.zoomOverlay.text = "meter %d%%,%d%%".format((fx * 100).toInt(), (fy * 100).toInt())
        binding.zoomOverlay.visibility = View.VISIBLE
        zoomOverlayHandler.removeCallbacksAndMessages(null)
        zoomOverlayHandler.postDelayed({
            binding.zoomOverlay.visibility = View.GONE
        }, ZOOM_OVERLAY_FADE_MS)
    }

    /**
     * One metering pass over a preview frame. Called on the render thread for
     * every frame, so it throttles hard and bails cheaply when metering is off.
     */
    private fun meterFrame(
        data: ByteArray,
        width: Int,
        height: Int,
        format: IPreviewDataCallBack.DataFormat
    ) {
        if (meterMode == METER_OFF) return
        if (width <= 0 || height <= 0) return
        if (++meterFrameCounter % METER_EVERY_N_FRAMES != 0) return
        if (meterBusy) return
        meterBusy = true
        try {
            if (meterDiagPending && format == IPreviewDataCallBack.DataFormat.RGBA) {
                meterDiagPending = false
                val asIs = meanLumaRgba(data, width, height, flipRows = false)
                val flipped = meanLumaRgba(data, width, height, flipRows = true)
                writeLog(
                    "meter Y-order probe: buffer=${width}x$height  " +
                        "rows-as-is=$asIs  rows-flipped=$flipped  " +
                        "(using ${if (RGBA_BUFFER_Y_FLIPPED) "flipped" else "as-is"}; " +
                        "tapped point should read as the brighter/darker one you aimed at)"
                )
            }
            val mean = when (format) {
                // RGBA comes out of the capture render pass, so it already has
                // zoom, pan, crop and mirror/flip applied — it is framed exactly
                // like the screen and the tap fraction maps straight through.
                IPreviewDataCallBack.DataFormat.RGBA ->
                    meanLumaRgba(data, width, height, RGBA_BUFFER_Y_FLIPPED)
                // NV21 is the raw frame from libuvc, upstream of all GL transforms.
                // Centre and edge regions are defined on the frame itself so they
                // stay correct, but a tapped spot is only in the right place while
                // zoom/pan/mirror are at their defaults. Flag it rather than
                // silently metering the wrong patch.
                IPreviewDataCallBack.DataFormat.NV21 -> {
                    warnIfRawSpotMisaligned()
                    meanLumaNv21(data, width, height)
                }
                else -> -1
            }
            if (mean >= 0) stepBrightness(mean)
        } catch (e: Exception) {
            writeLog("meterFrame: EXCEPTION - ${e.message}")
        } finally {
            meterBusy = false
        }
    }

    /**
     * Warns once per tap when spot metering a raw frame while a GL transform is
     * active, since the tapped point and the raw frame no longer line up.
     */
    private fun warnIfRawSpotMisaligned() {
        if (meterMode != METER_SPOT || rawSpotWarningIssued) return
        val transformed = currentZoom > 1.001f ||
            abs(currentPanX) > 0.001f ||
            abs(currentPanY) > 0.001f ||
            binding.mirrorCheckBox.isChecked ||
            binding.flipCheckBox.isChecked
        if (!transformed) return
        rawSpotWarningIssued = true
        writeLog(
            "metering: spot on a raw NV21 frame with zoom/pan/mirror active — " +
                "the tapped point is offset from the frame. Reset zoom/pan " +
                "(double-tap the preview) or use centre/edge mode instead."
        )
    }

    /**
     * Mean luminance over the active metering region of an RGBA buffer.
     * Uses Rec.601 luma weights on subsampled pixels.
     */
    private fun meanLumaRgba(
        data: ByteArray,
        width: Int,
        height: Int,
        flipRows: Boolean
    ): Int {
        if (data.size < width * height * 4) return -1
        var sum = 0L
        var count = 0
        val step = LUMA_SUBSAMPLE

        forEachRegionPixel(width, height, step) { x, y ->
            // glReadPixels row 0 is the bottom of the image.
            val row = if (flipRows) height - 1 - y else y
            val i = (row * width + x) * 4
            val r = data[i].toInt() and 0xFF
            val g = data[i + 1].toInt() and 0xFF
            val b = data[i + 2].toInt() and 0xFF
            sum += (r * 299 + g * 587 + b * 114) / 1000
            count++
        }
        return if (count > 0) (sum / count).toInt() else -1
    }

    /**
     * Mean luminance over the active metering region of an NV21 buffer.
     * The Y plane is the first width*height bytes. Kept for the raw-preview
     * path (setRawPreviewData(true)), which this app does not currently use.
     */
    private fun meanLumaNv21(data: ByteArray, width: Int, height: Int): Int {
        if (data.size < width * height) return -1
        var sum = 0L
        var count = 0
        forEachRegionPixel(width, height, LUMA_SUBSAMPLE) { x, y ->
            sum += (data[y * width + x].toInt() and 0xFF)
            count++
        }
        return if (count > 0) (sum / count).toInt() else -1
    }

    /**
     * Walks the pixels of the active metering region, in display orientation
     * (y = 0 is the top of the image as seen on screen).
     */
    private inline fun forEachRegionPixel(
        width: Int,
        height: Int,
        step: Int,
        body: (x: Int, y: Int) -> Unit
    ) {
        val shorter = min(width, height)
        when (meterMode) {
            METER_SPOT -> {
                val cx = (meterX * width).toInt()
                val cy = (meterY * height).toInt()
                val r = (meterWindow * shorter).toInt().coerceAtLeast(1)
                val x0 = (cx - r).coerceAtLeast(0)
                val x1 = (cx + r).coerceAtMost(width)
                val y0 = (cy - r).coerceAtLeast(0)
                val y1 = (cy + r).coerceAtMost(height)
                var y = y0
                while (y < y1) {
                    var x = x0
                    while (x < x1) { body(x, y); x += step }
                    y += step
                }
            }
            METER_CENTER -> {
                // Central 30% disc.
                val cx = width / 2
                val cy = height / 2
                val r = (0.30f * shorter).toInt()
                val r2 = r.toLong() * r
                var y = (cy - r).coerceAtLeast(0)
                val yEnd = (cy + r).coerceAtMost(height)
                while (y < yEnd) {
                    var x = (cx - r).coerceAtLeast(0)
                    val xEnd = (cx + r).coerceAtMost(width)
                    while (x < xEnd) {
                        val dx = (x - cx).toLong()
                        val dy = (y - cy).toLong()
                        if (dx * dx + dy * dy <= r2) body(x, y)
                        x += step
                    }
                    y += step
                }
            }
            METER_EDGE -> {
                // Annulus from 70% to 100% of the mask circle — this is the
                // mirror-tube case, where the glare sits on the tube's ring.
                val cx = width / 2
                val cy = height / 2
                val rOut = (shorter * (maskRadiusRef / MASK_REF_HEIGHT)).toInt()
                    .coerceIn(2, shorter / 2)
                val rIn = (0.70f * rOut).toInt()
                val rOut2 = rOut.toLong() * rOut
                val rIn2 = rIn.toLong() * rIn
                var y = (cy - rOut).coerceAtLeast(0)
                val yEnd = (cy + rOut).coerceAtMost(height)
                while (y < yEnd) {
                    var x = (cx - rOut).coerceAtLeast(0)
                    val xEnd = (cx + rOut).coerceAtMost(width)
                    while (x < xEnd) {
                        val dx = (x - cx).toLong()
                        val dy = (y - cy).toLong()
                        val d2 = dx * dx + dy * dy
                        if (d2 in rIn2..rOut2) body(x, y)
                        x += step
                    }
                    y += step
                }
            }
        }
    }

    /**
     * One proportional control step on the camera's brightness control.
     *
     * Deliberately drives the camera rather than the shader brightness slider:
     * blown-out glare is clipped, and no amount of post-processing recovers
     * detail that the sensor never captured.
     */
    private fun stepBrightness(meanLuma: Int) {
        val camera = getCurrentCamera() as? CameraUVC
        if (camera == null) {
            logMeterThrottled("stepBrightness: no CameraUVC — cannot adjust")
            return
        }
        val error = targetLuma - meanLuma          // positive means too dark
        if (abs(error) <= LUMA_DEADBAND) {
            postMeterStatus(meanLuma, null, settled = true)
            return
        }
        try {
            val current = camera.getBrightness()
            if (current == null) {
                logMeterThrottled("stepBrightness: getBrightness() null — control unavailable")
                return
            }
            var delta = (error / 8).coerceIn(-METER_MAX_STEP, METER_MAX_STEP)
            if (delta == 0) delta = if (error > 0) 1 else -1
            val next = (current + delta).coerceIn(BRIGHTNESS_MIN, BRIGHTNESS_MAX)
            if (next == current) {
                // Either the control is railed, or its range came back as zero and
                // getBrightness() is pinned at 0 — in both cases nothing will move.
                logMeterThrottled(
                    "stepBrightness: STUCK luma=$meanLuma err=$error " +
                        "brightness=$current delta=$delta -> next=$next (no call made). " +
                        "Control is railed or unsupported; see the exposure probe above."
                )
                postMeterStatus(meanLuma, current, settled = false)
                return
            }
            camera.setBrightness(next)
            val readBack = camera.getBrightness()
            logMeterThrottled(
                "stepBrightness: luma=$meanLuma err=$error " +
                    "brightness $current -> $next, read back $readBack" +
                    if (readBack != next) "  << DID NOT TAKE" else ""
            )
            postMeterStatus(meanLuma, next, settled = false)
        } catch (e: Exception) {
            writeLog("stepBrightness: EXCEPTION - ${e.message}")
        }
    }

    /** Rate-limits metering diagnostics so the loop cannot flood the log file. */
    private fun logMeterThrottled(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastMeterLogMs < METER_LOG_INTERVAL_MS) return
        lastMeterLogMs = now
        writeLog(message)
    }

    /**
     * One-shot probe of the camera's exposure-related PU controls.
     *
     * A UVC camera that does not support a control reports a zero min/max range,
     * at which point setBrightness() silently no-ops and getBrightness() always
     * returns 0 — indistinguishable from a working control sitting at 0 unless
     * you write a value and read it back. This does exactly that, then restores
     * whatever was there, so one launch says whether the loop can work at all.
     */
    private fun probeExposureControls() {
        val camera = getCurrentCamera() as? CameraUVC ?: run {
            writeLog("exposure probe: no CameraUVC available")
            return
        }
        try {
            val startBrightness = camera.getBrightness()
            val startContrast = camera.getContrast()
            writeLog("exposure probe: initial brightness=$startBrightness contrast=$startContrast")

            for (probe in intArrayOf(20, 80)) {
                camera.setBrightness(probe)
                val got = camera.getBrightness()
                writeLog(
                    "exposure probe: brightness set=$probe read=$got " +
                        if (got == probe) "OK" else "MISMATCH (control likely unsupported)"
                )
            }
            camera.setBrightness(startBrightness ?: BRIGHTNESS_DEFAULT)

            for (probe in intArrayOf(20, 80)) {
                camera.setContrast(probe)
                val got = camera.getContrast()
                writeLog(
                    "exposure probe: contrast set=$probe read=$got " +
                        if (got == probe) "OK" else "MISMATCH (control likely unsupported)"
                )
            }
            camera.setContrast(startContrast ?: BRIGHTNESS_DEFAULT)
            writeLog("exposure probe: restored brightness=$startBrightness contrast=$startContrast")
        } catch (e: Exception) {
            writeLog("exposure probe: EXCEPTION - ${e.message}")
        }
    }

    private fun resetExposure() {
        meterMode = METER_OFF
        syncMaskUi()
        try {
            (getCurrentCamera() as? CameraUVC)?.setBrightness(BRIGHTNESS_DEFAULT)
            writeLog("resetExposure: brightness -> $BRIGHTNESS_DEFAULT, metering off")
        } catch (e: Exception) {
            writeLog("resetExposure: EXCEPTION - ${e.message}")
        }
        updateMeterStatus("Exposure reset — metering off")
    }

    private fun postMeterStatus(meanLuma: Int, brightness: Int?, settled: Boolean) {
        val text = buildString {
            append("luma $meanLuma / target $targetLuma")
            if (brightness != null) append("  ·  brightness $brightness")
            append(if (settled) "  ·  settled" else "  ·  adjusting")
            append("  ·  $meterMode")
        }
        runOnUiThread { updateMeterStatus(text) }
    }

    private fun updateMeterStatus(text: String) {
        binding.meterStatusText.text = text
        binding.stripMeterStatusText?.text = text
    }

    private fun setupCollapsibleSections() {
        // Camera section
        binding.cameraSectionHeader.setOnClickListener {
            toggleSection(binding.cameraSectionContent, binding.cameraSectionChevron)
        }

        // Image section
        binding.imageSectionHeader.setOnClickListener {
            toggleSection(binding.imageSectionContent, binding.imageSectionChevron)
        }

        // Transform section
        binding.transformSectionHeader.setOnClickListener {
            toggleSection(binding.transformSectionContent, binding.transformSectionChevron)
        }
    }

    private fun toggleSection(content: View, chevron: ImageView) {
        if (content.visibility == View.VISIBLE) {
            content.visibility = View.GONE
            chevron.rotation = -90f
        } else {
            content.visibility = View.VISIBLE
            chevron.rotation = 0f
        }
    }

    private fun updateRecordingUI(recording: Boolean) {
        runOnUiThread {
            val color = if (recording) {
                ContextCompat.getColor(this, R.color.hawkeye_primary)
            } else {
                ContextCompat.getColor(this, R.color.text_secondary)
            }
            binding.recordIcon.setColorFilter(color)
            binding.recordLabel.text = if (recording) "STOP" else getString(R.string.btn_video)
            binding.recordLabel.setTextColor(color)
        }
    }

    // =====================
    // Permissions
    // =====================

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
        }

        // Storage permissions for Android 12 and below
        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.S_V2) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        // Notification permission for Android 13+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_PERMISSION)
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
                val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (granted) {
                    writeLog("All permissions granted")
                    showStatus("Permissions granted")
                } else {
                    writeLog("Some permissions denied")
                    showStatus("Some permissions denied - app may not work correctly")
                    Toast.makeText(this, "Please grant all permissions for full functionality", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // =====================
    // Capture & Recording
    // =====================

    /**
     * Returns the best available storage base directory.
     * If a removable USB drive is available, uses it; otherwise uses internal storage.
     * getExternalFilesDirs(null) returns [internal, usb1, usb2, ...].
     */
    private fun getPreferredStorageBase(): File {
        val dirs = getExternalFilesDirs(null)
        // dirs[0] is always internal; dirs[1+] are removable (USB drives, SD cards)
        // Check for a writable removable location
        if (dirs.size > 1) {
            for (i in 1 until dirs.size) {
                val dir = dirs[i]
                if (dir != null && dir.canWrite()) {
                    writeLog("Using removable storage: ${dir.absolutePath}")
                    return dir
                }
            }
        }
        return getExternalFilesDir(null) ?: filesDir
    }

    /** Check if we're currently saving to removable (USB) storage */
    private fun isUsingRemovableStorage(): Boolean {
        val dirs = getExternalFilesDirs(null)
        if (dirs.size > 1) {
            for (i in 1 until dirs.size) {
                val dir = dirs[i]
                if (dir != null && dir.canWrite()) return true
            }
        }
        return false
    }

    /** Label for the current storage location */
    private fun getStorageLabel(): String {
        return if (isUsingRemovableStorage()) "USB" else "Internal"
    }

    private fun getAppPicturesDir(): File {
        val dir = File(getPreferredStorageBase(), "Pictures")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getAppMoviesDir(): File {
        val dir = File(getPreferredStorageBase(), "Movies")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Get the internal storage base (always internal, for gallery browsing) */
    private fun getInternalStorageBase(): File {
        return getExternalFilesDir(null) ?: filesDir
    }

    /**
     * Capture a still image. Named doCapture() to avoid shadowing
     * the parent CameraActivity.captureImage(callback, path) method.
     */
private fun triggerHardwareCapture(fromHardwareButton: Boolean) {
    writeLog("triggerHardwareCapture: scheduled")
    hardwareCaptureInFlight = true
    hardwareButtonCaptureInFlight = fromHardwareButton
    hardwareCaptureStartedAtMs = System.currentTimeMillis()
    showHardwareCaptureTriggeredFeedback()
    binding.captureButton.postDelayed({
        captureHardwareStill(attempt = 0)
        }, HARDWARE_CAPTURE_INITIAL_DELAY_MS)
    }

    private fun captureHardwareStill(attempt: Int) {
        val ready = isCameraOpened()
        writeLog("triggerHardwareCapture: attempt=${attempt + 1} ready=$ready")
        // Try TextureView bitmap first — it already has GL shader adjustments baked in
        // so brightness/contrast/etc. match the preview exactly (no software re-processing)
        if (capturePreviewBitmap(announceStart = false)) {
            return
        }
        // Fallback to camera capture with software adjustments
        if (attempt + 1 < HARDWARE_CAPTURE_MAX_ATTEMPTS) {
            binding.captureButton.postDelayed({
                captureHardwareStill(attempt + 1)
            }, HARDWARE_CAPTURE_RETRY_DELAY_MS)
            return
        }
        doCapture(skipReadyCheck = true)
    }

    private fun captureCachedPreviewFrame(announceStart: Boolean = true): Boolean {
        val bytes = latestPreviewBytes
        val width = latestPreviewWidth
        val height = latestPreviewHeight
        val formatName = latestPreviewFormatName
        val ageMs = System.currentTimeMillis() - latestPreviewTimestampMs
        if (bytes == null || width <= 0 || height <= 0 || ageMs > MAX_PREVIEW_FRAME_AGE_MS) {
            writeLog("captureCachedPreviewFrame: no fresh preview frame available (format=$formatName age=${ageMs}ms)")
            return false
        }

        val imagePath = File(
            getAppPicturesDir(),
            "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        ).absolutePath

        return try {
            writeLog("captureCachedPreviewFrame: saving cached $formatName frame to $imagePath")
            if (announceStart) {
                showStatus("Capturing...")
                flashCaptureIcon()
            }

            savePreviewFrameAsJpeg(bytes, width, height, formatName, imagePath)
            val file = File(imagePath)
            if (!file.exists() || file.length() < MIN_VALID_HARDWARE_CAPTURE_BYTES) {
                writeLog("captureCachedPreviewFrame: image too small (${file.length()} bytes)")
                file.delete()
                return false
            }
            applyStillAdjustments(imagePath)
            writeLog("captureCachedPreviewFrame: onComplete - path=$imagePath")
            showStillCapturedFeedback()
            notifyMediaScannerAsync(imagePath)
            true
        } catch (e: Exception) {
            writeLog("captureCachedPreviewFrame: EXCEPTION - ${e.message}")
            writeLog("captureCachedPreviewFrame: stack - ${e.stackTraceToString()}")
            false
        }
    }

    private fun savePreviewFrameAsJpeg(
        bytes: ByteArray,
        width: Int,
        height: Int,
        formatName: String,
        imagePath: String
    ) {
        when (formatName.uppercase(Locale.US)) {
            "RGBA" -> {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
                    FileOutputStream(imagePath).use { output ->
                        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)) {
                            throw IllegalStateException("Bitmap compression failed")
                        }
                    }
                } finally {
                    bitmap.recycle()
                }
            }
            "NV21" -> saveYuvPreviewFrame(bytes, width, height, ImageFormat.NV21, imagePath)
            "YUY2", "YUYV" -> saveYuvPreviewFrame(bytes, width, height, ImageFormat.YUY2, imagePath)
            "MJPEG", "JPEG" -> {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw IllegalStateException("Bitmap decode failed for $formatName")
                try {
                    FileOutputStream(imagePath).use { output ->
                        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)) {
                            throw IllegalStateException("Bitmap compression failed")
                        }
                    }
                } finally {
                    bitmap.recycle()
                }
            }
            else -> throw IllegalStateException("Unsupported preview format $formatName")
        }
    }

    private fun saveYuvPreviewFrame(
        bytes: ByteArray,
        width: Int,
        height: Int,
        imageFormat: Int,
        imagePath: String
    ) {
        val yuvImage = YuvImage(bytes, imageFormat, width, height, null)
        FileOutputStream(imagePath).use { output ->
            if (!yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, output)) {
                throw IllegalStateException("YUV compression failed")
            }
        }
    }

    private fun capturePreviewBitmap(announceStart: Boolean = true): Boolean {
        val bitmap = mTextureView?.bitmap
        if (bitmap == null) {
            writeLog("capturePreviewBitmap: texture view bitmap unavailable")
            return false
        }

        val imagePath = File(
            getAppPicturesDir(),
            "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        ).absolutePath

        return try {
            writeLog("capturePreviewBitmap: saving to $imagePath")
            if (announceStart) {
                showStatus("Capturing...")
                flashCaptureIcon()
            }

            // TextureView bitmap already has GL shader adjustments AND transforms
            // (mirror/flip/rotation) baked in — true WYSIWYG, no further processing needed

            FileOutputStream(imagePath).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)) {
                    throw IllegalStateException("Bitmap compression failed")
                }
            }
            bitmap.recycle()
            val file = File(imagePath)
            if (!file.exists() || file.length() < MIN_VALID_HARDWARE_CAPTURE_BYTES) {
                writeLog("capturePreviewBitmap: image too small (${file.length()} bytes), retrying")
                file.delete()
                return false
            }
            writeLog("capturePreviewBitmap: onComplete - path=$imagePath")
            showStillCapturedFeedback()
            notifyMediaScannerAsync(imagePath)
            true
        } catch (e: Exception) {
            bitmap.recycle()
            writeLog("capturePreviewBitmap: EXCEPTION - ${e.message}")
            writeLog("capturePreviewBitmap: stack - ${e.stackTraceToString()}")
            showStatus("Capture error: ${e.message}")
            false
        }
    }

    /** Apply current adjustments + mirror/flip to a saved JPEG file (WYSIWYG) */
    private fun applyStillAdjustments(path: String) {
        val b = shaderBrightness
        val c = shaderContrast
        val s = shaderSaturation
        val g = shaderGamma
        val mirror = binding.mirrorCheckBox.isChecked
        val flip = binding.flipCheckBox.isChecked
        val needsColor = Math.abs(b - 1.0f) > 0.01f || Math.abs(c - 1.0f) > 0.01f ||
            Math.abs(s - 1.0f) > 0.01f || Math.abs(g - 1.0f) > 0.01f
        if (!needsColor && !mirror && !flip) return

        try {
            val file = java.io.File(path)
            if (!file.exists()) return
            val original = android.graphics.BitmapFactory.decodeFile(path) ?: return
            val w = original.width
            val h = original.height
            // Apply mirror/flip transform
            var bitmap = if (mirror || flip) {
                val matrix = android.graphics.Matrix()
                matrix.setScale(if (mirror) -1f else 1f, if (flip) -1f else 1f, w / 2f, h / 2f)
                val transformed = android.graphics.Bitmap.createBitmap(original, 0, 0, w, h, matrix, true)
                original.recycle()
                transformed
            } else original
            if (!needsColor) {
                // Only transform, no color adjustments
                java.io.FileOutputStream(file).use { fos ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, fos)
                }
                bitmap.recycle()
                writeLog("applyStillAdjustments: mirror=$mirror flip=$flip applied to $path")
                return
            }
            val mutableBitmap = if (bitmap.isMutable) bitmap
                else bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, true).also { bitmap.recycle() }
            val pixels = IntArray(w * h)
            mutableBitmap.getPixels(pixels, 0, w, 0, 0, w, h)

            val gammaLut = if (Math.abs(g - 1.0f) > 0.01f) {
                val invG = 1.0f / g
                IntArray(256) { i -> (Math.pow(i / 255.0, invG.toDouble()) * 255.0).toInt().coerceIn(0, 255) }
            } else null

            for (i in pixels.indices) {
                val px = pixels[i]
                val a = (px shr 24) and 0xFF
                var rf = ((px shr 16) and 0xFF) * b / 255.0f
                var gf = ((px shr 8) and 0xFF) * b / 255.0f
                var bf = (px and 0xFF) * b / 255.0f
                rf = (rf - 0.5f) * c + 0.5f
                gf = (gf - 0.5f) * c + 0.5f
                bf = (bf - 0.5f) * c + 0.5f
                val luma = 0.2126f * rf + 0.7152f * gf + 0.0722f * bf
                rf = luma + (rf - luma) * s
                gf = luma + (gf - luma) * s
                bf = luma + (bf - luma) * s
                var ri = (rf * 255.0f).toInt().coerceIn(0, 255)
                var gi = (gf * 255.0f).toInt().coerceIn(0, 255)
                var bi = (bf * 255.0f).toInt().coerceIn(0, 255)
                if (gammaLut != null) { ri = gammaLut[ri]; gi = gammaLut[gi]; bi = gammaLut[bi] }
                pixels[i] = (a shl 24) or (ri shl 16) or (gi shl 8) or bi
            }
            mutableBitmap.setPixels(pixels, 0, w, 0, 0, w, h)
            java.io.FileOutputStream(file).use { fos ->
                mutableBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, fos)
            }
            mutableBitmap.recycle()
            writeLog("applyStillAdjustments: b=$b c=$c s=$s g=$g mirror=$mirror flip=$flip applied to $path")
        } catch (e: Exception) {
            writeLog("applyStillAdjustments: EXCEPTION - ${e.message}")
        }
    }

    private fun clearLatestPreviewFrame() {
        latestPreviewBytes = null
        latestPreviewWidth = 0
        latestPreviewHeight = 0
        latestPreviewFormatName = ""
        latestPreviewTimestampMs = 0L
        hasLoggedPreviewFrame = false
    }

    private fun flashCaptureIcon() {
        binding.captureIcon.setColorFilter(ContextCompat.getColor(this, R.color.hawkeye_primary))
        binding.captureIcon.postDelayed({
            binding.captureIcon.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary))
        }, CAPTURE_FEEDBACK_DURATION_MS)
    }

    private fun showHardwareCaptureTriggeredFeedback() {
        if (!hasWindowFocus()) {
            writeLog("showHardwareCaptureTriggeredFeedback: app not focused")
            return
        }
        showStatus("Capturing image...")
        flashCaptureIcon()
    }

private fun showStillCapturedFeedback() {
    val captureAgeMs = if (hardwareButtonCaptureInFlight) {
        System.currentTimeMillis() - hardwareCaptureStartedAtMs
    } else {
        null
    }
    finishHardwareCapture(success = true, rearmReason = "capture-complete")
    if (!hasWindowFocus()) {
        writeLog("showStillCapturedFeedback: app not focused, skipping visible feedback")
        return
    }
    if (captureAgeMs != null && captureAgeMs > MAX_VISIBLE_HARDWARE_CAPTURE_AGE_MS) {
        writeLog("showStillCapturedFeedback: skipping stale hardware feedback age=${captureAgeMs}ms")
        return
    }
    showStatus("Image captured")
    }

    private fun notifyMediaScannerAsync(path: String) {
        Thread {
            notifyMediaScanner(path)
        }.apply {
            name = "MediaScannerNotify"
            isDaemon = true
            start()
        }
    }

    private fun doCapture(skipReadyCheck: Boolean = false) {
        if (!skipReadyCheck && !isCameraOpened()) {
            writeLog("doCapture: camera not open")
            showStatus("Camera not ready")
            finishHardwareCapture(success = false, rearmReason = "camera-not-open")
            return
        }

        // Prefer TextureView bitmap — already has GL shader adjustments baked in (WYSIWYG)
        if (capturePreviewBitmap(announceStart = true)) {
            writeLog("doCapture: used TextureView bitmap (WYSIWYG)")
            return
        }

        // Fallback to raw camera capture path
        writeLog("doCapture: TextureView bitmap unavailable, falling back to raw capture")
        try {
            val captureDir = getAppPicturesDir()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val imagePath = File(captureDir, "IMG_$timestamp.jpg").absolutePath

            writeLog("doCapture: saving to $imagePath")
            showStatus("Capturing...")

            flashCaptureIcon()

            captureImage(object : ICaptureCallBack {
                override fun onBegin() {
                    writeLog("doCapture: onBegin")
                }

                override fun onError(error: String?) {
                    runOnUiThread {
                        finishHardwareCapture(success = false, rearmReason = "camera-capture-error")
                        writeLog("doCapture: onError - $error")
                        showStatus("Capture failed: $error")
                    }
                }

                override fun onComplete(path: String?) {
                    runOnUiThread {
                        writeLog("doCapture: onComplete - path=$path")
                        if (path != null) {
                            showStillCapturedFeedback()
                            notifyMediaScannerAsync(path)
                        } else {
                            finishHardwareCapture(success = false, rearmReason = "camera-capture-null-path")
                            showStatus("Capture failed - null path")
                        }
                    }
                }
            }, imagePath)

            writeLog("doCapture: captureImage() called successfully")
        } catch (e: Exception) {
            finishHardwareCapture(success = false, rearmReason = "camera-capture-exception")
            writeLog("doCapture: EXCEPTION - ${e.message}")
            writeLog("doCapture: stack - ${e.stackTraceToString()}")
            showStatus("Capture error: ${e.message}")
        }
    }

    private fun finishHardwareCapture(success: Boolean, rearmReason: String) {
        val fromHardwareButton = hardwareCaptureInFlight && hardwareButtonCaptureInFlight
        hardwareCaptureInFlight = false
        hardwareButtonCaptureInFlight = false
        hardwareCaptureStartedAtMs = 0L
        if (fromHardwareButton) {
            writeLog("finishHardwareCapture: success=$success rearmReason=$rearmReason")
        }
    }

    private fun notifyMediaScanner(path: String) {
        try {
            val file = File(path)
            if (!file.exists() || file.length() == 0L) {
                writeLog("Media scanner skipped - file missing or empty: $path")
                return
            }
            val isVideo = path.endsWith(".mp4")
            val mimeType = if (isVideo) "video/mp4" else "image/jpeg"
            val collection = if (isVideo) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH,
                        if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val uri = contentResolver.insert(collection, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    file.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                }
                writeLog("Saved to gallery: ${file.name}")
            }
        } catch (e: Exception) {
            writeLog("Media scanner error: ${e.message}")
        }
    }

    private fun toggleRecording() {
        if (!isCameraOpened()) {
            showStatus("Camera not ready")
            return
        }

        try {
            if (isRecording) {
                // Stop recording
                writeLog("Stopping recording...")
                captureVideoStop()
                isRecording = false
                updateRecordingUI(false)
                showStatus("Saving video...")
            } else {
                // Start recording
                // NOTE: Library appends .mp4 to the path, so do NOT include .mp4 extension
                val recordDir = getAppMoviesDir()
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                currentRecordingPath = File(recordDir, "VID_$timestamp").absolutePath

                writeLog("Starting recording to: $currentRecordingPath")

                captureVideoStart(object : ICaptureCallBack {
                    override fun onBegin() {
                        runOnUiThread {
                            writeLog("Recording started")
                            isRecording = true
                            updateRecordingUI(true)
                            showStatus("Recording...")
                        }
                    }

                    override fun onError(error: String?) {
                        runOnUiThread {
                            writeLog("Recording error: $error")
                            showStatus("Recording error: $error")
                            isRecording = false
                            updateRecordingUI(false)
                        }
                    }

                    override fun onComplete(path: String?) {
                        runOnUiThread {
                            writeLog("Recording saved: $path")
                            if (path != null) {
                                showStatus("Video saved")
                                notifyMediaScannerAsync(path)
                            }
                        }
                    }
                }, currentRecordingPath)
            }
        } catch (e: Exception) {
            writeLog("Recording error: ${e.message}")
            showStatus("Recording error: ${e.message}")
            isRecording = false
            updateRecordingUI(false)
        }
    }

    // =====================
    // Phone Adjust Strip
    // =====================

    /** Image control definitions — maps strip index to panel seekbar/value pairs */
    private data class ImageControlDef(val name: String, val label: String)
    private val imageControlDefs = listOf(
        ImageControlDef("brightness", "Brightness"),
        ImageControlDef("contrast", "Contrast"),
        ImageControlDef("saturation", "Saturation"),
        ImageControlDef("hue", "Hue"),
        ImageControlDef("gamma", "Gamma"),
        ImageControlDef("sharpness", "Sharpness")
    )

    private fun getImageSeekBar(index: Int): SeekBar = when (index) {
        0 -> binding.brightnessSeekBar
        1 -> binding.contrastSeekBar
        2 -> binding.saturationSeekBar
        3 -> binding.hueSeekBar
        4 -> binding.gammaSeekBar
        5 -> binding.sharpnessSeekBar
        else -> binding.brightnessSeekBar
    }

    private fun getImageValueText(index: Int): android.widget.TextView = when (index) {
        0 -> binding.brightnessValue
        1 -> binding.contrastValue
        2 -> binding.saturationValue
        3 -> binding.hueValue
        4 -> binding.gammaValue
        5 -> binding.sharpnessValue
        else -> binding.brightnessValue
    }

    private fun setupPhoneAdjustStrip() {
        binding.adjustStrip ?: return
        val tabCamera = binding.tabCamera ?: return
        val tabImage = binding.tabImage ?: return
        val tabTransform = binding.tabTransform ?: return
        val prevBtn = binding.stripPrevButton ?: return
        val nextBtn = binding.stripNextButton ?: return
        val seekBar = binding.stripSeekBar ?: return
        binding.stripControlLabel ?: return
        val valueText = binding.stripValueText ?: return
        val defaultsBtn = binding.stripDefaultsButton ?: return

        // Section tab click handlers
        tabCamera.setOnClickListener { switchStripSection(StripSection.CAMERA) }
        tabImage.setOnClickListener { switchStripSection(StripSection.IMAGE) }
        tabTransform.setOnClickListener { switchStripSection(StripSection.TRANSFORM) }

        // Prev/next navigation (image controls)
        prevBtn.setOnClickListener {
            if (currentStripSection == StripSection.IMAGE) {
                currentImageControlIndex = (currentImageControlIndex - 1 + imageControlDefs.size) % imageControlDefs.size
                updateStripImageControl()
            }
        }
        nextBtn.setOnClickListener {
            if (currentStripSection == StripSection.IMAGE) {
                currentImageControlIndex = (currentImageControlIndex + 1) % imageControlDefs.size
                updateStripImageControl()
            }
        }

        // Strip seekbar → updates shader directly + syncs panel seekbar
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && currentStripSection == StripSection.IMAGE) {
                    valueText.text = progress.toString()
                    val def = imageControlDefs[currentImageControlIndex]
                    // Sync panel seekbar + value label (programmatic, so fromUser=false there)
                    getImageSeekBar(currentImageControlIndex).progress = progress
                    getImageValueText(currentImageControlIndex).text = progress.toString()
                    // Apply directly since panel listener skips programmatic changes
                    applyShaderAdjustment(def.name, progress)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                saveImageControls()
            }
        })

        // Defaults button in strip (with confirmation)
        defaultsBtn.setOnClickListener {
            confirmResetDefaults()
        }

        // Wire strip camera controls
        setupStripCameraControls()

        // Wire strip transform controls
        setupStripTransformControls()

        // Initialize to IMAGE section
        switchStripSection(StripSection.IMAGE)
    }

    private fun setupStripCameraControls() {
        val stripSpinner = binding.stripViewSpinner ?: return
        val stripStart = binding.stripStartButton ?: return
        val stripStop = binding.stripStopButton ?: return

        // Share the same adapter as the main spinner
        stripSpinner.adapter = binding.viewModeSpinner.adapter

        // Sync selection from main spinner
        val savedRatio = getSharedPreferences("image_adjustments", MODE_PRIVATE)
            .getString("aspect_ratio", "1:1") ?: "1:1"
        val savedIndex = aspectRatioTypes.indexOfFirst { it.ratio == savedRatio }
        if (savedIndex >= 0) stripSpinner.setSelection(savedIndex)

        stripSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val type = aspectRatioTypes[position]
                // Apply directly (don't rely on syncing to panel spinner)
                applyAspectType(type)
                getSharedPreferences("image_adjustments", MODE_PRIVATE)
                    .edit().putString("aspect_ratio", type.ratio).apply()
                // Keep panel spinner in sync for save/restore
                binding.viewModeSpinner.setSelection(position, false)
                writeLog("Strip: Aspect ratio changed to: ${buildAspectLabel(type)}")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Start/Stop camera — use shared methods
        stripStart.setOnClickListener { startCameraIfNeeded() }
        stripStop.setOnClickListener { stopCameraIfRunning() }
    }

    private fun setupStripTransformControls() {
        val stripMirror = binding.stripMirrorCheckBox ?: return
        val stripFlip = binding.stripFlipCheckBox ?: return

        // Sync initial state
        stripMirror.isChecked = binding.mirrorCheckBox.isChecked
        stripFlip.isChecked = binding.flipCheckBox.isChecked

        // Strip → panel sync
        stripMirror.setOnCheckedChangeListener { _, isChecked ->
            if (binding.mirrorCheckBox.isChecked != isChecked) {
                binding.mirrorCheckBox.isChecked = isChecked
            }
        }
        stripFlip.setOnCheckedChangeListener { _, isChecked ->
            if (binding.flipCheckBox.isChecked != isChecked) {
                binding.flipCheckBox.isChecked = isChecked
            }
        }
    }

    private fun toggleAdjustStrip() {
        val strip = binding.adjustStrip ?: return
        isAdjustStripOpen = !isAdjustStripOpen

        if (isAdjustStripOpen) {
            // Sync strip state before showing
            if (currentStripSection == StripSection.IMAGE) updateStripImageControl()
            strip.visibility = View.VISIBLE
            strip.translationY = strip.height.toFloat()
            strip.post {
                strip.translationY = strip.height.toFloat()
                strip.animate().translationY(0f).setDuration(200).start()
            }
        } else {
            strip.animate().translationY(strip.height.toFloat())
                .setDuration(200)
                .withEndAction { strip.visibility = View.GONE }
                .start()
        }
    }

    private fun switchStripSection(section: StripSection) {
        currentStripSection = section
        val controlRow = binding.stripControlRow
        val cameraContent = binding.stripCameraContent
        val transformContent = binding.stripTransformContent
        val activeColor = ContextCompat.getColor(this, R.color.hawkeye_primary)
        val inactiveColor = ContextCompat.getColor(this, R.color.text_secondary)

        // Update tab colors
        binding.tabCamera?.setTextColor(if (section == StripSection.CAMERA) activeColor else inactiveColor)
        binding.tabImage?.setTextColor(if (section == StripSection.IMAGE) activeColor else inactiveColor)
        binding.tabTransform?.setTextColor(if (section == StripSection.TRANSFORM) activeColor else inactiveColor)

        // Show/hide content rows
        controlRow?.visibility = if (section == StripSection.IMAGE) View.VISIBLE else View.GONE
        cameraContent?.visibility = if (section == StripSection.CAMERA) View.VISIBLE else View.GONE
        transformContent?.visibility = if (section == StripSection.TRANSFORM) View.VISIBLE else View.GONE
        // Scope + metering rides along with the CAM tab
        binding.stripMaskContent?.visibility =
            if (section == StripSection.CAMERA) View.VISIBLE else View.GONE

        if (section == StripSection.IMAGE) {
            updateStripImageControl()
        }
    }

    private fun updateStripImageControl() {
        val seekBar = binding.stripSeekBar ?: return
        val labelText = binding.stripControlLabel ?: return
        val valueText = binding.stripValueText ?: return

        val def = imageControlDefs[currentImageControlIndex]
        val panelSeekBar = getImageSeekBar(currentImageControlIndex)

        labelText.text = def.label
        seekBar.progress = panelSeekBar.progress
        valueText.text = panelSeekBar.progress.toString()
    }

    // =====================
    // Status display
    // =====================

    private fun showStatus(message: String) {
        runOnUiThread {
            binding.statusText.text = message
            binding.statusText.visibility = View.VISIBLE
            binding.statusText.removeCallbacks(hideStatusRunnable)
            binding.statusText.postDelayed(hideStatusRunnable, 3000)
        }
    }

    private val hideStatusRunnable = Runnable {
        binding.statusText.visibility = View.GONE
    }
}
