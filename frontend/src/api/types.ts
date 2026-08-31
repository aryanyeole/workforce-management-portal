/**
 * DTO shapes mirrored by hand from the backend's actual records
 * (com.aryanyeole.wmp.auth.api.*, com.aryanyeole.wmp.auth.domain.RoleCode) —
 * no codegen, kept in sync manually. If these drift from the backend, the
 * fix is here, not a workaround in a component.
 */

export type RoleCode = 'EMPLOYEE' | 'MANAGER' | 'PAYROLL_ADMIN' | 'HR_ADMIN' | 'SYSTEM';

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RefreshRequest {
  refreshToken: string;
}

/** Response shape shared by /login and /refresh — see AuthResponse.java. */
export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
  role: RoleCode;
  employeeId: number | null;
}

export interface MeResponse {
  userAccountId: number;
  employeeId: number | null;
  email: string;
  role: RoleCode;
}

/**
 * RFC 7807 — the shape GlobalExceptionHandler's single @RestControllerAdvice
 * returns for every error response (CLAUDE.md convention #3). Fields are
 * all optional here because a non-JSON or malformed error body should
 * still parse to *something* rather than throw while handling an error.
 */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
}

export type ExpenseStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'REJECTED';

/** See com.aryanyeole.wmp.expense.api.ExpenseResponse. */
export interface ExpenseResponse {
  id: number;
  employeeId: number;
  categoryId: number;
  categoryName: string;
  amountCents: number;
  currency: string;
  description: string;
  status: ExpenseStatus;
  submittedAt: string | null;
  approverId: number | null;
  approvedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

/**
 * Envelope for keyset-paginated endpoints — see
 * com.aryanyeole.wmp.common.api.CursorPageResponse and
 * docs/adr/0002-keyset-pagination.md. `nextCursor` is opaque by contract:
 * this app only ever passes it back verbatim as the next request's
 * `cursor` param, never decodes or inspects it (see src/api/expenses.ts).
 */
export interface CursorPageResponse<T> {
  content: T[];
  nextCursor: string | null;
}

/** See com.aryanyeole.wmp.expense.api.ExpenseCategoryResponse. */
export interface ExpenseCategoryResponse {
  id: number;
  name: string;
  description: string | null;
}

/** See com.aryanyeole.wmp.expense.api.CreateExpenseRequest. */
export interface CreateExpenseRequest {
  categoryId: number;
  amountCents: number;
  currency?: string;
  description?: string;
}

/**
 * Envelope for classic offset-paginated (page/size) endpoints — see
 * com.aryanyeole.wmp.common.api.PageResponse. Distinct from
 * CursorPageResponse above: this one carries page/totalPages because the
 * lists it fronts (own expenses, the onboarding employee directory) are a
 * few hundred rows at most, not the 21k-row approvals queue keyset exists
 * for — see docs/adr/0002-keyset-pagination.md's "what offset pagination
 * is still better at."
 */
export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export type EmploymentStatus = 'PENDING' | 'ACTIVE' | 'ON_LEAVE' | 'TERMINATED';

/** See com.aryanyeole.wmp.onboarding.api.EmployeeResponse. */
export interface EmployeeResponse {
  id: number;
  departmentId: number | null;
  departmentName: string | null;
  managerId: number | null;
  firstName: string;
  lastName: string;
  email: string;
  hireDate: string;
  employmentStatus: EmploymentStatus;
  createdAt: string;
  updatedAt: string;
}

export type OnboardingTaskStatus = 'PENDING' | 'IN_PROGRESS' | 'COMPLETED';

/** See com.aryanyeole.wmp.onboarding.api.TaskResponse. */
export interface TaskResponse {
  id: number;
  employeeId: number;
  title: string;
  description: string | null;
  status: OnboardingTaskStatus;
  dueDate: string | null;
  completedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

/** See com.aryanyeole.wmp.onboarding.api.CreateTaskRequest. */
export interface CreateTaskRequest {
  title: string;
  description?: string;
  dueDate?: string;
}

export type OnboardingDocumentStatus = 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED';

/** See com.aryanyeole.wmp.onboarding.api.DocumentResponse — metadata only. */
export interface DocumentResponse {
  id: number;
  employeeId: number;
  documentType: string;
  fileName: string;
  contentType: string;
  fileSizeBytes: number;
  status: OnboardingDocumentStatus;
  uploadedAt: string;
}
