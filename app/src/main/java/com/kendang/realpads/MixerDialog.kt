package com.kendang.realpads

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// =====================================================================================
// MIXER (v1.13.0)
//
// Layar mixing per pad, gaya senada UI app (CtrlPanelColor / PanelColor / aksen tema), bukan
// tiruan layar mixer lain. Isi:
//   - MASTER (volume kendang) + MUSIK (volume lagu) di kiri - sama persis dengan slider PAD & MUSIK
//     di panel kanan, jadi dua-duanya saling mengikuti.
//   - 1 strip per pad: kotak PAN (L/R), kotak TUNE (semitone + fine tune), fader VOLUME, nama pad.
//   - TIDAK ada Mute/Solo (sengaja disederhanakan).
//   - Pad yang lagi DIPILIH diputar BERULANG (pad-nya sendiri, suara asli) selama dialog terbuka,
//     dan diputar ulang sebentar setelah nilai diubah, supaya telinga langsung tahu "segini pas".
//   - PAN & TUNE dibuka sebagai dialog di TENGAH layar.
//
// Dialog ini murni UI: semua nilai disimpan lewat callback (MainActivity -> *Storage / KitState).
// Pemutaran preview memakai AudioEngine.nativeTrigger langsung lewat callback, BUKAN onPadDown,
// jadi tidak ikut menaikkan hitungan misi event, kedip pad, atau efek GLER.
// =====================================================================================

private const val MIX_PREVIEW_INTERVAL_MS = 1200L   // jeda antar putaran ulang pad terpilih
private const val MIX_PREVIEW_DEBOUNCE_MS = 150L    // tunggu nilai berhenti berubah sebelum diputar ulang
private const val MIX_DEFAULT_VOLUME = 1f
private const val MIX_MAX_VOLUME = 1.5f

private val StripWidth = 66.dp
private val CardShape = RoundedCornerShape(10.dp)

