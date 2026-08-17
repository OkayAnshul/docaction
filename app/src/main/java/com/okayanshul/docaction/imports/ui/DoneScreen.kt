package com.okayanshul.docaction.imports.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.okayanshul.docaction.core.designsystem.DocAction
import com.okayanshul.docaction.core.designsystem.MinTouchTarget
import com.okayanshul.docaction.imports.Copy
import com.okayanshul.docaction.imports.ImportState

/**
 * What actually happened.
 *
 * The number comes from reading the calendar back, not from what was attempted — "38 added"
 * has to mean 38 events exist. A partial result is stated as a partial result; the word
 * "Failed" appears nowhere, because 38 of 41 is not a failure and calling it one would be
 * both inaccurate and demoralising.
 *
 * Undo is offered here and only here, while the user still remembers agreeing to it, and it
 * removes exactly the events this import created — never a time range.
 *
 * There is deliberately no upsell on this screen.
 */
@Composable
fun DoneScreen(
    state: ImportState.Finished,
    onUndo: () -> Unit,
    onOpenCalendar: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = DocAction.space.default),
    ) {
        Spacer(Modifier.weight(1f))

        Text(
            text = Copy.result(state.written, state.failed),
            style = DocAction.type.display,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics {
                heading()
                liveRegion = LiveRegionMode.Assertive
            },
        )
        Text(
            text = buildString {
                append("They're in ${state.calendarLabel}.")
                if (state.remindersOn) append(" We'll remind you before each one.")
            },
            style = DocAction.type.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = DocAction.space.snug),
        )

        if (state.failed > 0) {
            Text(
                text = "The ones that couldn't be added were left out entirely — " +
                    "nothing partial was written.",
                style = DocAction.type.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = DocAction.space.default),
            )
        }

        Spacer(Modifier.weight(1f))

        Column(
            modifier = Modifier.padding(bottom = DocAction.space.section),
            verticalArrangement = Arrangement.spacedBy(DocAction.space.snug),
        ) {
            OutlinedButton(
                onClick = onOpenCalendar,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = MinTouchTarget),
            ) {
                Text("Open calendar", style = DocAction.type.label)
            }
            TextButton(
                onClick = onUndo,
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = MinTouchTarget),
            ) {
                Text("Undo — remove these again", style = DocAction.type.label)
            }
            TextButton(
                onClick = onDone,
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = MinTouchTarget),
            ) {
                Text("Done", style = DocAction.type.label)
            }
        }
    }
}
