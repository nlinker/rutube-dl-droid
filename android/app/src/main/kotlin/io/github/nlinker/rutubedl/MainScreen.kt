package io.github.nlinker.rutubedl

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()

    // First run: no folder saved yet, so open the picker before anything else.
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree(), viewModel::pickFolder)
    LaunchedEffect(Unit) {
        viewModel.promptFolder.collect { pickFolder.launch(null) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = viewModel::openSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.url,
                onValueChange = viewModel::setUrl,
                label = { Text(stringResource(R.string.url_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(stringResource(R.string.quality_label), style = MaterialTheme.typography.labelLarge)
            QualityChips(selected = state.quality, onSelect = viewModel::setQuality)

            Button(onClick = viewModel::probe, enabled = state.probe != ProbeState.Loading) {
                Text(stringResource(R.string.probe))
            }

            when (val probe = state.probe) {
                ProbeState.Idle -> {}
                ProbeState.Loading -> Text(stringResource(R.string.probing))
                is ProbeState.Done -> {
                    val info = probe.info
                    Text(info.title, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.info_resolution, info.width.toInt(), info.height.toInt()))
                    Text(stringResource(R.string.info_segments, info.segments.toInt()))
                    Text(stringResource(R.string.info_file_name, info.fileName))
                }
                is ProbeState.Failed -> Text(
                    stringResource(R.string.error_prefix, probe.message),
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (settings != null && settings?.folder == null) {
                FolderPrompt(rejected = state.folderRejected, onPick = { pickFolder.launch(null) })
            }
        }
    }
}

// Shown while no usable folder is saved; the download button will hang off this later.
@Composable
private fun FolderPrompt(rejected: Boolean, onPick: () -> Unit) {
    if (rejected) {
        Text(stringResource(R.string.folder_not_local), color = MaterialTheme.colorScheme.error)
    }
    Button(onClick = onPick) { Text(stringResource(R.string.folder_pick)) }
}
