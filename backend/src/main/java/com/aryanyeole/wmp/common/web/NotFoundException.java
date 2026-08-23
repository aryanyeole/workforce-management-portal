package com.aryanyeole.wmp.common.web;

/**
 * A 404. Also the mechanism behind leak-safe ownership scoping (ADR 0001):
 * a row that exists but is outside the caller's VisibilityScope is fetched
 * with a query predicate that simply doesn't match it, so "doesn't exist"
 * and "exists but isn't yours to see" are indistinguishable to the caller.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
