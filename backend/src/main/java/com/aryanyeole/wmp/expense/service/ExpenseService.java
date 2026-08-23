package com.aryanyeole.wmp.expense.service;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aryanyeole.wmp.auth.repository.UserAccountRepository;
import com.aryanyeole.wmp.common.api.PageResponse;
import com.aryanyeole.wmp.common.domain.ApprovalAction;
import com.aryanyeole.wmp.common.domain.ApprovalEntityType;
import com.aryanyeole.wmp.common.domain.ApprovalEvent;
import com.aryanyeole.wmp.common.money.Money;
import com.aryanyeole.wmp.common.repository.ApprovalEventRepository;
import com.aryanyeole.wmp.common.security.AuthPrincipal;
import com.aryanyeole.wmp.common.security.VisibilityScope;
import com.aryanyeole.wmp.common.security.VisibilityScopeResolver;
import com.aryanyeole.wmp.common.web.ConflictException;
import com.aryanyeole.wmp.common.web.NotFoundException;
import com.aryanyeole.wmp.expense.api.ApprovalDecisionRequest;
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
    private final UserAccountRepository userAccountRepository;
    private final ApprovalEventRepository approvalEventRepository;
    private final VisibilityScopeResolver visibilityScopeResolver;

    public ExpenseService(ExpenseReportRepository expenseReportRepository,
                           ExpenseCategoryRepository expenseCategoryRepository,
                           EmployeeRepository employeeRepository,
                           UserAccountRepository userAccountRepository,
                           ApprovalEventRepository approvalEventRepository,
                           VisibilityScopeResolver visibilityScopeResolver) {
        this.expenseReportRepository = expenseReportRepository;
        this.expenseCategoryRepository = expenseCategoryRepository;
        this.employeeRepository = employeeRepository;
        this.userAccountRepository = userAccountRepository;
        this.approvalEventRepository = approvalEventRepository;
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

    @Transactional
    public ExpenseResponse submit(AuthPrincipal principal, Long id) {
        ExpenseReport report = findVisible(id, ownScope(principal));
        ExpenseTransitions.requireTransition(report.getStatus(), ExpenseStatus.SUBMITTED);

        report.setStatus(ExpenseStatus.SUBMITTED);
        report.setSubmittedAt(Instant.now());

        recordEvent(report, principal, ApprovalAction.SUBMITTED, null);
        return ExpenseMapper.toResponse(report);
    }

    @Transactional
    public ExpenseResponse approve(AuthPrincipal principal, Long id, ApprovalDecisionRequest request) {
        return decide(principal, id, ExpenseStatus.APPROVED, ApprovalAction.APPROVED, request);
    }

    @Transactional
    public ExpenseResponse reject(AuthPrincipal principal, Long id, ApprovalDecisionRequest request) {
        return decide(principal, id, ExpenseStatus.REJECTED, ApprovalAction.REJECTED, request);
    }

    /**
     * Shared by approve/reject: both look the report up in the approver's
     * VisibilityScope (their team, or unrestricted for admin roles), both
     * write an approval_events row, both set approver_id/approved_at. Only
     * the target status, the recorded action, and the self-approval guard
     * differ.
     */
    private ExpenseResponse decide(AuthPrincipal principal, Long id, ExpenseStatus targetStatus,
                                    ApprovalAction action, ApprovalDecisionRequest request) {
        ExpenseReport report = findVisible(id, visibilityScopeResolver.resolve(principal));

        if (targetStatus == ExpenseStatus.APPROVED
                && report.getEmployee().getId().equals(principal.employeeId())) {
            throw new ConflictException("An approver may not approve their own expense report");
        }

        ExpenseTransitions.requireTransition(report.getStatus(), targetStatus);

        report.setStatus(targetStatus);
        report.setApprovedAt(Instant.now());
        report.setApprover(userAccountRepository.getReferenceById(principal.userAccountId()));

        recordEvent(report, principal, action, request == null ? null : request.comment());
        return ExpenseMapper.toResponse(report);
    }

    @Transactional(readOnly = true)
    public PageResponse<ExpenseResponse> pendingApprovals(AuthPrincipal principal, int page, int size) {
        Specification<ExpenseReport> approverScope = ExpenseSpecifications.visibleTo(
                visibilityScopeResolver.resolve(principal));

        Page<ExpenseResponse> results = expenseReportRepository
                .findPendingApprovals(approverScope, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "submittedAt")))
                .map(ExpenseMapper::toResponse);

        return PageResponse.from(results);
    }

    private void recordEvent(ExpenseReport report, AuthPrincipal principal, ApprovalAction action, String comment) {
        ApprovalEvent event = new ApprovalEvent();
        event.setEntityType(ApprovalEntityType.EXPENSE_REPORT);
        event.setEntityId(report.getId());
        event.setActor(userAccountRepository.getReferenceById(principal.userAccountId()));
        event.setAction(action);
        event.setComment(comment);
        approvalEventRepository.save(event);
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
