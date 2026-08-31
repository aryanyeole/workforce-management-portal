import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { fetchEmployees, EMPLOYEES_PAGE_SIZE } from '../../api/onboarding';
import { ApiError } from '../../api/client';

/**
 * GET /api/v1/onboarding/employees — offset-paginated (page/size), not
 * keyset: VisibilityScope already bounds this to at most a department or
 * two for a MANAGER, or the whole company for HR_ADMIN — a few hundred
 * rows at the very most, nothing like the approvals queue. Plain
 * previous/next paging, no virtualization (Task 4 point 4).
 */
export function OnboardingEmployeeList({ onSelect }: { onSelect: (employeeId: number) => void }) {
  const [page, setPage] = useState(0);
  const query = useQuery({
    queryKey: ['employees', page],
    queryFn: () => fetchEmployees(page),
  });

  if (query.isPending) {
    return <p className="approvals-status">Loading employees…</p>;
  }
  if (query.isError) {
    const message = query.error instanceof ApiError ? (query.error.problem?.detail ?? query.error.message) : 'Could not load employees.';
    return (
      <p className="approvals-status approvals-error" role="alert">
        {message}
      </p>
    );
  }

  const { content, totalPages } = query.data;

  return (
    <div>
      <h2>Employees</h2>
      {content.length === 0 ? (
        <p className="approvals-status">No employees visible to your account.</p>
      ) : (
        <table className="expenses-table">
          <thead>
            <tr>
              <th>ID</th>
              <th>Name</th>
              <th>Email</th>
              <th>Department</th>
              <th>Status</th>
              <th>Hired</th>
            </tr>
          </thead>
          <tbody>
            {content.map((employee) => (
              <tr key={employee.id} className="onboarding-employee-row" onClick={() => onSelect(employee.id)}>
                <td>{employee.id}</td>
                <td>
                  {employee.firstName} {employee.lastName}
                </td>
                <td>{employee.email}</td>
                <td>{employee.departmentName ?? '—'}</td>
                <td>{employee.employmentStatus}</td>
                <td>{employee.hireDate}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <div className="expenses-pagination">
        <button type="button" onClick={() => setPage((p) => p - 1)} disabled={page === 0}>
          Previous
        </button>
        <span>
          Page {query.data.page + 1} of {Math.max(totalPages, 1)} ({query.data.totalElements} total, {EMPLOYEES_PAGE_SIZE}/page)
        </span>
        <button type="button" onClick={() => setPage((p) => p + 1)} disabled={page + 1 >= totalPages}>
          Next
        </button>
      </div>
    </div>
  );
}
