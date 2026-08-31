import { apiFetch } from './client';
import type { CreateTaskRequest, DocumentResponse, EmployeeResponse, OnboardingTaskStatus, PageResponse, TaskResponse } from './types';

/** Employee directory page size — a few hundred rows at most, plain offset paging. */
export const EMPLOYEES_PAGE_SIZE = 20;

/** GET /api/v1/onboarding/employees. Offset-paginated, VisibilityScope-filtered on the backend. */
export function fetchEmployees(page: number): Promise<PageResponse<EmployeeResponse>> {
  const params = new URLSearchParams({ page: String(page), size: String(EMPLOYEES_PAGE_SIZE) });
  return apiFetch<PageResponse<EmployeeResponse>>(`/api/v1/onboarding/employees?${params.toString()}`);
}

/** GET /api/v1/onboarding/employees/{id}. 404 whether id doesn't exist or is out of the caller's VisibilityScope (ADR 0001). */
export function fetchEmployee(id: number): Promise<EmployeeResponse> {
  return apiFetch<EmployeeResponse>(`/api/v1/onboarding/employees/${id}`);
}

/** GET /api/v1/onboarding/employees/{id}/tasks — plain list, no pagination at all (a handful of tasks per employee). */
export function fetchTasks(employeeId: number): Promise<TaskResponse[]> {
  return apiFetch<TaskResponse[]>(`/api/v1/onboarding/employees/${employeeId}/tasks`);
}

/** POST /api/v1/onboarding/employees/{id}/tasks — route-restricted to HR_ADMIN/MANAGER (PermissionRegistry). */
export function createTask(employeeId: number, request: CreateTaskRequest): Promise<TaskResponse> {
  return apiFetch<TaskResponse>(`/api/v1/onboarding/employees/${employeeId}/tasks`, {
    method: 'POST',
    body: JSON.stringify(request),
  });
}

/**
 * PATCH /api/v1/onboarding/tasks/{taskId} — status only. The backend
 * accepts title/description/dueDate here too, but an EMPLOYEE caller gets
 * a 403 for supplying any of them (OnboardingTaskService.update's own
 * comment); this app only ever sends status, for every role, so it never
 * depends on which role is calling.
 */
export function updateTaskStatus(taskId: number, status: OnboardingTaskStatus): Promise<TaskResponse> {
  return apiFetch<TaskResponse>(`/api/v1/onboarding/tasks/${taskId}`, {
    method: 'PATCH',
    body: JSON.stringify({ status }),
  });
}

/** GET /api/v1/onboarding/employees/{id}/documents — plain list, no pagination. Metadata only, never file contents. */
export function fetchDocuments(employeeId: number): Promise<DocumentResponse[]> {
  return apiFetch<DocumentResponse[]>(`/api/v1/onboarding/employees/${employeeId}/documents`);
}

/**
 * POST /api/v1/onboarding/employees/{id}/documents — multipart/form-data,
 * not JSON (the only upload endpoint in this app). `body` is FormData, so
 * apiFetch/buildRequest must not (and, as of this task, does not) force a
 * JSON Content-Type onto it — see client.ts.
 */
export function uploadDocument(employeeId: number, documentType: string, file: File): Promise<DocumentResponse> {
  const formData = new FormData();
  formData.set('documentType', documentType);
  formData.set('file', file);
  return apiFetch<DocumentResponse>(`/api/v1/onboarding/employees/${employeeId}/documents`, {
    method: 'POST',
    body: formData,
  });
}
