package com.aryanyeole.wmp.common.security;

import java.io.IOException;

import org.springframework.http.MediaType;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Writes RFC 7807 problem responses from inside the filter chain.
 *
 * Filters run outside the DispatcherServlet, so @RestControllerAdvice never
 * sees these failures. The JSON is hand-built rather than serialized to avoid
 * depending on which ObjectMapper is on the classpath.
 */
final class SecurityResponses {

    private SecurityResponses() {
    }

    static void write(HttpServletResponse response, int status, String title, String detail)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("""
                {"type":"about:blank","title":"%s","status":%d,"detail":"%s"}"""
                .formatted(escape(title), status, escape(detail)));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}