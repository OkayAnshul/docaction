package com.okayanshul.docaction.imports.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.okayanshul.docaction.core.designsystem.DocAction
import com.okayanshul.docaction.core.designsystem.MinTouchTarget
import com.okayanshul.docaction.core.designsystem.StageLine
import com.okayanshul.docaction.core.designsystem.StageProgress

/**
 * What is happening, while it happens.
 *
 * The stage list is driven by the pipeline's real progress, not by a timer — which is why
 * Cancel is always available and always instant. Anything cancelled here has changed
 * nothing: no calendar has been touched at this point in the flow.
 */
@Composable
fun ProcessingScreen(
    documentName: String,
    stages: List<StageLine>,
    detail: String?,
    determinate: Float?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = DocAction.space.default),
        horizontalAlignment = Alignment.Start,
    ) {
        Spacer(Modifier.weight(1f))

        Text(
            text = "Reading your document",
            style = DocAction.type.title,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = documentName,
            style = DocAction.type.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.padding(top = DocAction.space.tight),
        )

        Spacer(Modifier.padding(top = DocAction.space.section))

        StageProgress(stages = stages, determinate = determinate, detail = detail)

        Spacer(Modifier.weight(1f))

        TextButton(
            onClick = onCancel,
            modifier = Modifier
                .sizeIn(minHeight = MinTouchTarget)
                .padding(bottom = DocAction.space.section),
        ) {
            Text("Cancel", style = DocAction.type.label)
        }
    }
}

/**
 * The write itself.
 *
 * Deliberately not cancellable. A half-cancelled batch would leave the calendar in a state
 * the user never asked for and cannot easily see; the honest options are "wait a moment" or
 * "undo afterwards", and undo is offered on the very next screen.
 */
@Composable
fun WritingScreen(written: Int, total: Int, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = DocAction.space.default),
    ) {
        Spacer(Modifier.weight(1f))
        Text(
            text = "Adding to your calendar",
            style = DocAction.type.title,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.padding(top = DocAction.space.section))
        StageProgress(
            stages = emptyList(),
            determinate = if (total > 0) written.toFloat() / total else null,
            detail = if (total > 0) "$written of $total" else null,
        )
        Spacer(Modifier.weight(1f))
    }
}
