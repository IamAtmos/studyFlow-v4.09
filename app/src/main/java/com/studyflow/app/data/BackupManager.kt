package com.studyflow.app.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Saves/restores all study data as a JSON file in the device's Downloads folder.
 * → Survives app reinstall (Downloads is a public folder)
 * → Survives app updates
 * → Can be manually transferred between devices
 * File location: Downloads/StudyFlow/studyflow_backup.json
 */
class BackupManager(private val context: Context) {

    private val dao = StudyDatabase.getDatabase(context).studyDao()
    private val FILE_NAME = "studyflow_backup.json"
    private val FOLDER    = "StudyFlow"

    // ── Auto-backup after every session save ──────────────────────────────────

    suspend fun autoBackup() = withContext(Dispatchers.IO) {
        try {
            val sessions = dao.getAllSessionsOnce()
            val subjects = dao.getAllSubjectsOnce()
            val json     = buildJson(sessions, subjects)
            writeToDownloads(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ── Restore on first launch if DB is empty but backup exists ─────────────

    suspend fun restoreIfNeeded() = withContext(Dispatchers.IO) {
        try {
            val sessions = dao.getAllSessionsOnce()
            if (sessions.isNotEmpty()) return@withContext   // DB already has data

            val json = readFromDownloads() ?: return@withContext
            parseAndRestore(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ── Build JSON ────────────────────────────────────────────────────────────

    private fun buildJson(sessions: List<StudySession>, subjects: List<Subject>): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("exportedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()))

        val subjectsArr = JSONArray()
        subjects.forEach { s ->
            subjectsArr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("colorIndex", s.colorIndex)
            })
        }
        root.put("subjects", subjectsArr)

        val sessionsArr = JSONArray()
        sessions.forEach { s ->
            sessionsArr.put(JSONObject().apply {
                put("id", s.id)
                put("subjectId", s.subjectId)
                put("subjectName", s.subjectName)
                put("subjectColorIndex", s.subjectColorIndex)
                put("durationMillis", s.durationMillis)
                put("testCount", s.testCount)
                put("note", s.note)
                put("date", s.date)
                put("timestamp", s.timestamp)
            })
        }
        root.put("sessions", sessionsArr)

        return root.toString(2)
    }

    // ── Parse + restore into Room ─────────────────────────────────────────────

    private suspend fun parseAndRestore(json: String) {
        val root     = JSONObject(json)
        val subjects = root.optJSONArray("subjects")
        val sessions = root.optJSONArray("sessions")

        subjects?.let {
            for (i in 0 until it.length()) {
                val obj = it.getJSONObject(i)
                dao.insertSubject(Subject(
                    id         = obj.getInt("id"),
                    name       = obj.getString("name"),
                    colorIndex = obj.getInt("colorIndex"),
                ))
            }
        }

        sessions?.let {
            for (i in 0 until it.length()) {
                val obj = it.getJSONObject(i)
                dao.insertSession(StudySession(
                    id                = obj.getInt("id"),
                    subjectId         = obj.getInt("subjectId"),
                    subjectName       = obj.getString("subjectName"),
                    subjectColorIndex = obj.getInt("subjectColorIndex"),
                    durationMillis    = obj.getLong("durationMillis"),
                    testCount         = obj.optInt("testCount", 0),
                    note              = obj.optString("note", ""),
                    date              = obj.getString("date"),
                    timestamp         = obj.getLong("timestamp"),
                ))
            }
        }
    }

    // ── Write to Downloads ────────────────────────────────────────────────────

    private fun writeToDownloads(content: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ — use MediaStore (no permission needed for Downloads)
            val resolver = context.contentResolver

            // Delete existing file first (update it)
            val existing = resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
                arrayOf(FILE_NAME, "Download/$FOLDER/"),
                null,
            )
            existing?.use { c ->
                if (c.moveToFirst()) {
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                    val uri   = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                        .buildUpon().appendPath(c.getLong(idCol).toString()).build()
                    resolver.delete(uri, null, null)
                }
            }

            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/$FOLDER/")
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let { resolver.openOutputStream(it)?.use { os -> os.write(content.toByteArray()) } }
        } else {
            // Android 9 and below — write directly (WRITE_EXTERNAL_STORAGE required)
            val dir  = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
            dir.mkdirs()
            File(dir, FILE_NAME).writeText(content)
        }
    }

    // ── Read from Downloads ───────────────────────────────────────────────────

    private fun readFromDownloads(): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val cursor   = resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
                    arrayOf(FILE_NAME, "Download/$FOLDER/"),
                    null,
                )
                cursor?.use { c ->
                    if (c.moveToFirst()) {
                        val id  = c.getLong(c.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                        val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI.buildUpon()
                            .appendPath(id.toString()).build()
                        resolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                    } else null
                }
            } else {
                val file = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "$FOLDER/$FILE_NAME",
                )
                if (file.exists()) file.readText() else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
