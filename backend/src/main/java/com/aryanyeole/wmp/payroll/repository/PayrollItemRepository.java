package com.aryanyeole.wmp.payroll.repository;

import com.aryanyeole.wmp.payroll.domain.PayrollItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayrollItemRepository extends JpaRepository<PayrollItem, Long> {

    List<PayrollItem> findByPayrollRunId(Long payrollRunId);
}
