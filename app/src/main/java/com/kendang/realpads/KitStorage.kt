package com.kendang.realpads

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// Semua kerja FILE untuk fitur Kit. Fungsi di sini BLOCKING (baca/tulis disk, decode bitmap,
// zip) -> panggil dari Dispatchers.IO. Tidak menyentuh state Compose sama sekali.
//
// Struktur di internal storage:
//   filesDir/kits/<kitId>/kit.json
//   filesDir/kits/<kitId>/images/<gambar pad & latar>   (.png / .jpg)
//   filesDir/kits/<kitId>/sounds/pad<N>.wav             (WAV kanonik)
// Isi folder <kitId> persis yang dibungkus jadi zip .kitdtxkdg saat export.
object KitStorage {
    // Batas total isi kit yang boleh diekstrak saat import (cegah zip bomb): 400 MB.
    private const val MAX_IMPORT_BYTES = 400L * 1024L * 1024L

    // Sisi terpanjang maksimum gambar pad / latar setelah disimpan ke kit.
    const val PAD_IMAGE_MAX_SIDE = 1024
    const val BACKGROUND_MAX_SIDE = 1920

    private fun root(context: Context): File = File(context.filesDir, "kits")
    fun dir(context: Context, id: String): File = File(root(context), id)
    private fun imagesDir(context: Context, id: String): File = File(dir(context, id), "images")
    private fun soundsDir(context: Context, id: String): File = File(dir(context, id), "sounds")
    fun imageFile(context: Context, id: String, name: String): File = File(imagesDir(context, id), name)
    fun soundFile(context: Context, id: String, name: String): File = File(soundsDir(context, id), name)

    fun newId(): String {
        val rnd = (Math.random() * 46656).toInt()   // 36^3
        return "kit_" + java.lang.Long.toString(System.currentTimeMillis(), 36) + java.lang.Integer.toString(rnd, 36)
    }

