package com.aryanyeole.wmp.common.web;

/**
 * A 401 that should reach the client as an RFC 7807 ProblemDetail.
 *
 * Deliberately generic: callers control the message, but the type carries no
 * information about which specific check failed. This lets AuthService use
 * one exception for both "unknown email" and "wrong password" so login
 * cannot be used to enumerate valid accounts.
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
