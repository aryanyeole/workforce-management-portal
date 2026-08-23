package com.aryanyeole.wmp.onboarding.api;

import java.time.LocalDate;

import com.aryanyeole.wmp.onboarding.domain.OnboardingTaskStatus;

/**
 * PATCH semantics: every field optional, null means "leave unchanged".
 * An EMPLOYEE caller may only supply status — OnboardingTaskService
 * rejects any other non-null field for that role with a 403.
 */
public record UpdateTaskRequest(
        String title,
        String description,
        LocalDate dueDate,
        OnboardingTaskStatus status) {
}
