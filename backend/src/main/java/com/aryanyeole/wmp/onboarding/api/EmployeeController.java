package com.aryanyeole.wmp.onboarding.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.aryanyeole.wmp.common.api.PageResponse;
import com.aryanyeole.wmp.common.security.AuthPrincipal;
import com.aryanyeole.wmp.onboarding.service.EmployeeService;
import com.aryanyeole.wmp.onboarding.service.OnboardingTaskService;

import jakarta.validation.Valid;

/**
 * No @PreAuthorize and no role checks here — route authorization is
 * PermissionRegistry + RouteAuthorizationFilter's job (ADR 0001); row-level
 * ownership is enforced inside EmployeeService via VisibilityScope.
 */
@RestController
@RequestMapping("/api/v1/onboarding/employees")
public class EmployeeController {

    private final EmployeeService employeeService;
    private final OnboardingTaskService onboardingTaskService;

    public EmployeeController(EmployeeService employeeService, OnboardingTaskService onboardingTaskService) {
        this.employeeService = employeeService;
        this.onboardingTaskService = onboardingTaskService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EmployeeResponse create(@Valid @RequestBody CreateEmployeeRequest request) {
        return employeeService.create(request);
    }

    @GetMapping
    public PageResponse<EmployeeResponse> list(@AuthenticationPrincipal AuthPrincipal principal,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return employeeService.list(principal, page, size);
    }

    @GetMapping("/{id}")
    public EmployeeResponse get(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable Long id) {
        return employeeService.get(principal, id);
    }

    @PatchMapping("/{id}")
    public EmployeeResponse update(@AuthenticationPrincipal AuthPrincipal principal,
                                    @PathVariable Long id,
                                    @RequestBody UpdateEmployeeRequest request) {
        return employeeService.update(principal, id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable Long id) {
        employeeService.delete(principal, id);
    }

    @GetMapping("/{id}/tasks")
    public List<TaskResponse> listTasks(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable Long id) {
        return onboardingTaskService.listForEmployee(principal, id);
    }

    @PostMapping("/{id}/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public TaskResponse createTask(@AuthenticationPrincipal AuthPrincipal principal,
                                    @PathVariable Long id,
                                    @Valid @RequestBody CreateTaskRequest request) {
        return onboardingTaskService.createForEmployee(principal, id, request);
    }
}
