package com.aryanyeole.wmp.common.security;

import com.aryanyeole.wmp.auth.domain.RoleCode;

/**
 * The authenticated caller, reconstructed from the JWT on every request.
 *
 * Carries both identity spaces deliberately: userAccountId addresses the login
 * identity (expense_reports.approver_id, payroll_runs.submitted_by), while
 * employeeId addresses the HR record (expense_reports.employee_id). Holding
 * both avoids a translation query on every ownership-scoped request.
 *
 * employeeId is null for non-human principals such as the SYSTEM account.
 */
public record AuthPrincipal(
        Long userAccountId,
        Long employeeId,
        String email,
        RoleCode role) {
}