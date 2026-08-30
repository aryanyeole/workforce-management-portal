import { apiFetch } from './client';
import type { AuthResponse, LoginRequest, MeResponse, RefreshRequest } from './types';

export function login(request: LoginRequest): Promise<AuthResponse> {
  return apiFetch<AuthResponse>('/api/v1/auth/login', {
    method: 'POST',
    body: JSON.stringify(request),
  });
}

export function refresh(request: RefreshRequest): Promise<AuthResponse> {
  return apiFetch<AuthResponse>('/api/v1/auth/refresh', {
    method: 'POST',
    body: JSON.stringify(request),
  });
}

export function me(): Promise<MeResponse> {
  return apiFetch<MeResponse>('/api/v1/auth/me');
}
