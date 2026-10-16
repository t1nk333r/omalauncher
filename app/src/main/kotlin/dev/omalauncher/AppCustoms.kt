package dev.omalauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Your own names and icons for apps, by their [dev.omalauncher.ui.AppHit] key: the drawer, home and the dock show
 * them instead of the app's. Icons are kept as PNGs in the app's files, 192px at most.
 */
class AppCustoms(
    private val context: Context,
    scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "app-icons")

    var names by mutableStateOf(readNames(prefs.getString(NAMES, null)))
        private set

    /** Loaded once at start, then as they're set. */
    val icons = mutableStateMapOf<String, ImageBitmap>()

    init {
        scope.launch(Dispatchers.IO) {
            for (file in dir.listFiles().orEmpty()) {
                val key = keyOf(file) ?: continue
                BitmapFactory.decodeFile(file.path)?.let { icons[key] = it.asImageBitmap() }
            }
        }
    }

    fun name(key: String) = names[key]

    /** Names the app [key] [name]; blank gives it back its own. */
    fun rename(
        key: String,
        name: String,
    ) {
        val new = if (name.isBlank()) names - key else names + (key to name.trim())
        if (new == names) return
        names = new
        prefs.edit().putString(NAMES, JSONObject(new).toString()).apply()
    }

    /** Makes the image at [uri] the app [key]'s icon; false when it can't be read. */
    suspend fun setIcon(
        key: String,
        uri: Uri,
    ): Boolean {
        val bitmap =
            withContext(Dispatchers.IO) {
                runCatching {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                        val (w, h) = info.size.width to info.size.height
                        val scale = minOf(1f, ICON_PX.toFloat() / maxOf(w, h))
                        decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }.also { bmp ->
                        dir.mkdirs()
                        fileOf(key).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        // The key beside it, since the file is named by a hash of the key.
                        File(dir, fileOf(key).name + ".key").writeText(key)
                    }
                }.onFailure { Log.w(TAG, "cannot use that image as an icon", it) }.getOrNull()
            } ?: return false
        icons[key] = bitmap.asImageBitmap()
        return true
    }

    /** Gives the app [key] back its own icon. */
    fun resetIcon(key: String) {
        icons.remove(key)
        fileOf(key).delete()
        File(dir, fileOf(key).name + ".key").delete()
    }

    private fun fileOf(key: String) = File(dir, sha1(key) + ".png")

    private fun keyOf(png: File): String? = png.takeIf { it.extension == "png" }?.let { File(dir, it.name + ".key") }?.takeIf { it.exists() }?.readText()

    private companion object {
        const val NAMES = "app_names"
        const val ICON_PX = 192
        const val TAG = "app-customs"

        fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

internal fun readNames(json: String?): Map<String, String> =
    runCatching {
        val o = JSONObject(json ?: return emptyMap())
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())
