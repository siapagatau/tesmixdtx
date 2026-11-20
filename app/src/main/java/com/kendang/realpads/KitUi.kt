package com.kendang.realpads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// ===================================================================================
// UI fitur Kit. Semua tombol pakai ikon + label pendek. Tidak ada tombol Kit yang menempel di
// strip bawah / panel kiri / panel kanan: pintu masuknya hanya 1 ikon di tab tipis kanan
// (lihat ClassicToggleTab / SkinToggleTab), dan bilah edit hanya muncul saat mode edit kit.
// ===================================================================================

private enum class DockPanel { NONE, SIZE, MIX, BACKGROUND }

// Bilah edit kit (melayang di atas/bawah area pad, hanya saat mode edit).
@Composable
fun KitEditDock(
    kit: Kit,
    selected: KitPad?,
    onDone: () -> Unit,
    onAddPhotoPads: () -> Unit,
    onChangeImage: () -> Unit,
    onSetSound: () -> Unit,
    onPickBackground: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var panel by remember { mutableStateOf(DockPanel.NONE) }
    // Tombol hapus 2 langkah: ketuk pertama "Yakin?", ketuk kedua benar-benar menghapus.
    var armDelete by remember(selected?.id) { mutableStateOf(false) }
    LaunchedEffect(armDelete) {
        if (armDelete) {
            delay(2500)
            armDelete = false
        }
    }
    // Panel khusus pad (ukuran / mixer) otomatis tertutup kalau tidak ada pad terpilih.
    val shown = if ((panel == DockPanel.SIZE || panel == DockPanel.MIX) && selected == null) DockPanel.NONE else panel
    val accent = ThemeState.accentColor

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(CtrlPanelColor.copy(alpha = 0.95f))
            // Cegah ketukan di bilah ini "tembus" ke pad di belakangnya.
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        when (shown) {
            DockPanel.SIZE -> if (selected != null) {
                PanelHeader("Ukuran pad") { panel = DockPanel.NONE }
                PanelSlider(
                    label = "Ukuran",
                    value = selected.h,
                    range = 0.04f..1f,
                    valueText = "${(selected.h * 100).roundToInt()}%",
                    // Skala lebar & tinggi bersama (proporsi pad tetap), nilai = tinggi pad dari tinggi area.
                    onChange = { v ->
                        KitState.updatePad(context, selected.id) { p ->
                            // Skala seragam dengan batas bersama (lihat KitPad.scaledUniform) -> tidak gepeng.
                            p.scaledUniform(v / p.h.coerceAtLeast(0.001f)).clampedInside()
                        }
                    }
                )
            }
            DockPanel.MIX -> if (selected != null) {
                PanelHeader("Suara pad") { panel = DockPanel.NONE }
                PanelSlider(
                    label = "Volume",
                    value = selected.volume,
                    range = 0f..1.5f,
                    valueText = "${(selected.volume * 100).roundToInt()}%",
                    onChange = { v -> KitState.setPadVolume(context, selected.id, v) }
                )
                PanelSlider(
                    label = "Nada",
                    value = selected.tune,
                    range = -12f..12f,
                    valueText = (if (selected.tune > 0.05f) "+" else "") + "${(selected.tune * 10).roundToInt() / 10f}",
                    onChange = { v -> KitState.setPadTune(context, selected.id, (v * 2).roundToInt() / 2f) }
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                ) {
                    Text(
                        "Potong suara lama saat pad diketuk lagi",
                        color = PadTextColor,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = selected.choke,
                        onCheckedChange = { KitState.setPadChoke(context, selected.id, it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = accent,
                            checkedTrackColor = accent.copy(alpha = 0.35f)
                        )
                    )
                }
            }
            DockPanel.BACKGROUND -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("Latar kit", color = PadTextColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    PanelChip(Icons.Filled.Image, "Pilih Gambar", onPickBackground)
                    if (kit.background != null) {
                        PanelChip(Icons.Filled.Delete, "Hapus") { KitState.clearBackground(context) }
                    }
                    PanelCloseButton { panel = DockPanel.NONE }
                }
                PanelSlider(
                    label = "Latar",
                    value = kit.bgOpacity,
                    range = 0f..1f,
                    valueText = "${(kit.bgOpacity * 100).roundToInt()}%",
                    onChange = { v -> KitState.setBgOpacity(context, v) }
                )
                PanelSlider(
                    label = "Pad",
                    value = kit.padOpacity,
                    range = 0f..1f,
                    valueText = "${(kit.padOpacity * 100).roundToInt()}%",
                    onChange = { v -> KitState.setPadOpacity(context, v) }
                )
            }
            DockPanel.NONE -> {
                Text(
                    text = if (selected == null) "Ketuk pad untuk memilih"
                    else "Tarik pad untuk menggeser · tarik bulatan di sudutnya untuk ubah ukuran",
                    color = TextDim,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            DockButton(Icons.Filled.Check, "Selesai", onDone, highlight = true)
            DockDivider()
            DockButton(
                Icons.Filled.AddPhotoAlternate, "Pad Foto",
                {
                    panel = DockPanel.NONE   // panel Latar/Ukuran/Mixer ditutup supaya tidak membingungkan
                    onAddPhotoPads()
                }
            )
            DockButton(
                Icons.Filled.Wallpaper, "Latar",
                { panel = if (panel == DockPanel.BACKGROUND) DockPanel.NONE else DockPanel.BACKGROUND },
                active = shown == DockPanel.BACKGROUND
            )
            DockDivider()
            DockButton(Icons.Filled.Image, "Gambar", onChangeImage, enabled = selected != null)
            DockButton(Icons.Filled.MusicNote, "Suara", onSetSound, enabled = selected != null)
            DockButton(
                Icons.Filled.PhotoSizeSelectLarge, "Ukuran",
                { panel = if (panel == DockPanel.SIZE) DockPanel.NONE else DockPanel.SIZE },
                enabled = selected != null, active = shown == DockPanel.SIZE
            )
            DockButton(
                Icons.Filled.Tune, "Mixer",
                { panel = if (panel == DockPanel.MIX) DockPanel.NONE else DockPanel.MIX },
                enabled = selected != null, active = shown == DockPanel.MIX
            )
            DockButton(
                Icons.Filled.Repeat, "Loop",
                { if (selected != null) KitState.setPadLoop(context, selected.id, !selected.loop) },
                enabled = selected != null, active = selected?.loop == true
            )
            DockButton(Icons.Filled.ContentCopy, "Salin", onDuplicate, enabled = selected != null)
            DockButton(
                Icons.Filled.Delete,
                if (armDelete) "Yakin?" else "Hapus",
                {
                    if (armDelete) {
                        armDelete = false
                        onDelete()
                    } else {
                        armDelete = true
                    }
                },
                enabled = selected != null,
                danger = armDelete
            )
        }
    }
}

@Composable
private fun DockDivider() {
    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .width(1.dp)
            .height(30.dp)
            .background(Color.White.copy(alpha = 0.12f))
    )
}

