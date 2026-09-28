package com.stampshot.app.gallery

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import com.stampshot.app.data.PhotoIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GalleryPhoto(
    val uri: Uri,
    val displayName: String,
    val session: String,
    val dateAdded: Long,
    val isVideo: Boolean = false,
)

/** Reads StampShot photos from MediaStore and decodes thumbnails without Coil. */
class GalleryStore(private val context: Context) {

    suspend fun loadPhotos(): List<GalleryPhoto> = withContext(Dispatchers.IO) {
        val index = PhotoIndex(context)
        val records = index.all().associateBy { it.displayName }
        val photos = mutableListOf<GalleryPhoto>()
        photos += queryCollection(imagesCollection(), "Pictures/StampShot", isVideo = false, records)
        photos += queryCollection(videoCollection(), "Movies/StampShot", isVideo = true, records)
        photos.sortByDescending { it.dateAdded }
        index.prune(photos.map { it.displayName }.toSet())
        photos
    }

    private fun imagesCollection() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    private fun videoCollection() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }

    private fun queryCollection(
        collection: Uri,
        relativeDir: String,
        isVideo: Boolean,
        records: Map<String, PhotoIndex.Record>,
    ): List<GalleryPhoto> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        val selection: String
        val args: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            args = arrayOf("$relativeDir%")
        } else {
            selection = "${MediaStore.MediaColumns.DATA} LIKE ?"
            args = arrayOf("%/$relativeDir/%")
        }
        val out = mutableListOf<GalleryPhoto>()
        context.contentResolver.query(
            collection, projection, selection, args,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val name = c.getString(nameCol) ?: continue
                val uri = ContentUris.withAppendedId(collection, id)
                val session = records[name]?.session ?: sessionFromFileName(name)
                out.add(GalleryPhoto(uri, name, session, c.getLong(dateCol), isVideo))
            }
        }
        return out
    }

    suspend fun loadThumbnail(uri: Uri, size: Int): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(uri, Size(size, size), null)
            } else {
                decodeSampled(uri, size)
            }
        }.getOrNull()
    }

    suspend fun loadFull(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri).use { input ->
                BitmapFactory.decodeStream(input)
            }
        }.getOrNull()
    }

    suspend fun delete(photo: GalleryPhoto): Boolean = withContext(Dispatchers.IO) {
        val rows = runCatching {
            context.contentResolver.delete(photo.uri, null, null)
        }.getOrDefault(0)
        if (rows > 0) {
            PhotoIndex(context).remove(photo.displayName)
            true
        } else false
    }

    private fun decodeSampled(uri: Uri, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }

    /** Recovers a displayable session name from `StampShot_<slug>_NNN_*.jpg`. */
    fun sessionFromFileName(name: String): String {
        val m = Regex("^StampShot_(.+)_\\d{3,}_\\d{8}_\\d{6}").find(name)
        return m?.groupValues?.get(1)?.replace('-', ' ') ?: "General"
    }
}
