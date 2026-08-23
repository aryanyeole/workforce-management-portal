package com.aryanyeole.wmp.expense.service;

import java.util.Map;
import java.util.Set;

import com.aryanyeole.wmp.common.web.ConflictException;
import com.aryanyeole.wmp.expense.domain.ExpenseStatus;

/**
 * The expense report state machine, in one place per CLAUDE.md convention:
 * every lifecycle rule for ExpenseReport.status lives here, not scattered as
 * inline comparisons across ExpenseService's methods. DRAFT -> SUBMITTED ->
 * APPROVED|REJECTED; APPROVED and REJECTED are terminal.
 */
final class ExpenseTransitions {

    private static final Map<ExpenseStatus, Set<ExpenseStatus>> ALLOWED = Map.of(
            ExpenseStatus.DRAFT, Set.of(ExpenseStatus.SUBMITTED),
            ExpenseStatus.SUBMITTED, Set.of(ExpenseStatus.APPROVED, ExpenseStatus.REJECTED),
            ExpenseStatus.APPROVED, Set.of(),
            ExpenseStatus.REJECTED, Set.of());

    private ExpenseTransitions() {
    }

    /** PATCH and DELETE are only legal while the report is still a draft. */
    static void requireDraft(ExpenseStatus current) {
        if (current != ExpenseStatus.DRAFT) {
            throw new ConflictException(
                    "Expense report must be DRAFT to modify or delete; current status is %s".formatted(current));
        }
    }

    /** submit/approve/reject each attempt one transition; illegal ones name both states in the 409. */
    static void requireTransition(ExpenseStatus current, ExpenseStatus target) {
        if (!ALLOWED.getOrDefault(current, Set.of()).contains(target)) {
            throw new ConflictException(
                    "Cannot transition expense report from %s to %s".formatted(current, target));
        }
    }
}
