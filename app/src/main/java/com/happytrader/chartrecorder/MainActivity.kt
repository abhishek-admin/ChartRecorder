package com.happytrader.chartrecorder

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.happytrader.chartrecorder.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    companion object {
        const val MODE_SCREEN = "screen"
        const val MODE_REAR = "rear"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private var currentMode = MODE_SCREEN

    // ── Permission launcher ──────────────────────────────────────
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) {
                checkOverlayPermission()
            } else {
                Toast.makeText(this, "All permissions are required to record.", Toast.LENGTH_LONG).show()
            }
        }

    // ── Overlay settings result ──────────────────────────────────
    private val overlayLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) {
                if (currentMode == MODE_SCREEN) {
                    requestScreenCapture()
                } else {
                    launchRearCameraRecording()
                }
            } else {
                Toast.makeText(this, "Overlay permission is required for the camera bubble.", Toast.LENGTH_LONG).show()
            }
        }

    // ── MediaProjection result (screen mode only) ────────────────
    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                startRecordingService(result.resultCode, result.data)
            } else {
                Toast.makeText(this, "Screen capture permission denied.", Toast.LENGTH_SHORT).show()
            }
        }

    // ────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("chart_recorder_prefs", MODE_PRIVATE)
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Restore preferences
        binding.switchFaceBubble.isChecked = prefs.getBoolean("bubble_enabled", true)
        binding.sliderBubbleSize.value = prefs.getInt("bubble_size", 120).toFloat()
        currentMode = prefs.getString("recording_mode", MODE_SCREEN) ?: MODE_SCREEN
        if (currentMode == MODE_REAR) {
            binding.toggleRecordingMode.check(R.id.btnModeRear)
        } else {
            binding.toggleRecordingMode.check(R.id.btnModeScreen)
        }
        updateModeDescription(currentMode)

        // Mode toggle listener
        binding.toggleRecordingMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentMode = if (checkedId == R.id.btnModeRear) MODE_REAR else MODE_SCREEN
                prefs.edit().putString("recording_mode", currentMode).apply()
                updateModeDescription(currentMode)
                updateInstructions(currentMode)
            }
        }

        binding.btnRecord.setOnClickListener {
            if (RecordingService.isRunning) {
                stopRecording()
            } else {
                startRecordingFlow()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUi(RecordingService.isRunning)
    }

    // ── Mode helpers ─────────────────────────────────────────────
    private fun updateModeDescription(mode: String) {
        binding.tvModeDescription.text = if (mode == MODE_SCREEN) {
            "Records your screen + front camera bubble"
        } else {
            "Records rear camera + front camera bubble overlay"
        }
    }

    private fun updateInstructions(mode: String) {
        binding.tvInstructionsTitle.text = if (mode == MODE_SCREEN) {
            "How to use (Screen Mode):"
        } else {
            "How to use (Rear Camera Mode):"
        }
        binding.tvInstructions.text = if (mode == MODE_SCREEN) {
            "1. Toggle face bubble & set size\n" +
            "2. Tap START RECORDING\n" +
            "3. Grant all permissions\n" +
            "4. App minimizes automatically\n" +
            "5. Open TradingView or any chart app\n" +
            "6. Your screen, voice & face are recorded\n" +
            "7. Pull down notification → tap STOP\n" +
            "8. MP4 saved to Movies/ChartRecorder/"
        } else {
            "1. Toggle face bubble & set size\n" +
            "2. Tap START RECORDING\n" +
            "3. Grant all permissions\n" +
            "4. Point rear camera at your chart/screen\n" +
            "5. Your face bubble appears overlaid on screen\n" +
            "6. Rear camera video + mic audio are recorded\n" +
            "7. Pull down notification → tap STOP\n" +
            "8. MP4 saved to Movies/ChartRecorder/"
        }
    }

    // ── UI ───────────────────────────────────────────────────────
    private fun updateUi(recording: Boolean) {
        if (recording) {
            binding.btnRecord.text = "⏹ STOP RECORDING"
            binding.btnRecord.setBackgroundColor(getColor(R.color.accent_red))
            binding.tvStatus.text = "🔴 Recording…"
            binding.toggleRecordingMode.isEnabled = false
        } else {
            binding.btnRecord.text = "🔴 START RECORDING"
            binding.btnRecord.setBackgroundColor(getColor(R.color.green_ready))
            binding.tvStatus.text = "Ready…"
            binding.toggleRecordingMode.isEnabled = true
        }
    }

    // ── Stop ─────────────────────────────────────────────────────
    private fun stopRecording() {
        startService(Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        })
        updateUi(recording = false)
    }

    // ── Start flow ───────────────────────────────────────────────
    private fun startRecordingFlow() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT in (Build.VERSION_CODES.M..Build.VERSION_CODES.S_V2)) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val notGranted = permissions.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        } else {
            checkOverlayPermission()
        }
    }

    private fun checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please enable 'Display over other apps' for the camera bubble.", Toast.LENGTH_LONG).show()
            overlayLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        } else {
            if (currentMode == MODE_SCREEN) requestScreenCapture() else launchRearCameraRecording()
        }
    }

    private fun requestScreenCapture() {
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun launchRearCameraRecording() {
        // Rear camera mode skips MediaProjection entirely
        startRecordingService(resultCode = -1, data = null)
    }

    // ── Launch service ───────────────────────────────────────────
    private fun startRecordingService(resultCode: Int, data: Intent?) {
        val bubbleEnabled = binding.switchFaceBubble.isChecked
        val bubbleSize = binding.sliderBubbleSize.value.toInt()

        prefs.edit()
            .putBoolean("bubble_enabled", bubbleEnabled)
            .putInt("bubble_size", bubbleSize)
            .apply()

        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_RESULT_CODE, resultCode)
            putExtra(RecordingService.EXTRA_DATA, data)
            putExtra(RecordingService.EXTRA_BUBBLE_ENABLED, bubbleEnabled)
            putExtra(RecordingService.EXTRA_BUBBLE_SIZE, bubbleSize)
            putExtra(RecordingService.EXTRA_RECORDING_MODE, currentMode)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        moveTaskToBack(true)
        updateUi(recording = true)
    }
}
