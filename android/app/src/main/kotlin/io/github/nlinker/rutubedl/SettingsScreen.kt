package io.github.nlinker.rutubedl

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
            Text(stringResource(R.string.folder_label), style = MaterialTheme.typography.labelLarge)
            Text(settings?.folder?.let(::folderLabel) ?: stringResource(R.string.folder_none))
            if (state.folderRejected) {
                Text(stringResource(R.string.folder_not_local), color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = { pickFolder.launch(settings?.folder) }) { Text(stringResource(R.string.folder_pick)) }

            Text(stringResource(R.string.default_quality_label), style = MaterialTheme.typography.labelLarge)
            QualityChips(selected = settings?.quality ?: state.quality, onSelect = viewModel::setDefaultQuality)
        }
    }
}

// A tree Uri's document id reads like "primary:Movies/Rutube"; show it as a path.
private fun folderLabel(uri: Uri): String =
    DocumentsContract.getTreeDocumentId(uri).replaceFirst("primary:", "/").replace(':', '/')
