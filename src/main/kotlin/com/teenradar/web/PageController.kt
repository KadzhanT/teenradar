package com.teenradar.web

import com.teenradar.AppProperties
import com.teenradar.trends.TermRepository
import com.teenradar.trends.TrendService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest

@Controller
class PageController(
    private val trends: TrendService,
    private val terms: TermRepository,
    private val visitors: VisitorRepository,
    private val props: AppProperties,
) {
    @GetMapping("/")
    fun home(request: HttpServletRequest, model: Model): String {
        model.addAttribute("feed", trends.feed())
        model.addAttribute("alerts", trends.alerts())
        model.addAttribute("premium", visitors.findById(request.visitorId()).map { it.premium }.orElse(false))
        model.addAttribute("freeLimit", props.freeDecodesPerDay)
        model.addAttribute("baseUrl", props.baseUrl)
        return "index"
    }

    /** One indexable page per term: "what does X mean" is how parents find us on Google. */
    @GetMapping("/t/{slug}")
    fun term(@PathVariable slug: String, model: Model): String {
        val term = terms.findBySlug(slug) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        model.addAttribute("t", term)
        model.addAttribute("baseUrl", props.baseUrl)
        return "term"
    }

    @GetMapping("/sitemap.xml", produces = ["application/xml"])
    @ResponseBody
    fun sitemap(): String {
        val urls = terms.findAll().joinToString("") { "<url><loc>${props.baseUrl}/t/${it.slug}</loc></url>" }
        return """<?xml version="1.0" encoding="UTF-8"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">""" +
            "<url><loc>${props.baseUrl}/</loc></url>$urls</urlset>"
    }

    /** Manual trigger for the daily trend job (run before the demo). */
    @PostMapping("/api/admin/ingest")
    @ResponseBody
    fun ingest(@RequestHeader("X-Admin-Token", required = false) token: String?): Map<String, Int> {
        val expected = props.adminToken
        val ok = expected.isNotBlank() && token != null &&
            MessageDigest.isEqual(token.toByteArray(), expected.toByteArray())
        if (!ok) throw ResponseStatusException(HttpStatus.FORBIDDEN)
        return mapOf("added" to trends.ingest())
    }
}
