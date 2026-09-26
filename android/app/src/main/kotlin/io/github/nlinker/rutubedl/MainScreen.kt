package io.github.nlinker.rutubedl

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.selectAll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloads by viewModel.downloadState.collectAsStateWithLifecycle()
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    // The notification carries the progress bar, so ask before the first download.
    val askNotifications =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            viewModel.startDownload(context)
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = viewModel::openSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings)
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            UrlField(viewModel.url)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        viewModel.probe()
                    },
                    enabled = state.probe != ProbeState.Loading,
                ) {
                    Text(stringResource(R.string.probe))
                }
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            viewModel.startDownload(context)
                        }
                    },
                    enabled = viewModel.url.text.isNotBlank(),
                ) {
                    Text(stringResource(R.string.download))
                }
            }

            when (val probe = state.probe) {
                ProbeState.Idle -> {}
                ProbeState.Loading -> Text(stringResource(R.string.probing))
                is ProbeState.Done -> {
                    val info = probe.info
                    SelectionContainer {
                        Text(info.title, style = MaterialTheme.typography.titleMedium)
                    }
                    Text(stringResource(R.string.info_segments, info.segments.toInt()))
                    settings?.let {
                        QualityList(
                            info.variants,
                            it,
                            viewModel::setChoice,
                            viewModel::setHeight
                        )
                    }
                }

                is ProbeState.Failed -> Text(
                    stringResource(R.string.error_prefix, probe.message),
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // The list proper comes in the next step; for now the newest entry, as before.
            when (val current = downloads.lastOrNull()?.state) {
                null, TaskState.Waiting -> {}
                is TaskState.Running -> DownloadProgress(
                    title = downloads.last().title ?: downloads.last().task.url.value,
                    state = current,
                    onCancel = { viewModel.cancelDownload(downloads.last().task.id) },
                )

                is TaskState.Done -> {
                    Text(stringResource(R.string.download_done, current.name))
                    OutlinedButton(onClick = { context.startActivity(view(current)) }) {
                        Text(stringResource(R.string.open))
                    }
                }

                is TaskState.Failed -> Text(
                    stringResource(R.string.error_prefix, current.message),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// Behaves like a browser's address bar: the first tap selects the whole text.
// A second tap places the cursor as usual.
@Composable
private fun UrlField(field: TextFieldState) {
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }

    Box {
        OutlinedTextField(
            state = field,
            label = { Text(stringResource(R.string.url_label)) },
            lineLimits = TextFieldLineLimits.SingleLine,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused },
        )
        // Workaround for a tap on the unfocused field placing the cursor on finger release
        // and removing the selection (we want the selection to stay). The overlay box intercepts
        // taps for the unfocused field.
        if (!focused) {
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(Unit) {
                        detectTapGestures {
                            focusRequester.requestFocus()
                            field.edit { selectAll() }
                        }
                    },
            )
        }
    }
}

@Composable
private fun DownloadProgress(title: String, state: TaskState.Running, onCancel: () -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (state.total == 0) {
        Text(stringResource(R.string.download_starting))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    } else {
        Text(stringResource(R.string.download_progress, state.done, state.total))
        LinearProgressIndicator(
            progress = { state.done.toFloat() / state.total },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
}

private fun view(done: TaskState.Done): Intent =
    Intent(Intent.ACTION_VIEW)
        .setDataAndType(done.uri.value.toUri(), "video/mp4")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
