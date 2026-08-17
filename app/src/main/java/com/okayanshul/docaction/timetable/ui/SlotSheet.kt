package com.okayanshul.docaction.timetable.ui

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
import androidx.compose.material3.FilterChip
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
import com.okayanshul.docaction.core.database.TimetableSlotEntity
import com.okayanshul.docaction.core.designsystem.DocAction
import com.okayanshul.docaction.core.designsystem.MinTouchTarget
import com.okayanshul.docaction.imports.ui.TimePickDialog
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * One slot in the week, being added or corrected.
 *
 * The weekly view was read-only, which made it a picture of a timetable rather than a
 * timetable — a room change meant re-importing the whole document, and a class the engine
 * missed could never be added at all.
 *
 * "Also on" is here because it is the shape of the actual problem: a lab that runs Tuesday
 * and Thursday is one thing the user should describe once. Offering it at the point of entry
 * costs nothing and saves the most common piece of repetition in a timetable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlotSheet(
    /** Null when adding. */
    slot: TimetableSlotEntity?,
    onDismiss: () -> Unit,
    onSave: (DayOfWeek, LocalTime, LocalTime, String, String?) -> Unit,
    onDelete: (() -> Unit)?,
    onDuplicate: ((Set<DayOfWeek>) -> Unit)?,
    defaultDay: DayOfWeek = DayOfWeek.MONDAY,
) {
    val creating = slot == null

    var title by remember(slot?.id) { mutableStateOf(slot?.title.orEmpty()) }
    var location by remember(slot?.id) { mutableStateOf(slot?.location.orEmpty()) }
    var day by remember(slot?.id) {
        mutableStateOf(slot?.let { DayOfWeek.of(it.weekday) } ?: defaultDay)
    }
    var start by remember(slot?.id) { mutableStateOf(minutesToTime(slot?.startMinute ?: 9 * 60)) }
    var end by remember(slot?.id) { mutableStateOf(minutesToTime(slot?.endMinute ?: 10 * 60)) }
    var alsoOn by remember(slot?.id) { mutableStateOf(emptySet<DayOfWeek>()) }
    var picking by remember { mutableStateOf<Picking?>(null) }

    // A zero-length class is not a class. Crossing midnight is legal for a night shift but
    // meaningless for a weekly slot, so the rule here is simply "end after start".
    val valid = title.isNotBlank() && end > start

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
                text = if (creating) "Add to your week" else "Edit this class",
                style = DocAction.type.title,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Day", style = DocAction.type.label, color = MaterialTheme.colorScheme.primary)
            DayChips(selected = setOf(day), onToggle = { day = it })

            Row(horizontalArrangement = Arrangement.spacedBy(DocAction.space.snug)) {
                TimeField("Starts", start, Modifier.weight(1f)) { picking = Picking.Start }
                TimeField("Ends", end, Modifier.weight(1f)) { picking = Picking.End }
            }

            if (end <= start) {
                Text(
                    text = "The end time needs to be after the start time.",
                    style = DocAction.type.meta,
                    color = DocAction.confidence.invalidFg,
                )
            }

            OutlinedTextField(
                value = location,
                onValueChange = { location = it },
                label = { Text("Room (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // Offered only when editing an existing slot: duplicating something that has not
            // been saved yet would ask the user to reason about two things at once.
            if (onDuplicate != null && !creating) {
                Text(
                    text = "Also on",
                    style = DocAction.type.label,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Copy this class onto other days.",
                    style = DocAction.type.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DayChips(
                    selected = alsoOn,
                    exclude = day,
                    onToggle = { picked ->
                        alsoOn = if (picked in alsoOn) alsoOn - picked else alsoOn + picked
                    },
                )
                if (alsoOn.isNotEmpty()) {
                    TextButton(
                        onClick = { onDuplicate(alsoOn) },
                        modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
                    ) {
                        Text(
                            text = "Copy to ${alsoOn.size} more " +
                                if (alsoOn.size == 1) "day" else "days",
                            style = DocAction.type.label,
                        )
                    }
                }
            }

            onDelete?.let { delete ->
                TextButton(onClick = delete, modifier = Modifier.sizeIn(minHeight = MinTouchTarget)) {
                    Text(
                        text = "Remove from my week",
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
                    onClick = { onSave(day, start, end, title, location.ifBlank { null }) },
                    enabled = valid,
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
        null -> Unit
        Picking.Start -> TimePickDialog("Start time", start) { picked ->
            picked?.let {
                // Keeping the length is what someone moving a class expects; recomputing it
                // from a stale end time is how a 09:00–10:00 class becomes nine hours long.
                val length = java.time.Duration.between(start, end)
                start = it
                end = it.plus(length)
            }
            picking = null
        }

        Picking.End -> TimePickDialog("End time", end) { picked ->
            picked?.let { end = it }
            picking = null
        }
    }
}

@Composable
private fun DayChips(
    selected: Set<DayOfWeek>,
    onToggle: (DayOfWeek) -> Unit,
    exclude: DayOfWeek? = null,
) {
    // Wraps rather than scrolls: seven three-letter chips fit at phone width, and a
    // horizontally scrolling row hides days behind an edge nobody thinks to drag.
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(DocAction.space.tight),
        modifier = Modifier.fillMaxWidth(),
    ) {
        DayOfWeek.entries.filter { it != exclude }.forEach { day ->
            FilterChip(
                selected = day in selected,
                onClick = { onToggle(day) },
                label = { Text(day.shortName()) },
                modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
            )
        }
    }
}

@Composable
private fun TimeField(
    label: String,
    value: LocalTime,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
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
            Text(
                text = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(value),
                style = DocAction.type.subject,
            )
        }
    }
}

private enum class Picking { Start, End }

internal fun minutesToTime(minutes: Int): LocalTime =
    LocalTime.of((minutes / 60).coerceIn(0, 23), minutes % 60)

internal fun DayOfWeek.shortName(): String =
    name.take(3).lowercase().replaceFirstChar(Char::titlecase)
