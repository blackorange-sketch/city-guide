package com.cityguide

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Підготовка фото для пошуку: файл для камери та стиснення до невеликого JPEG. */
object PhotoHelper {
    private const val MAX_SIDE = 1024
    private const val JPEG_QUALITY = 82

    /** Тимчасовий файл у кеші, куди камера запише знімок (віддається через FileProvider). */
    fun newCameraUri(context: Context): Uri {
        val dir = File(context.cacheDir, "photos").apply { mkdirs() }
        // Старі знімки прибираємо
        dir.listFiles()?.forEach { it.delete() }
        val file = File.createTempFile("shot", ".jpg", dir)
        return FileProvider.getUriForFile(context, context.packageName + ".files", file)
    }

    /** Читає зображення, зменшує довшу сторону до [MAX_SIDE] пікселів і стискає в JPEG. */
    fun loadJpeg(context: Context, uri: Uri): ByteArray? = try {
        val bitmap = decode(context, uri) ?: return null
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
    } catch (e: Exception) {
        null
    }

    private fun decode(context: Context, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder сам враховує поворот із EXIF
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = MAX_SIDE.toFloat() / max(w, h)
                if (scale < 1f) decoder.setTargetSize((w * scale).roundToInt(), (h * scale).roundToInt())
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }
}
