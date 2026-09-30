package com.ultrax26.recorder.ui.camera

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ultrax26.recorder.AppGraph
import com.ultrax26.recorder.effects.*
import com.ultrax26.recorder.ui.theme.UxColors
import java.util.UUID
import kotlin.math.roundToInt

/** Bottom panel on the camera screen: looks, backgrounds, stickers, beauty, face & age, style, my assets. */
@Composable
fun EffectsPanel(graph: AppGraph, onClose: () -> Unit) {
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val fx = settings.effects
    val renderer by graph.controller.rendererState.collectAsStateWithLifecycle()
    val stats = renderer?.stats?.collectAsStateWithLifecycle()?.value
    fun upd(f: (EffectsSettings) -> EffectsSettings) = graph.settings.update { it.copy(effects = f(it.effects)) }
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("Looks", "Backgrounds", "Stickers", "Beauty", "Face & age", "Style", "My assets")

    Column(Modifier.fillMaxWidth().background(UxColors.Panel, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).padding(bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Effects", color = UxColors.Orange, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(10.dp))
            if (stats != null) Text("${stats.fps.roundToInt()} fps · ${"%.1f".format(stats.frameMs)} ms${if (stats.faceTracked) " · face" else ""}${if (stats.segTracked) " · person" else ""}${stats.error?.let { " · $it" } ?: ""}", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
            else if (fx.isActive()) Text("starting pipeline…", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { upd { it.cleared() } }) { Text("Clear all") }
            TextButton(onClick = onClose) { Text("Close") }
        }
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp, containerColor = Color.Transparent) {
            tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, maxLines = 1) }) }
        }
        Box(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp)) {
                when (tab) {
                    0 -> LooksTab(fx, ::upd)
                    1 -> BackgroundsTab(graph, fx, ::upd)
                    2 -> StickersTab(fx, ::upd)
                    3 -> BeautyTab(fx, ::upd)
                    4 -> FaceAgeTab(fx, ::upd)
                    5 -> StyleTab(fx, ::upd)
                    else -> MyAssetsTab(graph, fx, ::upd)
                }
            }
        }
    }
}

@Composable
private fun FxChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label, maxLines = 1) }, modifier = Modifier.padding(end = 6.dp, bottom = 4.dp),
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = UxColors.Orange, selectedLabelColor = Color.Black))
}

@Composable
private fun FxSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float> = 0f..1f, format: (Float) -> String = { "${(it * 100).roundToInt()}%" }, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(110.dp))
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
        Text(format(value), color = UxColors.Sky, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
    }
}

@Composable
private fun SectionLabel(text: String) { Text(text, color = UxColors.Slate, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)) }

// -------------------------------------------------------------------------------------------------

