package io.github.nlinker.rutubedl

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.nlinker.rutubedl.bindings.Quality
import io.github.nlinker.rutubedl.bindings.VariantInfo
import io.github.nlinker.rutubedl.bindings.resolve
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// The two quality rows the main screen offers; which one was picked last.
enum class Choice { Fast, High }

data class AppSettings(
    // A SAF tree the user picked; null until the first pick.
    val folder: Uri? = null,
    // Preferred heights, resolved per video via Quality.AtMost; learned from what the user picks.
    val fastHeight: UInt = DEFAULT_FAST_HEIGHT,
    val highHeight: UInt = DEFAULT_HIGH_HEIGHT,
    val choice: Choice = Choice.Fast,
) {
    val preferredHeight: UInt get() = height(choice)

    fun height(choice: Choice): UInt = when (choice) {
        Choice.Fast -> fastHeight
        Choice.High -> highHeight
    }

    // The variant a row shows for this video; null only when `variants` is empty.
    fun resolve(choice: Choice, variants: List<VariantInfo>): VariantInfo? =
        resolve(variants, Quality.AtMost(height(choice)))
}

const val DEFAULT_FAST_HEIGHT = 360u
const val DEFAULT_HIGH_HEIGHT = 1080u

// One DataStore file per process, owned by the Context extension as the library requires.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class Settings(context: Context) {
    private val store = context.applicationContext.dataStore

    val flow: Flow<AppSettings> = store.data.map { prefs ->
        AppSettings(
            folder = prefs[FOLDER]?.let(Uri::parse),
            fastHeight = prefs[FAST_HEIGHT]?.toUInt() ?: DEFAULT_FAST_HEIGHT,
            highHeight = prefs[HIGH_HEIGHT]?.toUInt() ?: DEFAULT_HIGH_HEIGHT,
            choice = if (prefs[CHOICE] == Choice.High.name) Choice.High else Choice.Fast,
        )
    }

    suspend fun setFolder(uri: Uri) = store.edit { it[FOLDER] = uri.toString() }

    suspend fun clearFolder() = store.edit { it.remove(FOLDER) }

    // A pick from the collapsed list.
    suspend fun setChoice(choice: Choice) = store.edit { it[CHOICE] = choice.name }

    // A pick from the full list: the height becomes the preference of its category, and that
    // category the choice. One edit, so the two never disagree.
    suspend fun setHeight(choice: Choice, height: UInt) = store.edit {
        it[if (choice == Choice.Fast) FAST_HEIGHT else HIGH_HEIGHT] = height.toInt()
        it[CHOICE] = choice.name
    }

    suspend fun resetHeights() = store.edit {
        it.remove(FAST_HEIGHT)
        it.remove(HIGH_HEIGHT)
    }

    private companion object {
        val FOLDER = stringPreferencesKey("folder")
        val FAST_HEIGHT = intPreferencesKey("fast_height")
        val HIGH_HEIGHT = intPreferencesKey("high_height")
        val CHOICE = stringPreferencesKey("choice")
    }
}

// Same spelling as the Rust side: "worst", "best", a bare height, or "~height".
fun formatQuality(quality: Quality): String = when (quality) {
    Quality.Worst -> "worst"
    Quality.Best -> "best"
    is Quality.Height -> quality.height.toString()
    is Quality.AtMost -> "~${quality.height}"
}

fun parseQuality(text: String): Quality? = when {
    text == "worst" -> Quality.Worst
    text == "best" -> Quality.Best
    text.startsWith("~") -> text.drop(1).toUIntOrNull()?.let { Quality.AtMost(it) }
    else -> text.toUIntOrNull()?.let { Quality.Height(it) }
}
