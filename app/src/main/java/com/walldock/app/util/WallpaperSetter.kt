package com.walldock.app.util

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap

object WallpaperSetter {

    /** 只设置桌面壁纸（Dock 栏画在桌面壁纸上） */
    fun setHome(context: Context, bitmap: Bitmap) {
        set(context, bitmap, WallpaperManager.FLAG_SYSTEM)
    }

    /** 只设置锁屏壁纸 */
    fun setLock(context: Context, bitmap: Bitmap) {
        set(context, bitmap, WallpaperManager.FLAG_LOCK)
    }

    private fun set(context: Context, bitmap: Bitmap, which: Int) {
        val wm = WallpaperManager.getInstance(context)
        if (!wm.isWallpaperSupported) {
            throw IllegalStateException("当前设备不支持设置壁纸")
        }
        if (!wm.isSetWallpaperAllowed) {
            throw IllegalStateException("系统限制了壁纸修改，请检查设备管理器/家长控制设置")
        }
        wm.setBitmap(bitmap, null, true, which)
    }
}
