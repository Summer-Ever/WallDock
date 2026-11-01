package com.walldock.app.vm

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.walldock.app.img.BitmapUtils
import com.walldock.app.img.Blur
import com.walldock.app.img.DockRenderer
import com.walldock.app.model.ApplyTarget
import com.walldock.app.model.CropTransform
import com.walldock.app.model.DockSettings
import com.walldock.app.model.EditorState
import com.walldock.app.model.PreviewMode
import com.walldock.app.util.Screen
import com.walldock.app.util.WallpaperSetter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val screen: IntSize = Screen.size(app)
    private val density: Float = Screen.density(app)

    val screenWidth: Int get() = screen.width
    val screenHeight: Int get() = screen.height
    val aspect: Float get() = screen.width.toFloat() / screen.height.toFloat()

    var state by mutableStateOf(EditorState())
        private set

    var source by mutableStateOf<Bitmap?>(null)
        private set

    /** 当前用的是内置示例图（还没选自己的照片） */
    var isDemo by mutableStateOf(true)
        private set

    var preview by mutableStateOf<ImageBitmap?>(null)
        private set

    var previewMode by mutableStateOf(PreviewMode.HOME)
        private set

    var busy by mutableStateOf(false)
        private set

    var message by mutableStateOf<String?>(null)

    private var renderJob: Job? = null

    init {
        val w = 1080
        val h = (w * screen.height / screen.width.toFloat()).toInt().coerceAtLeast(2)
        source = BitmapUtils.demoBitmap(w, h)
        requestRender(immediate = true)
    }

    // ---------------- 图片 ----------------

    fun loadImage(uri: Uri) {
        viewModelScope.launch {
            busy = true
            val bmp = withContext(Dispatchers.IO) { BitmapUtils.decode(getApplication(), uri) }
            busy = false
            if (bmp == null) {
                message = "图片读取失败，换一张试试"
                return@launch
            }
            source = bmp
            isDemo = false
            state = state.copy(transform = CropTransform())
            requestRender(immediate = true)
        }
    }

    // ---------------- 参数 ----------------

    fun setHomeBlur(v: Float) {
        state = state.copy(homeBlur = v)
        requestRender()
    }

    fun setLockBlur(v: Float) {
        state = state.copy(lockBlur = v)
        requestRender()
    }

    fun setLinkBlur(v: Boolean) {
        state = state.copy(linkBlur = v, lockBlur = if (v) state.homeBlur else state.lockBlur)
        requestRender()
    }

    fun setShowGrid(v: Boolean) {
        state = state.copy(showGrid = v)
    }

    fun setZoom(v: Float) {
        state = state.copy(transform = state.transform.copy(zoom = v.coerceIn(1f, 5f)))
        requestRender()
    }

    fun nudgePan(dx: Float, dy: Float) {
        state = state.copy(
            transform = state.transform.copy(
                panX = (state.transform.panX + dx).coerceIn(-1f, 1f),
                panY = (state.transform.panY + dy).coerceIn(-1f, 1f),
            )
        )
        requestRender()
    }

    fun resetCrop() {
        state = state.copy(transform = CropTransform())
        requestRender(immediate = true)
    }

    fun switchPreviewMode(mode: PreviewMode) {
        if (previewMode == mode) return
        previewMode = mode
        requestRender(immediate = true)
    }

    fun updateDock(block: (DockSettings) -> DockSettings) {
        state = state.copy(dock = block(state.dock))
        requestRender()
    }

    /** 预览框内的双指缩放 / 拖动 */
    fun onGesture(panX: Float, panY: Float, zoomFactor: Float, boxW: Float, boxH: Float) {
        val src = source ?: return
        if (boxW <= 1f || boxH <= 1f) return
        val t = state.transform
        val newZoom = (t.zoom * zoomFactor).coerceIn(1f, 5f)
        val cover = max(boxW / src.width, boxH / src.height)
        val drawnW = src.width * cover * newZoom
        val drawnH = src.height * cover * newZoom
        val maxPanX = ((drawnW - boxW) / 2f).coerceAtLeast(1f)
        val maxPanY = ((drawnH - boxH) / 2f).coerceAtLeast(1f)
        state = state.copy(
            transform = CropTransform(
                zoom = newZoom,
                panX = (t.panX + panX / maxPanX).coerceIn(-1f, 1f),
                panY = (t.panY + panY / maxPanY).coerceIn(-1f, 1f),
            )
        )
        requestRender()
    }

    // ---------------- 预览渲染 ----------------

    fun requestRender(immediate: Boolean = false) {
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            if (!immediate) delay(40)
            renderPreview()
        }
    }

    private suspend fun renderPreview() {
        val src = source ?: return
        val st = state
        val pw = 480
        val ph = (pw * screen.height / screen.width.toFloat()).toInt().coerceAtLeast(2)

        val base = withContext(Dispatchers.Default) {
            BitmapUtils.renderCover(src, pw, ph, st.transform)
        }

        val strength = if (previewMode == PreviewMode.LOCK) {
            if (st.linkBlur) st.homeBlur else st.lockBlur
        } else st.homeBlur

        var layer = withContext(Dispatchers.Default) {
            val b = if (strength > 0.01f) Blur.blur(base, Blur.radiusFor(strength, pw)) else null
            if (b != null && b !== base) b else base
        }

        val dock = st.dock
        if (previewMode == PreviewMode.HOME && dock.enabled) {
            layer = withContext(Dispatchers.Default) {
                val backdrop = if (dock.glassOnBlur) Blur.blur(base, Blur.radiusFor(dock.frost, pw)) else base
                val out = layer.copy(Bitmap.Config.ARGB_8888, true)
                DockRenderer.draw(
                    Canvas(out),
                    pw,
                    ph,
                    density * pw / screenWidth.toFloat(),
                    backdrop,
                    dock,
                )
                out
            }
        }

        preview = layer.asImageBitmap()
    }

    // ---------------- 应用到壁纸 ----------------

    fun apply(target: ApplyTarget) {
        val src = source ?: return
        if (busy) return
        busy = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { runApply(src, target) }
            busy = false
            message = result
        }
    }

    private fun runApply(src: Bitmap, target: ApplyTarget): String {
        val w = screenWidth
        val h = screenHeight
        val ctx = getApplication<Application>()
        val base = BitmapUtils.renderCover(src, w, h, state.transform)
        var done = ""
        return try {
            if (target == ApplyTarget.HOME || target == ApplyTarget.BOTH) {
                val img = buildImage(base, w, h, state.homeBlur, withDock = true)
                WallpaperSetter.setHome(ctx, img)
                img.recycle()
                done = "桌面"
            }
            if (target == ApplyTarget.LOCK || target == ApplyTarget.BOTH) {
                val strength = if (state.linkBlur) state.homeBlur else state.lockBlur
                val img = buildImage(base, w, h, strength, withDock = false)
                WallpaperSetter.setLock(ctx, img)
                img.recycle()
                done = if (done.isEmpty()) "锁屏" else "桌面 + 锁屏"
            }
            "已设置为$done 壁纸"
        } catch (t: Throwable) {
            "设置失败：${t.message ?: t.javaClass.simpleName}"
        } finally {
            if (!base.isRecycled) base.recycle()
        }
    }

    private fun buildImage(base: Bitmap, w: Int, h: Int, blurStrength: Float, withDock: Boolean): Bitmap {
        val blurred = if (blurStrength > 0.01f) Blur.blur(base, Blur.radiusFor(blurStrength, w)) else null
        val layer = if (blurred != null && blurred !== base) blurred else base.copy(Bitmap.Config.ARGB_8888, true)

        val dock = state.dock
        if (!withDock || !dock.enabled) return layer

        val out = layer.copy(Bitmap.Config.ARGB_8888, true)
        val backdrop = if (dock.glassOnBlur) Blur.blur(base, Blur.radiusFor(dock.frost, w)) else base
        DockRenderer.draw(Canvas(out), w, h, density, backdrop, dock)
        if (backdrop !== base && backdrop !== layer) backdrop.recycle()
        if (layer !== base) layer.recycle()
        return out
    }
}
