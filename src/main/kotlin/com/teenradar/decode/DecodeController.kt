package com.teenradar.decode

import com.teenradar.ai.AiBusyException
import com.teenradar.ai.Image
import com.teenradar.web.visitorId
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.multipart.MultipartFile

private val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/webp")

@RestController
class DecodeController(private val service: DecodeService) {
    @PostMapping("/api/decode")
    fun decode(
        request: HttpServletRequest,
        @RequestParam(required = false) text: String?,
        @RequestParam(required = false) image: MultipartFile?,
    ): DecodeResponse {
        val img = image?.takeIf { !it.isEmpty }?.let {
            require(it.contentType in IMAGE_TYPES) { "Upload a PNG, JPEG or WEBP screenshot." }
            Image(it.contentType!!, it.bytes)
        }
        return service.decode(request.visitorId(), text, img)
    }
}

data class ApiError(val error: String, val paywall: Boolean = false)

@RestControllerAdvice(annotations = [RestController::class])
class ApiErrors {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler
    fun paywall(e: PaywallException) = ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(ApiError(e.reason, paywall = true))

    @ExceptionHandler
    fun badInput(e: IllegalArgumentException) = ResponseEntity.badRequest().body(ApiError(e.message ?: "Bad request"))

    @ExceptionHandler
    fun badJson(e: HttpMessageNotReadableException) = ResponseEntity.badRequest().body(ApiError("Malformed request."))

    @ExceptionHandler
    fun tooBig(e: MaxUploadSizeExceededException) = ResponseEntity.badRequest().body(ApiError("Screenshot is too big (max 4 MB)."))

    @ExceptionHandler
    fun busy(e: AiBusyException) =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiError("Our AI is busy right now. Try again in a minute."))

    @ExceptionHandler
    fun unexpected(e: Exception): ResponseEntity<ApiError> {
        log.error("Unhandled API error", e)
        return ResponseEntity.internalServerError().body(ApiError("Something went wrong. Try again."))
    }
}
