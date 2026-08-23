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
 * Returns {@code null} for Unrestricted, which is the documented Spring Data
 * JPA convention for "no restriction" — {@link Specification#where} and
 * {@link Specification#and} both treat a null component as a pass-through.
 */
public final class VisibilityScopeSpecifications {

    private VisibilityScopeSpecifications() {
    }

    public static <T> Specification<T> forEmployeePath(
            VisibilityScope scope, Function<Root<T>, Path<Employee>> employeePath) {
        return switch (scope) {
            case VisibilityScope.Unrestricted ignored -> null;
            case VisibilityScope.Self(Long employeeId) -> (root, query, cb) ->
                    cb.equal(employeePath.apply(root).get("id"), employeeId);
            case VisibilityScope.SelfAndManagedTeam(Long employeeId) -> (root, query, cb) -> cb.or(
                    cb.equal(employeePath.apply(root).get("id"), employeeId),
                    cb.equal(employeePath.apply(root).get("manager").get("id"), employeeId));
        };
    }
}
