package com.teenradar.trends

import com.teenradar.ai.GeminiClient
import com.teenradar.decode.AiTerm
import com.teenradar.decode.applyKnownCodes
import com.teenradar.decode.slugOf
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

fun currentWeek(): LocalDate = LocalDate.now(ZoneOffset.UTC).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

/** Badge text for the community signal: "new", "↑×3", or null when there is nothing to brag about. */
fun growthLabel(hits: Int, prev: Int): String? = when {
    hits == 0 -> null
    prev == 0 -> "new"
    hits >= prev * 2 -> "↑×${hits / prev}"
    else -> null
}

data class FeedItem(val row: FeedRow, val growth: String?)

data class IngestedTerm(val term: String, val meaning: String, val risk: Risk, val why: String = "", val howToTalk: String = "")

@Service
class TrendService(
    private val terms: TermRepository,
    private val gemini: GeminiClient,
    private val json: JsonMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun feed(limit: Int = 30): List<FeedItem> {
        val week = currentWeek()
        return terms.feed(week, week.minusWeeks(1), limit).map { FeedItem(it, growthLabel(it.hits, it.prev)) }
    }

    fun alerts(limit: Int = 6): List<Term> = terms.alerts(limit)

    /** Daily: ask Gemini (grounded in live Google Search) what teens started saying, store what is new. */
    @Scheduled(cron = "0 0 6 * * *", zone = "UTC")
    fun scheduledIngest() {
        runCatching { ingest() }.onFailure { log.warn("Trend ingest failed", it) }
    }

    fun ingest(): Int {
        val answer = gemini.generateWithSearch(INGEST_PROMPT)
        val found = parse(answer)
        val known = terms.findBySource(Source.KNOWN)
        var added = 0
        for (t in found) {
            // Known codes keep their curated meaning and risk, the model can only raise attention, never lower it
            val safe = applyKnownCodes("", listOf(AiTerm(t.term, t.meaning, t.risk, t.why)), known).first()
            if (safe.slug.isBlank() || safe.verified) continue
            val existing = terms.findBySlug(safe.slug)
            if (existing == null) {
                terms.save(Term(safe.slug, t.term, t.meaning, t.risk, t.why, t.howToTalk, Source.INGEST))
                added++
            } else if (existing.source == Source.DECODE) {
                // promote a term parents were already asking about, now with a researched explanation
                existing.apply {
                    meaning = t.meaning
                    risk = maxOf(risk, t.risk)
                    why = t.why
                    howToTalk = t.howToTalk
                    source = Source.INGEST
                }.let(terms::save)
            }
        }
        log.info("Trend ingest: {} found, {} new", found.size, added)
        return added
    }

    private fun parse(answer: String): List<IngestedTerm> {
        val start = answer.indexOf('[')
        val end = answer.lastIndexOf(']')
        require(start >= 0 && end > start) { "No JSON array in ingest answer" }
        return json.readValue(answer.substring(start, end + 1), Array<IngestedTerm>::class.java)
            .filter { it.term.isNotBlank() && it.meaning.isNotBlank() && slugOf(it.term).isNotBlank() }
    }

    companion object {
        private val INGEST_PROMPT = """
            Use Google Search to find slang words, acronyms, emoji codes and memes that US teens (ages 10-17) are actively
            using in the last 30 days. Prefer terms that are new or newly trending. Include anything parents should worry
            about (drug, sexual, self-harm, sextortion codes) if it is genuinely circulating, but do not invent dangers.

            Return ONLY a JSON array (no prose, no markdown) of up to 15 objects:
            {"term": "...", "meaning": "...", "risk": "GREEN|YELLOW|RED", "why": "one sentence", "howToTalk": "one sentence a parent can say"}
            Meanings must be accurate to how teens actually use the term.
        """.trimIndent()
    }
}
