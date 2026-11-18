package com.rel.adbhelper.core.recents

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.recentsDataStore by preferencesDataStore(name = "recents")

const val MAX_RECENTS = 10

/** [current] (most recent first) with [entry] moved or added to the top, capped at [MAX_RECENTS]. */
fun withRecent(current: List<String>, entry: String): List<String> =
    (listOf(entry) + (current - entry)).take(MAX_RECENTS)

/** Recently connected `host:port` targets, most recent first, persisted, each removable. */
class RecentConnections(context: Context) {

    private val dataStore = context.applicationContext.recentsDataStore

    val entries: Flow<List<String>> = dataStore.data.map(::read)

    suspend fun add(entry: String) {
        dataStore.edit { write(it, withRecent(read(it), entry)) }
    }

    suspend fun remove(entry: String) {
        dataStore.edit { write(it, read(it) - entry) }
    }

    /** Falls back to the old unordered set until the first write, so existing lists survive. */
    private fun read(prefs: Preferences): List<String> =
        prefs[KEY]?.split("\n")?.filter { it.isNotEmpty() }
            ?: prefs[LEGACY_KEY]?.sorted()?.take(MAX_RECENTS).orEmpty()

    private fun write(prefs: MutablePreferences, entries: List<String>) {
        prefs[KEY] = entries.joinToString("\n")
        prefs.remove(LEGACY_KEY)
    }

    companion object {
        private val KEY = stringPreferencesKey("recent_connections_ordered")
        private val LEGACY_KEY = stringSetPreferencesKey("recent_connections")
    }
}
