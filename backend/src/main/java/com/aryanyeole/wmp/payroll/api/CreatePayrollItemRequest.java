package com.aryanyeole.wmp.payroll.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * No netCents field: PayrollService always computes it as
 * grossCents - taxCents - deductionsCents server-side, so the invariant
 * can never be violated by a caller-supplied value. tax/deductions
 * default to 0 when omitted.
 */
public record CreatePayrollItemRequest(
        @NotNull Long employeeId,
        @PositiveOrZero long grossCents,
        @PositiveOrZero Long taxCents,
        @PositiveOrZero Long deductionsCents) {

    public CreatePayrollItemRequest {
        if (taxCents == null) {
            taxCents = 0L;
        }
        if (deductionsCents == null) {
            deductionsCents = 0L;
        }
    }
}
