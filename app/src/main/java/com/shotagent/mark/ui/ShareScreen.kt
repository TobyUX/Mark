package com.shotagent.mark.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import kotlin.math.min

data class MarkStroke(
    val color: Color,
    val points: List<Offset>,
)

private val pens = listOf(Color.Red, Color.Blue, Color.Yellow, Color.White)

@Composable
fun ShareScreen(
    bitmap: Bitmap,
    sending: Boolean,
    status: String?,
    micGranted: Boolean,
    onLog: (ByteArray, String) -> Unit,
    onBuild: (ByteArray, String) -> Unit,
) {
    val context = LocalContext.current
    var strokes by remember { mutableStateOf(listOf<MarkStroke>()) }
    var active by remember { mutableStateOf<MarkStroke?>(null) }
    var color by remember { mutableStateOf(Color.Red) }
    var transcript by remember { mutableStateOf("") }

    DisposableEffect(micGranted) {
        if (!micGranted || !SpeechRecognizer.isRecognitionAvailable(context)) {
            onDispose { }
        } else {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            val handler = Handler(Looper.getMainLooper())
            var listening = true
            fun start() {
                if (!listening) return
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
                recognizer.startListening(intent)
            }
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onPartialResults(partial: Bundle?) {
                    val text = partial.bestText()
                    if (text.isNotBlank()) transcript = text
                }
                override fun onResults(results: Bundle?) {
                    val text = results.bestText()
                    if (text.isNotBlank()) transcript = text
                    handler.post { start() }
                }
                override fun onError(error: Int) {
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) return
                    handler.postDelayed({ start() }, 400)
                }
            })
            start()
            onDispose {
                listening = false
                handler.removeCallbacksAndMessages(null)
                recognizer.destroy()
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pens.forEach { pen ->
                TextButton(onClick = { color = pen }) {
                    Box(
                        Modifier
                            .background(pen, CircleShape)
                            .padding(12.dp),
                    )
                }
            }
            TextButton(
                onClick = { if (strokes.isNotEmpty()) strokes = strokes.dropLast(1) },
                enabled = strokes.isNotEmpty(),
            ) { Text("Undo", color = Color.White) }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(bitmap, color) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val point = toBitmap(offset, size.width.toFloat(), size.height.toFloat(), bitmap)
                            if (point != null) active = MarkStroke(color, listOf(point))
                        },
                        onDrag = { change, _ ->
                            val point = toBitmap(change.position, size.width.toFloat(), size.height.toFloat(), bitmap)
                            if (point != null) {
                                active = active?.let { it.copy(points = it.points + point) }
                            }
                            change.consume()
                        },
                        onDragEnd = {
                            active?.let { strokes = strokes + it }
                            active = null
                        },
                        onDragCancel = { active = null },
                    )
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val rect = fitted(size.width, size.height, bitmap.width.toFloat(), bitmap.height.toFloat())
                drawImage(
                    image = bitmap.asImageBitmap(),
                    dstOffset = androidx.compose.ui.unit.IntOffset(rect.left.toInt(), rect.top.toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(rect.width.toInt(), rect.height.toInt()),
                )
                (strokes + listOfNotNull(active)).forEach { stroke ->
                    if (stroke.points.size < 2) return@forEach
                    val path = Path()
                    stroke.points.forEachIndexed { index, point ->
                        val x = rect.left + point.x / bitmap.width * rect.width
                        val y = rect.top + point.y / bitmap.height * rect.height
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(
                        path,
                        stroke.color,
                        style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
        }
        Text(
            text = when {
                !micGranted -> "Allow the microphone to dictate. You can still circle and send."
                transcript.isBlank() -> "Listening"
                else -> transcript
            },
            color = Color.White,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (!status.isNullOrBlank()) {
            Text(
                status,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Button(
            onClick = { onLog(pngUnderLimit(flatten(bitmap, strokes)), transcript) },
            enabled = !sending,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) { Text(if (sending) "Working…" else "Log") }
        OutlinedButton(
            onClick = { onBuild(pngUnderLimit(flatten(bitmap, strokes)), transcript) },
            enabled = !sending,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        ) { Text("Build") }
    }
}

private fun Bundle?.bestText(): String =
    this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

private data class FitRect(val left: Float, val top: Float, val width: Float, val height: Float)

private fun fitted(viewW: Float, viewH: Float, bmpW: Float, bmpH: Float): FitRect {
    val scale = min(viewW / bmpW, viewH / bmpH)
    val width = bmpW * scale
    val height = bmpH * scale
    return FitRect((viewW - width) / 2f, (viewH - height) / 2f, width, height)
}

private fun toBitmap(offset: Offset, viewW: Float, viewH: Float, bitmap: Bitmap): Offset? {
    val rect = fitted(viewW, viewH, bitmap.width.toFloat(), bitmap.height.toFloat())
    if (offset.x < rect.left || offset.y < rect.top) return null
    if (offset.x > rect.left + rect.width || offset.y > rect.top + rect.height) return null
    val x = (offset.x - rect.left) / rect.width * bitmap.width
    val y = (offset.y - rect.top) / rect.height * bitmap.height
    return Offset(x, y)
}

fun flatten(bitmap: Bitmap, strokes: List<MarkStroke>): Bitmap {
    val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = android.graphics.Canvas(out)
    val paint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
        strokeWidth = (maxOf(bitmap.width, bitmap.height) / 90f).coerceAtLeast(8f)
    }
    strokes.forEach { stroke ->
        if (stroke.points.size < 2) return@forEach
        paint.color = stroke.color.toArgb()
        val path = android.graphics.Path()
        stroke.points.forEachIndexed { index, point ->
            if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        canvas.drawPath(path, paint)
    }
    return out
}

fun pngUnderLimit(bitmap: Bitmap): ByteArray {
    var current = bitmap
    var bytes = compressPng(current)
    while (bytes.size > 8 * 1024 * 1024 && current.width > 480) {
        val scaled = Bitmap.createScaledBitmap(
            current,
            (current.width * 2 / 3).coerceAtLeast(1),
            (current.height * 2 / 3).coerceAtLeast(1),
            true,
        )
        if (current != bitmap) current.recycle()
        current = scaled
        bytes = compressPng(current)
    }
    if (current != bitmap) current.recycle()
    return bytes
}

private fun compressPng(bitmap: Bitmap): ByteArray {
    val stream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
    return stream.toByteArray()
}
