# Mode Edit — Pindah Suara Pad (v1.11.0)

Berlaku di tampilan pad **klasik** dan **skin**. Tidak aktif di mode **kit** (kit punya mode edit sendiri; tombolnya disembunyikan saat kit nyala, dan mode edit otomatis keluar kalau kit dinyalakan).

## Cara pakai
1. Tekan tombol **pensil** di tab kanan (tepat di atas tombol Kit).
2. Semua pad menyala warna tema (tanpa tulisan). Sentuhan tidak membunyikan pad.
3. Tahan sebuah pad → pad terangkat (membesar, bayangan, ikut jari). Di tempat asalnya tersisa bayangan putus-putus.
4. Geser ke pad lain → pad itu diberi tanda khusus (putih terang, garis tegas, lencana ikon tukar, berdenyut).
5. Lepas jari → pad meluncur turun ke pad tujuan, lalu **suaranya ditukar** (pad 1 ↔ pad 12). Dilepas di tempat lain → pad kembali, tidak ada perubahan.
6. Keluar mode edit: tekan tombol pensil lagi.

## Yang ikut berpindah bersama suara
File sample (.wav), flag bass (GLER), volume, tune, choke, loop, tempo loop, cross-choke (aktif/tidak + daftar pad yang memotong; bit pad a/b di semua mask ikut ditukar sehingga hubungan antar-suara tetap sama).
Tidak ikut: binding keyboard/MIDI (milik tombol fisik, bukan milik suara).
Pad yang sedang loop dihentikan dulu (fade-out) sebelum ditukar. Pad tanpa suara juga bisa dipindah (suara pindah, pad asal jadi kosong).

## Berkas
- `PadMoveOverlay.kt` — overlay + gestur (dipakai klasik & skin; menerima peta nomor pad → Rect lokal).
- `PadMoveState.kt` — `PadMoveState.editing`, `PadSoundMover.swap`.
- `PresetStorage.swapPads` — tukar file di disk + muat ulang ke native engine.
- `PadGrid` (MainActivity.kt) — param `editMode`, `onSwapPads`.
- `skin/SkinHost.kt` — param `padEditing`, `onSwapPads`.
- `skin/SkinToggleTab.kt` — `EditPadsSegment`.
