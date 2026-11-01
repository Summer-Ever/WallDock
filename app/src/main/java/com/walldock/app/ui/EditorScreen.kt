package com.walldock.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.walldock.app.model.ApplyTarget
import com.walldock.app.model.DockSettings
import com.walldock.app.model.GlassStyle
import com.walldock.app.model.PreviewMode
import com.walldock.app.ui.components.HSpacer
import com.walldock.app.ui.components.PillButton
import com.walldock.app.ui.components.SectionLabel
import com.walldock.app.ui.components.Segmented
import com.walldock.app.ui.components.SliderRow
import com.walldock.app.ui.components.SwitchRow
import com.walldock.app.ui.components.TabChips
import com.walldock.app.ui.components.ThinDivider
import com.walldock.app.vm.EditorViewModel
import kotlinx.coroutines.delay

@Composable
fun EditorScreen(vm: EditorViewModel) {
    val state = vm.state
    var tab by remember { mutableIntStateOf(0) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.loadImage(uri)
    }
    val pick = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    LaunchedEffect(vm.message) {
        if (vm.message != null) {
            delay(2800)
            vm.message = null
        }
    }

    Box(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Header(vm)
                PreviewArea(vm, vm.preview, Modifier.weight(1f))
                ControlPanel(vm, tab, onTab = { tab = it }, onPick = pick)
            }
        }

        // 轻提示
        vm.message?.let { msg ->
            Box(Modifier.fillMaxSize().padding(bottom = 300.dp), contentAlignment = Alignment.BottomCenter) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xE612141A),
                    modifier = Modifier.padding(horizontal = 32.dp),
                ) {
                    Text(
                        msg,
                        color = Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(vm: EditorViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("WallDock", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text(
                "壁纸裁剪 · 模糊 · 液态玻璃 Dock",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (vm.busy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
        }
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(
                "${vm.screenWidth}×${vm.screenHeight}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun PreviewArea(vm: EditorViewModel, preview: ImageBitmap?, modifier: Modifier) {
    val state = vm.state
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Segmented(
            options = listOf(PreviewMode.HOME.label, PreviewMode.LOCK.label),
            selectedIndex = if (vm.previewMode == PreviewMode.HOME) 0 else 1,
            onSelect = { vm.switchPreviewMode(if (it == 0) PreviewMode.HOME else PreviewMode.LOCK) },
            modifier = Modifier.width(180.dp),
        )
        Spacer(Modifier.height(8.dp))

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (preview == null) {
                EmptyPreview()
            } else {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .aspectRatio(vm.aspect, matchHeightConstraintsFirst = true)
                        .shadow(18.dp, RoundedCornerShape(22.dp))
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color.Black)
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                vm.onGesture(pan.x, pan.y, zoom, size.width.toFloat(), size.height.toFloat())
                            }
                        },
                ) {
                    Image(
                        bitmap = preview,
                        contentDescription = "壁纸预览",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    // 状态栏 / 灵动区域示意
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 8.dp)
                            .size(width = 52.dp, height = 14.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.75f)),
                    )
                    if (state.showGrid) {
                        Canvas(Modifier.fillMaxSize()) {
                            val w = 1.dp.toPx()
                            for (i in 1..2) {
                                val x = size.width * i / 3f
                                val y = size.height * i / 3f
                                drawLine(Color.White.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, size.height), w)
                                drawLine(Color.White.copy(alpha = 0.5f), Offset(0f, y), Offset(size.width, y), w)
                            }
                        }
                    }
                    if (vm.previewMode == PreviewMode.LOCK) {
                        Box(
                            Modifier
                                .align(Alignment.TopStart)
                                .padding(10.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.45f))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text("锁屏", color = Color.White, fontSize = 10.sp)
                        }
                    }
                    if (vm.isDemo) {
                        Box(
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(10.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.45f))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text("示例背景", color = Color.White, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "预览区可双指缩放、拖动构图",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyPreview() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("还没有图片", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text("点下方「选择图片」开始", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun ControlPanel(
    vm: EditorViewModel,
    tab: Int,
    onTab: (Int) -> Unit,
    onPick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        shadowElevation = 16.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            TabChips(listOf("壁纸", "Dock 栏", "应用"), tab, onTab)
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 256.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (tab) {
                    0 -> WallpaperTab(vm, onPick)
                    1 -> DockTab(vm)
                    else -> ApplyTab(vm)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun WallpaperTab(vm: EditorViewModel, onPick: () -> Unit) {
    val s = vm.state
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PillButton("选择图片", onClick = onPick, modifier = Modifier.weight(1f), filled = true)
        HSpacer(0)
        PillButton("重置裁剪", onClick = { vm.resetCrop() }, modifier = Modifier.weight(0.8f))
    }

    SectionLabel("模糊效果（桌面 / 锁屏可分别设置）")
    SliderRow("桌面壁纸模糊", s.homeBlur, 0f..1f, onChange = { vm.setHomeBlur(it) })
    SliderRow(
        "锁屏壁纸模糊",
        if (s.linkBlur) s.homeBlur else s.lockBlur,
        0f..1f,
        onChange = { vm.setLockBlur(it) },
        enabled = !s.linkBlur,
    )
    SwitchRow("锁屏跟随桌面", s.linkBlur, onChange = { vm.setLinkBlur(it) }, hint = "关闭后可单独调节锁屏模糊强度")

    SectionLabel("裁剪")
    SliderRow(
        "缩放",
        s.transform.zoom,
        1f..3f,
        onChange = { vm.setZoom(it) },
        valueText = "${(s.transform.zoom * 100).toInt() / 100f}x",
    )
    SwitchRow("显示三分构图线", s.showGrid, onChange = { vm.setShowGrid(it) })
}

@Composable
private fun DockTab(vm: EditorViewModel) {
    val d = vm.state.dock
    SwitchRow("启用 Dock 栏", d.enabled, onChange = { on -> vm.updateDock { it.copy(enabled = on) } })

    SectionLabel("玻璃样式")
    Segmented(
        options = GlassStyle.entries.map { it.label },
        selectedIndex = GlassStyle.entries.indexOf(d.style),
        onSelect = { idx -> vm.updateDock { it.copy(style = GlassStyle.entries[idx]) } },
    )
    Spacer(Modifier.height(8.dp))
    SectionLabel("Dock 底图来源")
    Segmented(
        options = listOf("模糊壁纸", "原图"),
        selectedIndex = if (d.glassOnBlur) 0 else 1,
        onSelect = { idx -> vm.updateDock { it.copy(glassOnBlur = idx == 0) } },
    )

    SectionLabel("位置与尺寸")
    SliderRow(
        "角度", d.angle, -30f..30f,
        onChange = { vm.updateDock { s -> s.copy(angle = it) } },
        valueText = "${d.angle.toInt()}°",
    )
    SliderRow(
        "宽度", d.widthFraction, 0.45f..0.98f,
        onChange = { vm.updateDock { s -> s.copy(widthFraction = it) } },
        valueText = "${(d.widthFraction * 100).toInt()}%",
    )
    SliderRow(
        "高度", d.heightDp, 56f..170f,
        onChange = { vm.updateDock { s -> s.copy(heightDp = it) } },
        valueText = "${d.heightDp.toInt()} dp",
    )
    SliderRow(
        "距底部", d.bottomMarginDp, 0f..180f,
        onChange = { vm.updateDock { s -> s.copy(bottomMarginDp = it) } },
        valueText = "${d.bottomMarginDp.toInt()} dp",
    )
    SliderRow(
        "圆角", d.cornerRadiusDp, 0f..80f,
        onChange = { vm.updateDock { s -> s.copy(cornerRadiusDp = it) } },
        valueText = "${d.cornerRadiusDp.toInt()} dp",
    )

    SectionLabel("材质")
    SliderRow(
        "透明度", d.opacity, 0f..1f,
        onChange = { vm.updateDock { s -> s.copy(opacity = it) } },
    )
    SliderRow(
        "玻璃后方模糊", d.frost, 0f..1f,
        onChange = { vm.updateDock { s -> s.copy(frost = it) } },
    )

    SectionLabel("图标位")
    SliderRow(
        "图标位数量", d.slots.toFloat(), 0f..6f,
        onChange = { vm.updateDock { s -> s.copy(slots = it.toInt()) } },
        valueText = "${d.slots} 个",
    )
    SwitchRow("显示图标对位参考", d.showGuides, onChange = { on -> vm.updateDock { it.copy(showGuides = on) } })
}

@Composable
private fun ApplyTab(vm: EditorViewModel) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "Dock 会被绘制进桌面壁纸本身，所以任何启动器都能看到它。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        PillButton(
            "应用到 桌面 + 锁屏",
            onClick = { vm.apply(ApplyTarget.BOTH) },
            modifier = Modifier.fillMaxWidth(),
            filled = true,
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("只设桌面", onClick = { vm.apply(ApplyTarget.HOME) }, modifier = Modifier.weight(1f))
            PillButton("只设锁屏", onClick = { vm.apply(ApplyTarget.LOCK) }, modifier = Modifier.weight(1f))
        }

        Spacer(Modifier.height(12.dp))
        ThinDivider()
        Spacer(Modifier.height(10.dp))
        Text(
            "澎湃 OS 提示",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "• 若锁屏没变化：设置 → 锁屏 → 关闭「锁屏画报」，或关闭「锁屏动态壁纸」。\n" +
                "• 若桌面壁纸被裁切：把系统壁纸的「滚动」选项关掉。\n" +
                "• 想让 Dock 图标对齐：打开「显示图标对位参考」，按参考框摆放启动器图标。",
            fontSize = 11.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
