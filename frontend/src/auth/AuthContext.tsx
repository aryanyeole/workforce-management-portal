import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { login as loginRequest, me as meRequest } from '../api/auth';
import { setOnSessionExpired } from '../api/client';
import type { MeResponse } from '../api/types';
import { clearTokens, getAccessToken, setTokens } from './tokenStorage';

type AuthStatus = 'loading' | 'authenticated' | 'anonymous';

interface AuthContextValue {
  user: MeResponse | null;
  status: AuthStatus;
  login: (email: string, password: string) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<MeResponse | null>(null);
  const [status, setStatus] = useState<AuthStatus>('loading');

  const logout = useCallback(() => {
    clearTokens();
    setUser(null);
    setStatus('anonymous');
  }, []);

  // Registered once so apiFetch can force a fall-back to login on a failed
  // refresh without importing this context directly — see client.ts.
  useEffect(() => {
    setOnSessionExpired(logout);
  }, [logout]);

  useEffect(() => {
    // Restore the session on reload if a token survived (see
    // tokenStorage.ts's localStorage trade-off) — but always re-hydrate
    // from /me rather than trusting anything client-side, since the token
    // could have expired or the account could have been deactivated since
    // the last page load.
    if (!getAccessToken()) {
      setStatus('anonymous');
      return;
    }
    meRequest()
      .then((fetchedUser) => {
        setUser(fetchedUser);
        setStatus('authenticated');
      })
      .catch(() => {
        // A 401 here already ran onSessionExpired via apiFetch; this catch
        // just guards against any other failure leaving status on
        // "loading" forever.
        setStatus('anonymous');
      });
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    const auth = await loginRequest({ email, password });
    setTokens(auth.accessToken, auth.refreshToken);
    const fetchedUser = await meRequest();
    setUser(fetchedUser);
    setStatus('authenticated');
  }, []);

  return <AuthContext.Provider value={{ user, status, login, logout }}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}