@Composable
private fun LooksTab(fx: EffectsSettings, upd: ((EffectsSettings) -> EffectsSettings) -> Unit) {
    val groups = EffectCatalog.looks.groupBy { it.category }
    for ((cat, looks) in groups) {
        SectionLabel(cat)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            looks.forEach { look ->
                val sel = fx.activeLook == look.id
                Column(Modifier.padding(end = 8.dp).width(72.dp).clip(RoundedCornerShape(10.dp)).background(if (sel) UxColors.Orange.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.08f))
                    .clickable { upd { s -> if (sel) s.cleared() else look.apply(s).copy(activeLook = look.id) } }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(look.emoji, fontSize = 26.sp)
                    Text(look.name, color = if (sel) Color.Black else Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    Text("Looks replace the current stack. Fine-tune anything on the other tabs afterwards.", color = UxColors.Slate, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun BackgroundsTab(graph: AppGraph, fx: EffectsSettings, upd: ((EffectsSettings) -> EffectsSettings) -> Unit) {
    val bg = fx.background
    fun setBg(f: (BackgroundSpec) -> BackgroundSpec) = upd { s -> s.copy(background = f(s.background), activeLook = null) }
    val ctx = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) { try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) { }
            val asset = UserAsset(UUID.randomUUID().toString(), uri.lastPathSegment ?: "photo", uri.toString(), false)
            upd { s -> s.copy(userBackgrounds = s.userBackgrounds + asset, background = BackgroundSpec(BackgroundType.IMAGE, uri = uri.toString()), activeLook = null) } }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) { try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) { }
            val asset = UserAsset(UUID.randomUUID().toString(), uri.lastPathSegment ?: "video", uri.toString(), true)
            upd { s -> s.copy(userBackgrounds = s.userBackgrounds + asset, background = BackgroundSpec(BackgroundType.VIDEO, uri = uri.toString()), activeLook = null) } }
    }
    SectionLabel("Basic")
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        FxChip("Real", bg.type == BackgroundType.NONE) { setBg { BackgroundSpec() } }
        FxChip("Blur", bg.type == BackgroundType.BLUR) { setBg { it.copy(type = BackgroundType.BLUR) } }
        FxChip("Color", bg.type == BackgroundType.COLOR) { setBg { it.copy(type = BackgroundType.COLOR) } }
        OutlinedButton(onClick = { imagePicker.launch(arrayOf("image/*")) }, modifier = Modifier.padding(end = 6.dp)) { Text("Photo…") }
        OutlinedButton(onClick = { videoPicker.launch(arrayOf("video/*")) }) { Text("Video…") }
    }
    if (bg.type == BackgroundType.BLUR) FxSlider("Blur amount", bg.blur) { v -> setBg { it.copy(blur = v) } }
    if (bg.type == BackgroundType.COLOR) Row(Modifier.horizontalScroll(rememberScrollState())) {
        listOf(0xFF0EA5E9L, 0xFF22C55EL, 0xFFEF4444L, 0xFFF59E0BL, 0xFFA855F7L, 0xFFEC4899L, 0xFFFFFFFFL, 0xFF111827L, 0xFF00FF00L, 0xFF0000FFL).forEach { c ->
            Box(Modifier.padding(end = 8.dp).size(34.dp).clip(RoundedCornerShape(8.dp)).background(Color(c)).border(2.dp, if (bg.color == c) UxColors.Orange else Color.Transparent, RoundedCornerShape(8.dp)).clickable { setBg { it.copy(color = c) } })
        }
    }
    SectionLabel("3D parallax scenes (move your head)")
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        EffectCatalog.parallaxScenes.forEach { sc -> FxChip("${sc.emoji} ${sc.name}", bg.type == BackgroundType.PARALLAX && bg.id == sc.id) { setBg { it.copy(type = BackgroundType.PARALLAX, id = sc.id) } } }
    }
    SectionLabel("Animated scenes")
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        EffectCatalog.proceduralBackgrounds.forEach { pb -> FxChip("${pb.emoji} ${pb.name}", bg.type == BackgroundType.PROCEDURAL && bg.id == pb.id) { setBg { it.copy(type = BackgroundType.PROCEDURAL, id = pb.id) } } }
    }
    if (fx.userBackgrounds.isNotEmpty()) {
        SectionLabel("My photos & videos")
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            fx.userBackgrounds.forEach { a -> FxChip("${if (a.isVideo) "🎞️" else "🖼️"} ${a.name.take(14)}", bg.uri == a.uri) { setBg { it.copy(type = if (a.isVideo) BackgroundType.VIDEO else BackgroundType.IMAGE, uri = a.uri) } } }
        }
    }
    if (bg.type != BackgroundType.NONE) {
        SectionLabel("Edge")
        FxSlider("Feather", bg.feather) { v -> setBg { it.copy(feather = v) } }
        FxSlider("Edge shift", bg.edgeShift, -1f..1f, { "%+.2f".format(it) }) { v -> setBg { it.copy(edgeShift = v) } }
        FxSlider("Light wrap", bg.lightWrap) { v -> setBg { it.copy(lightWrap = v) } }
        if (bg.type == BackgroundType.PARALLAX || bg.type == BackgroundType.PROCEDURAL || bg.type == BackgroundType.IMAGE) FxSlider("Parallax", bg.parallaxStrength, 0f..2f, { "%.1f×".format(it) }) { v -> setBg { it.copy(parallaxStrength = v) } }
        if (bg.type == BackgroundType.PROCEDURAL) Row(verticalAlignment = Alignment.CenterVertically) { Text("Animate", color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(110.dp)); Switch(checked = bg.animate, onCheckedChange = { on -> setBg { it.copy(animate = on) } }) }
    }
}

