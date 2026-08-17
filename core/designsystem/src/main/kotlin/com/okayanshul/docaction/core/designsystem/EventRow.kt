package com.okayanshul.docaction.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

/** One inline fix offered on a row that needs attention. */
data class RowAction(val label: String, val onClick: () -> Unit)

/**
 * One extracted event in the review list.
 *
 * The design carries a single idea: **the interface gets quieter as confidence rises.** A
 * ready row has no container, no tint and no elevation — just three lines and a small tick.
 * A row needing attention gains a tinted container, a plain-language reason, and its fix
 * right there.
 *
 * That contrast is the whole review screen. Forty-two identical cards would mean the user
 * reads none of them; here the two that matter are the only ones with visual weight.
 */
@Composable
fun EventRow(
    title: String,
    time: String,
    detail: String?,
    state: Confidence,
    selected: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    reason: String? = null,
    actions: List<RowAction> = emptyList(),
) {
    val needsAttention = state != Confidence.Ready
    val colours = DocAction.confidence

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (needsAttention) {
                    Modifier.background(colours.checkBg, RoundedCornerShape(DocAction.radius.md))
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onEdit)
            .padding(DocAction.space.default),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(
            checked = selected,
            onCheckedChange = { onToggle() },
            modifier = Modifier.sizeIn(minWidth = MinTouchTarget, minHeight = MinTouchTarget),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = DocAction.space.snug),
            verticalArrangement = Arrangement.spacedBy(DocAction.space.hairline),
        ) {
            // The static content announces as one sentence — read as five unlabelled
            // fragments, a 42-row list is exhausting. The merge stops at this column so the
            // checkbox and the inline fixes below stay separately reachable; clearing
            // semantics on the whole row instead made "Set end time" invisible to TalkBack.
            Column(
                verticalArrangement = Arrangement.spacedBy(DocAction.space.hairline),
                modifier = Modifier.semantics(mergeDescendants = true) {
                    contentDescription = buildString {
                        append(title); append(", "); append(time)
                        detail?.let { append(", "); append(it) }
                        append(", ")
                        append(if (needsAttention) reason ?: "needs attention" else "ready")
                        append(", ")
                        append(if (selected) "selected" else "not selected")
                    }
                },
            ) {
                Text(text = title, style = DocAction.type.subject, color = MaterialTheme.colorScheme.onSurface)
                Text(text = time, style = DocAction.type.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                detail?.let {
                    Text(text = it, style = DocAction.type.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                if (needsAttention && reason != null) {
                    Text(
                        text = reason,
                        style = DocAction.type.meta,
                        color = colours.checkFg,
                        modifier = Modifier.padding(top = DocAction.space.tight),
                    )
                }
            }

            if (actions.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(DocAction.space.snug)) {
                    actions.forEach { action ->
                        TextButton(
                            onClick = action.onClick,
                            modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
                        ) {
                            Text(action.label, style = DocAction.type.label)
                        }
                    }
                }
            }
        }

        ConfidenceBadge(state = state, showLabel = false)
    }
}
