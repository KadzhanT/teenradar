package com.teenradar.ai

import com.teenradar.AppProperties
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/** The model is out of quota / overloaded. Shown to the user as "busy, try again". */
class AiBusyException : RuntimeException("AI is busy")

class Image(val mimeType: String, val bytes: ByteArray)

/** Thin wrapper over the Gemini REST API (generateContent). */
@Component
class GeminiClient(
    builder: RestClient.Builder,
    private val props: AppProperties,
    private val json: JsonMapper,
) {
    private val http = builder
        .baseUrl("https://generativelanguage.googleapis.com/v1beta")
        .requestFactory(SimpleClientHttpRequestFactory().apply { setConnectTimeout(5_000); setReadTimeout(90_000) })
        .build()

    // ponytail: in-memory daily cap, resets on restart; fine for one instance, move to the DB if we scale out
    private val callsToday = AtomicInteger()

    @Volatile private var capDay = LocalDate.now(ZoneOffset.UTC)

    /** Structured JSON answer that matches [schema], parsed into [type]. */
    fun <T> generateJson(system: String, text: String, image: Image?, schema: Map<String, Any>, type: Class<T>): T {
        val parts = buildList {
            add(mapOf("text" to text))
            if (image != null) {
                add(mapOf("inline_data" to mapOf("mime_type" to image.mimeType, "data" to Base64.getEncoder().encodeToString(image.bytes))))
            }
        }
        val body = mapOf(
            "systemInstruction" to mapOf("parts" to listOf(mapOf("text" to system))),
            "contents" to listOf(mapOf("role" to "user", "parts" to parts)),
            "generationConfig" to mapOf("responseMimeType" to "application/json", "responseSchema" to schema),
        )
        return json.readValue(call(body), type)
    }

    /** Free-text answer grounded in live Google Search results (structured output is not combined with search). */
    fun generateWithSearch(prompt: String): String {
        val body = mapOf(
            "contents" to listOf(mapOf("role" to "user", "parts" to listOf(mapOf("text" to prompt)))),
            "tools" to listOf(mapOf("google_search" to emptyMap<String, Any>())),
        )
        return call(body)
    }

    private fun call(body: Map<String, Any>): String {
        check(props.gemini.apiKey.isNotBlank()) { "GEMINI_API_KEY is not set" }
        spendQuota()
        val response = http.post()
            .uri("/models/{model}:generateContent", props.gemini.model)
            .header("x-goog-api-key", props.gemini.apiKey)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .onStatus({ it.value() == HttpStatus.TOO_MANY_REQUESTS.value() || it.value() == 503 }) { _, _ -> throw AiBusyException() }
            .body(GeminiResponse::class.java)
        return response?.candidates?.firstOrNull()?.content?.parts?.mapNotNull { it.text }?.joinToString("")
            ?.takeIf { it.isNotBlank() }
            ?: throw AiBusyException()
    }

    private fun spendQuota() {
        val today = LocalDate.now(ZoneOffset.UTC)
        if (today != capDay) {
            capDay = today
            callsToday.set(0)
        }
        if (callsToday.incrementAndGet() > props.gemini.dailyCap) throw AiBusyException()
    }

    data class GeminiResponse(val candidates: List<Candidate>? = null)

    data class Candidate(val content: Content? = null)

    data class Content(val parts: List<Part>? = null)

    data class Part(val text: String? = null)
}
