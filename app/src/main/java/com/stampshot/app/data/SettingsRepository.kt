package com.stampshot.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stampshot.app.stamp.DateFormatOption
import com.stampshot.app.stamp.GpsFormat
import com.stampshot.app.stamp.PrivacyLevel
import com.stampshot.app.stamp.StampAlign
import com.stampshot.app.stamp.StampFont
import com.stampshot.app.stamp.StampPosition
import com.stampshot.app.stamp.StampStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.settingsStore by preferencesDataStore(name = "stampshot_settings")

data class AppSettings(
    val style: StampStyle = StampStyle.MINIMAL_CORNER,
    val showAddress: Boolean = true,
    val showGps: Boolean = false,
    val note: String = "",
    val sessions: List<String> = listOf(DEFAULT_SESSION),
    val currentSession: String = DEFAULT_SESSION,
    val counters: Map<String, Int> = mapOf(DEFAULT_SESSION to 0),
    val flashMode: Int = FLASH_OFF,
    val lensFacingBack: Boolean = true,
    val shareLevel: PrivacyLevel = PrivacyLevel.FULL,
    val keepOriginals: Boolean = true,
    // Stamp appearance
    val fontScale: Float = 1f,
    val fontColorArgb: Int = -1,
    val bgColorArgb: Int = -1,
    val textOpacity: Float = 1f,
    val bgOpacity: Float = 0.62f,
    val stampPosition: StampPosition = StampPosition.BOTTOM_LEFT,
    val stampFont: StampFont = StampFont.DEFAULT,
    val stampAlign: StampAlign = StampAlign.LEFT,
    val activity: String = "",
    val personName: String = "",
    val dateFormat: DateFormatOption = DateFormatOption.DAY_MONTH_YEAR,
    val gpsFormat: GpsFormat = GpsFormat.DECIMAL,
    val showNumber: Boolean = true,
    // Camera
    val showGrid: Boolean = false,
    val timerSecs: Int = 0,
    val shutterSound: Boolean = false,
    val touchToCapture: Boolean = false,
    val mirrorFront: Boolean = true,
    val photoMaxDim: Int = 0,
    val volumeKeysCapture: Boolean = true,
    val keepScreenOn: Boolean = true,
    val showSeconds: Boolean = true,
    val time24h: Boolean = true,
    // Video
    val videoQuality: Int = VIDEO_HD,
    val videoAudio: Boolean = true,
) {
    fun nextNumber(): Int = (counters[currentSession] ?: 0) + 1

    companion object {
        const val DEFAULT_SESSION = "General"
        const val FLASH_OFF = 0
        const val FLASH_ON = 1
        const val FLASH_AUTO = 2
        const val VIDEO_SD = 0
        const val VIDEO_HD = 1
        const val VIDEO_FHD = 2
        val VIDEO_QUALITY_OPTIONS = listOf(VIDEO_SD to "480p", VIDEO_HD to "720p", VIDEO_FHD to "1080p")
        val FONT_SCALE_OPTIONS = listOf(0.75f to "Small", 1f to "Medium", 1.3f to "Large")
        val TIMER_OPTIONS = listOf(0 to "Off", 3 to "3s", 5 to "5s", 10 to "10s")
        val RES_OPTIONS = listOf(0 to "Original", 1600 to "1600px", 1200 to "1200px")
        val FONT_COLOR_CHOICES = listOf(
            -1 to "Auto",
            0xFFFFFFFF.toInt() to "White",
            0xFF12161A.toInt() to "Black",
            0xFFFFD54F.toInt() to "Yellow",
            0xFFEF5350.toInt() to "Red",
            0xFF4FC3F7.toInt() to "Cyan",
        )
        val BG_COLOR_CHOICES = listOf(
            -1 to "Auto",
            0xFF101318.toInt() to "Black",
            0xFFFAFBFD.toInt() to "White",
            0x00101318 to "None",
        )
    }
}

class SettingsRepository(private val context: Context) {

