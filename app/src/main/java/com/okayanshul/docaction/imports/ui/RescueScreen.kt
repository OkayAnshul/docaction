package com.okayanshul.docaction.imports.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.okayanshul.docaction.core.designsystem.DocAction
import com.okayanshul.docaction.core.designsystem.MinTouchTarget
import com.okayanshul.docaction.domain.BoundingBox
import com.okayanshul.docaction.imports.ImportState

/**
 * The last resort, and the one that usually works: let the user point.
 *
 * A noticeboard photo where only the third column is theirs, a page where the engine locked
 * onto the wrong table, a 38-page bulletin where page 12 is the timetable — all of them are
 * one gesture away from readable, and none of them is solvable by better heuristics alone.
 *
 * Dragging is optional. Choosing a page and reading it whole is a complete answer, and it is
 * the whole answer for a document that was simply too big.
 */
@Composable
fun RescueScreen(
    state: ImportState.Rescuing,
    onPage: (Int) -> Unit,
    onCrop: (BoundingBox?) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(
                start = DocAction.space.default,
                end = DocAction.space.default,
                top = DocAction.space.default,
            ),
        ) {
            TextButton(onClick = onCancel, modifier = Modifier.sizeIn(minHeight = MinTouchTarget)) {
                Text("Cancel", style = DocAction.type.label)
            }
            Text(
                text = "Show us the part you need",
                style = DocAction.type.title,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = if (state.isPaged) {
                    "Pick the page, then drag across the part that's yours. " +
                        "Reading the whole page is fine too."
                } else {
                    "Drag across the part that's yours. You can skip this and we'll read it all."
                },
                style = DocAction.type.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = DocAction.space.tight),
            )
        }

        if (state.isPaged) {
            LazyRow(
                modifier = Modifier.padding(vertical = DocAction.space.snug),
                horizontalArrangement = Arrangement.spacedBy(DocAction.space.snug),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = DocAction.space.default,
                ),
            ) {
                items((0 until state.pageCount).toList()) { index ->
                    FilterChip(
                        selected = index == state.page,
                        onClick = { onPage(index) },
                        label = { Text("${index + 1}", style = DocAction.type.label) },
                        modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DocAction.space.default),
        ) {
            if (state.image == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                CropCanvas(state, onCrop)
            }
        }

        BottomBar(
            label = if (state.crop == null) "Read this page" else "Read this part",
            enabled = state.image != null,
            onClick = onApply,
            secondary = if (state.crop != null) {
                {
                    TextButton(
                        onClick = { onCrop(null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .sizeIn(minHeight = MinTouchTarget),
                    ) {
                        Text("Clear selection", style = DocAction.type.label)
                    }
                }
            } else {
                null
            },
        )
    }
}

/**
 * Drag anywhere on the page to select a region.
 *
 * The rectangle is stored as fractions of the page, in the same units a
 * [com.okayanshul.docaction.domain.SourceReference] uses, so it survives the difference
 * between the size the page is shown at here and the size it was read at.
 */
@Composable
private fun CropCanvas(state: ImportState.Rescuing, onCrop: (BoundingBox?) -> Unit) {
    val image = state.image!!.asImageBitmap()
    val outline = MaterialTheme.colorScheme.primary
    var anchor by remember(state.page) { mutableStateOf<Offset?>(null) }
    var current by remember(state.page) { mutableStateOf<Offset?>(null) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DocAction.radius.sm))
            .background(Color.White),
    ) {
        Image(
            bitmap = image,
            contentDescription = "Page ${state.page + 1} of ${state.documentName}",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth(),
        )

        Canvas(
            modifier = Modifier
                .matchParentSize()
                .semantics {
                    contentDescription = if (state.crop == null) {
                        "Drag to select part of the page"
                    } else {
                        "Part of the page is selected"
                    }
                }
                .pointerInput(state.page) {
                    detectDragGestures(
                        onDragStart = { start ->
                            anchor = start
                            current = start
                        },
                        onDrag = { change, _ ->
                            current = change.position
                            change.consume()
                        },
                        onDragEnd = {
                            val from = anchor
                            val to = current
                            anchor = null
                            if (from != null && to != null) {
                                val box = BoundingBox(
                                    left = minOf(from.x, to.x) / size.width,
                                    top = minOf(from.y, to.y) / size.height,
                                    right = maxOf(from.x, to.x) / size.width,
                                    bottom = maxOf(from.y, to.y) / size.height,
                                )
                                // A tap is not a selection. Treating one as a zero-area crop
                                // would silently produce "we couldn't find anything".
                                onCrop(box.takeIf { it.width > MIN_FRACTION && it.height > MIN_FRACTION })
                            }
                        },
                        onDragCancel = { anchor = null; current = null },
                    )
                },
        ) {
            // While dragging, the live rectangle; otherwise whatever is committed.
            val live = anchor?.let { from ->
                current?.let { to ->
                    BoundingBox(
                        left = minOf(from.x, to.x) / size.width,
                        top = minOf(from.y, to.y) / size.height,
                        right = maxOf(from.x, to.x) / size.width,
                        bottom = maxOf(from.y, to.y) / size.height,
                    )
                }
            }
            val box = live ?: state.crop ?: return@Canvas

            // Outside the selection is dimmed rather than hidden: the user is choosing
            // between parts of the page and needs to see what they are not choosing.
            val shade = Color.Black.copy(alpha = 0.35f)
            drawRect(shade, size = Size(size.width, box.top * size.height))
            drawRect(
                color = shade,
                topLeft = Offset(0f, box.bottom * size.height),
                size = Size(size.width, size.height - box.bottom * size.height),
            )
            drawRect(
                color = shade,
                topLeft = Offset(0f, box.top * size.height),
                size = Size(box.left * size.width, box.height * size.height),
            )
            drawRect(
                color = shade,
                topLeft = Offset(box.right * size.width, box.top * size.height),
                size = Size(size.width - box.right * size.width, box.height * size.height),
            )
            drawRect(
                color = outline,
                topLeft = Offset(box.left * size.width, box.top * size.height),
                size = Size(box.width * size.width, box.height * size.height),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

/** Below this, a drag is a slip of the thumb rather than a selection. */
private const val MIN_FRACTION = 0.03f
