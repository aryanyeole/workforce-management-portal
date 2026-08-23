package com.aryanyeole.wmp.payroll.api;

/** Optional body for approve/reject — comment is free text, no constraints. */
public record ApprovalDecisionRequest(String comment) {
}
