package com.aryanyeole.wmp.common.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.aryanyeole.wmp.common.logging.CorrelationId;

/**
 * The single @RestControllerAdvice for the API (CLAUDE.md convention #3: all
 * errors are RFC 7807 ProblemDetail, no stack traces in responses).
 *
 * Extending ResponseEntityExceptionHandler already gives ProblemDetail bodies
 * for framework exceptions (bean validation failures, malformed JSON, ...);
 * this class only needs to map application-thrown exceptions. Those
 * inherited framework-produced bodies do NOT get the correlationId property
 * this class adds below — only the five handlers here construct their own
 * body, so only they can add to it without overriding methods this class
 * doesn't otherwise touch. The X-Correlation-Id response header still covers
 * every response regardless (set by CorrelationIdFilter before the
 * DispatcherServlet ever runs), so the ID is never actually missing from an
 * error response — only from the body of the ones this class doesn't author.
 *
 * Authorization failures (401/403 from the security filter chain) are NOT
 * handled here — filters run before the DispatcherServlet, so this advice
 * never sees them. See SecurityResponses for that hand-built equivalent,
 * which adds the same correlationId property to its own body independently.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(UnauthorizedException.class)
    public ProblemDetail handleUnauthorized(UnauthorizedException ex) {
        return problem(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail handleNotFound(NotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail handleConflict(ConflictException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    public ProblemDetail handleForbidden(ForbiddenException ex) {
        return problem(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(BadRequestException.class)
    public ProblemDetail handleBadRequest(BadRequestException ex) {
        return problem(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /**
     * correlationId echoed into the body, on top of the response header
     * every response already carries: a body pasted into a bug report or a
     * support ticket routinely travels without its headers, and this is the
     * one shared place all five of this class's own error bodies pass
     * through.
     */
    private ProblemDetail problem(HttpStatus status, String detail) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        String correlationId = CorrelationId.current();
        if (correlationId != null) {
            problemDetail.setProperty("correlationId", correlationId);
        }
        return problemDetail;
    }
}
