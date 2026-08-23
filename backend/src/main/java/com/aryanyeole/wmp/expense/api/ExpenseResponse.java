package com.aryanyeole.wmp.expense.api;

import java.time.Instant;

import com.aryanyeole.wmp.expense.domain.ExpenseStatus;

public record ExpenseResponse(
        Long id,
        Long employeeId,
        Long categoryId,
        String categoryName,
        long amountCents,
        String currency,
        String description,
        ExpenseStatus status,
        Instant submittedAt,
        Long approverId,
        Instant approvedAt,
        Instant createdAt,
        Instant updatedAt) {
}
