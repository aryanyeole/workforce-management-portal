import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { ApiError } from '../../api/client';
import { createExpense, fetchExpenseCategories, fetchMyExpenses, submitExpense } from '../../api/expenses';
import type { ExpenseResponse } from '../../api/types';

function formatAmount(amountCents: number, currency: string): string {
  return new Intl.NumberFormat(undefined, { style: 'currency', currency }).format(amountCents / 100);
}

function errorDetail(err: unknown, fallback: string): string {
  return err instanceof ApiError ? (err.problem?.detail ?? err.message) : fallback;
}

/**
 * Create (POST /expenses) and submit (POST /expenses/{id}/submit) are kept
 * as two distinct actions on purpose (Task 4 point 1): creating a report
 * only ever produces a DRAFT, visible as such in the list below, with its
 * own separate "Submit" button. There is no single button that does both —
 * the DRAFT state is a real, visible stop, not a hidden implementation
 * detail on the way to SUBMITTED.
 *
 * Client-side validation here mirrors the server's bean validation on
 * CreateExpenseRequest for exactly one field — amount must be positive —
 * and that duplication is deliberate and named, not hidden: `@Positive` on
 * amountCents is about as unlikely-to-drift a rule as validation gets, and
 * the server remains authoritative regardless (a bypassed/wrong client
 * check still gets rejected server-side, and that rejection still surfaces
 * through the same ApiError/ProblemDetail path as everything else — no
 * special-casing for validation errors). categoryId's "must pick one"
 * requirement isn't duplicated at all: the dropdown's only options are
 * exactly what GET /expenses/categories returns, so there is no invalid
 * category id it's possible to submit, and no separate rule to keep in
 * sync. currency isn't exposed as a form field at all — CreateExpenseRequest's
 * own compact constructor already defaults a missing/blank currency to
 * "USD" server-side, so leaving it out entirely (rather than hand-rolling
 * the `[A-Z]{3}` pattern client-side) means one less rule to duplicate.
 * description has no server-side constraint, so none is added here either.
 */
export function MyExpensesView() {
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);

  const categoriesQuery = useQuery({
    queryKey: ['expenseCategories'],
    queryFn: fetchExpenseCategories,
  });
  const myExpensesQuery = useQuery({
    queryKey: ['myExpenses', page],
    queryFn: () => fetchMyExpenses(page),
  });

  const [categoryId, setCategoryId] = useState('');
  const [amountDollars, setAmountDollars] = useState('');
  const [description, setDescription] = useState('');
  const [formError, setFormError] = useState<string | null>(null);

  const createMutation = useMutation({
    mutationFn: createExpense,
    onSuccess: () => {
      setCategoryId('');
      setAmountDollars('');
      setDescription('');
      setFormError(null);
      queryClient.invalidateQueries({ queryKey: ['myExpenses'] });
    },
    onError: (err) => setFormError(errorDetail(err, 'Could not create expense report.')),
  });

  const submitMutation = useMutation({
    mutationFn: submitExpense,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['myExpenses'] }),
  });

  function handleCreate(e: React.FormEvent): void {
    e.preventDefault();
    setFormError(null);

    if (!categoryId) {
      setFormError('Choose a category.');
      return;
    }
    const amount = Number.parseFloat(amountDollars);
    if (!Number.isFinite(amount) || amount <= 0) {
      setFormError('Amount must be a positive number.');
      return;
    }

    createMutation.mutate({
      categoryId: Number(categoryId),
      amountCents: Math.round(amount * 100),
      description: description || undefined,
    });
  }

  return (
    <div className="expenses-wrap">
      <section className="expenses-form-section">
        <h2>New expense report</h2>
        <form className="expenses-form" onSubmit={handleCreate}>
          <label>
            Category
            <select value={categoryId} onChange={(e) => setCategoryId(e.target.value)} disabled={categoriesQuery.isPending}>
              <option value="">Select a category…</option>
              {categoriesQuery.data?.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
          </label>
          <label>
            Amount (USD)
            <input
              type="number"
              min="0.01"
              step="0.01"
              value={amountDollars}
              onChange={(e) => setAmountDollars(e.target.value)}
              placeholder="0.00"
            />
          </label>
          <label>
            Description
            <textarea value={description} onChange={(e) => setDescription(e.target.value)} rows={2} />
          </label>
          {formError && (
            <p className="expenses-form-error" role="alert">
              {formError}
            </p>
          )}
          <button type="submit" disabled={createMutation.isPending}>
            {createMutation.isPending ? 'Creating…' : 'Create draft'}
          </button>
        </form>
      </section>

      <section className="expenses-list-section">
        <h2>My expense reports</h2>
        {myExpensesQuery.isPending && <p className="approvals-status">Loading…</p>}
        {myExpensesQuery.isError && (
          <p className="approvals-status approvals-error" role="alert">
            {errorDetail(myExpensesQuery.error, 'Could not load expense reports.')}
          </p>
        )}
        {myExpensesQuery.data && (
          <>
            {myExpensesQuery.data.content.length === 0 ? (
              <p className="approvals-status">No expense reports yet.</p>
            ) : (
              <table className="expenses-table">
                <thead>
                  <tr>
                    <th>ID</th>
                    <th>Category</th>
                    <th>Amount</th>
                    <th>Description</th>
                    <th>Status</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  {myExpensesQuery.data.content.map((expense: ExpenseResponse) => (
                    <tr key={expense.id}>
                      <td>{expense.id}</td>
                      <td>{expense.categoryName}</td>
                      <td>{formatAmount(expense.amountCents, expense.currency)}</td>
                      <td>{expense.description}</td>
                      <td>
                        <span className={`expenses-status-badge expenses-status-${expense.status.toLowerCase()}`}>
                          {expense.status}
                        </span>
                      </td>
                      <td>
                        {expense.status === 'DRAFT' && (
                          <button
                            type="button"
                            onClick={() => submitMutation.mutate(expense.id)}
                            disabled={submitMutation.isPending}
                          >
                            Submit
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            {submitMutation.isError && (
              <p className="expenses-form-error" role="alert">
                {errorDetail(submitMutation.error, 'Could not submit expense report.')}
              </p>
            )}
            <div className="expenses-pagination">
              <button type="button" onClick={() => setPage((p) => p - 1)} disabled={page === 0}>
                Previous
              </button>
              <span>
                Page {myExpensesQuery.data.page + 1} of {Math.max(myExpensesQuery.data.totalPages, 1)}
              </span>
              <button
                type="button"
                onClick={() => setPage((p) => p + 1)}
                disabled={page + 1 >= myExpensesQuery.data.totalPages}
              >
                Next
              </button>
            </div>
          </>
        )}
      </section>
    </div>
  );
}
