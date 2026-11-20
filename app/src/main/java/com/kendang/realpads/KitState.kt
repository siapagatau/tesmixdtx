package com.kendang.realpads

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

// Jembatan Kit <-> engine native. Kit memakai slot preset 51.. (lihat kitSlotPreset), terpisah
// dari 50 preset biasa, jadi memuat/menghapus kit TIDAK PERNAH menyentuh suara preset P1..P50.
// Sample dimuat lewat jalur yang sama persis dengan pad biasa (nativeLoadSample) -> latency,
// mixer, REC, volume kendang, GLER semuanya otomatis ikut.
object KitEngine {
    fun clearAll() {
        for (id in 1..KIT_MAX_PADS) clearPad(id)
    }

    fun clearPad(id: Int) {
        val p = kitSlotPreset(id)
        val s = kitSlotPad(id)
        AudioEngine.nativeClearPad(p, s)
        AudioEngine.nativeSetPadVolume(p, s, 1f)
        AudioEngine.nativeSetPadTune(p, s, 0f)
        AudioEngine.nativeSetPadFineTune(p, s, 0f)
        AudioEngine.nativeSetPadPan(p, s, 0f)
        AudioEngine.nativeSetPadChoke(p, s, true)
        AudioEngine.nativeSetPadLoop(p, s, false)
    }

    fun applyParams(pad: KitPad) {
        val p = kitSlotPreset(pad.id)
        val s = kitSlotPad(pad.id)
        AudioEngine.nativeSetPadVolume(p, s, pad.volume)
        AudioEngine.nativeSetPadTune(p, s, pad.tune)
        AudioEngine.nativeSetPadFineTune(p, s, pad.fine)
        AudioEngine.nativeSetPadPan(p, s, pad.pan)
        AudioEngine.nativeSetPadChoke(p, s, pad.choke)
        AudioEngine.nativeSetPadLoop(p, s, pad.loop)
    }

    // Muat suara 1 pad dari file kit ke engine. Return true kalau berhasil.
    fun loadPadSound(context: Context, kitId: String, pad: KitPad): Boolean {
        val name = pad.sound ?: return false
        return try {
            val f = KitStorage.soundFile(context, kitId, name)
            if (!f.exists()) return false
            val wav = WavLoader.parse(f.readBytes()) ?: return false
            AudioEngine.nativeLoadSample(kitSlotPreset(pad.id), kitSlotPad(pad.id), wav.pcm, wav.channels)
            true
        } catch (e: Throwable) {
            false
        }
    }

    // Setelah nomor pad dirapikan (Kit.compactMapping): slot lama yang tidak dipakai lagi dikosongkan, slot yang
    // isinya berpindah dimuat ulang dari file. Pad yang nomornya tidak berubah tidak disentuh. BLOCKING (IO).
    // [kit] = kit SESUDAH diberi nomor baru, [map] = nomor lama -> baru.
    fun applyRenumber(context: Context, kit: Kit, map: Map<Int, Int>) {
        val newIds = kit.pads.map { it.id }.toHashSet()
        val changed = map.values.toHashSet()
        // Hentikan loop yang sedang berputar di slot yang isinya akan diganti.
        for (id in map.keys + changed) {
            val sp = kitSlotPreset(id)
            val sl = kitSlotPad(id)
            if (AudioEngine.nativeIsPadLooping(sp, sl)) AudioEngine.nativeTrigger(sp, sl)
        }
        for (old in map.keys) if (old !in newIds) clearPad(old)
        for (pad in kit.pads) {
            if (pad.id !in changed) continue
            clearPad(pad.id)
            applyParams(pad)
            loadPadSound(context, kit.id, pad)
        }
    }

    // Kosongkan semua slot kit lalu muat seluruh suara + parameter kit ini. BLOCKING (IO).
    fun loadKit(context: Context, kit: Kit) {
        clearAll()
        AudioEngine.nativeSetKitChokeAll(kit.chokeAll)
        for (pad in kit.pads) {
            applyParams(pad)
            loadPadSound(context, kit.id, pad)
        }
    }
}

// State Kit yang dipakai UI. Aturan thread sama seperti BackgroundState: fungsi suspend di
// sini mengerjakan IO di Dispatchers.IO lalu menulis state di thread pemanggil (main); fungsi
// biasa (update*, setMode, dst) HARUS dipanggil dari main thread.
object KitState {
    // Ganti ke true kalau suatu saat fitur Kit mau dikunci di versi FREE (seperti TEMA & SKIN).
    const val PREMIUM_ONLY = false

