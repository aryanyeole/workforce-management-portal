package com.aryanyeole.wmp.expense.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.aryanyeole.wmp.expense.domain.ExpenseReport;

public interface ExpenseReportRepository
        extends JpaRepository<ExpenseReport, Long>, JpaSpecificationExecutor<ExpenseReport> {
}
