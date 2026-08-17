package com.okayanshul.docaction.imports

import com.okayanshul.docaction.domain.Assumption
import com.okayanshul.docaction.domain.FailureReason
import com.okayanshul.docaction.domain.Unresolved

/** One recovery route offered on a failure screen. */
data class Recovery(val label: String, val action: RecoveryAction)

/**
 * What a recovery button actually does.
 *
 * Every value here is wired to working behaviour. A route that is designed but not built
 * stays out of this enum rather than appearing as an inert button — a button that does
 * nothing is worse than no button.
 */
enum class RecoveryAction { ChooseAnotherSchedule, ShowUsWhere, PickAnother, PickPhoto, Dismiss }

/**
 * Turns a failure into something a person can act on.
 *
 * Every [FailureReason] maps to exactly one screen, and none of them mentions a parser, a
 * stage, an exception or a code. The tone is *this document is difficult* — never *you did
 * something wrong*, and never *our software broke*, because the second is unkind and the
 * third destroys confidence in the results the app does produce.
 *
 * Every failure offers at least one way forward. A screen with only a "Close" button is a bug.
 */
object Copy {

    data class Failure(
        val headline: String,
        val cause: String?,
        val recoveries: List<Recovery>,
    )

    private val tryAnother = Recovery("Choose another file", RecoveryAction.PickAnother)
    private val tryPhoto = Recovery("Use a photo instead", RecoveryAction.PickPhoto)
    private val showUs = Recovery("Show us the part you need", RecoveryAction.ShowUsWhere)

    /**
     * The same failure means something different after the user has drawn a box.
     *
     * "We couldn't find a schedule" is true but useless there: the document is fine, the
     * selection was too narrow. The engine needs several weekdays and several times before
     * it will call something a weekly timetable, and the fix is to include the headings —
     * which is a thing a person can actually do.
     */
    /**
     * The section the user picked had nothing readable in it.
     *
     * "We couldn't find a schedule in this" would be false — we found several, and they
     * chose one of them. Saying so, and putting them back at the list rather than at the
     * start, is the difference between a dead end and a wrong turn.
     */
    fun forEmptySchedule(label: String?): Failure = Failure(
        headline = if (label == null) {
            "That one had nothing we could read"
        } else {
            "\"$label\" had nothing we could read"
        },
        cause = "The other sections in this document may still work.",
        recoveries = listOf(
            Recovery("Choose a different one", RecoveryAction.ChooseAnotherSchedule),
            tryAnother,
        ),
    )

    fun forFailure(reason: FailureReason, afterCrop: Boolean): Failure =
        if (afterCrop && reason == FailureReason.NothingActionable) {
            Failure(
                headline = "That selection was too narrow",
                cause = "We need the day and time headings, and more than one day, before " +
                    "we'll call something a timetable.",
                recoveries = listOf(
                    Recovery("Select more of the page", RecoveryAction.ShowUsWhere),
                    tryAnother,
                ),
            )
        } else {
            forFailure(reason)
        }

