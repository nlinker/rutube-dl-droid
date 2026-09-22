package io.github.nlinker.rutubedl

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import io.github.nlinker.rutubedl.bindings.VariantInfo

// The heights 144, 240, ... up to 480 are counted as "fast", above it, as "high".
private const val FAST_MAX_HEIGHT = 480u

private val UInt.category: Choice
    get() = if (this <= FAST_MAX_HEIGHT) Choice.Fast else Choice.High

// Collapsed: two rows, one per Choice, resolved against this video. The arrow expands them in place
// into every resolution the video has; picking one becomes the new preference of its category.
@Composable
fun QualityList(variants: List<VariantInfo>, settings: AppSettings, onChoose: (Choice) -> Unit, onPick: (Choice, UInt) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selectedHeight = settings.resolve(settings.choice, variants)?.height

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.quality_label),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { expanded = !expanded }) {
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(if (expanded) R.string.quality_collapse else R.string.quality_expand),
                )
            }
        }

        AnimatedVisibility(visible = !expanded) {
            Column {
                Choice.entries.forEach { choice ->
                    val variant = settings.resolve(choice, variants) ?: return@forEach
                    QualityRow(
                        label = rowLabel(choice, variant),
                        selected = settings.choice == choice,
                        onClick = { onChoose(choice) },
                    )
                }
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column {
                variants.forEach { variant ->
                    QualityRow(
                        label = rowLabel(variant.height.category, variant),
                        selected = variant.height == selectedHeight,
                        onClick = {
                            onPick(variant.height.category, variant.height)
                            expanded = false
                        },
                    )
                }
            }
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

// Construct row label like "Fast (360p) · 54 MB".
@Composable
private fun rowLabel(choice: Choice, variant: VariantInfo): String = stringResource(
    R.string.quality_row,
    stringResource(choice.label),
    variant.height.toInt(),
    fileSize(variant.estimatedSize),
)

private val Choice.label
    get() = when (this) {
        Choice.Fast -> R.string.quality_fast
        Choice.High -> R.string.quality_high
    }

// System formatting: "54 MB" in English, "54 Мб" in Russian.
@Composable
private fun fileSize(bytes: ULong): String =
    Formatter.formatShortFileSize(LocalContext.current, bytes.toLong())
