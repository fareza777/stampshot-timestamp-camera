package com.stampshot.app.capture

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.exifinterface.media.ExifInterface
import com.stampshot.app.data.PhotoIndex
import com.stampshot.app.stamp.StampInfo
import com.stampshot.app.stamp.StampOptions
import com.stampshot.app.stamp.StampRenderer
import com.stampshot.app.stamp.StampStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Date
import kotlin.math.max
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Capture → rotate → stamp → MediaStore(Pictures/StampShot) → EXIF → index.
 * When the burned-in stamp carries location info, an unstamped original is kept
 * in app-private storage so Private Share can re-render redacted variants.
 */
class PhotoCapture(private val context: Context) {

    private val index = PhotoIndex(context)

    data class SavedPhoto(val uri: Uri, val displayName: String, val number: Int)

    suspend fun capture(
        imageCapture: ImageCapture,
        executor: Executor,
        info: StampInfo,
        style: StampStyle,
        keepOriginal: Boolean,
        options: StampOptions = StampOptions(),
        mirror: Boolean = false,
        maxDim: Int = 0,
        jpegQuality: Int = 92,
    ): SavedPhoto = withContext(Dispatchers.IO) {
        val raw = File(context.cacheDir, "cap_${System.currentTimeMillis()}.jpg")
        try {
            withTimeout(45_000) { takePicture(imageCapture, raw, executor) }

            var captured = decodeRotated(raw) ?: error("Could not decode captured photo")
            if (mirror) {
                val m = Matrix().apply { postScale(-1f, 1f) }
                captured = Bitmap.createBitmap(captured, 0, 0, captured.width, captured.height, m, true)
            }
            if (maxDim > 0 && max(captured.width, captured.height) > maxDim) {
                val scale = maxDim.toFloat() / max(captured.width, captured.height)
                captured = Bitmap.createScaledBitmap(
                    captured,
                    (captured.width * scale).toInt(),
                    (captured.height * scale).toInt(),
                    true,
                )
            }
            val stamped = StampRenderer.render(captured, info, style, options)

            val displayName = buildFileName(info)
            val uri = saveToMediaStore(displayName, stamped, jpegQuality)

            val keep = keepOriginal && info.hasSensitiveContent()
            var originalPath: String? = null
            if (keep) {
                val origFile = index.newOriginalFile(displayName)
                origFile.outputStream().use { captured.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                originalPath = index.relativeOriginalPath(origFile)
            }
            if (stamped !== captured) stamped.recycle()
            captured.recycle()

            writeExif(uri, info)

            index.put(
                PhotoIndex.Record(
                    displayName = displayName,
                    session = info.sessionName ?: "General",
                    number = info.photoNumber ?: 0,
                    timestampMillis = info.timestampMillis,
                    originalPath = originalPath,
                    latitude = info.latitude,
                    longitude = info.longitude,
                    altitude = info.altitude,
                    address = info.address,
                    city = info.city,
                    note = info.note,
                    activity = info.activity,
                    personName = info.personName,
                    elementsJson = info.elements.toJson(),
                    addressMode = info.addressMode.name,
                ),
            )
            SavedPhoto(uri, displayName, info.photoNumber ?: 0)
        } finally {
            raw.delete()
        }
    }

    private suspend fun takePicture(
        imageCapture: ImageCapture,
        dest: File,
        executor: Executor,
    ): Unit = suspendCancellableCoroutine { cont ->
        val options = ImageCapture.OutputFileOptions.Builder(dest).build()
        imageCapture.takePicture(
            options,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onError(exception: ImageCaptureException) {
                    if (cont.isActive) cont.resumeWithException(exception)
                }
            },
        )
    }

    private fun decodeRotated(file: File): Bitmap? {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val exif = ExifInterface(file)
        val matrix = Matrix()
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun buildFileName(info: StampInfo): String =
        stampShotFileName(info.sessionName, info.photoNumber, info.timestampMillis, "jpg")

    private fun saveToMediaStore(displayName: String, bitmap: Bitmap, jpegQuality: Int): Uri {
        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/StampShot")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection, values) ?: error("MediaStore insert failed")
        resolver.openOutputStream(uri, "w")?.use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)) error("JPEG compress failed")
        } ?: error("Could not open $uri for writing")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Legacy path: relocate the row's file into Pictures/StampShot so the folder exists.
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "StampShot")
            dir.mkdirs()
            val dest = File(dir, displayName)
            val srcPath = queryDataColumn(uri)
            if (srcPath != null && srcPath != dest.absolutePath) {
                File(srcPath).renameTo(dest)
                val update = ContentValues().apply { put(MediaStore.Images.Media.DATA, dest.absolutePath) }
                resolver.update(uri, update, null, null)
            }
        } else {
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        }
        return uri
    }

    private fun queryDataColumn(uri: Uri): String? {
        val projection = arrayOf(MediaStore.Images.Media.DATA)
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    private fun writeExif(uri: Uri, info: StampInfo) {
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                val stamp = StampInfo.EXIF_FMT.format(Date(info.timestampMillis))
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, stamp)
                exif.setAttribute(ExifInterface.TAG_DATETIME, stamp)
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                if (info.elements.gps.on && info.latitude != null && info.longitude != null) {
                    exif.setLatLong(info.latitude, info.longitude)
                }
                exif.saveAttributes()
            }
        }
    }
}
