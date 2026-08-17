package com.okayanshul.docaction.imports.ui

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.okayanshul.docaction.core.designsystem.DocAction
import com.okayanshul.docaction.core.designsystem.MinTouchTarget
import com.okayanshul.docaction.domain.DateOrder
import com.okayanshul.docaction.domain.GroupId
import com.okayanshul.docaction.domain.PipelineQuestion
import com.okayanshul.docaction.domain.TermBounds
import com.okayanshul.docaction.imports.Copy
import com.okayanshul.docaction.imports.ImportViewModel

/**
 * The "guided when uncertain" half of the product, made concrete.
 *
 * Each question is asked **once and applied document-wide** — a date-order question is one
 * question, not forty-two — and none of them has a default that quietly does the guessing.
 * The screen cannot be dismissed into a result: the only ways out are answering and
 * cancelling, because the alternative is an answer we invented.
 */
@Composable
fun QuestionScreen(
    question: PipelineQuestion,
    onPickGroup: (GroupId) -> Unit,
    onPickTerm: (TermBounds) -> Unit,
    onPickOrder: (DateOrder) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (question) {
        is PipelineQuestion.WhichSchedule -> WhichSchedule(question, onPickGroup, onCancel, modifier)
        is PipelineQuestion.TermEnd -> TermEnd(question, onPickTerm, onCancel, modifier)
        is PipelineQuestion.DateOrder -> DateOrderQuestion(question, onPickOrder, onCancel, modifier)
    }
}

@Composable
private fun WhichSchedule(
    question: PipelineQuestion.WhichSchedule,
    onPick: (GroupId) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier,
) {
    var chosen by remember { mutableStateOf<GroupId?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        Ask(
            headline = "Which one is yours?",
            body = "This document contains ${Copy.countOf(question.groups.size, "schedule")}. " +
                "We'll only add the one you pick.",
            onCancel = onCancel,
        )

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(question.groups, key = { it.id.value }) { group ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { chosen = group.id }
                        .padding(
                            horizontal = DocAction.space.default,
                            vertical = DocAction.space.snug,
                        )
                        .sizeIn(minHeight = MinTouchTarget),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = chosen == group.id, onClick = { chosen = group.id })
                    Column(modifier = Modifier.padding(start = DocAction.space.snug)) {
                        Text(
                            text = group.label,
                            style = DocAction.type.subject,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = Copy.countOf(group.size, "entry", "entries"),
                            style = DocAction.type.meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        BottomBar(
            label = "Continue",
            enabled = chosen != null,
            onClick = { chosen?.let(onPick) },
        )
    }
}

@Composable
private fun TermEnd(
    question: PipelineQuestion.TermEnd,
    onPick: (TermBounds) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier,
) {
    val suggested = remember { ImportViewModel.suggestedTerm() }
    var start by remember { mutableStateOf(suggested.start) }
    var end by remember { mutableStateOf(suggested.end) }
    var picking by remember { mutableStateOf<Which?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        Ask(
            headline = "When does this schedule run?",
            // The label falls back to the file name when a document names no section, and
            // "kiit-cs1.pdf repeats weekly" reads like a bug even though it isn't.
            body = subject(question.scheduleLabel) +
                " repeats weekly, but the document doesn't say until when. " +
                "We won't add a schedule that never ends.",
            onCancel = onCancel,
        )

        Column(
            modifier = Modifier.padding(horizontal = DocAction.space.default),
            verticalArrangement = Arrangement.spacedBy(DocAction.space.snug),
        ) {
            Field(label = "From", value = Format.date(start), onClick = { picking = Which.Start })
            Field(label = "Until", value = Format.date(end), onClick = { picking = Which.End })

            if (!end.isAfter(start)) {
                Text(
                    text = "The end date needs to come after the start.",
                    style = DocAction.type.meta,
                    color = DocAction.confidence.invalidFg,
                )
            }
            Text(
                text = "These dates are a starting point, not a guess we've made — " +
                    "nothing is added until you set them.",
                style = DocAction.type.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(modifier = Modifier.weight(1f)) {}

        BottomBar(
            label = "Continue",
            enabled = end.isAfter(start),
            onClick = { onPick(TermBounds(start, end)) },
        )
    }

    when (picking) {
        null -> Unit
        Which.Start -> DatePickDialog(start) { picked ->
            picked?.let { start = it }
            picking = null
        }

        Which.End -> DatePickDialog(end) { picked ->
            picked?.let { end = it }
            picking = null
        }
    }
}

private enum class Which { Start, End }

/**
 * How to refer to a schedule in a sentence.
 *
 * A group label is the section name when the document gives one ("Section CS-1") and the
 * file name when it does not. Only the former belongs in quotation marks.
 */
private fun subject(label: String): String =
    if (label.substringAfterLast('.', "").length in 2..4 && '.' in label) {
        "This schedule"
    } else {
        "\"$label\""
    }

/**
 * `05/10/2026` — 5 October or 10 May?
 *
 * The engine settles this from evidence wherever it can: another date in the same document
 * with a day above twelve, or a weekday that only agrees with one reading. Device locale is
 * explicitly *not* evidence (ADR-004) — a phone bought abroad says nothing about who wrote
 * the document.
 *
 * When there is no evidence, this is the only honest move left. It was also the last piece
 * of "guided when uncertain" that did not work: the question type existed, the resolver
 * produced it, and nothing carried it here — so a train ticket whose single date was
 * ambiguous produced nothing at all and said nothing about why.
 */
@Composable
private fun DateOrderQuestion(
    question: PipelineQuestion.DateOrder,
    onPick: (DateOrder) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier,
) {
    var chosen by remember { mutableStateOf<DateOrder?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        Ask(
            headline = "How should we read \"${question.example}\"?",
            body = "Both readings are real dates and nothing in this document settles it. " +
                "Your answer applies to every date in it.",
            onCancel = onCancel,
        )

        Column(modifier = Modifier.padding(horizontal = DocAction.space.default)) {
            listOf(
                DateOrder.DayFirst to question.dayFirst,
                DateOrder.MonthFirst to question.monthFirst,
            ).forEach { (order, date) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { chosen = order }
                        .padding(vertical = DocAction.space.snug)
                        .sizeIn(minHeight = MinTouchTarget),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = chosen == order, onClick = { chosen = order })
                    Column(modifier = Modifier.padding(start = DocAction.space.snug)) {
                        Text(
                            text = Format.date(date),
                            style = DocAction.type.subject,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = if (order == DateOrder.DayFirst) {
                                "Day first — 05/10 is 5 October"
                            } else {
                                "Month first — 05/10 is 10 May"
                            },
                            style = DocAction.type.meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Column(modifier = Modifier.weight(1f)) {}

        BottomBar(
            label = "Continue",
            enabled = chosen != null,
            onClick = { chosen?.let(onPick) },
        )
    }
}

@Composable
private fun Ask(headline: String, body: String, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.padding(
            start = DocAction.space.default,
            end = DocAction.space.default,
            top = DocAction.space.default,
            bottom = DocAction.space.section,
        ),
    ) {
        TextButton(onClick = onCancel, modifier = Modifier.sizeIn(minHeight = MinTouchTarget)) {
            Text("Cancel", style = DocAction.type.label)
        }
        Text(
            text = headline,
            style = DocAction.type.title,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = body,
            style = DocAction.type.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = DocAction.space.snug),
        )
    }
}

@Composable
private fun Field(label: String, value: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = MinTouchTarget),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label,
                style = DocAction.type.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = value, style = DocAction.type.subject)
        }
    }
}
