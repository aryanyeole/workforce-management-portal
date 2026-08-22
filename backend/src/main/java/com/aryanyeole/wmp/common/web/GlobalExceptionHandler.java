package com.aryanyeole.wmp.common.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The single @RestControllerAdvice for the API (CLAUDE.md convention #3: all
 * errors are RFC 7807 ProblemDetail, no stack traces in responses).
 *
 * Extending ResponseEntityExceptionHandler already gives ProblemDetail bodies
 * for framework exceptions (bean validation failures, malformed JSON, ...);
 * this class only needs to map application-thrown exceptions.
 *
 * Authorization failures (401/403 from the security filter chain) are NOT
 * handled here — filters run before the DispatcherServlet, so this advice
 * never sees them. See SecurityResponses for that hand-built equivalent.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(UnauthorizedException.class)
    public ProblemDetail handleUnauthorized(UnauthorizedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }
}
