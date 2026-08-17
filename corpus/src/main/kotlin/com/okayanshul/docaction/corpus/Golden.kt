package com.okayanshul.docaction.corpus

import com.okayanshul.docaction.domain.CalendarEventCandidate
import com.okayanshul.docaction.domain.PipelineQuestion
import com.okayanshul.docaction.domain.PipelineResult
import com.okayanshul.docaction.domain.SourceReference
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What one document is expected to produce, written down.
 *
 * This exists because "29 of 42 documents are Ready" was reported for weeks as a success
 * measure, and it cannot tell a timetable that produced 23 events from one that produced
 * none. A verdict is not a result. Goldens record the result.
 *
 * Four rules decide whether a golden suite is maintained or deleted, and all four are
 * enforced here rather than left to discipline:
 *
 * 1. **Normalised hard.** Candidates are sorted; no coordinates, no floats, no ids that
 *    change between runs. A rounding change in a glyph box must not rewrite three hundred
 *    files, or nobody will read the diff that matters.
 * 2. **A missing golden fails.** [CorpusReport.compare] treats absence as a mismatch, so a
 *    document added without an expectation cannot pass quietly.
 * 3. **Regeneration is a task, not a flag.** See `:extraction:regenerateGoldens`.
 * 4. **The aggregate is itself a golden** — see [Summary]. One line of diff shows a shift
 *    across the whole corpus, which is precisely what the old `println` totals could not do.
 */
object Golden {

    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }
    private val date = DateTimeFormatter.ISO_LOCAL_DATE
    private val dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    /** The outcome of running one document, reduced to what a user would notice. */
    fun of(document: String, result: PipelineResult): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("document", document)
            when (result) {
                is PipelineResult.Failed -> {
                    put("outcome", "Failed")
                    put("reason", result.reason.name)
                }

                is PipelineResult.NeedsAnswers -> {
                    put("outcome", "NeedsAnswers")
                    put("questions", buildJsonArray { result.questions.forEach { add(question(it)) } })
                    put("groups", result.partial.groups.size)
                }

                is PipelineResult.Ready -> {
                    val review = result.review
                    put("outcome", "Ready")
                    put("groups", review.groups.size)
                    put("unresolved", review.unresolved.size)
                    put("candidateCount", review.candidates.size)
                    put(
                        "candidates",
                        buildJsonArray {
                            // Sorted, because the engine's iteration order is an
                            // implementation detail and must not show up as a diff.
                            review.candidates
                                .sortedWith(compareBy({ it.start.toString() }, { it.title }))
                                .forEach { add(candidate(it)) }
                        },
                    )
                }
            }
        },
    )

    private fun question(question: PipelineQuestion) = buildJsonObject {
        when (question) {
            is PipelineQuestion.WhichSchedule -> {
                put("kind", "WhichSchedule")
                put("choices", question.groups.size)
            }

            is PipelineQuestion.TermEnd -> {
                put("kind", "TermEnd")
                put("schedule", question.scheduleLabel)
            }

            is PipelineQuestion.DateOrder -> {
                put("kind", "DateOrder")
                put("example", question.example)
            }
        }
    }

    private fun candidate(candidate: CalendarEventCandidate) = buildJsonObject {
        put("title", candidate.title)
        put("start", dateTime.format(candidate.start))
        put("end", dateTime.format(candidate.end))
        put("zone", candidate.start.zone.id)
        candidate.location?.let { put("location", it) }
        put("status", candidate.status.name)
        put("allDay", candidate.isAllDay)
        if (candidate.assumptions.isNotEmpty()) {
            // The rule names, not the values: a golden should say *that* we filled a gap and
            // by which rule, so a change in the assumed duration shows up as a summary shift
            // rather than as a rewrite of every affected file.
            put(
                "assumed",
                buildJsonArray {
                    candidate.assumptions.map { it.rule }.sorted().forEach { add(JsonPrimitive(it)) }
                },
            )
        }
        candidate.recurrence?.let { recurrence ->
            put(
                "recurrence",
                buildJsonObject {
                    put("frequency", recurrence.frequency.name)
                    put("byWeekday", recurrence.byWeekday.sortedBy { it.value }.joinToString(",") { it.name })
                    put("until", date.format(recurrence.until))
                },
            )
        }
        // Kinds only. A `PdfSpan(page=3, bounds=…)` in a golden would make every geometry
        // tweak a corpus-wide merge conflict, and the page number is the only part a
        // person checking the file can actually verify.
        put(
            "sources",
            buildJsonArray {
                candidate.sources.map(::sourceKind).distinct().sorted()
                    .forEach { add(JsonPrimitive(it)) }
            },
        )
    }

    private fun sourceKind(source: SourceReference): String = when (source) {
        is SourceReference.PdfSpan -> "PdfSpan(page=${source.page})"
        is SourceReference.ImageRegion -> "ImageRegion(page=${source.page ?: "-"})"
        is SourceReference.SheetCell -> "SheetCell(${source.sheet})"
        is SourceReference.SheetRange -> "SheetRange(${source.sheet})"
        is SourceReference.CsvCell -> "CsvCell"
        is SourceReference.Derived -> "Derived(${source.rule})"
        is SourceReference.UserProvided -> "UserProvided(${source.field})"
        is SourceReference.Assumed -> "Assumed(${source.rule})"
    }

    /**
     * The corpus in one object.
     *
     * `candidates` is the number that actually matters and the one that was missing:
     * it counts events produced, not documents that avoided failing.
     */
    data class Summary(
        val documents: Int,
        val produced: Int,
        val readyButEmpty: Int,
        val asked: Int,
        val refused: Int,
        val candidates: Int,
        /**
         * How many candidates each inference rule filled a gap on.
         *
         * Here rather than only on the individual goldens because a shift in what the engine
         * is willing to assume is exactly the kind of change that hides: one relaxed rule can
         * add a hundred all-day events across the corpus without any single document looking
         * wrong. As a summary line it is one number that moved.
         */
        val assumptionsByRule: Map<String, Int>,
    ) {
        fun toJson(): String = Json { prettyPrint = true }.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("documents", documents)
                put("produced", produced)
                put("readyButEmpty", readyButEmpty)
                put("asked", asked)
                put("refused", refused)
                put("candidates", candidates)
                put(
                    "assumptionsByRule",
                    buildJsonObject {
                        assumptionsByRule.toSortedMap().forEach { (rule, count) -> put(rule, count) }
                    },
                )
            },
        )
    }

    fun summarise(results: Map<String, PipelineResult>): Summary {
        val ready = results.values.filterIsInstance<PipelineResult.Ready>()
        return Summary(
            documents = results.size,
            produced = ready.count { it.review.candidates.isNotEmpty() },
            readyButEmpty = ready.count { it.review.candidates.isEmpty() },
            asked = results.values.count { it is PipelineResult.NeedsAnswers },
            refused = results.values.count { it is PipelineResult.Failed },
            candidates = ready.sumOf { it.review.candidates.size },
            assumptionsByRule = ready
                .flatMap { it.review.candidates }
                .flatMap { candidate -> candidate.assumptions.map { it.rule } }
                .groupingBy { it }
                .eachCount(),
        )
    }
}
