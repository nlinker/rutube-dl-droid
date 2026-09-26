package io.github.nlinker.rutubedl

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

// Everything the queue holds, oldest first: what is downloading, what waits its turn, and what
// this session already finished or failed.
@Composable
fun DownloadList(entries: List<Entry>, onCancel: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        entries.forEach { entry ->
            when (val state = entry.state) {
                is TaskState.Waiting -> WaitingRow(entry, onCancel)
                is TaskState.Running -> RunningRow(entry, state, onCancel)
                is TaskState.Done -> DoneRow(state)
                is TaskState.Failed -> FailedRow(entry, state)
            }
        }
    }
}

@Composable
private fun WaitingRow(entry: Entry, onCancel: (Long) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.label(), maxLines = 1)
            Text(
                stringResource(R.string.download_waiting),
                style = MaterialTheme.typography.bodySmall
            )
        }
        CancelButton(entry, onCancel)
    }
}

@Composable
private fun RunningRow(entry: Entry, state: TaskState.Running, onCancel: (Long) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                entry.label(),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            CancelButton(entry, onCancel)
        }
        // `total` is 0 until the probe comes back, and a bar with no total has to be indeterminate.
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
    }
}

@Composable
private fun DoneRow(state: TaskState.Done) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.download_done, state.name), modifier = Modifier.weight(1f))
        TextButton(onClick = { context.startActivity(view(state)) }) {
            Text(stringResource(R.string.open))
        }
    }
}

@Composable
private fun FailedRow(entry: Entry, state: TaskState.Failed) {
    Column {
        Text(entry.label(), maxLines = 1)
        Text(
            stringResource(R.string.error_prefix, state.message),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun CancelButton(entry: Entry, onCancel: (Long) -> Unit) {
    IconButton(onClick = { onCancel(entry.task.id) }) {
        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
    }
}

// The link is shown until the probe answers with the title.
private fun Entry.label(): String = title ?: task.url.value

private fun view(done: TaskState.Done): Intent =
    Intent(Intent.ACTION_VIEW)
        .setDataAndType(done.uri.value.toUri(), "video/mp4")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
