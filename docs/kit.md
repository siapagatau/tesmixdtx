# Fitur KIT (v1.10.2)

Kit = preset siap pakai yang membawa pad bebas (gambar PNG transparan), posisi & ukuran, latar, transparansi, dan suara.
Dibagikan sebagai file zip berekstensi `.kitdtxkdg`. Repo kit gratis: folder `DTX-KIT-STORE` (dipublikasikan sebagai
`github.com/siapagatau/DTX-KIT-STORE`, folder `kits/`; konstanta di `KitOnline.kt`).

## Pintu masuk (UI asli tidak diubah)
Satu ikon **Widgets** di dasar tab tipis kanan (klasik: `ClassicToggleTab`, skin: `SkinToggleTab`). Tidak ada tombol
baru di strip bawah, panel preset, atau panel volume/musik. Ikon menyala warna aksen saat mode kit aktif.
Dialog Kit: saklar *Pakai Kit*, Edit Layout, Ganti Nama, daftar kit (pilih / export / hapus), Kit Baru, Impor, Online.

## Nyambung dengan mode biasa
Kit hanya menggantikan **isi area pad** (pad klasik / skin) selama saklar menyala; bisa dimatikan kapan saja tanpa restart.
Panel preset, volume, musik, strip tombol bawah, REC, LAYAR, visual sentuhan, GLER, event tetap sama.
Tombol SET SUARA (strip bawah / tombol VOICE skin) memakai pad kit yang terpilih, lengkap dengan pilih lokal/online
dan potong suara. Keyboard/MIDI memicu pad kit bernomor sama (1..16) selama mode kit aktif.

## Mode edit
Bilah edit melayang (hanya saat edit): Selesai, Pad Foto (banyak gambar sekaligus = banyak pad), Pad Polos, Latar,
Gambar, Suara, Ukuran, Mixer (volume, nada, choke), Loop, Salin, Hapus (2 ketukan). Tarik pad = geser;
tarik bulatan di sudut kanan-bawah pad terpilih = ubah ukuran. Bilah pindah ke atas bila pad terpilih ada di bawah.
Panel *Latar*: pilih/hapus gambar, slider transparansi latar dan transparansi pad (seperti pengaturan latar mode normal).

## Efek ketuk
Pad digelapkan (50%, hanya bagian yang tidak transparan) selama jari menempel, kembali normal saat dilepas.
Ketukan kilat tetap tampil minimal 70 ms. Multi-touch per jari. Keyboard/MIDI: gelap 90 ms.

## Teknis
- `KitModel.kt` model + pemetaan slot; `KitStorage.kt` JSON/gambar/zip (zip-slip dicek, batas 400 MB);
  `KitState.kt` state Compose + `KitEngine`; `KitCanvas.kt` panggung, sentuh, gestur edit; `KitUi.kt` dock & dialog;
  `KitOnline.kt` klien GitHub.
- Panggung kit = SELURUH area pad, di-STRETCH memenuhi area (seperti skin / pad normal; skala X dan Y boleh beda).
  Tiap pad menyimpan `cx, cy` (titik tengah) dan `w, h` (lebar/tinggi) sebagai pecahan 0..1 dari panggung, jadi posisinya
  tetap di layar berukuran apa pun, tanpa letterbox / area kosong. Latar juga di-stretch (FillBounds) supaya tetap segaris dengan pad.
- Format kit v2 (`KIT_FORMAT_VERSION = 2`). Kit v1 lama (`size` + `aspect`) dimigrasi otomatis saat dimuat
  (`KitState.migrate`: `w = h * rasioGambar / aspect`) dan ditulis ulang sebagai v2. `size` tetap ditulis (= h) untuk pembaca lama.
- Tarik bulatan sudut = ubah ukuran seragam (proporsi di layar tetap); slider Ukuran = skala w & h bersama.
- Hit-test mengabaikan piksel transparan PNG (toleransi kecil).
- Native: `kMaxPresets` 50 -> 58. Kit memakai slot preset 51..58 (8 x 16 = 128 pad) sehingga preset P1..P50 tidak tersentuh.
  Cross-choke antar pad tidak tersedia di kit (choke per pad tersedia).
- Data: `filesDir/kits/<id>/{kit.json, images/, sounds/}`; kit.json ditulis dengan jeda 400 ms + saat onPause/Selesai.
- `KitState.PREMIUM_ONLY` (default false) untuk mengunci fitur di versi FREE bila diinginkan.

## v1.10.2
- **Resize pad tidak gepeng lagi.** Penyebab lama: batas ukuran minimum/maksimum dihitung terpisah untuk lebar dan tinggi, jadi
  pad yang dikecilkan sampai mentok di satu sisi masih terus mengecil di sisi lain, lalu saat dibesarkan bentuknya sudah rusak.
  Sekarang lebar & tinggi selalu dikali faktor yang SAMA dengan batas bersama (`KitPad.scaledUniform`, `KIT_PAD_MIN_FRAC` /
  `KIT_PAD_MAX_FRAC` di KitModel.kt). Bulatan sudut menghitung ukuran dari kondisi awal sentuhan + total geseran (tidak ditumpuk
  per gerakan), dan slider Ukuran memakai fungsi yang sama.
