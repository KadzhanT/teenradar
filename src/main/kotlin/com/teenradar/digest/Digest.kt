package com.teenradar.digest

import com.teenradar.AppProperties
import com.teenradar.trends.Risk
import com.teenradar.trends.TrendService
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.slf4j.LoggerFactory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.http.MediaType
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClient
import org.springframework.web.util.HtmlUtils

@Entity
@Table(name = "subscribers")
class Subscriber(
    var email: String,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
)

interface SubscriberRepository : JpaRepository<Subscriber, Long> {
    @Transactional
    @Modifying
    @Query("insert into subscribers (email) values (:email) on conflict do nothing", nativeQuery = true)
    fun add(email: String): Int
}

private val EMAIL = Regex("^[^@\\s]{1,64}@[^@\\s]{1,255}\\.[^@\\s]{2,}$")

data class SubscribeRequest(val email: String = "")

@RestController
class SubscribeController(private val digest: DigestService) {
    @PostMapping("/api/subscribe")
    fun subscribe(@RequestBody body: SubscribeRequest): Map<String, Boolean> {
        val email = body.email.trim().lowercase()
        require(email.length <= 254 && EMAIL.matches(email)) { "Enter a valid email." }
        return mapOf("ok" to digest.subscribe(email))
    }
}

/** Weekly "what your kid is saying" email. The product comes to the parent instead of waiting to be asked. */
@Service
class DigestService(
    builder: RestClient.Builder,
    private val props: AppProperties,
    private val subscribers: SubscriberRepository,
    private val trends: TrendService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val resend = builder.baseUrl("https://api.resend.com").build()

    /** Subscribes and sends this week's brief right away, so the parent sees value immediately. */
    fun subscribe(email: String): Boolean {
        val isNew = subscribers.add(email) > 0
        if (isNew) runCatching { send(email, brief()) }.onFailure { log.warn("Welcome email failed", it) }
        return true
    }

    @Scheduled(cron = "0 0 14 * * MON", zone = "UTC")
    fun weekly() {
        val html = brief()
        subscribers.findAll().forEach { s ->
            runCatching { send(s.email, html) }.onFailure { log.warn("Digest to subscriber {} failed", s.id, it) }
        }
    }

    private fun brief(): String {
        val items = trends.feed(limit = 5).joinToString("") { item ->
            val t = item.row
            val color = when (Risk.valueOf(t.risk)) {
                Risk.GREEN -> "#16a34a"
                Risk.YELLOW -> "#ca8a04"
                Risk.RED -> "#dc2626"
            }
            """<li style="margin:0 0 12px"><b>${esc(t.term)}</b> <span style="color:$color">● ${t.risk.lowercase()}</span><br>
               ${esc(t.meaning)} <a href="${props.baseUrl}/t/${t.slug}">How to talk about it →</a></li>"""
        }
        return """<div style="font-family:system-ui,sans-serif;max-width:560px">
            <h2>This week on TeenRadar</h2><p>What kids are saying right now, and what it actually means:</p>
            <ul style="padding-left:18px">$items</ul>
            <p><a href="${props.baseUrl}/">Decode a message from your kid →</a></p>
            <p style="color:#888;font-size:12px">If you think your child is in danger, call 911. Crisis support: call or text 988.</p></div>"""
    }

    private fun send(to: String, html: String) {
        if (props.resend.apiKey.isBlank()) {
            log.info("RESEND_API_KEY not set, skipping email")
            return
        }
        resend.post()
            .uri("/emails")
            .header("Authorization", "Bearer ${props.resend.apiKey}")
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("from" to props.resend.from, "to" to listOf(to), "subject" to "What your kid is saying this week", "html" to html))
            .retrieve()
            .toBodilessEntity()
    }

    private fun esc(s: String) = HtmlUtils.htmlEscape(s)
}
