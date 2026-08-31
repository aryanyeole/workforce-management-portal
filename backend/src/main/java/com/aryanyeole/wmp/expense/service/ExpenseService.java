package com.aryanyeole.wmp.expense.service;

import java.time.Instant;
import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aryanyeole.wmp.auth.repository.UserAccountRepository;
import com.aryanyeole.wmp.common.api.CursorPageResponse;
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
import com.aryanyeole.wmp.expense.repository.ExpenseApprovalsKeysetRepository;
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

    /** "sane maximum" for the approvals queue's keyset size — see pendingApprovals. */
    private static final int APPROVALS_DEFAULT_SIZE = 20;
    private static final int APPROVALS_MAX_SIZE = 100;

    private final ExpenseReportRepository expenseReportRepository;
    private final ExpenseApprovalsKeysetRepository expenseApprovalsKeysetRepository;
    private final ExpenseCategoryRepository expenseCategoryRepository;
    private final EmployeeRepository employeeRepository;
    private final UserAccountRepository userAccountRepository;
    private final ApprovalEventRepository approvalEventRepository;
    private final VisibilityScopeResolver visibilityScopeResolver;
    private final MeterRegistry meterRegistry;

    public ExpenseService(ExpenseReportRepository expenseReportRepository,
                           ExpenseApprovalsKeysetRepository expenseApprovalsKeysetRepository,
                           ExpenseCategoryRepository expenseCategoryRepository,
                           EmployeeRepository employeeRepository,
                           UserAccountRepository userAccountRepository,
                           ApprovalEventRepository approvalEventRepository,
                           VisibilityScopeResolver visibilityScopeResolver,
                           MeterRegistry meterRegistry) {
        this.expenseReportRepository = expenseReportRepository;
        this.expenseApprovalsKeysetRepository = expenseApprovalsKeysetRepository;
        this.expenseCategoryRepository = expenseCategoryRepository;
        this.employeeRepository = employeeRepository;
        this.userAccountRepository = userAccountRepository;
        this.approvalEventRepository = approvalEventRepository;
        this.visibilityScopeResolver = visibilityScopeResolver;
        this.meterRegistry = meterRegistry;
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
        VisibilityScope scope = ownScope(principal);
        ExpenseReport report = findVisible(id, scope);
        ExpenseTransitions.requireTransition(report.getStatus(), ExpenseStatus.SUBMITTED);

        // Phase 10 Task 0: the check above reads a snapshot that can go
        // stale before this transaction commits — a concurrent submit of
        // the same report can pass the same check on its own snapshot
        // first. compareAndSetStatusOrConflict closes that window; see its
        // own javadoc and ExpenseReportRepository.compareAndSetStatus.
        compareAndSetStatusOrConflict(id, scope, report.getStatus(), ExpenseStatus.SUBMITTED);

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
        VisibilityScope scope = visibilityScopeResolver.resolve(principal);
        ExpenseReport report = findVisible(id, scope);

        if (targetStatus == ExpenseStatus.APPROVED
                && report.getEmployee().getId().equals(principal.employeeId())) {
            throw new ConflictException("An approver may not approve their own expense report");
        }

        ExpenseTransitions.requireTransition(report.getStatus(), targetStatus);
        // See submit()'s comment — same race, same guard.
        compareAndSetStatusOrConflict(id, scope, report.getStatus(), targetStatus);

        report.setStatus(targetStatus);
        report.setApprovedAt(Instant.now());
        report.setApprover(userAccountRepository.getReferenceById(principal.userAccountId()));

        recordEvent(report, principal, action, request == null ? null : request.comment());
        return ExpenseMapper.toResponse(report);
    }

    /**
     * Phase 10 Task 0. Attempts the guarded write; if another transaction
     * already moved this report past the status we read, re-checks against
     * the now-current row so the caller gets the exact same "Cannot
     * transition expense report from X to Y" message ExpenseTransitions
     * already produces for the ordinary (non-race) illegal-transition case
     * — one message-building path, not two that can drift.
     *
     * Every transition this guards (DRAFT->SUBMITTED, SUBMITTED->APPROVED|
     * REJECTED) targets a status whose own outgoing transitions are either
     * a single next step or terminal — there is no path back to `expected`
     * once it's left behind, so a failed compare-and-swap always means the
     * current status has moved strictly past `expected`, and
     * requireTransition below is guaranteed to throw. The trailing
     * ConflictException is a defensive fallback for that reasoning being
     * wrong someday (e.g. a future transition graph gains a cycle), not a
     * path this codebase's current state machine can actually reach.
     */
    private void compareAndSetStatusOrConflict(Long id, VisibilityScope scope, ExpenseStatus expected, ExpenseStatus next) {
        int updated = expenseReportRepository.compareAndSetStatus(id, expected, next);
        if (updated == 0) {
            ExpenseReport current = findVisible(id, scope);
            ExpenseTransitions.requireTransition(current.getStatus(), next);
            throw new ConflictException("Expense report was concurrently modified; please retry");
        }
    }

    /**
     * Keyset pagination: (submitted_at DESC, id DESC), an opaque cursor
     * over the last row's own (submitted_at, id) rather than an offset.
     * cursor is the raw client-supplied string (null for the first page);
     * a malformed one is rejected by ApprovalsCursor.decode before it
     * ever reaches the query. VisibilityScope scoping is unchanged from
     * the offset implementation — only how the page boundary is
     * expressed has changed.
     *
     * Timed under "approvals.requests" (Phase 7) — Phase 8's diagnosis
     * needs this path's latency visible on its own, not folded into the
     * generic http.server.requests bucket for every route.
     */
    @Transactional(readOnly = true)
    public CursorPageResponse<ExpenseResponse> pendingApprovals(AuthPrincipal principal, String cursor, int size) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            int boundedSize = boundedApprovalsSize(size);
            ApprovalsCursor decodedCursor = cursor == null ? null : ApprovalsCursor.decode(cursor);

            Specification<ExpenseReport> approverScope = ExpenseSpecifications.visibleTo(
                    visibilityScopeResolver.resolve(principal));

            // Fetch one extra row to learn whether more remain, instead of a
            // second COUNT query — the whole point of not using offset/Page here.
            Instant cursorSubmittedAt = decodedCursor == null ? null : decodedCursor.submittedAt();
            Long cursorId = decodedCursor == null ? null : decodedCursor.id();
            List<ExpenseReport> rows = expenseApprovalsKeysetRepository.findPage(
                    approverScope, cursorSubmittedAt, cursorId, boundedSize);

            boolean hasMore = rows.size() > boundedSize;
            List<ExpenseReport> pageRows = hasMore ? rows.subList(0, boundedSize) : rows;

            String nextCursor = null;
            if (hasMore) {
                ExpenseReport last = pageRows.get(pageRows.size() - 1);
                nextCursor = new ApprovalsCursor(last.getSubmittedAt(), last.getId()).encode();
            }

            return new CursorPageResponse<>(pageRows.stream().map(ExpenseMapper::toResponse).toList(), nextCursor);
        } finally {
            sample.stop(meterRegistry.timer("approvals.requests"));
        }
    }

    private int boundedApprovalsSize(int requested) {
        if (requested <= 0) {
            return APPROVALS_DEFAULT_SIZE;
        }
        return Math.min(requested, APPROVALS_MAX_SIZE);
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
