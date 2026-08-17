package com.okayanshul.docaction.document.csv

import com.okayanshul.docaction.document.spreadsheet.SpreadsheetSchedules
import com.okayanshul.docaction.domain.DocumentFormat
import com.okayanshul.docaction.domain.DocumentSource
import com.okayanshul.docaction.domain.ExtractionHints
import com.okayanshul.docaction.domain.FailureReason
import com.okayanshul.docaction.domain.FoundSchedules
import com.okayanshul.docaction.domain.GroupId
import com.okayanshul.docaction.domain.Outcome
import com.okayanshul.docaction.domain.PipelineAnswers
import com.okayanshul.docaction.domain.ScheduleGroup
import com.okayanshul.docaction.domain.ScheduleSource
import com.okayanshul.docaction.domain.SourceReference
import com.okayanshul.docaction.domain.Stage
import com.okayanshul.docaction.domain.StageProgress
import java.io.File

/**
 * A CSV reaches the pipeline the same way a workbook does.
 *
 * [ScheduleSource] rather than `DocumentReader`, for the reason ADR-011 gives: a delimited
 * file states its own grid. Handing it to geometric table detection would mean synthesising
 * coordinates and then inferring back the structure the file already declared — the mistake
 * that once cost three period columns on a real timetable.
 *
 * Everything past the parse is [SpreadsheetSchedules], unchanged.
 */
class CsvScheduleSource(
    private val fileFor: (DocumentSource) -> File?,
    private val reader: CsvReader = CsvReader(),
    private val schedules: SpreadsheetSchedules = SpreadsheetSchedules(),
) : ScheduleSource {

    override fun supports(format: DocumentFormat) = format == DocumentFormat.Csv

    override suspend fun read(
        source: DocumentSource,
        hints: ExtractionHints,
        answers: PipelineAnswers,
        onProgress: (StageProgress) -> Unit,
    ): Outcome<FoundSchedules> {
        val file = fileFor(source) ?: return Outcome.Failure(FailureReason.PermissionRevoked)

        val workbook = when (val result = reader.read(file, sheetName = source.displayName)) {
            is CsvReader.Result.Read -> result.workbook
            is CsvReader.Result.Refused -> return Outcome.Failure(
                when (result.why) {
                    CsvReader.Failure.Empty -> FailureReason.Empty
                    CsvReader.Failure.TooLarge -> FailureReason.TooLarge
                    // Not "corrupt": the file is fine, it simply is not text we can read.
                    CsvReader.Failure.NotText -> FailureReason.UnsupportedFormat
                }
            )
        }

        onProgress(StageProgress(Stage.DetectingStructure))
        val detected = schedules.detect(workbook)
        if (detected.isEmpty()) return Outcome.Success(FoundSchedules(emptyList()))

        onProgress(StageProgress(Stage.BuildingSchedule, 0, detected.size))

        // Same two-pass shape as a workbook: list cheaply, build the chosen one in full.
        val chosen = answers.selectedGroup?.let { id -> detected.firstOrNull { it.label == id.value } }
        if (chosen != null) {
            return Outcome.Success(schedules.build(workbook, chosen, answers))
        }
        if (detected.size == 1) {
            return Outcome.Success(schedules.build(workbook, detected.single(), answers))
        }

        return Outcome.Success(
            FoundSchedules(
                detected.map { candidate ->
                    ScheduleGroup(
                        id = GroupId(candidate.label),
                        label = candidate.label,
                        entries = emptyList(),
                        source = SourceReference.CsvCell(candidate.block.headerRow, 0),
                        estimatedSize = candidate.entryCount,
                    )
                }
            )
        )
    }
}
