package com.kendang.realpads

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Efek REVERB untuk suara pad (v1.12.0). Switch ON/OFF (DEFAULT OFF) + 4 knob: LEVEL, RUANG, REDAM, LEBAR.
 *
 * DSP-nya di native (cpp/PadReverb.*, disalin dari efek mik MIXDTX) dan dipasang sebagai send paralel di
 * PadEngine::render SEBELUM musik/lagu dicampur: hanya suara pad yang ber-reverb, musik tidak. Suara pad asli
 * tidak disentuh/ditunda, reverb hanya menambah ekor. File ini cuma
 * menyimpan state, meneruskannya ke native, dan menggambar bagian UI di dialog Pengaturan.
 */
object PadReverb {
    init { System.loadLibrary("padengine") }   // idempoten; AudioEngine juga memuat library yang sama

    external fun nativeSetEnabled(on: Boolean)
    external fun nativeSetLevel(v: Float)
    external fun nativeSetRoom(v: Float)
    external fun nativeSetDamp(v: Float)
    external fun nativeSetWidth(v: Float)

    const val DEF_LEVEL = 0.35f
    const val DEF_ROOM = 0.55f
    const val DEF_DAMP = 0.35f
    const val DEF_WIDTH = 1.0f

    // State untuk Compose (UI ikut berubah) - sumber kebenarannya tetap native.
    var enabled by mutableStateOf(false)   // DEFAULT OFF
        private set
    var level by mutableStateOf(DEF_LEVEL)
        private set
    var room by mutableStateOf(DEF_ROOM)
        private set
    var damp by mutableStateOf(DEF_DAMP)
        private set
    var width by mutableStateOf(DEF_WIDTH)
        private set

    private const val PREFS = "pad_reverb"

    /** Panggil SEBELUM AudioBackendManager.init (di MainActivity.onCreate): pulihkan setelan terakhir ke native. */
    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled = p.getBoolean("enabled", false)
        level = p.getFloat("level", DEF_LEVEL).coerceIn(0f, 1f)
        room = p.getFloat("room", DEF_ROOM).coerceIn(0f, 1f)
        damp = p.getFloat("damp", DEF_DAMP).coerceIn(0f, 1f)
        width = p.getFloat("width", DEF_WIDTH).coerceIn(0f, 1f)
        nativeSetLevel(level); nativeSetRoom(room); nativeSetDamp(damp); nativeSetWidth(width)
        nativeSetEnabled(enabled)
    }

    fun setEnabled(context: Context, on: Boolean) {
        enabled = on
        nativeSetEnabled(on)
        save(context)
    }

    // Setter knob: langsung ke native (live). Penyimpanan ke disk dilakukan di save() saat jari dilepas.
    fun updateLevel(v: Float) { level = v.coerceIn(0f, 1f); nativeSetLevel(level) }
    fun updateRoom(v: Float) { room = v.coerceIn(0f, 1f); nativeSetRoom(room) }
    fun updateDamp(v: Float) { damp = v.coerceIn(0f, 1f); nativeSetDamp(damp) }
    fun updateWidth(v: Float) { width = v.coerceIn(0f, 1f); nativeSetWidth(width) }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", enabled)
            .putFloat("level", level).putFloat("room", room)
            .putFloat("damp", damp).putFloat("width", width)
            .apply()
    }
}

