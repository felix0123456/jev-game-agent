package com.jev.gameagent.vision

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Base64
import java.io.ByteArrayOutputStream

object FrameCompressor {

    /**
     * Resizes the bitmap preserving aspect ratio so its largest dimension is at most [maxDimension].
     */
    fun downscaleBitmap(source: Bitmap, maxDimension: Int = 640): Bitmap {
        val width = source.width
        val height = source.height

        if (width <= maxDimension && height <= maxDimension) {
            return source
        }

        val scale = if (width >= height) {
            maxDimension.toFloat() / width
        } else {
            maxDimension.toFloat() / height
        }

        val matrix = Matrix().apply {
            postScale(scale, scale)
        }

        return Bitmap.createBitmap(source, 0, 0, width, height, matrix, true)
    }

    /**
     * Compresses the bitmap to JPEG or WebP and encodes it as Base64 string for Jev API state.
     */
    fun toBase64Jpeg(bitmap: Bitmap, quality: Int = 75): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        val byteArray = outputStream.toByteArray()
        return "data:image/jpeg;base64," + Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }
}
