package com.okayanshul.docaction.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.okayanshul.docaction.core.designsystem.DocAction
import com.okayanshul.docaction.core.designsystem.MinTouchTarget
import com.okayanshul.docaction.core.settings.ReminderPreferences
import com.okayanshul.docaction.imports.Copy

/**
 * The few things worth choosing.
 *
 * Everything here already had a defensible default and worked without a screen, which is the
 * bar for putting something in settings at all: a preference the product cannot function
 * without is not a preference, it is a missing decision.
 *
 * Reminder offsets are the one genuinely personal choice — a 5-minute warning is useless to
 * someone who commutes and perfect for someone already on campus.
 */
@Composable
fun SettingsScreen(
    preferences: ReminderPreferences,
    exactAlarmsUnavailable: Boolean,
    onToggleOffset: (Long) -> Unit,
    onSetRepeatUntilStart: (Boolean) -> Unit,
    onOpenPrivacy: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
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
                text = "Settings",
                style = DocAction.type.title,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
        }

        Section("Reminders")
        Text(
            text = "How far ahead we nudge you about a class or a deadline.",
            style = DocAction.type.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = DocAction.space.default),
        )

        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(DocAction.space.tight),
            modifier = Modifier
                .fillMaxWidth()
                .padding(DocAction.space.default),
        ) {
            OFFERED_OFFSETS.forEach { minutes ->
                FilterChip(
                    selected = minutes in preferences.offsetsMinutes,
                    onClick = { onToggleOffset(minutes) },
                    label = { Text(ahead(minutes)) },
                    modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
                )
            }
        }

        if (preferences.offsetsMinutes.isEmpty()) {
            // The settings layer refuses an empty ladder rather than silently stopping every
            // reminder; saying so is better than a chip row that appears not to respond.
            Text(
                text = "Keep at least one, or reminders would stop entirely.",
                style = DocAction.type.meta,
                color = DocAction.confidence.checkFg,
                modifier = Modifier.padding(horizontal = DocAction.space.default),
            )
        }

        Toggle(
            title = "Keep nudging until it starts",
            detail = "After the last reminder, every few minutes until the event begins.",
            checked = preferences.repeatUntilStart,
            onCheckedChange = onSetRepeatUntilStart,
        )

        if (exactAlarmsUnavailable) {
            // Explaining a late reminder before it happens is the difference between a quirk
            // and a bug report.
            Text(
                text = "This phone is limiting exact alarms, so reminders may arrive a few " +
                    "minutes late.",
                style = DocAction.type.meta,
                color = DocAction.confidence.checkFg,
                modifier = Modifier.padding(
                    horizontal = DocAction.space.default,
                    vertical = DocAction.space.snug,
                ),
            )
        }

        Section("Privacy")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenPrivacy)
                .padding(horizontal = DocAction.space.default, vertical = DocAction.space.snug)
                .sizeIn(minHeight = MinTouchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("What stays on this phone", style = DocAction.type.body)
                Text(
                    text = "Everything. Here's exactly what that means.",
                    style = DocAction.type.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Toggle(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = DocAction.space.default, vertical = DocAction.space.snug)
            .sizeIn(minHeight = MinTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = DocAction.type.body)
            Text(
                text = detail,
                style = DocAction.type.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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

/** The rungs worth offering. More than this is a configuration screen, not a preference. */
private val OFFERED_OFFSETS = listOf(1440L, 120L, 60L, 30L, 15L, 5L)

private fun ahead(minutes: Long): String = when {
    minutes >= 1440L && minutes % 1440L == 0L -> Copy.countOf((minutes / 1440).toInt(), "day")
    minutes >= 60L && minutes % 60L == 0L -> Copy.countOf((minutes / 60).toInt(), "hour")
    else -> Copy.countOf(minutes.toInt(), "min")
}
