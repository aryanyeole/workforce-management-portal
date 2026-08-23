package com.aryanyeole.wmp.payroll.service;

import com.aryanyeole.wmp.common.money.Money;
import com.aryanyeole.wmp.payroll.api.PayrollItemResponse;
import com.aryanyeole.wmp.payroll.api.PayrollRunResponse;
import com.aryanyeole.wmp.payroll.domain.PayrollItem;
import com.aryanyeole.wmp.payroll.domain.PayrollRun;

/** Explicit entity-to-DTO mapping — no reflection-based auto-mapping (CLAUDE.md convention #2). */
final class PayrollMapper {

    private PayrollMapper() {
    }

    static PayrollRunResponse toResponse(PayrollRun run) {
        return new PayrollRunResponse(
                run.getId(),
                run.getPeriodStart(),
                run.getPeriodEnd(),
                run.getStatus(),
                run.getSubmittedBy() == null ? null : run.getSubmittedBy().getId(),
                run.getSubmittedAt(),
                run.getApprovedBy() == null ? null : run.getApprovedBy().getId(),
                run.getApprovedAt(),
                run.getCreatedAt(),
                run.getUpdatedAt());
    }

    static PayrollItemResponse toResponse(PayrollItem item) {
        return new PayrollItemResponse(
                item.getId(),
                item.getPayrollRun().getId(),
                item.getEmployee().getId(),
                Money.amountToCents(item.getGrossPay()),
                Money.amountToCents(item.getTax()),
                Money.amountToCents(item.getDeductions()),
                Money.amountToCents(item.getNetPay()),
                item.getCreatedAt(),
                item.getUpdatedAt());
    }
}
