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
    val address: String? = null,
    val city: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val showAddress: Boolean = true,
    val showGps: Boolean = true,
) {
    fun date(): String = DATE_FMT.format(Date(timestampMillis))

    fun time(): String = TIME_FMT.format(Date(timestampMillis))

    fun dateTime(): String = "${date()} · ${time()}"

    fun sessionLine(): String? {
        val number = photoNumber?.let { "#%03d".format(it) }
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
            "%.5f, %.5f".format(Locale.US, latitude, longitude)
        } else null

    fun noteLine(): String? = note?.takeIf { it.isNotBlank() }

    /** All visible lines in display order. */
    fun lines(): List<String> = buildList {
        add(dateTime())
        sessionLine()?.let { add(it) }
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
        private val DATE_FMT = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        private val TIME_FMT = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val FILE_TS_FMT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val EXIF_FMT = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
    }
}

data class LocationStamp(
    val location: Location,
    val address: String?,
    val city: String?,
)
