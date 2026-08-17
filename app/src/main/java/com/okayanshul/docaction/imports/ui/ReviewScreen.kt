package com.okayanshul.docaction.imports.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.okayanshul.docaction.core.designsystem.Confidence
import com.okayanshul.docaction.core.designsystem.DocAction
import com.okayanshul.docaction.core.designsystem.EventRow
import com.okayanshul.docaction.core.designsystem.MinTouchTarget
import com.okayanshul.docaction.core.designsystem.RowAction
import com.okayanshul.docaction.domain.CalendarEventCandidate
import com.okayanshul.docaction.domain.CandidateId
import com.okayanshul.docaction.domain.CandidateStatus
import com.okayanshul.docaction.imports.Copy
import com.okayanshul.docaction.imports.ImportState

/**
 * Everything we found, before anything happens.
 *
 * The screen is built around one idea: **the user should only have to read the rows that
 * need them.** Ready rows are quiet and already ticked; the two that are uncertain carry the
 * tint, the reason, and the fix. A list of forty-two identical cards would be read by nobody,
 * and "review before we change anything" would become a formality rather than a safeguard.
 *
 * Confidence appears as ✓ / ⚠, never as a percentage — a number invites calibration against
 * a scale the user has no basis for.
 */
@Composable
fun ReviewScreen(
    state: ImportState.Reviewing,
    onToggle: (CandidateId) -> Unit,
    onEdit: (CandidateId) -> Unit,
    onFilter: (Boolean) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onContinue: () -> Unit,
    onRescue: () -> Unit,
    onCreate: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val candidates = state.visible
    val allSelected = state.selected.size == state.review.candidates.size

    Column(modifier = modifier.fillMaxSize()) {
        Header(state, onBack)

        Row(
            modifier = Modifier.padding(
                horizontal = DocAction.space.default,
                vertical = DocAction.space.snug,
            ),
            horizontalArrangement = Arrangement.spacedBy(DocAction.space.snug),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = !state.showOnlyAttention,
                onClick = { onFilter(false) },
                label = { Text("All ${state.review.candidates.size}", style = DocAction.type.label) },
            )
            if (state.attention > 0) {
                FilterChip(
                    selected = state.showOnlyAttention,
                    onClick = { onFilter(true) },
                    label = { Text("Needs a look ${state.attention}", style = DocAction.type.label) },
                )
            }
            TextButton(
                onClick = { onSelectAll(!allSelected) },
                modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
            ) {
                Text(if (allSelected) "Clear all" else "Select all", style = DocAction.type.label)
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        LazyColumn(modifier = Modifier.weight(1f)) {
            // Grouped by day, in the order the day occurs, because that is how anyone
            // holding a timetable reads it.
            candidates.groupBy { Format.grouping(it) }.forEach { (day, rows) ->
                item(key = "header-$day") { DayHeading(day) }

                items(rows, key = { it.id.value }) { candidate ->
                    val needsLook = candidate.status == CandidateStatus.NeedsAttention
                    EventRow(
                        title = candidate.title,
                        time = Format.whenLine(candidate),
                        detail = candidate.location,
                        state = if (needsLook) Confidence.Check else Confidence.Ready,
                        selected = candidate.id in state.selected,
                        onToggle = { onToggle(candidate.id) },
                        onEdit = { onEdit(candidate.id) },
                        reason = if (needsLook) reasonFor(candidate, state) else null,
                        actions = if (needsLook) {
                            val label = candidate.assumptions.firstOrNull()
                                ?.let(Copy::fixLabel)
                                ?: "Fix this"
                            listOf(RowAction(label) { onEdit(candidate.id) })
                        } else {
                            emptyList()
                        },
                        modifier = Modifier.padding(horizontal = DocAction.space.snug),
                    )
                }
            }

            if (state.review.unresolved.isNotEmpty()) {
                item(key = "unresolved") { UnresolvedNote(state.review.unresolved.size) }
            }

            // Adding one more by hand is always available, and is the only way to add
            // anything at all when the list started empty.
            item(key = "create") {
                TextButton(
                    onClick = onCreate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = MinTouchTarget),
                ) {
                    Text("Add another event", style = DocAction.type.label)
                }
            }

            // The engine can find a schedule and find the wrong one — a bulletin's holiday
            // list instead of the timetable two pages later. Without a way out of that, the
            // only options are importing rubbish or starting over. Meaningless when there is
            // no document behind the list, so it is not offered there.
            if (!state.isManual) {
                item(key = "rescue") {
                    TextButton(
                        onClick = onRescue,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = DocAction.space.section)
                            .sizeIn(minHeight = MinTouchTarget),
                    ) {
                        Text(
                            text = "This isn't the right part of the document",
                            style = DocAction.type.label,
                        )
                    }
                }
            }
        }

        BottomBar(
            label = if (state.selected.isEmpty()) {
                "Nothing selected"
            } else {
                "Continue with ${Copy.countOf(state.selected.size, "event")}"
            },
            enabled = state.selected.isNotEmpty(),
            onClick = onContinue,
        )
    }
}

