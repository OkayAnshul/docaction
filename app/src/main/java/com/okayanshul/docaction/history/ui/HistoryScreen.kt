package com.okayanshul.docaction.history.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.okayanshul.docaction.core.database.ImportEntity
import com.okayanshul.docaction.core.designsystem.DocAction
import com.okayanshul.docaction.core.designsystem.EmptyState
import com.okayanshul.docaction.core.designsystem.MinTouchTarget
import com.okayanshul.docaction.imports.Copy
import com.okayanshul.docaction.imports.ImportViewModel
import java.util.concurrent.TimeUnit

/**
 * Everything this app has added to the user's calendar, and the way back out.
 *
 * The one screen whose whole reason for existing is reversibility. Undo used to be offered
 * only in the moment after a write, which serves the user who notices immediately and nobody
 * else — and the failure this engine is most likely to produce, importing the wrong section
 * of a workbook, is one people notice days later.
 *
 * Each row says what it did in the terms the user cares about: which document, how many
 * events, how long ago. Nothing about formats, hashes or ids.
 */
@Composable
fun HistoryScreen(
    entries: List<ImportEntity>,
    working: Boolean,
    onUndo: (ImportEntity) -> Unit,
    onForget: (ImportEntity) -> Unit,
    onImport: () -> Unit,
    now: Long = System.currentTimeMillis(),
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = "History",
            style = DocAction.type.title,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(
                    start = DocAction.space.default,
                    end = DocAction.space.default,
                    top = DocAction.space.default,
                )
                .semantics { heading() },
        )

        if (working) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (entries.isEmpty()) {
            EmptyState(
                headline = "Nothing added yet",
                body = "Once you add events to your calendar, they'll be listed here — " +
                    "and you'll be able to take any import back out again.",
                actionLabel = "Add a document",
                onAction = onImport,
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(entries, key = { it.id }) { entry ->
                HistoryRow(entry, now, onUndo = { onUndo(entry) }, onForget = { onForget(entry) })
            }
        }
    }
}

@Composable
private fun HistoryRow(
    entry: ImportEntity,
    now: Long,
    onUndo: () -> Unit,
    onForget: () -> Unit,
) {
    val reverted = entry.state == ImportViewModel.STATE_REVERTED

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DocAction.space.default, vertical = DocAction.space.tight)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(DocAction.radius.md),
            )
            .padding(DocAction.space.default),
        verticalArrangement = Arrangement.spacedBy(DocAction.space.tight),
    ) {
        Text(
            text = entry.displayName,
            style = DocAction.type.subject,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
        Text(
            text = summarise(entry, now, reverted),
            style = DocAction.type.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(DocAction.space.snug)) {
            // Offered only while there is something to remove. A dead "Undo" on an import
            // already taken back is a button that teaches people the app is broken.
            if (!reverted && entry.committedCount > 0) {
                TextButton(onClick = onUndo, modifier = Modifier.sizeIn(minHeight = MinTouchTarget)) {
                    Text("Undo this import", style = DocAction.type.label)
                }
            }
            TextButton(onClick = onForget, modifier = Modifier.sizeIn(minHeight = MinTouchTarget)) {
                Text("Remove from list", style = DocAction.type.label)
            }
        }
    }
}

/**
 * Asks before removing anything, by name and by count.
 *
 * Phrased as *the events created from this document* rather than as a date range, because
 * that is what undo actually does — and saying it that way is what tells the user their own
 * events are not at risk.
 */
@Composable
fun ConfirmUndoDialog(entry: ImportEntity, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove these events?") },
        text = {
            Text(
                "This removes the ${Copy.countOf(entry.committedCount, "event")} added from " +
                    "\"${entry.displayName}\". Nothing else in your calendar is touched.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Remove ${Copy.countOf(entry.committedCount, "event")}")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep them") } },
    )
}

private fun summarise(entry: ImportEntity, now: Long, reverted: Boolean): String {
    val when_ = ago(now - (entry.completedAt ?: entry.startedAt))
    return when {
        reverted -> "Taken back · $when_"
        entry.committedCount == 0 -> "Nothing was added · $when_"
        entry.committedCount < entry.candidateCount ->
            "${entry.committedCount} of ${entry.candidateCount} added · $when_"

        else -> "${Copy.countOf(entry.committedCount, "event")} · $when_"
    }
}

/** Relative, and deliberately coarse: nobody needs "3 hours and 12 minutes ago". */
private fun ago(millis: Long): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
    val hours = TimeUnit.MILLISECONDS.toHours(millis)
    val days = TimeUnit.MILLISECONDS.toDays(millis)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${Copy.countOf(minutes.toInt(), "minute")} ago"
        hours < 24 -> "${Copy.countOf(hours.toInt(), "hour")} ago"
        days < 7 -> "${Copy.countOf(days.toInt(), "day")} ago"
        days < 30 -> "${Copy.countOf((days / 7).toInt(), "week")} ago"
        else -> "${Copy.countOf((days / 30).toInt(), "month")} ago"
    }
}
