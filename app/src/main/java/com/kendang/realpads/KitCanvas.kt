package com.kendang.realpads

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// Seberapa gelap pad saat diketuk / ditahan (0.5 = 50% lebih gelap). Hanya menggelapkan bagian
// gambar yang tidak transparan, jadi PNG transparan tetap mengikuti bentuknya.
private const val PRESS_DARKEN = 0.5f

// Piksel dengan alpha di bawah ini dianggap "kosong" (bukan bagian pad) saat hit-test.
private const val HIT_ALPHA_MIN = 24

private fun alphaAt(bmp: ImageBitmap, px: Int, py: Int): Int {
    val x = px.coerceIn(0, bmp.width - 1)
    val y = py.coerceIn(0, bmp.height - 1)
    return try {
        bmp.asAndroidBitmap().getPixel(x, y) ushr 24
    } catch (e: Throwable) {
        255
    }
}

// Pad paling ATAS (urutan terakhir di daftar) yang kena di titik (x,y) koordinat panggung.
// Untuk gambar PNG transparan, bagian yang transparan tidak dihitung (pad bulat tidak
// "memukul" di sudut kotaknya), dengan sedikit toleransi supaya jari yang meleset tipis tetap kena.
// Return id pad, atau -1 kalau tidak ada.
private fun hitPad(pads: List<KitPad>, x: Float, y: Float, stageW: Float, stageH: Float, useAlpha: Boolean): Int {
    var i = pads.size - 1
    while (i >= 0) {
        val p = pads[i]
        i--
        val h = p.h * stageH
        val w = p.w * stageW
        val left = p.cx * stageW - w / 2f
        val top = p.cy * stageH - h / 2f
        if (x < left || x > left + w || y < top || y > top + h) continue
        if (useAlpha) {
            val bmp = if (p.image == null) null else KitState.images[p.image]
            if (bmp != null && w > 0f && h > 0f) {
                val bx = ((x - left) / w * bmp.width).toInt()
                val by = ((y - top) / h * bmp.height).toInt()
                val r = maxOf(2, (bmp.width * 0.04f).toInt())
                val hit = alphaAt(bmp, bx, by) >= HIT_ALPHA_MIN ||
                    alphaAt(bmp, bx - r, by) >= HIT_ALPHA_MIN ||
                    alphaAt(bmp, bx + r, by) >= HIT_ALPHA_MIN ||
                    alphaAt(bmp, bx, by - r) >= HIT_ALPHA_MIN ||
                    alphaAt(bmp, bx, by + r) >= HIT_ALPHA_MIN
                if (!hit) continue
            }
        }
        return p.id
    }
    return -1
}

