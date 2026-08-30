package com.aryanyeole.wmp.payroll.api;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aryanyeole.wmp.payroll.job.PayrollAccrualJob;

/**
 * On-demand trigger for PayrollAccrualJob, alongside its own nightly cron
 * schedule — an ops-style "run it now" endpoint (also how Phase 8's
 * reproduction runs the job without waiting for a cron window). No
 * @PreAuthorize here — route authorization is PermissionRegistry +
 * RouteAuthorizationFilter's job (ADR 0001).
 */
@RestController
@RequestMapping("/api/v1/payroll/accrual")
public class PayrollAccrualController {

    private final PayrollAccrualJob payrollAccrualJob;

    public PayrollAccrualController(PayrollAccrualJob payrollAccrualJob) {
        this.payrollAccrualJob = payrollAccrualJob;
    }

    @PostMapping("/run")
    public AccrualRunResponse run() {
        PayrollAccrualJob.AccrualRunResult result = payrollAccrualJob.run();
        return new AccrualRunResponse(result.periodStart(), result.periodEnd(), result.employeesProcessed());
    }
}
