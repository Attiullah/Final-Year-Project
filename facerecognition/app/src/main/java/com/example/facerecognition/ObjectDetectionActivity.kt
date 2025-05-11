package com.example.facerecognition

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.util.Size
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.facerecognition.databinding.ActivityObjectDetectionBinding
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

class ObjectDetectionActivity : AppCompatActivity(),
    Detector.DetectorListener,
    TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityObjectDetectionBinding

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private lateinit var detector: Detector
    private lateinit var cameraExecutor: ExecutorService

    // Text-to-Speech
    private lateinit var tts: TextToSpeech
    private var lastSpeakTime = 0L
    private val speakInterval = 2000L // 2 seconds between TTS calls

    companion object {
        private const val TAG = "ObjectDetectionActivity"

        // If you only need CAMERA permission
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val REQUEST_CODE_PERMISSIONS = 10

        // Model & label file in assets/
        private const val MODEL_PATH = "yolov8n_float32.tflite"
        private const val LABELS_PATH = "labels.txt"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityObjectDetectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1) Initialize TTS
        tts = TextToSpeech(this, this)

        // 2) Initialize the TFLite detector
        detector = Detector(
            context = this,
            modelPath = MODEL_PATH,
            labelPath = LABELS_PATH,
            detectorListener = this
        )
        detector.setup()

        // 3) Check camera permission & start camera
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        // Single thread for camera frames
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases() {
        val rotation = binding.viewFinder.display.rotation
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        // Preview Use Case
        preview = Preview.Builder()
            .setTargetResolution(Size(1280, 720))
            .setTargetRotation(rotation)
            .build()

        // ImageAnalysis Use Case => real-time detection
        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetResolution(Size(1280, 720))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        // Analyzer: convert frame to Bitmap & run YOLO
        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            analyzeImage(imageProxy)
        }

        cameraProvider?.unbindAll()
        try {
            cameraProvider?.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )
            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "bindUseCases failed", exc)
        }
    }

    /** Convert the RGBA image to a Bitmap, rotate if needed, then call detector. */
    private fun analyzeImage(imageProxy: ImageProxy) {
        try {
            // 1) Create a Bitmap
            val bitmapBuffer = Bitmap.createBitmap(
                imageProxy.width,
                imageProxy.height,
                Bitmap.Config.ARGB_8888
            )
            imageProxy.use {
                bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer)
            }

            // 2) Rotate if needed
            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
            }
            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
                matrix, true
            )

            // 3) Detect
            detector.detect(rotatedBitmap)

        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing image", e)
        } finally {
            imageProxy.close()
        }
    }

    // region Detector callbacks
    override fun onEmptyDetect() {
        // No bounding boxes => clear overlay
        runOnUiThread {
            binding.overlay.setResults(emptyList())
        }
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        runOnUiThread {
            // 1) Draw bounding boxes
            binding.overlay.setResults(boundingBoxes)

            // 2) Show inference time
            binding.inferenceTime.text = "$inferenceTime ms"

            // 3) Speak top detection if any
            if (boundingBoxes.isNotEmpty()) {
                speakTopDetection(boundingBoxes)
            }
        }
    }
    // endregion

    // region TTS
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            Log.d(TAG, "TTS initialized successfully.")
        } else {
            Log.e(TAG, "TTS initialization failed.")
        }
    }

    private fun speakTopDetection(boxes: List<BoundingBox>) {
        // Pick the highest confidence bounding box
        val best = boxes.maxByOrNull { it.cnf } ?: return
        val label = best.clsName

        val now = System.currentTimeMillis()
        if (now - lastSpeakTime > speakInterval) {
            // Speak "Detected <label>"
            tts.speak("Detected $label", TextToSpeech.QUEUE_FLUSH, null, "obj_tts")
            lastSpeakTime = now
        }
    }
    // endregion

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()) {
            startCamera()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        detector.clear()
        cameraExecutor.shutdown()
        // Shutdown TTS
        tts.stop()
        tts.shutdown()
    }

    // region Permissions
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) ==
                PackageManager.PERMISSION_GRANTED
    }
    // endregion
}