    private const val PREFS = "realpads_kit"
    private const val KEY_ACTIVE = "active_id"
    private const val KEY_MODE = "mode_on"          // format lama (global); hanya dibaca untuk migrasi
    private const val KEY_PRESET_KITS = "preset_kits" // "1=kit_x;3=kit_y" -> kit yang dipakai tiap preset

    // Tap lebih singkat dari ini tetap menggelapkan pad sekurang-kurangnya selama ini, supaya

    // Kit aktif (null = belum ada kit).
    var kit by mutableStateOf<Kit?>(null)
        private set
    // True = area pad menampilkan kit; false = pad biasa / skin. Bisa diganti kapan saja tanpa restart.
    // MODE KIT BERLAKU PER PRESET: nilainya mengikuti preset yang sedang aktif (lihat presetKit / presetIndex).
    // P1 boleh memakai kit, P2 pad biasa, P3 kit lain, dst - pindah preset otomatis pindah tampilan.
    var modeOn by mutableStateOf(false)
        private set
    // Nomor preset -> id kit yang dipakai preset itu. Preset yang tidak ada di sini = pad biasa / skin.
    val presetKit = mutableStateMapOf<Int, String>()
    // Preset yang sedang aktif (diisi PadScreen lewat onPresetChanged).
    var presetIndex by mutableStateOf(1)
        private set
    private var ready = false
    private val switchMutex = Mutex()
    var editing by mutableStateOf(false)
    var kits by mutableStateOf<List<KitInfo>>(emptyList())
        private set
    var bgImage by mutableStateOf<ImageBitmap?>(null)
        private set
    // Gambar semua pad kit aktif, kunci = nama file.
    val images = mutableStateMapOf<String, ImageBitmap>()
    // False selama suara kit sedang dimuat ke engine (ketukan diabaikan sebentar).
    var soundsReady by mutableStateOf(true)
        private set
    var busy by mutableStateOf(false)
        private set

    // Rasio area pad saat ini (dilaporkan PadScreen) - dipakai untuk ukuran awal pad baru agar proporsional.
    var areaAspect: Float = 2.2f

    // Jumlah jari yang sedang menekan tiap pad (gelap selama > 0).
    val pressCount = mutableStateMapOf<Int, Int>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val saveExecutor = Executors.newSingleThreadExecutor()
    private var pendingSave: Runnable? = null

    // ------------------------------------------------------------ efek tekan

    // Hitung jari yang sedang menempel (gelap penuh selama > 0). Kedip meluruh setelah pukulan mengikuti
    // flashIntensity milik PadScreen (sama persis dengan pad biasa / skin), bukan diurus di sini.
    fun pressDown(id: Int) {
        pressCount[id] = (pressCount[id] ?: 0) + 1
    }

    fun pressUp(id: Int) {
        val c = (pressCount[id] ?: 0) - 1
        if (c <= 0) pressCount.remove(id) else pressCount[id] = c
    }

    // ------------------------------------------------------------ simpan

