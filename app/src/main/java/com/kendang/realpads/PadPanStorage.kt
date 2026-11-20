package com.kendang.realpads

import android.content.Context

private const val PAN_PREFS = "realpads_pad_pan"

// Nyimpen PAN (-1f..1f, 0f = tengah) per (preset, pad) buat fitur MIXER.
// Pola sama persis dengan PadTuneStorage: nilai ditulis ke native (langsung kedengeran) dan ke
// SharedPreferences (nempel walau apk ditutup total).
object PadPanStorage {
    private fun key(preset: Int, pad: Int) = "pan_p${preset}_pad$pad"

    fun loadAllIntoEngine(context: Context) {
        val prefs = context.getSharedPreferences(PAN_PREFS, Context.MODE_PRIVATE)
        for (preset in 1..PresetStorage.MAX_PRESET) {
            for (pad in 1..PresetStorage.MAX_PAD) {
                val k = key(preset, pad)
                if (prefs.contains(k)) {
                    AudioEngine.nativeSetPadPan(preset, pad, prefs.getFloat(k, 0f))
                }
            }
        }
    }

    fun set(context: Context, preset: Int, pad: Int, value: Float) {
        AudioEngine.nativeSetPadPan(preset, pad, value)
        context.getSharedPreferences(PAN_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(key(preset, pad), value)
            .apply()
    }

    fun get(preset: Int, pad: Int): Float = AudioEngine.nativeGetPadPan(preset, pad)

    // Hapus setelan 1 slot (dipakai saat preset dihapus). Tidak menyentuh native - pemanggil
    // yang mengembalikan native ke 0 sendiri.
    fun forget(context: Context, preset: Int, pad: Int) {
        context.getSharedPreferences(PAN_PREFS, Context.MODE_PRIVATE)
            .edit().remove(key(preset, pad)).apply()
    }
}
