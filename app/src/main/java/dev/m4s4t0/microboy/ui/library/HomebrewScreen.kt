package dev.m4s4t0.microboy.ui.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.m4s4t0.microboy.MicroBoyApp
import dev.m4s4t0.microboy.ui.common.CoverRefresh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** One game from MicroBoy's homebrew catalog (docs/homebrew/catalog.json on the website). */
data class HomebrewGame(
    val title: String,
    val description: String,
    val file: String,
    val cover: String,
    val sha1: String,
    val size: Long,
)

private const val SITE = "https://m4s4t0-v01d.github.io/MicroBoy/"

private fun download(path: String, limit: Long = 32L * 1024 * 1024): ByteArray {
    val conn = URL(SITE + path).openConnection() as HttpURLConnection
    conn.connectTimeout = 10_000
    conn.readTimeout = 20_000
    try {
        if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
        val bytes = conn.inputStream.use { it.readBytes() }
        if (bytes.size > limit) error("Too large")
        return bytes
    } finally {
        conn.disconnect()
    }
}

private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content ?: ""

private fun parseCatalog(text: String): List<HomebrewGame> =
    Json.parseToJsonElement(text).jsonObject["games"]!!.jsonArray.map {
        val o = it.jsonObject
        HomebrewGame(o.s("title"), o.s("description"), o.s("file"), o.s("cover"), o.s("sha1"),
            (o["size"] as? JsonPrimitive)?.longOrNull ?: 0)
    }

/**
 * Free games made for MicroBoy, downloaded from the MicroBoy website. Each
 * download is checked against the catalog's SHA-1 before it joins the library.
 */
@Composable
fun HomebrewScreen(onBack: () -> Unit, onMessage: (String) -> Unit) {
    val app = MicroBoyApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var games by remember { mutableStateOf<List<HomebrewGame>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val covers = remember { mutableStateMapOf<String, Bitmap>() }
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    val library by app.library.games.collectAsState()

    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) { runCatching { parseCatalog(String(download("homebrew/catalog.json"))) } }
        result.onSuccess { list ->
            games = list
            list.forEach { g ->
                launch(Dispatchers.IO) {
                    runCatching { download(g.cover, 2L * 1024 * 1024) }.getOrNull()?.let { b ->
                        BitmapFactory.decodeByteArray(b, 0, b.size)?.let { covers[g.sha1] = it }
                    }
                }
            }
        }.onFailure { error = "Couldn't load the homebrew list. Check your connection and try again." }
    }

    fun install(g: HomebrewGame) {
        busy[g.sha1] = true
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = download(g.file)
                    val sha = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
                    if (sha != g.sha1) error("The download didn't match. Try again later.")
                    val dir = File(context.cacheDir, "homebrew").apply { mkdirs() }
                    val tmp = File(dir, "${g.title}.gba")
                    tmp.writeBytes(bytes)
                    app.library.addFiles(listOf(Uri.fromFile(tmp)), importCopy = true)
                    tmp.delete()
                    covers[g.sha1]?.let { bmp ->
                        app.storage.coverFile(g.sha1).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        CoverRefresh.version++
                    }
                    "${g.title} added to your library"
                }.getOrElse { it.message ?: "Download failed" }
            }
            busy[g.sha1] = false
            onMessage(msg)
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Column {
                    Text("MicroBoy homebrew", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Free games made for MicroBoy. Downloaded from the MicroBoy website.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when {
                error != null -> Text(error!!, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error)
                games == null -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(games!!, key = { it.sha1 }) { g ->
                        val owned = library.any { it.id == g.sha1 }
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                val bmp = covers[g.sha1]
                                Box(Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                    if (bmp != null) {
                                        Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                                            filterQuality = FilterQuality.None)
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(g.title, style = MaterialTheme.typography.titleMedium)
                                    Text(g.description, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.height(6.dp))
                                    when {
                                        owned -> Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                            Spacer(Modifier.width(4.dp))
                                            Text("In your library", style = MaterialTheme.typography.labelLarge)
                                        }
                                        busy[g.sha1] == true -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                        else -> Button(onClick = { install(g) }) { Text("Get · ${sizeLabel(g.size)}") }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        OutlinedButton(onClick = onBack, Modifier.padding(16.dp)) { Text("Done") }
                    }
                }
            }
        }
    }
}

private fun sizeLabel(bytes: Long) = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
