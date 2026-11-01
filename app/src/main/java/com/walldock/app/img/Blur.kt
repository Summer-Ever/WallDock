package com.walldock.app.img

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 高性能高斯近似模糊：先降采样，再做 3 次分离盒式模糊（等价于三次卷积），最后升采样。
 * 完全不依赖 RenderScript / RenderEffect，任何 Android 版本表现一致。
 */
object Blur {

    /** 把 0..1 的强度换算成当前画布宽度下的模糊半径（px） */
    fun radiusFor(strength: Float, canvasWidth: Int): Float {
        val s = strength.coerceIn(0f, 1f)
        return s * s * canvasWidth * 0.045f
    }

    fun blur(src: Bitmap, radiusPx: Float, extraDownscale: Int = 1): Bitmap {
        val r = radiusPx.roundToInt()
        if (r < 1) return src

        val extra = extraDownscale.coerceAtLeast(1)
        val scale = when {
            r >= 20 -> 4
            r >= 9 -> 2
            else -> 1
        } * extra
        val w = max(1, src.width / scale)
        val h = max(1, src.height / scale)
        val small = if (scale == 1) {
            src.copy(Bitmap.Config.ARGB_8888, true)
        } else {
            Bitmap.createScaledBitmap(src, w, h, true)
        }
        if (small.width < 2 || small.height < 2) return small

        val effective = max(1, (r.toFloat() / scale).roundToInt())
        boxBlurInPlace(small, effective)

        if (scale == 1) return small
        val out = Bitmap.createScaledBitmap(small, src.width, src.height, true)
        small.recycle()
        return out
    }

    private fun boxBlurInPlace(bmp: Bitmap, radius: Int) {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(3) {
            blurHorizontal(pixels, tmp, w, h, radius)
            blurVertical(tmp, pixels, w, h, radius)
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun blurHorizontal(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val base = y * w
            var a = 0
            var rr = 0
            var gg = 0
            var bb = 0
            for (k in -r..r) {
                val px = src[base + k.coerceIn(0, w - 1)]
                a += (px ushr 24) and 0xFF
                rr += (px shr 16) and 0xFF
                gg += (px shr 8) and 0xFF
                bb += px and 0xFF
            }
            for (x in 0 until w) {
                dst[base + x] = ((a / div) shl 24) or ((rr / div) shl 16) or ((gg / div) shl 8) or (bb / div)
                val outPx = src[base + (x - r).coerceIn(0, w - 1)]
                val inPx = src[base + (x + r + 1).coerceIn(0, w - 1)]
                a += ((inPx ushr 24) and 0xFF) - ((outPx ushr 24) and 0xFF)
                rr += ((inPx shr 16) and 0xFF) - ((outPx shr 16) and 0xFF)
                gg += ((inPx shr 8) and 0xFF) - ((outPx shr 8) and 0xFF)
                bb += (inPx and 0xFF) - (outPx and 0xFF)
            }
        }
    }

    private fun blurVertical(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (x in 0 until w) {
            var a = 0
            var rr = 0
            var gg = 0
            var bb = 0
            for (k in -r..r) {
                val px = src[k.coerceIn(0, h - 1) * w + x]
                a += (px ushr 24) and 0xFF
                rr += (px shr 16) and 0xFF
                gg += (px shr 8) and 0xFF
                bb += px and 0xFF
            }
            for (y in 0 until h) {
                dst[y * w + x] = ((a / div) shl 24) or ((rr / div) shl 16) or ((gg / div) shl 8) or (bb / div)
                val outPx = src[(y - r).coerceIn(0, h - 1) * w + x]
                val inPx = src[(y + r + 1).coerceIn(0, h - 1) * w + x]
                a += ((inPx ushr 24) and 0xFF) - ((outPx ushr 24) and 0xFF)
                rr += ((inPx shr 16) and 0xFF) - ((outPx shr 16) and 0xFF)
                gg += ((inPx shr 8) and 0xFF) - ((outPx shr 8) and 0xFF)
                bb += (inPx and 0xFF) - (outPx and 0xFF)
            }
        }
    }
}
