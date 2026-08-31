import type { InfiniteData } from '@tanstack/react-query';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { CursorPageResponse, ExpenseResponse } from '../../api/types';
import type { ApprovalAction, BulkActionOutcome } from './bulkActions';
import { insertSorted, runBulkAction } from './bulkActions';

type ApprovalsData = InfiniteData<CursorPageResponse<ExpenseResponse>>;

interface MutationVariables {
  ids: number[];
  action: ApprovalAction;
}

/** Where an optimistically-removed row came from, so a failed one can be
 * put back in the right page rather than just appended somewhere. */
interface RemovedEntry {
  pageIndex: number;
  item: ExpenseResponse;
}

type MutationContext = {
  removed: Map<number, RemovedEntry>;
};

/**
 * Bulk approve/reject over the virtualized, cursor-paginated approvals
 * queue (Phase 9 Task 3).
 *
 * Optimistic update: selected rows disappear from the list the instant the
 * action is triggered (onMutate), before any network response comes back.
 * When results land, only the rows that actually failed (a 409, most
 * commonly — someone else already decided it) are put back; the ones that
 * succeeded stay gone. This is "rollback of exactly the failed subset,"
 * not the whole batch, done by recomputing from a snapshot of what was
 * removed rather than by trying to undo a partial optimistic change.
 *
 * Cache strategy: a surgical `setQueryData` edit of each page's `content`
 * array, never `invalidateQueries`/refetch. `nextCursor` on every page is
 * left untouched — react-virtual/useInfiniteQuery only ever read
 * `content` for rendering and `nextCursor` for "what to fetch next," and
 * since `nextCursor` is computed from the *server's* last raw row (not
 * this client's filtered array), removing decided rows from `content`
 * cannot desync a future `fetchNextPage()` call. Invalidating and
 * refetching the whole 20,976-row scrolled list instead would mean
 * re-walking every page from cursor null again just to reflect a handful
 * of decided rows -- the cost this approach avoids.
 */
export function useBulkApprovalMutation() {
  const queryClient = useQueryClient();

  return useMutation<BulkActionOutcome[], unknown, MutationVariables, MutationContext>({
    mutationFn: ({ ids, action }) => runBulkAction(ids, action),

    onMutate: async ({ ids }) => {
      // Stop any in-flight approvals fetch from clobbering the optimistic
      // edit below with a response that still includes the rows we're
      // about to remove.
      await queryClient.cancelQueries({ queryKey: ['approvals'] });

      const idSet = new Set(ids);
      const removed = new Map<number, RemovedEntry>();

      queryClient.setQueryData<ApprovalsData>(['approvals'], (old) => {
        if (!old) {
          return old;
        }
        const pages = old.pages.map((page, pageIndex) => {
          const content = page.content.filter((item) => {
            if (!idSet.has(item.id)) {
              return true;
            }
            removed.set(item.id, { pageIndex, item });
            return false;
          });
          return content.length === page.content.length ? page : { ...page, content };
        });
        return { ...old, pages };
      });

      return { removed };
    },

    onSuccess: (results, _variables, context) => {
      const failed = results.filter((r) => r.outcome === 'error');
      if (failed.length === 0 || !context) {
        return;
      }

      queryClient.setQueryData<ApprovalsData>(['approvals'], (old) => {
        if (!old) {
          return old;
        }
        const pages = old.pages.map((page, pageIndex) => {
          let content = page.content;
          for (const outcome of failed) {
            const entry = context.removed.get(outcome.id);
            // Pages only ever get appended by useInfiniteQuery (forward
            // scroll), never reordered or spliced out, so a pageIndex
            // recorded at onMutate time still names the same logical page
            // here -- reinsert into that same index, at the row's correct
            // sorted position (not its old array index, which may no
            // longer exist if a sibling on the same page also failed or
            // succeeded and shifted things).
            if (entry && entry.pageIndex === pageIndex) {
              content = insertSorted(content, entry.item);
            }
          }
          return content === page.content ? page : { ...page, content };
        });
        return { ...old, pages };
      });
    },

    onError: (_error, _variables, context) => {
      // mutationFn/runBulkAction catch every per-item error internally and
      // never reject -- reaching here means something outside that (e.g.
      // cancelQueries itself throwing) went wrong in a way this feature
      // never expects. There's no reliable partial-outcome data to
      // reconcile against in that case, so fall back to putting back
      // everything this mutation removed, rather than risk leaving rows
      // missing with no explanation. This is the one place a full
      // reconciliation happens, and only as a last resort.
      if (!context) {
        return;
      }
      queryClient.setQueryData<ApprovalsData>(['approvals'], (old) => {
        if (!old) {
          return old;
        }
        const pages = old.pages.map((page, pageIndex) => {
          let content = page.content;
          for (const entry of context.removed.values()) {
            if (entry.pageIndex === pageIndex) {
              content = insertSorted(content, entry.item);
            }
          }
          return content === page.content ? page : { ...page, content };
        });
        return { ...old, pages };
      });
    },
  });
}