@Composable
fun MixerDialog(
    // Nomor pad yang ditampilkan (mode normal 12/16 pad, mode kit = pad kit).
    padIds: List<Int>,
    padVolumes: Map<Int, Float>,
    padPans: Map<Int, Float>,       // -1..1
    padTunes: Map<Int, Float>,      // semitone (bulat)
    padFines: Map<Int, Float>,      // cent -100..100
    padLoops: Map<Int, Boolean>,
    masterVolume: Float,
    musicVolume: Float,
    onMasterVolumeChange: (Float) -> Unit,
    onMusicVolumeChange: (Float) -> Unit,
    onVolumeChange: (pad: Int, volume: Float) -> Unit,
    onPanChange: (pad: Int, pan: Float) -> Unit,
    onTuneChange: (pad: Int, semitones: Float) -> Unit,
    onFineTuneChange: (pad: Int, cents: Float) -> Unit,
    // Bunyikan pad (1 ketukan). Untuk pad loop: ketukan pertama = mulai, ketukan kedua = berhenti.
    onPreviewTrigger: (pad: Int) -> Unit,
    isPadLooping: (pad: Int) -> Boolean,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf<Int?>(null) }
    // Naik tiap nilai pad terpilih berubah -> memicu putar ulang (setelah debounce).
    var changeTick by remember { mutableIntStateOf(0) }
    var panEditPad by remember { mutableStateOf<Int?>(null) }
    var tuneEditPad by remember { mutableStateOf<Int?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }

    val currentTrigger by rememberUpdatedState(onPreviewTrigger)
    val currentIsLooping by rememberUpdatedState(isPadLooping)

    fun select(pad: Int) {
        selected = pad
        changeTick = 0
    }

    // Pad yang diubah otomatis jadi pad terpilih (kalau belum), lalu minta putar ulang.
    fun touched(pad: Int) {
        if (selected != pad) {
            select(pad)
        } else {
            changeTick += 1
        }
    }

    // --- Putar BERULANG pad terpilih ---
    // Non-loop: ketuk tiap MIX_PREVIEW_INTERVAL_MS. Pad dengan setelan Loop: pad itu sudah muter sendiri,
    // jadi cukup distop lalu dimulai ulang (supaya pan/tune/volume baru kepakai) - TIDAK diketuk berulang
    // karena ketukan kedua pada pad loop artinya STOP.
    LaunchedEffect(selected, changeTick) {
        val pad = selected ?: return@LaunchedEffect
        if (changeTick > 0) delay(MIX_PREVIEW_DEBOUNCE_MS)
        if (padLoops[pad] == true) {
            if (currentIsLooping(pad)) {
                currentTrigger(pad)   // stop loop lama
                delay(60)
            }
            currentTrigger(pad)       // mulai loop dengan nilai baru
        } else {
            while (true) {
                currentTrigger(pad)
                delay(MIX_PREVIEW_INTERVAL_MS)
            }
        }
    }
    // Ganti pad terpilih / tutup mixer: kalau pad loop masih muter gara-gara preview, hentikan.
    DisposableEffect(selected) {
        val pad = selected
        onDispose {
            if (pad != null && padLoops[pad] == true && currentIsLooping(pad)) currentTrigger(pad)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BgColor)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // ------------------------------------------------------------ header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(PanelColor, RoundedCornerShape(6.dp))
                        .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowLeft,
                        contentDescription = "Tutup mixer",
                        tint = PadTextColor,
                        modifier = Modifier.size(ControlIconSize)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    "MIXER",
                    color = PadTextColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.width(12.dp))
                val sel = selected
                Text(
                    text = if (sel != null) "P$sel diputar berulang - ketuk namanya lagi untuk berhenti"
                    else "Pilih pad untuk didengarkan berulang",
                    color = if (sel != null) ThemeState.accentColor else TextDim,
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .height(36.dp)
                        .background(PanelColor, RoundedCornerShape(6.dp))
                        .pointerInput(Unit) { detectTapGestures(onTap = { showResetConfirm = true }) }
                        .padding(horizontal = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.RestartAlt,
                        contentDescription = null,
                        tint = PadTextColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Text("RESET", color = PadTextColor, fontWeight = FontWeight.SemiBold, fontSize = AppTextSize)
                }
            }

            Spacer(Modifier.height(8.dp))

            // ------------------------------------------------------------ strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Grup kiri: MASTER + MUSIK (sama dengan slider PAD & MUSIK di panel kanan).
                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .clip(CardShape)
                        .background(CtrlPanelColor)
                        .border(1.dp, PadLineColor, CardShape)
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MixerBusStrip(
                        label = "MASTER",
                        icon = { tint ->
                            Icon(Icons.Filled.VolumeUp, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                        },
                        volume = masterVolume,
                        onVolumeChange = onMasterVolumeChange
                    )
                    MixerBusStrip(
                        label = "MUSIK",
                        icon = { tint ->
                            Icon(Icons.Filled.MusicNote, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                        },
                        volume = musicVolume,
                        onVolumeChange = onMusicVolumeChange
                    )
                }

                // Grup kanan: 1 strip per pad, bisa digeser ke samping.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CardShape)
                        .background(CtrlPanelColor)
                        .border(1.dp, PadLineColor, CardShape)
                ) {
                    LazyRow(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(padIds, key = { it }) { pad ->
                            val volume = padVolumes[pad] ?: MIX_DEFAULT_VOLUME
                            val pan = padPans[pad] ?: 0f
                            val semis = padTunes[pad] ?: 0f
                            val fine = padFines[pad] ?: 0f
                            MixerPadStrip(
                                label = "P$pad",
                                selected = selected == pad,
                                volume = volume,
                                panPercent = (pan * 100f).roundToInt(),
                                tuneCents = (semis * 100f).roundToInt() + fine.roundToInt(),
                                onSelect = { if (selected != pad) select(pad) },
                                onToggleSelect = { if (selected == pad) selected = null else select(pad) },
                                onVolumeChange = {
                                    onVolumeChange(pad, it)
                                    touched(pad)
                                },
                                onOpenPan = {
                                    if (selected != pad) select(pad)
                                    panEditPad = pad
                                },
                                onOpenTune = {
                                    if (selected != pad) select(pad)
                                    tuneEditPad = pad
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- dialog di TENGAH layar
    panEditPad?.let { pad ->
        val panPercent = ((padPans[pad] ?: 0f) * 100f).roundToInt()
        MixerPanDialog(
            title = "PAN - P$pad",
            panPercent = panPercent,
            onChange = { p ->
                onPanChange(pad, p.coerceIn(-100, 100) / 100f)
                touched(pad)
            },
            onDismiss = { panEditPad = null }
        )
    }

    tuneEditPad?.let { pad ->
        MixerTuneDialog(
            title = "TUNE - P$pad",
            semitones = (padTunes[pad] ?: 0f).roundToInt(),
            fineCents = (padFines[pad] ?: 0f).roundToInt(),
            onSemitonesChange = { s ->
                onTuneChange(pad, s.coerceIn(-12, 12).toFloat())
                touched(pad)
            },
            onFineChange = { c ->
                onFineTuneChange(pad, c.coerceIn(-100, 100).toFloat())
                touched(pad)
            },
            onDismiss = { tuneEditPad = null }
        )
    }

    if (showResetConfirm) {
        ConfirmDialog(
            title = "Reset mixer?",
            message = "Volume, pan, dan tune semua pad di daftar ini dikembalikan ke bawaan. Volume MASTER dan MUSIK tidak berubah.",
            confirmLabel = "RESET",
            dismissLabel = "BATAL",
            onConfirm = {
                for (pad in padIds) {
                    onVolumeChange(pad, MIX_DEFAULT_VOLUME)
                    onPanChange(pad, 0f)
                    onTuneChange(pad, 0f)
                    onFineTuneChange(pad, 0f)
                }
                showResetConfirm = false
                if (selected != null) changeTick += 1
            },
            onDismiss = { showResetConfirm = false }
        )
    }
}

// ------------------------------------------------------------------------------------
// Strip PAD: PAN, TUNE, fader volume, nama.
// ------------------------------------------------------------------------------------
@Composable
private fun MixerPadStrip(
    label: String,
    selected: Boolean,
    volume: Float,
    panPercent: Int,
    tuneCents: Int,
    onSelect: () -> Unit,
    onToggleSelect: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onOpenPan: () -> Unit,
    onOpenTune: () -> Unit
) {
    val accent = ThemeState.accentColor
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .width(StripWidth)
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) accent.copy(alpha = 0.10f) else Color.Transparent)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) accent else PadLineColor,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 5.dp, vertical = 6.dp)
    ) {
        // PAN: tulisan + bar kecil (isi dari tengah ke arah L/R)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(PanelColor, RoundedCornerShape(6.dp))
                .pointerInput(Unit) { detectTapGestures(onTap = { onOpenPan() }) }
                .padding(vertical = 5.dp)
        ) {
            Text(
                text = panChipText(panPercent),
                color = if (panPercent == 0) TextDim else accent,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                maxLines = 1
            )
            PanBar(panPercent = panPercent, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp).height(5.dp))
        }

        // TUNE
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .background(PanelColor, RoundedCornerShape(6.dp))
                .pointerInput(Unit) { detectTapGestures(onTap = { onOpenTune() }) },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = tuneChipText(tuneCents),
                color = if (tuneCents == 0) TextDim else accent,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                maxLines = 1
            )
        }

        // FADER volume
        MixerFader(
            value = volume,
            range = 0f..MIX_MAX_VOLUME,
            unity = MIX_DEFAULT_VOLUME,
            onChange = onVolumeChange,
            onTouch = onSelect,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )

        Text(
            "${(volume * 100f).roundToInt()}%",
            color = TextDim,
            fontSize = 11.sp,
            maxLines = 1
        )

        // Nama pad = tombol pilih / berhenti dengar (menyala aksen saat terpilih)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .background(if (selected) accent else PanelColor, RoundedCornerShape(6.dp))
                .pointerInput(Unit) { detectTapGestures(onTap = { onToggleSelect() }) },
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                color = if (selected) ThemeState.accentTextColor else PadTextColor,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                fontSize = AppTextSize,
                maxLines = 1
            )
        }
    }
}

