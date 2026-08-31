package com.aryanyeole.wmp.onboarding.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.aryanyeole.wmp.onboarding.domain.Employee;
import com.aryanyeole.wmp.onboarding.domain.EmploymentStatus;

public interface EmployeeRepository extends JpaRepository<Employee, Long>, JpaSpecificationExecutor<Employee> {

    Optional<Employee> findByEmail(String email);

    /** PayrollAccrualJob's population — accrual only makes sense for employees currently working. */
    List<Employee> findByEmploymentStatusAndDeletedAtIsNull(EmploymentStatus employmentStatus);

    /**
     * Compare-and-swap on employmentStatus — mirrors
     * ExpenseReportRepository.compareAndSetStatus and
     * PayrollRunRepository.compareAndSetStatus (Phase 10 Task 0/0b): only
     * writes if the row's employmentStatus is still exactly {@code expected}
     * at UPDATE time, returning the row count as the "did I win" signal.
     * Closes the same read-check-then-write race in
     * EmployeeService.update's employmentStatus branch.
     *
     * This is now the third copy of the identical pattern (once per
     * entity/repository) — see EmployeeService.compareAndSetStatusOrConflict's
     * javadoc for where it should probably be consolidated instead.
     */
    @Modifying
    @Query("UPDATE Employee e SET e.employmentStatus = :next WHERE e.id = :id AND e.employmentStatus = :expected")
    int compareAndSetStatus(@Param("id") Long id, @Param("expected") EmploymentStatus expected, @Param("next") EmploymentStatus next);
}
