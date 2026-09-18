package io.github.nlinker.rutubedl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nlinker.rutubedl.bindings.Quality

// Qualities offered as chips; other heights are not reachable from the UI yet.
private val QUALITIES = listOf(Quality.Worst, Quality.Height(720u), Quality.Height(1080u), Quality.Best)

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold { innerPadding ->
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QUALITIES.forEach { quality ->
                    FilterChip(
                        selected = state.quality == quality,
                        onClick = { viewModel.setQuality(quality) },
                        label = { Text(qualityLabel(quality)) },
                    )
                }
            }

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
        }
    }
}

@Composable
private fun qualityLabel(quality: Quality): String = when (quality) {
    Quality.Worst -> stringResource(R.string.quality_worst)
    Quality.Best -> stringResource(R.string.quality_best)
    is Quality.Height -> "${quality.height}p"
}
