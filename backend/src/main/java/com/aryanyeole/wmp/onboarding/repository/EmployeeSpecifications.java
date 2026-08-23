package com.aryanyeole.wmp.onboarding.repository;

import org.springframework.data.jpa.domain.Specification;

import com.aryanyeole.wmp.common.repository.VisibilityScopeSpecifications;
import com.aryanyeole.wmp.common.security.VisibilityScope;
import com.aryanyeole.wmp.onboarding.domain.Employee;

/**
 * Composable query predicates for Employee.
 *
 * Unlike ExpenseReport/OnboardingTask (which point AT an employee via an
 * "employee" field), Employee IS the employee — there is no nested field
 * to navigate to. VisibilityScopeSpecifications.forEmployeePath already
 * handles this without modification: Root<Employee> is itself a
 * Path<Employee> (Root extends Path), so the identity function `root ->
 * root` is a valid "how to reach this entity's Employee" for the one
 * entity that IS the Employee. No change to the shared mechanism needed.
 */
public final class EmployeeSpecifications {

    private EmployeeSpecifications() {
    }

    public static Specification<Employee> visibleTo(VisibilityScope scope) {
        return VisibilityScopeSpecifications.forEmployeePath(scope, root -> root);
    }

    public static Specification<Employee> hasId(Long id) {
        return (root, query, cb) -> cb.equal(root.get("id"), id);
    }

    /** Every normal query excludes soft-deleted rows — see V3 migration. */
    public static Specification<Employee> notDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }
}
