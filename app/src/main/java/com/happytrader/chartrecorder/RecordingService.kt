package com.happytrader.chartrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.happytrader.chartrecorder.databinding.CameraOverlayBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingService : Service(), LifecycleOwner {

    companion object {
        const val ACTION_START = "com.happytrader.chartrecorder.ACTION_START"
        const val ACTION_STOP  = "com.happytrader.chartrecorder.ACTION_STOP"
        const val EXTRA_RESULT_CODE    = "extra_result_code"
        const val EXTRA_DATA           = "extra_data"
        const val EXTRA_BUBBLE_ENABLED = "extra_bubble_enabled"
        const val EXTRA_BUBBLE_SIZE    = "extra_bubble_size"
        const val EXTRA_RECORDING_MODE = "extra_recording_mode"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "chart_recorder_channel"

        @Volatile var isRunning = false
    }

    // ── CameraX Lifecycle ────────────────────────────────────────
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    // ── Screen-mode components ───────────────────────────────────
    private var mediaRecorder: MediaRecorder? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null

    // ── Rear-camera-mode components ──────────────────────────────
    private var cameraProvider: ProcessCameraProvider? = null
    private var activeRecording: Recording? = null

    // ── Shared ───────────────────────────────────────────────────
    private var overlayView: View? = null
    private lateinit var windowManager: WindowManager
    private var outputFile: File? = null
    private var recordingMode = MainActivity.MODE_SCREEN

    // ────────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode   = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
                }
                val bubbleEnabled = intent.getBooleanExtra(EXTRA_BUBBLE_ENABLED, true)
                val bubbleSizeDp  = intent.getIntExtra(EXTRA_BUBBLE_SIZE, 120)
                recordingMode     = intent.getStringExtra(EXTRA_RECORDING_MODE) ?: MainActivity.MODE_SCREEN

                startForeground(NOTIFICATION_ID, buildNotification())

                if (recordingMode == MainActivity.MODE_REAR) {
                    startRearCameraRecording(bubbleEnabled, bubbleSizeDp)
                } else if (data != null) {
                    startScreenRecording(resultCode, data, bubbleEnabled, bubbleSizeDp)
                }
            }
            ACTION_STOP -> {
                stopAll()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ═══════════════════════════════════════════════════════════
    //  SCREEN RECORDING MODE
    // ═══════════════════════════════════════════════════════════
    private fun startScreenRecording(
        resultCode: Int, data: Intent, bubbleEnabled: Boolean, bubbleSizeDp: Int
    ) {
        isRunning = true
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(metrics)
        val alignedWidth  = (metrics.widthPixels  / 16) * 16
        val alignedHeight = (metrics.heightPixels / 16) * 16

        outputFile = buildOutputFile()

        // MediaRecorder
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this)
                       else @Suppress("DEPRECATION") MediaRecorder()
        recorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoSize(alignedWidth, alignedHeight)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(8_000_000)
            setAudioEncodingBitRate(128_000)
            setAudioSamplingRate(44100)
            setOutputFile(outputFile!!.absolutePath)
            prepare()
        }
        mediaRecorder = recorder

        // MediaProjection + VirtualDisplay
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpManager.getMediaProjection(resultCode, data)
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopAll() }
        }, null)
        mediaProjection = projection

        virtualDisplay = projection.createVirtualDisplay(
            "ChartRecorderDisplay",
            alignedWidth, alignedHeight, metrics.densityDpi,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            recorder.surface, null, null
        )
        recorder.start()

        // Front camera bubble (visible inside the recording via screen capture)
        if (bubbleEnabled) showFrontCameraOverlay(bubbleSizeDp)
    }

    // ═══════════════════════════════════════════════════════════
    //  REAR CAMERA RECORDING MODE
    // ═══════════════════════════════════════════════════════════
    private fun startRearCameraRecording(bubbleEnabled: Boolean, bubbleSizeDp: Int) {
        isRunning = true
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        outputFile = buildOutputFile()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            // Build rear camera VideoCapture
            val qualitySelector = QualitySelector.fromOrderedList(
                listOf(Quality.FHD, Quality.HD, Quality.SD)
            )
            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build()
            val videoCapture = VideoCapture.withOutput(recorder)

            // Check concurrent camera support for front bubble
            val supportsConcurrent = try {
                cameraProvider!!.availableConcurrentCameraInfos.any { infoList ->
                    infoList.any { it.lensFacing == CameraSelector.LENS_FACING_BACK } &&
                    infoList.any { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
                }
            } catch (_: Exception) { false }

            cameraProvider!!.unbindAll()

            if (supportsConcurrent && bubbleEnabled) {
                // ── Concurrent: bind rear (VideoCapture) + front (Preview overlay)
                bindConcurrentCameras(videoCapture, bubbleSizeDp)
            } else {
                // ── Single camera: rear only, optional bubble via second CameraX instance
                cameraProvider!!.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, videoCapture
                )
                // Show front camera bubble via separate provider if bubble requested
                if (bubbleEnabled) showFrontCameraOverlay(bubbleSizeDp)
            }

            // Start the actual recording to file
            val outputOptions = FileOutputOptions.Builder(outputFile!!).build()
            activeRecording = videoCapture.output
                .prepareRecording(this, outputOptions)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(this)) { event ->
                    if (event is VideoRecordEvent.Finalize) {
                        if (!event.hasError()) {
                            scanAndNotify(outputFile)
                        }
                    }
                }

        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(androidx.camera.lifecycle.ExperimentalCameraProviderConfiguration::class)
    private fun bindConcurrentCameras(videoCapture: VideoCapture<Recorder>, bubbleSizeDp: Int) {
        // Show overlay first so the PreviewView surface is ready
        val overlayBinding = showFrontCameraOverlay(bubbleSizeDp, returnBinding = true) ?: return

        val frontPreview = Preview.Builder().build().also {
            it.setSurfaceProvider(overlayBinding.previewView.surfaceProvider)
        }

        val backGroup  = androidx.camera.core.UseCaseGroup.Builder().addUseCase(videoCapture).build()
        val frontGroup = androidx.camera.core.UseCaseGroup.Builder().addUseCase(frontPreview).build()

        val backConfig  = androidx.camera.lifecycle.SingleCameraConfig(
            CameraSelector.DEFAULT_BACK_CAMERA, backGroup, this
        )
        val frontConfig = androidx.camera.lifecycle.SingleCameraConfig(
            CameraSelector.DEFAULT_FRONT_CAMERA, frontGroup, this
        )
        cameraProvider!!.bindToLifecycle(listOf(backConfig, frontConfig))
    }

    // ═══════════════════════════════════════════════════════════
    //  STOP ALL
    // ═══════════════════════════════════════════════════════════
    private fun stopAll() {
        isRunning = false

        // Stop screen recording if running
        try { mediaRecorder?.stop() } catch (_: RuntimeException) {
            outputFile?.delete(); outputFile = null
        }
        mediaRecorder?.reset(); mediaRecorder?.release(); mediaRecorder = null
        virtualDisplay?.release(); virtualDisplay = null
        mediaProjection?.stop(); mediaProjection = null

        // Stop CameraX recording if running
        activeRecording?.stop(); activeRecording = null

        // Unbind all cameras
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}

        // Remove overlay
        try { overlayView?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        overlayView = null

        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED

        // Notify for screen mode (rear camera mode notifies via VideoRecordEvent.Finalize)
        if (recordingMode == MainActivity.MODE_SCREEN) {
            scanAndNotify(outputFile)
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  CAMERA OVERLAY (Front Camera Bubble)
    // ═══════════════════════════════════════════════════════════

    /**
     * Inflates the circular front-camera overlay into WindowManager.
     * Returns the binding if [returnBinding] = true (used for concurrent camera binding).
     * Otherwise also binds CameraX Preview internally.
     */
    private fun showFrontCameraOverlay(
        bubbleSizeDp: Int,
        returnBinding: Boolean = false
    ): CameraOverlayBinding? {
        val density = resources.displayMetrics.density
        val sizePx  = (bubbleSizeDp * density).toInt()

        val overlayBinding = CameraOverlayBinding.inflate(LayoutInflater.from(this))
        overlayView = overlayBinding.root

        val params = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = (24 * density).toInt()
            y = (100 * density).toInt()
        }
        windowManager.addView(overlayView, params)
        makeDraggable(overlayView!!, params)

        if (returnBinding) {
            // Caller (bindConcurrentCameras) will set the surfaceProvider
            return overlayBinding
        }

        // Standalone: bind front camera Preview internally
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(overlayBinding.previewView.surfaceProvider)
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))

        return null
    }

    private fun makeDraggable(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0; var initialY = 0
        var initialTouchX = 0f; var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX - (event.rawX - initialTouchX).toInt()
                    params.y = initialY - (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(view, params); true
                }
                else -> false
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════
    private fun buildOutputFile(): File {
        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val appDir = File(moviesDir, "ChartRecorder").also { it.mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(appDir, "CHART_$timestamp.mp4")
    }

    private fun scanAndNotify(file: File?) {
        file ?: return
        @Suppress("DEPRECATION")
        sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
            this.data = android.net.Uri.fromFile(file)
        })
        val msg = "✅ Saved: ${file.name}\n📁 Movies/ChartRecorder/"
        android.os.Handler(mainLooper).post {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    // ── Notification ─────────────────────────────────────────────
    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Chart Recorder", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "Recording in progress" }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, RecordingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val modeLabel = if (recordingMode == MainActivity.MODE_REAR) "Rear Camera" else "Screen"
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("🔴 Recording ($modeLabel) + Voice")
            .setContentText("Tap STOP to finish recording.")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_pause, "⏹ STOP", stopIntent
                ).build()
            ).build()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRunning) stopAll()
    }
}
