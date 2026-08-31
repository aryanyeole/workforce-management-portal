import { useInfiniteQuery } from '@tanstack/react-query';
import { useEffect, useRef } from 'react';
import { fetchApprovalsPage } from '../../api/expenses';
import { ApiError } from '../../api/client';

function formatAmount(amountCents: number, currency: string): string {
  return new Intl.NumberFormat(undefined, { style: 'currency', currency }).format(amountCents / 100);
}

function formatSubmittedAt(submittedAt: string | null): string {
  return submittedAt ? new Date(submittedAt).toLocaleString() : '—';
}

/**
 * Cursor-driven infinite scroll over GET /api/v1/expenses/approvals — the
 * reason keyset pagination exists (docs/adr/0002-keyset-pagination.md).
 *
 * useInfiniteQuery's own `pageParam` IS the cursor: TanStack Query tracks
 * the sequence of cursors internally so this component never has to. There
 * is no page number anywhere in this file, and nothing here decodes
 * `nextCursor` — it is only ever read as "is there one" (truthy check via
 * `hasNextPage`) and passed back verbatim as the next request's `cursor`.
 */
export function ApprovalsTable() {
  const { data, error, isPending, isError, fetchNextPage, hasNextPage, isFetchingNextPage } = useInfiniteQuery({
    queryKey: ['approvals'],
    queryFn: ({ pageParam }) => fetchApprovalsPage(pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (lastPage) => lastPage.nextCursor,
  });

  const sentinelRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const sentinel = sentinelRef.current;
    if (!sentinel || !hasNextPage) {
      return;
    }
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting && !isFetchingNextPage) {
          fetchNextPage();
        }
      },
      // Fires the next fetch a bit before the sentinel is actually on
      // screen, so a fast scroller doesn't see a visible gap while it loads.
      { rootMargin: '600px' },
    );
    observer.observe(sentinel);
    return () => observer.disconnect();
  }, [hasNextPage, isFetchingNextPage, fetchNextPage]);

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

  const rows = data.pages.flatMap((page) => page.content);

  if (rows.length === 0) {
    // An approver with nothing pending is a real, valid state — not an
    // error and not a loading state that never resolved.
    return <p className="approvals-status">No pending approvals.</p>;
  }

  return (
    <div className="approvals-table-wrap">
      <table className="approvals-table" data-testid="approvals-table">
        <thead>
          <tr>
            <th>ID</th>
            <th>Employee</th>
            <th>Category</th>
            <th>Amount</th>
            <th>Description</th>
            <th>Submitted</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((expense) => (
            <tr key={expense.id} data-row-id={expense.id}>
              <td>{expense.id}</td>
              <td>#{expense.employeeId}</td>
              <td>{expense.categoryName}</td>
              <td>{formatAmount(expense.amountCents, expense.currency)}</td>
              <td>{expense.description}</td>
              <td>{formatSubmittedAt(expense.submittedAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>

      <div ref={sentinelRef} className="approvals-sentinel">
        {isFetchingNextPage && <span>Loading more…</span>}
        {!hasNextPage && <span>End of list — {rows.length} total shown.</span>}
      </div>
    </div>
  );
}