// Bagian "Reverb" di dialog Pengaturan: baris switch (default OFF); knob baru muncul kalau switch ON.
@Composable
fun PadReverbSection() {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Reverb", color = PadTextColor, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text("Gema ruang untuk suara pad, tanpa menunda suara asli", color = TextDim, fontSize = 11.sp)
            }
            Switch(
                checked = PadReverb.enabled,
                onCheckedChange = { PadReverb.setEnabled(context, it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = ThemeState.accentColor,
                    checkedTrackColor = ThemeState.accentColor.copy(alpha = 0.5f)
                )
            )
        }
        if (PadReverb.enabled) {
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ReverbKnob("LEVEL", PadReverb.level, PadReverb.DEF_LEVEL, PadReverb::updateLevel, Modifier.weight(1f)) { PadReverb.save(context) }
                ReverbKnob("RUANG", PadReverb.room, PadReverb.DEF_ROOM, PadReverb::updateRoom, Modifier.weight(1f)) { PadReverb.save(context) }
                ReverbKnob("REDAM", PadReverb.damp, PadReverb.DEF_DAMP, PadReverb::updateDamp, Modifier.weight(1f)) { PadReverb.save(context) }
                ReverbKnob("LEBAR", PadReverb.width, PadReverb.DEF_WIDTH, PadReverb::updateWidth, Modifier.weight(1f)) { PadReverb.save(context) }
            }
            Text(
                "Putar searah jarum jam = naik. Geser jari menjauh dari knob = lebih halus. Ketuk 2x = bawaan.",
                color = TextDim, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

// Knob putar sungguhan: badan bulat + jalur busur 270° (pukul 7:30 -> 4:30) + penunjuk.
// Kontrol = memutar: sudut jari terhadap pusat knob diikuti secara inkremental (searah jarum jam = naik),
// jadi penunjuk selalu ikut jari 1:1. Menyentuh knob langsung "memegang" gestur (consume), sehingga dialog
// Pengaturan yang bisa di-scroll tidak ikut bergeser. Area sentuh 76dp (lebih besar dari gambar knob).
@Composable
private fun ReverbKnob(
    label: String,
    value: Float,
    default: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onFinish: () -> Unit
) {
    val valueNow by rememberUpdatedState(value)
    val onChangeNow by rememberUpdatedState(onChange)
    val onFinishNow by rememberUpdatedState(onFinish)
    var dragging by remember { mutableStateOf(false) }
    val accent = ThemeState.accentColor
    val sweepRad = (270.0 * PI / 180.0).toFloat()
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            modifier = Modifier
                .size(76.dp)
                .pointerInput(default) {
                    var lastTapMs = 0L
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()                       // pegang gestur: cegah scroll dialog
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val deadZone = size.width * 0.16f    // sangat dekat pusat sudutnya tidak stabil -> abaikan
                        var prev = atan2(down.position.y - cy, down.position.x - cx)
                        var turned = 0f
                        dragging = true
                        drag(down.id) { change ->
                            val dx = change.position.x - cx
                            val dy = change.position.y - cy
                            val a = atan2(dy, dx)
                            if (hypot(dx, dy) > deadZone) {
                                var d = a - prev
                                if (d > PI.toFloat()) d -= (2 * PI).toFloat()
                                if (d < -PI.toFloat()) d += (2 * PI).toFloat()
                                turned += abs(d)
                                onChangeNow((valueNow + d / sweepRad).coerceIn(0f, 1f))
                            }
                            prev = a
                            change.consume()
                        }
                        dragging = false
                        if (turned < 0.12f) {                // hampir tidak diputar = ketukan
                            val now = System.currentTimeMillis()
                            if (now - lastTapMs < 300L) { onChangeNow(default); lastTapMs = 0L } else lastTapMs = now
                        }
                        onFinishNow()
                    }
                }
        ) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val ringStroke = 4.dp.toPx()
            val ringR = size.minDimension / 2f - 6.dp.toPx()           // garis tengah busur
            val bodyR = ringR - ringStroke / 2f - 4.dp.toPx()           // badan knob (ada celah ke busur)
            val arcTopLeft = Offset(c.x - ringR, c.y - ringR)
            val arcSize = Size(ringR * 2, ringR * 2)

            // jalur busur: lebih GELAP dari panel supaya jelas terlihat
            drawArc(
                color = Color(0xFF1B1B1D), startAngle = 135f, sweepAngle = 270f, useCenter = false,
                topLeft = arcTopLeft, size = arcSize, style = Stroke(ringStroke, cap = StrokeCap.Round)
            )
            // busur nilai
            if (value > 0.003f) {
                drawArc(
                    color = accent, startAngle = 135f, sweepAngle = 270f * value, useCenter = false,
                    topLeft = arcTopLeft, size = arcSize, style = Stroke(ringStroke, cap = StrokeCap.Round)
                )
            }
            // badan knob: bulat solid + tepi (tepi menyala saat dipegang)
            drawCircle(color = Color(0xFF2A2A2D), radius = bodyR, center = c)
            drawCircle(
                color = if (dragging) accent else Color(0xFF5B5B60), radius = bodyR, center = c,
                style = Stroke(1.5.dp.toPx())
            )
            // penunjuk di badan knob
            val ang = Math.toRadians((135f + 270f * value).toDouble())
            val dirX = cos(ang).toFloat()
            val dirY = sin(ang).toFloat()
            drawLine(
                color = accent,
                start = Offset(c.x + dirX * bodyR * 0.38f, c.y + dirY * bodyR * 0.38f),
                end = Offset(c.x + dirX * bodyR * 0.88f, c.y + dirY * bodyR * 0.88f),
                strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round
            )
        }
        Text(label, color = PadTextColor, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
        Text("${(value * 100).toInt()}%", color = if (dragging) accent else TextDim, fontSize = 11.sp)
    }
}
