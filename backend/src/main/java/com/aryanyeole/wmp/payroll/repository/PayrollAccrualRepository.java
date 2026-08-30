package com.aryanyeole.wmp.payroll.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aryanyeole.wmp.payroll.domain.PayrollAccrual;

/**
 * Read-only from the application's perspective — PayrollAccrualJob writes
 * via raw JDBC, not through this repository. Exists for verification (the
 * Phase 8 Task 4 regression test) and any future read endpoint.
 */
public interface PayrollAccrualRepository extends JpaRepository<PayrollAccrual, Long> {

    List<PayrollAccrual> findByPeriodStartAndPeriodEnd(LocalDate periodStart, LocalDate periodEnd);
}
