package com.okayanshul.docaction.document.spreadsheet

import com.okayanshul.docaction.domain.DocumentFormat
import com.okayanshul.docaction.domain.DocumentSource
import com.okayanshul.docaction.domain.ExtractionHints
import com.okayanshul.docaction.domain.FailureReason
import com.okayanshul.docaction.domain.FoundSchedules
import com.okayanshul.docaction.domain.GroupId
import com.okayanshul.docaction.domain.PipelineAnswers
import com.okayanshul.docaction.domain.PipelineQuestion
import com.okayanshul.docaction.domain.Outcome
import com.okayanshul.docaction.domain.ScheduleGroup
import com.okayanshul.docaction.domain.ScheduleSource
import com.okayanshul.docaction.domain.SourceReference
import com.okayanshul.docaction.domain.Stage
import com.okayanshul.docaction.domain.StageProgress
import java.io.File

/**
 * Reads schedules straight out of a workbook.
 *
 * Detection is separated from extraction on purpose. A real institutional export contained
 * **335 section blocks**; building every one of them so the user can pick a single section
 * would be slow and pointless, so this lists them cheaply and only the chosen block goes
 * through the full engine.
 */
class XlsxScheduleSource(
    private val fileFor: (DocumentSource) -> File?,
    private val schedules: SpreadsheetSchedules = SpreadsheetSchedules(),
) : ScheduleSource {

    override fun supports(format: DocumentFormat) = format == DocumentFormat.Xlsx

    override suspend fun read(
        source: DocumentSource,
        hints: ExtractionHints,
        answers: PipelineAnswers,
        onProgress: (StageProgress) -> Unit,
    ): Outcome<FoundSchedules> {
        val file = fileFor(source) ?: return Outcome.Failure(FailureReason.PermissionRevoked)

        val workbook = try {
            schedules.open(file)
        } catch (e: XlsxException) {
            return Outcome.Failure(
                when (e.failure) {
                    XlsxFailure.Empty -> FailureReason.Empty
                    XlsxFailure.TooLarge -> FailureReason.TooLarge
                    XlsxFailure.NotAWorkbook -> FailureReason.UnsupportedFormat
                    // A hostile archive is reported as damaged rather than accused; the user
                    // gains nothing from "we think your file is an attack".
                    XlsxFailure.Hostile, XlsxFailure.Corrupt -> FailureReason.Corrupt
                }
            )
        }

        onProgress(StageProgress(Stage.DetectingStructure))
        val detected = schedules.detect(workbook)
        if (detected.isEmpty()) return Outcome.Success(FoundSchedules(emptyList()))

        onProgress(StageProgress(Stage.BuildingSchedule, 0, detected.size))

        // Second pass. The user has picked one of the placeholders below, so build that one
        // in full and nothing else.
        val chosen = answers.selectedGroup?.let { id ->
            detected.firstOrNull { it.label == id.value }
        }
        if (chosen != null) {
            return Outcome.Success(schedules.build(workbook, chosen, answers))
        }

        // With exactly one schedule there is nothing to choose, so extract it immediately.
        if (detected.size == 1) {
            return Outcome.Success(schedules.build(workbook, detected.single(), answers))
        }

        // First pass. Placeholders carrying the labels and sizes the picker needs, without
        // building hundreds of timetables to show a list.
        return Outcome.Success(
            FoundSchedules(
                detected.map { candidate ->
                    ScheduleGroup(
                        id = GroupId(candidate.label),
                        label = candidate.label,
                        entries = emptyList(),
                        source = SourceReference.SheetCell(
                            sheet = candidate.sheetName,
                            row = candidate.block.headerRow,
                            column = 0,
                        ),
                        estimatedSize = candidate.entryCount,
                    )
                }
            )
        )
    }

}
