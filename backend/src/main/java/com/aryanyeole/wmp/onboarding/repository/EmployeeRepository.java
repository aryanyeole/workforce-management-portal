package com.aryanyeole.wmp.onboarding.repository;

import java.util.Optional;

import com.aryanyeole.wmp.onboarding.domain.Employee;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    Optional<Employee> findByEmail(String email);
}
