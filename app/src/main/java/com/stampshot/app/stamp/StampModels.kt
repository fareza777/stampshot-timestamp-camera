package com.stampshot.app.stamp

import android.location.Location
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class StampStyle(val label: String, val shortLabel: String) {
    MINIMAL_CORNER("Minimal Corner", "Minimal"),
    CLEAN_BOTTOM_BAR("Clean Bottom Bar", "Bottom Bar"),
    WORK_PROOF("Work Proof", "Work Proof"),
    BOTTOM_INFO_STRIP("Bottom Info Strip", "Info Strip"),
}

enum class StampPosition(val label: String) {
    BOTTOM_LEFT("Bottom Left"),
    BOTTOM_RIGHT("Bottom Right"),
    TOP_LEFT("Top Left"),
    TOP_RIGHT("Top Right"),
}

enum class StampFont(val label: String) {
    DEFAULT("Sans"), SERIF("Serif"), MONO("Mono"),
}

enum class StampAlign(val label: String) {
    LEFT("Left"), RIGHT("Right"),
}

enum class DateFormatOption(val label: String, val pattern: String) {
    DAY_MONTH_YEAR("28 Sep 2026", "dd MMM yyyy"),
    WEEKDAY_FULL("Sunday, 28 September 2026", "EEEE, dd MMMM yyyy"),
    SLASH("28/09/2026", "dd/MM/yyyy"),
    ISO("2026-09-28", "yyyy-MM-dd"),
}

enum class GpsFormat(val label: String) {
    DECIMAL("39.23726, -123.15003"),
    DMS("39°14'14\"N, 123°9'0\"W"),
}

/** Per-capture look: colors carry ARGB, -1 means "auto" (Smart Readability). */
data class StampOptions(
    val fontScale: Float = 1f,
    val fontColorArgb: Int = -1,
    val bgColorArgb: Int = -1,
    val textOpacity: Float = 1f,
    val bgOpacity: Float = 0.62f,
    val position: StampPosition = StampPosition.BOTTOM_LEFT,
    val font: StampFont = StampFont.DEFAULT,
    val align: StampAlign = StampAlign.LEFT,
)

enum class PrivacyLevel(val label: String, val description: String) {
    FULL("Full", "Timestamp + address + GPS"),
    APPROXIMATE("Approximate", "Timestamp + city/area only"),
    PRIVATE("Private", "Timestamp only — GPS & EXIF removed"),
}

data class StampInfo(
    val timestampMillis: Long,
    val sessionName: String? = null,
    val photoNumber: Int? = null,
    val note: String? = null,
    val activity: String? = null,
    val personName: String? = null,
    val address: String? = null,
    val city: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val showAddress: Boolean = true,
    val showGps: Boolean = true,
    val showNumber: Boolean = true,
    val dateFormat: DateFormatOption = DateFormatOption.DAY_MONTH_YEAR,
    val gpsFormat: GpsFormat = GpsFormat.DECIMAL,
    val showSeconds: Boolean = true,
    val time24h: Boolean = true,
) {
    fun date(): String = SimpleDateFormat(dateFormat.pattern, Locale.getDefault()).format(Date(timestampMillis))

    fun time(): String {
        val pattern = buildString {
            append(if (time24h) "HH:mm" else "hh:mm")
            if (showSeconds) append(":ss")
            if (!time24h) append(" a")
        }
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestampMillis))
    }

    fun dateTime(): String = "${date()} · ${time()}"

    fun sessionLine(): String? {
        val number = if (showNumber) photoNumber?.let { "#%03d".format(it) } else null
        return when {
            !sessionName.isNullOrBlank() && number != null -> "$sessionName · $number"
            !sessionName.isNullOrBlank() -> sessionName
            number != null -> number
            else -> null
        }
    }

    fun addressLine(): String? =
        if (showAddress && !address.isNullOrBlank()) address else null

    fun gpsLine(): String? =
        if (showGps && latitude != null && longitude != null) {
            when (gpsFormat) {
                GpsFormat.DECIMAL -> "%.5f, %.5f".format(Locale.US, latitude, longitude)
                GpsFormat.DMS -> "${dms(latitude, true)}, ${dms(longitude, false)}"
            }
        } else null

    private fun dms(value: Double, isLat: Boolean): String {
        val dir = if (isLat) (if (value >= 0) "N" else "S") else (if (value >= 0) "E" else "W")
        val abs = kotlin.math.abs(value)
        val d = abs.toInt()
        val mFull = (abs - d) * 60
        val m = mFull.toInt()
        val s = ((mFull - m) * 60).toInt()
        return "$d°$m'$s\"$dir"
    }

    fun noteLine(): String? = note?.takeIf { it.isNotBlank() }

    fun activityLine(): String? = activity?.takeIf { it.isNotBlank() }?.let { "Activity: $it" }

    fun personLine(): String? = personName?.takeIf { it.isNotBlank() }?.let { "Name: $it" }

    /** All visible lines in display order. */
    fun lines(): List<String> = buildList {
        add(dateTime())
        sessionLine()?.let { add(it) }
        activityLine()?.let { add(it) }
        personLine()?.let { add(it) }
        addressLine()?.let { add(it) }
        gpsLine()?.let { add(it) }
        noteLine()?.let { add(it) }
    }

    /** Redact the stamp for a lower privacy level (re-rendered on the clean original). */
    fun reduced(level: PrivacyLevel): StampInfo = when (level) {
        PrivacyLevel.FULL -> this
        PrivacyLevel.APPROXIMATE -> copy(
            address = city,
            latitude = null, longitude = null, showGps = false,
        )
        PrivacyLevel.PRIVATE -> copy(
            address = null, city = null,
            latitude = null, longitude = null,
            note = null,
            showAddress = false, showGps = false,
        )
    }

    /** True when the stamp leaks location info a private share would need to re-render. */
    fun hasSensitiveContent(): Boolean =
        (showAddress && !address.isNullOrBlank()) || (showGps && latitude != null)

    companion object {
        val FILE_TS_FMT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val EXIF_FMT = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
    }
}

data class LocationStamp(
    val location: Location,
    val address: String?,
    val city: String?,
)
