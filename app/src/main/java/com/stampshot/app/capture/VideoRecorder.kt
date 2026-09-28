package com.stampshot.app.capture

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import com.stampshot.app.data.AppSettings
import com.stampshot.app.data.PhotoIndex
import com.stampshot.app.stamp.StampInfo
import java.io.File
import java.util.concurrent.Executor

/**
 * Video recording through CameraX VideoCapture. The raw clip lands in app
 * cache first; VideoStampProcessor then burns the timestamp stamp into every
 * frame and publishes the finished mp4 to Movies/StampShot.
 */
class VideoRecorder(private val context: Context) {

    data class RecordingResult(val rawFile: File?, val error: String?)

    fun buildUseCase(videoQuality: Int): VideoCapture<Recorder> {
        val quality = when (videoQuality) {
            AppSettings.VIDEO_SD -> Quality.SD
            AppSettings.VIDEO_FHD -> Quality.FHD
            else -> Quality.HD
        }
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(quality))
            .build()
        return VideoCapture.withOutput(recorder)
    }

    /**
     * Starts recording into a cache file. The returned Recording must be
     * stopped by the caller; [onFinished] fires on the caller's executor with
     * the raw file path (or error) after finalize.
     */
    fun start(
        videoCapture: VideoCapture<Recorder>,
        withAudio: Boolean,
        executor: Executor,
        onFinished: (RecordingResult) -> Unit,
    ): Recording {
        val rawDir = File(context.cacheDir, "video_raw").apply { mkdirs() }
        rawDir.listFiles()?.forEach { it.delete() }
        val rawFile = File(rawDir, "rec_${System.currentTimeMillis()}.mp4")

        val output = FileOutputOptions.Builder(rawFile).build()
        val pending = videoCapture.output.prepareRecording(context, output)
        if (withAudio) pending.withAudioEnabled()

        return pending.start(executor) { event ->
            if (event is VideoRecordEvent.Finalize) {
                if (event.hasError() || !rawFile.exists() || rawFile.length() == 0L) {
                    rawFile.delete()
                    onFinished(RecordingResult(null, event.error?.let { "code $it" } ?: "empty file"))
                } else {
                    onFinished(RecordingResult(rawFile, null))
                }
            }
        }
    }

    /** Inserts a finished mp4 into Movies/StampShot and records it in the index. */
    fun publish(processed: File, info: StampInfo): Uri {
        val displayName = stampShotFileName(
            info.sessionName, info.photoNumber, info.timestampMillis, "mp4",
        )
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/StampShot")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val uri = resolver.insert(collection, values) ?: error("MediaStore insert failed")
        resolver.openOutputStream(uri, "w")?.use { out ->
            processed.inputStream().use { it.copyTo(out) }
        } ?: error("Could not open $uri for writing")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "StampShot",
            )
            dir.mkdirs()
            val dest = File(dir, displayName)
            val srcPath = queryDataColumn(uri)
            if (srcPath != null && srcPath != dest.absolutePath) {
                File(srcPath).renameTo(dest)
                val update = ContentValues().apply { put(MediaStore.Video.Media.DATA, dest.absolutePath) }
                resolver.update(uri, update, null, null)
            }
        } else {
            val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        }

        PhotoIndex(context).put(
            PhotoIndex.Record(
                displayName = displayName,
                session = info.sessionName ?: "General",
                number = info.photoNumber ?: 0,
                timestampMillis = info.timestampMillis,
                originalPath = null,
                latitude = info.latitude,
                longitude = info.longitude,
                address = info.address,
                city = info.city,
                note = info.note,
                showAddress = info.showAddress,
                showGps = info.showGps,
            ),
        )
        return uri
    }

    private fun queryDataColumn(uri: Uri): String? {
        val projection = arrayOf(MediaStore.Video.Media.DATA)
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }
}
