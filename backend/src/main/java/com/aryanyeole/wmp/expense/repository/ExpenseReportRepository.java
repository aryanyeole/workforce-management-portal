package com.aryanyeole.wmp.expense.repository;

import com.aryanyeole.wmp.expense.domain.ExpenseReport;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExpenseReportRepository extends JpaRepository<ExpenseReport, Long> {
}
