package com.aryanyeole.wmp.payroll.api;

import java.time.LocalDate;

import com.aryanyeole.wmp.payroll.domain.PayrollRunStatus;

public record PeriodSummaryResponse(
        LocalDate periodStart,
        LocalDate periodEnd,
        PayrollRunStatus status,
        long totalGrossCents,
        long totalTaxCents,
        long totalDeductionsCents,
        long totalNetCents,
        long itemCount) {
}