    private object K {
        val STYLE = stringPreferencesKey("stamp_style")
        val SHOW_ADDRESS = booleanPreferencesKey("show_address")
        val SHOW_GPS = booleanPreferencesKey("show_gps")
        val NOTE = stringPreferencesKey("note")
        val SESSIONS = stringPreferencesKey("sessions_json")
        val CURRENT_SESSION = stringPreferencesKey("current_session")
        val COUNTERS = stringPreferencesKey("counters_json")
        val FLASH = intPreferencesKey("flash_mode")
        val LENS_BACK = booleanPreferencesKey("lens_back")
        val SHARE_LEVEL = stringPreferencesKey("share_level")
        val KEEP_ORIGINALS = booleanPreferencesKey("keep_originals")
        val FONT_SCALE = floatPreferencesKey("font_scale")
        val FONT_COLOR = intPreferencesKey("font_color")
        val BG_COLOR = intPreferencesKey("bg_color")
        val TEXT_OPACITY = floatPreferencesKey("text_opacity")
        val BG_OPACITY = floatPreferencesKey("bg_opacity")
        val POSITION = stringPreferencesKey("stamp_position")
        val FONT = stringPreferencesKey("stamp_font")
        val ALIGN = stringPreferencesKey("stamp_align")
        val ACTIVITY = stringPreferencesKey("activity")
        val PERSON_NAME = stringPreferencesKey("person_name")
        val DATE_FORMAT = stringPreferencesKey("date_format")
        val GPS_FORMAT = stringPreferencesKey("gps_format")
        val SHOW_NUMBER = booleanPreferencesKey("show_number")
        val SHOW_GRID = booleanPreferencesKey("show_grid")
        val TIMER = intPreferencesKey("timer_secs")
        val SHUTTER_SOUND = booleanPreferencesKey("shutter_sound")
        val TOUCH_CAPTURE = booleanPreferencesKey("touch_capture")
        val MIRROR_FRONT = booleanPreferencesKey("mirror_front")
        val PHOTO_MAX_DIM = intPreferencesKey("photo_max_dim")
        val VOLUME_KEYS = booleanPreferencesKey("volume_keys_capture")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val SHOW_SECONDS = booleanPreferencesKey("show_seconds")
        val TIME_24H = booleanPreferencesKey("time_24h")
        val VIDEO_QUALITY = intPreferencesKey("video_quality")
        val VIDEO_AUDIO = booleanPreferencesKey("video_audio")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data.map { p ->
        AppSettings(
            style = p[K.STYLE]?.let { runCatching { StampStyle.valueOf(it) }.getOrNull() }
                ?: StampStyle.MINIMAL_CORNER,
            showAddress = p[K.SHOW_ADDRESS] ?: true,
            showGps = p[K.SHOW_GPS] ?: false,
            note = p[K.NOTE] ?: "",
            sessions = parseStringArray(p[K.SESSIONS]).ifEmpty { listOf(AppSettings.DEFAULT_SESSION) },
            currentSession = p[K.CURRENT_SESSION] ?: AppSettings.DEFAULT_SESSION,
            counters = parseCounterMap(p[K.COUNTERS]),
            flashMode = p[K.FLASH] ?: AppSettings.FLASH_OFF,
            lensFacingBack = p[K.LENS_BACK] ?: true,
            shareLevel = p[K.SHARE_LEVEL]?.let { runCatching { PrivacyLevel.valueOf(it) }.getOrNull() }
                ?: PrivacyLevel.FULL,
            keepOriginals = p[K.KEEP_ORIGINALS] ?: true,
            fontScale = p[K.FONT_SCALE] ?: 1f,
            fontColorArgb = p[K.FONT_COLOR] ?: -1,
            bgColorArgb = p[K.BG_COLOR] ?: -1,
            textOpacity = p[K.TEXT_OPACITY] ?: 1f,
            bgOpacity = p[K.BG_OPACITY] ?: 0.62f,
            stampPosition = p[K.POSITION]?.let { runCatching { StampPosition.valueOf(it) }.getOrNull() }
                ?: StampPosition.BOTTOM_LEFT,
            stampFont = p[K.FONT]?.let { runCatching { StampFont.valueOf(it) }.getOrNull() }
                ?: StampFont.DEFAULT,
            stampAlign = p[K.ALIGN]?.let { runCatching { StampAlign.valueOf(it) }.getOrNull() }
                ?: StampAlign.LEFT,
            activity = p[K.ACTIVITY] ?: "",
            personName = p[K.PERSON_NAME] ?: "",
            dateFormat = p[K.DATE_FORMAT]?.let { runCatching { DateFormatOption.valueOf(it) }.getOrNull() }
                ?: DateFormatOption.DAY_MONTH_YEAR,
            gpsFormat = p[K.GPS_FORMAT]?.let { runCatching { GpsFormat.valueOf(it) }.getOrNull() }
                ?: GpsFormat.DECIMAL,
            showNumber = p[K.SHOW_NUMBER] ?: true,
            showGrid = p[K.SHOW_GRID] ?: false,
            timerSecs = p[K.TIMER] ?: 0,
            shutterSound = p[K.SHUTTER_SOUND] ?: false,
            touchToCapture = p[K.TOUCH_CAPTURE] ?: false,
            mirrorFront = p[K.MIRROR_FRONT] ?: true,
            photoMaxDim = p[K.PHOTO_MAX_DIM] ?: 0,
            volumeKeysCapture = p[K.VOLUME_KEYS] ?: true,
            keepScreenOn = p[K.KEEP_SCREEN_ON] ?: true,
            showSeconds = p[K.SHOW_SECONDS] ?: true,
            time24h = p[K.TIME_24H] ?: true,
            videoQuality = p[K.VIDEO_QUALITY] ?: AppSettings.VIDEO_HD,
            videoAudio = p[K.VIDEO_AUDIO] ?: true,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setStyle(style: StampStyle) = put(K.STYLE, style.name)
    suspend fun setShowAddress(v: Boolean) = put(K.SHOW_ADDRESS, v)
    suspend fun setShowGps(v: Boolean) = put(K.SHOW_GPS, v)
    suspend fun setNote(v: String) = put(K.NOTE, v.trim())
    suspend fun setFlashMode(v: Int) = put(K.FLASH, v)
    suspend fun setLensFacingBack(v: Boolean) = put(K.LENS_BACK, v)
    suspend fun setShareLevel(v: PrivacyLevel) = put(K.SHARE_LEVEL, v.name)
    suspend fun setKeepOriginals(v: Boolean) = put(K.KEEP_ORIGINALS, v)
    suspend fun setFontScale(v: Float) = put(K.FONT_SCALE, v)
    suspend fun setFontColorArgb(v: Int) = put(K.FONT_COLOR, v)
    suspend fun setBgColorArgb(v: Int) = put(K.BG_COLOR, v)
    suspend fun setTextOpacity(v: Float) = put(K.TEXT_OPACITY, v.coerceIn(0.2f, 1f))
    suspend fun setBgOpacity(v: Float) = put(K.BG_OPACITY, v.coerceIn(0f, 1f))
    suspend fun setStampPosition(v: StampPosition) = put(K.POSITION, v.name)
    suspend fun setStampFont(v: StampFont) = put(K.FONT, v.name)
    suspend fun setStampAlign(v: StampAlign) = put(K.ALIGN, v.name)
    suspend fun setActivity(v: String) = put(K.ACTIVITY, v.trim())
    suspend fun setPersonName(v: String) = put(K.PERSON_NAME, v.trim())
    suspend fun setDateFormat(v: DateFormatOption) = put(K.DATE_FORMAT, v.name)
    suspend fun setGpsFormat(v: GpsFormat) = put(K.GPS_FORMAT, v.name)
    suspend fun setShowNumber(v: Boolean) = put(K.SHOW_NUMBER, v)
    suspend fun setShowGrid(v: Boolean) = put(K.SHOW_GRID, v)
    suspend fun setTimerSecs(v: Int) = put(K.TIMER, v)
    suspend fun setShutterSound(v: Boolean) = put(K.SHUTTER_SOUND, v)
    suspend fun setTouchToCapture(v: Boolean) = put(K.TOUCH_CAPTURE, v)
    suspend fun setMirrorFront(v: Boolean) = put(K.MIRROR_FRONT, v)
    suspend fun setPhotoMaxDim(v: Int) = put(K.PHOTO_MAX_DIM, v)
    suspend fun setVolumeKeysCapture(v: Boolean) = put(K.VOLUME_KEYS, v)
    suspend fun setKeepScreenOn(v: Boolean) = put(K.KEEP_SCREEN_ON, v)
    suspend fun setShowSeconds(v: Boolean) = put(K.SHOW_SECONDS, v)
    suspend fun setTime24h(v: Boolean) = put(K.TIME_24H, v)
    suspend fun setVideoQuality(v: Int) = put(K.VIDEO_QUALITY, v)
    suspend fun setVideoAudio(v: Boolean) = put(K.VIDEO_AUDIO, v)

    suspend fun setSession(name: String) {
        val clean = name.trim().ifBlank { AppSettings.DEFAULT_SESSION }
        context.settingsStore.edit { p ->
            val sessions = parseStringArray(p[K.SESSIONS]).toMutableList()
            if (clean !in sessions) sessions.add(clean)
            p[K.SESSIONS] = JSONArray(sessions).toString()
            p[K.CURRENT_SESSION] = clean
        }
    }

    /** Atomically increments and returns the next photo number for a session. */
    suspend fun nextNumber(session: String): Int {
        var next = 1
        context.settingsStore.edit { p ->
            val counters = parseCounterMap(p[K.COUNTERS]).toMutableMap()
            next = (counters[session] ?: 0) + 1
            counters[session] = next
            p[K.COUNTERS] = JSONObject(counters.mapValues { it.value }).toString()
        }
        return next
    }

    suspend fun renameSessionCountersIfNeeded(oldName: String, newName: String) {
        context.settingsStore.edit { p ->
            val counters = parseCounterMap(p[K.COUNTERS]).toMutableMap()
            counters[oldName]?.let { counters[newName] = it; counters.remove(oldName) }
            p[K.COUNTERS] = JSONObject(counters.mapValues { it.value }).toString()
        }
    }

    private suspend fun <T> put(key: androidx.datastore.preferences.core.Preferences.Key<T>, value: T) {
        context.settingsStore.edit { it[key] = value }
    }

    private fun parseStringArray(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    private fun parseCounterMap(json: String?): Map<String, Int> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(json)
            obj.keys().asSequence().associateWith { obj.getInt(it) }
        }.getOrDefault(emptyMap())
    }
}
