package com.archivesaver.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object ArchiveHistoryStore {
    data class Entry(
        val title: String,
        val archiveUrl: String,
        val sourceUrl: String,
        val collectionTitle: String,
        val savedAt: Long
    )

    private const val PREFS_NAME = "archive_history"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 100

    fun add(context: Context, entry: Entry) {
        val entries = listOf(entry) + getAll(context).filter { it.archiveUrl != entry.archiveUrl }
        saveAll(context, entries.take(MAX_ENTRIES))
    }

    fun getAll(context: Context): List<Entry> {
        val raw = prefs(context).getString(KEY_ENTRIES, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        Entry(
                            title = item.optString("title"),
                            archiveUrl = item.optString("archiveUrl"),
                            sourceUrl = item.optString("sourceUrl"),
                            collectionTitle = item.optString("collectionTitle"),
                            savedAt = item.optLong("savedAt")
                        )
                    )
                }
            }.filter { it.archiveUrl.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private fun saveAll(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("archiveUrl", entry.archiveUrl)
                    put("title", entry.title)
                    put("sourceUrl", entry.sourceUrl)
                    put("collectionTitle", entry.collectionTitle)
                    put("savedAt", entry.savedAt)
                }
            )
        }
        prefs(context).edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
