package com.teenradar

import com.teenradar.decode.AiTerm
import com.teenradar.decode.applyKnownCodes
import com.teenradar.decode.slugOf
import com.teenradar.trends.Risk
import com.teenradar.trends.Source
import com.teenradar.trends.Term
import com.teenradar.trends.growthLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KnownCodesTest {
    private fun known(term: String, risk: Risk, matchInText: Boolean = true) =
        Term(slugOf(term), term, "known meaning of $term", risk, "why", null, Source.KNOWN, verified = true, matchInText = matchInText)

    private val codes = listOf(known("🔌", Risk.YELLOW), known("perc", Risk.RED), known("plug", Risk.YELLOW, matchInText = false))

    @Test
    fun should_make_slugs_for_words_and_emoji() {
        assertEquals("no-cap", slugOf("No Cap"))
        assertEquals("1f50c", slugOf("🔌"))
        assertEquals("2744", slugOf("❄️"))
        assertEquals("6-7", slugOf("6 7"))
    }

    @Test
    fun should_raise_risk_to_known_code_when_ai_underrates_it() {
        val result = applyKnownCodes("got percs", listOf(AiTerm("perc", "a pill", Risk.GREEN)), codes)
        assertEquals(Risk.RED, result.single { it.slug == "perc" }.risk)
        assertTrue(result.single { it.slug == "perc" }.verified)
    }

    @Test
    fun should_keep_ai_risk_when_it_is_higher_than_known() {
        val result = applyKnownCodes("x", listOf(AiTerm("🔌", "dealer selling fentanyl", Risk.RED)), codes)
        assertEquals(Risk.RED, result.single().risk)
    }

    @Test
    fun should_add_unambiguous_code_the_ai_missed() {
        val result = applyKnownCodes("bro got the 🔌 tonight", emptyList(), codes)
        assertEquals(listOf("1f50c"), result.map { it.slug })
    }

    @Test
    fun should_not_scan_ambiguous_codes_or_partial_words() {
        // "plug" is not matchInText, "percolator" must not match "perc"
        assertTrue(applyKnownCodes("plug in the percolator", emptyList(), codes).isEmpty())
    }

    @Test
    fun should_label_growth() {
        assertNull(growthLabel(0, 0))
        assertEquals("new", growthLabel(3, 0))
        assertEquals("↑×3", growthLabel(9, 3))
        assertNull(growthLabel(4, 3))
    }
}
