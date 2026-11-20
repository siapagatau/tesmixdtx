package com.kendang.realpads

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// MODE EDIT PINDAH SUARA PAD (v1.11.0).
//
// Mode ini berlaku di tampilan pad KLASIK dan SKIN (bukan mode kit - kit punya mode edit sendiri).
// Selama mode edit nyala, semua pad ditutup overlay menyala (PadMoveOverlay.kt) dan sentuhan TIDAK
// membunyikan pad: jari dipakai untuk mengangkat sebuah pad lalu menjatuhkannya ke pad lain.
// Yang berpindah adalah SUARA (+ setelan suaranya), bukan posisi kotak padnya.
object PadMoveState {
    // Tidak disimpan permanen: setiap app dibuka, mode edit selalu mati.
    var editing by mutableStateOf(false)
        private set

    fun toggle() { editing = !editing }
    fun exit() { editing = false }
}

class PadMoveResult(val bassA: Boolean, val bassB: Boolean, val tempoA: Float, val tempoB: Float)

object PadSoundMover {
    // Tukar suara pad [a] dan [b] di [preset]. Ikut berpindah bersama suaranya: file sample, flag bass (GLER),
    // volume, tune, choke, loop, tempo loop, dan cross-choke (aktif/tidaknya DAN daftar pad yang memotongnya;
    // nomor pad di dalam daftar itu ikut ditukar supaya hubungan "suara X memotong suara Y" tetap utuh).
    // Binding keyboard/MIDI sengaja TIDAK ikut: itu milik tombol fisiknya, bukan milik suaranya.
    // Return null kalau parameter tidak valid.
    suspend fun swap(context: Context, preset: Int, a: Int, b: Int): PadMoveResult? {
        val max = PresetStorage.MAX_PAD
        if (a == b || a !in 1..max || b !in 1..max) return null

        // Pad yang sedang loop dihentikan dulu (ketukan pada pad loop = stop loop, dengan fade-out),
        // supaya suara lama tidak tertinggal berputar di pad yang isinya sudah diganti.
        var stopped = false
        for (p in intArrayOf(a, b)) {
            if (AudioEngine.nativeIsPadLooping(preset, p)) {
                AudioEngine.nativeTrigger(preset, p)
                stopped = true
            }
        }
        if (stopped) delay(180)

        val app = context.applicationContext
        return withContext(Dispatchers.IO) {
            val bass = PresetStorage.swapPads(app, preset, a, b)
            swapSettings(app, preset, a, b)
            PadMoveResult(
                bassA = bass.first,
                bassB = bass.second,
                tempoA = PadLoopTempoStorage.get(preset, a),
                tempoB = PadLoopTempoStorage.get(preset, b)
            )
        }
    }

    private fun swapSettings(context: Context, preset: Int, a: Int, b: Int) {
        val volA = PadVolumeStorage.get(preset, a)
        val volB = PadVolumeStorage.get(preset, b)
        if (volA != volB) {
            PadVolumeStorage.set(context, preset, a, volB)
            PadVolumeStorage.set(context, preset, b, volA)
        }

        val tuneA = PadTuneStorage.get(preset, a)
        val tuneB = PadTuneStorage.get(preset, b)
        if (tuneA != tuneB) {
            PadTuneStorage.set(context, preset, a, tuneB)
            PadTuneStorage.set(context, preset, b, tuneA)
        }

        val panA = PadPanStorage.get(preset, a)
        val panB = PadPanStorage.get(preset, b)
        if (panA != panB) {
            PadPanStorage.set(context, preset, a, panB)
            PadPanStorage.set(context, preset, b, panA)
        }

        val fineA = PadFineTuneStorage.get(preset, a)
        val fineB = PadFineTuneStorage.get(preset, b)
        if (fineA != fineB) {
            PadFineTuneStorage.set(context, preset, a, fineB)
            PadFineTuneStorage.set(context, preset, b, fineA)
        }

        val chokeA = PadChokeStorage.get(preset, a)
        val chokeB = PadChokeStorage.get(preset, b)
        if (chokeA != chokeB) {
            PadChokeStorage.set(context, preset, a, chokeB)
            PadChokeStorage.set(context, preset, b, chokeA)
        }

        val loopA = PadLoopStorage.get(preset, a)
        val loopB = PadLoopStorage.get(preset, b)
        if (loopA != loopB) {
            PadLoopStorage.set(context, preset, a, loopB)
            PadLoopStorage.set(context, preset, b, loopA)
        }

        val tempoA = PadLoopTempoStorage.get(preset, a)
        val tempoB = PadLoopTempoStorage.get(preset, b)
        if (tempoA != tempoB) {
            PadLoopTempoStorage.set(context, preset, a, tempoB)
            PadLoopTempoStorage.set(context, preset, b, tempoA)
        }

        swapCrossChoke(context, preset, a, b)
    }

    // Pad p menyimpan (1) apakah cross-choke aktif dan (2) mask: bit (n-1) = pad n yang memotong suaranya.
    // Setelah suara a<->b ditukar, entri tiap pad pindah ke pad pasangannya (a<->b), dan bit a/b di dalam
    // SEMUA mask ikut ditukar.
    private fun swapCrossChoke(context: Context, preset: Int, a: Int, b: Int) {
        val max = PresetStorage.MAX_PAD
        val oldEnabled = BooleanArray(max + 1)
        val oldMask = IntArray(max + 1)
        for (p in 1..max) {
            oldEnabled[p] = PadCrossChokeStorage.getEnabled(preset, p)
            oldMask[p] = PadCrossChokeStorage.getMask(preset, p)
        }
        for (p in 1..max) {
            val q = if (p == a) b else if (p == b) a else p
            val newMask = remapBits(oldMask[p], a, b)
            val newEnabled = oldEnabled[p]
            if (oldMask[q] != newMask) PadCrossChokeStorage.setMask(context, preset, q, newMask)
            if (oldEnabled[q] != newEnabled) PadCrossChokeStorage.setEnabled(context, preset, q, newEnabled)
        }
    }

    private fun remapBits(mask: Int, a: Int, b: Int): Int {
        val bitA = 1 shl (a - 1)
        val bitB = 1 shl (b - 1)
        val hasA = mask and bitA != 0
        val hasB = mask and bitB != 0
        var r = mask and bitA.inv() and bitB.inv()
        if (hasA) r = r or bitB
        if (hasB) r = r or bitA
        return r
    }
}
