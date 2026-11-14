package com.teenradar.decode

import com.teenradar.trends.Risk
import com.teenradar.trends.Term

/** What the model returns for one slang term. */
data class AiTerm(val term: String, val meaning: String, val risk: Risk, val why: String = "")

/** One term as shown to the parent. `verified` = it comes from our curated code list, not only from the AI. */
data class DecodedTerm(
    val term: String,
    val meaning: String,
    val risk: Risk,
    val why: String,
    val verified: Boolean,
    val slug: String,
)

private const val VARIATION_SELECTOR = "️"

/** "No Cap" -> "no-cap", "🔌" -> "1f50c", "❄️" -> "2744" (emoji = code points without the variation selector). */
fun slugOf(term: String): String {
    val clean = term.replace(VARIATION_SELECTOR, "").trim()
    val text = clean.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    if (text.isNotEmpty()) return text
    return clean.codePoints().toArray().joinToString("-") { Integer.toHexString(it) }
}

private fun mentions(text: String, code: String): Boolean {
    val needle = Regex.escape(code.replace(VARIATION_SELECTOR, ""))
    return Regex("(?i)(?<![\\p{L}\\p{N}])$needle(?![\\p{L}\\p{N}])").containsMatchIn(text.replace(VARIATION_SELECTOR, ""))
}

/**
 * The safety net: curated codes beat the model.
 * 1. A term the AI found that is a known code gets the known meaning and at least the known risk.
 * 2. An unambiguous known code that appears in the message is added even if the AI missed it.
 */
fun applyKnownCodes(message: String, aiTerms: List<AiTerm>, known: List<Term>): List<DecodedTerm> {
    val bySlug = known.associateBy { it.slug }
    val result = aiTerms.map { ai ->
        val slug = slugOf(ai.term)
        val code = bySlug[slug]
        if (code == null) {
            DecodedTerm(ai.term, ai.meaning, ai.risk, ai.why, verified = false, slug = slug)
        } else {
            DecodedTerm(ai.term, code.meaning, maxOf(ai.risk, code.risk), code.why ?: ai.why, verified = true, slug = slug)
        }
    }
    val found = result.map { it.slug }.toSet()
    val missed = known
        .filter { it.matchInText && it.slug !in found && mentions(message, it.term) }
        .map { DecodedTerm(it.term, it.meaning, it.risk, it.why ?: "", verified = true, slug = it.slug) }
    return result + missed
}