- **Mode edit = full layar otomatis.** Saat edit kit dimulai, panel preset, panel volume/musik, strip tombol bawah, panel skin, dan
  tab tipis kanan disembunyikan otomatis; saat Selesai, semuanya dikembalikan persis seperti sebelum edit (jadi kalau sebelumnya
  sudah full layar, tetap full layar). Perubahan sementara ini tidak ditulis ke pengaturan permanen (`KitUiBackup` di MainActivity).
- **Kit per preset.** Mode kit sekarang berlaku PER PRESET: P1 bisa kit A, P2 pad biasa, P3 kit B. Pindah preset otomatis pindah
  tampilan. Peta preset -> kit disimpan di prefs `realpads_kit` (`preset_kits`, mis. `1=kit_x;3=kit_y`); dikelola `KitState.presetKit`,
  `KitState.onPresetChanged`. Kit hanya dimuat ulang bila preset tujuan memakai kit yang BERBEDA dari yang sedang termuat; pindah
  ke preset pad biasa lalu kembali tidak perlu muat ulang. Preset yang memakai kit diberi ikon kecil di panel preset; dialog Kit
  menampilkan preset aktif dan di preset mana saja tiap kit dipakai. Menghapus preset / kit membersihkan penugasannya. Pengguna
  versi lama (mode kit global) otomatis dimigrasi: kit terakhir dipakai di P1.
- Catatan: mengedit sebuah kit mengubah semua preset yang memakai kit itu.

## v1.10.3
- Saat edit, pad tidak bisa keluar dari area: geser, tarik bulatan ukuran, slider Ukuran, salin, dan tambah pad semuanya dijaga lewat `KitPad.clampedInside()` (seluruh pad di dalam panggung; kalau kebesaran diperkecil seragam). Ukuran maksimum pad = seluruh area (`KIT_PAD_MAX_FRAC = 1`).

## v1.10.4
- Badge preset di judul dialog Kit sekarang kotak 34dp bergaya tombol preset (sudut 4dp, warna aksen, teks tebal).
- Kedip gelap pad kit mengikuti ketukan: lepas jari = langsung terang (tanpa penundaan lepas), kilatan minimum 40 ms dihitung per ketukan, dan ketukan beruntun sempat terang 1 frame di antaranya (`KitState.pressSeq` + efek `flash` di KitPadView). Sebelumnya penundaan lepas 70 ms membuat ketukan cepat menyambung jadi seperti ditahan.

## v1.10.5
- Fitur "Pad Polos" dihapus (tombol di bilah edit dan fungsi `addBlankPad`). Pad baru hanya lewat "Pad Foto".
- Panel di bilah edit (Latar, Ukuran, Mixer) punya tombol X untuk menutup. Menekan "Pad Foto" juga menutup panel yang terbuka.
- Efek ketuk pad kit disamakan dengan skin: kedip dicatat sinkron saat pukulan (`onKitPadDown` mengisi `padHitTimeNanos` / `flashIntensity`), gelap penuh selama jari menempel, lalu meluruh linear 90 ms. Bedanya, skin menyalakan pad, kit menggelapkan pad (`KitCanvas`: `padFlash`, `darkAmt`). Efek `pressSeq` / `pulse` / penundaan lepas dari 1.10.4 dihapus; keyboard/MIDI otomatis ikut kedip yang sama.

## Nomor pad selalu rapi (urut 1..N)
- **Hapus pad** di mode edit kit -> nomor pad sisanya langsung dirapikan (hapus pad 3 dari 5 pad: pad 4 jadi 3, pad 5 jadi 4). Tidak ada lagi lubang / loncatan nomor. Pad baru selalu mendapat nomor N+1.
- **Kit lama / hasil impor** yang nomornya tidak beraturan (mis. 1, 5, 9) dirapikan otomatis saat kit dipakai (`KitState.loadFromDisk`), lalu ditulis ulang ke `kit.json`.
- Urutan relatif nomor dijaga (nomor terkecil -> 1, dst). Posisi, ukuran, gambar, dan urutan tumpukan pad di layar tidak berubah.
- Yang ikut berpindah bersama nomornya: file suara (`pad<N>.wav` diganti nama), slot suara di engine native, volume, tune, loop, choke, dan flag bass. Nomor dipakai juga oleh keyboard/MIDI (pad 1..16), jadi tombol fisik mengikuti nomor baru.
- Kode: `Kit.compactMapping()` (KitModel.kt), `KitStorage.renumberPads` (KitStorage.kt), `KitEngine.applyRenumber`, `KitState.compactPadNumbers` / `removePad` (KitState.kt).
- **Export & impor:** kit yang diexport selalu bernomor rapi (kit aktif lewat `compactPadNumbers`, kit lain lewat `KitStorage.compactOnDisk`), dan kit yang diimpor (termasuk dari repo online) dirapikan saat impor.
- **Aman terhadap kegagalan:** `renumberPads` memindahkan file suara lewat rename, dan kalau ada satu saja yang gagal semuanya dikembalikan seperti semula (nomor tidak berubah setengah-setengah, dicoba lagi lain kali).
- **Hapus beruntun:** perapian diantre lewat mutex dan selalu dihitung dari kit terbaru. `KitState.renumbering` menahan aksi edit yang memakai nomor pad selama perapian berjalan.
