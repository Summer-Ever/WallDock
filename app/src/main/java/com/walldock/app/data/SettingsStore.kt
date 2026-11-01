package com.walldock.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.walldock.app.model.CropTransform
import com.walldock.app.model.DockSettings
import com.walldock.app.model.EditorState
import com.walldock.app.model.GlassStyle
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/** 一次会话中被记住的配置 */
data class SavedSetup(
    val homeBlur: Float = 0f,
    val lockBlur: Float = 0f,
    val linkBlur: Boolean = true,
    val showGrid: Boolean = false,
    val desktopPreview: Boolean = false,
    val dock: DockSettings = DockSettings(),
    val transform: CropTransform = CropTransform(),
    /** 上次的壁纸是否还缓存着 */
    val hasWallpaper: Boolean = false,
)

/**
 * 配置持久化。
 *
 * - Dock / 模糊 / 裁剪参数：SharedPreferences 里存一段 JSON（不引入额外序列化依赖）
 * - 上次用的壁纸：以缩略原图形式落盘到 filesDir，重开 App 直接接着上次继续
 *
 * 目的：换壁纸时 Dock 参数完全不用重调。
 */
class SettingsStore(context: Context) {

    private val app: Context = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val wallpaperFile = File(app.filesDir, WALLPAPER_NAME)

    val hasWallpaper: Boolean get() = wallpaperFile.exists() && wallpaperFile.length() > 0

    // ---------------- 读取 ----------------

    fun load(): SavedSetup {
        val raw = prefs.getString(KEY_SETUP, null) ?: return SavedSetup(hasWallpaper = hasWallpaper)
        return try {
            parse(JSONObject(raw))
        } catch (t: Throwable) {
            // 数据损坏就退回默认值，不让 App 起不来
            SavedSetup(hasWallpaper = hasWallpaper)
        }
    }

    private fun parse(o: JSONObject): SavedSetup {
        val blur = o.optJSONObject("blur") ?: JSONObject()
        val dockObj = o.optJSONObject("dock") ?: JSONObject()
        val tr = o.optJSONObject("transform") ?: JSONObject()
        val d = DockSettings()
        return SavedSetup(
            homeBlur = blur.optDouble("home", 0.0).toFloat().coerceIn(0f, 1f),
            lockBlur = blur.optDouble("lock", 0.0).toFloat().coerceIn(0f, 1f),
            linkBlur = blur.optBoolean("link", true),
            showGrid = o.optBoolean("grid", false),
            desktopPreview = o.optBoolean("desktopPreview", false),
            transform = CropTransform(
                zoom = tr.optDouble("zoom", 1.0).toFloat().coerceIn(1f, 5f),
                panX = tr.optDouble("panX", 0.0).toFloat().coerceIn(-1f, 1f),
                panY = tr.optDouble("panY", 0.0).toFloat().coerceIn(-1f, 1f),
            ),
            dock = DockSettings(
                enabled = dockObj.optBoolean("enabled", d.enabled),
                style = runCatching {
                    GlassStyle.valueOf(dockObj.optString("style", d.style.name))
                }.getOrDefault(d.style),
                glassOnBlur = dockObj.optBoolean("glassOnBlur", d.glassOnBlur),
                angle = dockObj.optDouble("angle", d.angle.toDouble()).toFloat().coerceIn(-30f, 30f),
                widthFraction = dockObj.optDouble("width", d.widthFraction.toDouble())
                    .toFloat().coerceIn(0.45f, 0.98f),
                heightDp = dockObj.optDouble("height", d.heightDp.toDouble()).toFloat().coerceIn(56f, 170f),
                bottomMarginDp = dockObj.optDouble("bottom", d.bottomMarginDp.toDouble())
                    .toFloat().coerceIn(0f, 180f),
                cornerRadiusDp = dockObj.optDouble("corner", d.cornerRadiusDp.toDouble())
                    .toFloat().coerceIn(0f, 80f),
                opacity = dockObj.optDouble("opacity", d.opacity.toDouble()).toFloat().coerceIn(0f, 1f),
                frost = dockObj.optDouble("frost", d.frost.toDouble()).toFloat().coerceIn(0f, 1f),
                slots = dockObj.optInt("slots", d.slots).coerceIn(0, 6),
                showGuides = dockObj.optBoolean("guides", d.showGuides),
            ),
            hasWallpaper = hasWallpaper,
        )
    }

    // ---------------- 写入 ----------------

    fun save(state: EditorState) {
        val d = state.dock
        val t = state.transform
        val o = JSONObject().apply {
            put("v", VERSION)
            put(
                "blur",
                JSONObject().apply {
                    put("home", state.homeBlur.toDouble())
                    put("lock", state.lockBlur.toDouble())
                    put("link", state.linkBlur)
                },
            )
            put("grid", state.showGrid)
            put("desktopPreview", state.desktopPreview)
            put(
                "transform",
                JSONObject().apply {
                    put("zoom", t.zoom.toDouble())
                    put("panX", t.panX.toDouble())
                    put("panY", t.panY.toDouble())
                },
            )
            put(
                "dock",
                JSONObject().apply {
                    put("enabled", d.enabled)
                    put("style", d.style.name)
                    put("glassOnBlur", d.glassOnBlur)
                    put("angle", d.angle.toDouble())
                    put("width", d.widthFraction.toDouble())
                    put("height", d.heightDp.toDouble())
                    put("bottom", d.bottomMarginDp.toDouble())
                    put("corner", d.cornerRadiusDp.toDouble())
                    put("opacity", d.opacity.toDouble())
                    put("frost", d.frost.toDouble())
                    put("slots", d.slots)
                    put("guides", d.showGuides)
                },
            )
        }
        prefs.edit().putString(KEY_SETUP, o.toString()).apply()
    }

    /** 把当前壁纸存一份（限尺寸的 JPEG），下次打开直接还原 */
    fun saveWallpaper(src: Bitmap) {
        try {
            val bmp = downscale(src, MAX_CACHE_DIM)
            FileOutputStream(wallpaperFile).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
                out.flush()
            }
            if (bmp !== src) bmp.recycle()
        } catch (t: Throwable) {
            // 缓存失败不影响主流程
        }
    }

    fun loadWallpaper(): Bitmap? {
        if (!hasWallpaper) return null
        return try {
            BitmapFactory.decodeFile(wallpaperFile.absolutePath)
        } catch (t: Throwable) {
            null
        }
    }

    fun clearWallpaper() {
        runCatching { wallpaperFile.delete() }
    }

    private fun downscale(src: Bitmap, maxDim: Int): Bitmap {
        val m = max(src.width, src.height)
        if (m <= maxDim) return src
        val s = maxDim.toFloat() / m
        return Bitmap.createScaledBitmap(
            src,
            max(1, (src.width * s).toInt()),
            max(1, (src.height * s).toInt()),
            true,
        )
    }

    private companion object {
        const val PREFS = "walldock_settings"
        const val KEY_SETUP = "setup"
        const val VERSION = 1
        const val WALLPAPER_NAME = "last_wallpaper.jpg"
        const val MAX_CACHE_DIM = 2400
    }
}
