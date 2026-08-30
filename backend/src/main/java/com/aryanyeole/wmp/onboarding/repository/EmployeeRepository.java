package com.aryanyeole.wmp.onboarding.repository;

import java.util.List;
import java.util.Optional;

import com.aryanyeole.wmp.onboarding.domain.Employee;
import com.aryanyeole.wmp.onboarding.domain.EmploymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface EmployeeRepository extends JpaRepository<Employee, Long>, JpaSpecificationExecutor<Employee> {

    Optional<Employee> findByEmail(String email);

    /** PayrollAccrualJob's population — accrual only makes sense for employees currently working. */
    List<Employee> findByEmploymentStatusAndDeletedAtIsNull(EmploymentStatus employmentStatus);
}
