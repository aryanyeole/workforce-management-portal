package com.aryanyeole.wmp.onboarding.api;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;

public record CreateTaskRequest(
        @NotBlank String title,
        String description,
        LocalDate dueDate) {
}
