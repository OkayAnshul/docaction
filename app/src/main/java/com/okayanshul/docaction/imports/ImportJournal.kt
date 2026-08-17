package com.okayanshul.docaction.imports

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.okayanshul.docaction.domain.BoundingBox
import com.okayanshul.docaction.domain.DocumentSource
import com.okayanshul.docaction.domain.ExtractionHints
import com.okayanshul.docaction.domain.GroupId
import com.okayanshul.docaction.domain.PipelineAnswers
import com.okayanshul.docaction.domain.TermBounds
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.first

private val Context.journal: DataStore<Preferences> by preferencesDataStore("import_journal")

/** An import the user was partway through when the app went away. */
data class Interrupted(
    val source: DocumentSource,
    val answers: PipelineAnswers,
    val hints: ExtractionHints,
    val atEpochMillis: Long,
)

/**
 * Remembers an import across process death.
 *
 * **The inputs are saved, not the results.** A `ReviewSet` is a large object graph with
 * bitmaps' worth of provenance hanging off it, and serialising it would mean a second,
 * parallel definition of every domain type that then has to be kept in step. Re-running the
 * pipeline from the staged file takes a few seconds and produces exactly the same answer,
 * because the pipeline is deterministic given the same document and the same answers — which
 * is a property worth having anyway.
 *
 * What survives is therefore: the document, what the user has already told us, and where
 * they told us to look. That is enough to put them back where they were.
 */
class ImportJournal(private val context: Context) {

    suspend fun remember(source: DocumentSource, answers: PipelineAnswers, hints: ExtractionHints) {
        context.journal.edit { entry ->
            entry[Keys.Uri] = source.uri
            entry[Keys.Name] = source.displayName
            entry[Keys.Mime] = source.declaredMimeType.orEmpty()
            entry[Keys.Size] = source.sizeBytes
            entry[Keys.At] = System.currentTimeMillis()

            // Written as set-or-clear rather than set-if-present: an answer the user has
            // since retracted must not survive as one they appear to have given. `-=` rather
            // than `remove`, whose declared return type unboxes to a crash on a missing key.
            val term = answers.term
            if (term != null) {
                entry[Keys.TermStart] = term.start.toEpochDay()
                entry[Keys.TermEnd] = term.end.toEpochDay()
            } else {
                entry -= Keys.TermStart
                entry -= Keys.TermEnd
            }

            answers.selectedGroup
                ?.let { entry[Keys.Group] = it.value }
                ?: run { entry -= Keys.Group }

            answers.assumedYear
                ?.let { entry[Keys.Year] = it }
                ?: run { entry -= Keys.Year }

            hints.pageSelection
                ?.let { entry[Keys.Pages] = it.joinToString(",") }
                ?: run { entry -= Keys.Pages }

            hints.cropRegion
                ?.let { entry[Keys.Crop] = "${it.left},${it.top},${it.right},${it.bottom}" }
                ?: run { entry -= Keys.Crop }
        }
    }

    suspend fun forget() {
        context.journal.edit { it.clear() }
    }

    /**
     * @return null when there is nothing to resume — including when the record survived but
     *   the document behind it did not, which is the case after the abandoned-import sweep.
     *   A resume offer that fails when tapped is worse than no offer.
     */
    suspend fun interrupted(): Interrupted? {
        val entry = context.journal.data.first()
        val uri = entry[Keys.Uri] ?: return null
        if (!File(uri).exists()) return null

        val start = entry[Keys.TermStart]
        val end = entry[Keys.TermEnd]

        return Interrupted(
            source = DocumentSource(
                uri = uri,
                displayName = entry[Keys.Name] ?: File(uri).name,
                declaredMimeType = entry[Keys.Mime]?.ifBlank { null },
                sizeBytes = entry[Keys.Size] ?: 0L,
            ),
            answers = PipelineAnswers(
                term = if (start != null && end != null) {
                    TermBounds(LocalDate.ofEpochDay(start), LocalDate.ofEpochDay(end))
                } else {
                    null
                },
                selectedGroup = entry[Keys.Group]?.let(::GroupId),
                assumedYear = entry[Keys.Year],
            ),
            hints = ExtractionHints(
                pageSelection = entry[Keys.Pages]
                    ?.split(',')
                    ?.mapNotNull(String::toIntOrNull)
                    ?.takeIf { it.isNotEmpty() },
                cropRegion = entry[Keys.Crop]?.split(',')?.mapNotNull(String::toFloatOrNull)
                    ?.takeIf { it.size == 4 }
                    ?.let { BoundingBox(it[0], it[1], it[2], it[3]) },
            ),
            atEpochMillis = entry[Keys.At] ?: 0L,
        )
    }

    private object Keys {
        val Uri = stringPreferencesKey("document_uri")
        val Name = stringPreferencesKey("document_name")
        val Mime = stringPreferencesKey("document_mime")
        val Size = longPreferencesKey("document_size")
        val At = longPreferencesKey("recorded_at")
        val TermStart = longPreferencesKey("term_start_epoch_day")
        val TermEnd = longPreferencesKey("term_end_epoch_day")
        val Group = stringPreferencesKey("selected_group")
        val Year = intPreferencesKey("assumed_year")
        val Pages = stringPreferencesKey("page_selection")
        val Crop = stringPreferencesKey("crop_region")
    }
}
