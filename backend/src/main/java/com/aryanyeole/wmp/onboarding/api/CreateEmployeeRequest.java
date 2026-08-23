package com.aryanyeole.wmp.onboarding.api;

import java.time.LocalDate;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * No employmentStatus field: every new employee starts PENDING
 * (EmployeeService.create), not caller-supplied. No user_account is
 * created here either — login provisioning is a separate concern, out of
 * scope for this phase.
 */
public record CreateEmployeeRequest(
        @NotBlank String firstName,
        @NotBlank String lastName,
        @NotBlank @Email String email,
        @NotNull LocalDate hireDate,
        Long departmentId,
        Long managerId) {
}
