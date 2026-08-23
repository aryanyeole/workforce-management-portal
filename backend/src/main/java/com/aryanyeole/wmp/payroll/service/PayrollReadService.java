package com.aryanyeole.wmp.payroll.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aryanyeole.wmp.common.api.PageResponse;
import com.aryanyeole.wmp.common.money.Money;
import com.aryanyeole.wmp.common.security.AuthPrincipal;
import com.aryanyeole.wmp.onboarding.service.EmployeeService;
import com.aryanyeole.wmp.payroll.api.PayslipResponse;
import com.aryanyeole.wmp.payroll.api.PeriodSummaryResponse;
import com.aryanyeole.wmp.payroll.domain.PayrollItem;
import com.aryanyeole.wmp.payroll.repository.PayrollItemRepository;
import com.aryanyeole.wmp.payroll.repository.PayrollPeriodSummary;

/**
 * Unlike PayrollService, this class's queries ARE employee-owned — reuses
 * VisibilityScope exactly as expense/onboarding do, via
 * EmployeeService.requireVisible rather than a parallel check (ADR 0001).
 */
@Service
public class PayrollReadService {

    private final PayrollItemRepository payrollItemRepository;
    private final EmployeeService employeeService;

    public PayrollReadService(PayrollItemRepository payrollItemRepository, EmployeeService employeeService) {
        this.payrollItemRepository = payrollItemRepository;
        this.employeeService = employeeService;
    }

    /**
     * 404s (not empty page) when employeeId is outside the caller's scope —
     * requireVisible runs before any payslip query, same as onboarding's
     * nested employee routes.
     */
    @Transactional(readOnly = true)
    public PageResponse<PayslipResponse> payslips(AuthPrincipal principal, Long employeeId, int page, int size) {
        employeeService.requireVisible(principal, employeeId);

        Page<PayslipResponse> results = payrollItemRepository
                .findByEmployeeIdOrderByPayrollRun_PeriodStartDesc(employeeId, PageRequest.of(page, size))
                .map(this::toPayslipResponse);

        return PageResponse.from(results);
    }

    @Transactional(readOnly = true)
    public PageResponse<PeriodSummaryResponse> summary(int page, int size) {
        Page<PeriodSummaryResponse> results = payrollItemRepository
                .summarizeByPeriod(PageRequest.of(page, size))
                .map(this::toSummaryResponse);

        return PageResponse.from(results);
    }

    private PayslipResponse toPayslipResponse(PayrollItem item) {
        return new PayslipResponse(
                item.getId(),
                item.getPayrollRun().getId(),
                item.getPayrollRun().getPeriodStart(),
                item.getPayrollRun().getPeriodEnd(),
                item.getPayrollRun().getStatus(),
                item.getEmployee().getId(),
                Money.amountToCents(item.getGrossPay()),
                Money.amountToCents(item.getTax()),
                Money.amountToCents(item.getDeductions()),
                Money.amountToCents(item.getNetPay()));
    }

    private PeriodSummaryResponse toSummaryResponse(PayrollPeriodSummary summary) {
        return new PeriodSummaryResponse(
                summary.getPeriodStart(),
                summary.getPeriodEnd(),
                summary.getStatus(),
                Money.amountToCents(summary.getTotalGross()),
                Money.amountToCents(summary.getTotalTax()),
                Money.amountToCents(summary.getTotalDeductions()),
                Money.amountToCents(summary.getTotalNet()),
                summary.getItemCount());
    }
}