@Composable
private fun Header(state: ImportState.Reviewing, onBack: () -> Unit) {
    Column(
        modifier = Modifier.padding(
            start = DocAction.space.default,
            end = DocAction.space.default,
            top = DocAction.space.default,
        ),
    ) {
        TextButton(
            onClick = onBack,
            modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
        ) {
            Text("Cancel", style = DocAction.type.label)
        }
        Text(
            // "3 events found" would be an odd thing to say about three the user just typed.
            text = when {
                !state.isManual -> "${Copy.countOf(state.review.candidates.size, "event")} found"
                state.review.candidates.isEmpty() -> "Your events"
                else -> Copy.countOf(state.review.candidates.size, "event")
            },
            style = DocAction.type.title,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = when {
                state.isManual && state.review.candidates.isEmpty() ->
                    "Add an event and it'll appear here."

                state.attention == 0 ->
                    "Nothing has been added yet. Check them over, then continue."

                else -> "${Copy.countOf(state.attention, "event")} " +
                    "${if (state.attention == 1) "needs" else "need"} a look before we add anything."
            },
            style = DocAction.type.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = DocAction.space.tight),
        )
    }
}

@Composable
private fun DayHeading(day: String) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Text(
            text = day,
            style = DocAction.type.label,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = DocAction.space.default,
                    end = DocAction.space.default,
                    top = DocAction.space.default,
                    bottom = DocAction.space.tight,
                )
                .semantics { heading() },
        )
    }
}

/**
 * Says out loud what could not be read.
 *
 * Entries the engine refused to turn into events are not silently dropped: a user who knows
 * their timetable has 24 classes and sees 22 deserves to be told why, not left to discover
 * the gap in three weeks.
 */
@Composable
private fun UnresolvedNote(count: Int) {
    Text(
        text = "${Copy.countOf(count, "other line")} in this document couldn't be read as " +
            "${if (count == 1) "an event" else "events"}. " +
            "They were left out rather than guessed at.",
        style = DocAction.type.meta,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            horizontal = DocAction.space.default,
            vertical = DocAction.space.section,
        ),
    )
}

@Composable
internal fun BottomBar(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    secondary: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(DocAction.space.default),
        verticalArrangement = Arrangement.spacedBy(DocAction.space.snug),
    ) {
        secondary?.invoke()
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(DocAction.radius.xl),
            modifier = Modifier
                .fillMaxWidth()
                .sizeIn(minHeight = MinTouchTarget),
        ) {
            Text(label, style = DocAction.type.label)
        }
    }
}

/**
 * Why this row is flagged, in the user's words.
 *
 * Prefers the specific question the extractor recorded for this entry; falls back to a
 * plain statement rather than inventing a reason it does not have.
 */
private fun reasonFor(candidate: CalendarEventCandidate, state: ImportState.Reviewing): String =
    // What we invented comes first. "When does this end?" is the wrong thing to say about a
    // row where we have already supplied an answer — the user needs to know we filled it,
    // not be asked a question the row appears to have answered.
    candidate.assumptions.firstOrNull()?.let(Copy::assumption)
        ?: state.review.unresolved
            .firstOrNull { it.entryId == candidate.entryId }
            ?.let { Copy.question(it.field) }
        ?: "Some of this was hard to read. Worth a glance."