// Area kit: "panggung" yang di-STRETCH memenuhi seluruh area pad (seperti skin / pad normal, skala X
// dan Y boleh beda), berisi gambar latar + semua pad. Tiap pad punya posisi (cx, cy) dan ukuran (w, h)
// sebagai pecahan panggung, jadi letaknya tetap konsisten di layar berukuran apa pun. Mode MAIN: jari menekan pad -> onPadDown (suara), pad digelapkan
// selama jari menempel dan kembali terang begitu dilepas. Mode EDIT: pad bisa digeser (tarik
// badan pad) dan diubah ukurannya (tarik bulatan di sudut kanan bawah pad terpilih), tanpa suara.
@Composable
fun KitCanvas(
    kit: Kit,
    editing: Boolean,
    selectedPad: Int?,
    onSelectPad: (Int?) -> Unit,
    onPadDown: (Int) -> Unit,
    // Intensitas kedip 0..1 per pad dari PadScreen (flashIntensity) - sama dengan yang dipakai skin.
    padFlash: (Int) -> Float,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.background(BgColor)) {
        // Panggung = seluruh area (stretch). Tidak ada contain / letterbox.
        val stageW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val stageH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val sw = stageW
        val sh = stageH

        val padsNow by rememberUpdatedState(kit.pads)
        val swNow by rememberUpdatedState(sw)
        val shNow by rememberUpdatedState(sh)
        val onPadDownNow by rememberUpdatedState(onPadDown)
        val onSelectNow by rememberUpdatedState(onSelectPad)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .pointerInput(editing) {
                    if (editing) {
                        // Ketuk area kosong = batal pilih pad (ketukan pada pad sudah dikonsumsi pad).
                        detectTapGestures(onTap = { onSelectNow(null) })
                    } else {
                        awaitPointerEventScope {
                            val owner = HashMap<Long, Int>()      // id jari -> pad yang sedang ditekan
                            val downAt = HashMap<Long, Long>()    // id jari -> waktu mulai menekan (ns)
                            // Lepas = jari tidak lagi menahan gelap; sisa kedip meluruh lewat padFlash.
                            fun release(id: Int, @Suppress("UNUSED_PARAMETER") downNano: Long?) {
                                KitState.pressUp(id)
                            }
                            try {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    for (ch in event.changes) {
                                        val pid = ch.id.value
                                        if (!ch.pressed) {
                                            val held = owner.remove(pid)
                                            if (held != null) release(held, downAt.remove(pid))
                                            continue
                                        }
                                        if (ch.changedToDown()) {
                                            val id = hitPad(padsNow, ch.position.x, ch.position.y, swNow, shNow, true)
                                            if (id > 0) {
                                                owner[pid] = id
                                                downAt[pid] = System.nanoTime()
                                                KitState.pressDown(id)
                                                onPadDownNow(id)
                                            }
                                        } else if (ch.positionChanged() && PadSlideRollState.enabled) {
                                            // Sama seperti pad biasa: geser jari ke pad lain = pukulan baru
                                            // (hanya kalau "Geser Pindah Pad" dinyalakan di Pengaturan).
                                            val cur = owner[pid]
                                            val id = hitPad(padsNow, ch.position.x, ch.position.y, swNow, shNow, true)
                                            if (id > 0 && id != cur) {
                                                if (cur != null) release(cur, downAt[pid])
                                                owner[pid] = id
                                                downAt[pid] = System.nanoTime()
                                                KitState.pressDown(id)
                                                onPadDownNow(id)
                                            }
                                        }
                                    }
                                }
                            } finally {
                                // Mode berganti / layar hilang selagi jari menempel: jangan biarkan pad nyangkut gelap.
                                for (entry in owner.entries) KitState.pressUp(entry.value)
                                owner.clear()
                            }
                        }
                    }
                }
        ) {
            val bg = KitState.bgImage
            if (bg != null) {
                Image(
                    bitmap = bg,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    alpha = kit.bgOpacity.coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(modifier = Modifier.fillMaxSize().background(PadLineColor))
            }

            for (pad in kit.pads) {
                key(pad.id) {
                    KitPadView(
                        pad = pad,
                        stageW = sw,
                        stageH = sh,
                        editing = editing,
                        selected = selectedPad == pad.id,
                        padOpacity = kit.padOpacity,
                        padFlash = padFlash,
                        onSelect = { onSelectPad(pad.id) }
                    )
                }
            }

            if (kit.pads.isEmpty()) {
                Text(
                    text = if (editing) "Kit masih kosong.\nTekan Pad Foto di bawah untuk menambah pad."
                    else "Kit masih kosong.\nBuka menu Kit lalu pilih Edit untuk menambah pad.",
                    color = TextDim,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp)
                )
            }
        }
    }
}

