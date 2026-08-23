package com.aryanyeole.wmp.onboarding.repository;

import java.util.Optional;

import com.aryanyeole.wmp.onboarding.domain.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface EmployeeRepository extends JpaRepository<Employee, Long>, JpaSpecificationExecutor<Employee> {

    Optional<Employee> findByEmail(String email);
}
