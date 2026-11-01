package com.walldock.app.model

/** 裁剪变换：zoom 与归一化平移量（-1..1，1 表示平移到边缘） */
data class CropTransform(
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
)

/** 玻璃样式 */
enum class GlassStyle(val label: String) {
    FROSTED("磨砂"),
    LIQUID("液态玻璃"),
    CLEAR("通透"),
}

enum class PreviewMode(val label: String) {
    HOME("桌面"),
    LOCK("锁屏"),
}

enum class ApplyTarget { BOTH, HOME, LOCK }

/** Dock 栏参数 */
data class DockSettings(
    val enabled: Boolean = true,
    val style: GlassStyle = GlassStyle.LIQUID,
    /** true = Dock 的玻璃底图取「模糊后的壁纸」，false = 取原图 */
    val glassOnBlur: Boolean = true,
    /** 旋转角度，度（-30..30） */
    val angle: Float = 0f,
    /** 宽度占屏幕宽度比例 */
    val widthFraction: Float = 0.88f,
    val heightDp: Float = 96f,
    val bottomMarginDp: Float = 52f,
    val cornerRadiusDp: Float = 44f,
    /** 玻璃底色不透明度 0..1 */
    val opacity: Float = 0.42f,
    /** 玻璃后方额外的模糊强度 0..1 */
    val frost: Float = 0.55f,
    val slots: Int = 4,
    val showGuides: Boolean = true,
)

data class EditorState(
    val transform: CropTransform = CropTransform(),
    /** 桌面壁纸模糊强度 0..1 */
    val homeBlur: Float = 0f,
    /** 锁屏壁纸模糊强度 0..1 */
    val lockBlur: Float = 0f,
    /** 锁屏模糊是否跟随桌面 */
    val linkBlur: Boolean = true,
    val dock: DockSettings = DockSettings(),
    val showGrid: Boolean = false,
    /** 预览里叠加桌面示意图标层，方便对齐 Dock 位置 */
    val desktopPreview: Boolean = false,
)
