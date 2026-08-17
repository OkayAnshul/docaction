package com.okayanshul.docaction.timetable.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.okayanshul.docaction.core.designsystem.DocAction
import com.okayanshul.docaction.core.designsystem.EmptyState
import com.okayanshul.docaction.core.designsystem.MinTouchTarget
import com.okayanshul.docaction.core.database.TimetableSlotEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

/**
 * The user's week.
 *
 * A day at a time, not a seven-column grid. A grid is what a timetable looks like on paper
 * and it is unreadable at phone width — seven columns of forty-character subject names — so
 * this shows one day filling the screen and a strip to move between them. It opens on today,
 * because the question someone actually has when they open this is "what do I have now".
 *
 * Editable, as of the slot write-through in the calendar executor. A weekly view you cannot
 * correct is a picture of a timetable rather than a timetable: a room change meant
 * re-importing the whole document, and a class the engine missed could never be added at all.
 */
@Composable
fun TimetableScreen(
    label: String,
    slots: List<TimetableSlotEntity>,
    onImport: () -> Unit,
    onBack: () -> Unit,
    onEditSlot: (TimetableSlotEntity) -> Unit = {},
    onAddSlot: () -> Unit = {},
    onRename: ((String) -> Unit)? = null,
    today: DayOfWeek = LocalDate.now().dayOfWeek,
    modifier: Modifier = Modifier,
) {
    if (slots.isEmpty()) {
        Column(modifier = modifier.fillMaxSize()) {
            Header(label, onBack, onRename)
            EmptyState(
                headline = "Nothing in your week yet",
                body = "Import a weekly timetable, or build one a class at a time.",
                actionLabel = "Import a document",
                onAction = onImport,
            )
            TextButton(
                onClick = onAddSlot,
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = MinTouchTarget),
            ) {
                Text("Add a class yourself", style = DocAction.type.label)
            }
        }
        return
    }

    // Only days that have something on them. A week with no Saturday classes should not
    // make the user swipe past an empty Saturday to reach Monday.
    val days = slots.map { DayOfWeek.of(it.weekday) }.distinct().sortedBy { it.value }
    val startPage = days.indexOf(today).coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = startPage) { days.size }
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxSize()) {
        Header(label, onBack, onRename)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DocAction.space.default),
            horizontalArrangement = Arrangement.spacedBy(DocAction.space.tight),
        ) {
            days.forEachIndexed { index, day ->
                FilterChip(
                    selected = pager.currentPage == index,
                    onClick = { scope.launch { pager.animateScrollToPage(index) } },
                    label = { Text(day.name.take(3).lowercase().replaceFirstChar(Char::titlecase)) },
                )
            }
        }

        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
            val day = days[page]
            val onThisDay = slots.filter { it.weekday == day.value }.sortedBy { it.startMinute }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Text(
                        text = day.name.lowercase().replaceFirstChar(Char::titlecase),
                        style = DocAction.type.title,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .padding(DocAction.space.default)
                            .semantics { heading() },
                    )
                }
                items(onThisDay, key = { it.id }) { slot ->
                    SlotRow(slot) { onEditSlot(slot) }
                }

                item(key = "add") {
                    TextButton(
                        onClick = onAddSlot,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = DocAction.space.section)
                            .sizeIn(minHeight = MinTouchTarget),
                    ) {
                        Text("Add a class", style = DocAction.type.label)
                    }
                }
            }
        }
    }
}

@Composable
private fun SlotRow(slot: TimetableSlotEntity, onClick: () -> Unit) {
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DocAction.space.default, vertical = DocAction.space.tight)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(DocAction.radius.md),
            )
            .clickable(onClick = onClick)
            .padding(DocAction.space.default)
            .sizeIn(minHeight = MinTouchTarget),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = slot.title,
                style = DocAction.type.subject,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${time.format(LocalTime.ofSecondOfDay(slot.startMinute * 60L))} – " +
                    time.format(LocalTime.ofSecondOfDay(slot.endMinute * 60L)),
                style = DocAction.type.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            slot.location?.let {
                Text(
                    text = it,
                    style = DocAction.type.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The assumption follows the slot into storage and onto this screen. A weekly
            // view has the same duty as the review screen to say which parts came from us.
            if (slot.endAssumed) {
                Text(
                    text = "End time assumed",
                    style = DocAction.type.meta,
                    color = DocAction.confidence.checkFg,
                )
            }
        }
    }
}

@Composable
private fun Header(label: String, onBack: () -> Unit, onRename: ((String) -> Unit)?) {
    var renaming by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.padding(
            start = DocAction.space.default,
            end = DocAction.space.default,
            top = DocAction.space.default,
        ),
    ) {
        TextButton(onClick = onBack, modifier = Modifier.sizeIn(minHeight = MinTouchTarget)) {
            Text("Back", style = DocAction.type.label)
        }
        Text(
            text = label,
            style = DocAction.type.title,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .semantics { heading() }
                .then(
                    if (onRename != null) Modifier.clickable { renaming = true } else Modifier,
                ),
        )
        // The name is only ever a label — renaming one never forks or merges anything, which
        // is why it can be this casual. See TimetableEntity.label.
        if (onRename != null) {
            Text(
                text = "Tap the name to rename",
                style = DocAction.type.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (renaming && onRename != null) {
        RenameDialog(label, onDismiss = { renaming = false }) {
            onRename(it)
            renaming = false
        }
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename this timetable") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Name") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(text) },
                enabled = text.isNotBlank(),
            ) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
