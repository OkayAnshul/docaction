package com.okayanshul.docaction.imports.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.okayanshul.docaction.core.designsystem.DocAction
import com.okayanshul.docaction.domain.BoundingBox
import com.okayanshul.docaction.imports.source.SourceEvidence

/**
 * "Where did this come from?"
 *
 * The single most convincing thing the app can do. Every claim it makes about a document is
 * checkable in one tap, against the document itself — which turns "the app says my class is
 * at 9" into "I can see the row it read". No amount of confidence tuning buys that.
 *
 * It shows the page and outlines the span. It never states coordinates: "page 3" is a fact a
 * person can use, and `BoundingBox(72.4, 318.9, …)` is a fact about our internals.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceSheet(
    evidence: SourceEvidence?,
    onDismiss: () -> Unit,
) {
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
                text = "Where this came from",
                style = DocAction.type.title,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )

            when (evidence) {
                null -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = DocAction.space.major),
                ) {
                    CircularProgressIndicator(modifier = Modifier.align(androidx.compose.ui.Alignment.Center))
                }

                is SourceEvidence.Page -> {
                    Label(evidence.label)
                    HighlightedPage(evidence.image.asImageBitmap(), evidence.highlight, evidence.label)
                }

                is SourceEvidence.Cells -> {
                    Label(evidence.label)
                    CellWindow(evidence)
                }

                is SourceEvidence.Told -> {
                    Label(evidence.label)
                    Text(
                        text = evidence.detail,
                        style = DocAction.type.body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is SourceEvidence.Unavailable -> Text(
                    text = evidence.reason,
                    style = DocAction.type.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text = text,
        style = DocAction.type.label,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * The page, with the span we read outlined.
 *
 * The outline is drawn rather than the rest dimmed: a person checking our work needs to read
 * the surrounding rows too, and a spotlight effect hides exactly the context that makes the
 * highlighted row meaningful.
 */
@Composable
private fun HighlightedPage(image: ImageBitmap, highlight: BoundingBox?, label: String) {
    val outline = DocAction.confidence.checkFg

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DocAction.radius.sm))
            .background(Color.White)
            .semantics {
                contentDescription = if (highlight == null) {
                    "$label, shown without a highlight"
                } else {
                    "$label, with the part we read outlined"
                }
            },
    ) {
        androidx.compose.foundation.Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth(),
        )

        if (highlight != null && (highlight.width > 0f || highlight.height > 0f)) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(image.width.toFloat() / image.height.coerceAtLeast(1)),
            ) {
                // The box arrives as fractions of the page, so it lands correctly whatever
                // size the page was rendered at — see SourceReference's contract.
                val inset = 2.dp.toPx()
                drawRect(
                    color = outline,
                    topLeft = Offset(
                        x = highlight.left * size.width - inset,
                        y = highlight.top * size.height - inset,
                    ),
                    size = Size(
                        width = (highlight.width * size.width + inset * 2).coerceAtLeast(inset),
                        height = (highlight.height * size.height + inset * 2).coerceAtLeast(inset),
                    ),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
    }
}

/** A workbook shows its own grid: the cell we read, in the context around it. */
@Composable
private fun CellWindow(evidence: SourceEvidence.Cells) {
    val focus = DocAction.confidence.checkBg

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        Row {
            Gutter("")
            evidence.columnLabels.forEach { Header(it) }
        }
        evidence.rows.forEach { row ->
            Row(
                modifier = Modifier.background(if (row.isFocus) focus else Color.Transparent),
            ) {
                Gutter(row.label)
                row.values.forEachIndexed { index, value ->
                    Text(
                        text = value,
                        style = DocAction.type.meta,
                        maxLines = 2,
                        color = if (row.isFocus && index == evidence.focusColumn) {
                            DocAction.confidence.checkFg
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier
                            .width(CELL_WIDTH)
                            .heightIn(min = 32.dp)
                            .padding(DocAction.space.tight),
                    )
                }
            }
        }
    }
}

@Composable
private fun Gutter(text: String) {
    Text(
        text = text,
        style = DocAction.type.meta,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .width(36.dp)
            .padding(DocAction.space.tight),
    )
}

@Composable
private fun Header(text: String) {
    Text(
        text = text,
        style = DocAction.type.label,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .width(CELL_WIDTH)
            .padding(DocAction.space.tight),
    )
}

private val CELL_WIDTH = 96.dp
