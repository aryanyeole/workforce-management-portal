import { clearTokens, getAccessToken, getRefreshToken, setTokens } from '../auth/tokenStorage';
import type { AuthResponse, ProblemDetail } from './types';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

/**
 * Thrown by apiFetch on any non-2xx response. `problem` is the parsed
 * RFC 7807 body when the server returned one (which it always does — see
 * GlobalExceptionHandler) and null only when the response genuinely wasn't
 * JSON (a network-layer failure, a proxy's own error page, etc.) — callers
 * check `problem` rather than re-parsing `response` themselves.
 */
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly problem: ProblemDetail | null,
  ) {
    super(problem?.detail ?? `Request failed with status ${status}`);
    this.name = 'ApiError';
  }
}

/**
 * Called once, by AuthProvider, so the API client can trigger "fall back to
 * login" without importing React/context itself — this file has no
 * business knowing about component state, only that a session ended.
 */
let onSessionExpired: (() => void) | null = null;
export function setOnSessionExpired(callback: () => void): void {
  onSessionExpired = callback;
}

async function parseProblemDetail(response: Response): Promise<ProblemDetail | null> {
  const contentType = response.headers.get('content-type') ?? '';
  if (!contentType.includes('json')) {
    return null;
  }
  try {
    return (await response.json()) as ProblemDetail;
  } catch {
    return null;
  }
}

function buildRequest(init: RequestInit, accessToken: string | null): RequestInit {
  const headers = new Headers(init.headers);
  if (accessToken) {
    headers.set('Authorization', `Bearer ${accessToken}`);
  }
  if (init.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  return { ...init, headers };
}

// De-dupes concurrent refresh attempts: several requests can 401 at once
// (e.g. a page firing off a few queries on mount with an expired token) and
// they must not each independently spend the one refresh token.
let refreshPromise: Promise<string | null> | null = null;

async function refreshAccessToken(): Promise<string | null> {
  const refreshToken = getRefreshToken();
  if (!refreshToken) {
    return null;
  }

  if (!refreshPromise) {
    refreshPromise = (async () => {
      try {
        const response = await fetch(`${API_BASE_URL}/api/v1/auth/refresh`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken }),
        });
        if (!response.ok) {
          return null;
        }
        const auth = (await response.json()) as AuthResponse;
        setTokens(auth.accessToken, auth.refreshToken);
        return auth.accessToken;
      } catch {
        return null;
      } finally {
        refreshPromise = null;
      }
    })();
  }
  return refreshPromise;
}

/**
 * The one place every request in this app goes through. Three jobs, and
 * only these three — every later feature is expected to call this rather
 * than `fetch` directly:
 *
 * 1. Attaches the bearer token to every request.
 * 2. On a 401, attempts exactly one refresh and retries the request once;
 *    if the refresh itself fails, clears the session and calls
 *    onSessionExpired so the UI falls back to the login screen.
 * 3. On any other non-2xx response, parses the RFC 7807 body and throws an
 *    ApiError carrying it, so no component ever hand-parses an error body.
 */
export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  let accessToken = getAccessToken();
  let response = await fetch(`${API_BASE_URL}${path}`, buildRequest(init, accessToken));

  if (response.status === 401) {
    accessToken = await refreshAccessToken();
    if (accessToken) {
      response = await fetch(`${API_BASE_URL}${path}`, buildRequest(init, accessToken));
    } else {
      clearTokens();
      onSessionExpired?.();
      throw new ApiError(401, await parseProblemDetail(response));
    }
  }

  if (!response.ok) {
    throw new ApiError(response.status, await parseProblemDetail(response));
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}
