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
)

/** Reads StampShot photos from MediaStore and decodes thumbnails without Coil. */
class GalleryStore(private val context: Context) {

    suspend fun loadPhotos(): List<GalleryPhoto> = withContext(Dispatchers.IO) {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
        )
        val selection: String
        val args: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            args = arrayOf("Pictures/StampShot%")
        } else {
            selection = "${MediaStore.Images.Media.DATA} LIKE ?"
            args = arrayOf("%/Pictures/StampShot/%")
        }
        val index = PhotoIndex(context)
        val records = index.all().associateBy { it.displayName }
        val photos = mutableListOf<GalleryPhoto>()
        context.contentResolver.query(
            collection, projection, selection, args,
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val name = c.getString(nameCol) ?: continue
                val uri = ContentUris.withAppendedId(collection, id)
                val session = records[name]?.session ?: sessionFromFileName(name)
                photos.add(GalleryPhoto(uri, name, session, c.getLong(dateCol)))
            }
        }
        index.prune(photos.map { it.displayName }.toSet())
        photos
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
