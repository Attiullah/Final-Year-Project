package com.example.facerecognition;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import com.example.facerecognition.TextRecognitionActivity;
import java.io.File;
import java.io.FileOutputStream;

class HomeActivity : AppCompatActivity() {

    private lateinit var faceRecButton: Button
    private lateinit var objDetButton: Button
    private lateinit var uploadButton: Button
    private lateinit var textRecButton: Button

    // For picking multiple images from gallery
    private val pickImagesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri>? ->
        uris?.takeIf { it.isNotEmpty() }?.let {
            showLabelDialog(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        faceRecButton = findViewById(R.id.faceRecButton)
        objDetButton  = findViewById(R.id.objDetButton)
        uploadButton  = findViewById(R.id.uploadButton)
        textRecButton = findViewById(R.id.textRecButton)

        // 1) Face Recognition => launch MainActivity
        faceRecButton.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        // 2) Object Detection => launch ObjectDetectionActivity
        objDetButton.setOnClickListener {
            startActivity(Intent(this, ObjectDetectionActivity::class.java))
        }

        // 3) Upload images => pick multiple from gallery
        uploadButton.setOnClickListener {
            // Allow selecting multiple images
            pickImagesLauncher.launch(arrayOf("image/*"))
        }

        // 4) Text Recognition => launch TextRecognitionActivity
        textRecButton.setOnClickListener {
            startActivity(Intent(this, TextRecognitionActivity::class.java))
        }
    }

    /**
     * Ask user for a name/label, then save all picked images with that label.
     */
    private fun showLabelDialog(imageUris: List<Uri>) {
        val editText = EditText(this).apply {
            hint = "Enter person's name"
        }

        AlertDialog.Builder(this)
            .setTitle("Set Label for ${imageUris.size} photo(s)")
            .setView(editText)
            .setPositiveButton("OK") { dialog: DialogInterface, _: Int ->
                val label = editText.text.toString().trim()
                if (label.isNotEmpty()) {
                    saveImagesToKnownFaces(imageUris, label)
                    Toast.makeText(
                        this,
                        "Saved ${imageUris.size} photo(s) for '$label'",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    /**
     * Save selected images to app's internal 'known_faces' folder.
     * Filenames = label_index.ext (e.g. John_01.jpg)
     */
    private fun saveImagesToKnownFaces(imageUris: List<Uri>, label: String) {
        val knownFacesDir = File(filesDir, "known_faces")
        if (!knownFacesDir.exists()) {
            knownFacesDir.mkdirs()
        }

        var counter = 1
        for (uri in imageUris) {
            // Determine extension from original filename
            val extension = contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                    name.substringAfterLast('.', "jpg")
                } else {
                    "jpg"
                }
            } ?: "jpg"

            // Format filename as label_XX.ext
            val index = String.format("%02d", counter++)
            val filename = "${label}_$index.$extension"
            val targetFile = File(knownFacesDir, filename)

            // Copy content
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }
}
