package com.aryanyeole.wmp.payroll.api;

import java.time.Instant;

public record PayrollItemResponse(
        Long id,
        Long payrollRunId,
        Long employeeId,
        long grossCents,
        long taxCents,
        long deductionsCents,
        long netCents,
        Instant createdAt,
        Instant updatedAt) {
}
