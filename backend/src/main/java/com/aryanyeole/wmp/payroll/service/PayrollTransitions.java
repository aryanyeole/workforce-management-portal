package com.aryanyeole.wmp.payroll.service;

import java.util.Map;
import java.util.Set;

import com.aryanyeole.wmp.common.web.ConflictException;
import com.aryanyeole.wmp.payroll.domain.PayrollRunStatus;

/**
 * The payroll run state machine, in one place per CLAUDE.md convention
 * (mirrors ExpenseTransitions/EmployeeTransitions): every lifecycle rule
 * for PayrollRun.status lives here. DRAFT -> SUBMITTED -> APPROVED|REJECTED
 * -> PAID; REJECTED and PAID are terminal. Nothing in Phase 5's 30
 * endpoints actually drives APPROVED -> PAID (no such endpoint exists yet),
 * but the rule is declared here now so the state machine is complete and
 * correct as soon as something does.
 */
final class PayrollTransitions {

    private static final Map<PayrollRunStatus, Set<PayrollRunStatus>> ALLOWED = Map.of(
            PayrollRunStatus.DRAFT, Set.of(PayrollRunStatus.SUBMITTED),
            PayrollRunStatus.SUBMITTED, Set.of(PayrollRunStatus.APPROVED, PayrollRunStatus.REJECTED),
            PayrollRunStatus.APPROVED, Set.of(PayrollRunStatus.PAID),
            PayrollRunStatus.REJECTED, Set.of(),
            PayrollRunStatus.PAID, Set.of());

    private PayrollTransitions() {
    }

    /** Items may only be added while the run is still a draft. */
    static void requireDraft(PayrollRunStatus current) {
        if (current != PayrollRunStatus.DRAFT) {
            throw new ConflictException(
                    "Payroll run must be DRAFT to add items; current status is %s".formatted(current));
        }
    }

    /** submit/approve/reject each attempt one transition; illegal ones name both states in the 409. */
    static void requireTransition(PayrollRunStatus current, PayrollRunStatus target) {
        if (!ALLOWED.getOrDefault(current, Set.of()).contains(target)) {
            throw new ConflictException(
                    "Cannot transition payroll run from %s to %s".formatted(current, target));
        }
    }
}
