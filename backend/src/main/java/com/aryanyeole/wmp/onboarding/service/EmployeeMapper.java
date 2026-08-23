package com.aryanyeole.wmp.onboarding.service;

import com.aryanyeole.wmp.common.domain.Department;
import com.aryanyeole.wmp.onboarding.api.EmployeeResponse;
import com.aryanyeole.wmp.onboarding.domain.Employee;

/** Explicit entity-to-DTO mapping — no reflection-based auto-mapping (CLAUDE.md convention #2). */
final class EmployeeMapper {

    private EmployeeMapper() {
    }

    static EmployeeResponse toResponse(Employee employee) {
        Department department = employee.getDepartment();
        return new EmployeeResponse(
                employee.getId(),
                department == null ? null : department.getId(),
                department == null ? null : department.getName(),
                employee.getManager() == null ? null : employee.getManager().getId(),
                employee.getFirstName(),
                employee.getLastName(),
                employee.getEmail(),
                employee.getHireDate(),
                employee.getEmploymentStatus(),
                employee.getCreatedAt(),
                employee.getUpdatedAt());
    }
}
