package com.aryanyeole.wmp.payroll.service;

import java.time.Instant;
import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
import com.aryanyeole.wmp.common.web.BadRequestException;
import com.aryanyeole.wmp.common.web.ConflictException;
import com.aryanyeole.wmp.common.web.NotFoundException;
import com.aryanyeole.wmp.onboarding.domain.Employee;
import com.aryanyeole.wmp.onboarding.repository.EmployeeRepository;
import com.aryanyeole.wmp.payroll.api.ApprovalDecisionRequest;
import com.aryanyeole.wmp.payroll.api.CreatePayrollItemRequest;
import com.aryanyeole.wmp.payroll.api.CreatePayrollRunRequest;
import com.aryanyeole.wmp.payroll.api.PayrollItemResponse;
import com.aryanyeole.wmp.payroll.api.PayrollRunResponse;
import com.aryanyeole.wmp.payroll.domain.PayrollItem;
import com.aryanyeole.wmp.payroll.domain.PayrollRun;
import com.aryanyeole.wmp.payroll.domain.PayrollRunStatus;
import com.aryanyeole.wmp.payroll.repository.PayrollItemRepository;
import com.aryanyeole.wmp.payroll.repository.PayrollRunRepository;

/**
 * No role checks here — route-level RBAC is RouteAuthorizationFilter's job
 * (ADR 0001), and it does all the work for this class: a payroll run is
 * org-wide, not employee-owned, so there is no VisibilityScope to apply to
 * PayrollRun/PayrollItem here — PAYROLL_ADMIN is the only role the registry
 * admits to any of these methods. VisibilityScope reappears in the
 * payslips read model (PayrollReadService), which really is employee-owned.
 */
@Service
public class PayrollService {

    private static final String RUN_NOT_FOUND = "Payroll run not found";

    private final PayrollRunRepository payrollRunRepository;
    private final PayrollItemRepository payrollItemRepository;
    private final EmployeeRepository employeeRepository;
    private final UserAccountRepository userAccountRepository;
    private final ApprovalEventRepository approvalEventRepository;
    private final MeterRegistry meterRegistry;

