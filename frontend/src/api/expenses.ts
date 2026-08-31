import { apiFetch } from './client';
import type { CursorPageResponse, ExpenseResponse } from './types';

/**
 * Matches the depths measured in docs/measurements.md's Phase 6/7 keyset
 * work (size=20 was the harness default there; 50 here is just a UI-scroll
 * batch size choice, not tied to that measurement).
 */
export const APPROVALS_PAGE_SIZE = 50;

/**
 * GET /api/v1/expenses/approvals. `cursor` is opaque — see
 * docs/adr/0002-keyset-pagination.md and ExpenseResponse's own comment:
 * this function never decodes it, only forwards whatever the previous
 * page's `nextCursor` was, verbatim, as this request's `cursor` param.
 */
export function fetchApprovalsPage(cursor: string | null): Promise<CursorPageResponse<ExpenseResponse>> {
  const params = new URLSearchParams({ size: String(APPROVALS_PAGE_SIZE) });
  if (cursor) {
    params.set('cursor', cursor);
  }
  return apiFetch<CursorPageResponse<ExpenseResponse>>(`/api/v1/expenses/approvals?${params.toString()}`);
}

/**
 * POST /api/v1/expenses/{id}/approve and /reject. Per-resource — there is
 * no bulk endpoint (Phase 9 Task 3: the approvals table's own bulk-action UI
 * calls these one at a time, bounded by concurrency, not in one request).
 * `comment` is optional free text, same as the backend's ApprovalDecisionRequest.
 */
export function approveExpense(id: number, comment?: string): Promise<ExpenseResponse> {
  return apiFetch<ExpenseResponse>(`/api/v1/expenses/${id}/approve`, {
    method: 'POST',
    body: comment ? JSON.stringify({ comment }) : undefined,
  });
}

export function rejectExpense(id: number, comment?: string): Promise<ExpenseResponse> {
  return apiFetch<ExpenseResponse>(`/api/v1/expenses/${id}/reject`, {
    method: 'POST',
    body: comment ? JSON.stringify({ comment }) : undefined,
  });
}
