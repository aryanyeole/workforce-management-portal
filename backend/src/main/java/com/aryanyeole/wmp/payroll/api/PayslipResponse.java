package com.aryanyeole.wmp.payroll.api;

import java.time.LocalDate;

import com.aryanyeole.wmp.payroll.domain.PayrollRunStatus;

public record PayslipResponse(
        Long id,
        Long payrollRunId,
        LocalDate periodStart,
        LocalDate periodEnd,
        PayrollRunStatus runStatus,
        Long employeeId,
        long grossCents,
        long taxCents,
        long deductionsCents,
        long netCents) {
}
