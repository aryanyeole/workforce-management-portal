package com.aryanyeole.wmp.payroll.api;

import java.time.LocalDate;

/** Response DTO — controllers never return entities. See PayrollAccrualJob. */
public record AccrualRunResponse(LocalDate periodStart, LocalDate periodEnd, int employeesProcessed) {
}
