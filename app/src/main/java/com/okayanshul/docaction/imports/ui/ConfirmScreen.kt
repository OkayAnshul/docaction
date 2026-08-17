package com.okayanshul.docaction.imports.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.okayanshul.docaction.actions.calendar.CalendarTarget
import com.okayanshul.docaction.core.designsystem.DocAction
import com.okayanshul.docaction.core.designsystem.MinTouchTarget
import com.okayanshul.docaction.domain.ActionTarget
import com.okayanshul.docaction.imports.Copy
import com.okayanshul.docaction.imports.ImportState

/**
 * The consent step. Nothing outside the app has changed before this screen, and the only
 * thing that changes it is the button at the bottom.
 *
 * Three things are stated plainly because they are what a person would want to know before
 * agreeing: **how many** events, **which calendar**, and **whether any of them already
 * exist**. The calendar is never pre-picked when there is more than one — writing 42 events
 * into a work account someone forgot was on their phone is a real harm and an avoidable one.
 */
@Composable
fun ConfirmScreen(
    state: ImportState.Confirming,
    onChooseTarget: (ActionTarget) -> Unit,
    onSetReminders: (Boolean) -> Unit,
    onSetKeepTimetable: (Boolean) -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onWrite: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
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
                    text = "Add ${Copy.countOf(state.chosen.size, "event")}?",
                    style = DocAction.type.title,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = summary(state),
                    style = DocAction.type.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = DocAction.space.tight),
                )
            }

            if (state.needsPermission) {
                PermissionBlock(state.denied, onOpenSettings)
            } else {
                Section("Add to")
                state.targets.forEach { target ->
                    TargetRow(
                        target = target,
                        selected = state.target == target,
                        onClick = { onChooseTarget(target) },
                    )
                }

                if (state.duplicates > 0) {
                    Notice(
                        if (state.duplicates == 1) {
                            "One of these is already in this calendar. Adding it again will " +
                                "create a duplicate."
                        } else {
                            "${state.duplicates} of these are already in this calendar. " +
                                "Adding them again will create duplicates."
                        },
                    )
                }

                Section("Reminders")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSetReminders(!state.remindersEnabled) }
                        .padding(
                            horizontal = DocAction.space.default,
                            vertical = DocAction.space.snug,
                        )
                        .sizeIn(minHeight = MinTouchTarget),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Remind me before each one", style = DocAction.type.body)
                        Text(
                            text = ladderLine(state),
                            style = DocAction.type.meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.remindersEnabled, onCheckedChange = onSetReminders)
                }

                if (state.canKeepAsTimetable) {
                    Section("My Timetable")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSetKeepTimetable(!state.keepAsTimetable) }
                            .padding(
                                horizontal = DocAction.space.default,
                                vertical = DocAction.space.snug,
                            )
                            .sizeIn(minHeight = MinTouchTarget),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Keep this as my timetable", style = DocAction.type.body)
                            Text(
                                text = "See your week at a glance, and update it in one step " +
                                    "when a new version comes out.",
                                style = DocAction.type.meta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.keepAsTimetable,
                            onCheckedChange = onSetKeepTimetable,
                        )
                    }
                }
            }
        }

        BottomBar(
            label = if (state.needsPermission) {
                "Allow calendar access"
            } else {
                "Add ${Copy.countOf(state.chosen.size, "event")}"
            },
            enabled = state.canWrite || (state.needsPermission && !state.denied),
            onClick = if (state.needsPermission) onRequestPermission else onWrite,
        )
    }
}

/**
 * Denial is a dead end with dignity.
 *
 * It is stated once, without a second ask and without implying the user did something
 * wrong. The route to Settings is there for anyone who changes their mind, and that is all.
 */
@Composable
private fun PermissionBlock(denied: Boolean, onSettings: () -> Unit) {
    Column(
        modifier = Modifier.padding(
            horizontal = DocAction.space.default,
            vertical = DocAction.space.section,
        ),
        verticalArrangement = Arrangement.spacedBy(DocAction.space.snug),
    ) {
        Text(
            text = if (denied) "No problem. Nothing was changed." else "One thing first",
            style = DocAction.type.subject,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = if (denied) {
                "We can't add events without calendar access. Everything we read is still " +
                    "here if you change your mind."
            } else {
                "To add these to your calendar, DocAction needs permission to read and write " +
                    "it. We only ever add what you've just confirmed."
            },
            style = DocAction.type.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (denied) {
            TextButton(onClick = onSettings, modifier = Modifier.sizeIn(minHeight = MinTouchTarget)) {
                Text("Open app settings", style = DocAction.type.label)
            }
        }
    }
}

@Composable
private fun TargetRow(target: ActionTarget, selected: Boolean, onClick: () -> Unit) {
    val calendar = target as? CalendarTarget
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = DocAction.space.default, vertical = DocAction.space.snug)
            .sizeIn(minHeight = MinTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(start = DocAction.space.snug)) {
            Text(target.label, style = DocAction.type.body)
            calendar?.accountName?.takeIf { it != target.label }?.let {
                Text(
                    text = it,
                    style = DocAction.type.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text(
        text = title,
        style = DocAction.type.label,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(
            start = DocAction.space.default,
            end = DocAction.space.default,
            top = DocAction.space.section,
            bottom = DocAction.space.snug,
        ),
    )
}

@Composable
private fun Notice(text: String) {
    Text(
        text = text,
        style = DocAction.type.meta,
        color = DocAction.confidence.checkFg,
        modifier = Modifier
            .padding(horizontal = DocAction.space.default)
            .fillMaxWidth()
            .background(DocAction.confidence.checkBg, RoundedCornerShape(DocAction.radius.md))
            .padding(DocAction.space.default),
    )
}

private fun summary(state: ImportState.Confirming): String {
    val recurring = state.recurring
    return when {
        recurring == 0 -> "From ${state.review.source.displayName}."
        recurring == state.chosen.size ->
            "All of them repeat weekly. Each is added as one repeating event, not as " +
                "hundreds of copies."

        else ->
            "${Copy.countOf(recurring, "of them repeats", "of them repeat")} weekly. Each " +
                "repeating one is added once, not as hundreds of copies."
    }
}

private fun ladderLine(state: ImportState.Confirming): String {
    val offsets = state.reminders.offsetsMinutes.sortedDescending()
    return when (offsets.size) {
        0 -> "No reminder times are set yet."
        1 -> "Once, ${ahead(offsets.first())} before."
        else -> "${offsets.size} nudges, starting ${ahead(offsets.first())} before."
    }
}

/** "1 day", "2 hours", "15 minutes" — the largest whole unit that divides evenly. */
private fun ahead(minutes: Long): String = when {
    minutes >= 1440L && minutes % 1440L == 0L -> Copy.countOf((minutes / 1440).toInt(), "day")
    minutes >= 60L && minutes % 60L == 0L -> Copy.countOf((minutes / 60).toInt(), "hour")
    else -> Copy.countOf(minutes.toInt(), "minute")
}
