package com.aryanyeole.wmp.expense.service;

import com.aryanyeole.wmp.common.web.ConflictException;
import com.aryanyeole.wmp.expense.domain.ExpenseStatus;

/**
 * The expense report state machine, in one place per CLAUDE.md convention:
 * every lifecycle rule for ExpenseReport.status lives here, not scattered as
 * inline comparisons across ExpenseService's methods.
 */
final class ExpenseTransitions {

    private ExpenseTransitions() {
    }

    /** PATCH and DELETE are only legal while the report is still a draft. */
    static void requireDraft(ExpenseStatus current) {
        if (current != ExpenseStatus.DRAFT) {
            throw new ConflictException(
                    "Expense report must be DRAFT to modify or delete; current status is %s".formatted(current));
        }
    }
}
