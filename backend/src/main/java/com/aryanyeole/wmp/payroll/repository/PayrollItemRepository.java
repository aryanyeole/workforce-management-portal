package com.aryanyeole.wmp.payroll.repository;

import com.aryanyeole.wmp.payroll.domain.PayrollItem;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PayrollItemRepository extends JpaRepository<PayrollItem, Long> {

    List<PayrollItem> findByPayrollRunId(Long payrollRunId);

    boolean existsByPayrollRunId(Long payrollRunId);

    boolean existsByPayrollRunIdAndEmployeeId(Long payrollRunId, Long employeeId);

    /** Payslips: an employee's items across every run, newest run first — see PayrollReadService.payslips. */
    Page<PayrollItem> findByEmployeeIdOrderByPayrollRun_PeriodStartDesc(Long employeeId, Pageable pageable);

    /**
     * GET /payroll/summary: aggregated in SQL (SUM/COUNT + GROUP BY), never
     * by loading every item into memory and summing in Java.
     */
    @Query("""
            SELECT r.periodStart AS periodStart, r.periodEnd AS periodEnd, r.status AS status,
                   SUM(i.grossPay) AS totalGross, SUM(i.tax) AS totalTax,
                   SUM(i.deductions) AS totalDeductions, SUM(i.netPay) AS totalNet,
                   COUNT(i) AS itemCount
            FROM PayrollItem i JOIN i.payrollRun r
            GROUP BY r.id, r.periodStart, r.periodEnd, r.status
            ORDER BY r.periodStart DESC
            """)
    Page<PayrollPeriodSummary> summarizeByPeriod(Pageable pageable);
}
