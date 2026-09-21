package io.github.nlinker.rutubedl

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import io.github.nlinker.rutubedl.bindings.VariantInfo

// Two rows, one per Choice
@Composable
fun QualityList(variants: List<VariantInfo>, settings: AppSettings, onClick: (Choice) -> Unit) {
    Column {
        Choice.entries.forEach { choice ->
            val variant = settings.resolve(choice, variants) ?: return@forEach
            QualityRow(
                label = stringResource(
                    R.string.quality_row,
                    stringResource(choice.label),
                    variant.height.toInt(),
                    fileSize(variant.estimatedSize)
                ),
                selected = settings.choice == choice,
                onClick = { onClick(choice) },
            )
        }
    }
}

@Composable
private fun QualityRow(label: String, selected: Boolean, onClick: () -> Unit) {
    // `selectable` makes the whole row is like radio button;
    // the RadioButton itself gets no onClick :-(.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        RadioButton(selected = selected, onClick = null)
    }
}

private val Choice.label
    get() = when (this) {
        Choice.Fast -> R.string.quality_fast
        Choice.High -> R.string.quality_high
    }

// System formatting: "54 MB" in English, "54 Мб" in Russian.
@Composable
private fun fileSize(bytes: ULong): String =
    Formatter.formatShortFileSize(LocalContext.current, bytes.toLong())
