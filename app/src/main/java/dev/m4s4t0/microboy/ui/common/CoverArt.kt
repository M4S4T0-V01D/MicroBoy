package dev.m4s4t0.microboy.ui.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.m4s4t0.microboy.MicroBoyApp
import dev.m4s4t0.microboy.data.Game
import dev.m4s4t0.microboy.data.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private object BitmapCache {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun load(file: File): Bitmap? {
        if (!file.exists()) return null
        val key = "${file.path}@${file.lastModified()}"
        cache.get(key)?.let { return it }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, opts)
        var sample = 1
        while (opts.outWidth / (sample * 2) >= 400) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        cache.put(key, bmp)
        return bmp
    }
}

/** Bumped whenever a cover image changes on disk so every card reloads. */
object CoverRefresh {
    var version by mutableIntStateOf(0)
}

/** Which image a game card shows; `refreshKey` forces a reload after changes. */
@Composable
fun CoverArt(game: Game, modifier: Modifier = Modifier, refreshKey: Any? = null) {
    val storage = MicroBoyApp.instance.storage
    val image by produceState<Pair<Bitmap, Boolean>?>(null, game.id, game.lastPlayed, refreshKey, CoverRefresh.version) {
        value = withContext(Dispatchers.IO) {
            BitmapCache.load(storage.coverFile(game.id))?.let { it to false }
                ?: storage.stateSlots(game.id)
                    .filter { it.thumbnail.exists() }
                    .maxByOrNull { it.thumbnail.lastModified() }
                    ?.let { BitmapCache.load(it.thumbnail) }
                    ?.let { it to true }
        }
    }
    Box(modifier.clip(MaterialTheme.shapes.medium)) {
        val img = image
        if (img != null) {
            Image(
                bitmap = img.first.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // Screenshots are pixel art: keep them crisp.
                filterQuality = if (img.second) FilterQuality.None else FilterQuality.Low,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Placeholder(game)
        }
    }
}

@Composable
private fun Placeholder(game: Game) {
    val hue = (game.id.take(6).toLong(16) % 360).toFloat()
    val c1 = Color.hsv(hue, 0.45f, 0.45f)
    val c2 = Color.hsv((hue + 40f) % 360f, 0.55f, 0.25f)
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(c1, c2))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = game.displayTitle,
            color = Color.White.copy(alpha = 0.92f),
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
            maxLines = 4,
            modifier = Modifier.padding(10.dp),
        )
    }
}

/** Loads a save-state thumbnail for the save manager. */
@Composable
fun StateThumbnail(file: File, modified: Long, modifier: Modifier = Modifier) {
    val bmp by produceState<Bitmap?>(null, file.path, modified) {
        value = withContext(Dispatchers.IO) { BitmapCache.load(file) }
    }
    Box(modifier.background(Color(0xFF0E0C16)), contentAlignment = Alignment.Center) {
        bmp?.let {
            Image(
                it.asImageBitmap(), null,
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

fun slotName(slot: Int) = if (slot == Storage.AUTO_SLOT) "Auto" else "Slot $slot"