    fun forFailure(reason: FailureReason): Failure = when (reason) {
        FailureReason.Encrypted -> Failure(
            headline = "This document is password protected",
            cause = "We can't open protected files, even with the password.",
            recoveries = listOf(tryAnother),
        )

        FailureReason.Corrupt -> Failure(
            headline = "This file appears to be damaged",
            cause = "It may not have finished downloading.",
            recoveries = listOf(tryAnother),
        )

        // OCR has already been tried by this point, so promising to "read it as an image"
        // would be offering a road we have just driven down.
        FailureReason.NoTextLayer -> Failure(
            headline = "We couldn't read any text in this PDF",
            cause = "It's a scan, and the page was too faint or too skewed to read.",
            recoveries = listOf(showUs, tryPhoto, tryAnother),
        )

        FailureReason.Empty -> Failure(
            headline = "This file is empty",
            cause = "There's nothing in it to read.",
            recoveries = listOf(tryAnother),
        )

        // Deliberately no "show us the part you need" here. The size check happens before
        // the file is ever opened, so pointing at one page of it cannot help — the offer
        // would look like a fix and dead-end.
        FailureReason.TooLarge -> Failure(
            headline = "This file is too large",
            cause = "We can handle files up to 100 MB.",
            recoveries = listOf(tryAnother),
        )

        FailureReason.UnsupportedFormat -> Failure(
            headline = "We can't read this kind of file",
            cause = "Right now we read PDFs, Excel files, CSV files and photos.",
            recoveries = listOf(tryAnother),
        )

        FailureReason.PermissionRevoked -> Failure(
            headline = "We can't open this file any more",
            cause = "The app that shared it withdrew access before we could read it.",
            recoveries = listOf(Recovery("Choose it again", RecoveryAction.PickAnother)),
        )

        // "We looked at too much." Narrowing to one page is the actual fix, and a far
        // better answer than asking for a different document.
        FailureReason.Timeout -> Failure(
            headline = "This is taking too long",
            cause = "The document is unusually complex.",
            recoveries = listOf(showUs, tryAnother),
        )

        FailureReason.Cancelled -> Failure(
            headline = "Cancelled",
            cause = "Nothing was changed.",
            recoveries = listOf(Recovery("Done", RecoveryAction.Dismiss)),
        )

        FailureReason.NothingActionable -> Failure(
            headline = "We couldn't find a schedule in this",
            cause = "There were no dates or times we could read with confidence.",
            recoveries = listOf(showUs, tryPhoto, tryAnother),
        )

        FailureReason.StorageUnavailable -> Failure(
            headline = "Not enough space",
            cause = "Free up a little storage and try again.",
            recoveries = listOf(tryAnother),
        )

        FailureReason.ProcessingUnavailable -> Failure(
            headline = "We couldn't process this",
            cause = "Something went wrong on our side. Nothing was changed.",
            recoveries = listOf(tryAnother),
        )
    }

    /**
     * What we filled in, and the invitation to correct it.
     *
     * Deliberately says *we* assumed rather than describing the document. The user should be
     * able to tell at a glance which part of the row came from their file and which part
     * came from us — that distinction is the entire justification for filling gaps at all.
     */
    fun assumption(assumption: Assumption): String = when (assumption) {
        is Assumption.EndTime -> {
            val minutes = assumption.duration.toMinutes()
            val length = when {
                minutes % 60L == 0L -> countOf((minutes / 60).toInt(), "hour")
                else -> countOf(minutes.toInt(), "minute")
            }
            "End time assumed ($length) · tap to change"
        }

        is Assumption.NoTimeOfDay -> "No time given · added as all day, tap to set one"
    }

    /** The label on the row's inline fix, matched to what needs fixing. */
    fun fixLabel(assumption: Assumption): String = when (assumption) {
        is Assumption.EndTime -> "Change end time"
        is Assumption.NoTimeOfDay -> "Add a time"
    }

    /** The question a review row is asking, in plain language. */
    fun question(field: Unresolved.Field): String = when (field) {
        Unresolved.Field.Title -> "What is this called?"
        Unresolved.Field.Date -> "What date is this on?"
        Unresolved.Field.Weekday -> "Which day is this?"
        Unresolved.Field.StartTime -> "When does this start?"
        Unresolved.Field.EndTime -> "When does this end?"
        Unresolved.Field.Location -> "Where is this?"
        Unresolved.Field.Recurrence -> "When does this schedule end?"
    }

    /** "38 added." / "38 added. 3 couldn't be added." Never the word "Failed". */
    fun result(written: Int, failed: Int): String = when {
        written == 0 -> "Nothing was added."
        failed == 0 && written == 1 -> "1 event added."
        failed == 0 -> "$written events added."
        else -> "$written added. $failed couldn't be added."
    }

    fun countOf(n: Int, singular: String, plural: String = singular + "s"): String =
        if (n == 1) "1 $singular" else "$n $plural"
}
