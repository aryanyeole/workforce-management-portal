package com.aryanyeole.wmp.payroll.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aryanyeole.wmp.common.api.PageResponse;
import com.aryanyeole.wmp.common.security.AuthPrincipal;
import com.aryanyeole.wmp.payroll.service.PayrollReadService;

/**
 * No @PreAuthorize and no role checks here — route authorization is
 * PermissionRegistry + RouteAuthorizationFilter's job (ADR 0001); row-level
 * ownership for payslips is enforced inside PayrollReadService via
 * VisibilityScope (reused from EmployeeService).
 */
@RestController
@RequestMapping("/api/v1/payroll")
public class PayrollReportController {

    private final PayrollReadService payrollReadService;

    public PayrollReportController(PayrollReadService payrollReadService) {
        this.payrollReadService = payrollReadService;
    }

    @GetMapping("/employees/{employeeId}/payslips")
    public PageResponse<PayslipResponse> payslips(@AuthenticationPrincipal AuthPrincipal principal,
                                                   @PathVariable Long employeeId,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return payrollReadService.payslips(principal, employeeId, page, size);
    }

    @GetMapping("/summary")
    public PageResponse<PeriodSummaryResponse> summary(@RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "20") int size) {
        return payrollReadService.summary(page, size);
    }
}