// Strip MASTER / MUSIK: nilai, fader, ikon, nama - tanpa PAN/TUNE.
@Composable
private fun MixerBusStrip(
    label: String,
    icon: @Composable (tint: Color) -> Unit,
    volume: Float,
    onVolumeChange: (Float) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .width(StripWidth)
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, PadLineColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 5.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .background(PanelColor, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "${(volume * 100f).roundToInt()}%",
                color = ThemeState.accentColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                maxLines = 1
            )
        }
        MixerFader(
            value = volume,
            range = 0f..MIX_MAX_VOLUME,
            unity = MIX_DEFAULT_VOLUME,
            onChange = onVolumeChange,
            onTouch = {},
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )
        icon(TextDim)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .background(PanelColor, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(label, color = PadTextColor, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 1)
        }
    }
}

// Bar pan kecil: garis tengah + isi aksen dari tengah ke arah L atau R.
@Composable
private fun PanBar(panPercent: Int, modifier: Modifier = Modifier) {
    val accent = ThemeState.accentColor
    Canvas(modifier = modifier) {
        drawRect(color = CtrlPanelColor, size = size)
        val half = size.width / 2f
        val len = half * (abs(panPercent) / 100f)
        if (panPercent < 0) {
            drawRect(color = accent, topLeft = Offset(half - len, 0f), size = Size(len, size.height))
        } else if (panPercent > 0) {
            drawRect(color = accent, topLeft = Offset(half, 0f), size = Size(len, size.height))
        }
        // penanda tengah
        drawRect(color = TextDim, topLeft = Offset(half - 0.75f, 0f), size = Size(1.5f, size.height))
    }
}

