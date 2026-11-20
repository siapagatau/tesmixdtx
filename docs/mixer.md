# Mixer (v1.13.0)

Layar mixing per pad: **volume, pan L/R, tune (semitone + fine tune)**, plus **MASTER** dan **MUSIK**.
Tanpa Mute/Solo (sengaja disederhanakan).

## Cara pakai
- Buka lewat tombol **MIXER** di strip bawah, atau **Pengaturan -> Mixer -> BUKA** (berguna saat skin menyembunyikan strip bawah).
- Tiap pad = 1 strip: kotak **PAN**, kotak **TUNE**, **fader volume**, nama pad. Strip bisa digeser ke samping.
- **MASTER** = volume kendang, **MUSIK** = volume lagu. Keduanya sama persis dengan slider PAD & MUSIK di panel kanan (saling mengikuti).
- Ketuk kotak **PAN** / **TUNE** -> dialog muncul di **tengah layar** (tombol - / + bisa ditahan = berulang cepat, ada slider, tombol RESET/TENGAH dan OK).
- TUNE punya 2 tab: **SEMITON (±12)** dan **FINE TUNE (±100 cent)**, baris **Total** menampilkan offset gabungan.
- Fader: ketuk 2x = kembali 100%; menempel (detent) di 100%.
- **RESET** (kanan atas) mengembalikan volume/pan/tune semua pad di daftar ke bawaan (pakai konfirmasi). MASTER/MUSIK tidak ikut.

## Dengar langsung
Pad yang **dipilih** (ketuk strip / ketuk nama pad / sentuh fader / buka PAN atau TUNE) **diputar berulang** (tiap ~1,2 dtk)
dengan suara aslinya, dan **diputar ulang ~150 ms setelah nilai berhenti berubah** supaya perubahan langsung terdengar.
Ketuk nama pad yang sedang menyala untuk berhenti. Pad dengan setelan **Loop**: distop lalu dimulai ulang tiap nilai
berubah (tidak diketuk berulang, karena ketukan kedua = stop), dan dihentikan saat pilihan pindah / mixer ditutup.
Preview memanggil `AudioEngine.nativeTrigger` langsung, bukan `onPadDown` -> tidak menghitung misi event, kedip pad, atau GLER.

## Teknis
- Native (`PadEngine`): `padPan_` (-1..1) dan `padFineTune_` (cent) per (preset, pad). Di-**snapshot ke voice** saat ketukan mulai
  (sama seperti volume & tune). `pitchRatio = 2^((semitone + cent/100)/12)`.
- **Hukum pan = balance**: sisi lawan diredam `cos(|pan|*pi/2)`, sisi searah tetap 1.0. Pan 0 = (1,1) -> output **identik bit-per-bit**
  dengan sebelum fitur ini; pan ekstrem tidak pernah lebih keras dari sample asli. Hanya untuk output stereo.
- JNI: `nativeSet/GetPadPan`, `nativeSet/GetPadFineTune` (AudioEngine.kt).
- Penyimpanan: `PadPanStorage` (`realpads_pad_pan`), `PadFineTuneStorage` (`realpads_pad_fine_tune`), dimuat di `MainActivity` bersama
  volume/tune. Dibersihkan di `PresetStorage.deletePreset`, ikut tertukar di mode edit pindah suara (`PadSoundMover.swapSettings`).
- **Mode Kit**: `KitPad.pan` / `KitPad.fine` disimpan di JSON kit (ikut export/import `.kitdtxkdg`; kit lama tanpa field ini = 0).
- Semitone di mixer **bulat**. Kalau slider Tune lama (Pengaturan) pernah menyimpan semitone pecahan (mis. 1.5), saat mixer dibuka
  pecahannya dilipat ke fine tune (pitch total tetap sama).
- UI: `MixerDialog.kt` (Dialog layar penuh + 2 dialog tengah). Warna/bentuk memakai palet yang sudah ada (`CtrlPanelColor`, `PanelColor`,
  `ThemeState.accentColor`, gaya tombol seperti `ConfirmDialog`).

## Uji (PC, g++) yang sudah dijalankan pada engine
Sinus 440 Hz: pan 0 -> L=R; L100 -> R=0; R100 -> L=0; L50 -> R/L = 0.707; R25 -> L/R = 0.924; +12 st -> 880 Hz; +100c ~ 466 Hz;
-100c ~ 415 Hz; +1 st -100c -> 440 Hz.

## Belum
Kotlin/Gradle **belum dikompilasi** dan belum diuji di HP (sandbox tanpa Android SDK). Cek di HP: buka MIXER, pilih pad (harus bunyi
berulang), geser fader / ubah PAN (L terdengar di kiri) / TUNE (nada berubah, total offset benar), tutup mixer (suara berhenti,
pad loop ikut berhenti), tombol RESET, ganti preset lalu buka lagi, mode Kit.
