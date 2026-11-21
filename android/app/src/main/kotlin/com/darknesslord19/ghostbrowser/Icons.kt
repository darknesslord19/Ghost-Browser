package com.darknesslord19.ghostbrowser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/** Emoji yerine çizilen, her ekranda keskin görünen çizgi ikonları (24x24 ızgara). */
class IconView(ctx: Context, var kind: String) : View(ctx) {
    var tint: Int = Color.parseColor("#D8D8EA")
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2f
    }
    private val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    override fun onDraw(c: Canvas) {
        val inner = minOf(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom)
        val s = inner / 24f
        c.save()
        c.translate((width - 24 * s) / 2f, (height - 24 * s) / 2f)
        c.scale(s, s)
        p.color = tint
        f.color = tint
        paint(c)
        c.restore()
    }

    private fun paint(c: Canvas) {
        when (kind) {
            "back" -> { c.drawLine(5f, 12f, 19f, 12f, p); c.drawLine(11f, 6f, 5f, 12f, p); c.drawLine(5f, 12f, 11f, 18f, p) }
            "forward" -> { c.drawLine(19f, 12f, 5f, 12f, p); c.drawLine(13f, 6f, 19f, 12f, p); c.drawLine(19f, 12f, 13f, 18f, p) }
            "reload" -> {
                p.strokeWidth = 2.6f
                c.drawArc(RectF(5f, 5f, 19f, 19f), 20f, 290f, false, p)
                val a = Math.toRadians(-50.0)
                val ex = 12f + 7f * Math.cos(a).toFloat(); val ey = 12f + 7f * Math.sin(a).toFloat()
                val tx = (-Math.sin(a)).toFloat(); val ty = Math.cos(a).toFloat()
                val nx = Math.cos(a).toFloat(); val ny = Math.sin(a).toFloat()
                val ah = Path()
                ah.moveTo(ex + tx * 4.6f, ey + ty * 4.6f)
                ah.lineTo(ex + nx * 3.8f, ey + ny * 3.8f)
                ah.lineTo(ex - nx * 3.8f, ey - ny * 3.8f)
                ah.close()
                c.drawPath(ah, f)
            }
            "close" -> { c.drawLine(6f, 6f, 18f, 18f, p); c.drawLine(18f, 6f, 6f, 18f, p) }
            "home" -> {
                val pa = Path()
                pa.moveTo(4f, 11f); pa.lineTo(12f, 4f); pa.lineTo(20f, 11f)
                pa.moveTo(6f, 10f); pa.lineTo(6f, 20f); pa.lineTo(18f, 20f); pa.lineTo(18f, 10f)
                c.drawPath(pa, p)
            }
            "lock" -> {
                c.drawRoundRect(RectF(6f, 11f, 18f, 20f), 2.2f, 2.2f, p)
                c.drawArc(RectF(8f, 4f, 16f, 12f), 180f, 180f, false, p)
                c.drawLine(8f, 8f, 8f, 11f, p); c.drawLine(16f, 8f, 16f, 11f, p)
            }
            "info" -> { c.drawCircle(12f, 12f, 9f, p); c.drawCircle(12f, 8f, 0.6f, f); c.drawLine(12f, 11f, 12f, 17f, p) }
            "search" -> { c.drawCircle(10.5f, 10.5f, 5.5f, p); c.drawLine(14.6f, 14.6f, 19f, 19f, p) }
            "dots" -> { c.drawCircle(12f, 5f, 1.9f, f); c.drawCircle(12f, 12f, 1.9f, f); c.drawCircle(12f, 19f, 1.9f, f) }
            "plus" -> { c.drawLine(12f, 5f, 12f, 19f, p); c.drawLine(5f, 12f, 19f, 12f, p) }
            "clock" -> {
                c.drawCircle(12f, 12f, 9f, p)
                val pa = Path(); pa.moveTo(12f, 7f); pa.lineTo(12f, 12f); pa.lineTo(15.5f, 14f); c.drawPath(pa, p)
            }
            "gear" -> {
                c.drawCircle(12f, 12f, 3f, p)
                c.drawCircle(12f, 12f, 7f, p)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val ca = Math.cos(a).toFloat(); val sa = Math.sin(a).toFloat()
                    c.drawLine(12f + ca * 7f, 12f + sa * 7f, 12f + ca * 10f, 12f + sa * 10f, p)
                }
            }
            "phone" -> { c.drawRoundRect(RectF(7f, 3f, 17f, 21f), 2.4f, 2.4f, p); c.drawLine(10.5f, 18f, 13.5f, 18f, p) }
            "tv" -> { c.drawRoundRect(RectF(3f, 5f, 21f, 16f), 2.2f, 2.2f, p); c.drawLine(8f, 20f, 16f, 20f, p); c.drawLine(12f, 16f, 12f, 20f, p) }
            "shield" -> {
                val pa = Path()
                pa.moveTo(12f, 3f); pa.lineTo(19f, 6f); pa.lineTo(19f, 12f)
                pa.cubicTo(19f, 16.5f, 16f, 19.5f, 12f, 21f)
                pa.cubicTo(8f, 19.5f, 5f, 16.5f, 5f, 12f)
                pa.lineTo(5f, 6f); pa.close()
                c.drawPath(pa, p)
            }
            "fullscreen" -> {
                val pa = Path()
                pa.moveTo(4f, 9f); pa.lineTo(4f, 4f); pa.lineTo(9f, 4f)
                pa.moveTo(15f, 4f); pa.lineTo(20f, 4f); pa.lineTo(20f, 9f)
                pa.moveTo(20f, 15f); pa.lineTo(20f, 20f); pa.lineTo(15f, 20f)
                pa.moveTo(9f, 20f); pa.lineTo(4f, 20f); pa.lineTo(4f, 15f)
                c.drawPath(pa, p)
            }
            "power" -> { c.drawArc(RectF(4.5f, 4.5f, 19.5f, 19.5f), -55f, 290f, false, p); c.drawLine(12f, 3f, 12f, 11f, p) }
            "play" -> {
                val pa = Path(); pa.moveTo(8f, 5f); pa.lineTo(19f, 12f); pa.lineTo(8f, 19f); pa.close()
                c.drawPath(pa, f)
            }
            "tabs" -> { c.drawRoundRect(RectF(3f, 7f, 17f, 21f), 2.5f, 2.5f, p); val pa = Path(); pa.moveTo(7f, 4f); pa.lineTo(7f, 3.5f); pa.lineTo(20.5f, 3.5f); pa.lineTo(20.5f, 17f); pa.lineTo(20f, 17f); c.drawPath(pa, p) }
        }
    }
}

