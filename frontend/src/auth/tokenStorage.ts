/**
 * Token storage: localStorage, chosen deliberately over an in-memory
 * variable — the trade-off is real and is written down here rather than
 * just picked.
 *
 * localStorage persists across a page reload, so a reviewer clicking
 * through this app doesn't get signed out every time the page refreshes.
 * The cost: anything running as JavaScript on this page — including an XSS
 * payload, if one were ever injected — can read these tokens. In-memory
 * storage (a module-level variable, nothing written to disk) would close
 * that specific hole, but the session would not survive a reload at all;
 * recovering it would need a refresh token the browser still has, which
 * means either storing it somewhere that *does* survive reload (the same
 * trade-off, one level down) or an httpOnly-cookie-backed refresh flow —
 * and the backend is frozen for this phase, so inventing one now would be
 * dodging this choice, not making it (see the Phase 9 Task 1 report).
 *
 * React escapes rendered content by default and nothing in this app
 * currently renders raw HTML from user input, so the practical exposure
 * today is low — but the trade-off is accepted, not eliminated, and worth
 * revisiting if that ever changes.
 */
const ACCESS_TOKEN_KEY = 'wmp.accessToken';
const REFRESH_TOKEN_KEY = 'wmp.refreshToken';

export function getAccessToken(): string | null {
  return localStorage.getItem(ACCESS_TOKEN_KEY);
}

export function getRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_TOKEN_KEY);
}

export function setTokens(accessToken: string, refreshToken: string): void {
  localStorage.setItem(ACCESS_TOKEN_KEY, accessToken);
  localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken);
}

export function clearTokens(): void {
  localStorage.removeItem(ACCESS_TOKEN_KEY);
  localStorage.removeItem(REFRESH_TOKEN_KEY);
}