@Composable
private fun DockButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    active: Boolean = false,
    highlight: Boolean = false,
    danger: Boolean = false
) {
    val color = when {
        !enabled -> TextDim.copy(alpha = 0.45f)
        danger -> Color(0xFFE5534B)
        active || highlight -> ThemeState.accentColor
        else -> PadTextColor
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(54.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(if (active) Modifier.background(Color.White.copy(alpha = 0.08f)) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 5.dp)
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = color, modifier = Modifier.size(22.dp))
        Text(label, color = color, fontSize = 9.sp, maxLines = 1, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PanelChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(PanelColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = ThemeState.accentColor, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = PadTextColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

// Tombol X untuk menutup panel di bilah edit (Latar / Ukuran / Mixer) - langsung kembali ke bilah utama
// supaya bisa lanjut menambah pad.
@Composable
private fun PanelCloseButton(onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(PanelColor)
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Filled.Close, contentDescription = "Tutup", tint = PadTextColor, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun PanelHeader(title: String, onClose: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(title, color = PadTextColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        PanelCloseButton(onClose)
    }
}

@Composable
private fun PanelSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onChange: (Float) -> Unit
) {
    val accent = ThemeState.accentColor
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .heightIn(min = 36.dp)
    ) {
        Text(label, color = PadTextColor, fontSize = 11.sp, modifier = Modifier.width(54.dp))
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = PanelColor
            ),
            modifier = Modifier.weight(1f)
        )
        Text(
            valueText,
            color = TextDim,
            fontSize = 11.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.width(48.dp)
        )
    }
}

// ===================================================================================
// Dialog utama Kit: nyalakan/matikan mode kit, daftar kit, kit baru, impor, online, export.
// ===================================================================================
@Composable
fun KitHubDialog(
    kits: List<KitInfo>,
    // Preset yang sedang aktif + peta preset -> kit (mode kit berlaku per preset).
    preset: Int,
    presetKits: Map<Int, String>,
    // Kit yang dipakai preset aktif ini (null = preset ini pakai pad biasa).
    activeId: String?,
    modeOn: Boolean,
    busy: Boolean,
    onToggleMode: (Boolean) -> Unit,
    onSelectKit: (String) -> Unit,
    onEditLayout: () -> Unit,
    onRename: () -> Unit,
    onNewKit: () -> Unit,
    onImport: () -> Unit,
    onOnline: () -> Unit,
    onExport: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val accent = ThemeState.accentColor
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelColor,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Widgets, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("KIT", color = PadTextColor, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                // Kotak persegi bergaya tombol preset di panel kiri (sudut 4dp, warna aksen, teks tebal).
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(accent, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "P$preset",
                        color = ThemeState.accentTextColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
                Spacer(Modifier.weight(1f))
                if (busy) {
                    CircularProgressIndicator(color = accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .width(320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CtrlPanelColor, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Pakai Kit di P$preset", color = PadTextColor, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(
                            when {
                                kits.isEmpty() -> "Belum ada kit"
                                modeOn -> "P$preset menampilkan kit. Preset lain tidak terpengaruh."
                                else -> "P$preset memakai pad biasa. Nyalakan untuk pasang kit di sini."
                            },
                            color = TextDim,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = modeOn,
                        onCheckedChange = onToggleMode,
                        enabled = kits.isNotEmpty() && !busy,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = accent,
                            checkedTrackColor = accent.copy(alpha = 0.35f)
                        )
                    )
                }

                if (activeId != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HubTile(Icons.Filled.Edit, "Edit Layout", Modifier.weight(1f), onEditLayout)
                        HubTile(Icons.Filled.DriveFileRenameOutline, "Ganti Nama", Modifier.weight(1f), onRename)
                    }
                }

                Text("KIT SAYA", color = TextDim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                if (kits.isEmpty()) {
                    Text(
                        "Belum ada kit. Buat kit baru, impor file .$KIT_EXTENSION, atau ambil dari online.",
                        color = TextDim,
                        fontSize = 11.sp
                    )
                } else {
                    kits.forEach { info ->
                        KitRow(
                            info = info,
                            active = info.id == activeId,
                            usedBy = presetKits.filterValues { it == info.id }.keys.sorted(),
                            onClick = { onSelectKit(info.id) },
                            onExport = { onExport(info.id) },
                            onDelete = { onDelete(info.id) }
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HubTile(Icons.Filled.Add, "Kit Baru", Modifier.weight(1f), onNewKit)
                    HubTile(Icons.Filled.FileDownload, "Impor", Modifier.weight(1f), onImport)
                    HubTile(Icons.Filled.CloudDownload, "Online", Modifier.weight(1f), onOnline)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = CtrlPanelColor, contentColor = PadTextColor)
            ) {
                Text("TUTUP", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            }
        }
    )
}

@Composable
private fun HubTile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(CtrlPanelColor)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = ThemeState.accentColor, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, color = PadTextColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun KitRow(
    info: KitInfo,
    active: Boolean,
    usedBy: List<Int>,
    onClick: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(CtrlPanelColor)
            .clickable(onClick = onClick)
            .padding(start = 10.dp, top = 4.dp, bottom = 4.dp, end = 2.dp)
    ) {
        Icon(
            imageVector = if (active) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = if (active) "Kit aktif" else "Pilih kit",
            tint = if (active) ThemeState.accentColor else TextDim,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(info.name, color = PadTextColor, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1)
            Text(
                "${info.padCount} pad" + if (usedBy.isNotEmpty()) "  ·  dipakai di " + usedBy.joinToString(" ") { "P$it" } else "",
                color = TextDim,
                fontSize = 10.sp,
                maxLines = 1
            )
        }
        IconButton(onClick = onExport, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Filled.FileUpload, contentDescription = "Export kit", tint = PadTextColor, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Filled.Delete, contentDescription = "Hapus kit", tint = TextDim, modifier = Modifier.size(18.dp))
        }
    }
}

// Dialog isian nama (kit baru / ganti nama).
@Composable
fun KitNameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelColor,
        title = { Text(title, color = PadTextColor, fontWeight = FontWeight.Bold) },
        text = {
            BasicTextField(
                value = text,
                onValueChange = { if (it.length <= 40) text = it },
                singleLine = true,
                textStyle = TextStyle(color = PadTextColor, fontSize = 14.sp),
                cursorBrush = SolidColor(ThemeState.accentColor),
                modifier = Modifier
                    .width(280.dp)
                    .background(CtrlPanelColor, RoundedCornerShape(8.dp))
                    .padding(12.dp)
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = ThemeState.accentColor,
                    contentColor = ThemeState.accentTextColor
                )
            ) {
                Text(confirmLabel, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = CtrlPanelColor, contentColor = PadTextColor)
            ) {
                Text("BATAL", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            }
        }
    )
}

