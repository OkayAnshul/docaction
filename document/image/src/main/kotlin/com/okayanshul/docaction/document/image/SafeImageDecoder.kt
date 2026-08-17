package com.okayanshul.docaction.document.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream

/**
 * Decodes user-supplied images without trusting anything the file says about itself.
 *
 * Every out-of-memory crash in an app like this traces to allocating from a declared
 * dimension. A 30,000 × 30,000 PNG is a few hundred kilobytes on disk and 3.6 GB decoded at
 * `ARGB_8888` — a trivially constructed hostile input. So bounds are always read first, a
 * sample size is always computed, and the result is always capped.
 *
 * See docs/12-privacy-security.md § Input limits.
 */
class SafeImageDecoder(
    /** Long edge ceiling. Well above what the recogniser needs, far below anything dangerous. */
    private val maxEdgePx: Int = 3072,
) {

    sealed interface Result {
        data class Decoded(val bitmap: Bitmap) : Result
        data class Rejected(val why: String) : Result
    }

    /**
     * @param openStream opens a fresh stream each time it is called. Decoding needs three
     *   passes — bounds, pixels, EXIF — and streams are not rewindable. Taking a provider
     *   rather than a `ContentResolver` also keeps this class independent of how the image
     *   was addressed, which matters because images reach us as `content://` from the photo
     *   picker but as plain files when a PDF page has been rasterised.
     */
    fun decode(openStream: () -> InputStream): Result {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val opened = runCatching { openStream().use { BitmapFactory.decodeStream(it, null, bounds) } }
        if (opened.isFailure) return Result.Rejected("couldn't open this image")

        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return Result.Rejected("this doesn't look like an image")
        if (width.toLong() * height > MAX_SOURCE_PIXELS) {
            return Result.Rejected("this image is too large to process safely")
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(width, height)
            // The recogniser does not use alpha, and RGB_565 halves the allocation.
            inPreferredConfig = Bitmap.Config.RGB_565
        }

        val decoded = runCatching { openStream().use { BitmapFactory.decodeStream(it, null, options) } }
            .getOrNull()
            ?: return Result.Rejected("couldn't read this image")

        val rotation = runCatching { openStream().use(::rotationOf) }.getOrNull() ?: 0
        return Result.Decoded(if (rotation == 0) decoded else rotate(decoded, rotation))
    }

    /** Smallest power-of-two reduction that brings the long edge under the ceiling. */
    internal fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width, height) / sample > maxEdgePx) sample *= 2
        return sample
    }

    /**
     * Camera photos routinely carry rotation in EXIF while the pixels stay landscape.
     * Skipping this silently produces sideways text and unusable recognition. Screenshots
     * carry no EXIF at all, which is why the default is "no rotation" rather than a guess.
     */
    private fun rotationOf(input: InputStream): Int =
        when (ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }

    private companion object {
        /** ~130 megapixels. Beyond this the file is hostile, not merely large. */
        const val MAX_SOURCE_PIXELS = 130_000_000L
    }
}
