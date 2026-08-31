import { useInfiniteQuery } from '@tanstack/react-query';
import { useVirtualizer } from '@tanstack/react-virtual';
import { useEffect, useMemo, useRef, useState } from 'react';
import { ApiError } from '../../api/client';
import { fetchApprovalsPage } from '../../api/expenses';
import type { ExpenseResponse } from '../../api/types';
import type { BulkActionOutcome } from './bulkActions';
import { useBulkApprovalMutation } from './useBulkApprovalMutation';

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

const GRID_TEMPLATE_COLUMNS = '36px 90px 100px 160px 120px 1fr 200px';

function ApprovalRow({
  expense,
  selected,
  disabled,
  onToggle,
}: {
  expense: ExpenseResponse;
  selected: boolean;
  disabled: boolean;
  onToggle: (id: number) => void;
}) {
  return (
    <>
      <div role="cell" className="approvals-select-cell">
        <input
          type="checkbox"
          checked={selected}
          disabled={disabled}
          onChange={() => onToggle(expense.id)}
          aria-label={`Select expense ${expense.id}`}
        />
      </div>
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

function BulkResultBanner({ results, onDismiss }: { results: BulkActionOutcome[]; onDismiss: () => void }) {
  const succeeded = results.filter((r) => r.outcome === 'success');
  const failed = results.filter((r) => r.outcome === 'error');

  return (
    <div className="approvals-result-banner" role="status">
      <div className="approvals-result-summary">
        <span>
          {succeeded.length} of {results.length} succeeded
          {failed.length > 0 ? `, ${failed.length} failed` : ''}.
        </span>
        <button type="button" onClick={onDismiss}>
          Dismiss
        </button>
      </div>
      {failed.length > 0 && (
        <ul className="approvals-result-failures">
          {failed.map((f) => (
            <li key={f.id}>
              #{f.id}: {f.detail}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/**
 * Cursor-driven infinite scroll over GET /api/v1/expenses/approvals,
 * virtualized (Phase 9 Task 2b), with bulk approve/reject (Phase 9 Task 3).
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
 *
 * Selection (Task 3) is a `Set<number>` of expense IDs held here, at the
 * table's own top level — not inside ApprovalRow or anything the
 * virtualizer mounts/unmounts. A row scrolled out of the rendered window
 * unmounts its component entirely; if selection lived in that component's
 * own state it would silently forget the row was selected the moment it
 * scrolled offscreen. Living here, selection survives regardless of what's
 * currently mounted, exactly like the correctness check in Task 2b had to
 * read from the network/cache rather than the DOM for the same reason.
 *
 * "Select all" only ever means "select all rows loaded into the cache so
 * far" (`rows`, i.e. every page useInfiniteQuery has already fetched) —
 * never "all rows matching the query" across the full 20,976-row server
 * side queue. The button is labeled with the loaded count for exactly this
 * reason: selecting the entire real queue would mean either fetching all
 * 20,976 rows just to select them, or a server-side "select by filter"
 * concept that doesn't exist and isn't in scope here. This UI does not
 * offer that; it offers bulk actions over whatever the admin has already
 * scrolled through.
 */
export function ApprovalsTable() {
  const { data, error, isPending, isError, fetchNextPage, hasNextPage, isFetchingNextPage } = useInfiniteQuery({
    queryKey: ['approvals'],
    queryFn: ({ pageParam }) => fetchApprovalsPage(pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (lastPage) => lastPage.nextCursor,
  });

  const rows = useMemo(() => (data ? data.pages.flatMap((page) => page.content) : []), [data]);

  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());

  const bulkMutation = useBulkApprovalMutation();
  const bulkInFlight = bulkMutation.isPending;

  function toggleRow(id: number): void {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  }

  function selectAllLoaded(): void {
    setSelectedIds(new Set(rows.map((r) => r.id)));
  }

  function clearSelection(): void {
    setSelectedIds(new Set());
  }

  function runBulk(action: 'approve' | 'reject'): void {
    const ids = Array.from(selectedIds);
    if (ids.length === 0 || bulkInFlight) {
      return;
    }
    bulkMutation.mutate(
      { ids, action },
      {
        onSuccess: (results) => {
          const failedIds = new Set(results.filter((r) => r.outcome === 'error').map((r) => r.id));
          // Rows that succeeded are gone from the queue — drop them from
          // selection. Rows that failed (usually a 409: someone else
          // already decided it) are still visible and still selected, so
          // retrying is one click, not re-selecting from scratch.
          setSelectedIds((prev) => new Set(Array.from(prev).filter((id) => failedIds.has(id))));
        },
      },
    );
  }

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

  const allLoadedSelected = selectedIds.size > 0 && rows.every((r) => selectedIds.has(r.id));

  return (
    <div className="approvals-wrap">
      <div className="approvals-toolbar">
        <span className="approvals-toolbar-count">{selectedIds.size} selected</span>
        <button type="button" onClick={selectAllLoaded} disabled={bulkInFlight}>
          Select all loaded ({rows.length})
        </button>
        <button type="button" onClick={clearSelection} disabled={bulkInFlight || selectedIds.size === 0}>
          Clear selection
        </button>
        <button
          type="button"
          className="approvals-toolbar-approve"
          onClick={() => runBulk('approve')}
          disabled={bulkInFlight || selectedIds.size === 0}
        >
          Approve selected
        </button>
        <button
          type="button"
          className="approvals-toolbar-reject"
          onClick={() => runBulk('reject')}
          disabled={bulkInFlight || selectedIds.size === 0}
        >
          Reject selected
        </button>
        {bulkInFlight && <span className="approvals-toolbar-status">Working…</span>}
      </div>

      {bulkMutation.data && !bulkInFlight && (
        <BulkResultBanner results={bulkMutation.data} onDismiss={() => bulkMutation.reset()} />
      )}

      <div className="approvals-table-wrap" role="table" data-testid="approvals-table">
        <div className="approvals-header-row" role="row" style={{ gridTemplateColumns: GRID_TEMPLATE_COLUMNS }}>
          <div role="columnheader" className="approvals-select-cell">
            <input
              type="checkbox"
              checked={allLoadedSelected}
              disabled={bulkInFlight}
              onChange={() => (allLoadedSelected ? clearSelection() : selectAllLoaded())}
              aria-label="Select all loaded rows"
            />
          </div>
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
                  <ApprovalRow
                    expense={expense}
                    selected={selectedIds.has(expense.id)}
                    disabled={bulkInFlight}
                    onToggle={toggleRow}
                  />
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
    </div>
  );
}
