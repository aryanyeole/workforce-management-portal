package com.aryanyeole.wmp.payroll.repository;

import com.aryanyeole.wmp.payroll.domain.PayrollItem;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayrollItemRepository extends JpaRepository<PayrollItem, Long> {

    List<PayrollItem> findByPayrollRunId(Long payrollRunId);

    boolean existsByPayrollRunId(Long payrollRunId);

    boolean existsByPayrollRunIdAndEmployeeId(Long payrollRunId, Long employeeId);

    /** Payslips: an employee's items across every run, newest run first — see PayrollService.payslips. */
    Page<PayrollItem> findByEmployeeIdOrderByPayrollRun_PeriodStartDesc(Long employeeId, Pageable pageable);
}
