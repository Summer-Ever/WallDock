package com.walldock.app.util

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.compose.ui.unit.IntSize

object Screen {

    /** 真实屏幕像素尺寸（含状态栏/导航栏区域），壁纸需要按这个尺寸生成 */
    fun size(context: Context): IntSize {
        val wm = context.getSystemService(WindowManager::class.java)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.maximumWindowMetrics.bounds
                if (bounds.width() > 0 && bounds.height() > 0) {
                    IntSize(bounds.width(), bounds.height())
                } else fallback(context)
            } else {
                fallback(context)
            }
        } catch (t: Throwable) {
            fallback(context)
        }
    }

    private fun fallback(context: Context): IntSize {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        context.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(dm)
        return IntSize(dm.widthPixels, dm.heightPixels)
    }

    fun density(context: Context): Float = context.resources.displayMetrics.density
}
