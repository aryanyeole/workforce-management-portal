package com.aryanyeole.wmp.payroll.job;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

/**
 * On-demand trigger for PayrollAccrualJob, alongside its own nightly cron
 * schedule. This is operational tooling, not domain API — it doesn't
 * belong on /api/v1 or in the OpenAPI-documented surface (OpenApiIT
 * enforces "exactly 30 domain endpoints" precisely so this kind of route
 * can't drift onto that surface unnoticed) — so it's exposed the same way
 * /actuator/health and /actuator/metrics are, at /actuator/payroll-accrual,
 * outside PermissionRegistry entirely.
 *
 * Unlike the read-only actuator endpoints this project already exposes,
 * this one triggers a real write (and, currently, PayrollAccrualJob's
 * intentional Phase 8 leak) — see application.yml/application-dev.yml:
 * it's only ever exposed when the "dev" profile is active, never by
 * default. Where a real deployment would put an operational trigger like
 * this behind actuator's separate management port and network policy
 * instead of an application-level permission check, this project (no
 * separate management port configured) relies on that dev-only exposure
 * gate as its equivalent for a portfolio-scoped setup.
 */
@Component
@Endpoint(id = "payroll-accrual")
public class PayrollAccrualEndpoint {

    private final PayrollAccrualJob payrollAccrualJob;

    public PayrollAccrualEndpoint(PayrollAccrualJob payrollAccrualJob) {
        this.payrollAccrualJob = payrollAccrualJob;
    }

    @WriteOperation
    public PayrollAccrualJob.AccrualRunResult run() {
        return payrollAccrualJob.run();
    }
}
