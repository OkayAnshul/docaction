package com.okayanshul.docaction.timetable.ui

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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 * Read-only for the moment. Editing a slot has to write through to the calendar rows it
 * created, and that is a change to the write path rather than to this screen.
 */
@Composable
fun TimetableScreen(
    label: String,
    slots: List<TimetableSlotEntity>,
    onImport: () -> Unit,
    onBack: () -> Unit,
    today: DayOfWeek = LocalDate.now().dayOfWeek,
    modifier: Modifier = Modifier,
) {
    if (slots.isEmpty()) {
        Column(modifier = modifier.fillMaxSize()) {
            Header(label, onBack)
            EmptyState(
                headline = "No timetable yet",
                body = "Import a weekly timetable and choose \"Keep this as my timetable\" " +
                    "to see your week here.",
                actionLabel = "Import a document",
                onAction = onImport,
            )
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
        Header(label, onBack)

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
                items(onThisDay, key = { it.id }) { slot -> SlotRow(slot) }
            }
        }
    }
}

@Composable
private fun SlotRow(slot: TimetableSlotEntity) {
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DocAction.space.default, vertical = DocAction.space.tight)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(DocAction.radius.md),
            )
            .padding(DocAction.space.default),
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
private fun Header(label: String, onBack: () -> Unit) {
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
            modifier = Modifier.semantics { heading() },
        )
    }
}
