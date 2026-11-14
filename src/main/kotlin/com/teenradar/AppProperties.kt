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
    data class Gemini(val apiKey: String, val model: String, val dailyCap: Int)

    data class Stripe(val secretKey: String, val priceCents: Int)

    data class Resend(val apiKey: String, val from: String)
}
