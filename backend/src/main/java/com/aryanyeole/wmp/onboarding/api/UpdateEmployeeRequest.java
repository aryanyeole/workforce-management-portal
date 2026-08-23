package com.aryanyeole.wmp.onboarding.api;

import java.time.LocalDate;

import com.aryanyeole.wmp.onboarding.domain.EmploymentStatus;

/**
 * PATCH semantics: every field optional, null means "leave unchanged".
 * departmentId/managerId use 0 as an explicit "clear this relationship"
 * sentinel would be ambiguous with "unchanged", so clearing either is not
 * supported by this endpoint — only reassignment to another id.
 * employmentStatus, if present, goes through EmployeeTransitions before
 * being applied.
 */
public record UpdateEmployeeRequest(
        String firstName,
        String lastName,
        LocalDate hireDate,
        Long departmentId,
        Long managerId,
        EmploymentStatus employmentStatus) {
}
