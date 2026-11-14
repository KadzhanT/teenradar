package com.teenradar.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration
import java.util.UUID

const val VISITOR_ATTR = "vid"

/** Gives every browser a stable anonymous id. Stored in the DB lazily, only when it decodes or pays. */
@Component
class VisitorFilter : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest) =
        request.requestURI.startsWith("/actuator")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val existing = request.cookies
            ?.firstOrNull { it.name == VISITOR_ATTR }
            ?.value
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val vid = existing ?: UUID.randomUUID().also {
            val cookie = ResponseCookie.from(VISITOR_ATTR, it.toString())
                .httpOnly(true)
                .secure(request.isSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofDays(365))
                .build()
            response.addHeader("Set-Cookie", cookie.toString())
        }
        request.setAttribute(VISITOR_ATTR, vid)
        chain.doFilter(request, response)
    }
}

fun HttpServletRequest.visitorId() = getAttribute(VISITOR_ATTR) as UUID
