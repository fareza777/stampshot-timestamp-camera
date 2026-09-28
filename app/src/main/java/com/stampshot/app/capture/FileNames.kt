package com.stampshot.app.capture

import com.stampshot.app.stamp.StampInfo
import java.util.Date

/** `StampShot_<session-slug>_NNN_yyyyMMdd_HHmmss.<ext>` — shared by photo and video saves. */
fun stampShotFileName(sessionName: String?, number: Int?, timestampMillis: Long, ext: String): String {
    val slug = (sessionName ?: "General")
        .replace(Regex("[^A-Za-z0-9\\- ]"), "")
        .trim()
        .replace(Regex("\\s+"), "-")
        .ifBlank { "General" }
        .take(40)
    val num = "%03d".format(number ?: 0)
    val ts = StampInfo.FILE_TS_FMT.format(Date(timestampMillis))
    return "StampShot_${slug}_${num}_${ts}.$ext"
}
