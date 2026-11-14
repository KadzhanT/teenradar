package com.teenradar.billing

import com.teenradar.AppProperties
import com.teenradar.web.VisitorRepository
import com.teenradar.web.visitorId
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClient
import java.net.URI

/**
 * Premium via Stripe Checkout (test mode: card 4242 4242 4242 4242).
 * No webhook: on return we ask Stripe whether the session is paid and belongs to this visitor.
 */
@RestController
class BillingController(
    builder: RestClient.Builder,
    private val props: AppProperties,
    private val visitors: VisitorRepository,
) {
    private val stripe = builder.baseUrl("https://api.stripe.com/v1").build()

    @PostMapping("/api/checkout")
    fun checkout(request: HttpServletRequest): Map<String, String> {
        require(props.stripe.secretKey.isNotBlank()) { "Payments are not configured yet." }
        val vid = request.visitorId()
        visitors.ensure(vid)
        val form = LinkedMultiValueMap<String, String>().apply {
            add("mode", "subscription")
            add("client_reference_id", vid.toString())
            add("line_items[0][quantity]", "1")
            add("line_items[0][price_data][currency]", "usd")
            add("line_items[0][price_data][unit_amount]", props.stripe.priceCents.toString())
            add("line_items[0][price_data][recurring][interval]", "month")
            add("line_items[0][price_data][product_data][name]", "TeenRadar Premium")
            add("success_url", "${props.baseUrl}/billing/success?session_id={CHECKOUT_SESSION_ID}")
            add("cancel_url", "${props.baseUrl}/")
        }
        val session = stripe.post()
            .uri("/checkout/sessions")
            .header("Authorization", "Bearer ${props.stripe.secretKey}")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(Map::class.java)
        return mapOf("url" to session!!["url"] as String)
    }

    @GetMapping("/billing/success")
    fun success(request: HttpServletRequest, @RequestParam("session_id") sessionId: String): ResponseEntity<Void> {
        require(sessionId.matches(Regex("cs_[A-Za-z0-9_]+"))) { "Bad session id" }
        val session = stripe.get()
            .uri("/checkout/sessions/{id}", sessionId)
            .header("Authorization", "Bearer ${props.stripe.secretKey}")
            .retrieve()
            .body(Map::class.java)
        val paid = session?.get("payment_status") == "paid"
        val mine = session?.get("client_reference_id") == request.visitorId().toString()
        if (paid && mine) visitors.markPremium(request.visitorId())
        val target = if (paid && mine) "/?premium=1" else "/?premium=0"
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build()
    }
}