// ------------------------------------------------------------------------------------
// Fader vertikal: sentuh/geser = ubah nilai, ketuk 2x = balik ke nilai bawaan (unity).
// Menempel (detent) ke unity kalau digeser mendekatinya.
// ------------------------------------------------------------------------------------
@Composable
private fun MixerFader(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unity: Float,
    onChange: (Float) -> Unit,
    onTouch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = ThemeState.accentColor
    val density = LocalDensity.current
    val thumbHeight = 32.dp
    val thumbWidth = 44.dp
    val thumbHeightPx = with(density) { thumbHeight.toPx() }

    var heightPx by remember { mutableIntStateOf(0) }
    val currentHeight by rememberUpdatedState(heightPx)
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnTouch by rememberUpdatedState(onTouch)

    val span = range.endInclusive - range.start
    val frac = ((value - range.start) / span).coerceIn(0f, 1f)
    val travel = (heightPx - thumbHeightPx).coerceAtLeast(1f)
    val thumbTopPx = ((1f - frac) * travel)
    val unityFrac = ((unity - range.start) / span).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .onSizeChanged { heightPx = it.height }
            .pointerInput(range, unity) {
                var lastTapUp = 0L
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    currentOnTouch()
                    val isDouble = down.uptimeMillis - lastTapUp < 300L

                    fun setFromY(y: Float) {
                        val h = currentHeight.toFloat()
                        val tr = (h - thumbHeightPx).coerceAtLeast(1f)
                        val f = (1f - (y - thumbHeightPx / 2f) / tr).coerceIn(0f, 1f)
                        var v = range.start + f * span
                        if (abs(v - unity) < span * 0.025f) v = unity   // detent di unity
                        v = (v * 100f).roundToInt() / 100f
                        currentOnChange(v.coerceIn(range.start, range.endInclusive))
                    }

                    if (!isDouble) setFromY(down.position.y)
                    var moved = false
                    drag(down.id) { change ->
                        moved = true
                        setFromY(change.position.y)
                        change.consume()
                    }
                    if (!moved) {
                        if (isDouble) {
                            currentOnChange(unity)
                            lastTapUp = 0L
                        } else {
                            lastTapUp = down.uptimeMillis
                        }
                    } else {
                        lastTapUp = 0L
                    }
                }
            },
        contentAlignment = Alignment.TopCenter
    ) {
        // rel + jalur + isi aksen + tanda unity
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val trackW = 3.dp.toPx()
            val half = thumbHeightPx / 2f
            val trackTop = half
            val trackBottom = size.height - half
            drawRect(
                color = PadLineColor,
                topLeft = Offset(cx - trackW / 2f, trackTop),
                size = Size(trackW, (trackBottom - trackTop).coerceAtLeast(0f))
            )
            val thumbCenter = thumbTopPx + half
            drawRect(
                color = accent,
                topLeft = Offset(cx - trackW / 2f, thumbCenter),
                size = Size(trackW, (trackBottom - thumbCenter).coerceAtLeast(0f))
            )
            // tanda unity (100%)
            val uy = half + (1f - unityFrac) * (size.height - thumbHeightPx)
            val tickLen = 9.dp.toPx()
            val tickH = 1.5.dp.toPx()
            drawRect(color = TextDim, topLeft = Offset(0f, uy - tickH / 2f), size = Size(tickLen, tickH))
            drawRect(color = TextDim, topLeft = Offset(size.width - tickLen, uy - tickH / 2f), size = Size(tickLen, tickH))
        }
        // knob
        Box(
            modifier = Modifier
                .offset { IntOffset(0, thumbTopPx.roundToInt()) }
                .width(thumbWidth)
                .height(thumbHeight)
                .shadow(3.dp, RoundedCornerShape(6.dp))
                .background(PanelColor, RoundedCornerShape(6.dp))
                .border(1.dp, PadColor, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                repeat(3) {
                    Box(
                        Modifier
                            .width(18.dp)
                            .height(1.5.dp)
                            .background(if (it == 1) accent else TextDim)
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------
// Dialog di TENGAH layar
// ------------------------------------------------------------------------------------

// Kartu dialog: gaya sama dengan ConfirmDialog (PanelColor, sudut 14dp, bayangan), muncul di tengah
// layar. Boleh ditutup dengan ketuk di luar / tombol back.
@Composable
private fun MixerPopup(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth(0.9f)
                .heightIn(max = 340.dp)
                .shadow(elevation = 18.dp, shape = RoundedCornerShape(14.dp), clip = false)
                .clip(RoundedCornerShape(14.dp))
                .background(PanelColor)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Text(
                title,
                color = TextDim,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 1.5.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun MixerPanDialog(
    title: String,
    panPercent: Int,
    onChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    MixerPopup(title = title, onDismiss = onDismiss) {
        ValueStepper(
            valueText = panBigText(panPercent),
            highlight = panPercent != 0,
            onMinus = { onChange(panPercent - 1) },
            onPlus = { onChange(panPercent + 1) }
        )
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("L 100", color = TextDim, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text(
                "TENGAH",
                color = if (panPercent == 0) ThemeState.accentColor else TextDim,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text("R 100", color = TextDim, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
        MixerSlider(
            value = panPercent.toFloat(),
            range = -100f..100f,
            snapToZero = true,
            onChange = { onChange(it.roundToInt()) }
        )
        Spacer(Modifier.height(6.dp))
        PopupButtons(
            onReset = { onChange(0) },
            resetLabel = "TENGAH",
            onDone = onDismiss
        )
    }
}

@Composable
private fun MixerTuneDialog(
    title: String,
    semitones: Int,
    fineCents: Int,
    onSemitonesChange: (Int) -> Unit,
    onFineChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var tab by remember { mutableIntStateOf(0) } // 0 = semitone, 1 = fine tune
    MixerPopup(title = title, onDismiss = onDismiss) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CtrlPanelColor, RoundedCornerShape(8.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            TuneTab("SEMITON (±12)", tab == 0, { tab = 0 }, Modifier.weight(1f))
            TuneTab("FINE TUNE (±100)", tab == 1, { tab = 1 }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))

        if (tab == 0) {
            ValueStepper(
                valueText = signed(semitones),
                highlight = semitones != 0,
                onMinus = { onSemitonesChange(semitones - 1) },
                onPlus = { onSemitonesChange(semitones + 1) }
            )
            Spacer(Modifier.height(4.dp))
            RangeCaption("-12 ST", "TENGAH", "+12 ST", semitones == 0)
            MixerSlider(
                value = semitones.toFloat(),
                range = -12f..12f,
                snapToZero = false,
                onChange = { onSemitonesChange(it.roundToInt()) }
            )
        } else {
            ValueStepper(
                valueText = signed(fineCents),
                highlight = fineCents != 0,
                onMinus = { onFineChange(fineCents - 1) },
                onPlus = { onFineChange(fineCents + 1) }
            )
            Spacer(Modifier.height(4.dp))
            RangeCaption("-100 CENT", "TENGAH", "+100 CENT", fineCents == 0)
            MixerSlider(
                value = fineCents.toFloat(),
                range = -100f..100f,
                snapToZero = true,
                onChange = { onFineChange(it.roundToInt()) }
            )
        }

        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(CtrlPanelColor, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text("Total", color = TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(
                totalOffsetText(semitones, fineCents),
                color = if (semitones == 0 && fineCents == 0) TextDim else ThemeState.accentColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp
            )
        }

        Spacer(Modifier.height(10.dp))
        PopupButtons(
            onReset = {
                onSemitonesChange(0)
                onFineChange(0)
            },
            resetLabel = "RESET",
            onDone = onDismiss
        )
    }
}

@Composable
private fun TuneTab(label: String, selected: Boolean, onTap: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(if (selected) ThemeState.accentColor else Color.Transparent, RoundedCornerShape(6.dp))
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) ThemeState.accentTextColor else TextDim,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun RangeCaption(left: String, center: String, right: String, centerActive: Boolean) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(left, color = TextDim, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text(
            center,
            color = if (centerActive) ThemeState.accentColor else TextDim,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(right, color = TextDim, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

// [-]  nilai besar  [+]  - tahan tombol = berulang cepat.
@Composable
private fun ValueStepper(
    valueText: String,
    highlight: Boolean,
    onMinus: () -> Unit,
    onPlus: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        StepButton(isPlus = false, onStep = onMinus)
        Text(
            valueText,
            color = if (highlight) ThemeState.accentColor else PadTextColor,
            fontWeight = FontWeight.Bold,
            fontSize = 34.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f)
        )
        StepButton(isPlus = true, onStep = onPlus)
    }
}

@Composable
private fun StepButton(isPlus: Boolean, onStep: () -> Unit) {
    val step by rememberUpdatedState(onStep)
    Box(
        modifier = Modifier
            .size(46.dp)
            .background(CtrlPanelColor, RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    step()
                    coroutineScope {
                        val job = launch {
                            delay(380)
                            while (true) {
                                step()
                                delay(55)
                            }
                        }
                        tryAwaitRelease()
                        job.cancel()
                    }
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isPlus) Icons.Filled.Add else Icons.Filled.Remove,
            contentDescription = if (isPlus) "Tambah" else "Kurangi",
            tint = PadTextColor,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun MixerSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    snapToZero: Boolean,
    onChange: (Float) -> Unit
) {
    Slider(
        value = value.coerceIn(range.start, range.endInclusive),
        onValueChange = { v ->
            // detent kecil di tengah (hanya untuk rentang ±100) supaya mudah kembali ke 0
            onChange(if (snapToZero && abs(v) < 3f) 0f else v)
        },
        valueRange = range,
        colors = SliderDefaults.colors(
            thumbColor = ThemeState.accentColor,
            activeTrackColor = ThemeState.accentColor,
            inactiveTrackColor = CtrlPanelColor
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

// Tombol bawah dialog: [RESET/TENGAH] [OK] - gaya sama dengan ConfirmDialog.
@Composable
private fun PopupButtons(onReset: () -> Unit, resetLabel: String, onDone: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Button(
            onClick = onReset,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CtrlPanelColor, contentColor = PadTextColor)
        ) {
            Text(resetLabel, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        }
        Button(
            onClick = onDone,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ThemeState.accentColor,
                contentColor = ThemeState.accentTextColor
            )
        ) {
            Text("OK", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

// ------------------------------------------------------------------------------------
// Format teks
// ------------------------------------------------------------------------------------

private fun signed(v: Int): String = if (v > 0) "+$v" else "$v"

// Teks di kotak PAN pada strip: "PAN" saat tengah, "L 20" / "R 30" saat bergeser.
private fun panChipText(p: Int): String = when {
    p == 0 -> "PAN"
    p < 0 -> "L ${-p}"
    else -> "R $p"
}

// Angka besar di dialog PAN: "C", "L41", "R30".
private fun panBigText(p: Int): String = when {
    p == 0 -> "C"
    p < 0 -> "L${-p}"
    else -> "R$p"
}

// Offset total dalam semitone dari total cent. 0 -> "0", 150 -> "+1.5", -235 -> "-2.35".
private fun semitoneText(totalCents: Int): String {
    if (totalCents == 0) return "0"
    val sign = if (totalCents > 0) "+" else "-"
    val a = abs(totalCents)
    val whole = a / 100
    val rem = a % 100
    return if (rem == 0) "$sign$whole"
    else sign + whole + "." + (if (rem % 10 == 0) (rem / 10).toString() else String.format(Locale.US, "%02d", rem))
}

// Teks di kotak TUNE pada strip: "TUNE" saat 0, selain itu offset total dalam semitone.
private fun tuneChipText(totalCents: Int): String = if (totalCents == 0) "TUNE" else semitoneText(totalCents)

// Baris "Total": mis. "+2.35 st (+2 st, 35c)".
private fun totalOffsetText(semitones: Int, fineCents: Int): String {
    val total = semitones * 100 + fineCents
    return String.format(Locale.US, "%+.2f st (%+d st, %+dc)", total / 100.0, semitones, fineCents)
}
