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
