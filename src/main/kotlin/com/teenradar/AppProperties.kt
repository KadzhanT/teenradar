package com.teenradar

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("app")
data class AppProperties(
    val baseUrl: String,
    val adminToken: String,
    val freeDecodesPerDay: Int,
    val gemini: Gemini,
    val stripe: Stripe,
    val resend: Resend,
) {
    data class Gemini(
        val apiKey: String,
        val model: String,
        /** Comma-separated, tried in order when the main model is overloaded. */
        val fallbackModels: String,
    )

    data class Stripe(val secretKey: String, val priceCents: Int)

    data class Resend(val apiKey: String, val from: String)
}
