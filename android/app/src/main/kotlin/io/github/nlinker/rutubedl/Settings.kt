package io.github.nlinker.rutubedl

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.nlinker.rutubedl.bindings.Quality
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class AppSettings(
    // A SAF tree the user picked; null until the first pick.
    val folder: Uri? = null,
    val quality: Quality = Quality.Worst,
)

// One DataStore file per process, owned by the Context extension as the library requires.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class Settings(context: Context) {
    private val store = context.applicationContext.dataStore

    val flow: Flow<AppSettings> = store.data.map { prefs ->
        AppSettings(
            folder = prefs[FOLDER]?.let(Uri::parse),
            quality = prefs[QUALITY]?.let(::parseQuality) ?: Quality.Worst,
        )
    }

    suspend fun setFolder(uri: Uri) = store.edit { it[FOLDER] = uri.toString() }

    suspend fun clearFolder() = store.edit { it.remove(FOLDER) }

    suspend fun setQuality(quality: Quality) = store.edit { it[QUALITY] = formatQuality(quality) }

    private companion object {
        val FOLDER = stringPreferencesKey("folder")
        val QUALITY = stringPreferencesKey("quality")
    }
}

// Same spelling as the Rust side: "worst", "best", or a bare height.
fun formatQuality(quality: Quality): String = when (quality) {
    Quality.Worst -> "worst"
    Quality.Best -> "best"
    is Quality.Height -> quality.height.toString()
}

fun parseQuality(text: String): Quality? = when (text) {
    "worst" -> Quality.Worst
    "best" -> Quality.Best
    else -> text.toUIntOrNull()?.let { Quality.Height(it) }
}
