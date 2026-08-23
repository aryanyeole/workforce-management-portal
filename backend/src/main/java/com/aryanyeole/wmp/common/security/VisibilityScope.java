package com.aryanyeole.wmp.common.security;

/**
 * Which employee-owned rows a principal may see, independent of any
 * particular domain's schema. Read endpoints in expense (and, per ADR 0001,
 * payroll and onboarding once those phases exist) resolve one of these per
 * request via VisibilityScopeResolver, then turn it into a query predicate
 * via VisibilityScopeSpecifications — never an if-check on role in a
 * service method.
 *
 * Mutation endpoints (create/PATCH/DELETE/submit) do not use the resolver at
 * all; they build {@code new Self(principal.employeeId())} directly, since
 * editing your own record is always self-scoped regardless of role.
 */
public sealed interface VisibilityScope {

    /** Only rows owned by this employee. */
    record Self(Long employeeId) implements VisibilityScope {
    }

    /** Rows owned by this employee, or by anyone whose manager is them. */
    record SelfAndManagedTeam(Long employeeId) implements VisibilityScope {
    }

    /** No ownership restriction — administrative roles. */
    record Unrestricted() implements VisibilityScope {
    }
}
