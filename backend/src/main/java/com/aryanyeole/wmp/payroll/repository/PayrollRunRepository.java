package com.aryanyeole.wmp.payroll.repository;

import java.time.LocalDate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.aryanyeole.wmp.payroll.domain.PayrollRun;
import com.aryanyeole.wmp.payroll.domain.PayrollRunStatus;

public interface PayrollRunRepository extends JpaRepository<PayrollRun, Long> {

    boolean existsByPeriodStartAndPeriodEnd(LocalDate periodStart, LocalDate periodEnd);

    /**
     * Compare-and-swap on status — see ExpenseReportRepository.compareAndSetStatus's
     * javadoc for the race this closes and why it's shaped this way
     * (Phase 10 Task 0).
     */
    @Modifying
    @Query("UPDATE PayrollRun r SET r.status = :next WHERE r.id = :id AND r.status = :expected")
    int compareAndSetStatus(@Param("id") Long id, @Param("expected") PayrollRunStatus expected, @Param("next") PayrollRunStatus next);
}
