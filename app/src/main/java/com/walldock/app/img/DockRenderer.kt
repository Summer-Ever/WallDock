package com.walldock.app.img

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.walldock.app.model.DockSettings
import com.walldock.app.model.GlassStyle
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * 把 Dock 栏「烧进」壁纸。
 *
 * 做法：先按旋转角度把 Dock 画到一张独立图层上（图层坐标 = 屏幕坐标偏移），
 * 玻璃后面的背景直接从整张壁纸上取，因此 Dock 看起来是真的悬在壁纸之上。
 */
object DockRenderer {

    private var noiseShader: BitmapShader? = null

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        pxPerDp: Float,
        backdrop: Bitmap,
        s: DockSettings,
    ) {
        if (!s.enabled || width <= 0 || height <= 0) return

        val barW = width * s.widthFraction.coerceIn(0.3f, 0.99f)
        val barH = (s.heightDp * pxPerDp).coerceAtLeast(8f)
        val cx = width / 2f
        val cy = height - s.bottomMarginDp * pxPerDp - barH / 2f

        val l = cx - barW / 2f
        val t = cy - barH / 2f
        val r = cx + barW / 2f
        val b = cy + barH / 2f

        val angle = s.angle
        val rad = Math.toRadians(angle.toDouble())
        val ca = cos(rad).toFloat()
        val sa = sin(rad).toFloat()

        // 旋转后的四个角，用来算图层包围盒
        fun rxp(x: Float, y: Float) = cx + (x - cx) * ca - (y - cy) * sa
        fun ryp(x: Float, y: Float) = cy + (x - cx) * sa + (y - cy) * ca

        val pad = 36f * pxPerDp
        val xs = floatArrayOf(rxp(l, t), rxp(r, t), rxp(r, b), rxp(l, b))
        val ys = floatArrayOf(ryp(l, t), ryp(r, t), ryp(r, b), ryp(l, b))
        val minX = xs.minOrNull() ?: 0f
        val maxX = xs.maxOrNull() ?: 0f
        val minY = ys.minOrNull() ?: 0f
        val maxY = ys.maxOrNull() ?: 0f

        val bx = floor(minX - pad).toInt().coerceAtLeast(0)
        val by = floor(minY - pad).toInt().coerceAtLeast(0)
        val bw = ceil(maxX + pad).toInt().coerceAtMost(width) - bx
        val bh = ceil(maxY + pad).toInt().coerceAtMost(height) - by
        if (bw <= 2 || bh <= 2) return

        val layer = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(layer)
        c.translate(-bx.toFloat(), -by.toFloat())
        c.rotate(angle, cx, cy)

        val rect = RectF(l, t, r, b)
        val radius = min(s.cornerRadiusDp * pxPerDp, barH / 2f).coerceAtLeast(0f)
        val path = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }

        // ---------- 1. 投影 ----------
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(72, 0, 0, 0)
            maskFilter = BlurMaskFilter(13f * pxPerDp, BlurMaskFilter.Blur.NORMAL)
        }
        c.save()
        c.translate(0f, 9f * pxPerDp)
        c.drawPath(path, shadow)
        c.restore()

        // ---------- 2. 玻璃主体 ----------
        val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        c.save()
        c.clipPath(path)
        c.drawBitmap(backdrop, 0f, 0f, bmpPaint)

        val whiteMix = when (s.style) {
            GlassStyle.FROSTED -> 0.62f
            GlassStyle.LIQUID -> 0.30f
            GlassStyle.CLEAR -> 0.10f
        }
        val tintAlpha = (s.opacity.coerceIn(0f, 1f) * whiteMix * 255f).toInt().coerceIn(0, 255)

        // 底色
        c.drawRect(rect, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(tintAlpha, 255, 255, 255)
        })

        // 上亮下暗的通透感
        val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, t, 0f, b,
                intArrayOf(
                    Color.argb(96, 255, 255, 255),
                    Color.argb(20, 255, 255, 255),
                    Color.argb(0, 255, 255, 255),
                    Color.argb(58, 0, 0, 0),
                ),
                floatArrayOf(0f, 0.32f, 0.66f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(rect, sheen)

        // 斜向高光条（液态玻璃的关键观感）
        if (s.style != GlassStyle.CLEAR) {
            val spec = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    l, t, r, t + barH * 1.5f,
                    intArrayOf(
                        Color.argb(84, 255, 255, 255),
                        Color.argb(0, 255, 255, 255),
                        Color.argb(46, 255, 255, 255),
                        Color.argb(0, 255, 255, 255),
                    ),
                    floatArrayOf(0f, 0.26f, 0.5f, 0.82f),
                    Shader.TileMode.CLAMP,
                )
            }
            c.drawRect(rect, spec)
        }

        // 上边缘内高光
        val topEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, t, 0f, t + barH * 0.26f,
                Color.argb(160, 255, 255, 255), Color.argb(0, 255, 255, 255),
                Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(l, t, r, t + barH * 0.26f, topEdge)

        // 下边缘内阴影
        val bottomEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, b - barH * 0.3f, 0f, b,
                Color.argb(0, 0, 0, 0), Color.argb(64, 0, 0, 0),
                Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(l, b - barH * 0.3f, r, b, bottomEdge)

        // 液态玻璃：左上角柔光球 + 边缘色散
        if (s.style == GlassStyle.LIQUID) {
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    l + barW * 0.24f, t + barH * 0.12f, barW * 0.62f,
                    Color.argb(52, 255, 255, 255), Color.argb(0, 255, 255, 255),
                    Shader.TileMode.CLAMP,
                )
            }
            c.drawRect(rect, glow)
        }

        // 细腻噪点，避免大面积渐变出现色带
        val noisePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = noiseShader()
            alpha = if (s.style == GlassStyle.LIQUID) 26 else 16
        }
        c.drawRect(rect, noisePaint)

        // 图标对位参考
        if (s.showGuides && s.slots > 0) {
            val slotW = barW / s.slots
            val iconSize = min(slotW * 0.58f, barH * 0.58f)
            val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.1f * pxPerDp
                color = Color.argb(78, 255, 255, 255)
            }
            for (i in 0 until s.slots) {
                val sx = l + slotW * i + slotW / 2f
                c.drawRoundRect(
                    RectF(sx - iconSize / 2f, cy - iconSize / 2f, sx + iconSize / 2f, cy + iconSize / 2f),
                    iconSize * 0.28f, iconSize * 0.28f, gp,
                )
            }
        }
        c.restore()

        // ---------- 3. 描边 ----------
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (if (s.style == GlassStyle.LIQUID) 1.7f else 1.2f) * pxPerDp
            shader = LinearGradient(
                l, t, l + barW * 0.58f, b,
                intArrayOf(
                    Color.argb(220, 255, 255, 255),
                    Color.argb(66, 255, 255, 255),
                    Color.argb(26, 255, 255, 255),
                    Color.argb(132, 255, 255, 255),
                ),
                floatArrayOf(0f, 0.38f, 0.74f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        c.drawPath(path, stroke)

        if (s.style == GlassStyle.LIQUID) {
            val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2.6f * pxPerDp
                shader = LinearGradient(
                    l, t, r, b,
                    intArrayOf(
                        Color.argb(76, 158, 232, 255),
                        Color.argb(0, 255, 255, 255),
                        Color.argb(64, 255, 196, 244),
                    ),
                    null,
                    Shader.TileMode.CLAMP,
                )
                maskFilter = BlurMaskFilter(3.2f * pxPerDp, BlurMaskFilter.Blur.NORMAL)
            }
            c.drawPath(path, rim)
        }

        canvas.drawBitmap(layer, bx.toFloat(), by.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        layer.recycle()
    }

    private fun noiseShader(): BitmapShader {
        noiseShader?.let { return it }
        val size = 64
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val px = IntArray(size * size)
        val rnd = java.util.Random(20261101L)
        for (i in px.indices) {
            val v = 128 + rnd.nextInt(127)
            val a = 20 + rnd.nextInt(46)
            px[i] = (a shl 24) or (v shl 16) or (v shl 8) or v
        }
        bmp.setPixels(px, 0, size, 0, 0, size, size)
        val sh = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        noiseShader = sh
        return sh
    }
}
