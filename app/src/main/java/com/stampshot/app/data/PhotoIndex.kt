package com.stampshot.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * App-private index of photos captured by StampShot. Maps each saved photo's
 * MediaStore display name to the metadata needed to re-render privacy-redacted
 * share variants, plus the path of the kept clean (unstamped) original.
 *
 * The original is only kept when the burned-in stamp contains location info,
 * so the app doesn't double storage for users who never stamp GPS/address.
 */
class PhotoIndex(private val context: Context) {

    data class Record(
        val displayName: String,
        val session: String,
        val number: Int,
        val timestampMillis: Long,
        val originalPath: String?,
        val latitude: Double?,
        val longitude: Double?,
        val altitude: Double?,
        val address: String?,
        val city: String?,
        val note: String?,
        val activity: String?,
        val personName: String?,
        /** Serialized StampElements config at capture time (JSON), null on legacy rows. */
        val elementsJson: String?,
        val addressMode: String?,
    )

    private val indexFile: File get() = File(context.filesDir, "photo_index.json")
    private val originalsDir: File get() = File(context.filesDir, "originals")

    @Synchronized
    fun all(): List<Record> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(indexFile.readText())
            (0 until arr.length()).map { arr.getJSONObject(it).toRecord() }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun get(displayName: String): Record? = all().firstOrNull { it.displayName == displayName }

    @Synchronized
    fun put(record: Record) {
        val records = all().filterNot { it.displayName == record.displayName } + record
        save(records)
    }

    /** Removes the record and deletes its kept original file if present. */
    @Synchronized
    fun remove(displayName: String) {
        val record = get(displayName)
        record?.originalPath?.let { File(context.filesDir, it).delete() }
        save(all().filterNot { it.displayName == displayName })
    }

    /** Drops records whose MediaStore photo is gone and orphans originals. */
    @Synchronized
    fun prune(existingDisplayNames: Set<String>) {
        val records = all()
        val stale = records.filter { it.displayName !in existingDisplayNames }
        stale.forEach { it.originalPath?.let { p -> File(context.filesDir, p).delete() } }
        save(records - stale.toSet())
    }

    fun newOriginalFile(displayName: String): File {
        originalsDir.mkdirs()
        return File(originalsDir, displayName)
    }

    fun relativeOriginalPath(file: File): String =
        file.relativeTo(context.filesDir).path

    private fun save(records: List<Record>) {
        val arr = JSONArray()
        records.forEach { arr.put(it.toJson()) }
        indexFile.writeText(arr.toString())
    }

    private fun Record.toJson() = JSONObject().apply {
        put("displayName", displayName)
        put("session", session)
        put("number", number)
        put("ts", timestampMillis)
        put("orig", originalPath)
        if (latitude != null) put("lat", latitude)
        if (longitude != null) put("lon", longitude)
        if (altitude != null) put("alt", altitude)
        if (address != null) put("address", address)
        if (city != null) put("city", city)
        if (note != null) put("note", note)
        if (activity != null) put("activity", activity)
        if (personName != null) put("personName", personName)
        if (elementsJson != null) put("elements", elementsJson)
        if (addressMode != null) put("addressMode", addressMode)
    }

    private fun JSONObject.toRecord() = Record(
        displayName = getString("displayName"),
        session = optString("session", AppSettings.DEFAULT_SESSION),
        number = optInt("number", 0),
        timestampMillis = optLong("ts", 0L),
        originalPath = if (isNull("orig")) null else optString("orig"),
        latitude = if (has("lat")) getDouble("lat") else null,
        longitude = if (has("lon")) getDouble("lon") else null,
        altitude = if (has("alt")) getDouble("alt") else null,
        address = if (isNull("address")) null else getString("address"),
        city = if (isNull("city")) null else getString("city"),
        note = if (isNull("note")) null else getString("note"),
        activity = if (isNull("activity")) null else getString("activity"),
        personName = if (isNull("personName")) null else getString("personName"),
        elementsJson = if (isNull("elements")) null else getString("elements"),
        addressMode = if (isNull("addressMode")) null else getString("addressMode"),
    )
}
