package com.aryanyeole.wmp.onboarding.service;

import java.time.Instant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aryanyeole.wmp.common.api.PageResponse;
import com.aryanyeole.wmp.common.domain.Department;
import com.aryanyeole.wmp.common.repository.DepartmentRepository;
import com.aryanyeole.wmp.common.security.AuthPrincipal;
import com.aryanyeole.wmp.common.security.VisibilityScope;
import com.aryanyeole.wmp.common.security.VisibilityScopeResolver;
import com.aryanyeole.wmp.common.web.NotFoundException;
import com.aryanyeole.wmp.onboarding.api.CreateEmployeeRequest;
import com.aryanyeole.wmp.onboarding.api.EmployeeResponse;
import com.aryanyeole.wmp.onboarding.api.UpdateEmployeeRequest;
import com.aryanyeole.wmp.onboarding.domain.Employee;
import com.aryanyeole.wmp.onboarding.domain.EmploymentStatus;
import com.aryanyeole.wmp.onboarding.repository.EmployeeRepository;
import com.aryanyeole.wmp.onboarding.repository.EmployeeSpecifications;

/**
 * No role checks here — route-level RBAC is RouteAuthorizationFilter's job
 * (ADR 0001). Row-level ownership is this class's job, expressed as
 * VisibilityScope query predicates, reused unchanged from the expense
 * domain (see EmployeeSpecifications for the one adaptation the Employee
 * entity itself needed).
 *
 * requireVisible is used by OnboardingTaskService / OnboardingDocumentService
 * (same package) and, as of Phase 5, PayrollReadService (a different
 * package — hence public): "can this principal act on employee #id" is
 * exactly the same question in every case, not a parallel check.
 */
@Service
public class EmployeeService {

    private static final String NOT_FOUND_MESSAGE = "Employee not found";

    private final EmployeeRepository employeeRepository;
    private final DepartmentRepository departmentRepository;
    private final VisibilityScopeResolver visibilityScopeResolver;

    public EmployeeService(EmployeeRepository employeeRepository,
                            DepartmentRepository departmentRepository,
                            VisibilityScopeResolver visibilityScopeResolver) {
        this.employeeRepository = employeeRepository;
        this.departmentRepository = departmentRepository;
        this.visibilityScopeResolver = visibilityScopeResolver;
    }

    @Transactional
    public EmployeeResponse create(CreateEmployeeRequest request) {
        Employee employee = new Employee();
        employee.setFirstName(request.firstName());
        employee.setLastName(request.lastName());
        employee.setEmail(request.email());
        employee.setHireDate(request.hireDate());
        employee.setEmploymentStatus(EmploymentStatus.PENDING);
        if (request.departmentId() != null) {
            employee.setDepartment(requireDepartment(request.departmentId()));
        }
        if (request.managerId() != null) {
            employee.setManager(requireManager(request.managerId()));
        }

        return EmployeeMapper.toResponse(employeeRepository.save(employee));
    }

    @Transactional(readOnly = true)
    public PageResponse<EmployeeResponse> list(AuthPrincipal principal, int page, int size) {
        Specification<Employee> spec = visibleSpec(visibilityScopeResolver.resolve(principal));

        Page<EmployeeResponse> results = employeeRepository
                .findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")))
                .map(EmployeeMapper::toResponse);

        return PageResponse.from(results);
    }

    @Transactional(readOnly = true)
    public EmployeeResponse get(AuthPrincipal principal, Long id) {
        return EmployeeMapper.toResponse(requireVisible(principal, id));
    }

    @Transactional
    public EmployeeResponse update(AuthPrincipal principal, Long id, UpdateEmployeeRequest request) {
        Employee employee = requireVisible(principal, id);

        if (request.firstName() != null) {
            employee.setFirstName(request.firstName());
        }
        if (request.lastName() != null) {
            employee.setLastName(request.lastName());
        }
        if (request.hireDate() != null) {
            employee.setHireDate(request.hireDate());
        }
        if (request.departmentId() != null) {
            employee.setDepartment(requireDepartment(request.departmentId()));
        }
        if (request.managerId() != null) {
            employee.setManager(requireManager(request.managerId()));
        }
        if (request.employmentStatus() != null) {
            EmployeeTransitions.requireTransition(employee.getEmploymentStatus(), request.employmentStatus());
            employee.setEmploymentStatus(request.employmentStatus());
        }

        return EmployeeMapper.toResponse(employee);
    }

    @Transactional
    public void delete(AuthPrincipal principal, Long id) {
        Employee employee = requireVisible(principal, id);
        employee.setDeletedAt(Instant.now());
    }

    /**
     * Resolves the caller's VisibilityScope and fetches employee #id within
     * it, or 404 — same predicate whether id is out of scope or genuinely
     * doesn't exist, so existence is never leaked (ADR 0001).
     *
     * Public (not package-private) as of Phase 5: PayrollReadService's
     * payslips endpoint reuses this exact "is employee #id visible to this
     * principal" question across the onboarding/payroll package boundary —
     * same method, same VisibilityScope mechanism, not a parallel check.
     */
    public Employee requireVisible(AuthPrincipal principal, Long id) {
        Specification<Employee> spec = EmployeeSpecifications.hasId(id)
                .and(visibleSpec(visibilityScopeResolver.resolve(principal)));
        return employeeRepository.findOne(spec).orElseThrow(() -> new NotFoundException(NOT_FOUND_MESSAGE));
    }

    private Specification<Employee> visibleSpec(VisibilityScope scope) {
        return EmployeeSpecifications.notDeleted().and(EmployeeSpecifications.visibleTo(scope));
    }

    private Department requireDepartment(Long departmentId) {
        return departmentRepository.findById(departmentId)
                .orElseThrow(() -> new NotFoundException("Department not found: " + departmentId));
    }

    private Employee requireManager(Long managerId) {
        return employeeRepository.findById(managerId)
                .orElseThrow(() -> new NotFoundException("Manager not found: " + managerId));
    }
}
