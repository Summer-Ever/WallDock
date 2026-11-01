package com.walldock.app.img

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import com.walldock.app.model.CropTransform
import kotlin.math.max

object BitmapUtils {

    /** 用系统解码器读取图片（自动处理 EXIF 方向），并限制最大边长 */
    fun decode(context: Context, uri: Uri, maxDim: Int = 4096): Bitmap? = try {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val w = info.size.width
            val h = info.size.height
            val m = max(w, h)
            if (m > maxDim && w > 0 && h > 0) {
                val s = maxDim.toFloat() / m
                decoder.setTargetSize(max(1, (w * s).toInt()), max(1, (h * s).toInt()))
            }
        }
    } catch (t: Throwable) {
        null
    }

    /** 计算 cover 铺满 + 缩放 + 平移的变换矩阵 */
    fun coverMatrix(
        srcW: Int,
        srcH: Int,
        dstW: Float,
        dstH: Float,
        t: CropTransform,
    ): Matrix {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0f || dstH <= 0f) return Matrix()
        val cover = max(dstW / srcW, dstH / srcH)
        val zoom = t.zoom.coerceIn(1f, 6f)
        val scale = cover * zoom
        val drawnW = srcW * scale
        val drawnH = srcH * scale
        val maxPanX = ((drawnW - dstW) / 2f).coerceAtLeast(0f)
        val maxPanY = ((drawnH - dstH) / 2f).coerceAtLeast(0f)
        val dx = maxPanX * t.panX.coerceIn(-1f, 1f)
        val dy = maxPanY * t.panY.coerceIn(-1f, 1f)
        val left = (dstW - drawnW) / 2f + dx
        val top = (dstH - drawnH) / 2f + dy
        return Matrix().apply {
            postScale(scale, scale)
            postTranslate(left, top)
        }
    }

    /** 按裁剪参数渲染一张铺满目标尺寸的位图 */
    fun renderCover(src: Bitmap, w: Int, h: Int, t: CropTransform): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(src, coverMatrix(src.width, src.height, w.toFloat(), h.toFloat(), t), paint)
        return out
    }

    /** 首次启动的示例渐变背景，让用户立刻看到效果 */
    fun demoBitmap(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, w * 0.7f, h.toFloat(),
            intArrayOf(0xFF2A1B5E.toInt(), 0xFF4C3BC7.toInt(), 0xFF6FC7E8.toInt(), 0xFFF3B8C9.toInt()),
            floatArrayOf(0f, 0.4f, 0.75f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

        // 叠一层柔光，让模糊效果更容易看出来
        val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        glow.shader = LinearGradient(
            0f, h * 0.55f, w.toFloat(), h.toFloat(),
            intArrayOf(0x00000000, 0x55000000),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), glow)
        return bmp
    }
}