    // Nama file dari JSON TIDAK dipercaya: buang path (cegah "../" keluar folder kit).
    private fun safeName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val n = File(raw).name
        return if (n.isBlank() || n == "." || n == "..") null else n
    }

    // ---------------------------------------------------------------- JSON

    fun toJson(kit: Kit): JSONObject {
        val o = JSONObject()
        o.put("format", KIT_FORMAT_NAME)
        o.put("version", KIT_FORMAT_VERSION)
        o.put("id", kit.id)
        o.put("name", kit.name)
        o.put("author", kit.author)
        o.put("aspect", kit.aspect.toDouble())
        o.put("background", kit.background ?: JSONObject.NULL)
        o.put("bgOpacity", kit.bgOpacity.toDouble())
        o.put("padOpacity", kit.padOpacity.toDouble())
        o.put("chokeAll", kit.chokeAll)
        val arr = JSONArray()
        for (p in kit.pads) {
            val po = JSONObject()
            po.put("id", p.id)
            po.put("cx", p.cx.toDouble())
            po.put("cy", p.cy.toDouble())
            po.put("w", p.w.toDouble())
            po.put("h", p.h.toDouble())
            po.put("size", p.h.toDouble())   // kompatibel dengan pembaca format lama
            po.put("image", p.image ?: JSONObject.NULL)
            po.put("sound", p.sound ?: JSONObject.NULL)
            po.put("volume", p.volume.toDouble())
            po.put("tune", p.tune.toDouble())
            po.put("fine", p.fine.toDouble())
            po.put("pan", p.pan.toDouble())
            po.put("loop", p.loop)
            po.put("choke", p.choke)
            po.put("bass", p.bass)
            arr.put(po)
        }
        o.put("pads", arr)
        return o
    }

    fun fromJson(o: JSONObject, fallbackId: String): Kit {
        val pads = ArrayList<KitPad>()
        val seen = HashSet<Int>()
        val arr = o.optJSONArray("pads")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val po = arr.optJSONObject(i) ?: continue
                val id = po.optInt("id", -1)
                if (id < 1 || id > KIT_MAX_PADS || !seen.add(id)) continue
                // Format lama (v1) tidak punya "w"/"h": h = size, w = -1 (dihitung saat dimuat, KitState.migrate).
                val oldSize = po.optDouble("size", 0.3).toFloat().coerceIn(0.03f, 1.5f)
                val hh = po.optDouble("h", oldSize.toDouble()).toFloat().coerceIn(0.02f, 2f)
                val ww = if (po.has("w")) po.optDouble("w", -1.0).toFloat().let { if (it > 0f) it.coerceIn(0.02f, 2f) else -1f } else -1f
                pads.add(
                    KitPad(
                        id = id,
                        cx = po.optDouble("cx", 0.5).toFloat().coerceIn(-0.5f, 1.5f),
                        cy = po.optDouble("cy", 0.5).toFloat().coerceIn(-0.5f, 1.5f),
                        w = ww,
                        h = hh,
                        image = safeName(if (po.isNull("image")) null else po.optString("image")),
                        sound = safeName(if (po.isNull("sound")) null else po.optString("sound")),
                        volume = po.optDouble("volume", 1.0).toFloat().coerceIn(0f, 1.5f),
                        tune = po.optDouble("tune", 0.0).toFloat().coerceIn(-12f, 12f),
                        fine = po.optDouble("fine", 0.0).toFloat().coerceIn(-100f, 100f),
                        pan = po.optDouble("pan", 0.0).toFloat().coerceIn(-1f, 1f),
                        loop = po.optBoolean("loop", false),
                        choke = po.optBoolean("choke", true),
                        bass = po.optBoolean("bass", false)
                    )
                )
            }
        }
        return Kit(
            id = fallbackId,
            name = o.optString("name", "Kit").ifBlank { "Kit" },
            author = o.optString("author", ""),
            aspect = o.optDouble("aspect", 2.0).toFloat().coerceIn(0.5f, 4f),
            background = safeName(if (o.isNull("background")) null else o.optString("background")),
            bgOpacity = o.optDouble("bgOpacity", 1.0).toFloat().coerceIn(0f, 1f),
            padOpacity = o.optDouble("padOpacity", 1.0).toFloat().coerceIn(0f, 1f),
            chokeAll = o.optBoolean("chokeAll", false),
            pads = pads
        )
    }

    // ------------------------------------------------------- daftar / muat / simpan

    fun save(context: Context, kit: Kit) {
        val d = dir(context, kit.id)
        d.mkdirs()
        val tmp = File(d, "kit.json.tmp")
        val target = File(d, "kit.json")
        tmp.writeText(toJson(kit).toString())
        if (!tmp.renameTo(target)) {
            target.writeText(toJson(kit).toString())
            tmp.delete()
        }
    }

    fun load(context: Context, id: String): Kit? {
        return try {
            val f = File(dir(context, id), "kit.json")
            if (!f.exists()) null else fromJson(JSONObject(f.readText()), id)
        } catch (e: Throwable) {
            null
        }
    }

    fun list(context: Context): List<KitInfo> {
        val dirs = root(context).listFiles() ?: return emptyList()
        val out = ArrayList<KitInfo>()
        for (d in dirs) {
            if (!d.isDirectory) continue
            val k = load(context, d.name) ?: continue
            out.add(KitInfo(k.id, k.name, k.pads.size))
        }
        return out.sortedBy { it.name.lowercase() }
    }

    fun createEmpty(context: Context, name: String, aspect: Float): Kit {
        val kit = Kit(id = newId(), name = name.trim().ifBlank { "Kit Baru" }, aspect = aspect.coerceIn(0.5f, 4f))
        save(context, kit)
        return kit
    }

    fun delete(context: Context, id: String) {
        dir(context, id).deleteRecursively()
    }

    // ------------------------------------------------------------- gambar

    // Decode + perkecil (sisi terpanjang <= maxSide). Alpha (PNG transparan) dipertahankan.
    fun decodeScaled(bytes: ByteArray, maxSide: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > maxSide * 2 || bounds.outHeight / sample > maxSide * 2) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            val longest = maxOf(bmp.width, bmp.height)
            if (longest > maxSide) {
                val scale = maxSide.toFloat() / longest.toFloat()
                val w = maxOf(1, (bmp.width * scale).toInt())
                val h = maxOf(1, (bmp.height * scale).toInt())
                val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
                if (scaled !== bmp) bmp.recycle()
                bmp = scaled
            }
            bmp
        } catch (e: Throwable) {
            null
        }
    }

    // Simpan gambar dari galeri ke folder images kit (PNG kalau ada alpha, selain itu JPEG).
    // Return nama file, atau null kalau bukan gambar valid.
    fun saveImage(context: Context, kitId: String, bytes: ByteArray, maxSide: Int, prefix: String): String? {
        val bmp = decodeScaled(bytes, maxSide) ?: return null
        return try {
            val hasAlpha = bmp.hasAlpha()
            val name = prefix + java.lang.Long.toString(System.nanoTime(), 36) + (if (hasAlpha) ".png" else ".jpg")
            val f = imageFile(context, kitId, name)
            f.parentFile?.mkdirs()
            FileOutputStream(f).use { out ->
                bmp.compress(if (hasAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, out)
            }
            name
        } catch (e: Throwable) {
            null
        } finally {
            bmp.recycle()
        }
    }

    fun decodeImage(context: Context, kitId: String, name: String): ImageBitmap? {
        return try {
            val f = imageFile(context, kitId, name)
            if (!f.exists()) return null
            BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap()
        } catch (e: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------- suara

    fun soundFileName(padId: Int): String = "pad$padId.wav"

    // Simpan WAV kanonik pad. Return nama file.
    fun saveSound(context: Context, kitId: String, padId: Int, wavBytes: ByteArray): String {
        val name = soundFileName(padId)
        val f = soundFile(context, kitId, name)
        f.parentFile?.mkdirs()
        f.writeBytes(wavBytes)
        return name
    }

    // Salin suara pad lama ke pad baru (duplikat pad). Return nama file baru atau null.
    fun copySound(context: Context, kitId: String, fromName: String, toPadId: Int): String? {
        return try {
            val src = soundFile(context, kitId, fromName)
            if (!src.exists()) return null
            val name = soundFileName(toPadId)
            val dst = soundFile(context, kitId, name)
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = true)
            name
        } catch (e: Throwable) {
            null
        }
    }

    // Beri nomor ulang pad sesuai [map] (nomor lama -> baru, lihat Kit.compactMapping). File suara yang
    // bernama kanonik pad<N>.wav ikut diganti nama jadi pad<nomor baru>.wav; nama lain dibiarkan apa adanya.
    // Cara kerja (aman untuk geser banyak pad sekaligus, tidak ada suara yang saling menimpa):
    //   1) tiap file suara yang berpindah dipindah (rename, instan) ke berkas sementara .renum_<N>.tmp;
    //      kalau file itu juga dipakai pad lain, disalin saja;
    //   2) kalau ADA yang gagal, semuanya dikembalikan persis seperti semula dan kit dikembalikan APA ADANYA
    //      (tidak ada nomor yang berubah setengah-setengah);
    //   3) berkas sementara dipindah ke nama akhirnya.
    // Hanya mengubah file di disk + mengembalikan Kit baru (return [kit] yang sama persis bila dibatalkan);
    // kit.json dan engine native diurus pemanggil.
    fun renumberPads(context: Context, kit: Kit, map: Map<Int, Int>): Kit {
        if (map.isEmpty()) return kit
        val dirSounds = soundsDir(context, kit.id)
        val moving = kit.pads.filter { map.containsKey(it.id) && it.sound != null && it.sound == soundFileName(it.id) }
        val movingIds = moving.map { it.id }.toHashSet()
        // Nama file yang dipegang pad lain (tidak ikut ganti nama) tidak boleh tertimpa / dipindah.
        val keptNames = kit.pads.filter { it.id !in movingIds }.mapNotNull { it.sound }.toHashSet()

        val finalName = HashMap<Int, String?>()   // nomor lama -> nama file suara akhir (null = file tidak ada)
        val temps = HashMap<Int, File>()           // nomor lama -> berkas sementara
        val movedFrom = HashMap<Int, File>()       // nomor lama -> file asal yang sudah dipindah (untuk dikembalikan)
        var failed = false
        for (p in moving) {
            val srcName = p.sound!!
            val src = soundFile(context, kit.id, srcName)
            if (!src.exists()) { finalName[p.id] = null; continue }
            val newId = map.getValue(p.id)
            val tmp = File(dirSounds, ".renum_$newId.tmp")
            val ok = try {
                tmp.delete()
                if (srcName in keptNames) {
                    src.copyTo(tmp, overwrite = true)
                } else {
                    if (!src.renameTo(tmp)) {
                        src.copyTo(tmp, overwrite = true)
                        src.delete()
                    }
                    movedFrom[p.id] = src
                }
                true
            } catch (e: Throwable) {
                false
            }
            if (!ok) {
                // Bisa jadi rename sudah terjadi sebelum error: kembalikan pad ini juga.
                if (!src.exists() && tmp.exists()) { try { tmp.renameTo(src) } catch (e2: Throwable) { } } else tmp.delete()
                movedFrom.remove(p.id)
                failed = true
                break
            }
            temps[p.id] = tmp
            var name = soundFileName(newId)
            if (name in keptNames) name = "pad${newId}_" + java.lang.Long.toString(System.nanoTime(), 36) + ".wav"
            finalName[p.id] = name
        }
        if (failed) {
            for ((oldId, tmp) in temps) {
                val back = movedFrom[oldId]
                if (back == null) {
                    tmp.delete()
                } else if (!tmp.renameTo(back)) {
                    try { tmp.copyTo(back, overwrite = true); tmp.delete() } catch (e: Throwable) { }
                }
            }
            return kit
        }
        for ((oldId, tmp) in temps) {
            val dst = soundFile(context, kit.id, finalName[oldId]!!)
            var ok = try { dst.delete(); tmp.renameTo(dst) } catch (e: Throwable) { false }
            if (!ok) ok = try { tmp.copyTo(dst, overwrite = true); true } catch (e: Throwable) { false }
            tmp.delete()
            if (!ok) finalName[oldId] = null
        }
        return kit.copy(pads = kit.pads.map { p ->
            val n = map[p.id] ?: return@map p
            if (p.id in movingIds) p.copy(id = n, sound = finalName[p.id]) else p.copy(id = n)
        })
    }

    // Rapikan nomor pad sebuah kit LANGSUNG DI DISK. Hanya untuk kit yang TIDAK sedang dimuat di engine
    // (mis. sebelum export kit yang tidak aktif).
    fun compactOnDisk(context: Context, id: String) {
        val k = load(context, id) ?: return
        val map = k.compactMapping()
        if (map.isEmpty()) return
        val nk = renumberPads(context, k, map)
        if (nk !== k) save(context, nk)
    }

    // Hapus file gambar/suara yang tidak dipakai lagi (pad dihapus, gambar diganti) supaya
    // file .kitdtxkdg hasil export tidak membawa sampah.
    fun prune(context: Context, kit: Kit) {
        try {
            val usedImages = HashSet<String>()
            kit.background?.let { usedImages.add(it) }
            val usedSounds = HashSet<String>()
            for (p in kit.pads) {
                p.image?.let { usedImages.add(it) }
                p.sound?.let { usedSounds.add(it) }
            }
            imagesDir(context, kit.id).listFiles()?.forEach { if (it.isFile && it.name !in usedImages) it.delete() }
            soundsDir(context, kit.id).listFiles()?.forEach { if (it.isFile && it.name !in usedSounds) it.delete() }
        } catch (e: Throwable) {
            // pembersihan opsional, abaikan kegagalan
        }
    }

    // ------------------------------------------------------- export / import

    // Bungkus 1 kit jadi zip (.kitdtxkdg). Return jumlah file di dalamnya, 0 kalau kit tidak ada.
    fun exportKit(context: Context, kitId: String, out: OutputStream): Int {
        val kit = load(context, kitId) ?: return 0
        prune(context, kit)
        val d = dir(context, kitId)
        var count = 0
        ZipOutputStream(out).use { zip ->
            d.walkTopDown().forEach { f ->
                if (f.isFile && !f.name.endsWith(".tmp")) {
                    val rel = f.relativeTo(d).path.replace(File.separatorChar, '/')
                    zip.putNextEntry(ZipEntry(rel))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    count++
                }
            }
        }
        return count
    }

    // Import file .kitdtxkdg. Selalu jadi kit BARU (id baru) - tidak pernah menimpa kit yang ada.
    // Return id kit baru, atau null kalau file bukan kit yang valid.
    fun importKit(context: Context, input: InputStream): String? {
        val tmp = File(context.cacheDir, "kit_import_" + System.nanoTime())
        tmp.mkdirs()
        try {
            var total = 0L
            val rootPath = tmp.canonicalPath + File.separator
            ZipInputStream(input).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val target = File(tmp, entry.name)
                        // Zip-slip: tolak entry yang mencoba keluar dari folder sementara.
                        if (target.canonicalPath.startsWith(rootPath)) {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = zip.read(buf)
                                    if (n < 0) break
                                    total += n
                                    if (total > MAX_IMPORT_BYTES) throw IOException("Kit terlalu besar")
                                    out.write(buf, 0, n)
                                }
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            // kit.json boleh ada di akar zip, atau di 1 folder (kalau pembuat men-zip foldernya).
            val manifest = tmp.walkTopDown().firstOrNull { it.isFile && it.name == "kit.json" } ?: return null
            val contentRoot = manifest.parentFile ?: return null
            val parsed = fromJson(JSONObject(manifest.readText()), "")
            val newId = newId()
            val dest = dir(context, newId)
            dest.mkdirs()
            contentRoot.copyRecursively(dest, overwrite = true)

            // Suara non-WAV (mp3/ogg/dll) diubah jadi WAV kanonik supaya sama seperti kit buatan app.
            val fixedPads = ArrayList<KitPad>()
            for (p in parsed.pads) {
                var sound = p.sound
                if (sound != null && !sound.lowercase().endsWith(".wav")) {
                    val src = soundFile(context, newId, sound)
                    val converted = if (src.exists()) SampleImport.toCanonical(context, src.readBytes()) else null
                    sound = if (converted != null) {
                        val wavName = saveSound(context, newId, p.id, converted.second)
                        src.delete()
                        wavName
                    } else null
                } else if (sound != null && !soundFile(context, newId, sound).exists()) {
                    sound = null
                }
                var image = p.image
                if (image != null && !imageFile(context, newId, image).exists()) image = null
                fixedPads.add(p.copy(sound = sound, image = image))
            }
            var bg = parsed.background
            if (bg != null && !imageFile(context, newId, bg).exists()) bg = null
            var kit = parsed.copy(id = newId, pads = fixedPads, background = bg)
            // Kit yang diimpor (mis. dari repo online) dengan nomor loncat dirapikan sekalian.
            val renum = kit.compactMapping()
            if (renum.isNotEmpty()) kit = renumberPads(context, kit, renum)
            save(context, kit)
            prune(context, kit)
            return newId
        } finally {
            tmp.deleteRecursively()
        }
    }
}
