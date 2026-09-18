package io.github.nlinker.rutubedl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.nlinker.rutubedl.bindings.Quality

// Qualities offered as chips; other heights are not reachable from the UI yet.
private val QUALITIES = listOf(Quality.Worst, Quality.Height(720u), Quality.Height(1080u), Quality.Best)

@Composable
fun QualityChips(selected: Quality, onSelect: (Quality) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QUALITIES.forEach { quality ->
            FilterChip(
                selected = selected == quality,
                onClick = { onSelect(quality) },
                label = { Text(qualityLabel(quality), maxLines = 1) },
            )
        }
    }
}

@Composable
private fun qualityLabel(quality: Quality): String = when (quality) {
    Quality.Worst -> stringResource(R.string.quality_worst)
    Quality.Best -> stringResource(R.string.quality_best)
    is Quality.Height -> "${quality.height}p"
}
