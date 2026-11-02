package com.microinspect.app

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.microinspect.app.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Locale

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
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
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

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
                startContinuousAutofocus()
                setupTapToFocus()
                detectTorchCapabilities()
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
        val previewView = binding.previewView
        previewView.post {
            val factory = previewView.meteringPointFactory
            val centerPoint = factory.createPoint(previewView.width / 2f, previewView.height / 2f)

            val action = FocusMeteringAction.Builder(
                centerPoint,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
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
            if (event.action == android.view.MotionEvent.ACTION_UP) {
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
    }
}