    private fun scheduleSave(context: Context) {
        val app = context.applicationContext
        pendingSave?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            val snap = kit
            if (snap != null) saveExecutor.execute { KitStorage.save(app, snap) }
        }
        pendingSave = r
        mainHandler.postDelayed(r, 400L)
    }

    // Tulis kit.json SEKARANG (dipanggil saat app ke background / selesai edit).
    fun flushNow(context: Context) {
        pendingSave?.let { mainHandler.removeCallbacks(it) }
        pendingSave = null
        val snap = kit ?: return
        val app = context.applicationContext
        saveExecutor.execute { KitStorage.save(app, snap) }
    }

    // ------------------------------------------------------------ muat / aktifkan

    private class Loaded(val kit: Kit, val images: Map<String, ImageBitmap>, val bg: ImageBitmap?)

    private fun loadFromDisk(app: Context, id: String): Loaded? {
        val loadedKit = KitStorage.load(app, id) ?: return null
        val imgs = HashMap<String, ImageBitmap>()
        for (p in loadedKit.pads) {
            val n = p.image ?: continue
            if (imgs.containsKey(n)) continue
            val bmp = KitStorage.decodeImage(app, id, n)
            if (bmp != null) imgs[n] = bmp
        }
        var k = migrate(loadedKit, imgs)
        // Kit lama / hasil impor yang nomor padnya loncat-loncat (mis. 1, 5, 9) dirapikan otomatis jadi 1..N
        // sebelum suara dimuat ke engine, lalu ditulis ulang sekali saja.
        val renum = k.compactMapping()
        if (renum.isNotEmpty()) k = KitStorage.renumberPads(app, k, renum)
        if (k !== loadedKit) KitStorage.save(app, k)   // tulis format baru / nomor rapi sekali saja
        val bg = k.background?.let { KitStorage.decodeImage(app, id, it) }
        KitEngine.loadKit(app, k)
        return Loaded(k, imgs, bg)
    }

    // Kit format lama (v1) menyimpan size (tinggi) + aspect panggung, lebar ikut rasio gambar.
    // Format baru menyimpan w & h langsung sebagai pecahan panggung yang di-stretch ke area pad.
    // Konversi: w = h * rasioGambar / aspect (hasilnya sama persis dengan tampilan lama di layar pembuatnya).
    private fun migrate(k: Kit, imgs: Map<String, ImageBitmap>): Kit {
        if (k.pads.none { it.w <= 0f }) return k
        val stageAspect = k.aspect.coerceAtLeast(0.1f)
        return k.copy(pads = k.pads.map { p ->
            if (p.w > 0f) {
                p
            } else {
                val b = p.image?.let { imgs[it] }
                val ia = if (b != null && b.height > 0) b.width.toFloat() / b.height.toFloat() else 1f
                p.copy(w = (p.h * ia / stageAspect).coerceIn(0.02f, 2f))
            }
        })
    }

    suspend fun refreshList(context: Context) {
        val app = context.applicationContext
        kits = withContext(Dispatchers.IO) { KitStorage.list(app) }
    }

    private fun savePresetKits(app: Context) {
        val sb = StringBuilder()
        for ((n, id) in presetKit) {
            if (sb.isNotEmpty()) sb.append(';')
            sb.append(n).append('=').append(id)
        }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PRESET_KITS, sb.toString()).apply()
    }

    // Return false kalau belum pernah disimpan (pengguna dari versi sebelum kit per preset).
    private fun loadPresetKits(app: Context): Boolean {
        val raw = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PRESET_KITS, null) ?: return false
        presetKit.clear()
        for (part in raw.split(';')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val n = part.substring(0, eq).toIntOrNull() ?: continue
            val id = part.substring(eq + 1)
            if (id.isNotBlank()) presetKit[n] = id
        }
        return true
    }

    private fun refreshMode() {
        val want = presetKit[presetIndex]
        val k = kit
        modeOn = want != null && k != null && k.id == want
        if (!modeOn) editing = false
    }

    // Dipanggil sekali saat app dibuka.
    suspend fun init(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        refreshList(app)
        if (!loadPresetKits(app)) {
            // Migrasi dari versi lama (mode kit global): kit aktif terakhir dipakai di P1.
            val savedId = prefs.getString(KEY_ACTIVE, null)
            if (prefs.getBoolean(KEY_MODE, false) && savedId != null && kits.any { it.id == savedId }) {
                presetKit[1] = savedId
            }
            savePresetKits(app)
        }
        // Buang penugasan ke kit yang sudah tidak ada.
        val valid = kits.map { it.id }.toSet()
        val stale = presetKit.filterValues { it !in valid }.keys.toList()
        if (stale.isNotEmpty()) {
            stale.forEach { presetKit.remove(it) }
            savePresetKits(app)
        }
        ready = true
        applyPreset(app, presetIndex)
    }

    // PadScreen memanggil ini tiap preset aktif berganti. Kit dimuat HANYA bila preset tujuan memakai kit yang
    // berbeda dari kit yang sedang termuat di engine (slot kit terpisah dari P1..P50, jadi kit yang sama / pindah
    // ke preset pad biasa dan kembali TIDAK perlu muat ulang = pindah preset instan).
    suspend fun onPresetChanged(context: Context, preset: Int) {
        presetIndex = preset
        if (!ready) return   // init() akan menerapkan preset ini setelah selesai
        applyPreset(context.applicationContext, preset)
    }

    private suspend fun applyPreset(app: Context, preset: Int) {
        // NonCancellable: kalau pengguna pindah preset cepat-cepat, pemuatan kit yang sudah jalan harus tuntas
        // supaya engine & state tetap sinkron; permintaan berikutnya antre lewat mutex lalu menerapkan preset terakhir.
        withContext(NonCancellable) {
            switchMutex.withLock {
                val target = presetIndex   // selalu ikuti preset terbaru, bukan yang diminta saat antre
                if (editing && kit != null) {
                    editing = false
                    flushNow(app)
                }
                val want = presetKit[target]
                if (want != null && kits.none { it.id == want }) {
                    presetKit.remove(target)
                    savePresetKits(app)
                }
                val id = presetKit[target]
                if (id != null && kit?.id != id) activate(app, id)
                refreshMode()
            }
        }
    }

    // Hapus penugasan kit dari sebuah preset (mis. preset dihapus).
    fun clearPresetAssignment(context: Context, preset: Int) {
        if (presetKit.remove(preset) != null) {
            savePresetKits(context.applicationContext)
            refreshMode()
        }
    }

    // Jadikan kit ini kit aktif (muat gambar + suara). Mode kit ON/OFF tidak diubah di sini.
    suspend fun activate(context: Context, id: String): Boolean {
        val app = context.applicationContext
        busy = true
        soundsReady = false
        editing = false
        val loaded = withContext(Dispatchers.IO) { loadFromDisk(app, id) }
        if (loaded == null) {
            soundsReady = true
            busy = false
            return false
        }
        images.clear()
        images.putAll(loaded.images)
        bgImage = loaded.bg
        kit = loaded.kit
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ACTIVE, id).apply()
        soundsReady = true
        busy = false
        refreshMode()
        return true
    }

    // Nyalakan / matikan mode kit untuk PRESET AKTIF saja. ON memakai kit yang sedang termuat.
    fun setMode(context: Context, on: Boolean) {
        val app = context.applicationContext
        val k = kit
        if (on && k != null) presetKit[presetIndex] = k.id else presetKit.remove(presetIndex)
        savePresetKits(app)
        refreshMode()
    }

    // Buat kit kosong baru, aktifkan, nyalakan mode kit, dan langsung masuk mode edit.
    suspend fun createKit(context: Context, name: String): Boolean {
        val app = context.applicationContext
        val created = withContext(Dispatchers.IO) { KitStorage.createEmpty(app, name, areaAspect) }
        refreshList(app)
        val ok = activate(app, created.id)
        if (ok) {
            setMode(app, true)
            editing = true
        }
        return ok
    }

    // Hapus kit. Preset yang memakai kit ini kembali ke pad biasa. Kalau yang dihapus kit aktif di preset
    // yang sedang tampil: pindah ke kit lain (bila ada), atau kembali ke pad biasa.
    suspend fun deleteKit(context: Context, id: String) {
        val app = context.applicationContext
        val wasActive = kit?.id == id
        val wasShownNow = modeOn && wasActive
        if (wasActive) {
            pendingSave?.let { mainHandler.removeCallbacks(it) }
            pendingSave = null
        }
        withContext(Dispatchers.IO) { KitStorage.delete(app, id) }
        refreshList(app)
        val users = presetKit.filterValues { it == id }.keys.toList()
        users.forEach { presetKit.remove(it) }
        if (wasActive) {
            val next = kits.firstOrNull()?.id
            if (next != null) {
                activate(app, next)
                if (wasShownNow) presetKit[presetIndex] = next
            } else {
                withContext(Dispatchers.IO) { KitEngine.clearAll() }
                images.clear()
                bgImage = null
                kit = null
            }
        }
        savePresetKits(app)
        refreshMode()
    }

    suspend fun importFromUri(context: Context, uri: Uri): String? {
        val app = context.applicationContext
        busy = true
        val id = withContext(Dispatchers.IO) {
            try {
                app.contentResolver.openInputStream(uri)?.use { KitStorage.importKit(app, it) }
            } catch (e: Throwable) {
                null
            }
        }
        refreshList(app)
        busy = false
        return id
    }

    // Import kit dari file zip yang sudah diunduh (kit online). Return id kit baru / null.
    fun importFromFileBlocking(app: Context, file: java.io.File): String? {
        return try {
            file.inputStream().use { KitStorage.importKit(app, it) }
        } catch (e: Throwable) {
            null
        }
    }

    suspend fun exportTo(context: Context, kitId: String, uri: Uri): Int {
        val app = context.applicationContext
        // Kit yang diexport selalu bernomor rapi: kit aktif dirapikan lewat state (engine ikut), kit lain langsung di disk.
        if (kit?.id == kitId) compactPadNumbers(app)
        val snap = kit
        return withContext(Dispatchers.IO) {
            try {
                // Pastikan kit.json terbaru sudah di disk (perubahan terakhir mungkin belum tertulis).
                if (snap != null && snap.id == kitId) KitStorage.save(app, snap) else KitStorage.compactOnDisk(app, kitId)
                app.contentResolver.openOutputStream(uri)?.use { KitStorage.exportKit(app, kitId, it) } ?: 0
            } catch (e: Throwable) {
                0
            }
        }
    }

    // ------------------------------------------------------------ edit kit aktif

    fun update(context: Context, transform: (Kit) -> Kit) {
        val cur = kit ?: return
        kit = transform(cur)
        scheduleSave(context)
    }

    fun updatePad(context: Context, padId: Int, transform: (KitPad) -> KitPad) {
        update(context) { k -> k.copy(pads = k.pads.map { if (it.id == padId) transform(it) else it }) }
    }

    fun padById(id: Int): KitPad? = kit?.pads?.firstOrNull { it.id == id }

    fun rename(context: Context, name: String) {
        update(context) { it.copy(name = name.trim().ifBlank { it.name }) }
        kits = kits.map { if (it.id == kit?.id) it.copy(name = kit?.name ?: it.name) else it }
    }

    private fun nextFreeId(k: Kit): Int? {
        val used = HashSet<Int>()
        for (p in k.pads) used.add(p.id)
        for (i in 1..KIT_MAX_PADS) if (i !in used) return i
        return null
    }

    // Ukuran awal pad (w, h = pecahan panggung). Tinggi 30% area, tapi gambar yang sangat lebar dibatasi
    // ~40% lebar. Rasio dihitung dari area pad di layar pembuat (areaAspect) supaya gambar tampil
    // proporsional di sana; di layar lain ikut ter-stretch bersama seluruh kit.
    private fun defaultSize(imageAspect: Float): Pair<Float, Float> {
        val stageAspect = areaAspect.coerceIn(0.5f, 4f)
        val ia = imageAspect.coerceAtLeast(0.1f)
        var h = 0.3f
        var w = h * ia / stageAspect
        if (w > 0.4f) {
            w = 0.4f
            h = w * stageAspect / ia
        }
        return Pair(w.coerceAtLeast(0.06f), h.coerceAtLeast(0.08f))
    }

    // Posisi awal pad baru: bertingkat supaya tidak menumpuk persis.
    private fun defaultPosition(count: Int): Pair<Float, Float> {
        val n = count % 8
        return Pair(0.5f + (n - 3.5f) * 0.05f, 0.5f + ((n % 4) - 1.5f) * 0.06f)
    }

    // Tambah banyak pad sekaligus dari gambar galeri (1 gambar = 1 pad). Return jumlah pad yang ditambah.
    suspend fun addPadsFromUris(context: Context, uris: List<Uri>): Int {
        val app = context.applicationContext
        var added = 0
        for (uri in uris) {
            val cur = kit ?: break
            val id = nextFreeId(cur) ?: break
            val saved: Pair<String, ImageBitmap>? = withContext(Dispatchers.IO) {
                val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null) {
                    null
                } else {
                    val name = KitStorage.saveImage(app, cur.id, bytes, KitStorage.PAD_IMAGE_MAX_SIDE, "pad_")
                    val bmp = if (name != null) KitStorage.decodeImage(app, cur.id, name) else null
                    if (name != null && bmp != null) Pair(name, bmp) else null
                }
            }
            if (saved == null) continue
            images[saved.first] = saved.second
            val imgAspect = saved.second.width.toFloat() / saved.second.height.toFloat().coerceAtLeast(1f)
            val pos = defaultPosition(cur.pads.size)
            val sz = defaultSize(imgAspect)
            update(app) { it.copy(pads = it.pads + KitPad(id = id, cx = pos.first, cy = pos.second, w = sz.first, h = sz.second, image = saved.first).clampedInside()) }
            added++
        }
        return added
    }

    suspend fun setPadImage(context: Context, padId: Int, uri: Uri): Boolean {
        val app = context.applicationContext
        val cur = kit ?: return false
        val saved: Pair<String, ImageBitmap>? = withContext(Dispatchers.IO) {
            val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null) {
                null
            } else {
                val name = KitStorage.saveImage(app, cur.id, bytes, KitStorage.PAD_IMAGE_MAX_SIDE, "pad_")
                val bmp = if (name != null) KitStorage.decodeImage(app, cur.id, name) else null
                if (name != null && bmp != null) Pair(name, bmp) else null
            }
        }
        if (saved == null) return false
        images[saved.first] = saved.second
        updatePad(app, padId) { it.copy(image = saved.first) }
        return true
    }

    fun clearPadImage(context: Context, padId: Int) {
        updatePad(context, padId) { it.copy(image = null) }
    }

    suspend fun setBackground(context: Context, uri: Uri): Boolean {
        val app = context.applicationContext
        val cur = kit ?: return false
        val saved: Pair<String, ImageBitmap>? = withContext(Dispatchers.IO) {
            val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null) {
                null
            } else {
                val name = KitStorage.saveImage(app, cur.id, bytes, KitStorage.BACKGROUND_MAX_SIDE, "bg_")
                val bmp = if (name != null) KitStorage.decodeImage(app, cur.id, name) else null
                if (name != null && bmp != null) Pair(name, bmp) else null
            }
        }
        if (saved == null) return false
        bgImage = saved.second
        // Latar baru langsung tampil penuh (sama seperti latar di mode normal).
        update(app) { it.copy(background = saved.first, bgOpacity = 1f) }
        return true
    }

    fun clearBackground(context: Context) {
        bgImage = null
        update(context) { it.copy(background = null) }
    }

    fun setBgOpacity(context: Context, v: Float) {
        update(context) { it.copy(bgOpacity = v.coerceIn(0f, 1f)) }
    }

    fun setPadOpacity(context: Context, v: Float) {
        update(context) { it.copy(padOpacity = v.coerceIn(0f, 1f)) }
    }

    // Hapus pad, lalu nomor pad sisanya langsung dirapikan lagi (tidak ada lubang nomor: hapus pad 3 dari 5
    // pad -> pad 4 jadi 3, pad 5 jadi 4).
    suspend fun removePad(context: Context, padId: Int) {
        KitEngine.clearPad(padId)
        update(context) { k -> k.copy(pads = k.pads.filter { it.id != padId }) }
        compactPadNumbers(context)
    }

    private val compactMutex = Mutex()

    // True selama nomor pad sedang dirapikan (file suara & slot engine dipindah). UI mengabaikan aksi edit
    // yang memakai nomor pad (hapus/salin/suara/dst) selama ini supaya tidak memakai nomor yang sedang berubah.
    var renumbering by mutableStateOf(false)
        private set

    // Rapikan nomor pad kit aktif jadi 1..N berurutan (suara ikut pindah: file, slot engine, dan kit.json).
    // Panggilan beruntun diantre lewat mutex dan selalu menghitung ulang dari kit TERBARU, jadi hapus pad
    // dua kali berturut-turut tidak ada yang terlewat. Return true kalau ada nomor yang berubah.
    suspend fun compactPadNumbers(context: Context): Boolean = compactMutex.withLock {
        val app = context.applicationContext
        val cur = kit ?: return@withLock false
        val map = cur.compactMapping()
        if (map.isEmpty()) return@withLock false
        renumbering = true
        var mutedSounds = false
        // Batalkan simpan tertunda: isinya masih nomor lama dan bisa menimpa hasil di bawah.
        pendingSave?.let { mainHandler.removeCallbacks(it) }
        pendingSave = null
        try {
            // Tahap 1 (cepat): pindah file suara + tulis kit.json bernomor baru.
            val nk = withContext(NonCancellable + Dispatchers.IO) {
                val r = KitStorage.renumberPads(app, cur, map)
                if (r !== cur) KitStorage.save(app, r)
                r
            }
            if (nk === cur) return@withLock false   // gagal & sudah dikembalikan seperti semula; dicoba lagi lain kali
            kit = nk
            // Tahap 2: slot suara engine mengikuti nomor baru (ketukan diabaikan sebentar).
            soundsReady = false
            mutedSounds = true
            withContext(NonCancellable + Dispatchers.IO) { KitEngine.applyRenumber(app, nk, map) }
            flushNow(app)
            true
        } finally {
            if (mutedSounds) soundsReady = true
            renumbering = false
        }
    }

    // Salin pad (gambar, suara, pengaturan) ke pad baru yang sedikit bergeser. Return id pad baru.
    fun duplicatePad(context: Context, padId: Int): Int? {
        val app = context.applicationContext
        val k = kit ?: return null
        val src = k.pads.firstOrNull { it.id == padId } ?: return null
        val newId = nextFreeId(k) ?: return null
        val newSound = src.sound?.let { KitStorage.copySound(app, k.id, it, newId) }
        val copy = src.copy(
            id = newId,
            cx = src.cx + 0.05f,
            cy = src.cy + 0.05f,
            sound = newSound
        ).clampedInside()
        update(app) { it.copy(pads = it.pads + copy) }
        KitEngine.applyParams(copy)
        if (newSound != null) KitEngine.loadPadSound(app, k.id, copy)
        return newId
    }

    // ------------------------------------------------------------ suara & parameter pad

    // Tahap 1 (thread IO): tulis WAV ke folder kit. Return nama file.
    fun storeSoundFile(context: Context, kitId: String, padId: Int, wavBytes: ByteArray): String {
        return KitStorage.saveSound(context.applicationContext, kitId, padId, wavBytes)
    }

    // Tahap 2 (main): pasang ke engine + catat di model kit.
    fun applySound(context: Context, padId: Int, wav: WavData, soundName: String) {
        AudioEngine.nativeLoadSample(kitSlotPreset(padId), kitSlotPad(padId), wav.pcm, wav.channels)
        updatePad(context, padId) { it.copy(sound = soundName, bass = wav.isBass) }
        padById(padId)?.let { KitEngine.applyParams(it) }
    }

    fun setPadVolume(context: Context, padId: Int, v: Float) {
        val value = v.coerceIn(0f, 1.5f)
        updatePad(context, padId) { it.copy(volume = value) }
        AudioEngine.nativeSetPadVolume(kitSlotPreset(padId), kitSlotPad(padId), value)
    }

    fun setPadTune(context: Context, padId: Int, semitones: Float) {
        val value = semitones.coerceIn(-12f, 12f)
        updatePad(context, padId) { it.copy(tune = value) }
        AudioEngine.nativeSetPadTune(kitSlotPreset(padId), kitSlotPad(padId), value)
    }

    // MIXER: fine tune (cent) & pan per pad kit.
    fun setPadFineTune(context: Context, padId: Int, cents: Float) {
        val value = cents.coerceIn(-100f, 100f)
        updatePad(context, padId) { it.copy(fine = value) }
        AudioEngine.nativeSetPadFineTune(kitSlotPreset(padId), kitSlotPad(padId), value)
    }

    fun setPadPan(context: Context, padId: Int, pan: Float) {
        val value = pan.coerceIn(-1f, 1f)
        updatePad(context, padId) { it.copy(pan = value) }
        AudioEngine.nativeSetPadPan(kitSlotPreset(padId), kitSlotPad(padId), value)
    }

    fun setPadLoop(context: Context, padId: Int, enabled: Boolean) {
        updatePad(context, padId) { it.copy(loop = enabled) }
        AudioEngine.nativeSetPadLoop(kitSlotPreset(padId), kitSlotPad(padId), enabled)
    }

    // Choke kit: ON = memukul pad mana pun menghentikan semua pad kit lain. Berlaku untuk seluruh kit.
    fun setChokeAll(context: Context, enabled: Boolean) {
        update(context) { it.copy(chokeAll = enabled) }
        AudioEngine.nativeSetKitChokeAll(enabled)
    }

    fun setPadChoke(context: Context, padId: Int, enabled: Boolean) {
        updatePad(context, padId) { it.copy(choke = enabled) }
        AudioEngine.nativeSetPadChoke(kitSlotPreset(padId), kitSlotPad(padId), enabled)
    }

    fun finishEdit(context: Context) {
        editing = false
        flushNow(context)
    }
}
