package com.okayanshul.docaction.extraction.date

import com.google.common.truth.Truth.assertThat
import com.okayanshul.docaction.domain.BoundingBox
import com.okayanshul.docaction.domain.DateOrder
import com.okayanshul.docaction.domain.ResolutionEvidence
import com.okayanshul.docaction.domain.SourceReference
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Test

class DateResolverTest {

    private val engine = DateEngine()
    private val resolver = DateResolver()

    private fun at(page: Int = 1) =
        SourceReference.PdfSpan(page, BoundingBox(0f, 0f, 10f, 10f))

    private fun located(text: String, weekday: DayOfWeek? = null, page: Int = 1) =
        LocatedDate(engine.parse(text).first(), at(page), weekday)

    @Test
    fun `a sibling date above twelve resolves the whole document`() {
        val result = resolver.resolve(
            listOf(
                located("03/04/2026"),
                located("13/04/2026"),
            )
        )

        assertThat(result.order).isEqualTo(DateOrder.DayFirst)
        assertThat(result.interpretations[0].resolved).isEqualTo(LocalDate.of(2026, 4, 3))
        assertThat(result.questions).isEmpty()
    }

    @Test
    fun `a month-first sibling resolves the document the other way`() {
        val result = resolver.resolve(
            listOf(
                located("03/04/2026"),
                located("04/13/2026"),
            )
        )

        assertThat(result.order).isEqualTo(DateOrder.MonthFirst)
        assertThat(result.interpretations[0].resolved).isEqualTo(LocalDate.of(2026, 3, 4))
    }

    @Test
    fun `contradictory sibling evidence resolves nothing`() {
        // One date proves day-first, another proves month-first. The document is
        // inconsistent, so we trust neither and ask.
        val result = resolver.resolve(
            listOf(
                located("13/04/2026"),
                located("04/13/2026"),
                located("03/04/2026"),
            )
        )

        assertThat(result.order).isNull()
        assertThat(result.interpretations[2].isAmbiguous).isTrue()
    }

    @Test
    fun `a stated weekday resolves an otherwise ambiguous date`() {
        // 2026-04-03 is a Friday; 2026-03-04 is a Wednesday.
        val result = resolver.resolve(listOf(located("03/04/2026", weekday = DayOfWeek.FRIDAY)))

        assertThat(result.interpretations.single().resolved).isEqualTo(LocalDate.of(2026, 4, 3))
        assertThat(result.interpretations.single().resolvedBy)
            .isInstanceOf(ResolutionEvidence.WeekdayAgreement::class.java)
    }

    @Test
    fun `sequence coherence does not fire when both readings are ordered`() {
        // Day-first:   5 Jan, 6 Feb, 7 Mar, 8 Apr — increasing.
        // Month-first: 1 May, 2 Jun, 3 Jul, 4 Aug — also increasing.
        // Two working readings is not evidence, so nothing may be inferred.
        val result = resolver.resolve(
            listOf(
                located("05/01/2026"),
                located("06/02/2026"),
                located("07/03/2026"),
                located("08/04/2026"),
            )
        )

        assertThat(result.order).isNull()
        assertThat(result.interpretations.all { it.isAmbiguous }).isTrue()
    }

    @Test
    fun `sequence coherence resolves day-first when only that reading is ordered`() {
        // Day-first:   6 Jan, 5 Feb, 4 Mar, 3 Apr — increasing.
        // Month-first: 1 Jun, 2 May, 3 Apr, 4 Mar — decreasing.
        val result = resolver.resolve(
            listOf(
                located("06/01/2026"),
                located("05/02/2026"),
                located("04/03/2026"),
                located("03/04/2026"),
            )
        )

        assertThat(result.order).isEqualTo(DateOrder.DayFirst)
        assertThat(result.interpretations.first().resolved).isEqualTo(LocalDate.of(2026, 1, 6))
    }

    @Test
    fun `sequence coherence resolves month-first when only that reading is ordered`() {
        // Day-first:   1 Dec, 2 Nov, 3 Oct, 4 Sep — decreasing.
        // Month-first: 12 Jan, 11 Feb, 10 Mar, 9 Apr — increasing.
        val result = resolver.resolve(
            listOf(
                located("01/12/2026"),
                located("02/11/2026"),
                located("03/10/2026"),
                located("04/09/2026"),
            )
        )

        assertThat(result.order).isEqualTo(DateOrder.MonthFirst)
        assertThat(result.interpretations.first().resolved).isEqualTo(LocalDate.of(2026, 1, 12))
    }

    @Test
    fun `a short run of dates is not enough for sequence evidence`() {
        val result = resolver.resolve(
            listOf(
                located("01/02/2026"),
                located("02/03/2026"),
            )
        )
        assertThat(result.order).isNull()
        assertThat(result.questions).isNotEmpty()
    }

    @Test
    fun `unresolved ambiguity produces a question and blocks resolution`() {
        val result = resolver.resolve(listOf(located("03/04/2026")))

        val interpretation = result.interpretations.single()
        assertThat(interpretation.isAmbiguous).isTrue()
        assertThat(interpretation.resolved).isNull()
        assertThat(result.questions).containsExactly(
            DateQuestion.Order("03/04/2026", LocalDate.of(2026, 4, 3), LocalDate.of(2026, 3, 4))
        )
    }

    @Test
    fun `the user's answer resolves the document`() {
        val result = resolver.resolve(
            dates = listOf(located("03/04/2026")),
            userOrder = DateOrder.MonthFirst,
            userOrderAtEpochMillis = 1_000L,
        )

        assertThat(result.interpretations.single().resolved).isEqualTo(LocalDate.of(2026, 3, 4))
        assertThat(result.orderEvidence)
            .isEqualTo(ResolutionEvidence.UserChoice(DateOrder.MonthFirst, 1_000L))
    }

    @Test
    fun `an impossible date yields no candidates and is not repaired`() {
        val result = resolver.resolve(listOf(located("32/09/2026")))

        val interpretation = result.interpretations.single()
        assertThat(interpretation.candidates).isEmpty()
        assertThat(interpretation.resolved).isNull()
        assertThat(interpretation.raw).isEqualTo("32/09/2026")
    }

    @Test
    fun `a missing year is asked about rather than assumed`() {
        val result = resolver.resolve(listOf(located("18/09")))

        assertThat(result.questions).contains(DateQuestion.Year("18/09"))
        assertThat(result.interpretations.single().resolved).isNull()
    }

    @Test
    fun `a supplied year completes an otherwise unresolvable date`() {
        val result = resolver.resolve(listOf(located("18/09")), assumedYear = 2026)

        assertThat(result.interpretations.single().resolved).isEqualTo(LocalDate.of(2026, 9, 18))
    }

    @Test
    fun `an unambiguous form needs no evidence`() {
        val result = resolver.resolve(listOf(located("18 September 2026")))

        assertThat(result.interpretations.single().resolved).isEqualTo(LocalDate.of(2026, 9, 18))
        assertThat(result.interpretations.single().resolvedBy).isEqualTo(ResolutionEvidence.Unambiguous)
        assertThat(result.questions).isEmpty()
    }
}
