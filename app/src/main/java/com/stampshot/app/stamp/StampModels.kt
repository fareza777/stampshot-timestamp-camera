package com.stampshot.app.stamp

import android.location.Location
import org.json.JSONObject
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

enum class AddressMode(val label: String) {
    FULL("Full address"), CITY("City only"),
}

enum class ElemSize(val label: String, val scale: Float) {
    S("S", 0.85f), M("M", 1f), L("L", 1.25f);
}

/** One stamp element's visibility, column (two-column styles) and size. */
data class El(
    val side: StampAlign = StampAlign.LEFT,
    val size: ElemSize = ElemSize.M,
    val on: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("side", side.name)
        put("size", size.name)
        put("on", on)
    }

    companion object {
        fun fromJson(o: JSONObject?, fallback: El): El = if (o == null) fallback else El(
            side = o.optString("side").let {
                runCatching { StampAlign.valueOf(it) }.getOrDefault(fallback.side)
            },
            size = o.optString("size").let {
                runCatching { ElemSize.valueOf(it) }.getOrDefault(fallback.size)
            },
            on = o.optBoolean("on", fallback.on),
        )
    }
}

/** The full set of configurable stamp elements, in canonical display order. */
data class StampElements(
    val date: El = El(),
    val time: El = El(),
    val session: El = El(),
    val activity: El = El(),
    val personName: El = El(),
    val address: El = El(side = StampAlign.RIGHT),
    val gps: El = El(side = StampAlign.RIGHT),
    val altitude: El = El(side = StampAlign.RIGHT, on = false),
    val note: El = El(side = StampAlign.RIGHT),
) {
    fun toJson(): String = JSONObject().apply {
        put("date", date.toJson())
        put("time", time.toJson())
        put("session", session.toJson())
        put("activity", activity.toJson())
        put("personName", personName.toJson())
        put("address", address.toJson())
        put("gps", gps.toJson())
        put("altitude", altitude.toJson())
        put("note", note.toJson())
    }.toString()

    companion object {
        fun fromJson(json: String?, defaults: StampElements = StampElements()): StampElements {
            val o = runCatching { JSONObject(json ?: "") }.getOrNull() ?: return defaults
            return StampElements(
                date = El.fromJson(o.optJSONObject("date"), defaults.date),
                time = El.fromJson(o.optJSONObject("time"), defaults.time),
                session = El.fromJson(o.optJSONObject("session"), defaults.session),
                activity = El.fromJson(o.optJSONObject("activity"), defaults.activity),
                personName = El.fromJson(o.optJSONObject("personName"), defaults.personName),
                address = El.fromJson(o.optJSONObject("address"), defaults.address),
                gps = El.fromJson(o.optJSONObject("gps"), defaults.gps),
                altitude = El.fromJson(o.optJSONObject("altitude"), defaults.altitude),
                note = El.fromJson(o.optJSONObject("note"), defaults.note),
            )
        }
    }
}

/** A resolved stamp line ready for layout: text + which column + size + weight. */
data class StampLine(
    val text: String,
    val side: StampAlign,
    val size: ElemSize,
    val bold: Boolean,
    val maxLines: Int = 2,
)

/** Per-capture look: colors carry ARGB, -1 means "auto" (Smart Readability). */
data class StampOptions(
    val fontScale: Float = 1f,
    val fontColorArgb: Int = -1,
    val bgColorArgb: Int = -1,
    val textOpacity: Float = 1f,
    val bgOpacity: Float = 0.62f,
    val position: StampPosition = StampPosition.BOTTOM_LEFT,
    val font: StampFont = StampFont.DEFAULT,
    val transparent: Boolean = false,
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
    val altitude: Double? = null,
    val showNumber: Boolean = true,
    val elements: StampElements = StampElements(),
    val addressMode: AddressMode = AddressMode.FULL,
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
        if (!address.isNullOrBlank() || !city.isNullOrBlank()) {
            when (addressMode) {
                AddressMode.FULL -> address ?: city
                // Prefer the city, but don't drop the line when it's unresolved.
                AddressMode.CITY -> city ?: address
            }?.takeIf { it.isNotBlank() }
        } else null

    fun gpsLine(): String? =
        if (latitude != null && longitude != null) {
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

    fun altLine(): String? =
        altitude?.let { "Alt: %.0f m".format(Locale.US, it) }

    fun noteLine(): String? = note?.takeIf { it.isNotBlank() }

    fun activityLine(): String? = activity?.takeIf { it.isNotBlank() }?.let { "Activity: $it" }

    fun personLine(): String? = personName?.takeIf { it.isNotBlank() }?.let { "Name: $it" }

    /** All enabled elements resolved to laid-out lines, in canonical order. */
    fun stampLines(): List<StampLine> = buildList {
        val els = elements
        if (els.date.on && els.time.on && els.date.side == els.time.side) {
            val size = if (els.date.size.ordinal >= els.time.size.ordinal) els.date.size else els.time.size
            add(StampLine(dateTime(), els.date.side, size, bold = true))
        } else {
            if (els.date.on) add(StampLine(date(), els.date.side, els.date.size, bold = true))
            if (els.time.on) add(StampLine(time(), els.time.side, els.time.size, bold = true))
        }
        sessionLine()?.let {
            if (els.session.on) add(StampLine(it, els.session.side, els.session.size, bold = false))
        }
        activityLine()?.let {
            if (els.activity.on) add(StampLine(it, els.activity.side, els.activity.size, bold = false))
        }
        personLine()?.let {
            if (els.personName.on) add(StampLine(it, els.personName.side, els.personName.size, bold = false))
        }
        addressLine()?.let {
            if (els.address.on) add(StampLine(it, els.address.side, els.address.size, bold = false, maxLines = 3))
        }
        gpsLine()?.let {
            if (els.gps.on) add(StampLine(it, els.gps.side, els.gps.size, bold = false))
        }
        altLine()?.let {
            if (els.altitude.on) add(StampLine(it, els.altitude.side, els.altitude.size, bold = false))
        }
        noteLine()?.let {
            if (els.note.on) add(StampLine(it, els.note.side, els.note.size, bold = false))
        }
        if (isEmpty()) add(StampLine(dateTime(), StampAlign.LEFT, ElemSize.M, bold = true))
    }

    /** Line texts only — kept for callers that don't care about layout. */
    fun lines(): List<String> = stampLines().map { it.text }

    /** Redact the stamp for a lower privacy level (re-rendered on the clean original). */
    fun reduced(level: PrivacyLevel): StampInfo = when (level) {
        PrivacyLevel.FULL -> this
        PrivacyLevel.APPROXIMATE -> copy(
            address = null, addressMode = AddressMode.CITY,
            latitude = null, longitude = null, altitude = null,
        )
        PrivacyLevel.PRIVATE -> copy(
            address = null, city = null,
            latitude = null, longitude = null, altitude = null,
            note = null,
        )
    }

    /** True when the stamp leaks location info a private share would need to re-render. */
    fun hasSensitiveContent(): Boolean =
        (elements.address.on && addressLine() != null) ||
            (elements.gps.on && gpsLine() != null) ||
            (elements.altitude.on && altLine() != null)

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
