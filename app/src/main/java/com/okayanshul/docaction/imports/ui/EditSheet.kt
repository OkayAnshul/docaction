package com.okayanshul.docaction.imports.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.okayanshul.docaction.core.designsystem.DocAction
import com.okayanshul.docaction.core.designsystem.MinTouchTarget
import com.okayanshul.docaction.domain.CalendarEventCandidate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Correcting one event.
 *
 * Pickers are the platform's own — a date is chosen on a calendar and a time on a clock,
 * never typed into a text field, because a typed date is exactly the ambiguity
 * (`03/04` — March or April?) the whole engine refuses to guess at.
 *
 * Save is disabled until the result is an event that can actually exist. The sheet never
 * silently repairs a bad combination: if the times make no sense, it says so and waits.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditSheet(
    candidate: CalendarEventCandidate,
    onDismiss: () -> Unit,
    onSave: (CalendarEventCandidate) -> Unit,
    onShowSource: () -> Unit,
) {
    var title by remember(candidate.id) { mutableStateOf(candidate.title) }
    var location by remember(candidate.id) { mutableStateOf(candidate.location.orEmpty()) }
    var date by remember(candidate.id) { mutableStateOf(candidate.start.toLocalDate()) }

    // An all-day row has no clock time to edit, and reading one off `start`/`end` would put
    // 12:00 AM in both fields and disable Save for ever — `end` is the next midnight, so it
    // is never after `start`. The sheet offers to *add* a time instead, and only then shows
    // the pickers.
    var timed by remember(candidate.id) { mutableStateOf(!candidate.isAllDay) }
    var start by remember(candidate.id) {
        mutableStateOf(if (candidate.isAllDay) DEFAULT_START else candidate.start.toLocalTime())
    }
    var end by remember(candidate.id) {
        mutableStateOf(
            if (candidate.isAllDay) DEFAULT_START.plusHours(1) else candidate.end.toLocalTime(),
        )
    }

    var picking by remember { mutableStateOf(Picking.None) }

    val zone = candidate.start.zone
    // An end at or before the start means the event crosses midnight, which is legal and
    // common for night shifts. Equal times are not: a zero-length event is never padded.
    val endDate = if (end <= start) date.plusDays(1) else date
    val edited = if (timed) {
        candidate.edited(
            title = title,
            start = ZonedDateTime.of(date, start, zone),
            end = ZonedDateTime.of(endDate, end, zone),
            location = location.ifBlank { null },
        )
    } else {
        // Still editable while it stays all-day: the title, the date and the place.
        candidate.editedAllDay(title = title, date = date, location = location.ifBlank { null })
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DocAction.space.default)
                .padding(bottom = DocAction.space.section),
            verticalArrangement = Arrangement.spacedBy(DocAction.space.default),
        ) {
            Text(
                text = "Edit this event",
                style = DocAction.type.title,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Name") },
                singleLine = true,
                isError = title.isBlank(),
                supportingText = if (title.isBlank()) {
                    { Text("An event needs a name.") }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Field(
                label = if (candidate.recurrence != null) "First class" else "Date",
                value = Format.date(date),
                onClick = { picking = Picking.Date },
            )

            if (!timed) {
                Field(
                    label = "Time",
                    value = "All day — add a time",
                    onClick = { timed = true },
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(DocAction.space.snug)) {
                    Field(
                        label = "Starts",
                        value = Format.time(ZonedDateTime.of(date, start, zone)),
                        onClick = { picking = Picking.Start },
                        modifier = Modifier.weight(1f),
                    )
                    Field(
                        label = "Ends",
                        value = Format.time(ZonedDateTime.of(endDate, end, zone)),
                        onClick = { picking = Picking.End },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (timed && end == start) {
                Text(
                    text = "This starts and ends at the same time. Choose an end time.",
                    style = DocAction.type.meta,
                    color = DocAction.confidence.invalidFg,
                )
            } else if (timed && endDate != date) {
                Text(
                    text = "This ends the next day.",
                    style = DocAction.type.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = location,
                onValueChange = { location = it },
                label = { Text("Where (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            candidate.recurrence?.let {
                Text(
                    text = "Repeats every ${Format.day(ZonedDateTime.of(date, start, zone)).lowercase()} " +
                        "until ${Format.date(it.until)}.",
                    style = DocAction.type.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Offered before Save, not after: someone about to correct a value wants to see
            // what we read first, and checking our work is the point of the feature.
            TextButton(
                onClick = onShowSource,
                modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
            ) {
                Text("Where did this come from?", style = DocAction.type.label)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(DocAction.space.snug)) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .sizeIn(minHeight = MinTouchTarget),
                ) {
                    Text("Cancel", style = DocAction.type.label)
                }
                Button(
                    onClick = { edited?.let(onSave) },
                    enabled = edited != null,
                    modifier = Modifier
                        .weight(1f)
                        .sizeIn(minHeight = MinTouchTarget),
                ) {
                    Text("Save", style = DocAction.type.label)
                }
            }
        }
    }

    when (picking) {
        Picking.None -> Unit

        Picking.Date -> DatePickDialog(date) { picked ->
            picked?.let { date = it }
            picking = Picking.None
        }

        Picking.Start -> TimePickDialog("Start time", start) { picked ->
            picked?.let { start = it }
            picking = Picking.None
        }

        Picking.End -> TimePickDialog("End time", end) { picked ->
            picked?.let { end = it }
            picking = Picking.None
        }
    }
}

private enum class Picking { None, Date, Start, End }

/** Where the clock starts when a user turns an all-day item into a timed one. */
private val DEFAULT_START: LocalTime = LocalTime.of(9, 0)

@Composable
private fun Field(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .sizeIn(minHeight = MinTouchTarget),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label,
                style = DocAction.type.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = value, style = DocAction.type.subject)
        }
    }
}
