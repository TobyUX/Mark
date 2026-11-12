package com.shotagent.mark

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class WalkthroughOverlay(
    private val context: Context,
    private val onDone: () -> Unit,
    private val onStrokeFinished: (strokes: List<StrokePath>, viewWidth: Int, viewHeight: Int) -> Unit,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var barView: LinearLayout? = null
    private var drawView: DrawOverlayView? = null
    private var highlighting = false

    private var barParams: WindowManager.LayoutParams? = null
    private var dragStartX = 0
    private var dragStartY = 0
    private var barStartX = 0
    private var barStartY = 0

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (barView != null) return
        val density = context.resources.displayMetrics.density
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xEE1C1B1F.toInt())
            setPadding(
                (12 * density).toInt(),
                (10 * density).toInt(),
                (12 * density).toInt(),
                (10 * density).toInt(),
            )
            elevation = 12 * density
        }
        val label = TextView(context).apply {
            text = "Mark"
            setTextColor(Color.WHITE)
            setPadding(0, 0, (12 * density).toInt(), 0)
        }
        val highlight = Button(context).apply {
            text = "Highlight"
            setOnClickListener { toggleHighlight() }
        }
        val done = Button(context).apply {
            text = "Done"
            setOnClickListener { onDone() }
        }
        bar.addView(label)
        bar.addView(highlight)
        bar.addView(done)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (24 * density).toInt()
            y = (120 * density).toInt()
        }
        label.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = event.rawX.toInt()
                    dragStartY = event.rawY.toInt()
                    barStartX = params.x
                    barStartY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = barStartX + (event.rawX.toInt() - dragStartX)
                    params.y = barStartY + (event.rawY.toInt() - dragStartY)
                    windowManager.updateViewLayout(bar, params)
                    true
                }
                else -> false
            }
        }
        windowManager.addView(bar, params)
        barView = bar
        barParams = params
        this.highlightButton = highlight
    }

    private var highlightButton: Button? = null

    private fun toggleHighlight() {
        highlighting = !highlighting
        highlightButton?.text = if (highlighting) "Stop highlight" else "Highlight"
        if (highlighting) showDrawLayer() else hideDrawLayer()
    }

    private fun showDrawLayer() {
        if (drawView != null) return
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        val view = DrawOverlayView(context) { strokes, w, h ->
            onStrokeFinished(strokes, w, h)
        }
        windowManager.addView(view, params)
        drawView = view
        // Keep the control bar above the draw layer.
        barView?.let { bar ->
            windowManager.removeView(bar)
            windowManager.addView(bar, barParams)
        }
    }

    private fun hideDrawLayer() {
        drawView?.let { windowManager.removeView(it) }
        drawView = null
    }

    fun dismiss() {
        hideDrawLayer()
        barView?.let {
            runCatching { windowManager.removeView(it) }
        }
        barView = null
        barParams = null
    }
}

data class StrokePath(
    val points: List<Pair<Float, Float>>,
)

@SuppressLint("ViewConstructor")
private class DrawOverlayView(
    context: Context,
    private val onStrokeFinished: (List<StrokePath>, Int, Int) -> Unit,
) : View(context) {
    private val paint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 8f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    private val strokes = mutableListOf<StrokePath>()
    private var active = mutableListOf<Pair<Float, Float>>()
    private var activePath = Path()

    override fun onDraw(canvas: Canvas) {
        strokes.forEach { stroke ->
            if (stroke.points.size < 2) return@forEach
            val path = Path()
            stroke.points.forEachIndexed { index, point ->
                if (index == 0) path.moveTo(point.first, point.second)
                else path.lineTo(point.first, point.second)
            }
            canvas.drawPath(path, paint)
        }
        if (active.size >= 2) canvas.drawPath(activePath, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = mutableListOf(event.x to event.y)
                activePath = Path().apply { moveTo(event.x, event.y) }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                active.add(event.x to event.y)
                activePath.lineTo(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (active.size >= 2) {
                    val finished = strokes.toList() + StrokePath(active.toList())
                    strokes.clear()
                    active.clear()
                    activePath.reset()
                    invalidate()
                    onStrokeFinished(finished, width, height)
                } else {
                    active.clear()
                    activePath.reset()
                    invalidate()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
