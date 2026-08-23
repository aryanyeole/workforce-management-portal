package com.aryanyeole.wmp.payroll.api;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;

public record CreatePayrollRunRequest(
        @NotNull LocalDate periodStart,
        @NotNull LocalDate periodEnd) {
}
