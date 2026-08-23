package com.aryanyeole.wmp.expense.repository;

import org.springframework.data.jpa.domain.Specification;

import com.aryanyeole.wmp.common.repository.VisibilityScopeSpecifications;
import com.aryanyeole.wmp.common.security.VisibilityScope;
import com.aryanyeole.wmp.expense.domain.ExpenseReport;
import com.aryanyeole.wmp.expense.domain.ExpenseStatus;

/** Composable query predicates for ExpenseReport — see ExpenseService for how these combine. */
public final class ExpenseSpecifications {

    private ExpenseSpecifications() {
    }

    public static Specification<ExpenseReport> visibleTo(VisibilityScope scope) {
        return VisibilityScopeSpecifications.forEmployeePath(scope, root -> root.get("employee"));
    }

    public static Specification<ExpenseReport> hasId(Long id) {
        return (root, query, cb) -> cb.equal(root.get("id"), id);
    }

    /** Every normal query excludes soft-deleted rows — see V2 migration. */
    public static Specification<ExpenseReport> notDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }

    public static Specification<ExpenseReport> hasStatus(ExpenseStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }
}
