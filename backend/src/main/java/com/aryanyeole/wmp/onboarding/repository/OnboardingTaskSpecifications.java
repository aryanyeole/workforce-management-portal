package com.aryanyeole.wmp.onboarding.repository;

import org.springframework.data.jpa.domain.Specification;

import com.aryanyeole.wmp.common.repository.VisibilityScopeSpecifications;
import com.aryanyeole.wmp.common.security.VisibilityScope;
import com.aryanyeole.wmp.onboarding.domain.OnboardingTask;

/**
 * PATCH /onboarding/tasks/{taskId} isn't nested under an employee path, so
 * visibleTo + hasId together resolve the task's owner and decide
 * visibility in one query — reusing the exact same mechanism as every
 * other domain, not an ad-hoc "look up the employee first" check.
 */
public final class OnboardingTaskSpecifications {

    private OnboardingTaskSpecifications() {
    }

    public static Specification<OnboardingTask> visibleTo(VisibilityScope scope) {
        return VisibilityScopeSpecifications.forEmployeePath(scope, root -> root.get("employee"));
    }

    public static Specification<OnboardingTask> hasId(Long id) {
        return (root, query, cb) -> cb.equal(root.get("id"), id);
    }
}
