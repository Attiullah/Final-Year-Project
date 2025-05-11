package com.example.facerecognition

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.util.Size
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var imageView: ImageView
    private lateinit var captureButton: Button
    private lateinit var previewView: PreviewView
    private lateinit var matchLabel: TextView

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private lateinit var faceNetModel: Interpreter

    // Text-to-Speech engine
    private lateinit var tts: TextToSpeech

    // Data class to hold label + embedding
    private data class KnownFace(val label: String, val embedding: FloatArray)

    // We'll store one KnownFace per label after averaging multiple images.
    private var knownFaceEmbeddings: List<KnownFace>? = null

    companion object {
        private const val TAG = "FaceRecognitionApp"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // UI elements
        previewView = findViewById(R.id.viewFinder)
        imageView = findViewById(R.id.imageView)
        captureButton = findViewById(R.id.captureButton)
        matchLabel = findViewById(R.id.matchLabel)

        // Initialize TextToSpeech
        tts = TextToSpeech(this, this)

        // Check/request camera permission
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(
                this,
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        } else {
            startCamera()
        }

        // Load the FaceNet model
        faceNetModel = loadModel()

        // Executor for background tasks
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Precompute embeddings for known faces
        loadKnownFaces()

        // Capture button
        captureButton.setOnClickListener {
            takePhoto()
        }
    }

    /**
     * TTS initialization callback
     */
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                Log.e(TAG, "TTS language not supported.")
            }
        } else {
            Log.e(TAG, "TTS initialization failed.")
        }
    }

    /**
     * Start camera preview with reduced target resolution
     */
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            // Limit the resolution of captured images to avoid huge bitmaps
            imageCapture = ImageCapture.Builder()
                .setTargetResolution(Size(1280, 720)) // Example: 1280x720
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Take photo and process it
     */
    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        val mediaDir = File(getExternalFilesDir(null), "captured_images")
        if (!mediaDir.exists()) mediaDir.mkdirs()

        val photoFile = File(mediaDir, "captured_face.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    // Downsample the captured image as well before detection
                    val bitmap = decodeScaledBitmap(photoFile.absolutePath, 1280, 1280)
                    if (bitmap == null) {
                        Toast.makeText(this@MainActivity, "Failed to decode captured image", Toast.LENGTH_SHORT).show()
                        return
                    }
                    imageView.setImageBitmap(bitmap)
                    matchLabel.text = ""
                    recognizeFace(bitmap)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                }
            }
        )
    }

    /**
     * Detect face, crop, resize, extract embedding, match
     * Using PERFORMANCE_MODE_FAST to reduce memory usage
     */
    private fun recognizeFace(bitmap: Bitmap) {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()

        val detector = FaceDetection.getClient(options)
        val image = InputImage.fromBitmap(bitmap, 0)

        detector.process(image)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    val face = faces[0]
                    val bounds = face.boundingBox
                    val left = bounds.left.coerceAtLeast(0)
                    val top = bounds.top.coerceAtLeast(0)
                    val right = bounds.right.coerceAtMost(bitmap.width)
                    val bottom = bounds.bottom.coerceAtMost(bitmap.height)
                    val width = right - left
                    val height = bottom - top

                    if (width > 0 && height > 0) {
                        val faceBitmap = Bitmap.createBitmap(bitmap, left, top, width, height)
                        val resizedFaceBitmap = Bitmap.createScaledBitmap(
                            faceBitmap,
                            160,
                            160,
                            true
                        )
                        val faceEmbedding = extractFaceEmbedding(resizedFaceBitmap)
                        matchFace(faceEmbedding)
                    } else {
                        Toast.makeText(
                            this,
                            "Invalid face bounds",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    Toast.makeText(this, "No face detected", Toast.LENGTH_SHORT).show()
                    matchLabel.text = "No face detected"
                    tts.speak("No face detected", TextToSpeech.QUEUE_FLUSH, null, "tts_id_1")
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Face detection failed", e)
                Toast.makeText(this, "Face detection failed", Toast.LENGTH_SHORT).show()
                matchLabel.text = "Face detection failed"
                tts.speak("Face detection failed", TextToSpeech.QUEUE_FLUSH, null, "tts_id_1")
            }
    }

    /**
     * Extract 512-dim embedding from 160x160 bitmap
     */
    private fun extractFaceEmbedding(bitmap: Bitmap): FloatArray {
        // Ensure 160x160
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 160, 160, true)
        val inputBuffer = ByteBuffer.allocateDirect(1 * 160 * 160 * 3 * 4)
            .order(ByteOrder.nativeOrder())

        val intValues = IntArray(160 * 160)
        resizedBitmap.getPixels(intValues, 0, 160, 0, 0, 160, 160)
        for (pixelValue in intValues) {
            inputBuffer.putFloat(((pixelValue shr 16) and 0xFF) / 255.0f)
            inputBuffer.putFloat(((pixelValue shr 8) and 0xFF) / 255.0f)
            inputBuffer.putFloat((pixelValue and 0xFF) / 255.0f)
        }
        inputBuffer.rewind()

        val output = Array(1) { FloatArray(512) }
        faceNetModel.run(inputBuffer, output)
        return output[0]
    }

    /**
     * Synchronously crop/resize a face from a bitmap (blocking call).
     * Downsample large bitmaps for face detection to avoid OOM.
     */
    private fun processFaceBitmap(bitmap: Bitmap): Bitmap? {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()

        val detector = FaceDetection.getClient(options)
        val image = InputImage.fromBitmap(bitmap, 0)

        return try {
            val task = detector.process(image)
            Tasks.await(task)
            val faces = task.result
            if (faces.isNullOrEmpty()) {
                null
            } else {
                val face = faces[0]
                val bounds = face.boundingBox
                val left = bounds.left.coerceAtLeast(0)
                val top = bounds.top.coerceAtLeast(0)
                val right = bounds.right.coerceAtMost(bitmap.width)
                val bottom = bounds.bottom.coerceAtMost(bitmap.height)
                val width = right - left
                val height = bottom - top
                if (width <= 0 || height <= 0) null
                else {
                    val cropped = Bitmap.createBitmap(bitmap, left, top, width, height)
                    Bitmap.createScaledBitmap(cropped, 160, 160, true)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing face bitmap", e)
            null
        }
    }

    /**
     * Load multiple images for each label, average embeddings
     * We downsample large files to avoid OOM
     */
    private fun loadKnownFaces() {
        cameraExecutor.execute {
            val knownFacesDir = File(getExternalFilesDir(null), "known_faces")
            if (!knownFacesDir.exists()) {
                knownFacesDir.mkdirs()
            }
            // label -> list of embeddings
            val embeddingsMap = mutableMapOf<String, MutableList<FloatArray>>()

            knownFacesDir.listFiles()?.forEach { file ->
                // Downsample the known-face images
                val storedBitmap = decodeScaledBitmap(file.absolutePath, 1280, 1280)
                if (storedBitmap != null) {
                    val processedBitmap = processFaceBitmap(storedBitmap)
                    if (processedBitmap != null) {
                        val embedding = extractFaceEmbedding(processedBitmap)
                        // Parse label from file name, e.g. "john_01.jpg" -> "john"
                        val fileNameNoExt = file.nameWithoutExtension
                        val underscoreIndex = fileNameNoExt.indexOf('_')
                        val label = if (underscoreIndex != -1) {
                            fileNameNoExt.substring(0, underscoreIndex)
                        } else {
                            // If no underscore, use the entire name
                            fileNameNoExt
                        }
                        embeddingsMap.putIfAbsent(label, mutableListOf())
                        embeddingsMap[label]?.add(embedding)
                    } else {
                        Log.w(TAG, "No face found in known face image: ${file.name}")
                    }
                } else {
                    Log.w(TAG, "Failed to decode file: ${file.name}")
                }
            }

            // Average embeddings for each label
            val finalList = mutableListOf<KnownFace>()
            for ((label, embeddings) in embeddingsMap) {
                val averageEmbedding = averageEmbeddings(embeddings)
                finalList.add(KnownFace(label, averageEmbedding))
            }

            knownFaceEmbeddings = finalList
            Log.d(TAG, "Loaded and averaged embeddings for ${finalList.size} label(s).")
        }
    }

    /**
     * Average multiple embeddings for a single label
     */
    private fun averageEmbeddings(embeddings: List<FloatArray>): FloatArray {
        if (embeddings.isEmpty()) {
            return FloatArray(512) { 0f }
        }
        val result = FloatArray(512)
        // Accumulate each embedding into 'result'
        for (emb in embeddings) {
            for (i in emb.indices) {
                result[i] = result[i] + emb[i]
            }
        }
        // Divide each accumulated value by the number of embeddings to compute the average
        for (i in result.indices) {
            result[i] = result[i] / embeddings.size
        }
        return result
    }

    /**
     * Compare face embedding to known embeddings
     */
    private fun matchFace(embedding: FloatArray) {
        cameraExecutor.execute {
            val knownFaces = knownFaceEmbeddings
            if (knownFaces.isNullOrEmpty()) {
                runOnUiThread {
                    val msg = "No known faces available"
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    matchLabel.text = msg
                    tts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "tts_id_2")
                }
                return@execute
            }

            var bestMatch: String? = null
            var bestSimilarity = 0f

            for (kf in knownFaces) {
                val similarity = cosineSimilarity(embedding, kf.embedding)
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity
                    bestMatch = kf.label
                }
            }

            runOnUiThread {
                if (bestSimilarity > 0.85) {
                    val matchText = "The identified person is: $bestMatch"
                    Toast.makeText(this, matchText, Toast.LENGTH_LONG).show()
                    matchLabel.text = matchText
                    tts.speak("The identified person is: $bestMatch", TextToSpeech.QUEUE_FLUSH, null, "tts_id_3")
                } else {
                    val noMatchText = "No match found"
                    Toast.makeText(this, noMatchText, Toast.LENGTH_LONG).show()
                    matchLabel.text = noMatchText
                    tts.speak(noMatchText, TextToSpeech.QUEUE_FLUSH, null, "tts_id_3")
                }
            }
        }
    }

    /**
     * Cosine similarity
     */
    private fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        var dotProduct = 0f
        var normV1 = 0f
        var normV2 = 0f
        for (i in v1.indices) {
            dotProduct += v1[i] * v2[i]
            normV1 += v1[i] * v1[i]
            normV2 += v2[i] * v2[i]
        }
        return dotProduct / (sqrt(normV1) * sqrt(normV2))
    }

    /**
     * Check camera permission
     */
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Handle permission result
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    /**
     * Cleanup
     */
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }

    /**
     * Load FaceNet model from assets
     */
    private fun loadModel(): Interpreter {
        val assetManager = assets
        val fileDescriptor = assetManager.openFd("facenet.tflite")
        val inputStream = fileDescriptor.createInputStream()
        val modelBytes = inputStream.readBytes()
        val byteBuffer = ByteBuffer.allocateDirect(modelBytes.size)
            .order(ByteOrder.nativeOrder())
        byteBuffer.put(modelBytes)
        byteBuffer.rewind()
        return Interpreter(byteBuffer)
    }

    /**
     * Downsample an image file to avoid OOM
     */
    private fun decodeScaledBitmap(filePath: String, maxWidth: Int, maxHeight: Int): Bitmap? {
        // 1) Read dimensions only
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(filePath, options)

        val (originalWidth, originalHeight) = options.outWidth to options.outHeight
        if (originalWidth <= 0 || originalHeight <= 0) {
            return null
        }

        // 2) Calculate inSampleSize
        var inSampleSize = 1
        while ((originalWidth / inSampleSize) > maxWidth || (originalHeight / inSampleSize) > maxHeight) {
            inSampleSize *= 2
        }

        // 3) Decode with inSampleSize
        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
        }
        return BitmapFactory.decodeFile(filePath, decodeOptions)
    }
}
