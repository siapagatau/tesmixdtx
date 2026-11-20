package com.kendang.realpads.skin

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.kendang.realpads.CtrlPanelColor
import com.kendang.realpads.PadTextColor
import com.kendang.realpads.TextDim
import com.kendang.realpads.ThemeState

/**
 * Tab tipis permanen di tepi kanan saat SKIN aktif (pengganti HideToggleTab klasik). Tiga tombol bertumpuk,
 * dipasang sebagai kolom sendiri di Row -> area sentuhnya tidak pernah tumpang tindih dengan pad.
 *
 *  1. atas   : tampil/sembunyi panel ASLI APK (preset di kiri, volume & musik di kanan)
 *  2. tengah : tampil/sembunyi panel kontrol skin (LCD + tombol DTX) -> pad memenuhi seluruh area
 *  3. bawah  : tampil/sembunyi strip tombol utama APK (REC, LAYAR, SET SUARA, TEMA, SKIN, EXPORT, LOAD)
 */
@Composable
fun SkinToggleTab(
    sidePanelsHidden: Boolean,
    onToggleSidePanels: () -> Unit,
    skinPanelVisible: Boolean,
    onToggleSkinPanel: () -> Unit,
    actionBarVisible: Boolean,
    onToggleActionBar: () -> Unit,
    onEditPads: (() -> Unit)? = null,
    editPadsActive: Boolean = false,
    onKit: (() -> Unit)? = null,
    kitActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.background(CtrlPanelColor)) {
        TabSegment(
            icon = if (sidePanelsHidden) Icons.Filled.KeyboardArrowLeft else Icons.Filled.KeyboardArrowRight,
            description = if (sidePanelsHidden) "Tampilkan panel preset dan volume" else "Sembunyikan panel preset dan volume",
            tint = PadTextColor,
            onTap = onToggleSidePanels,
            modifier = Modifier.weight(1f)
        )
        Divider()
        TabSegment(
            icon = if (skinPanelVisible) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
            description = if (skinPanelVisible) "Sembunyikan panel kontrol skin" else "Tampilkan panel kontrol skin",
            tint = if (skinPanelVisible) PadTextColor else TextDim,
            onTap = onToggleSkinPanel,
            modifier = Modifier.weight(1f)
        )
        Divider()
        TabSegment(
            icon = Icons.Filled.Menu,
            description = if (actionBarVisible) "Sembunyikan tombol utama bawah" else "Tampilkan tombol utama bawah",
            tint = if (actionBarVisible) PadTextColor else TextDim,
            onTap = onToggleActionBar,
            modifier = Modifier.weight(1f)
        )
        EditPadsSegment(onEditPads, editPadsActive)
        KitSegment(onKit, kitActive)
    }
}

/**
 * Tab tipis di tepi kanan untuk UI KLASIK (tema default bawaan, tanpa skin). Dua tombol bertumpuk:
 *  1. atas  : tampil/sembunyi panel preset (kiri) + panel volume & musik (kanan)
 *  2. bawah : tampil/sembunyi strip tombol utama bawah (REC, LAYAR, SET SUARA, TEMA, SKIN, EXPORT, LOAD)
 * Dipasang sebagai kolom sendiri di Row -> area sentuhnya tidak pernah tumpang tindih dengan pad.
 */
@Composable
fun ClassicToggleTab(
    sidePanelsHidden: Boolean,
    onToggleSidePanels: () -> Unit,
    actionBarVisible: Boolean,
    onToggleActionBar: () -> Unit,
    onEditPads: (() -> Unit)? = null,
    editPadsActive: Boolean = false,
    onKit: (() -> Unit)? = null,
    kitActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.background(CtrlPanelColor)) {
        TabSegment(
            icon = if (sidePanelsHidden) Icons.Filled.KeyboardArrowLeft else Icons.Filled.KeyboardArrowRight,
            description = if (sidePanelsHidden) "Tampilkan panel preset dan volume" else "Sembunyikan panel preset dan volume",
            tint = PadTextColor,
            onTap = onToggleSidePanels,
            modifier = Modifier.weight(1f)
        )
        Divider()
        TabSegment(
            icon = if (actionBarVisible) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
            description = if (actionBarVisible) "Sembunyikan tombol utama bawah" else "Tampilkan tombol utama bawah",
            tint = if (actionBarVisible) PadTextColor else TextDim,
            onTap = onToggleActionBar,
            modifier = Modifier.weight(1f)
        )
        EditPadsSegment(onEditPads, editPadsActive)
        KitSegment(onKit, kitActive)
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color.Black.copy(alpha = 0.35f)))
}

@Composable
private fun TabSegment(icon: ImageVector, description: String, tint: Color, onTap: () -> Unit, modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(onTap) { detectTapGestures(onTap = { onTap() }) },
        contentAlignment = Alignment.Center
    ) {
        Icon(imageVector = icon, contentDescription = description, tint = tint, modifier = Modifier.size(16.dp))
    }
}

/**
 * Tombol MODE EDIT pindah suara pad: tepat di ATAS tombol Kit. Ikon pensil; saat mode edit nyala,
 * ikon berwarna aksen di atas latar aksen tipis. Tekan lagi untuk keluar dari mode edit.
 * Tidak tampil kalau onEditPads null (mis. saat mode kit aktif - kit punya mode edit sendiri).
 */
@Composable
private fun EditPadsSegment(onEditPads: (() -> Unit)?, active: Boolean) {
    if (onEditPads == null) return
    Divider()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(if (active) ThemeState.accentColor.copy(alpha = 0.22f) else Color.Transparent)
            .pointerInput(onEditPads) { detectTapGestures(onTap = { onEditPads() }) },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Edit,
            contentDescription = if (active) "Keluar mode edit pad" else "Mode edit: pindah suara pad",
            tint = if (active) ThemeState.accentColor else TextDim,
            modifier = Modifier.size(16.dp)
        )
    }
}

/**
 * Pintu masuk fitur KIT: satu ikon di dasar tab (tinggi tetap, tidak menggeser dua tombol asli di atasnya
 * lebih dari seperlunya). Menyala warna aksen saat mode kit aktif. Tidak tampil kalau onKit null.
 */
@Composable
private fun KitSegment(onKit: (() -> Unit)?, kitActive: Boolean) {
    if (onKit == null) return
    Divider()
    TabSegment(
        icon = Icons.Filled.Widgets,
        description = "Kit",
        tint = if (kitActive) ThemeState.accentColor else TextDim,
        onTap = onKit,
        modifier = Modifier.height(44.dp)
    )
}
