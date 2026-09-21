package io.github.nlinker.rutubedl

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val download by viewModel.downloadState.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
            OutlinedTextField(
                value = state.url,
                onValueChange = viewModel::setUrl,
                label = { Text(stringResource(R.string.url_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::probe, enabled = state.probe != ProbeState.Loading) {
                    Text(stringResource(R.string.probe))
                }
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            viewModel.startDownload(context)
                        }
                    },
                    enabled = state.url.isNotBlank() && download !is DownloadState.Running,
                ) {
                    Text(stringResource(R.string.download))
                }
            }

            when (val probe = state.probe) {
                ProbeState.Idle -> {}
                ProbeState.Loading -> Text(stringResource(R.string.probing))
                is ProbeState.Done -> {
                    val info = probe.info
                    Text(info.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(
                            R.string.info_resolution,
                            info.width.toInt(),
                            info.height.toInt()
                        )
                    )
                    Text(stringResource(R.string.info_segments, info.segments.toInt()))
                    Text(stringResource(R.string.info_file_name, info.fileName))
                }

                is ProbeState.Failed -> Text(
                    stringResource(R.string.error_prefix, probe.message),
                    color = MaterialTheme.colorScheme.error,
                )
            }

            when (val current = download) {
                DownloadState.Idle -> {}
                is DownloadState.Running -> DownloadProgress(
                    current,
                    onCancel = { viewModel.cancelDownload(context) })

                is DownloadState.Done -> {
                    Text(stringResource(R.string.download_done, current.name))
                    OutlinedButton(onClick = { context.startActivity(view(current)) }) {
                        Text(stringResource(R.string.open))
                    }
                }

                is DownloadState.Failed -> Text(
                    stringResource(R.string.error_prefix, current.message),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun DownloadProgress(state: DownloadState.Running, onCancel: () -> Unit) {
    Text(state.title, style = MaterialTheme.typography.titleMedium)
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

private fun view(done: DownloadState.Done): Intent =
    Intent(Intent.ACTION_VIEW)
        .setDataAndType(done.uri, "video/mp4")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
