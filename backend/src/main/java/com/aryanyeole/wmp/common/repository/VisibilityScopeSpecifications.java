package com.aryanyeole.wmp.common.repository;

import java.util.function.Function;

import org.springframework.data.jpa.domain.Specification;

import com.aryanyeole.wmp.common.security.VisibilityScope;
import com.aryanyeole.wmp.onboarding.domain.Employee;

import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;

/**
 * Turns a VisibilityScope into a JPA Specification, given how to navigate
 * from an entity's root to the Employee it belongs to. The scope itself
 * carries no knowledge of any specific entity's schema — each domain
 * repository supplies that one path function, so the same three scope
 * shapes serve expense, payroll, and onboarding without re-deciding the
 * role-to-scope mapping per domain (see ADR 0001).
 *
 * Unrestricted returns an always-true predicate rather than null: despite
 * some Spring Data JPA docs suggesting {@code Specification.and(null)} is a
 * no-op pass-through, this version's default implementation rejects a null
 * argument outright (InvalidDataAccessApiUsageException: "Other
 * specification must not be null"), so this stays explicit instead of
 * relying on that.
 */
public final class VisibilityScopeSpecifications {

    private VisibilityScopeSpecifications() {
    }

    public static <T> Specification<T> forEmployeePath(
            VisibilityScope scope, Function<Root<T>, Path<Employee>> employeePath) {
        return switch (scope) {
            case VisibilityScope.Unrestricted ignored -> (root, query, cb) -> cb.conjunction();
            case VisibilityScope.Self(Long employeeId) -> (root, query, cb) ->
                    cb.equal(employeePath.apply(root).get("id"), employeeId);
            case VisibilityScope.SelfAndManagedTeam(Long employeeId) -> (root, query, cb) -> cb.or(
                    cb.equal(employeePath.apply(root).get("id"), employeeId),
                    cb.equal(employeePath.apply(root).get("manager").get("id"), employeeId));
        };
    }
}
