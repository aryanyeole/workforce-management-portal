package com.aryanyeole.wmp.expense.api;

/** Optional body for approve/reject — comment is free text, no constraints. */
public record ApprovalDecisionRequest(String comment) {
}
