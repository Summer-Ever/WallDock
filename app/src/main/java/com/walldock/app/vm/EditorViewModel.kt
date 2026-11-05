package com.walldock.app.vm

import android.app.Application
import android.content.pm.ApplicationInfo
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.max

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val screen: IntSize = Screen.size(app)
    private val density: Float = Screen.density(app)

    /** 仅调试包打渲染日志：release 里每帧 Log.d（字符串分配 + logd）是纯开销 */
    private val debugLog =
        (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

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
    private var loadJob: Job? = null

    /** 渲染串行锁：合并循环、手势结束补帧、换图渲染可能同时到来，串行才能保证不互相覆盖 */
    private val renderMutex = Mutex()

    /**
     * 预览渲染缓存：只重算真正变化的层，其余复用。
     * - [cachedBase]：构图（transform）不变就跳过 cover 重采样
     * - [cachedBlur]：构图 + 模糊强度不变就跳过壁纸模糊
     * - [cachedFrost]：构图 + frost 不变就跳过玻璃后方模糊
     * 键里带上源图实例，换图后旧缓存自然失效。缓存只读不改，Dock 始终画在副本上。
     */
    private var cachedBase: Bitmap? = null
    private var cachedBaseKey: Pair<Bitmap, CropTransform>? = null
    private var cachedBlur: Bitmap? = null
    private var cachedBlurKey: Triple<Bitmap, CropTransform, Float>? = null
    private var cachedFrost: Bitmap? = null
    private var cachedFrostKey: Triple<Bitmap, CropTransform, Float>? = null

    init {
        viewModelScope.launch {
            // 壁纸解码与示例图生成全部放后台：大图 JPEG 解码可达数百毫秒，不能卡主线程
            val cached = withContext(Dispatchers.Default) { store.loadWallpaper() }
            if (cached != null) {
                // 上次的壁纸直接接上，配合已恢复的 Dock 参数＝打开即可用
                source = cached
                isDemo = false
                restoredLastSession = true
                state = state.copy(transform = saved.transform)
            } else {
                val w = 1080
                val h = (w * screen.height / screen.width.toFloat()).toInt().coerceAtLeast(2)
                source = withContext(Dispatchers.Default) { BitmapUtils.demoBitmap(w, h) }
            }
            val src = source ?: return@launch
            // 同样先备好降采样源与手势底图，首帧才既快又对
            preparePreviewSource(src)
            refreshBasePreview()
            requestRender(immediate = true)
        }
    }

    /** Dock 顶边在屏幕高度中的比例（0=顶部，1=底部），桌面示意层用它决定图标能排到哪 */
    fun dockTopFraction(): Float {
        val d = state.dock
        if (!d.enabled) return 0.88f
        val topPx = screen.height - (d.bottomMarginDp + d.heightDp) * density
        return (topPx / screen.height).coerceIn(0.4f, 1f)
    }

    /**
     * 手势层用的平移像素量，必须与 [BitmapUtils.coverMatrix] 的算法一致，
     * 否则松手切到成品图时会因位置差异「跳」一下。
     */
    fun livePanPixels(boxW: Float, boxH: Float): FloatArray {
        val src = source ?: return floatArrayOf(0f, 0f)
        if (src.width <= 0 || src.height <= 0 || boxW <= 1f || boxH <= 1f) {
            return floatArrayOf(0f, 0f)
        }
        val t = state.transform
        val cover = max(boxW / src.width, boxH / src.height)
        val drawnW = src.width * cover * t.zoom
        val drawnH = src.height * cover * t.zoom
        val maxPanX = ((drawnW - boxW) / 2f).coerceAtLeast(0f)
        val maxPanY = ((drawnH - boxH) / 2f).coerceAtLeast(0f)
        return floatArrayOf(maxPanX * t.panX, maxPanY * t.panY)
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
        // 连选多张时只认最后一张，避免前一张的异步结果覆盖后一张
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            busy = true
            val bmp = withContext(Dispatchers.IO) { BitmapUtils.decode(getApplication(), uri) }
            if (bmp == null) {
                busy = false
                message = "图片读取失败，换一张试试"
                return@launch
            }
            source = bmp
            isDemo = false
            restoredLastSession = false
            // 只重置裁剪，Dock / 模糊参数原样沿用——换壁纸不用重调
            state = state.copy(transform = CropTransform())
            gestureActive = false

            // 顺序很重要：必须先重建 previewSource 与手势底图，再渲染。
            // 否则渲染会继续用「上一张图」的缓存，表现为选图慢一拍。
            preparePreviewSource(bmp)
            refreshBasePreview()
            // 立刻把新手势底图顶上预览，让用户马上看到换图成功，随后再出完整效果
            preview = basePreview
            if (debugLog) Log.d("WallDock", "image loaded ${bmp.width}x${bmp.height} → 预览已切换为新图")

            busy = false
            requestRender(immediate = true)
            persist(0)
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
        // 滑杆是低频输入：走合并渲染，拖动中也能持续看到带模糊与 Dock 的效果；
        // 不用手势层，否则调缩放时模糊和 Dock 会暂时消失
        requestRender()
        persist()
    }

    fun nudgePan(dx: Float, dy: Float) {
        state = state.copy(
            transform = state.transform.copy(
                panX = (state.transform.panX + dx).coerceIn(-1f, 1f),
                panY = (state.transform.panY + dy).coerceIn(-1f, 1f),
            )
        )
        persist()
        // 与手势走同一条路径：先由 live 层立刻反映新位置，渲染完再撤层
        markGestureActive()
    }

    fun resetCrop() {
        state = state.copy(transform = CropTransform())
        persist()
        markGestureActive()
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
            // 顺序关键：先把新构图完整渲染出来，再撤掉 live 层。
            // 若先撤层，预览会短暂切回「手势前的旧成品图」再跳到新位置——就是那一下抖动。
            renderPreview()
            if (debugLog) Log.d("WallDock", "gesture settle → 切回成品图（渲染已完成）")
            gestureActive = false
            persist()
        }
    }

    /** 重建预览用的降采样源图（换图时必须先调用，否则渲染会沿用上一张图） */
    private suspend fun preparePreviewSource(src: Bitmap) {
        val pw = GESTURE_WIDTH
        previewSource = withContext(Dispatchers.Default) {
            if (src.width > pw) {
                Bitmap.createScaledBitmap(src, pw, max(1, src.height * pw / src.width), true)
            } else src
        }
    }

    /** 生成手势底图（cover 铺满、未模糊、未加 Dock） */
    private suspend fun refreshBasePreview() {
        val src = previewSource ?: source ?: return
        val pw = GESTURE_WIDTH
        val ph = (pw * screen.height / screen.width.toFloat()).toInt().coerceAtLeast(2)
        val bmp = withContext(Dispatchers.Default) {
            BitmapUtils.renderCover(src, pw, ph, CropTransform())
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

    private suspend fun renderPreview() = renderMutex.withLock {
        // 预览一律用降采样源图（720 宽）：滑动调参时渲染量约降 4 倍，观感几乎无差
        val src = previewSource ?: source ?: return@withLock
        val t0 = if (debugLog) System.currentTimeMillis() else 0L
        val st = state
        val pw = 480
        val ph = (pw * screen.height / screen.width.toFloat()).toInt().coerceAtLeast(2)

        // 1) 底图：构图不变就复用，调 Dock / 模糊参数不再重复重采样
        val base = cachedBase?.takeIf { cachedBaseKey == Pair(src, st.transform) }
            ?: withContext(Dispatchers.Default) {
                BitmapUtils.renderCover(src, pw, ph, st.transform)
            }.also {
                cachedBase = it
                cachedBaseKey = Pair(src, st.transform)
            }
        coroutineContext.ensureActive()

        val strength = if (previewMode == PreviewMode.LOCK) {
            if (st.linkBlur) st.homeBlur else st.lockBlur
        } else st.homeBlur

        // 2) 壁纸模糊层：构图 + 强度不变就复用
        var layer = base
        if (strength > 0.01f) {
            val key = Triple(src, st.transform, strength)
            val blurred = cachedBlur?.takeIf { cachedBlurKey == key }
                ?: withContext(Dispatchers.Default) {
                    Blur.blur(base, Blur.radiusFor(strength, pw), extraDownscale = PREVIEW_BLUR_DOWNSCALE)
                }.also {
                    cachedBlur = it
                    cachedBlurKey = key
                }
            if (blurred !== base) layer = blurred
        }
        coroutineContext.ensureActive()

        val dock = st.dock
        if (previewMode == PreviewMode.HOME && dock.enabled) {
            layer = withContext(Dispatchers.Default) {
                // 3) 玻璃后方模糊层同样缓存，拖透明度 / 圆角等参数时完全跳过模糊
                val backdrop = if (dock.glassOnBlur) {
                    val key = Triple(src, st.transform, dock.frost)
                    val b = cachedFrost?.takeIf { cachedFrostKey == key }
                        ?: Blur.blur(base, Blur.radiusFor(dock.frost, pw), extraDownscale = PREVIEW_BLUR_DOWNSCALE)
                            .also {
                                cachedFrost = it
                                cachedFrostKey = key
                            }
                    if (b !== base) b else base
                } else base
                // 缓存层只读：Dock 必须画在副本上
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
        if (debugLog) Log.d("WallDock", "preview render ${System.currentTimeMillis() - t0}ms")
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
        val homeStrength = state.homeBlur
        val lockStrength = if (state.linkBlur) state.homeBlur else state.lockBlur
        // 桌面 + 锁屏模糊强度一致时共享一次全尺寸模糊——它是「应用」耗时的大头
        val sharedBlur: Bitmap? =
            if (target == ApplyTarget.BOTH && homeStrength == lockStrength) {
                blurLayer(base, homeStrength, w)
            } else null
        return try {
            val dock = state.dock
            if (target == ApplyTarget.HOME || target == ApplyTarget.BOTH) {
                // 共享层还要留给锁屏用，画 Dock 前先拷贝；单独设桌面时直接画在模糊层上，省一次全尺寸拷贝
                val layer = if (sharedBlur != null) {
                    sharedBlur.copy(Bitmap.Config.ARGB_8888, true)
                } else {
                    blurLayer(base, homeStrength, w) ?: base.copy(Bitmap.Config.ARGB_8888, true)
                }
                if (dock.enabled) {
                    val backdrop = if (dock.glassOnBlur) blurLayer(base, dock.frost, w) ?: base else base
                    DockRenderer.draw(Canvas(layer), w, h, density, backdrop, dock)
                    if (backdrop !== base) backdrop.recycle()
                }
                WallpaperSetter.setHome(ctx, layer)
                layer.recycle()
                done = "桌面"
            }
            if (target == ApplyTarget.LOCK || target == ApplyTarget.BOTH) {
                // 锁屏是共享层的最后一次使用，直接交付，回收统一走 finally
                val layer = sharedBlur
                    ?: blurLayer(base, lockStrength, w)
                    ?: base.copy(Bitmap.Config.ARGB_8888, true)
                WallpaperSetter.setLock(ctx, layer)
                if (layer !== sharedBlur) layer.recycle()
                done = if (done.isEmpty()) "锁屏" else "桌面 + 锁屏"
            }
            "已设置为$done 壁纸"
        } catch (t: Throwable) {
            "设置失败：${t.message ?: t.javaClass.simpleName}"
        } finally {
            sharedBlur?.let { if (!it.isRecycled) it.recycle() }
            if (!base.isRecycled) base.recycle()
        }
    }

    /** 真正做了模糊就返回新位图；强度过小 / 半径不足时返回 null，由调用方退回原图或其副本 */
    private fun blurLayer(base: Bitmap, strength: Float, w: Int): Bitmap? {
        if (strength <= 0.01f) return null
        val b = Blur.blur(base, Blur.radiusFor(strength, w))
        return if (b !== base) b else null
    }

    private companion object {
        /** 手势底图宽度：越大放大后越清晰，720 在观感与内存之间折中 */
        const val GESTURE_WIDTH = 720

        /** 预览模糊额外降采样倍数（只影响预览，最终输出不受影响） */
        const val PREVIEW_BLUR_DOWNSCALE = 2
    }
}
