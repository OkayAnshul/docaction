package com.okayanshul.docaction.imports.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.LaunchedEffect
import com.okayanshul.docaction.core.designsystem.DocAction
import com.okayanshul.docaction.core.designsystem.MinTouchTarget
import com.okayanshul.docaction.domain.CalendarEventCandidate
import com.okayanshul.docaction.domain.ManualEvent
import com.okayanshul.docaction.domain.TermBounds
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * One event, being corrected or being written from scratch.
 *
 * **The same sheet does both**, because they are the same task: a person deciding what an
 * event says. Splitting them would mean two forms, two sets of validation, and one of them
 * quietly getting the midnight-crossing rule wrong.
 *
 * What differs is only what happens on Save, and it differs for a real reason. An edit goes
 * through [CalendarEventCandidate.edited], which keeps the row's identity and adds a
 * `UserProvided` source for each field actually changed — so Source View can still show the
 * document region for the values that came from it. A new event goes through [ManualEvent],
 * which builds a fresh entry whose every field is the user's. Routing edits through the
 * creation path would be simpler and would throw away the provenance that makes the review
 * screen worth trusting.
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
    /** Null when writing a new event. */
    candidate: CalendarEventCandidate?,
    zone: ZoneId,
    term: TermBounds,
    onDismiss: () -> Unit,
    onSave: (CalendarEventCandidate) -> Unit,
    /** Null when there is no document behind this event to point at. */
    onShowSource: (() -> Unit)?,
    /** Null when the row cannot be removed — an extracted row is deselected, not deleted. */
    onDelete: (() -> Unit)? = null,
    today: LocalDate = LocalDate.now(),
) {
    val creating = candidate == null
    val key = candidate?.id

    var title by remember(key) { mutableStateOf(candidate?.title.orEmpty()) }
    var location by remember(key) { mutableStateOf(candidate?.location.orEmpty()) }
    var date by remember(key) { mutableStateOf(candidate?.start?.toLocalDate() ?: today) }

    // An all-day row has no clock time to edit, and reading one off `start`/`end` would put
    // 12:00 AM in both fields and disable Save for ever — `end` is the next midnight, so it
    // is never after `start`. The sheet offers to *add* a time instead, and only then shows
    // the pickers. A new event starts timed, which is what most events are.
    var timed by remember(key) { mutableStateOf(candidate?.isAllDay?.not() ?: true) }
    var start by remember(key) {
        mutableStateOf(
            if (candidate == null || candidate.isAllDay) DEFAULT_START else candidate.start.toLocalTime(),
        )
    }
    var end by remember(key) {
        mutableStateOf(
            if (candidate == null || candidate.isAllDay) {
                DEFAULT_START.plusHours(1)
            } else {
                candidate.end.toLocalTime()
            },
        )
    }
    var repeats by remember(key) { mutableStateOf(candidate?.recurrence != null) }

    var picking by remember { mutableStateOf(Picking.None) }

    val eventZone = candidate?.start?.zone ?: zone
    // An end at or before the start means the event crosses midnight, which is legal and
    // common for night shifts. Equal times are not: a zero-length event is never padded.
    val endDate = if (end <= start) date.plusDays(1) else date

    val result: CalendarEventCandidate? = remember(
        key, title, location, date, timed, start, end, repeats,
    ) {
        buildCandidate(
            candidate = candidate,
            title = title,
            location = location,
            date = date,
            timed = timed,
            start = start,
            end = end,
            endDate = endDate,
            repeats = repeats,
            zone = eventZone,
            term = term,
        )
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
                text = if (creating) "New event" else "Edit this event",
                style = DocAction.type.title,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )

            val focus = remember { FocusRequester() }
            // Straight into the name field. Someone who chose "create an event" has already
            // decided; the fewer taps between that decision and typing, the better.
            LaunchedEffect(creating) { if (creating) runCatching { focus.requestFocus() } }

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Name") },
                singleLine = true,
                // A blank name on a brand-new event is the starting state, not a mistake.
                // Shouting at someone before they have typed anything is just rude.
                isError = title.isBlank() && !creating,
                supportingText = if (title.isBlank() && !creating) {
                    { Text("An event needs a name.") }
                } else {
                    null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )

            Field(
                label = if (repeats) "First class" else "Date",
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
                        value = Format.time(ZonedDateTime.of(date, start, eventZone)),
                        onClick = { picking = Picking.Start },
                        modifier = Modifier.weight(1f),
                    )
                    Field(
                        label = "Ends",
                        value = Format.time(ZonedDateTime.of(endDate, end, eventZone)),
                        onClick = { picking = Picking.End },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (creating) {
                    TextButton(
                        onClick = { timed = false },
                        modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
                    ) {
                        Text("Make it all day", style = DocAction.type.label)
                    }
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

            // Repeating is offered only when writing a new event and only for a timed one. A
            // weekly all-day event is almost always a mistake, and changing an extracted row's
            // recurrence belongs to the timetable, not to a single-event editor.
            if (creating && timed) {
                Row(
                    // The whole row toggles, not just the switch. A 48dp switch beside two
                    // lines of explanation is a small target next to a large dead zone.
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { repeats = !repeats }
                        .sizeIn(minHeight = MinTouchTarget),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Every week", style = DocAction.type.body)
                        Text(
                            text = if (repeats) {
                                "Every ${Format.day(ZonedDateTime.of(date, start, eventZone))
                                    .lowercase()} until ${Format.date(term.end)}. " +
                                    "One repeating event, not one per week."
                            } else {
                                "Just this once."
                            },
                            style = DocAction.type.meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = repeats, onCheckedChange = { repeats = it })
                }
            }

            if (!creating) {
                candidate?.recurrence?.let {
                    Text(
                        text = "Repeats every " +
                            "${Format.day(ZonedDateTime.of(date, start, eventZone)).lowercase()} " +
                            "until ${Format.date(it.until)}.",
                        style = DocAction.type.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Offered before Save, not after: someone about to correct a value wants to see
            // what we read first, and checking our work is the point of the feature.
            onShowSource?.let { show ->
                TextButton(onClick = show, modifier = Modifier.sizeIn(minHeight = MinTouchTarget)) {
                    Text("Where did this come from?", style = DocAction.type.label)
                }
            }

            onDelete?.let { delete ->
                TextButton(onClick = delete, modifier = Modifier.sizeIn(minHeight = MinTouchTarget)) {
                    Text(
                        text = "Remove this event",
                        style = DocAction.type.label,
                        color = DocAction.confidence.invalidFg,
                    )
                }
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
                    onClick = { result?.let(onSave) },
                    enabled = result != null,
                    modifier = Modifier
                        .weight(1f)
                        .sizeIn(minHeight = MinTouchTarget),
                ) {
                    Text(if (creating) "Add" else "Save", style = DocAction.type.label)
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

/**
 * Turns what is on screen into an event, or into null when it could not be one.
 *
 * Null disables Save. Every rejection here comes from the domain's own choke point rather
 * than from a rule restated in the UI, so the form cannot drift into accepting something the
 * engine would refuse — or into refusing something it would allow.
 */
private fun buildCandidate(
    candidate: CalendarEventCandidate?,
    title: String,
    location: String,
    date: LocalDate,
    timed: Boolean,
    start: LocalTime,
    end: LocalTime,
    endDate: LocalDate,
    repeats: Boolean,
    zone: ZoneId,
    term: TermBounds,
): CalendarEventCandidate? {
    if (title.isBlank()) return null

    if (candidate != null) {
        return if (timed) {
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
    }

    val entry = when {
        !timed -> ManualEvent.allDay(title, date, location.ifBlank { null })
        repeats -> ManualEvent.weekly(
            title = title,
            weekday = date.dayOfWeek,
            start = start,
            end = end,
            location = location.ifBlank { null },
        )

        else -> ManualEvent.dated(title, date, start, end, location.ifBlank { null })
    }

    return (ManualEvent.candidate(entry, zone, term) as? CalendarEventCandidate.Result.Accepted)
        ?.candidate
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
