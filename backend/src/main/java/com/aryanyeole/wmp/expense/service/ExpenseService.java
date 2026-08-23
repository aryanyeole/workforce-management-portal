package com.aryanyeole.wmp.expense.service;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aryanyeole.wmp.common.api.PageResponse;
import com.aryanyeole.wmp.common.money.Money;
import com.aryanyeole.wmp.common.security.AuthPrincipal;
import com.aryanyeole.wmp.common.security.VisibilityScope;
import com.aryanyeole.wmp.common.security.VisibilityScopeResolver;
import com.aryanyeole.wmp.common.web.NotFoundException;
import com.aryanyeole.wmp.expense.api.CreateExpenseRequest;
import com.aryanyeole.wmp.expense.api.ExpenseCategoryResponse;
import com.aryanyeole.wmp.expense.api.ExpenseResponse;
import com.aryanyeole.wmp.expense.api.UpdateExpenseRequest;
import com.aryanyeole.wmp.expense.domain.ExpenseCategory;
import com.aryanyeole.wmp.expense.domain.ExpenseReport;
import com.aryanyeole.wmp.expense.domain.ExpenseStatus;
import com.aryanyeole.wmp.expense.repository.ExpenseCategoryRepository;
import com.aryanyeole.wmp.expense.repository.ExpenseReportRepository;
import com.aryanyeole.wmp.expense.repository.ExpenseSpecifications;
import com.aryanyeole.wmp.onboarding.repository.EmployeeRepository;

/**
 * No role checks here — route-level RBAC is RouteAuthorizationFilter's job
 * (ADR 0001). Row-level ownership is this class's job, expressed as
 * VisibilityScope query predicates (see ExpenseSpecifications), never as an
 * if-check on principal.role().
 */
@Service
public class ExpenseService {

    private static final String NOT_FOUND_MESSAGE = "Expense report not found";

    private final ExpenseReportRepository expenseReportRepository;
    private final ExpenseCategoryRepository expenseCategoryRepository;
    private final EmployeeRepository employeeRepository;
    private final VisibilityScopeResolver visibilityScopeResolver;

    public ExpenseService(ExpenseReportRepository expenseReportRepository,
                           ExpenseCategoryRepository expenseCategoryRepository,
                           EmployeeRepository employeeRepository,
                           VisibilityScopeResolver visibilityScopeResolver) {
        this.expenseReportRepository = expenseReportRepository;
        this.expenseCategoryRepository = expenseCategoryRepository;
        this.employeeRepository = employeeRepository;
        this.visibilityScopeResolver = visibilityScopeResolver;
    }

    @Transactional
    public ExpenseResponse create(AuthPrincipal principal, CreateExpenseRequest request) {
        ExpenseCategory category = requireCategory(request.categoryId());

        ExpenseReport report = new ExpenseReport();
        report.setEmployee(employeeRepository.getReferenceById(principal.employeeId()));
        report.setCategory(category);
        report.setAmount(Money.centsToAmount(request.amountCents()));
        report.setCurrency(request.currency());
        report.setDescription(request.description());
        report.setStatus(ExpenseStatus.DRAFT);

        return ExpenseMapper.toResponse(expenseReportRepository.save(report));
    }

    @Transactional(readOnly = true)
    public PageResponse<ExpenseResponse> list(AuthPrincipal principal, int page, int size) {
        Specification<ExpenseReport> spec = visibleSpec(visibilityScopeResolver.resolve(principal));

        Page<ExpenseResponse> results = expenseReportRepository
                .findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")))
                .map(ExpenseMapper::toResponse);

        return PageResponse.from(results);
    }

    @Transactional(readOnly = true)
    public ExpenseResponse get(AuthPrincipal principal, Long id) {
        return ExpenseMapper.toResponse(findVisible(id, visibilityScopeResolver.resolve(principal)));
    }

    @Transactional
    public ExpenseResponse update(AuthPrincipal principal, Long id, UpdateExpenseRequest request) {
        ExpenseReport report = findVisible(id, ownScope(principal));
        ExpenseTransitions.requireDraft(report.getStatus());

        if (request.categoryId() != null) {
            report.setCategory(requireCategory(request.categoryId()));
        }
        if (request.amountCents() != null) {
            report.setAmount(Money.centsToAmount(request.amountCents()));
        }
        if (request.currency() != null) {
            report.setCurrency(request.currency());
        }
        if (request.description() != null) {
            report.setDescription(request.description());
        }

        return ExpenseMapper.toResponse(report);
    }

    @Transactional
    public void delete(AuthPrincipal principal, Long id) {
        ExpenseReport report = findVisible(id, ownScope(principal));
        ExpenseTransitions.requireDraft(report.getStatus());
        report.setDeletedAt(Instant.now());
    }

    @Transactional(readOnly = true)
    public List<ExpenseCategoryResponse> listCategories() {
        return expenseCategoryRepository.findAll().stream()
                .map(ExpenseMapper::toCategoryResponse)
                .toList();
    }

    private ExpenseCategory requireCategory(Long categoryId) {
        return expenseCategoryRepository.findById(categoryId)
                .orElseThrow(() -> new NotFoundException("Expense category not found: " + categoryId));
    }

    private ExpenseReport findVisible(Long id, VisibilityScope scope) {
        Specification<ExpenseReport> spec = ExpenseSpecifications.hasId(id).and(visibleSpec(scope));
        return expenseReportRepository.findOne(spec).orElseThrow(() -> new NotFoundException(NOT_FOUND_MESSAGE));
    }

    private Specification<ExpenseReport> visibleSpec(VisibilityScope scope) {
        return ExpenseSpecifications.notDeleted().and(ExpenseSpecifications.visibleTo(scope));
    }

    private VisibilityScope ownScope(AuthPrincipal principal) {
        return new VisibilityScope.Self(principal.employeeId());
    }
}
