import { apiFetch } from './client';
import type { CreateExpenseRequest, CursorPageResponse, ExpenseCategoryResponse, ExpenseResponse, PageResponse } from './types';

/** Own-expenses list page size (Phase 9 Task 4) — small, offset-paginated, not the approvals queue. */
export const MY_EXPENSES_PAGE_SIZE = 20;

/**
 * GET /api/v1/expenses — the caller's own expense reports (VisibilityScope
 * on the backend, not a query param here). Offset-paginated (page/size),
 * unlike /approvals: this list is at most a few hundred rows for any one
 * employee, nowhere near the 20,976-row approvals queue keyset pagination
 * exists for (docs/adr/0002-keyset-pagination.md) — no cursor, no
 * virtualization here, plain page/size.
 */
export function fetchMyExpenses(page: number): Promise<PageResponse<ExpenseResponse>> {
  const params = new URLSearchParams({ page: String(page), size: String(MY_EXPENSES_PAGE_SIZE) });
  return apiFetch<PageResponse<ExpenseResponse>>(`/api/v1/expenses?${params.toString()}`);
}

/** GET /api/v1/expenses/categories — plain list, no pagination at all. */
export function fetchExpenseCategories(): Promise<ExpenseCategoryResponse[]> {
  return apiFetch<ExpenseCategoryResponse[]>('/api/v1/expenses/categories');
}

/**
 * POST /api/v1/expenses — creates a DRAFT report. Distinct from submit
 * below: this alone never moves anything into the approvals queue (Task 4
 * point 1 — do not collapse the two into one action).
 */
export function createExpense(request: CreateExpenseRequest): Promise<ExpenseResponse> {
  return apiFetch<ExpenseResponse>('/api/v1/expenses', {
    method: 'POST',
    body: JSON.stringify(request),
  });
}

/** POST /api/v1/expenses/{id}/submit — DRAFT -> SUBMITTED. 409 (via ExpenseTransitions) if not currently DRAFT. */
export function submitExpense(id: number): Promise<ExpenseResponse> {
  return apiFetch<ExpenseResponse>(`/api/v1/expenses/${id}/submit`, { method: 'POST' });
}

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
