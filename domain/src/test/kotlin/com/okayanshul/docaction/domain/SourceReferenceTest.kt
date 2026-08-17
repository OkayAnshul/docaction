package com.okayanshul.docaction.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The contract that makes Source View possible: a reference's box is a proportion of its
 * page, not a measurement in whatever units the reader happened to work in.
 *
 * Without it, a highlight is only correct when the page is redrawn at exactly the size it
 * was read at — which is never, because reading happens at 300 dpi and showing happens at
 * whatever fits the screen.
 */
class SourceReferenceTest {

    @Test
    fun `a box becomes a proportion of its page`() {
        val box = BoundingBox(left = 72f, top = 144f, right = 144f, bottom = 216f)
        val fraction = box.fractionOf(width = 720f, height = 1440f)

        assertThat(fraction.left).isWithin(1e-5f).of(0.1f)
        assertThat(fraction.top).isWithin(1e-5f).of(0.1f)
        assertThat(fraction.right).isWithin(1e-5f).of(0.2f)
        assertThat(fraction.bottom).isWithin(1e-5f).of(0.15f)
    }

    @Test
    fun `a box overhanging the page is clamped rather than drawn outside it`() {
        // Glyph boxes routinely overhang by a fraction of a point.
        val fraction = BoundingBox(-5f, -5f, 800f, 1500f).fractionOf(720f, 1440f)

        assertThat(fraction.left).isEqualTo(0f)
        assertThat(fraction.top).isEqualTo(0f)
        assertThat(fraction.right).isEqualTo(1f)
        assertThat(fraction.bottom).isEqualTo(1f)
    }

    @Test
    fun `a page with no area means somewhere on this page, not a division by zero`() {
        val fraction = BoundingBox(10f, 10f, 20f, 20f).fractionOf(0f, 0f)
        assertThat(fraction).isEqualTo(BoundingBox(0f, 0f, 1f, 1f))
    }

    @Test
    fun `a photo has no page number and a scanned page does`() {
        assertThat(SourceReference.ImageRegion(BoundingBox(0f, 0f, 1f, 1f)).page).isNull()
        assertThat(SourceReference.ImageRegion(BoundingBox(0f, 0f, 1f, 1f), page = 3).page).isEqualTo(3)
    }
}
