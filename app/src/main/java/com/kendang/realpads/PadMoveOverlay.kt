package com.kendang.realpads

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Overlay MODE EDIT pindah suara pad (dipakai tampilan klasik DAN skin).
//
// Tampilan:
//  - Setiap pad tertutup kotak menyala berwarna tema (ThemeState.accentColor) dengan kotak menyala (tanpa tulisan).
//  - Jari menekan sebuah pad -> pad itu "terangkat": membesar, bayangan, dan ikut jari. Di tempat asalnya
//    tertinggal bayangan putus-putus (ghost).
//  - Jari melewati pad lain -> pad itu diberi tanda khusus (putih terang, garis tegas, ikon tukar, berdenyut) =
//    "pad ini yang akan ditukar suaranya".
//  - Jari dilepas di atas pad tanda -> pad terangkat meluncur turun ke pad itu, lalu onSwap(asal, tujuan).
//    Dilepas di tempat lain -> pad meluncur kembali, tidak ada yang berubah.
//
// Sentuhan di area ini seluruhnya dikonsumsi overlay (pad di bawahnya tidak berbunyi).
//
// [padRects] = nomor pad -> posisi di koordinat overlay ini (piksel). Boleh SnapshotStateMap.
@Composable
fun PadMoveOverlay(
    padRects: Map<Int, Rect>,
    onSwap: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val snapPx = with(density) { 14.dp.toPx() }
    val curRects by rememberUpdatedState(padRects)
    val curSwap by rememberUpdatedState(onSwap)

    var dragPad by remember { mutableStateOf<Int?>(null) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset.Zero) }
    var target by remember { mutableStateOf<Int?>(null) }
    var settle by remember { mutableStateOf<Settle?>(null) }
    var flashPads by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var hint by remember { mutableStateOf(true) }
    var flashTick by remember { mutableStateOf(0) }

    val lift = remember { Animatable(0f) }
    val settleP = remember { Animatable(0f) }
    val flash = remember { Animatable(0f) }

    val transition = rememberInfiniteTransition(label = "padmove")
    val pulse = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(850, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )

    LaunchedEffect(Unit) {
        delay(4500)
        hint = false
    }
    LaunchedEffect(dragPad) {
        lift.animateTo(if (dragPad != null) 1f else 0f, tween(140))
    }
    // Kilatan putih di dua pad setelah suara ditukar - efek terpisah supaya tidak ikut terbatalkan saat settle dibersihkan.
    LaunchedEffect(flashTick) {
        if (flashTick == 0) return@LaunchedEffect
        flash.snapTo(1f)
        flash.animateTo(0f, tween(480))
    }
    // Pad terangkat meluncur ke tujuan (atau kembali ke asal), baru kemudian suara ditukar.
    LaunchedEffect(settle) {
        val s = settle ?: return@LaunchedEffect
        settleP.snapTo(0f)
        settleP.animateTo(1f, tween(170, easing = FastOutSlowInEasing))
        val t = s.target
        if (t != null) {
            curSwap(s.pad, t)
            flashPads = setOf(s.pad, t)
            flashTick++
            settle = null
            dragPad = null
            target = null
        } else {
            settle = null
            dragPad = null
            target = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    var activeId: PointerId? = null
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val rects = curRects
                        // Sedang meluncur: abaikan sentuhan (170 ms) tapi tetap jangan tembus ke pad di bawah.
                        if (settle != null) {
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        val id = activeId
                        if (id == null) {
                            val down = event.changes.firstOrNull { it.changedToDown() }
                            if (down != null) {
                                val pad = padAtPoint(rects, down.position, 0f, null)
                                val r = if (pad != null) rects[pad] else null
                                if (pad != null && r != null) {
                                    activeId = down.id
                                    dragPad = pad
                                    hint = false
                                    finger = down.position
                                    grab = down.position - r.center
                                    target = null
                                }
                            }
                        } else {
                            val ch = event.changes.firstOrNull { it.id == id }
                            if (ch == null) {
                                activeId = null
                                dragPad = null
                                target = null
                            } else if (ch.pressed) {
                                finger = ch.position
                                target = padAtPoint(rects, ch.position, snapPx, dragPad)
                            } else {
                                val pad = dragPad
                                val r = if (pad != null) rects[pad] else null
                                if (pad != null && r != null) {
                                    val tgt = padAtPoint(rects, ch.position, snapPx, pad)
                                    val tr = if (tgt != null) rects[tgt] else null
                                    settle = Settle(
                                        pad = pad,
                                        from = ch.position - grab,
                                        to = tr?.center ?: r.center,
                                        target = if (tr != null) tgt else null
                                    )
                                } else {
                                    dragPad = null
                                    target = null
                                }
                                activeId = null
                            }
                        }
                        event.changes.forEach { it.consume() }
                    }
                }
            }
    ) {
        val dp = dragPad
        val s = settle
        val tgtNow = target

        // Semua pad: menyala. Pad asal = ghost, pad tujuan = tanda khusus.
        for ((num, r) in padRects.entries.sortedBy { it.key }) {
            val mode = when {
                num == dp -> TileMode.GHOST
                dp != null && num == tgtNow -> TileMode.TARGET
                else -> TileMode.NORMAL
            }
            val isFlash = num in flashPads
            PadTile(
                num = num,
                wPx = r.width,
                hPx = r.height,
                mode = mode,
                pulse = { pulse.value },
                flash = { if (isFlash) flash.value else 0f },
                modifier = Modifier
                    .offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                    .graphicsLayer {
                        val sc = if (mode == TileMode.TARGET) 1f + 0.05f * pulse.value else 1f
                        scaleX = sc
                        scaleY = sc
                    }
            )
        }

        // Pad yang sedang diangkat: di atas segalanya, mengikuti jari.
        if (dp != null) {
            val r = padRects[dp]
            if (r != null) {
                PadTile(
                    num = dp,
                    wPx = r.width,
                    hPx = r.height,
                    mode = TileMode.LIFTED,
                    pulse = { pulse.value },
                    flash = { 0f },
                    modifier = Modifier
                        .zIndex(10f)
                        .offset {
                            val c = if (s != null) {
                                val p = settleP.value
                                Offset(s.from.x + (s.to.x - s.from.x) * p, s.from.y + (s.to.y - s.from.y) * p)
                            } else {
                                finger - grab
                            }
                            IntOffset((c.x - r.width / 2f).roundToInt(), (c.y - r.height / 2f).roundToInt())
                        }
                        .graphicsLayer {
                            // Terangkat: membesar + bayangan + sedikit miring. Saat turun (settle) semuanya kembali normal.
                            val up = lift.value * (1f - (if (s != null) settleP.value else 0f))
                            val sc = 1f + 0.16f * up
                            scaleX = sc
                            scaleY = sc
                            rotationZ = 1.5f * up
                            shadowElevation = 18.dp.toPx() * up
                            shape = RoundedCornerShape(4.dp)
                            clip = false
                        }
                )
            }
        }

        // Petunjuk singkat, hilang sendiri / begitu pad pertama diangkat.
        if (hint && dp == null) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 6.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = "Mode edit: tahan & geser pad ke pad lain untuk menukar suara",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

private class Settle(val pad: Int, val from: Offset, val to: Offset, val target: Int?)

private enum class TileMode { NORMAL, GHOST, TARGET, LIFTED }

// Pad yang kena titik [p]. Yang persis mengenai titik menang (kalau tumpang tindih: yang terkecil);
// kalau tidak ada, pad TERDEKAT asal jaraknya <= [snap] (menutup celah antar pad). [exclude] dilewati.
private fun padAtPoint(rects: Map<Int, Rect>, p: Offset, snap: Float, exclude: Int?): Int? {
    var best: Int? = null
    var bestD = Float.MAX_VALUE
    var bestArea = Float.MAX_VALUE
    for ((id, r) in rects) {
        if (id == exclude) continue
        val dx = max(max(r.left - p.x, 0f), p.x - r.right)
        val dy = max(max(r.top - p.y, 0f), p.y - r.bottom)
        val d = hypot(dx, dy)
        if (d > snap) continue
        val area = r.width * r.height
        if (d == 0f) {
            if (bestD > 0f || area < bestArea) {
                best = id
                bestD = 0f
                bestArea = area
            }
        } else if (bestD > 0f && d < bestD) {
            best = id
            bestD = d
        }
    }
    return best
}

@Composable
private fun PadTile(
    num: Int,
    wPx: Float,
    hPx: Float,
    mode: TileMode,
    pulse: () -> Float,
    flash: () -> Float,
    modifier: Modifier
) {
    val density = LocalDensity.current
    val accent = ThemeState.accentColor
    val wDp = with(density) { wPx.toDp() }
    val hDp = with(density) { hPx.toDp() }
    val minDp = min(wDp.value, hDp.value)

    val targetFill = lerpColor(accent, Color.White, 0.65f)
    val liftedFill = lerpColor(accent, Color.White, 0.15f)
    Box(
        modifier = modifier
            .size(wDp, hDp)
            .drawBehind {
                val cr = CornerRadius(4.dp.toPx())
                val p = pulse()
                when (mode) {
                    TileMode.NORMAL -> {
                        val a1 = 0.62f + 0.22f * p
                        val a2 = 0.40f + 0.18f * p
                        drawRoundRect(
                            brush = Brush.radialGradient(
                                colors = listOf(accent.copy(alpha = a1), accent.copy(alpha = a2)),
                                center = Offset(size.width / 2f, size.height / 2f),
                                radius = max(size.width, size.height) * 0.7f
                            ),
                            cornerRadius = cr
                        )
                        drawRoundRect(
                            color = accent.copy(alpha = 0.55f + 0.4f * p),
                            style = Stroke(2.dp.toPx()),
                            cornerRadius = cr
                        )
                    }
                    TileMode.GHOST -> {
                        drawRoundRect(color = accent.copy(alpha = 0.10f), cornerRadius = cr)
                        drawRoundRect(
                            color = accent.copy(alpha = 0.75f),
                            style = Stroke(
                                width = 2.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12.dp.toPx(), 8.dp.toPx()))
                            ),
                            cornerRadius = cr
                        )
                    }
                    TileMode.TARGET -> {
                        drawRoundRect(
                            color = lerpColor(accent, Color.White, 0.55f + 0.25f * p).copy(alpha = 0.97f),
                            cornerRadius = cr
                        )
                        drawRoundRect(color = Color.White, style = Stroke(3.5f.dp.toPx()), cornerRadius = cr)
                        val inset = 6.dp.toPx()
                        if (size.width > inset * 3f && size.height > inset * 3f) {
                            drawRoundRect(
                                color = bestTextColorFor(targetFill).copy(alpha = 0.75f),
                                topLeft = Offset(inset, inset),
                                size = Size(size.width - inset * 2f, size.height - inset * 2f),
                                style = Stroke(
                                    width = 1.5f.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))
                                ),
                                cornerRadius = cr
                            )
                        }
                    }
                    TileMode.LIFTED -> {
                        drawRoundRect(color = liftedFill, cornerRadius = cr)
                        drawRoundRect(color = Color.White, style = Stroke(2.5f.dp.toPx()), cornerRadius = cr)
                    }
                }
                // Kilatan putih singkat setelah suara berhasil ditukar.
                val f = flash()
                if (f > 0f) drawRoundRect(color = Color.White.copy(alpha = 0.75f * f), cornerRadius = cr)
            },
        contentAlignment = Alignment.Center
    ) {
        if (mode == TileMode.TARGET) {
            // Tanda khusus: lencana ikon tukar di pojok kanan atas.
            val badge = min(22f, minDp * 0.38f).coerceAtLeast(12f).dp
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(badge)
                    .background(bestTextColorFor(targetFill), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.SwapHoriz,
                    contentDescription = "Tukar suara",
                    tint = targetFill,
                    modifier = Modifier.fillMaxSize().padding(2.dp)
                )
            }
        }
    }
}
