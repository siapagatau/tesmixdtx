package com.kendang.realpads

// ===================================================================================
// FITUR KIT (v1.10.0) - model data.
//
// Kit = "preset siap pakai" yang membawa SEMUANYA: daftar pad bebas (jumlah bebas),
// gambar tiap pad (PNG transparan / foto), posisi & ukuran, gambar latar, transparansi,
// suara tiap pad, volume/tune/loop/choke. Dibungkus jadi 1 file zip berekstensi
// .kitdtxkdg (lihat KitStorage.exportKit / importKit) supaya bisa dibagikan & diunduh.
//
// Koordinat pad memakai PECAHAN dari "panggung" (stage) kit, bukan piksel. Panggung = SELURUH
// area pad dan di-STRETCH memenuhi area itu (sama seperti skin / pad normal, skala X dan Y boleh
// beda), jadi tiap pad punya posisinya sendiri dan tidak bergeser di ukuran layar apa pun:
//   cx, cy = titik tengah pad (0..1 dari lebar / tinggi panggung)
//   w, h   = lebar / tinggi pad (pecahan dari lebar / tinggi panggung)
// Tidak ada area kosong (letterbox) dan tidak ada perhitungan "contain" lagi.
// (Kit format v1 lama memakai size + aspect; dimigrasi otomatis saat dimuat, lihat KitState.migrate.)
// ===================================================================================

// Ekstensi file kit (tanpa titik). Isinya zip biasa.
const val KIT_EXTENSION = "kitdtxkdg"

// Jumlah pad maksimum dalam 1 kit. Tiap 16 pad memakai 1 "slot preset" native
// (PadEngine.kMaxPresets = 50 preset biasa + KIT_SLOT_GROUPS slot khusus kit).
const val KIT_PADS_PER_SLOT = 16
const val KIT_SLOT_GROUPS = 8
const val KIT_MAX_PADS = KIT_PADS_PER_SLOT * KIT_SLOT_GROUPS   // 128
const val KIT_SLOT_PRESET_BASE = 51                            // slot native pertama milik kit

// Id pad kit (1..KIT_MAX_PADS) -> alamat (preset, pad) di engine native.
fun kitSlotPreset(padId: Int): Int = KIT_SLOT_PRESET_BASE + (padId - 1) / KIT_PADS_PER_SLOT
fun kitSlotPad(padId: Int): Int = (padId - 1) % KIT_PADS_PER_SLOT + 1

const val KIT_FORMAT_NAME = "dtx-kit"
const val KIT_FORMAT_VERSION = 2

data class KitPad(
    val id: Int,
    val cx: Float,
    val cy: Float,
    // Lebar & tinggi pad, pecahan dari panggung. w <= 0 = kit format lama (belum dimigrasi).
    val w: Float,
    val h: Float,
    // Nama file di folder images/ (null = pad polos bawaan, digambar aplikasi).
    val image: String? = null,
    // Nama file di folder sounds/ (selalu .wav kanonik 16-bit; null = belum ada suara).
    val sound: String? = null,
    val volume: Float = 1f,       // 0..1.5
    val tune: Float = 0f,         // -12..12 semitone
    val fine: Float = 0f,         // -100..100 cent (fine tune, ditambahkan ke tune)
    val pan: Float = 0f,          // -1 (kiri) .. 0 (tengah) .. 1 (kanan)
    val loop: Boolean = false,
    val choke: Boolean = true,
    val bass: Boolean = false     // nada rendah -> memicu efek getar layar (GLER)
)

// Batas ukuran pad (pecahan panggung per sumbu). Sisi terkecil pad tidak boleh lebih kecil dari MIN,
// sisi terbesar tidak lebih dari MAX - dihitung BERSAMA supaya proporsi pad tidak pernah rusak.
const val KIT_PAD_MIN_FRAC = 0.04f
const val KIT_PAD_MAX_FRAC = 1f

// Skala pad secara SERAGAM: lebar dan tinggi dikali faktor yang SAMA. Faktor dibatasi sekali untuk
// kedua sumbu (bukan batas sendiri-sendiri per sumbu), jadi saat pad dikecilkan sampai mentok lalu
// dibesarkan lagi, bentuknya tetap sama - tidak pernah jadi gepeng. Pad yang sudah di luar batas
// (mis. kit lama) tidak dipaksa loncat: faktor 1 selalu boleh.
fun KitPad.scaledUniform(f: Float): KitPad {
    val w0 = w.coerceAtLeast(0.0001f)
    val h0 = h.coerceAtLeast(0.0001f)
    val lo = minOf(1f, maxOf(KIT_PAD_MIN_FRAC / w0, KIT_PAD_MIN_FRAC / h0))
    val hi = maxOf(1f, minOf(KIT_PAD_MAX_FRAC / w0, KIT_PAD_MAX_FRAC / h0))
    val k = f.coerceIn(lo, hi)
    return copy(w = w * k, h = h * k)
}

// Pastikan SELURUH pad ada di dalam panggung (tidak ada bagian yang keluar area). Kalau pad lebih besar
// dari panggung, diperkecil SERAGAM (proporsi tetap), lalu posisinya digeser masuk.
fun KitPad.clampedInside(): KitPad {
    val f = minOf(1f, 1f / w.coerceAtLeast(0.0001f), 1f / h.coerceAtLeast(0.0001f))
    val nw = w * f
    val nh = h * f
    return copy(
        w = nw,
        h = nh,
        cx = cx.coerceIn(nw / 2f, 1f - nw / 2f),
        cy = cy.coerceIn(nh / 2f, 1f - nh / 2f)
    )
}

data class Kit(
    val id: String,
    val name: String,
    val author: String = "",
    // Hanya info + bahan migrasi kit format lama (v1): rasio area pad saat kit dibuat.
    // Tampilan TIDAK memakainya; panggung selalu memenuhi area pad (stretch).
    val aspect: Float = 2f,
    // Nama file latar di folder images/ (null = tanpa latar).
    val background: String? = null,
    // Transparansi gambar latar & pad - sama seperti pengaturan latar di mode normal.
    val bgOpacity: Float = 1f,
    val padOpacity: Float = 1f,
    // Choke kit (v1.10.6): true = memukul pad mana pun menghentikan semua pad kit lain.
    val chokeAll: Boolean = false,
    val pads: List<KitPad> = emptyList()
)

// Peta nomor lama -> nomor baru supaya nomor pad urut rapi 1..N tanpa lompatan (urutan relatif nomor
// dijaga: nomor terkecil jadi 1, berikutnya 2, dst). Hanya berisi pad yang nomornya BERUBAH; kosong
// berarti kit sudah rapi. Urutan gambar pad (susunan tumpukan di layar) tidak diubah sama sekali.
fun Kit.compactMapping(): Map<Int, Int> {
    val m = LinkedHashMap<Int, Int>()
    pads.map { it.id }.sorted().forEachIndexed { i, old -> if (old != i + 1) m[old] = i + 1 }
    return m
}

// Ringkasan 1 kit untuk daftar di dialog Kit (tanpa memuat gambar/suara).
data class KitInfo(val id: String, val name: String, val padCount: Int)
