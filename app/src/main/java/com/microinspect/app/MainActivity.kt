package com.microinspect.app

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.microinspect.app.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private lateinit var cameraExecutor: java.util.concurrent.ExecutorService

    private lateinit var cameraManager: CameraManager
    private var torchCameraId: String? = null
    private var maxTorchStrength: Int = 1
    private var supportsVariableTorch: Boolean = false
    private var torchOn: Boolean = false

    private lateinit var calibrationPrefs: SharedPreferences
    private var currentZoomPercent: Int = 0
    private var pendingCalibrationPixelDistance: Float = 0f

    private var imageAnalysis: ImageAnalysis? = null
    private var afEnabled: Boolean = true
    private var minFocusDistance: Float = 0f // diopters; 0 = infinity
    private var manualFocusDiopters: Float = 0f
    private val analysisExecutor by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }

    private var supportsOpticalStabilization: Boolean = false
    private var supportsVideoStabilization: Boolean = false
    private var stabilizationEnabled: Boolean = true

    private val requestPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        calibrationPrefs = getSharedPreferences("measure_calibration", Context.MODE_PRIVATE)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        binding.btnCapture.setOnClickListener { takePhoto() }

        binding.btnToggleTorch.setOnClickListener {
            if (!torchOn) {
                turnTorchOn(binding.torchBrightnessSeekBar.progress)
                binding.torchBrightnessRow.visibility =
                    if (supportsVariableTorch) android.view.View.VISIBLE else android.view.View.GONE
            } else {
                turnTorchOff()
                binding.torchBrightnessRow.visibility = android.view.View.GONE
            }
        }

        binding.torchBrightnessSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && torchOn) applyTorchStrength(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnGallery.setOnClickListener {
            Toast.makeText(this, "Saved to Pictures/MicroInspect", Toast.LENGTH_SHORT).show()
        }

        binding.zoomSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                camera?.cameraControl?.setLinearZoom(progress / 100f)
                currentZoomPercent = progress
                loadCalibrationForCurrentZoom()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnMeasure.setOnClickListener {
            startCalibration()
        }

        binding.measureOverlayView.onCalibrationPointsChosen = { pixelDistance ->
            pendingCalibrationPixelDistance = pixelDistance
            promptForKnownLength()
        }

        binding.btnToggleAf.setOnClickListener {
            setAfEnabled(!afEnabled)
        }

        binding.btnToggleStabilization.setOnClickListener {
            setStabilizationEnabled(!stabilizationEnabled)
        }

        binding.manualFocusSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !afEnabled) applyManualFocus(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    /**
     * Enters calibration mode: the ruled scale overlay is hidden and the
     * user is asked to tap two points on an object of known length (a
     * ruler, a coin, a reference card) placed under the lens.
     */
    private fun startCalibration() {
        binding.measureOverlayView.calibrationMode = true
        binding.calibrationBanner.visibility = android.view.View.VISIBLE
        binding.calibrationBanner.text = "Tap two points on a known-length object"
    }

    private fun promptForKnownLength() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "Length in millimeters, e.g. 10"
        }

        AlertDialog.Builder(this)
            .setTitle("Known length")
            .setMessage("Enter the real length of the span you just marked, in millimeters.")
            .setView(input)
            .setPositiveButton("Calibrate") { _, _ ->
                val mm = input.text.toString().toFloatOrNull()
                if (mm == null || mm <= 0f) {
                    Toast.makeText(this, "Enter a valid length in mm", Toast.LENGTH_SHORT).show()
                    cancelCalibration()
                    return@setPositiveButton
                }
                val pxPerMm = pendingCalibrationPixelDistance / mm
                saveCalibrationForCurrentZoom(pxPerMm)
                finishCalibration(pxPerMm)
            }
            .setNegativeButton("Cancel") { _, _ -> cancelCalibration() }
            .setCancelable(false)
            .show()
    }

    private fun finishCalibration(pxPerMm: Float) {
        binding.measureOverlayView.calibrationMode = false
        binding.measureOverlayView.pixelsPerMm = pxPerMm
        binding.measureOverlayView.isCalibrated = true
        binding.calibrationBanner.visibility = android.view.View.GONE
        binding.calibrationStatusText.text = "Calibrated: %.1f px/mm".format(pxPerMm)
        Toast.makeText(this, "Calibrated. Ruled scale now shows real mm.", Toast.LENGTH_SHORT).show()
    }

    private fun cancelCalibration() {
        binding.measureOverlayView.calibrationMode = false
        binding.calibrationBanner.visibility = android.view.View.GONE
        loadCalibrationForCurrentZoom()
    }

    private fun calibrationKeyForZoom(): String = "pxPerMm_zoom_$currentZoomPercent"

    private fun saveCalibrationForCurrentZoom(pxPerMm: Float) {
        calibrationPrefs.edit().putFloat(calibrationKeyForZoom(), pxPerMm).apply()
    }

    /**
     * Calibration is zoom-dependent (pixels-per-mm changes with zoom), so
     * each zoom level keeps its own saved value. When there's no saved
     * calibration for the current zoom, the ruled scale is still shown
     * immediately using a rough density-based estimate so the screen never
     * looks "empty" — it's drawn in orange and labeled APPROX until the
     * user calibrates against a known object at this zoom.
     */
    private fun loadCalibrationForCurrentZoom() {
        val saved = calibrationPrefs.getFloat(calibrationKeyForZoom(), -1f)
        if (saved > 0f) {
            binding.measureOverlayView.pixelsPerMm = saved
            binding.measureOverlayView.isCalibrated = true
            binding.calibrationStatusText.text = "Calibrated: %.1f px/mm".format(saved)
        } else {
            binding.measureOverlayView.pixelsPerMm = estimateDefaultPixelsPerMm()
            binding.measureOverlayView.isCalibrated = false
            binding.calibrationStatusText.text = "Not calibrated at this zoom (showing approx scale)"
        }
    }

    /**
     * A rough starting px/mm so the ruler overlay has something plausible
     * to show before the user calibrates. Based on screen density only —
     * it assumes a roughly life-size subject in frame and gets less
     * accurate the more the lens is zoomed in, which is exactly why it's
     * drawn as an approximation rather than a real reading.
     */
    private fun estimateDefaultPixelsPerMm(): Float {
        val baseDotsPerMm = resources.displayMetrics.xdpi / 25.4f
        val zoomMultiplier = 1f + (currentZoomPercent / 100f) * 3f
        return baseDotsPerMm * zoomMultiplier
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            setupSharpnessAnalysis()
            val analysis = imageAnalysis

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = if (analysis != null) {
                    cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, imageCapture, analysis
                    )
                } else {
                    cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, imageCapture
                    )
                }
                detectFocusCapabilities()
                detectStabilizationCapabilities()
                afEnabled = true
                stabilizationEnabled = true
                applyCaptureRequestOptions()
                startContinuousAutofocus()
                setupTapToFocus()
                detectTorchCapabilities()
                loadCalibrationForCurrentZoom()
            } catch (exc: Exception) {
                Toast.makeText(this, "Camera init failed: ${exc.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Reads the physical torch strength range for the active camera.
     * Variable torch power (CameraManager.turnOnTorchWithStrengthLevel)
     * requires Android 13+ (API 33) and hardware support; otherwise the
     * torch is on/off only and the brightness slider is hidden.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun detectTorchCapabilities() {
        val cam = camera ?: return
        val cameraId = Camera2CameraInfo.from(cam.cameraInfo).cameraId
        torchCameraId = cameraId

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val maxLevel = characteristics.get(
                    android.hardware.camera2.CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL
                ) ?: 1
                maxTorchStrength = maxLevel.coerceAtLeast(1)
                supportsVariableTorch = maxTorchStrength > 1
            } catch (e: Exception) {
                supportsVariableTorch = false
                maxTorchStrength = 1
            }
        } else {
            supportsVariableTorch = false
            maxTorchStrength = 1
        }

        binding.torchBrightnessSeekBar.max = 100
    }

    private fun turnTorchOn(percent: Int) {
        val cameraId = torchCameraId
        if (supportsVariableTorch && cameraId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val level = percentToStrength(percent)
                cameraManager.turnOnTorchWithStrengthLevel(cameraId, level)
                torchOn = true
                return
            } catch (e: Exception) {
                // Fall through to CameraX on/off control.
            }
        }
        camera?.cameraControl?.enableTorch(true)
        torchOn = true
    }

    private fun applyTorchStrength(percent: Int) {
        val cameraId = torchCameraId ?: return
        if (!supportsVariableTorch || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        try {
            cameraManager.turnOnTorchWithStrengthLevel(cameraId, percentToStrength(percent))
        } catch (e: Exception) {
            // Ignore transient strength-change failures (e.g. camera briefly busy).
        }
    }

    private fun turnTorchOff() {
        val cameraId = torchCameraId
        if (supportsVariableTorch && cameraId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                cameraManager.setTorchMode(cameraId, false)
                torchOn = false
                return
            } catch (e: Exception) {
                // Fall through to CameraX control.
            }
        }
        camera?.cameraControl?.enableTorch(false)
        torchOn = false
    }

    private fun percentToStrength(percent: Int): Int {
        val fraction = percent.coerceIn(1, 100) / 100f
        return (fraction * maxTorchStrength).toInt().coerceIn(1, maxTorchStrength)
    }

    /**
     * Kicks off continuous autofocus (like a DSLR's AF-C) centered on the
     * crosshair so the lens keeps hunting focus on its own as the phone or
     * the part moves, instead of locking after a single focus pass.
     */
    private fun startContinuousAutofocus() {
        if (!afEnabled) return
        val previewView = binding.previewView
        previewView.post {
            if (!afEnabled) return@post
            val factory = previewView.meteringPointFactory
            val centerPoint = factory.createPoint(previewView.width / 2f, previewView.height / 2f)

            // AF only here, deliberately without FLAG_AE: re-triggering
            // auto-exposure metering on every continuous refocus pass is
            // what caused the preview to flash/pump brightness. Exposure
            // still runs continuously on its own; it just isn't re-anchored
            // every time focus hunts.
            val action = FocusMeteringAction.Builder(
                centerPoint,
                FocusMeteringAction.FLAG_AF
            )
                // No auto-cancel: keep refocusing continuously rather than locking.
                .disableAutoCancel()
                .build()

            camera?.cameraControl?.startFocusAndMetering(action)
            binding.statusText.text = "Continuous autofocus active"
        }
    }

    private fun setupTapToFocus() {
        binding.previewView.setOnTouchListener { view, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP && afEnabled) {
                val factory = binding.previewView.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)
                // Tap pins focus/exposure on a specific defect for a few
                // seconds, then falls back to continuous autofocus again.
                val action = FocusMeteringAction.Builder(
                    point,
                    FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                ).setAutoCancelDuration(4, java.util.concurrent.TimeUnit.SECONDS).build()

                val future = camera?.cameraControl?.startFocusAndMetering(action)
                binding.statusText.text = "Focusing on surface point…"

                future?.addListener({
                    binding.statusText.text = "Continuous autofocus active"
                }, ContextCompat.getMainExecutor(this))

                view.performClick()
            }
            true
        }
    }

    /**
     * Toggles between continuous autofocus and manual focus. Turning AF
     * off cancels any in-flight focus/metering action and freezes the lens
     * wherever it currently sits (then the manual slider takes over);
     * turning AF back on resumes continuous hunting and hides the slider.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun setAfEnabled(enabled: Boolean) {
        afEnabled = enabled
        val cam = camera

        if (enabled) {
            binding.btnToggleAf.text = "AF: On"
            binding.manualFocusRow.visibility = android.view.View.GONE
            applyCaptureRequestOptions()
            startContinuousAutofocus()
        } else {
            binding.btnToggleAf.text = "AF: Off"
            cam?.cameraControl?.cancelFocusAndMetering()
            if (minFocusDistance > 0f) {
                binding.manualFocusRow.visibility = android.view.View.VISIBLE
                applyManualFocus(binding.manualFocusSeekBar.progress)
            } else {
                Toast.makeText(this, "This camera doesn't support manual focus", Toast.LENGTH_SHORT).show()
                applyCaptureRequestOptions()
            }
            binding.statusText.text = "Manual focus"
        }
    }

    /**
     * Drives the lens directly via Camera2's LENS_FOCUS_DISTANCE, in
     * diopters (0 = infinity, increasing = closer). CameraX has no native
     * manual-focus API, so this goes through Camera2Interop.
     */
    private fun applyManualFocus(percent: Int) {
        if (minFocusDistance <= 0f) return
        val fraction = percent.coerceIn(0, 100) / 100f
        manualFocusDiopters = fraction * minFocusDistance
        applyCaptureRequestOptions()
    }

    /**
     * Builds and applies the full Camera2 capture request options from
     * current state in one place. AF mode/manual focus and stabilization
     * are independent Camera2 settings that both go through the same
     * Camera2CameraControl, so setting one via a fresh builder would wipe
     * out the other; this always composes the complete set.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyCaptureRequestOptions() {
        val cam = camera ?: return
        val builder = CaptureRequestOptions.Builder()

        if (afEnabled) {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
        } else if (minFocusDistance > 0f) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusDiopters)
        }

        if (stabilizationEnabled && supportsOpticalStabilization) {
            builder.setCaptureRequestOption(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
            )
        } else if (supportsOpticalStabilization) {
            builder.setCaptureRequestOption(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
            )
        }

        // Digital/electronic stabilization of the preview+video pipeline.
        // Only used when OIS isn't available, since running both at once
        // tends to fight on cheaper sensors.
        if (!supportsOpticalStabilization && supportsVideoStabilization) {
            if (stabilizationEnabled) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    builder.setCaptureRequestOption(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
                    )
                } else {
                    builder.setCaptureRequestOption(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                    )
                }
            } else {
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                )
            }
        }

        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(builder.build())
    }

    /**
     * Checks what stabilization this camera supports. OIS (hardware,
     * moves the lens element) is preferred when present; otherwise falls
     * back to Android's video/preview electronic stabilization. Handheld
     * macro shake is heavily amplified at close focus distances and high
     * zoom, so this matters more here than on a typical wide shot.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun detectStabilizationCapabilities() {
        val cam = camera ?: return
        try {
            val cameraId = Camera2CameraInfo.from(cam.cameraInfo).cameraId
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)

            val oisModes = characteristics.get(
                CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION
            )
            supportsOpticalStabilization = oisModes?.contains(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
            ) == true

            val videoModes = characteristics.get(
                CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
            )
            supportsVideoStabilization = videoModes?.any {
                it == CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        it == CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION)
            } == true
        } catch (e: Exception) {
            supportsOpticalStabilization = false
            supportsVideoStabilization = false
        }

        binding.btnToggleStabilization.visibility =
            if (supportsOpticalStabilization || supportsVideoStabilization) android.view.View.VISIBLE
            else android.view.View.GONE

        updateStabilizationLabel()
    }

    private fun updateStabilizationLabel() {
        val kind = when {
            supportsOpticalStabilization -> "OIS"
            supportsVideoStabilization -> "EIS"
            else -> ""
        }
        binding.btnToggleStabilization.text =
            if (stabilizationEnabled) "Stab: $kind" else "Stab: Off"
    }

    private fun setStabilizationEnabled(enabled: Boolean) {
        stabilizationEnabled = enabled
        updateStabilizationLabel()
        applyCaptureRequestOptions()
    }

    /**
     * Reads the lens's minimum focus distance (needed to drive manual
     * focus) and whether the device reports a focus distance calibration
     * good enough to trust the slider's mapping.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun detectFocusCapabilities() {
        val cam = camera ?: return
        try {
            val cameraId = Camera2CameraInfo.from(cam.cameraInfo).cameraId
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            minFocusDistance = characteristics.get(
                CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
            ) ?: 0f
        } catch (e: Exception) {
            minFocusDistance = 0f
        }
    }

    /**
     * Streams low-res preview frames through ImageAnalysis and scores each
     * one for sharpness using a Laplacian-variance style edge-strength
     * metric: in-focus images have sharp edges (high local contrast
     * variance), blurry ones don't. The score is normalized to 0-100 and
     * shown on the sharpness meter so the user can see focus quality peak
     * in real time, whether driven by AF or the manual slider.
     */
    private fun setupSharpnessAnalysis() {
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()

        analysis.setAnalyzer(analysisExecutor) { imageProxy ->
            try {
                val score = computeSharpnessScore(imageProxy)
                runOnUiThread {
                    binding.sharpnessMeter.progress = score
                }
            } finally {
                imageProxy.close()
            }
        }

        imageAnalysis = analysis
    }

    private var runningMaxVariance = 1.0

    private fun computeSharpnessScore(imageProxy: ImageProxy): Int {
        if (imageProxy.format != ImageFormat.YUV_420_888) return 0
        val yPlane = imageProxy.planes[0]
        val buffer = yPlane.buffer
        val rowStride = yPlane.rowStride
        val width = imageProxy.width
        val height = imageProxy.height

        // Sample a sparse grid of the luma plane (every few pixels) and
        // approximate a Laplacian via a simple 4-neighbor second
        // difference. This is cheap enough to run every frame on-device
        // without a dedicated image-processing library.
        val step = 6
        var sum = 0.0
        var sumSq = 0.0
        var count = 0

        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        var y = step
        while (y < height - step) {
            var x = step
            while (x < width - step) {
                val idx = y * rowStride + x
                val idxUp = (y - step) * rowStride + x
                val idxDown = (y + step) * rowStride + x
                val idxLeft = y * rowStride + (x - step)
                val idxRight = y * rowStride + (x + step)

                if (idx < bytes.size && idxUp >= 0 && idxUp < bytes.size &&
                    idxDown < bytes.size && idxLeft >= 0 && idxLeft < bytes.size && idxRight < bytes.size
                ) {
                    val center = bytes[idx].toInt() and 0xFF
                    val up = bytes[idxUp].toInt() and 0xFF
                    val down = bytes[idxDown].toInt() and 0xFF
                    val left = bytes[idxLeft].toInt() and 0xFF
                    val right = bytes[idxRight].toInt() and 0xFF

                    val laplacian = (up + down + left + right - 4 * center).toDouble()
                    sum += laplacian
                    sumSq += laplacian * laplacian
                    count++
                }
                x += step
            }
            y += step
        }

        if (count == 0) return 0

        val mean = sum / count
        val variance = (sumSq / count) - (mean * mean)

        // Track a running max so the meter is relative to what this scene
        // has actually achieved (an absolute scale would mean nearly
        // everything reads near-zero on low-texture surfaces).
        runningMaxVariance = max(runningMaxVariance, variance)
        val normalized = (variance / runningMaxVariance) * 100.0
        return normalized.coerceIn(0.0, 100.0).toInt()
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val name = "DEFECT_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis())

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/MicroInspect")
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(baseContext, "Capture failed: ${exc.message}", Toast.LENGTH_LONG).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    binding.statusText.text = "Saved: $name"
                }
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        if (torchOn) turnTorchOff()
        cameraExecutor.shutdown()
        analysisExecutor.shutdown()
    }
}
