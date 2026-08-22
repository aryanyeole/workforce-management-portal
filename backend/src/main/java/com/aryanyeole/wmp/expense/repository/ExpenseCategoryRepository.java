package com.aryanyeole.wmp.expense.repository;

import java.util.Optional;

import com.aryanyeole.wmp.expense.domain.ExpenseCategory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExpenseCategoryRepository extends JpaRepository<ExpenseCategory, Long> {

    Optional<ExpenseCategory> findByName(String name);
}
