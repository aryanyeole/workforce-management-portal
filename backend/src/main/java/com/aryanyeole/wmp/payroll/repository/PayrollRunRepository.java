package com.aryanyeole.wmp.payroll.repository;

import com.aryanyeole.wmp.payroll.domain.PayrollRun;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayrollRunRepository extends JpaRepository<PayrollRun, Long> {
}
