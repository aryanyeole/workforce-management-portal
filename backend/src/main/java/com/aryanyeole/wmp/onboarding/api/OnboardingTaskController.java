package com.aryanyeole.wmp.onboarding.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aryanyeole.wmp.common.security.AuthPrincipal;
import com.aryanyeole.wmp.onboarding.service.OnboardingTaskService;

/**
 * Not nested under /employees: {taskId} alone identifies the task, and
 * OnboardingTaskService resolves its owning employee as part of the same
 * visibility query rather than as a separate lookup.
 */
@RestController
@RequestMapping("/api/v1/onboarding/tasks")
public class OnboardingTaskController {

    private final OnboardingTaskService onboardingTaskService;

    public OnboardingTaskController(OnboardingTaskService onboardingTaskService) {
        this.onboardingTaskService = onboardingTaskService;
    }

    @PatchMapping("/{taskId}")
    public TaskResponse update(@AuthenticationPrincipal AuthPrincipal principal,
                                @PathVariable Long taskId,
                                @RequestBody UpdateTaskRequest request) {
        return onboardingTaskService.update(principal, taskId, request);
    }
}
