package com.shotagent.mark

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class WalkthroughService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var recorderDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var imageDisplay: VirtualDisplay? = null
    private var overlay: WalkthroughOverlay? = null
    private var recognizer: SpeechRecognizer? = null
    private var videoFile: File? = null
    private val sessionDir by lazy {
        File(cacheDir, "walkthrough-${System.currentTimeMillis()}").also { it.mkdirs() }
    }
    private val stillFiles = mutableListOf<File>()
    private val transcriptParts = mutableListOf<String>()
    private val listening = AtomicBoolean(true)
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var pendingStrokes: List<StrokePath>? = null
    private var pendingViewW = 0
    private var pendingViewH = 0
    private var screenWidth = 0
    private var screenHeight = 0
    private var startError: String? = null
    private var lastPartial: String = ""
    private var speechRestartAttempt = 0
    private val restartSpeech = Runnable { startListeningPass() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                finishSession()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (data == null || resultCode != Activity.RESULT_OK) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundNotification()
                try {
                    startCapture(resultCode, data)
                } catch (error: Exception) {
                    Log.e(TAG, "Walkthrough capture failed to start", error)
                    startError = error.message ?: "Could not start screen capture."
                    finishSession()
                }
                return START_STICKY
            }
        }
        stopSelf()
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, WalkthroughService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, MarkApp.WALK_CHANNEL)
            .setContentTitle("Mark walkthrough")
            .setContentText("Recording. Tap Done on the floating bar when finished.")
            .setSmallIcon(R.drawable.ic_stat_mark)
            .setOngoing(true)
            .addAction(0, "Done", stopIntent)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        val bounds = screenBounds()
        screenWidth = bounds.width().coerceAtLeast(2)
        screenHeight = bounds.height().coerceAtLeast(2)
        val density = resources.displayMetrics.densityDpi
        val (recordW, recordH) = recordingSize(screenWidth, screenHeight)

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mediaProjection = mgr.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("Screen capture permission expired. Try Start walkthrough again.")
        projection = mediaProjection
        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post { finishSession() }
            }
        }, mainHandler)

        captureThread = HandlerThread("mark-capture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)

        val videoStarted = startVideoRecorder(mediaProjection, recordW, recordH, density)
        val stillsStarted = startStillCapture(mediaProjection, density)
        when {
            !videoStarted && !stillsStarted ->
                throw IllegalStateException("Could not start video or still capture on this phone.")
            !videoStarted ->
                startError = "Video encoder failed on this phone. Highlight stills will still work."
            !stillsStarted ->
                startError = "Highlight stills failed on this phone. Video will still be saved."
        }

        mainHandler.post {
            overlay = WalkthroughOverlay(
                context = this,
                onDone = { finishSession() },
                onStrokeFinished = { strokes, viewW, viewH ->
                    pendingViewW = viewW
                    pendingViewH = viewH
                    pendingStrokes = strokes
                },
            ).also { it.show() }
            startSpeech()
        }
    }

    private fun startVideoRecorder(
        mediaProjection: MediaProjection,
        width: Int,
        height: Int,
        density: Int,
    ): Boolean {
        videoFile = File(sessionDir, "walkthrough.mp4")
        return try {
            val mediaRecorder = if (Build.VERSION.SDK_INT >= 31) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder = mediaRecorder
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            mediaRecorder.setVideoSize(width, height)
            mediaRecorder.setVideoEncodingBitRate((width * height * 4).coerceIn(1_500_000, 6_000_000))
            mediaRecorder.setVideoFrameRate(24)
            mediaRecorder.setOutputFile(videoFile!!.absolutePath)
            mediaRecorder.prepare()

            recorderDisplay = mediaProjection.createVirtualDisplay(
                "mark-recorder",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder.surface,
                null,
                null,
            )
            mediaRecorder.start()
            true
        } catch (error: Exception) {
            Log.e(TAG, "MediaRecorder failed for ${width}x$height", error)
            runCatching { recorder?.reset() }
            runCatching { recorder?.release() }
            recorder = null
            runCatching { recorderDisplay?.release() }
            recorderDisplay = null
            videoFile = null
            false
        }
    }

    private fun startStillCapture(mediaProjection: MediaProjection, density: Int): Boolean {
        return try {
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val strokes = pendingStrokes
                if (strokes == null) {
                    image.close()
                    return@setOnImageAvailableListener
                }
                pendingStrokes = null
                try {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * screenWidth
                    val bitmap = Bitmap.createBitmap(
                        screenWidth + rowPadding / pixelStride,
                        screenHeight,
                        Bitmap.Config.ARGB_8888,
                    )
                    bitmap.copyPixelsFromBuffer(buffer)
                    val cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
                    if (bitmap != cropped) bitmap.recycle()
                    val marked = drawStrokes(cropped, strokes, pendingViewW, pendingViewH)
                    if (marked != cropped) cropped.recycle()
                    val out = File(sessionDir, "still-${stillFiles.size + 1}.png")
                    out.outputStream().use { stream ->
                        marked.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    }
                    marked.recycle()
                    stillFiles.add(out)
                } catch (error: Exception) {
                    Log.e(TAG, "Still capture failed", error)
                } finally {
                    image.close()
                }
            }, captureHandler)

            imageDisplay = mediaProjection.createVirtualDisplay(
                "mark-stills",
                screenWidth,
                screenHeight,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                captureHandler,
            )
            true
        } catch (error: Exception) {
            Log.e(TAG, "Still virtual display failed", error)
            runCatching { imageDisplay?.release() }
            imageDisplay = null
            runCatching { imageReader?.close() }
            imageReader = null
            false
        }
    }

    private fun screenBounds(): Rect {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            // Service context: maximum bounds are the full display used for capture.
            wm.maximumWindowMetrics.bounds
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
    }

    private fun recordingSize(rawW: Int, rawH: Int): Pair<Int, Int> {
        var width = rawW
        var height = rawH
        val longest = maxOf(width, height)
        if (longest > MAX_RECORD_EDGE) {
            val scale = MAX_RECORD_EDGE.toFloat() / longest
            width = (width * scale).toInt()
            height = (height * scale).toInt()
        }
        width = width.and(1.inv()).coerceAtLeast(16)
        height = height.and(1.inv()).coerceAtLeast(16)
        return width to height
    }

    private fun drawStrokes(
        source: Bitmap,
        strokes: List<StrokePath>,
        viewW: Int,
        viewH: Int,
    ): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
            strokeWidth = (maxOf(out.width, out.height) / 90f).coerceAtLeast(8f)
        }
        val scaleX = if (viewW > 0) out.width.toFloat() / viewW else 1f
        val scaleY = if (viewH > 0) out.height.toFloat() / viewH else 1f
        strokes.forEach { stroke ->
            if (stroke.points.size < 2) return@forEach
            val path = Path()
            stroke.points.forEachIndexed { index, point ->
                val x = point.first * scaleX
                val y = point.second * scaleY
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
        }
        return out
    }

    private fun startSpeech() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            startError = listOfNotNull(
                startError,
                "Speech recognition is not available on this phone.",
            ).joinToString(" ")
            return
        }
        val speech = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = speech
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {
                speechRestartAttempt = 0
            }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
            override fun onPartialResults(partialResults: android.os.Bundle?) {
                lastPartial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
            }
            override fun onResults(results: android.os.Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                    .ifBlank { lastPartial }
                commitTranscript(text)
                lastPartial = ""
                scheduleSpeechRestart(350L)
            }
            override fun onError(error: Int) {
                when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> return
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                    SpeechRecognizer.ERROR_CLIENT,
                    -> {
                        speechRestartAttempt += 1
                        val delay = (500L * speechRestartAttempt).coerceAtMost(2_500L)
                        scheduleSpeechRestart(delay)
                    }
                    else -> {
                        // Keep going after silence / no-match so longer walkthroughs keep capturing.
                        if (lastPartial.isNotBlank()) {
                            commitTranscript(lastPartial)
                            lastPartial = ""
                        }
                        scheduleSpeechRestart(400L)
                    }
                }
            }
        })
        startListeningPass()
    }

    private fun commitTranscript(text: String) {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return
        val last = transcriptParts.lastOrNull()
        if (last == cleaned) return
        transcriptParts.add(cleaned)
    }

    private fun scheduleSpeechRestart(delayMs: Long) {
        mainHandler.removeCallbacks(restartSpeech)
        if (!listening.get()) return
        mainHandler.postDelayed(restartSpeech, delayMs)
    }

    private fun startListeningPass() {
        if (!listening.get()) return
        val speech = recognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Allow short pauses without ending the whole utterance.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_500)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1_500)
        }
        runCatching {
            speech.cancel()
            speech.startListening(intent)
        }.onFailure { error ->
            Log.e(TAG, "Speech startListening failed", error)
            scheduleSpeechRestart(700L)
        }
    }

    private fun finishSession() {
        if (!listening.getAndSet(false)) return
        mainHandler.removeCallbacks(restartSpeech)
        runCatching { overlay?.dismiss() }
        overlay = null
        if (lastPartial.isNotBlank()) {
            commitTranscript(lastPartial)
            lastPartial = ""
        }
        runCatching { recognizer?.stopListening() }
        runCatching { recognizer?.destroy() }
        recognizer = null

        runCatching { recorder?.stop() }
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { recorderDisplay?.release() }
        recorderDisplay = null
        runCatching { imageDisplay?.release() }
        imageDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { projection?.stop() }
        projection = null
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null

        val transcript = transcriptParts.joinToString("\n").trim()
        val video = videoFile?.takeIf { it.exists() && it.length() > 0 }
        val stills = stillFiles.filter { it.exists() && it.length() > 0 }
        val review = Intent(this, MainActivity::class.java).apply {
            action = ACTION_REVIEW
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_TRANSCRIPT, transcript)
            putExtra(EXTRA_VIDEO_PATH, video?.absolutePath)
            putStringArrayListExtra(
                EXTRA_STILL_PATHS,
                ArrayList(stills.map { it.absolutePath }),
            )
            startError?.let { putExtra(EXTRA_ERROR, it) }
        }
        startActivity(review)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (listening.get()) finishSession()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MarkWalkthrough"
        private const val MAX_RECORD_EDGE = 1280
        const val ACTION_START = "com.shotagent.mark.walkthrough.START"
        const val ACTION_STOP = "com.shotagent.mark.walkthrough.STOP"
        const val ACTION_REVIEW = "com.shotagent.mark.walkthrough.REVIEW"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_TRANSCRIPT = "transcript"
        const val EXTRA_VIDEO_PATH = "video_path"
        const val EXTRA_STILL_PATHS = "still_paths"
        const val EXTRA_ERROR = "error"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, WalkthroughService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
