package com.aryanyeole.wmp.onboarding.api;

import java.time.Instant;
import java.time.LocalDate;

import com.aryanyeole.wmp.onboarding.domain.OnboardingTaskStatus;

public record TaskResponse(
        Long id,
        Long employeeId,
        String title,
        String description,
        OnboardingTaskStatus status,
        LocalDate dueDate,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt) {
}
