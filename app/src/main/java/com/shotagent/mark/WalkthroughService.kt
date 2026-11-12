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
                startCapture(resultCode, data)
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
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mediaProjection = mgr.getMediaProjection(resultCode, data)
        projection = mediaProjection
        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post { finishSession() }
            }
        }, mainHandler)

        videoFile = File(sessionDir, "walkthrough.mp4")
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
        mediaRecorder.setVideoEncodingBitRate(4_000_000)
        mediaRecorder.setVideoFrameRate(30)
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

        captureThread = HandlerThread("mark-capture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
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
                val rowPadding = rowStride - pixelStride * width
                val bitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888,
                )
                bitmap.copyPixelsFromBuffer(buffer)
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                if (bitmap != cropped) bitmap.recycle()
                val marked = drawStrokes(cropped, strokes, pendingViewW, pendingViewH)
                if (marked != cropped) cropped.recycle()
                val out = File(sessionDir, "still-${stillFiles.size + 1}.png")
                out.outputStream().use { stream ->
                    marked.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
                marked.recycle()
                stillFiles.add(out)
            } catch (_: Exception) {
                // Keep recording even if one still fails.
            } finally {
                image.close()
            }
        }, captureHandler)

        imageDisplay = mediaProjection.createVirtualDisplay(
            "mark-stills",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            captureHandler,
        )

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
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        val speech = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = speech
        fun listen() {
            if (!listening.get()) return
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            runCatching { speech.startListening(intent) }
        }
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
            override fun onPartialResults(partialResults: android.os.Bundle?) = Unit
            override fun onResults(results: android.os.Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotBlank()) transcriptParts.add(text)
                mainHandler.post { listen() }
            }
            override fun onError(error: Int) {
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) return
                mainHandler.postDelayed({ listen() }, 400)
            }
        })
        listen()
    }

    private fun finishSession() {
        if (!listening.getAndSet(false)) return
        runCatching { overlay?.dismiss() }
        overlay = null
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
        const val ACTION_START = "com.shotagent.mark.walkthrough.START"
        const val ACTION_STOP = "com.shotagent.mark.walkthrough.STOP"
        const val ACTION_REVIEW = "com.shotagent.mark.walkthrough.REVIEW"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_TRANSCRIPT = "transcript"
        const val EXTRA_VIDEO_PATH = "video_path"
        const val EXTRA_STILL_PATHS = "still_paths"
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