@Composable
private fun KitPadView(
    pad: KitPad,
    stageW: Float,
    stageH: Float,
    editing: Boolean,
    selected: Boolean,
    padOpacity: Float,
    padFlash: (Int) -> Float,
    onSelect: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val accent = ThemeState.accentColor
    val bmp = if (pad.image == null) null else KitState.images[pad.image]
    // Ukuran & posisi langsung dari pecahan panggung (stretch) - tidak bergantung rasio gambar.
    val hPx = pad.h * stageH
    val wPx = pad.w * stageW
    // Efek ketuk SAMA seperti skin: gelap penuh saat dipukul & selama jari menempel, lalu meluruh linear
    // (padFlash = flashIntensity PadScreen, 90 ms). Bedanya skin menyalakan pad, kit menggelapkan pad.
    val held = (KitState.pressCount[pad.id] ?: 0) > 0
    val darkAmt = if (editing) 0f else maxOf(if (held) 1f else 0f, padFlash(pad.id)).coerceIn(0f, 1f)
    val dark = darkAmt > 0.01f
    val opacity = padOpacity.coerceIn(0f, 1f)

    val swNow by rememberUpdatedState(stageW)
    val shNow by rememberUpdatedState(stageH)
    val selectNow by rememberUpdatedState(onSelect)

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (pad.cx * stageW - wPx / 2f).roundToInt(),
                    (pad.cy * stageH - hPx / 2f).roundToInt()
                )
            }
            .size(with(density) { wPx.toDp() }, with(density) { hPx.toDp() })
            .then(
                if (editing) {
                    Modifier
                        .border(
                            if (selected) 2.dp else 1.dp,
                            if (selected) accent else accent.copy(alpha = 0.35f)
                        )
                        .pointerInput(pad.id) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                down.consume()
                                selectNow()
                                drag(down.id) { change ->
                                    val d = change.positionChange()
                                    if (d.x != 0f || d.y != 0f) {
                                        val w = swNow
                                        val h = shNow
                                        KitState.updatePad(context, pad.id) { p ->
                                            // Seluruh pad dijaga tetap di dalam area (tidak bisa keluar layar).
                                            p.copy(
                                                cx = p.cx + d.x / w,
                                                cy = p.cy + d.y / h
                                            ).clampedInside()
                                        }
                                    }
                                    change.consume()
                                }
                            }
                        }
                } else {
                    Modifier
                }
            )
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                alpha = opacity,
                colorFilter = if (dark) ColorFilter.tint(Color.Black.copy(alpha = PRESS_DARKEN * darkAmt), BlendMode.SrcAtop) else null,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        lerp(PadColor, Color.Black, PRESS_DARKEN * darkAmt).copy(alpha = opacity),
                        RoundedCornerShape(6.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${pad.id}",
                    color = PadTextColor,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = AppTextSize
                )
            }
        }

        if (editing) {
            // Nomor pad (sama dengan nomor untuk binding keyboard/MIDI 1..16).
            Text(
                text = "${pad.id}",
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(bottomEnd = 4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }

        if (editing && selected) {
            // Pegangan ubah ukuran: tarik ke kanan-bawah = membesar, ke kiri-atas = mengecil.
            // Sudut kiri-atas pad tetap di tempat, jadi bulatannya mengikuti jari.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(26.dp)
                    .background(accent, CircleShape)
                    .border(2.dp, Color.Black.copy(alpha = 0.45f), CircleShape)
                    .pointerInput(pad.id) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            // Ukuran dihitung dari kondisi SAAT JARI MENYENTUH + total geseran (bukan ditumpuk per
                            // gerakan kecil), jadi tidak ada penyimpangan bentuk walau pad dikecilkan sampai mentok
                            // lalu dibesarkan lagi: lebar & tinggi selalu dikali faktor yang sama (scaledUniform).
                            val start = KitState.padById(pad.id)
                            if (start != null) {
                                val sw0 = swNow
                                val sh0 = shNow
                                val startW = (start.w * sw0).coerceAtLeast(1f)
                                val startH = (start.h * sh0).coerceAtLeast(1f)
                                // Sudut kiri-atas pad tetap di tempat.
                                val left = start.cx * sw0 - startW / 2f
                                val top = start.cy * sh0 - startH / 2f
                                var accX = 0f
                                var accY = 0f
                                drag(down.id) { change ->
                                    val d = change.positionChange()
                                    if (d.x != 0f || d.y != 0f) {
                                        accX += d.x
                                        accY += d.y
                                        // Proyeksi geseran ke diagonal pad: ke kanan-bawah = membesar, kiri-atas = mengecil.
                                        val fRaw = 1f + (accX * startW + accY * startH) / (startW * startW + startH * startH)
                                        // Batas tambahan: tepi kanan & bawah pad tidak boleh melewati area (sudut kiri-atas tetap).
                                        val fPos = minOf((sw0 - left) / startW, (sh0 - top) / startH)
                                        val scaled = start.scaledUniform(fRaw.coerceAtMost(maxOf(1f, fPos)))
                                        val newW = scaled.w * sw0
                                        val newH = scaled.h * sh0
                                        KitState.updatePad(context, pad.id) { p ->
                                            p.copy(
                                                w = scaled.w,
                                                h = scaled.h,
                                                cx = (left + newW / 2f) / sw0,
                                                cy = (top + newH / 2f) / sh0
                                            ).clampedInside()
                                        }
                                    }
                                    change.consume()
                                }
                            }
                        }
                    }
            )
        }
    }
}
