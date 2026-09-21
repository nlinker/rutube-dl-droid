package io.github.nlinker.rutubedl

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree(), viewModel::pickFolder)

    BackHandler(onBack = viewModel::closeSettings)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = viewModel::closeSettings) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.language_label), style = MaterialTheme.typography.labelLarge)
            LanguageChips()

            Text(stringResource(R.string.folder_label), style = MaterialTheme.typography.labelLarge)
            Text(settings?.folder?.let(::folderLabel) ?: stringResource(R.string.folder_default))
            if (state.folderRejected) {
                Text(stringResource(R.string.folder_not_local), color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { pickFolder.launch(settings?.folder) }) { Text(stringResource(R.string.folder_pick)) }
                if (settings?.folder != null) {
                    OutlinedButton(onClick = viewModel::resetFolder) { Text(stringResource(R.string.folder_reset)) }
                }
            }

            Text(stringResource(R.string.quality_label), style = MaterialTheme.typography.labelLarge)
            settings?.let {
                Text(stringResource(R.string.quality_heights, it.fastHeight.toInt(), it.highHeight.toInt()))
            }
            OutlinedButton(onClick = viewModel::resetHeights) {
                Text(stringResource(R.string.quality_reset, DEFAULT_FAST_HEIGHT.toInt(), DEFAULT_HIGH_HEIGHT.toInt()))
            }
        }
    }
}

// Currently there are two language supported: resource values/ is English, values-ru/ is Russian,
// so a Russian system gets Russian and every other system gets English
private val LANGUAGES = listOf("ru" to R.string.language_ru, "en" to R.string.language_en)

// AppCompat owns the language setting: setApplicationLocales() writes it, and it
// hides the difference between API 33+ (system option) and below 33 (its own store).
// After setApplicationLocales() the activity is recreated with the new configuration,
// so this composable needs neither `remember`, nor a StateFlow, nor a write to DataStore.
// The selected chip corresponds to the language the resources actually resolved to.
@Composable
private fun LanguageChips() {
    val current = LocalConfiguration.current.locales[0].language
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LANGUAGES.forEach { (tag, label) ->
            FilterChip(
                selected = current == tag,
                onClick = { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag)) },
                label = { Text(stringResource(label), maxLines = 1) },
            )
        }
    }
}

// A tree Uri's document id reads like "primary:Movies/Rutube"; show it as a path.
private fun folderLabel(uri: Uri): String =
    DocumentsContract.getTreeDocumentId(uri).replaceFirst("primary:", "/").replace(':', '/')
