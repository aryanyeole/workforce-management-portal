import { ApiError } from '../../api/client';
import { approveExpense, rejectExpense } from '../../api/expenses';
import type { ExpenseResponse } from '../../api/types';

export type ApprovalAction = 'approve' | 'reject';

export type BulkActionOutcome =
  | { id: number; outcome: 'success'; expense: ExpenseResponse }
  | { id: number; outcome: 'error'; httpStatus: number; detail: string };

/**
 * How many of a bulk selection's approve/reject requests run at once.
 *
 * There is no bulk endpoint, so N selected rows means N separate HTTP
 * requests, each opening its own short-lived HikariCP connection
 * (ExpenseService.decide is @Transactional, one connection per call,
 * released the moment the request completes). The pool is 10 connections
 * (docs/incidents/2026-08-payroll-500s.md) and shared with every other
 * request this backend serves — the approvals list's own in-flight scroll
 * fetches, other admins, refresh calls.
 *
 * This isn't the same defect as that incident (nothing here leaks a
 * connection; every request releases its own the instant it completes),
 * but the incident's own conclusion generalizes directly: don't assume
 * headroom in a pool this small. Firing all N requests at once would let a
 * single admin's one bulk click claim the *entire* pool for however long
 * the slowest of them takes, starving everything else sharing it —
 * including that same admin's own approvals list, still scrolling.
 *
 * 4 is deliberately well under half the pool: enough to make a multi-item
 * bulk action meaningfully faster than one-at-a-time, while leaving most of
 * the pool free for concurrent traffic regardless of how large a selection
 * grows.
 */
export const BULK_ACTION_CONCURRENCY = 4;

/**
 * Runs `worker` over `items` with at most `limit` in flight at once.
 * Order-preserving: `results[i]` corresponds to `items[i]` regardless of
 * completion order. Never rejects itself — that's `worker`'s job to avoid
 * (see decideOne below), since a bulk action's whole point is that one
 * item's failure must not stop or lose the others.
 */
export async function runWithConcurrencyLimit<T, R>(
  items: T[],
  limit: number,
  worker: (item: T) => Promise<R>,
): Promise<R[]> {
  const results: R[] = new Array(items.length);
  let nextIndex = 0;

  async function runNext(): Promise<void> {
    for (;;) {
      const currentIndex = nextIndex++;
      if (currentIndex >= items.length) {
        return;
      }
      results[currentIndex] = await worker(items[currentIndex]);
    }
  }

  const workerCount = Math.min(limit, items.length);
  await Promise.all(Array.from({ length: workerCount }, () => runNext()));
  return results;
}

/**
 * One item's approve/reject attempt. Goes through the same apiFetch/ApiError
 * path as every other request in this app (Task 1) — no second error path.
 * ApiError already carries everything a per-item outcome needs (status,
 * parsed ProblemDetail.detail); the only thing it doesn't carry is *which
 * row* failed, and that's naturally known here, by the caller, not
 * something ApiError itself needs to be extended to hold.
 */
async function decideOne(id: number, action: ApprovalAction): Promise<BulkActionOutcome> {
  try {
    const expense = action === 'approve' ? await approveExpense(id) : await rejectExpense(id);
    return { id, outcome: 'success', expense };
  } catch (err) {
    if (err instanceof ApiError) {
      return { id, outcome: 'error', httpStatus: err.status, detail: err.problem?.detail ?? err.message };
    }
    return { id, outcome: 'error', httpStatus: 0, detail: err instanceof Error ? err.message : 'Unknown error' };
  }
}

export function runBulkAction(ids: number[], action: ApprovalAction): Promise<BulkActionOutcome[]> {
  return runWithConcurrencyLimit(ids, BULK_ACTION_CONCURRENCY, (id) => decideOne(id, action));
}

/**
 * Approvals-queue sort order, matching the backend's own
 * `ORDER BY submitted_at DESC, id DESC` (docs/adr/0002-keyset-pagination.md).
 * Used only to reinsert a failed row back into its page at the right spot —
 * see reinsertFailed below — never to decide anything about the cursor
 * itself.
 */
function compareApprovalOrder(a: ExpenseResponse, b: ExpenseResponse): number {
  const aTime = a.submittedAt ?? '';
  const bTime = b.submittedAt ?? '';
  if (aTime !== bTime) {
    return aTime > bTime ? -1 : 1;
  }
  return b.id - a.id;
}

/** Inserts `item` into `content` at its correct sorted position. */
export function insertSorted(content: ExpenseResponse[], item: ExpenseResponse): ExpenseResponse[] {
  const insertAt = content.findIndex((existing) => compareApprovalOrder(item, existing) < 0);
  if (insertAt === -1) {
    return [...content, item];
  }
  return [...content.slice(0, insertAt), item, ...content.slice(insertAt)];
}
