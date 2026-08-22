package com.aryanyeole.wmp.common.repository;

import java.util.Optional;

import com.aryanyeole.wmp.common.domain.Department;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepartmentRepository extends JpaRepository<Department, Long> {

    Optional<Department> findByName(String name);
}
