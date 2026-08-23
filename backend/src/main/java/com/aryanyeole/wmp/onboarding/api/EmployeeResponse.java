package com.aryanyeole.wmp.onboarding.api;

import java.time.Instant;
import java.time.LocalDate;

import com.aryanyeole.wmp.onboarding.domain.EmploymentStatus;

public record EmployeeResponse(
        Long id,
        Long departmentId,
        String departmentName,
        Long managerId,
        String firstName,
        String lastName,
        String email,
        LocalDate hireDate,
        EmploymentStatus employmentStatus,
        Instant createdAt,
        Instant updatedAt) {
}
