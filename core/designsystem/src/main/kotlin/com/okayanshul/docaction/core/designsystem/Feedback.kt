package com.okayanshul.docaction.core.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign

/** One line of the processing screen. [Skipped] stages are never rendered. */
enum class StageState { Pending, Active, Done, Skipped }

data class StageLine(val label: String, val state: StageState)

/**
 * Real progress through the pipeline.
 *
 * Every line corresponds to a stage that is actually running. Stages the document does not
 * need — OCR on a text PDF — are not shown at all, because listing work that isn't happening
 * is the same lie as a fake spinner, just dressed better.
 */
@Composable
fun StageProgress(
    stages: List<StageLine>,
    modifier: Modifier = Modifier,
    determinate: Float? = null,
    detail: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // Announces stage changes politely, so a screen-reader user knows work is moving.
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(DocAction.space.snug),
    ) {
        stages.filterNot { it.state == StageState.Skipped }.forEach { stage ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                val glyph = when (stage.state) {
                    StageState.Done -> "✓"
                    StageState.Active -> "●"
                    else -> "○"
                }
                val colour = when (stage.state) {
                    StageState.Done -> DocAction.confidence.readyFg
                    StageState.Active -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(text = glyph, style = DocAction.type.body, color = colour)
                Text(
                    text = stage.label,
                    style = DocAction.type.body,
                    color = if (stage.state == StageState.Pending) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.padding(start = DocAction.space.snug),
                )
            }
        }

        detail?.let {
            Text(
                text = it,
                style = DocAction.type.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = DocAction.space.snug),
            )
        }

        // A determinate bar only when the total is genuinely known. Users have learned to
        // distrust fake ones, and distrust is the one thing this product cannot afford.
        if (determinate != null) {
            val progress by animateFloatAsState(targetValue = determinate, label = "progress")
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = DocAction.space.snug),
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = DocAction.space.snug),
            )
        }
    }
}

/**
 * What to show when there is nothing.
 *
 * Always a real sentence with a way forward — never "No data". There is no illustration
 * budget: a well-set sentence outperforms a generic vector and costs nothing to maintain.
 */
@Composable
fun EmptyState(
    headline: String,
    body: String,
    actionLabel: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = DocAction.space.default, vertical = DocAction.space.major),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DocAction.space.snug),
    ) {
        Text(
            text = headline,
            style = DocAction.type.title,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = DocAction.type.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null) {
            Button(
                onClick = onAction,
                modifier = Modifier.padding(top = DocAction.space.default),
            ) {
                Text(actionLabel, style = DocAction.type.label)
            }
        }
    }
}
