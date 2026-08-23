package com.aryanyeole.wmp.expense.service;

import com.aryanyeole.wmp.common.money.Money;
import com.aryanyeole.wmp.expense.api.ExpenseCategoryResponse;
import com.aryanyeole.wmp.expense.api.ExpenseResponse;
import com.aryanyeole.wmp.expense.domain.ExpenseCategory;
import com.aryanyeole.wmp.expense.domain.ExpenseReport;

/** Explicit entity-to-DTO mapping — no reflection-based auto-mapping (CLAUDE.md convention #2). */
final class ExpenseMapper {

    private ExpenseMapper() {
    }

    static ExpenseResponse toResponse(ExpenseReport report) {
        return new ExpenseResponse(
                report.getId(),
                report.getEmployee().getId(),
                report.getCategory().getId(),
                report.getCategory().getName(),
                Money.amountToCents(report.getAmount()),
                report.getCurrency(),
                report.getDescription(),
                report.getStatus(),
                report.getSubmittedAt(),
                report.getApprover() == null ? null : report.getApprover().getId(),
                report.getApprovedAt(),
                report.getCreatedAt(),
                report.getUpdatedAt());
    }

    static ExpenseCategoryResponse toCategoryResponse(ExpenseCategory category) {
        return new ExpenseCategoryResponse(category.getId(), category.getName(), category.getDescription());
    }
}
