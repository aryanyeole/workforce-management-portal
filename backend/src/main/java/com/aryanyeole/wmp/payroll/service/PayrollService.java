package com.aryanyeole.wmp.payroll.service;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aryanyeole.wmp.common.api.PageResponse;
import com.aryanyeole.wmp.common.money.Money;
import com.aryanyeole.wmp.common.web.BadRequestException;
import com.aryanyeole.wmp.common.web.ConflictException;
import com.aryanyeole.wmp.common.web.NotFoundException;
import com.aryanyeole.wmp.onboarding.domain.Employee;
import com.aryanyeole.wmp.onboarding.repository.EmployeeRepository;
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

    public PayrollService(PayrollRunRepository payrollRunRepository,
                           PayrollItemRepository payrollItemRepository,
                           EmployeeRepository employeeRepository) {
        this.payrollRunRepository = payrollRunRepository;
        this.payrollItemRepository = payrollItemRepository;
        this.employeeRepository = employeeRepository;
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

    PayrollRun requireRun(Long id) {
        return payrollRunRepository.findById(id).orElseThrow(() -> new NotFoundException(RUN_NOT_FOUND));
    }
}
