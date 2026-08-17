package com.okayanshul.docaction.imports.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.okayanshul.docaction.core.designsystem.DocAction
import com.okayanshul.docaction.core.designsystem.MinTouchTarget
import com.okayanshul.docaction.imports.Interrupted

/**
 * The first screen, and the whole promise in one sentence.
 *
 * Three buttons, all of which work. Still no "paste text" button, because that one is not
 * implemented and offering a route that dead-ends is a worse first impression than offering
 * fewer routes.
 *
 * "Take a photo" asks for no camera permission: it hands off to whichever camera app the
 * user already has, which is both a better camera and one less prompt in the way.
 *
 * The privacy line is at the bottom, stated once, in the same voice as everything else —
 * not a badge, not a lock icon, not a marketing claim.
 */
@Composable
fun HomeScreen(
    onPickFile: () -> Unit,
    onPickPhoto: () -> Unit,
    onTakePhoto: () -> Unit = {},
    interrupted: Interrupted? = null,
    onResume: () -> Unit = {},
    onDiscardInterrupted: () -> Unit = {},
    onOpenTimetable: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DocAction.space.default),
    ) {
        Spacer(Modifier.weight(1f))

        Text(
            text = "Turn documents into actions",
            style = DocAction.type.display,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.padding(top = DocAction.space.snug))
        Text(
            text = "Open a timetable, an exam schedule or a notice. " +
                "We'll read it, show you what we found, and add it to your calendar " +
                "once you say so.",
            style = DocAction.type.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.padding(top = DocAction.space.section))

        // Offered, never resumed automatically. Coming back to an app that has silently
        // resurrected work you had walked away from is unsettling, and the one-tap cost of
        // saying yes is lower than the cost of being surprised.
        if (interrupted != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(DocAction.radius.md),
                    )
                    .padding(DocAction.space.default),
                verticalArrangement = Arrangement.spacedBy(DocAction.space.tight),
            ) {
                Text(
                    text = "You were partway through",
                    style = DocAction.type.subject,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = interrupted.source.displayName,
                    style = DocAction.type.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(DocAction.space.snug)) {
                    TextButton(
                        onClick = onResume,
                        modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
                    ) {
                        Text("Pick up where you left off", style = DocAction.type.label)
                    }
                    TextButton(
                        onClick = onDiscardInterrupted,
                        modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
                    ) {
                        Text("Discard", style = DocAction.type.label)
                    }
                }
            }
            Spacer(Modifier.padding(top = DocAction.space.default))
        }

        Column(verticalArrangement = Arrangement.spacedBy(DocAction.space.snug)) {
            Button(
                onClick = onPickFile,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = MinTouchTarget),
            ) {
                Text("Choose a file", style = DocAction.type.label)
            }
            OutlinedButton(
                onClick = onPickPhoto,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = MinTouchTarget),
            ) {
                Text("Choose a photo", style = DocAction.type.label)
            }
            OutlinedButton(
                onClick = onTakePhoto,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = MinTouchTarget),
            ) {
                Text("Take a photo", style = DocAction.type.label)
            }
        }

        // Only once there is a week to show. An entry point to an empty screen is a
        // promise the app has not yet kept.
        onOpenTimetable?.let { open ->
            Spacer(Modifier.padding(top = DocAction.space.default))
            OutlinedButton(
                onClick = open,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = MinTouchTarget),
            ) {
                Text("My Timetable", style = DocAction.type.label)
            }
        }

        Spacer(Modifier.padding(top = DocAction.space.default))
        Text(
            text = "PDF, Excel, CSV and photos. You can also share a document to DocAction " +
                "from any app.",
            style = DocAction.type.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.weight(1f))

        Text(
            text = "Documents are read on your phone and never uploaded.",
            style = DocAction.type.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = DocAction.space.section),
        )
    }
}
