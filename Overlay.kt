package app.soundpad

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

class Overlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val host = FrameLayout(ctx)
    private var open = false
    private var attached = false

    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 16
        y = 240
    }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun show() {
        draw()
        wm.addView(host, lp)
        attached = true
    }

    fun hide() {
        if (!attached) return
        try {
            wm.removeView(host)
        } catch (_: Exception) {
        }
        attached = false
    }

    private fun relayout() {
        if (attached) wm.updateViewLayout(host, lp)
    }

    private fun draw() {
        host.removeAllViews()
        host.addView(if (open) panel() else bubble())
        relayout()
    }

    private fun round(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
    }

    private fun bubble(): View {
        val v = TextView(ctx).apply {
            text = "♪"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = round(Color.parseColor("#CC3F51B5"), 40)
            layoutParams = FrameLayout.LayoutParams(dp(52), dp(52))
        }
        drag(v) {
            open = true
            draw()
        }
        return v
    }

    private fun panel(): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = round(Color.parseColor("#EE1E1E1E"), 14)
        }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(ctx).apply {
            text = "⠿  Soundpad"
            setTextColor(Color.WHITE)
            setPadding(dp(4), dp(10), dp(4), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        drag(title) {}
        head.addView(title)
        head.addView(small("■") { Engine.stopAll() })
        head.addView(small("✕") {
            open = false
            draw()
        })
        col.addView(head)

        val grid = GridLayout(ctx).apply { columnCount = 3 }
        for (p in Store.pads) grid.addView(padButton(p))
        val sv = ScrollView(ctx).apply {
            addView(grid)
            layoutParams = LinearLayout.LayoutParams(dp(300), dp(320))
        }
        col.addView(sv)
        return col
    }

    private fun small(t: String, onClick: () -> Unit) = Button(ctx).apply {
        text = t
        minWidth = 0
        minimumWidth = 0
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(40))
        setOnClickListener { onClick() }
    }

    private fun padButton(p: Pad) = Button(ctx).apply {
        text = p.name
        textSize = 11f
        maxLines = 2
        isAllCaps = false
        layoutParams = GridLayout.LayoutParams().apply {
            width = dp(92)
            height = dp(56)
            setMargins(dp(2), dp(2), dp(2), dp(2))
        }
        setOnClickListener { Engine.trigger(p) }
    }

    private fun drag(v: View, tap: () -> Unit) {
        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX
                    sy = e.rawY
                    ox = lp.x
                    oy = lp.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - sx).toInt()
                    val dy = (e.rawY - sy).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) {
                        lp.x = ox + dx
                        lp.y = oy + dy
                        relayout()
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) tap()
            }
            true
        }
    }
}
