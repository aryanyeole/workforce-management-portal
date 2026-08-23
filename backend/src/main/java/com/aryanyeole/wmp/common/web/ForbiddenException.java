package com.aryanyeole.wmp.common.web;

/**
 * A 403 raised from inside a service, not the filter chain: the caller is
 * authenticated and the route allowed them in, and the target row is
 * within their VisibilityScope (otherwise it would be a 404) — but the
 * specific action or field they're attempting is not permitted for their
 * role. E.g. an EMPLOYEE may update the status of their own onboarding
 * task, but not its title/description/dueDate.
 *
 * Distinct from RouteAuthorizationFilter's 403 (a different mechanism —
 * hand-written JSON before the DispatcherServlet, for "may this role call
 * this route at all"). This is the finer-grained, row-already-visible
 * question, which by nature can only be answered once the request body
 * and the specific row are both in hand — i.e. in the service.
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