// Gambar apa yang sedang dipilih (menentukan ke mana hasil pilih gambar dikirim).
enum class ImageTarget { KIT_ADD_PADS, KIT_PAD_IMAGE, KIT_BACKGROUND, NORMAL_BACKGROUND, TOUCH_FX }

// Sumber gambar yang dipilih pengguna di dialog "Pilih gambar dari".
enum class ImageSource { GALLERY, FILE }

// Pemilih gambar bawaan sistem. GALLERY = chooser aplikasi galeri/foto yang terpasang di HP (selalu
// tampil daftar pilihan, tidak otomatis loncat ke satu aplikasi). FILE = pemilih dokumen / file manager.
// Hasil: daftar Uri (kosong = dibatalkan).
class SystemImagePickerContract(private val multiple: Boolean) :
    androidx.activity.result.contract.ActivityResultContract<ImageSource, List<android.net.Uri>>() {

    override fun createIntent(context: android.content.Context, input: ImageSource): android.content.Intent {
        return when (input) {
            ImageSource.GALLERY -> {
                val pick = android.content.Intent(
                    android.content.Intent.ACTION_PICK,
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                ).apply {
                    type = "image/*"
                    putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, multiple)
                }
                android.content.Intent.createChooser(pick, "Pilih dari galeri")
            }
            ImageSource.FILE -> android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/*"
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, multiple)
            }
        }
    }

    override fun parseResult(resultCode: Int, intent: android.content.Intent?): List<android.net.Uri> {
        if (resultCode != android.app.Activity.RESULT_OK || intent == null) return emptyList()
        val out = ArrayList<android.net.Uri>()
        val clip = intent.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { out.add(it) }
        }
        if (out.isEmpty()) intent.data?.let { out.add(it) }
        return if (multiple) out else out.take(1)
    }
}

// Dialog pilihan sumber gambar: GALERI atau FILE. Dipakai semua tempat ganti gambar.
@Composable
fun ImageSourceDialog(
    multiple: Boolean,
    onGallery: () -> Unit,
    onFiles: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelColor,
        title = { Text("Pilih gambar dari", color = PadTextColor, fontWeight = FontWeight.Bold) },
        text = {
            Text(
                if (multiple) "Galeri: pilih beberapa foto sekaligus. File: buka lewat file manager."
                else "Galeri: pilih dari foto di HP. File: buka lewat file manager.",
                color = TextDim,
                fontSize = 12.sp
            )
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onFiles,
                    colors = ButtonDefaults.buttonColors(containerColor = CtrlPanelColor, contentColor = PadTextColor)
                ) {
                    Text("FILE", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
                Button(
                    onClick = onGallery,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ThemeState.accentColor,
                        contentColor = ThemeState.accentTextColor
                    )
                ) {
                    Text("GALERI", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = CtrlPanelColor, contentColor = PadTextColor)
            ) {
                Text("BATAL", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            }
        }
    )
}
