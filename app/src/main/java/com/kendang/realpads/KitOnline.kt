package com.kendang.realpads

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// Sumber kit online: folder "kits" di repo GitHub publik (lihat folder DTX-KIT-STORE yang ikut
// di paket ini). Sama seperti preset online: TIDAK butuh index/manifest - cukup upload file
// .kitdtxkdg ke folder kits/ dan aplikasi langsung melihatnya lewat GitHub Contents API.
// Gambar pratinjau opsional: taruh file gambar dengan NAMA SAMA di folder yang sama
// (mis. "Kit Rock.kitdtxkdg" + "Kit Rock.png").
object KitOnlineRepo {
    // Ganti 3 baris ini kalau repo kit dipindah / dinamai lain.
    private const val REPO_OWNER = "siapagatau"
    private const val REPO_NAME = "DTX-KIT-STORE"
    private const val REPO_PATH = "kits"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    data class OnlineKit(
        val name: String,
        val downloadUrl: String,
        val sizeBytes: Long,
        val previewUrl: String?
    )

    class RateLimitException(val resetAtMs: Long) : Exception("Batas request API GitHub habis sementara")

    private data class CacheEntry(val kits: List<OnlineKit>, val fetchedAtMs: Long)
    @Volatile private var cache: CacheEntry? = null
    private val thumbCache = HashMap<String, ImageBitmap>()

    fun peekFresh(): List<OnlineKit>? {
        val c = cache ?: return null
        return if (System.currentTimeMillis() - c.fetchedAtMs < CACHE_TTL_MS) c.kits else null
    }

    fun listKits(forceRefresh: Boolean = false): List<OnlineKit> {
        if (!forceRefresh) peekFresh()?.let { return it }
        val apiUrl = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/contents/$REPO_PATH"
        val arr = JSONArray(String(httpGetBytes(apiUrl, acceptJson = true), Charsets.UTF_8))
        val previews = HashMap<String, String>()   // nama dasar (huruf kecil) -> url gambar
        val kitFiles = ArrayList<Triple<String, String, Long>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("type") != "file") continue
            val name = o.optString("name")
            val url = o.optString("download_url")
            if (url.isBlank()) continue
            val lower = name.lowercase()
            val ext = lower.substringAfterLast('.', "")
            val base = lower.substringBeforeLast('.')
            when (ext) {
                KIT_EXTENSION -> kitFiles.add(Triple(name, url, o.optLong("size", 0L)))
                "png", "jpg", "jpeg", "webp" -> previews[base] = url
            }
        }
        val result = kitFiles.map { (name, url, size) ->
            OnlineKit(name, url, size, previews[name.lowercase().substringBeforeLast('.')])
        }.sortedBy { it.name.lowercase() }
        cache = CacheEntry(result, System.currentTimeMillis())
        return result
    }

    fun formatRateLimitMessage(resetAtMs: Long): String {
        val minutesLeft = ((resetAtMs - System.currentTimeMillis()) / 60_000L + 1).coerceAtLeast(1)
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(resetAtMs))
        return "Batas request API GitHub (tanpa login) habis sementara. Coba lagi sekitar $minutesLeft menit lagi (\u2248 $timeStr)."
    }

    // Unduh langsung ke file (streaming, hemat memori untuk kit besar).
    fun downloadToFile(urlStr: String, target: File) {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "DTX-Android-App")
            val code = conn.responseCode
            if (code !in 200..299) throw Exception("HTTP $code")
            conn.inputStream.use { input -> FileOutputStream(target).use { out -> input.copyTo(out) } }
        } finally {
            conn.disconnect()
        }
    }

    fun fetchThumb(url: String): ImageBitmap? {
        synchronized(thumbCache) { thumbCache[url]?.let { return it } }
        return try {
            val bytes = httpGetBytes(url)
            val bmp = KitStorage.decodeScaled(bytes, 200)?.asImageBitmap()
            if (bmp != null) {
                synchronized(thumbCache) {
                    if (thumbCache.size > 40) thumbCache.clear()
                    thumbCache[url] = bmp
                }
            }
            bmp
        } catch (e: Throwable) {
            null
        }
    }

    private fun httpGetBytes(urlStr: String, acceptJson: Boolean = false): ByteArray {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "DTX-Android-App")
            if (acceptJson) conn.setRequestProperty("Accept", "application/vnd.github+json")
            val code = conn.responseCode
            if (code !in 200..299) {
                if (code == 403 && conn.getHeaderField("X-RateLimit-Remaining") == "0") {
                    val resetSec = conn.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()
                    throw RateLimitException(if (resetSec != null) resetSec * 1000L else System.currentTimeMillis() + 60_000L)
                }
                if (code == 404) throw Exception("Repo/folder kit belum ada (404)")
                throw Exception("HTTP $code")
            }
            val out = ByteArrayOutputStream()
            conn.inputStream.use { it.copyTo(out) }
            return out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }
}

