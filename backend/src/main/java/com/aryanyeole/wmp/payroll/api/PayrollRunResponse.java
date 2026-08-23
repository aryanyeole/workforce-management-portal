package com.aryanyeole.wmp.payroll.api;

import java.time.Instant;
import java.time.LocalDate;

import com.aryanyeole.wmp.payroll.domain.PayrollRunStatus;

public record PayrollRunResponse(
        Long id,
        LocalDate periodStart,
        LocalDate periodEnd,
        PayrollRunStatus status,
        Long submittedById,
        Instant submittedAt,
        Long approvedById,
        Instant approvedAt,
        Instant createdAt,
        Instant updatedAt) {
}
