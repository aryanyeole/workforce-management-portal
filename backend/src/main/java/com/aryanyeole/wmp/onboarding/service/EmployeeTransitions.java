package com.aryanyeole.wmp.onboarding.service;

import java.util.Map;
import java.util.Set;

import com.aryanyeole.wmp.common.web.ConflictException;
import com.aryanyeole.wmp.onboarding.domain.EmploymentStatus;

/**
 * The employee lifecycle state machine, in one place per CLAUDE.md
 * convention (mirrors ExpenseTransitions): PENDING -> ACTIVE ->
 * ON_LEAVE|TERMINATED, with ON_LEAVE able to return to ACTIVE. TERMINATED
 * is terminal.
 */
final class EmployeeTransitions {

    private static final Map<EmploymentStatus, Set<EmploymentStatus>> ALLOWED = Map.of(
            EmploymentStatus.PENDING, Set.of(EmploymentStatus.ACTIVE),
            EmploymentStatus.ACTIVE, Set.of(EmploymentStatus.ON_LEAVE, EmploymentStatus.TERMINATED),
            EmploymentStatus.ON_LEAVE, Set.of(EmploymentStatus.ACTIVE, EmploymentStatus.TERMINATED),
            EmploymentStatus.TERMINATED, Set.of());

    private EmployeeTransitions() {
    }

    static void requireTransition(EmploymentStatus current, EmploymentStatus target) {
        if (current == target) {
            return;
        }
        if (!ALLOWED.getOrDefault(current, Set.of()).contains(target)) {
            throw new ConflictException(
                    "Cannot transition employee from %s to %s".formatted(current, target));
        }
    }
}