    public PayrollService(PayrollRunRepository payrollRunRepository,
                           PayrollItemRepository payrollItemRepository,
                           EmployeeRepository employeeRepository,
                           UserAccountRepository userAccountRepository,
                           ApprovalEventRepository approvalEventRepository,
                           MeterRegistry meterRegistry) {
        this.payrollRunRepository = payrollRunRepository;
        this.payrollItemRepository = payrollItemRepository;
        this.employeeRepository = employeeRepository;
        this.userAccountRepository = userAccountRepository;
        this.approvalEventRepository = approvalEventRepository;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public PayrollRunResponse createRun(CreatePayrollRunRequest request) {
        if (request.periodEnd().isBefore(request.periodStart())) {
            throw new BadRequestException("periodEnd must not be before periodStart");
        }
        // Fast-path check before the DB round trip that would otherwise fail as a
        // DataIntegrityViolationException — see payrollRuns_period_unique (V5).
        if (payrollRunRepository.existsByPeriodStartAndPeriodEnd(request.periodStart(), request.periodEnd())) {
            throw new ConflictException(
                    "A payroll run already exists for period %s to %s".formatted(request.periodStart(), request.periodEnd()));
        }

        PayrollRun run = new PayrollRun();
        run.setPeriodStart(request.periodStart());
        run.setPeriodEnd(request.periodEnd());
        run.setStatus(PayrollRunStatus.DRAFT);

        try {
            return PayrollMapper.toResponse(payrollRunRepository.saveAndFlush(run));
        } catch (DataIntegrityViolationException e) {
            // Defense in depth for the race between the check above and this insert.
            throw new ConflictException(
                    "A payroll run already exists for period %s to %s".formatted(request.periodStart(), request.periodEnd()));
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<PayrollRunResponse> listRuns(int page, int size) {
        Page<PayrollRunResponse> results = payrollRunRepository
                .findAll(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")))
                .map(PayrollMapper::toResponse);
        return PageResponse.from(results);
    }

    @Transactional(readOnly = true)
    public PayrollRunResponse getRun(Long id) {
        return PayrollMapper.toResponse(requireRun(id));
    }

    @Transactional(readOnly = true)
    public List<PayrollItemResponse> listItems(Long runId) {
        requireRun(runId);
        return payrollItemRepository.findByPayrollRunId(runId).stream()
                .map(PayrollMapper::toResponse)
                .toList();
    }

    @Transactional
    public PayrollItemResponse createItem(Long runId, CreatePayrollItemRequest request) {
        PayrollRun run = requireRun(runId);
        PayrollTransitions.requireDraft(run.getStatus());

        Employee employee = employeeRepository.findById(request.employeeId())
                .orElseThrow(() -> new NotFoundException("Employee not found: " + request.employeeId()));

        if (payrollItemRepository.existsByPayrollRunIdAndEmployeeId(runId, request.employeeId())) {
            throw new ConflictException(
                    "Employee %d already has an item in this payroll run".formatted(request.employeeId()));
        }

        long netCents = request.grossCents() - request.taxCents() - request.deductionsCents();
        if (netCents < 0) {
            throw new BadRequestException("gross pay is less than tax plus deductions");
        }

        PayrollItem item = new PayrollItem();
        item.setPayrollRun(run);
        item.setEmployee(employee);
        item.setGrossPay(Money.centsToAmount(request.grossCents()));
        item.setTax(Money.centsToAmount(request.taxCents()));
        item.setDeductions(Money.centsToAmount(request.deductionsCents()));
        item.setNetPay(Money.centsToAmount(netCents));

        try {
            return PayrollMapper.toResponse(payrollItemRepository.saveAndFlush(item));
        } catch (DataIntegrityViolationException e) {
            // Defense in depth for the race between the check above and this insert.
            throw new ConflictException(
                    "Employee %d already has an item in this payroll run".formatted(request.employeeId()));
        }
    }

    /**
     * Plain @Transactional, repository calls only — no manual DataSource or
     * long-held connections. ROADMAP Phase 8 reproduces pool exhaustion
     * against this exact endpoint; the leak it introduces lives elsewhere
     * (a scheduled batch job), so this method must stay boring and
     * innocent.
     *
     * Timed under "payroll.submit.requests" (Phase 7) — this is the exact
     * path Phase 8's diagnosis watches for latency growth as the pool
     * degrades, so it gets its own named timer rather than only showing up
     * folded into the generic http.server.requests bucket.
     */
    @Transactional
    public PayrollRunResponse submit(AuthPrincipal principal, Long runId) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            PayrollRun run = requireRun(runId);
            PayrollTransitions.requireTransition(run.getStatus(), PayrollRunStatus.SUBMITTED);

            if (!payrollItemRepository.existsByPayrollRunId(runId)) {
                throw new ConflictException("Cannot submit a payroll run with no items");
            }

            // Phase 10 Task 0: same read-check-then-write race as
            // ExpenseService.submit/decide — see
            // PayrollRunRepository.compareAndSetStatus and
            // ExpenseService.compareAndSetStatusOrConflict's javadoc.
            compareAndSetStatusOrConflict(runId, run.getStatus(), PayrollRunStatus.SUBMITTED);

            run.setStatus(PayrollRunStatus.SUBMITTED);
            run.setSubmittedBy(userAccountRepository.getReferenceById(principal.userAccountId()));
            run.setSubmittedAt(Instant.now());

            recordEvent(run, principal, ApprovalAction.SUBMITTED, null);
            return PayrollMapper.toResponse(run);
        } finally {
            sample.stop(meterRegistry.timer("payroll.submit.requests"));
        }
    }

    @Transactional
    public PayrollRunResponse approve(AuthPrincipal principal, Long runId, ApprovalDecisionRequest request) {
        return decide(principal, runId, PayrollRunStatus.APPROVED, ApprovalAction.APPROVED, request);
    }

    @Transactional
    public PayrollRunResponse reject(AuthPrincipal principal, Long runId, ApprovalDecisionRequest request) {
        return decide(principal, runId, PayrollRunStatus.REJECTED, ApprovalAction.REJECTED, request);
    }

    private PayrollRunResponse decide(AuthPrincipal principal, Long runId, PayrollRunStatus targetStatus,
                                       ApprovalAction action, ApprovalDecisionRequest request) {
        PayrollRun run = requireRun(runId);

        if (targetStatus == PayrollRunStatus.APPROVED
                && run.getSubmittedBy() != null
                && run.getSubmittedBy().getId().equals(principal.userAccountId())) {
            throw new ConflictException("A payroll run's submitter may not approve their own submission");
        }

        PayrollTransitions.requireTransition(run.getStatus(), targetStatus);
        // See submit()'s comment — same race, same guard.
        compareAndSetStatusOrConflict(runId, run.getStatus(), targetStatus);

        run.setStatus(targetStatus);
        run.setApprovedBy(userAccountRepository.getReferenceById(principal.userAccountId()));
        run.setApprovedAt(Instant.now());

        recordEvent(run, principal, action, request == null ? null : request.comment());
        return PayrollMapper.toResponse(run);
    }

    /**
     * Phase 10 Task 0 — mirrors ExpenseService.compareAndSetStatusOrConflict;
     * see that javadoc for the race this closes and why a failed
     * compare-and-swap is guaranteed to make requireTransition throw below
     * rather than silently pass (PayrollRunStatus's transitions are all
     * single-step or terminal, same as ExpenseStatus).
     */
    private void compareAndSetStatusOrConflict(Long runId, PayrollRunStatus expected, PayrollRunStatus next) {
        int updated = payrollRunRepository.compareAndSetStatus(runId, expected, next);
        if (updated == 0) {
            PayrollRun current = requireRun(runId);
            PayrollTransitions.requireTransition(current.getStatus(), next);
            throw new ConflictException("Payroll run was concurrently modified; please retry");
        }
    }

    private void recordEvent(PayrollRun run, AuthPrincipal principal, ApprovalAction action, String comment) {
        ApprovalEvent event = new ApprovalEvent();
        event.setEntityType(ApprovalEntityType.PAYROLL_RUN);
        event.setEntityId(run.getId());
        event.setActor(userAccountRepository.getReferenceById(principal.userAccountId()));
        event.setAction(action);
        event.setComment(comment);
        approvalEventRepository.save(event);
    }

    PayrollRun requireRun(Long id) {
        return payrollRunRepository.findById(id).orElseThrow(() -> new NotFoundException(RUN_NOT_FOUND));
    }
}
