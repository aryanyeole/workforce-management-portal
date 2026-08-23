package com.aryanyeole.wmp.payroll.repository;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.aryanyeole.wmp.payroll.domain.PayrollRunStatus;

/**
 * Spring Data interface projection for PayrollItemRepository.summarizeByPeriod
 * — getter names are matched against the query's column aliases. Amounts
 * stay BigDecimal here (this is still inside the repository layer); the
 * service maps through common.money.Money to cents for the API response,
 * same boundary as everywhere else.
 */
public interface PayrollPeriodSummary {

    LocalDate getPeriodStart();

    LocalDate getPeriodEnd();

    PayrollRunStatus getStatus();

    BigDecimal getTotalGross();

    BigDecimal getTotalTax();

    BigDecimal getTotalDeductions();

    BigDecimal getTotalNet();

    Long getItemCount();
}
