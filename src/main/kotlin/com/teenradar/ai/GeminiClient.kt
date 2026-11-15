package com.teenradar.ai

import com.teenradar.AppProperties
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.util.Base64

/** The model is out of quota / overloaded. Shown to the user as "busy, try again". */
class AiBusyException : RuntimeException("AI is busy")

/** The model answered with no text (usually its safety filter). Callers fall back to the curated code list. */
class AiBlockedException : RuntimeException("AI returned no answer")

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
        .requestFactory(SimpleClientHttpRequestFactory().apply { setConnectTimeout(5_000); setReadTimeout(25_000) })
        .build()

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
            "generationConfig" to mapOf(
                "responseMimeType" to "application/json",
                "responseSchema" to schema,
                // Gemini 3 "thinks" a lot by default; decoding a phrase does not need it and the parent is waiting
                "thinkingConfig" to mapOf("thinkingLevel" to "low"),
            ),
        )
        return json.readValue(call(body), type)
    }

    /** Free-text answer, optionally grounded in live Google Search results (structured output is not combined with search). */
    fun generateText(prompt: String, search: Boolean): String {
        val body = buildMap {
            put("contents", listOf(mapOf("role" to "user", "parts" to listOf(mapOf("text" to prompt)))))
            if (search) put("tools", listOf(mapOf("google_search" to emptyMap<String, Any>())))
        }
        return call(body)
    }

    /** Tries the main model, then each fallback when one is overloaded (503) or rate-limited (429). */
    private fun call(body: Map<String, Any>): String {
        check(props.gemini.apiKey.isNotBlank()) { "GEMINI_API_KEY is not set" }
        val models = listOf(props.gemini.model) + props.gemini.fallbackModels.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        var busy: AiBusyException? = null
        for (model in models) {
            try {
                return callModel(model, body)
            } catch (e: AiBusyException) {
                log.warn("Gemini model {} is busy, trying the next one", model)
                busy = e
            } catch (e: ResourceAccessException) {
                log.warn("Gemini model {} timed out, trying the next one", model)
                busy = AiBusyException()
            }
        }
        throw busy!!
    }

    private fun callModel(model: String, body: Map<String, Any>): String {
        val response = http.post()
            .uri("/models/{model}:generateContent", model)
            .header("x-goog-api-key", props.gemini.apiKey)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body + ("safetySettings" to SAFETY))
            .retrieve()
            .onStatus({ it.isError }) { _, res ->
                val detail = res.body.readNBytes(2_000).toString(Charsets.UTF_8)
                log.warn("Gemini HTTP {}: {}", res.statusCode.value(), detail)
                if (res.statusCode.value() in setOf(429, 503)) throw AiBusyException()
                throw IllegalStateException("Gemini HTTP ${res.statusCode.value()}")
            }
            .body(GeminiResponse::class.java)
        val candidate = response?.candidates?.firstOrNull()
        val text = candidate?.content?.parts?.mapNotNull { it.text }?.joinToString("")
        if (text.isNullOrBlank()) {
            log.warn("Gemini returned no text: finishReason={}, promptFeedback={}", candidate?.finishReason, response?.promptFeedback)
            throw AiBlockedException()
        }
        return text
    }

    data class GeminiResponse(val candidates: List<Candidate>? = null, val promptFeedback: Map<String, Any?>? = null)

    data class Candidate(val content: Content? = null, val finishReason: String? = null)

    data class Content(val parts: List<Part>? = null)

    data class Part(val text: String? = null)

    companion object {
        private val log = LoggerFactory.getLogger(GeminiClient::class.java)

        // Explaining drug, sex and self-harm codes to parents IS the product, so default filters block legit answers.
        // Only clearly high-harm output is still blocked (and Google's non-configurable child-safety filters always apply).
        private val SAFETY = listOf(
            "HARM_CATEGORY_DANGEROUS_CONTENT",
            "HARM_CATEGORY_SEXUALLY_EXPLICIT",
            "HARM_CATEGORY_HARASSMENT",
            "HARM_CATEGORY_HATE_SPEECH",
        ).map { mapOf("category" to it, "threshold" to "BLOCK_ONLY_HIGH") }
    }
}
