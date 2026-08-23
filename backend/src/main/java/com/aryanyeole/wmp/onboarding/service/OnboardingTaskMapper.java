package com.aryanyeole.wmp.onboarding.service;

import com.aryanyeole.wmp.onboarding.api.TaskResponse;
import com.aryanyeole.wmp.onboarding.domain.OnboardingTask;

final class OnboardingTaskMapper {

    private OnboardingTaskMapper() {
    }

    static TaskResponse toResponse(OnboardingTask task) {
        return new TaskResponse(
                task.getId(),
                task.getEmployee().getId(),
                task.getTitle(),
                task.getDescription(),
                task.getStatus(),
                task.getDueDate(),
                task.getCompletedAt(),
                task.getCreatedAt(),
                task.getUpdatedAt());
    }
}
