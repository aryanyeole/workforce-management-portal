package com.aryanyeole.wmp.common.logging;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * First filter in the chain (see SecurityConfig — registered before
 * JwtAuthenticationFilter, which itself runs before RouteAuthorizationFilter)
 * so every response this API produces, success or denial, carries a
 * correlation ID: a 401 from JwtAuthenticationFilter, a 403 from
 * RouteAuthorizationFilter, and anything the DispatcherServlet or
 * GlobalExceptionHandler produces afterward are all on the same thread this
 * filter set MDC on before any of them ran.
 *
 * Trust: an inbound X-Correlation-Id or X-Request-Id (checked in that order)
 * is reused only if it matches SAFE_ID — otherwise a fresh one is generated.
 * Reusing a caller-supplied ID at all is a deliberate choice, not an
 * oversight: it lets a caller's own trace ID (this project's own frontend,
 * today) thread through into backend logs, which a filter that always
 * overwrote would make impossible. The trade-off is that an inbound ID is
 * attacker-controlled input that lands verbatim in a structured JSON log
 * line — unvalidated, that's a log-injection path (control characters,
 * absurd length, anything a downstream log consumer might misparse).
 * SAFE_ID closes that door by construction (bounded length, no punctuation
 * beyond a hyphen) while still accepting a UUID or any similarly-shaped
 * opaque token; anything else is treated as a confused client, not an
 * attack, and silently replaced rather than rejected — the request itself
 * is otherwise perfectly valid.
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String inbound = firstNonBlank(
                request.getHeader("X-Correlation-Id"),
                request.getHeader("X-Request-Id"));
        String id = (inbound != null && SAFE_ID.matcher(inbound).matches())
                ? inbound
                : UUID.randomUUID().toString();

        // Set on the response before the chain runs, not after: the header
        // must survive on responses this filter never sees the body of
        // (RouteAuthorizationFilter writing a 403 directly, or the
        // DispatcherServlet writing a 200 or a ProblemDetail 500 further
        // down) — setting it here, before the response is committed, means
        // it's already there by the time any of them write anything.
        response.setHeader(CorrelationId.RESPONSE_HEADER, id);
        MDC.put(CorrelationId.MDC_KEY, id);
        try {
            chain.doFilter(request, response);
        } finally {
            // Tomcat's request-handling threads are pooled and reused across
            // unrelated requests — leaving this set would leak one request's
            // ID onto the next request that happens to land on the same
            // thread.
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }
}