@Composable
private fun StickersTab(fx: EffectsSettings, upd: ((EffectsSettings) -> EffectsSettings) -> Unit) {
    var cat by remember { mutableStateOf(StickerCategory.HEADWEAR) }
    val ctx = LocalContext.current
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        StickerCategory.entries.filter { it != StickerCategory.USER }.forEach { c -> FxChip(c.label, cat == c) { cat = c } }
    }
    LazyRow(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        items(EffectCatalog.stickers.filter { it.category == cat }) { asset ->
            val active = fx.stickers.any { it.assetId == asset.id }
            val resId = remember(asset.drawable) { ctx.resources.getIdentifier(asset.drawable, "drawable", ctx.packageName) }
            Column(Modifier.padding(end = 8.dp).width(76.dp).clip(RoundedCornerShape(10.dp)).background(if (active) UxColors.Green.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.08f))
                .clickable { upd { s -> if (active) s.copy(stickers = s.stickers.filter { it.assetId != asset.id }, activeLook = null) else s.copy(stickers = s.stickers + asset.layer(), activeLook = null) } }
                .padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (resId != 0) Image(painter = painterResource(resId), contentDescription = asset.name, modifier = Modifier.size(44.dp)) else Box(Modifier.size(44.dp))
                Text(asset.name, color = if (active) Color.Black else Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (fx.stickers.isNotEmpty()) {
        SectionLabel("Placed stickers (tap to adjust)")
        var editing by remember { mutableStateOf<String?>(null) }
        fx.stickers.forEach { layer ->
            val asset = EffectCatalog.sticker(layer.assetId)
            val name = asset?.name ?: layer.userUri?.let { "My sticker" } ?: layer.assetId
            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.06f)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { editing = if (editing == layer.id) null else layer.id }) {
                    Text("$name · ${layer.anchor.label}", color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { upd { s -> s.copy(stickers = s.stickers.filter { it.id != layer.id }) } }) { Text("Remove") }
                }
                if (editing == layer.id) {
                    fun updLayer(f: (StickerLayer) -> StickerLayer) = upd { s -> s.copy(stickers = s.stickers.map { if (it.id == layer.id) f(it) else it }) }
                    FxSlider("Size", layer.scale, 0.1f..4f, { "%.2f".format(it) }) { v -> updLayer { it.copy(scale = v) } }
                    FxSlider("Left / right", layer.offsetX, -2f..2f, { "%+.2f".format(it) }) { v -> updLayer { it.copy(offsetX = v) } }
                    FxSlider("Up / down", layer.offsetY, -2f..2f, { "%+.2f".format(it) }) { v -> updLayer { it.copy(offsetY = v) } }
                    FxSlider("Depth", layer.offsetZ, -1f..1f, { "%+.2f".format(it) }) { v -> updLayer { it.copy(offsetZ = v) } }
                    FxSlider("Opacity", layer.opacity) { v -> updLayer { it.copy(opacity = v) } }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        RotationMode.entries.forEach { m -> FxChip(m.label, layer.rotation == m) { updLayer { it.copy(rotation = m) } } }
                        FxChip("Behind me", layer.behindPerson) { updLayer { it.copy(behindPerson = !it.behindPerson) } }
                        FxChip("Flip", layer.mirrorX) { updLayer { it.copy(mirrorX = !it.mirrorX) } }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        Text("Anchor:", color = UxColors.Slate, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 6.dp, top = 8.dp))
                        Anchor.entries.forEach { a -> FxChip(a.label, layer.anchor == a) { updLayer { it.copy(anchor = a) } } }
                    }
                }
            }
        }
    }
}

@Composable
private fun BeautyTab(fx: EffectsSettings, upd: ((EffectsSettings) -> EffectsSettings) -> Unit) {
    val b = fx.beauty
    fun ub(f: (BeautySettings) -> BeautySettings) = upd { s -> s.copy(beauty = f(s.beauty), activeLook = null) }
    FxSlider("Skin smoothing", b.smoothing) { v -> ub { it.copy(smoothing = v) } }
    FxSlider("Brighten", b.brightening) { v -> ub { it.copy(brightening = v) } }
    FxSlider("Eye enlarge", b.eyeEnlarge) { v -> ub { it.copy(eyeEnlarge = v) } }
    FxSlider("Face slim", b.faceSlim) { v -> ub { it.copy(faceSlim = v) } }
    FxSlider("Nose slim", b.noseSlim) { v -> ub { it.copy(noseSlim = v) } }
    FxSlider("Chin shorten", b.chinShorten) { v -> ub { it.copy(chinShorten = v) } }
    FxSlider("Teeth whitening", b.teethWhitening) { v -> ub { it.copy(teethWhitening = v) } }
    FxSlider("Eye brighten", b.eyeBrighten) { v -> ub { it.copy(eyeBrighten = v) } }
    FxSlider("Lipstick", b.lipstick) { v -> ub { it.copy(lipstick = v) } }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        listOf(0xFFC0264BL, 0xFFE11D48L, 0xFF9F1239L, 0xFFF97316L, 0xFFDB2777L, 0xFF7C2D12L, 0xFF1F2937L).forEach { c ->
            Box(Modifier.padding(end = 8.dp).size(28.dp).clip(RoundedCornerShape(14.dp)).background(Color(c)).border(2.dp, if (b.lipColor == c) UxColors.Orange else Color.Transparent, RoundedCornerShape(14.dp)).clickable { ub { it.copy(lipColor = c) } })
        }
    }
    FxSlider("Blush", b.blush) { v -> ub { it.copy(blush = v) } }
    FxSlider("Sharpen", b.sharpen) { v -> ub { it.copy(sharpen = v) } }
    FxSlider("Glow", b.glow) { v -> ub { it.copy(glow = v) } }
    Row { TextButton(onClick = { ub { BeautySettings() } }) { Text("Reset beauty") } }
}

