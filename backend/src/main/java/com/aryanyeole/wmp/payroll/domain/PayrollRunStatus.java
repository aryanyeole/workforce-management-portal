package com.aryanyeole.wmp.payroll.domain;

/** Mirrors the CHECK constraint on payroll_runs.status. */
public enum PayrollRunStatus {
    DRAFT,
    SUBMITTED,
    APPROVED,
    REJECTED,
    PAID
}
