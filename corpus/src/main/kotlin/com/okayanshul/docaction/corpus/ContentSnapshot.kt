package com.okayanshul.docaction.corpus

import com.okayanshul.docaction.domain.BoundingBox
import com.okayanshul.docaction.domain.DocumentContent
import com.okayanshul.docaction.domain.DocumentFormat
import com.okayanshul.docaction.domain.Issue
import com.okayanshul.docaction.domain.IssueKind
import com.okayanshul.docaction.domain.PageContent
import com.okayanshul.docaction.domain.TextOrigin
import com.okayanshul.docaction.domain.TextRun
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * What a reader produced, frozen so the engine can be tested without a device.
 *
 * Every root cause behind the corpus's low yield lives *downstream* of reading — the choke
 * point, the prose builder, the plausibility guards. So the reader boundary is where the
 * gate belongs: capture `DocumentContent` once on a device, replay it on the JVM, and the
 * whole engine becomes a unit test that runs in seconds over hundreds of documents.
 *
 * The alternative — running desktop PDFBox on the JVM — was rejected deliberately.
 * `PositionedTextStripper` extends a class from the `pdfbox-android` AAR, so a desktop
 * parser would produce geometry the device never produces, and the gate would be testing a
 * document nobody's phone will ever see.
 *
 * Snapshots are checked in; the source documents are not. Regenerating one is a reviewed
 * act, and the JSON diff says exactly what a reader change did.
 *
 * Written by hand through the `JsonElement` DSL rather than with `@Serializable`, so the
 * serialization compiler plugin never lands on a production module — and so the schema
 * stays explicit, which matters because the golden format depends on it being stable.
 */
object ContentSnapshot {

    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun write(content: DocumentContent): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("format", content.format.name)
            put("pages", buildJsonArray { content.pages.forEach { add(page(it)) } })
            put("issues", buildJsonArray { content.issues.forEach { add(issue(it)) } })
        },
    )

    fun read(text: String): DocumentContent {
        val root = json.parseToJsonElement(text).jsonObject
        return DocumentContent(
            format = DocumentFormat.valueOf(root.string("format")),
            pages = root["pages"]!!.jsonArray.map(::page),
            issues = root["issues"]?.jsonArray.orEmpty().map(::issue),
        )
    }

    // --- pages ---

    private fun page(page: PageContent) = buildJsonObject {
        put("index", page.index)
        put("widthPt", page.widthPt)
        put("heightPt", page.heightPt)
        put("runs", buildJsonArray { page.runs.forEach { add(run(it)) } })
    }

    private fun page(element: kotlinx.serialization.json.JsonElement): PageContent {
        val o = element.jsonObject
        return PageContent(
            index = o.int("index"),
            widthPt = o.float("widthPt"),
            heightPt = o.float("heightPt"),
            runs = o["runs"]!!.jsonArray.map(::run),
        )
    }

    /**
     * Bounds are kept here — unlike in the goldens, where they are dropped.
     *
     * A snapshot has to reproduce the reader exactly, and geometry *is* what the reader
     * produced; the table builder's whole job is reading it. Goldens drop coordinates for
     * the opposite reason: they describe what the user gets, and a rounding change in a
     * glyph box must not rewrite three hundred expectation files.
     */
    private fun run(run: TextRun) = buildJsonObject {
        put("text", run.text)
        put("l", run.bounds.left)
        put("t", run.bounds.top)
        put("r", run.bounds.right)
        put("b", run.bounds.bottom)
        run.confidence?.let { put("conf", it) }
        put("origin", run.origin.name)
    }

    private fun run(element: kotlinx.serialization.json.JsonElement): TextRun {
        val o = element.jsonObject
        return TextRun(
            text = o.string("text"),
            bounds = BoundingBox(o.float("l"), o.float("t"), o.float("r"), o.float("b")),
            confidence = o["conf"]?.jsonPrimitive?.floatOrNull,
            origin = TextOrigin.valueOf(o.string("origin")),
        )
    }

    // --- issues ---

    private fun issue(issue: Issue) = buildJsonObject {
        put("kind", issue.kind.name)
        put("detail", issue.detail)
    }

    private fun issue(element: kotlinx.serialization.json.JsonElement): Issue {
        val o = element.jsonObject
        // Source references are deliberately not round-tripped: an Issue's source is used
        // for display, never by the engine, and carrying it would put coordinates in a file
        // that is otherwise stable.
        return Issue(kind = IssueKind.valueOf(o.string("kind")), detail = o.string("detail"))
    }
}

internal fun JsonObject.string(key: String): String =
    this[key]?.jsonPrimitive?.content ?: error("snapshot is missing \"$key\"")

internal fun JsonObject.int(key: String): Int =
    this[key]?.jsonPrimitive?.int ?: error("snapshot is missing \"$key\"")

internal fun JsonObject.float(key: String): Float =
    this[key]?.jsonPrimitive?.float ?: error("snapshot is missing \"$key\"")

internal fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> =
    this?.toList() ?: emptyList()
