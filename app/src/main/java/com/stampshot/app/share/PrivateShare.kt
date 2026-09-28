package com.stampshot.app.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import com.stampshot.app.data.PhotoIndex
import com.stampshot.app.stamp.PrivacyLevel
import com.stampshot.app.stamp.StampInfo
import com.stampshot.app.stamp.StampOptions
import com.stampshot.app.stamp.StampRenderer
import com.stampshot.app.stamp.StampStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shares a photo at the requested privacy level:
 *  - FULL: the MediaStore file as saved (stamp + EXIF GPS if it was on).
 *  - APPROXIMATE: re-rendered on the kept original with city-level address only.
 *  - PRIVATE: re-rendered timestamp-only, no EXIF at all.
 *
 * Re-rendered variants are written to cacheDir/shared and exposed via FileProvider.
 */
object PrivateShare {

    data class ShareResult(
        val launched: Boolean,
        val fellBackToPixelsOnly: Boolean = false,
        val error: String? = null,
    )

    suspend fun share(
        context: Context,
        photoUri: Uri,
        displayName: String,
        level: PrivacyLevel,
        style: StampStyle,
        options: StampOptions = StampOptions(),
    ): ShareResult = withContext(Dispatchers.IO) {
        try {
            when (level) {
                PrivacyLevel.FULL -> {
                    launchShare(context, photoUri)
                    ShareResult(launched = true)
                }
                else -> {
                    val record = PhotoIndex(context).get(displayName)
                    val variant = renderVariant(context, photoUri, displayName, record, level, style, options)
                    launchShare(context, variant)
                    ShareResult(
                        launched = true,
                        fellBackToPixelsOnly = record?.originalPath == null,
                    )
                }
            }
        } catch (e: Exception) {
            ShareResult(launched = false, error = e.message)
        }
    }

    /**
     * Renders a redacted variant. If no clean original was kept (stamp had no
     * location data, or the user disabled originals), falls back to re-encoding
     * the stamped pixels without EXIF — location still can't leak via metadata.
     */
    private fun renderVariant(
        context: Context,
        photoUri: Uri,
        displayName: String,
        record: PhotoIndex.Record?,
        level: PrivacyLevel,
        style: StampStyle,
        options: StampOptions,
    ): Uri {
        val outFile = File(File(context.cacheDir, "shared").apply { mkdirs() },
            "share_${level.name.lowercase()}_${System.currentTimeMillis()}.jpg")

        val originalFile = record?.originalPath?.let { File(context.filesDir, it) }
        val bitmap: Bitmap
        var recycled = false
        if (originalFile != null && originalFile.exists()) {
            val clean = BitmapFactory.decodeFile(originalFile.absolutePath)
                ?: error("Could not decode kept original")
            val info = recordToInfo(record).reduced(level)
            bitmap = StampRenderer.render(clean, info, style, options)
            clean.recycle()
            recycled = true
        } else {
            bitmap = context.contentResolver.openInputStream(photoUri).use { input ->
                BitmapFactory.decodeStream(input)
            } ?: error("Could not decode photo")
        }

        outFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        if (!recycled) bitmap.recycle()
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
    }

    private fun recordToInfo(r: PhotoIndex.Record): StampInfo = StampInfo(
        timestampMillis = r.timestampMillis,
        sessionName = r.session,
        photoNumber = r.number,
        note = r.note,
        address = r.address,
        city = r.city,
        latitude = r.latitude,
        longitude = r.longitude,
        showAddress = r.showAddress,
        showGps = r.showGps,
    )

    private fun launchShare(context: Context, uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share photo").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
