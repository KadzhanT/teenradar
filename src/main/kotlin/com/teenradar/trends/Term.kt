package com.teenradar.trends

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/** Ordered by severity, so `maxOf` gives the worst one. */
enum class Risk { GREEN, YELLOW, RED }

enum class Source { KNOWN, SEED, INGEST, DECODE }

@Entity
@Table(name = "terms")
class Term(
    var slug: String,
    var term: String,
    var meaning: String,
    @Enumerated(EnumType.STRING) var risk: Risk,
    var why: String?,
    var howToTalk: String?,
    @Enumerated(EnumType.STRING) var source: Source,
    var verified: Boolean = false,
    var matchInText: Boolean = false,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
)

interface FeedRow {
    val slug: String
    val term: String
    val meaning: String
    val risk: String
    val source: String
    val verified: Boolean
    val hits: Int
    val prev: Int
}

interface TermRepository : JpaRepository<Term, Long> {
    fun findBySlug(slug: String): Term?

    fun findBySource(source: Source): List<Term>

    @Transactional
    @Modifying
    @Query(
        """
        insert into terms (slug, term, meaning, risk, why, source) values (:slug, :term, :meaning, :risk, :why, :source)
        on conflict (slug) do nothing
        """,
        nativeQuery = true,
    )
    fun insertIfAbsent(slug: String, term: String, meaning: String, risk: String, why: String?, source: String): Int

    @Transactional
    @Modifying
    @Query(
        """
        insert into term_hits (term_id, week_start, hits) values (:termId, :week, 1)
        on conflict (term_id, week_start) do update set hits = term_hits.hits + 1
        """,
        nativeQuery = true,
    )
    fun hit(termId: Long, week: LocalDate)

    /** Most-asked terms this week first, then the newest. `prev` = hits last week. */
    @Query(
        """
        select t.slug, t.term, t.meaning, t.risk, t.source, t.verified,
               coalesce(cur.hits, 0) as hits, coalesce(old.hits, 0) as prev
        from terms t
        left join term_hits cur on cur.term_id = t.id and cur.week_start = :thisWeek
        left join term_hits old on old.term_id = t.id and old.week_start = :lastWeek
        where t.source in ('SEED', 'INGEST') or cur.hits >= 2
        order by hits desc, t.created_at desc
        limit :limit
        """,
        nativeQuery = true,
    )
    fun feed(thisWeek: LocalDate, lastWeek: LocalDate, limit: Int): List<FeedRow>

    @Query(
        "select * from terms where source = 'KNOWN' and risk = 'RED' order by created_at, id limit :limit",
        nativeQuery = true,
    )
    fun alerts(limit: Int): List<Term>
}