/** Chrome tarzı yuvarlak kare içinde sekme sayısı. */
class TabCountView(ctx: Context) : View(ctx) {
    var count = 1
        set(v) { field = v; invalidate() }
    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#D8D8EA")
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D8D8EA")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val s = 22f * d
        val l = (width - s) / 2f
        val t = (height - s) / 2f
        box.strokeWidth = 2f * d
        c.drawRoundRect(RectF(l, t, l + s, t + s), 6f * d, 6f * d, box)
        txt.textSize = (if (count > 9) 11f else 13f) * d
        val label = if (count > 99) ":)" else count.toString()
        c.drawText(label, width / 2f, height / 2f - (txt.descent() + txt.ascent()) / 2f, txt)
    }
}

/** Menüdeki aç/kapa anahtarı. */
class ToggleView(ctx: Context, on: Boolean) : View(ctx) {
    var on: Boolean = on
        set(v) { field = v; invalidate() }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        track.color = if (on) Color.parseColor("#8B5CF6") else Color.parseColor("#3A3A52")
        c.drawRoundRect(RectF(0f, 0f, w, h), h / 2f, h / 2f, track)
        val r = h / 2f - 3f * resources.displayMetrics.density
        c.drawCircle(if (on) w - h / 2f else h / 2f, h / 2f, r, knob)
    }
}
