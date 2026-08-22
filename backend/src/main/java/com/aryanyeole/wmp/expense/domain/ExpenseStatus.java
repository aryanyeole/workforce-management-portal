package com.aryanyeole.wmp.expense.domain;

/** Mirrors the CHECK constraint on expense_reports.status. */
public enum ExpenseStatus {
    DRAFT,
    SUBMITTED,
    APPROVED,
    REJECTED
}
