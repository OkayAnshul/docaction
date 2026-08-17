package com.okayanshul.docaction.imports.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.style.TextOverflow
import com.okayanshul.docaction.core.designsystem.DocAction
import com.okayanshul.docaction.core.designsystem.MinTouchTarget
import com.okayanshul.docaction.domain.FailureReason
import com.okayanshul.docaction.imports.Copy
import com.okayanshul.docaction.imports.RecoveryAction

/**
 * One template for every failure: plain statement, honest cause, concrete next step.
 *
 * The first recovery is the primary button because it is the one most likely to work; the
 * rest are quieter. There is always at least one, and it always does something — a screen
 * whose only option is "Close" leaves the user exactly where they were, which is the one
 * outcome an error screen exists to prevent.
 */
@Composable
fun FailureScreen(
    reason: FailureReason,
    documentName: String,
    onRecover: (RecoveryAction) -> Unit,
    modifier: Modifier = Modifier,
    afterCrop: Boolean = false,
    emptySchedule: String? = null,
) {
    val failure = if (emptySchedule != null) {
        Copy.forEmptySchedule(emptySchedule)
    } else {
        Copy.forFailure(reason, afterCrop)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = DocAction.space.default),
    ) {
        Spacer(Modifier.weight(1f))

        Text(
            text = failure.headline,
            style = DocAction.type.title,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics {
                heading()
                liveRegion = LiveRegionMode.Assertive
            },
        )
        failure.cause?.let {
            Text(
                text = it,
                style = DocAction.type.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = DocAction.space.snug),
            )
        }
        Text(
            text = documentName,
            style = DocAction.type.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.padding(top = DocAction.space.default),
        )

        Spacer(Modifier.weight(1f))

        Column(
            modifier = Modifier.padding(bottom = DocAction.space.section),
            verticalArrangement = Arrangement.spacedBy(DocAction.space.snug),
        ) {
            failure.recoveries.forEachIndexed { index, recovery ->
                if (index == 0) {
                    Button(
                        onClick = { onRecover(recovery.action) },
                        shape = MaterialTheme.shapes.extraLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .sizeIn(minHeight = MinTouchTarget),
                    ) {
                        Text(recovery.label, style = DocAction.type.label)
                    }
                } else {
                    OutlinedButton(
                        onClick = { onRecover(recovery.action) },
                        shape = MaterialTheme.shapes.extraLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .sizeIn(minHeight = MinTouchTarget),
                    ) {
                        Text(recovery.label, style = DocAction.type.label)
                    }
                }
            }

            if (failure.recoveries.none { it.action == RecoveryAction.Dismiss }) {
                TextButton(
                    onClick = { onRecover(RecoveryAction.Dismiss) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = MinTouchTarget),
                ) {
                    Text("Not now", style = DocAction.type.label)
                }
            }
        }
    }
}
