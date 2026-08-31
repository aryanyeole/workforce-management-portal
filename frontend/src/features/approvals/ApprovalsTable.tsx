import { useInfiniteQuery } from '@tanstack/react-query';
import { useVirtualizer } from '@tanstack/react-virtual';
import { useEffect, useRef } from 'react';
import { ApiError } from '../../api/client';
import { fetchApprovalsPage } from '../../api/expenses';
import type { ExpenseResponse } from '../../api/types';

function formatAmount(amountCents: number, currency: string): string {
  return new Intl.NumberFormat(undefined, { style: 'currency', currency }).format(amountCents / 100);
}

function formatSubmittedAt(submittedAt: string | null): string {
  return submittedAt ? new Date(submittedAt).toLocaleString() : '—';
}

// Matches .approvals-row's fixed height in index.css — react-virtual needs
// a size estimate up front; every row here is the same fixed height, so
// this is exact, not an estimate.
const ROW_HEIGHT_PX = 37;
// How many rows from the end of the currently-loaded set triggers the next
// fetch — see the component doc comment for why this replaces a sentinel.
const FETCH_NEXT_PAGE_THRESHOLD = 8;

const GRID_TEMPLATE_COLUMNS = '90px 100px 160px 120px 1fr 200px';

function ApprovalRow({ expense }: { expense: ExpenseResponse }) {
  return (
    <>
      <div role="cell">{expense.id}</div>
      <div role="cell">#{expense.employeeId}</div>
      <div role="cell">{expense.categoryName}</div>
      <div role="cell">{formatAmount(expense.amountCents, expense.currency)}</div>
      <div role="cell" className="approvals-cell-description">
        {expense.description}
      </div>
      <div role="cell">{formatSubmittedAt(expense.submittedAt)}</div>
    </>
  );
}

/**
 * Cursor-driven infinite scroll over GET /api/v1/expenses/approvals,
 * virtualized (Phase 9 Task 2b — see docs/measurements.md for the
 * before/after this exists to fix: Task 2's unvirtualized version kept
 * every fetched row mounted forever, and wall-clock cost per batch grew
 * ~13x from the first few thousand rows alone).
 *
 * useInfiniteQuery and the cursor contract are untouched from Task 2 —
 * `pageParam` is still the opaque cursor, nothing here decodes it, and
 * only the rendering layer changed.
 *
 * The next-page fetch is driven by the virtualizer's own rendered index
 * range (the last virtual item's index approaching the end of the
 * currently-loaded rows), not a sentinel DOM element. A sentinel placed
 * after react-virtual's sized spacer — the approach Task 2 used — was
 * checked, not assumed to break: since the spacer's total height is sized
 * to exactly `rows.length` (not padded with an extra placeholder row), a
 * sentinel immediately after it stays a normal, always-mounted DOM node
 * and does get scrolled into view correctly. It isn't broken by
 * virtualization here. The rendered-range approach was still used because
 * it's the pattern react-virtual's own docs recommend for this and avoids
 * relying on a spacer's exact sizing behavior as an implementation detail.
 */
export function ApprovalsTable() {
  const { data, error, isPending, isError, fetchNextPage, hasNextPage, isFetchingNextPage } = useInfiniteQuery({
    queryKey: ['approvals'],
    queryFn: ({ pageParam }) => fetchApprovalsPage(pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (lastPage) => lastPage.nextCursor,
  });

  const rows = data ? data.pages.flatMap((page) => page.content) : [];

  const scrollContainerRef = useRef<HTMLDivElement | null>(null);

  const virtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => scrollContainerRef.current,
    estimateSize: () => ROW_HEIGHT_PX,
    overscan: 12,
  });

  const virtualItems = virtualizer.getVirtualItems();
  const lastVirtualIndex = virtualItems.length > 0 ? virtualItems[virtualItems.length - 1].index : -1;

  useEffect(() => {
    if (lastVirtualIndex < 0 || !hasNextPage || isFetchingNextPage) {
      return;
    }
    if (lastVirtualIndex >= rows.length - FETCH_NEXT_PAGE_THRESHOLD) {
      fetchNextPage();
    }
  }, [lastVirtualIndex, rows.length, hasNextPage, isFetchingNextPage, fetchNextPage]);

  if (isPending) {
    return <p className="approvals-status">Loading approvals…</p>;
  }

  if (isError) {
    const message = error instanceof ApiError ? (error.problem?.detail ?? error.message) : 'Could not load approvals.';
    return (
      <p className="approvals-status approvals-error" role="alert">
        {message}
      </p>
    );
  }

  if (rows.length === 0) {
    // An approver with nothing pending is a real, valid state — not an
    // error and not a loading state that never resolved.
    return <p className="approvals-status">No pending approvals.</p>;
  }

  return (
    <div className="approvals-table-wrap" role="table" data-testid="approvals-table">
      <div className="approvals-header-row" role="row" style={{ gridTemplateColumns: GRID_TEMPLATE_COLUMNS }}>
        <div role="columnheader">ID</div>
        <div role="columnheader">Employee</div>
        <div role="columnheader">Category</div>
        <div role="columnheader">Amount</div>
        <div role="columnheader">Description</div>
        <div role="columnheader">Submitted</div>
      </div>

      <div ref={scrollContainerRef} className="approvals-scroll-container">
        <div style={{ height: virtualizer.getTotalSize(), position: 'relative' }}>
          {virtualItems.map((virtualItem) => {
            const expense = rows[virtualItem.index];
            return (
              <div
                key={expense.id}
                data-row-id={expense.id}
                role="row"
                className="approvals-row"
                style={{
                  gridTemplateColumns: GRID_TEMPLATE_COLUMNS,
                  position: 'absolute',
                  top: 0,
                  left: 0,
                  width: '100%',
                  height: virtualItem.size,
                  transform: `translateY(${virtualItem.start}px)`,
                }}
              >
                <ApprovalRow expense={expense} />
              </div>
            );
          })}
        </div>
      </div>

      <div className="approvals-sentinel">
        {isFetchingNextPage && <span>Loading more…</span>}
        {!hasNextPage && <span>End of list — {rows.length} total shown.</span>}
      </div>
    </div>
  );
}