@Composable
private fun FaceAgeTab(fx: EffectsSettings, upd: ((EffectsSettings) -> EffectsSettings) -> Unit) {
    SectionLabel("Face")
    Row(Modifier.horizontalScroll(rememberScrollState())) { FaceMode.entries.forEach { m -> FxChip(m.label, fx.faceMode == m) { upd { s -> s.copy(faceMode = m, activeLook = null) } } } }
    if (fx.faceMode != FaceMode.NONE) FxSlider("Intensity", fx.faceModeIntensity) { v -> upd { s -> s.copy(faceModeIntensity = v) } }
    SectionLabel("Age (stylized: texture, tone and shape, not a learned face model)")
    Row(Modifier.horizontalScroll(rememberScrollState())) { AgeMode.entries.forEach { m -> FxChip(m.label, fx.age == m) { upd { s -> s.copy(age = m, activeLook = null) } } } }
    if (fx.age != AgeMode.NONE) FxSlider("Intensity", fx.ageIntensity) { v -> upd { s -> s.copy(ageIntensity = v) } }
}

@Composable
private fun StyleTab(fx: EffectsSettings, upd: ((EffectsSettings) -> EffectsSettings) -> Unit) {
    SectionLabel("Fun")
    Row(Modifier.horizontalScroll(rememberScrollState())) { FunMode.entries.forEach { m -> FxChip(m.label, fx.funMode == m) { upd { s -> s.copy(funMode = m, activeLook = null) } } } }
    if (fx.funMode != FunMode.NONE) FxSlider("Intensity", fx.funIntensity) { v -> upd { s -> s.copy(funIntensity = v) } }
    SectionLabel("Color look")
    Row(Modifier.horizontalScroll(rememberScrollState())) { ColorLook.entries.forEach { m -> FxChip(m.label, fx.look == m) { upd { s -> s.copy(look = m, activeLook = null) } } } }
    if (fx.look != ColorLook.NONE) FxSlider("Intensity", fx.lookIntensity) { v -> upd { s -> s.copy(lookIntensity = v) } }
    FxSlider("Vignette", fx.vignette) { v -> upd { s -> s.copy(vignette = v) } }
    FxSlider("Film grain", fx.grain) { v -> upd { s -> s.copy(grain = v) } }
}

@Composable
private fun MyAssetsTab(graph: AppGraph, fx: EffectsSettings, upd: ((EffectsSettings) -> EffectsSettings) -> Unit) {
    val ctx = LocalContext.current
    var anchor by remember { mutableStateOf(Anchor.HEAD_TOP) }
    val stickerPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) { }
            val asset = UserAsset(UUID.randomUUID().toString(), uri.lastPathSegment ?: "sticker", uri.toString())
            val layer = StickerLayer(UUID.randomUUID().toString(), "user:${asset.id}", anchor, 1f, userUri = uri.toString(), rotation = RotationMode.BILLBOARD)
            upd { s -> s.copy(userStickers = s.userStickers + asset, stickers = s.stickers + layer, activeLook = null) }
        }
    }
    Text("Import any transparent PNG as a sticker (a hat, a logo, a pet…) and pick where it sticks. Photos and videos become backgrounds on the Backgrounds tab.", color = Color.White, style = MaterialTheme.typography.bodySmall)
    SectionLabel("Attach imported sticker to")
    Row(Modifier.horizontalScroll(rememberScrollState())) { Anchor.entries.forEach { a -> FxChip(a.label, anchor == a) { anchor = a } } }
    Row { Button(onClick = { stickerPicker.launch(arrayOf("image/png", "image/webp", "image/*")) }) { Text("Import PNG sticker") } }
    if (fx.userStickers.isNotEmpty()) {
        SectionLabel("Imported stickers")
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            fx.userStickers.forEach { a ->
                val active = fx.stickers.any { it.userUri == a.uri }
                FxChip("🖼️ ${a.name.take(14)}", active) {
                    upd { s -> if (active) s.copy(stickers = s.stickers.filter { it.userUri != a.uri }) else s.copy(stickers = s.stickers + StickerLayer(UUID.randomUUID().toString(), "user:${a.id}", anchor, 1f, userUri = a.uri)) }
                }
            }
        }
        Row { TextButton(onClick = { upd { s -> s.copy(userStickers = emptyList(), stickers = s.stickers.filter { it.userUri == null }) } }) { Text("Forget imported stickers") } }
    }
    if (graph.settings.current.effects.userBackgrounds.isNotEmpty()) Row { TextButton(onClick = { upd { s -> s.copy(userBackgrounds = emptyList(), background = if (s.background.uri != null) BackgroundSpec() else s.background) } }) { Text("Forget imported backgrounds") } }
}
