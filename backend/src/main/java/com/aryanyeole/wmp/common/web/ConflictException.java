package com.aryanyeole.wmp.common.web;

/**
 * A 409: the request is well-formed and the caller can see the resource, but
 * the resource's current state doesn't allow the requested action — an
 * illegal lifecycle transition, editing a non-draft report, self-approval,
 * and so on. Distinct from NotFoundException (404), which is about
 * existence/visibility, not state.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
