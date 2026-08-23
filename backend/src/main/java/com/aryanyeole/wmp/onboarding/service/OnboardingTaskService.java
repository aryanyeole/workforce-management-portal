package com.aryanyeole.wmp.onboarding.service;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aryanyeole.wmp.auth.domain.RoleCode;
import com.aryanyeole.wmp.common.security.AuthPrincipal;
import com.aryanyeole.wmp.common.security.VisibilityScopeResolver;
import com.aryanyeole.wmp.common.web.ForbiddenException;
import com.aryanyeole.wmp.common.web.NotFoundException;
import com.aryanyeole.wmp.onboarding.api.CreateTaskRequest;
import com.aryanyeole.wmp.onboarding.api.TaskResponse;
import com.aryanyeole.wmp.onboarding.api.UpdateTaskRequest;
import com.aryanyeole.wmp.onboarding.domain.Employee;
import com.aryanyeole.wmp.onboarding.domain.OnboardingTask;
import com.aryanyeole.wmp.onboarding.domain.OnboardingTaskStatus;
import com.aryanyeole.wmp.onboarding.repository.OnboardingTaskRepository;
import com.aryanyeole.wmp.onboarding.repository.OnboardingTaskSpecifications;

/**
 * No role checks here beyond the one field-level exception noted below —
 * route-level RBAC is RouteAuthorizationFilter's job (ADR 0001), and row
 * visibility is EmployeeService.requireVisible / VisibilityScope, reused
 * unchanged from expense and employees.
 */
@Service
public class OnboardingTaskService {

    private static final String NOT_FOUND_MESSAGE = "Onboarding task not found";

    private final OnboardingTaskRepository onboardingTaskRepository;
    private final EmployeeService employeeService;
    private final VisibilityScopeResolver visibilityScopeResolver;

    public OnboardingTaskService(OnboardingTaskRepository onboardingTaskRepository,
                                  EmployeeService employeeService,
                                  VisibilityScopeResolver visibilityScopeResolver) {
        this.onboardingTaskRepository = onboardingTaskRepository;
        this.employeeService = employeeService;
        this.visibilityScopeResolver = visibilityScopeResolver;
    }

    /** GET .../employees/{id}/tasks: employee-level visibility gates the whole list. */
    @Transactional(readOnly = true)
    public List<TaskResponse> listForEmployee(AuthPrincipal principal, Long employeeId) {
        employeeService.requireVisible(principal, employeeId);
        return onboardingTaskRepository.findByEmployeeId(employeeId).stream()
                .map(OnboardingTaskMapper::toResponse)
                .toList();
    }

    /** POST .../employees/{id}/tasks: route already restricts callers to HR_ADMIN/MANAGER. */
    @Transactional
    public TaskResponse createForEmployee(AuthPrincipal principal, Long employeeId, CreateTaskRequest request) {
        Employee employee = employeeService.requireVisible(principal, employeeId);

        OnboardingTask task = new OnboardingTask();
        task.setEmployee(employee);
        task.setTitle(request.title());
        task.setDescription(request.description());
        task.setDueDate(request.dueDate());
        task.setStatus(OnboardingTaskStatus.PENDING);

        return OnboardingTaskMapper.toResponse(onboardingTaskRepository.save(task));
    }

    /**
     * PATCH /onboarding/tasks/{taskId}: not nested under the employee, so
     * the task's owner is resolved as part of the same visibility query
     * (OnboardingTaskSpecifications.visibleTo), not a separate lookup.
     * EMPLOYEE callers may only change status; anything else is a 403.
     */
    @Transactional
    public TaskResponse update(AuthPrincipal principal, Long taskId, UpdateTaskRequest request) {
        OnboardingTask task = findVisible(principal, taskId);

        if (principal.role() == RoleCode.EMPLOYEE && hasNonStatusField(request)) {
            throw new ForbiddenException("Employees may only update the status of their own onboarding tasks");
        }

        if (request.title() != null) {
            task.setTitle(request.title());
        }
        if (request.description() != null) {
            task.setDescription(request.description());
        }
        if (request.dueDate() != null) {
            task.setDueDate(request.dueDate());
        }
        if (request.status() != null) {
            task.setStatus(request.status());
            if (request.status() == OnboardingTaskStatus.COMPLETED && task.getCompletedAt() == null) {
                task.setCompletedAt(Instant.now());
            }
        }

        return OnboardingTaskMapper.toResponse(task);
    }

    private boolean hasNonStatusField(UpdateTaskRequest request) {
        return request.title() != null || request.description() != null || request.dueDate() != null;
    }

    private OnboardingTask findVisible(AuthPrincipal principal, Long taskId) {
        Specification<OnboardingTask> spec = OnboardingTaskSpecifications.hasId(taskId)
                .and(OnboardingTaskSpecifications.visibleTo(visibilityScopeResolver.resolve(principal)));
        return onboardingTaskRepository.findOne(spec).orElseThrow(() -> new NotFoundException(NOT_FOUND_MESSAGE));
    }
}
