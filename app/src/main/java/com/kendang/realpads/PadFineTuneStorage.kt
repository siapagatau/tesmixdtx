package com.kendang.realpads

import android.content.Context

private const val FINE_PREFS = "realpads_pad_fine_tune"

// Nyimpen FINE TUNE (cent, -100f..100f, 0f = tanpa koreksi) per (preset, pad) buat fitur MIXER.
// Pola sama persis dengan PadTuneStorage: nilai ditulis ke native (langsung kedengeran) dan ke
// SharedPreferences (nempel walau apk ditutup total).
object PadFineTuneStorage {
    private fun key(preset: Int, pad: Int) = "fine_p${preset}_pad$pad"

    fun loadAllIntoEngine(context: Context) {
        val prefs = context.getSharedPreferences(FINE_PREFS, Context.MODE_PRIVATE)
        for (preset in 1..PresetStorage.MAX_PRESET) {
            for (pad in 1..PresetStorage.MAX_PAD) {
                val k = key(preset, pad)
                if (prefs.contains(k)) {
                    AudioEngine.nativeSetPadFineTune(preset, pad, prefs.getFloat(k, 0f))
                }
            }
        }
    }

    fun set(context: Context, preset: Int, pad: Int, value: Float) {
        AudioEngine.nativeSetPadFineTune(preset, pad, value)
        context.getSharedPreferences(FINE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(key(preset, pad), value)
            .apply()
    }

    fun get(preset: Int, pad: Int): Float = AudioEngine.nativeGetPadFineTune(preset, pad)

    // Hapus setelan 1 slot (dipakai saat preset dihapus). Tidak menyentuh native - pemanggil
    // yang mengembalikan native ke 0 sendiri.
    fun forget(context: Context, preset: Int, pad: Int) {
        context.getSharedPreferences(FINE_PREFS, Context.MODE_PRIVATE)
            .edit().remove(key(preset, pad)).apply()
    }
}
