package com.walldock.app.vm

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.walldock.app.data.SettingsStore
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

    /** 配置持久化：Dock / 模糊参数长期记住，换壁纸不用重调 */
    private val store = SettingsStore(app)
    private val saved = store.load()

    var state by mutableStateOf(
        EditorState(
            homeBlur = saved.homeBlur,
            lockBlur = saved.lockBlur,
            linkBlur = saved.linkBlur,
            dock = saved.dock,
            showGrid = saved.showGrid,
            desktopPreview = saved.desktopPreview,
        )
    )
        private set

    var source by mutableStateOf<Bitmap?>(null)
        private set

    /** 当前用的是内置示例图（还没选自己的照片） */
    var isDemo by mutableStateOf(true)
        private set

    /** 本次启动是否恢复出了上次的壁纸（用于界面提示） */
    var restoredLastSession by mutableStateOf(false)
        private set

    var preview by mutableStateOf<ImageBitmap?>(null)
        private set

    /**
     * 手势专用底图：cover 铺满、未模糊、未加 Dock。
     * 手指拖动/缩放时直接对这张图做图形变换（不重绘），松手后再出完整效果。
     */
    var basePreview by mutableStateOf<ImageBitmap?>(null)
        private set

    /** 正在拖动/缩放（此时预览走图形变换，跳过模糊与 Dock 重绘） */
    var gestureActive by mutableStateOf(false)
        private set

    /** 预览渲染用的降采样源图：滑杆调参时渲染量降到约 1/4 */
    private var previewSource: Bitmap? = null

    var previewMode by mutableStateOf(PreviewMode.HOME)
        private set

    var busy by mutableStateOf(false)
        private set

    var message by mutableStateOf<String?>(null)

    private var renderJob: Job? = null
    private var renderQueued = false
    private var saveJob: Job? = null
    private var gestureEndJob: Job? = null

    init {
        val cached = store.loadWallpaper()
        if (cached != null) {
            // 上次的壁纸直接接上，配合已恢复的 Dock 参数＝打开即可用
            source = cached
            isDemo = false
            restoredLastSession = true
            state = state.copy(transform = saved.transform)
        } else {
            val w = 1080
            val h = (w * screen.height / screen.width.toFloat()).toInt().coerceAtLeast(2)
            source = BitmapUtils.demoBitmap(w, h)
        }
        requestRender(immediate = true)
        viewModelScope.launch { refreshBasePreview() }
    }

    /** Dock 顶边在屏幕高度中的比例（0=顶部，1=底部），桌面示意层用它决定图标能排到哪 */
    fun dockTopFraction(): Float {
        val d = state.dock
        if (!d.enabled) return 0.88f
        val topPx = screen.height - (d.bottomMarginDp + d.heightDp) * density
        return (topPx / screen.height).coerceIn(0.4f, 1f)
    }

    // ---------------- 持久化 ----------------

    /** 参数变化后延迟落盘，避免拖动滑杆时频繁写盘 */
    private fun persist(debounceMs: Long = 350) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            val snapshot = state
            withContext(Dispatchers.IO) { store.save(snapshot) }
        }
    }

    /** Dock 参数恢复出厂值 */
    fun resetDock() {
        state = state.copy(dock = DockSettings())
        requestRender(immediate = true)
        persist(0)
        message = "Dock 参数已恢复默认"
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
            restoredLastSession = false
            // 只重置裁剪，Dock / 模糊参数原样沿用——换壁纸不用重调
            state = state.copy(transform = CropTransform())
            gestureActive = false
            requestRender(immediate = true)
            persist(0)
            refreshBasePreview()
            withContext(Dispatchers.IO) { store.saveWallpaper(bmp) }
        }
    }

    // ---------------- 参数 ----------------

    fun setHomeBlur(v: Float) {
        state = state.copy(homeBlur = v)
        requestRender()
        persist()
    }

    fun setLockBlur(v: Float) {
        state = state.copy(lockBlur = v)
        requestRender()
        persist()
    }

    fun setLinkBlur(v: Boolean) {
        state = state.copy(linkBlur = v, lockBlur = if (v) state.homeBlur else state.lockBlur)
        requestRender()
        persist()
    }

    fun setShowGrid(v: Boolean) {
        state = state.copy(showGrid = v)
        persist()
    }

    fun setDesktopPreview(v: Boolean) {
        state = state.copy(desktopPreview = v)
        persist()
    }

    fun setZoom(v: Float) {
        state = state.copy(transform = state.transform.copy(zoom = v.coerceIn(1f, 5f)))
        markGestureActive()
    }

    fun nudgePan(dx: Float, dy: Float) {
        state = state.copy(
            transform = state.transform.copy(
                panX = (state.transform.panX + dx).coerceIn(-1f, 1f),
                panY = (state.transform.panY + dy).coerceIn(-1f, 1f),
            )
        )
        requestRender()
        persist()
    }

    fun resetCrop() {
        state = state.copy(transform = CropTransform())
        gestureActive = false
        requestRender(immediate = true)
        persist()
    }

    fun switchPreviewMode(mode: PreviewMode) {
        if (previewMode == mode) return
        previewMode = mode
        requestRender(immediate = true)
    }

    fun updateDock(block: (DockSettings) -> DockSettings) {
        state = state.copy(dock = block(state.dock))
        requestRender()
        persist()
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
        // 手势期间不做重绘：UI 直接对底图做图形变换，跟手且零延迟；
        // 停手 180ms 后再跑一次完整渲染（模糊 + Dock）
        markGestureActive()
    }

    private fun markGestureActive() {
        gestureActive = true
        gestureEndJob?.cancel()
        gestureEndJob = viewModelScope.launch {
            delay(180)
            gestureActive = false
            requestRender(immediate = true)
            persist()
        }
    }

    /** 生成手势底图 + 预览降采样源图 */
    private suspend fun refreshBasePreview() {
        val src = source ?: return
        val pw = GESTURE_WIDTH
        val ph = (pw * screen.height / screen.width.toFloat()).toInt().coerceAtLeast(2)
        withContext(Dispatchers.Default) {
            previewSource = if (src.width > pw) {
                Bitmap.createScaledBitmap(src, pw, max(1, src.height * pw / src.width), true)
            } else src
        }
        val bmp = withContext(Dispatchers.Default) {
            BitmapUtils.renderCover(previewSource ?: src, pw, ph, CropTransform())
        }
        basePreview = bmp.asImageBitmap()
    }

    // ---------------- 预览渲染 ----------------

    fun requestRender(immediate: Boolean = false) {
        if (immediate) {
            renderJob?.cancel()
            renderQueued = false
            renderJob = viewModelScope.launch { renderPreview() }
            return
        }
        // 合并式渲染：拖动滑杆时请求不断到来，在跑的一轮结束后带上最新参数继续渲染，
        // 而不是把渲染请求一直取消到松手才刷新
        renderQueued = true
        if (renderJob?.isActive == true) return
        renderJob = viewModelScope.launch {
            while (renderQueued) {
                renderQueued = false
                renderPreview()
                delay(16) // 让出一帧，避免连续渲染抢占 UI
            }
        }
    }

    private suspend fun renderPreview() {
        // 预览一律用降采样源图（720 宽）：滑动调参时渲染量约降 4 倍，观感几乎无差
        val src = previewSource ?: source ?: return
        val t0 = System.currentTimeMillis()
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
            val b = if (strength > 0.01f) {
                Blur.blur(base, Blur.radiusFor(strength, pw), extraDownscale = PREVIEW_BLUR_DOWNSCALE)
            } else null
            if (b != null && b !== base) b else base
        }

        val dock = st.dock
        if (previewMode == PreviewMode.HOME && dock.enabled) {
            layer = withContext(Dispatchers.Default) {
                val backdrop = if (dock.glassOnBlur) {
                    Blur.blur(base, Blur.radiusFor(dock.frost, pw), extraDownscale = PREVIEW_BLUR_DOWNSCALE)
                } else base
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
        Log.d("WallDock", "preview render ${System.currentTimeMillis() - t0}ms")
    }

    // ---------------- 应用到壁纸 ----------------

    fun apply(target: ApplyTarget) {
        val src = source ?: return
        if (busy) return
        busy = true
        persist(0)
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

    private companion object {
        /** 手势底图宽度：越大放大后越清晰，720 在观感与内存之间折中 */
        const val GESTURE_WIDTH = 720

        /** 预览模糊额外降采样倍数（只影响预览，最终输出不受影响） */
        const val PREVIEW_BLUR_DOWNSCALE = 2
    }
}
