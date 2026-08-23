package com.aryanyeole.wmp.payroll.service;

import com.aryanyeole.wmp.common.web.ConflictException;
import com.aryanyeole.wmp.payroll.domain.PayrollRunStatus;

/**
 * The payroll run state machine, in one place per CLAUDE.md convention
 * (mirrors ExpenseTransitions/EmployeeTransitions): every lifecycle rule
 * for PayrollRun.status lives here.
 */
final class PayrollTransitions {

    private PayrollTransitions() {
    }

    /** Items may only be added while the run is still a draft. */
    static void requireDraft(PayrollRunStatus current) {
        if (current != PayrollRunStatus.DRAFT) {
            throw new ConflictException(
                    "Payroll run must be DRAFT to add items; current status is %s".formatted(current));
        }
    }
}
