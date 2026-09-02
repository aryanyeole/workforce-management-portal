package com.aryanyeole.wmp.common.logging;

import org.slf4j.MDC;

/**
 * One MDC key, shared by every source of a correlation ID in this app —
 * {@link CorrelationIdFilter} for inbound HTTP requests and
 * {@code PayrollAccrualJob} for its own scheduled runs. Using a single key
 * rather than a request-scoped name and a separate job-scoped name means
 * every structured log line that carries any kind of run identifier shows
 * up under the same JSON field, so a log query never has to know in advance
 * which kind of caller produced the line it's looking for.
 */
public final class CorrelationId {

    public static final String MDC_KEY = "correlationId";
    public static final String RESPONSE_HEADER = "X-Correlation-Id";

    private CorrelationId() {
    }

    /** The current thread's correlation ID, or null if none is set. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