private sealed class KitOnlineUi {
    data object Loading : KitOnlineUi()
    data class Error(val message: String, val rateLimit: Boolean) : KitOnlineUi()
    data class Loaded(val kits: List<KitOnlineRepo.OnlineKit>) : KitOnlineUi()
}

// Dialog daftar kit online. Mandiri (state di dalam): tap kit -> unduh -> impor -> onImported(idKitBaru).
@Composable
fun KitOnlineDialog(onImported: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<KitOnlineUi>(KitOnlineUi.Loading) }
    var downloading by remember { mutableStateOf<String?>(null) }

    fun load(force: Boolean) {
        if (!force) {
            val fresh = KitOnlineRepo.peekFresh()
            if (fresh != null) {
                state = KitOnlineUi.Loaded(fresh)
                return
            }
        }
        state = KitOnlineUi.Loading
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { KitOnlineRepo.listKits(force) } }
            val next: KitOnlineUi = r.fold(
                onSuccess = { KitOnlineUi.Loaded(it) },
                onFailure = { e ->
                    if (e is KitOnlineRepo.RateLimitException) {
                        KitOnlineUi.Error(KitOnlineRepo.formatRateLimitMessage(e.resetAtMs), true)
                    } else {
                        KitOnlineUi.Error(e.message ?: "tidak diketahui", false)
                    }
                }
            )
            state = next
        }
    }

    fun download(item: KitOnlineRepo.OnlineKit) {
        downloading = item.name
        val app: Context = context.applicationContext
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(app.cacheDir, "kit_dl_" + System.nanoTime() + ".zip")
                    try {
                        KitOnlineRepo.downloadToFile(item.downloadUrl, tmp)
                        KitState.importFromFileBlocking(app, tmp) ?: throw Exception("File kit tidak valid")
                    } finally {
                        tmp.delete()
                    }
                }
            }
            downloading = null
            r.fold(
                onSuccess = { id -> onImported(id) },
                onFailure = { e ->
                    Toast.makeText(context, "Gagal mengunduh kit (${e.message ?: "cek koneksi internet"})", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    LaunchedEffect(Unit) { load(false) }

    AlertDialog(
        onDismissRequest = { if (downloading == null) onDismiss() },
        containerColor = PanelColor,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Kit Online", color = PadTextColor, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { load(true) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Muat ulang daftar", tint = TextDim, modifier = Modifier.size(18.dp))
                }
            }
        },
        text = {
            Box(modifier = Modifier.width(310.dp).heightIn(min = 120.dp, max = 400.dp)) {
                when (val s = state) {
                    is KitOnlineUi.Loading -> Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = ThemeState.accentColor)
                        Spacer(Modifier.size(10.dp))
                        Text("Mengambil daftar kit...", color = TextDim, fontSize = 12.sp)
                    }
                    is KitOnlineUi.Error -> Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        Text(
                            if (s.rateLimit) "Kena limit request GitHub." else "Gagal mengambil daftar kit.",
                            color = PadTextColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            if (s.rateLimit) s.message else "Cek koneksi internet, lalu coba lagi (${s.message})",
                            color = TextDim, fontSize = 11.sp
                        )
                    }
                    is KitOnlineUi.Loaded -> {
                        if (s.kits.isEmpty()) {
                            Text("Belum ada kit di repo ini.", color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp))
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(s.kits) { k ->
                                    OnlineKitRow(
                                        item = k,
                                        isDownloading = downloading == k.name,
                                        disabled = downloading != null,
                                        onClick = { download(k) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (downloading == null) onDismiss() },
                colors = ButtonDefaults.buttonColors(containerColor = CtrlPanelColor, contentColor = PadTextColor)
            ) {
                Text("TUTUP", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            }
        }
    )
}

@Composable
private fun OnlineKitRow(
    item: KitOnlineRepo.OnlineKit,
    isDownloading: Boolean,
    disabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(CtrlPanelColor)
            .clickable(enabled = !disabled, onClick = onClick)
            .padding(8.dp)
    ) {
        KitThumb(item.previewUrl)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.name.substringBeforeLast('.'),
                color = if (disabled && !isDownloading) TextDim else PadTextColor,
                fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 2
            )
            val mb = item.sizeBytes / (1024.0 * 1024.0)
            if (item.sizeBytes > 0) {
                Text(
                    if (mb < 1) "${(item.sizeBytes / 1024.0).roundToInt()} KB" else "%.1f MB".format(mb),
                    color = TextDim, fontSize = 10.sp
                )
            }
        }
        if (isDownloading) {
            CircularProgressIndicator(color = ThemeState.accentColor, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        } else {
            Icon(
                Icons.Filled.CloudDownload,
                contentDescription = "Unduh ${item.name}",
                tint = if (disabled) TextDim else ThemeState.accentColor,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun KitThumb(url: String?) {
    var bmp by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        if (url != null) bmp = withContext(Dispatchers.IO) { KitOnlineRepo.fetchThumb(url) }
    }
    Box(
        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)).background(PanelColor),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) {
            Image(bitmap = b, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Filled.Widgets, contentDescription = null, tint = TextDim, modifier = Modifier.size(22.dp))
        }
    }
}
