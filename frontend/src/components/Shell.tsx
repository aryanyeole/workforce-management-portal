import type { ReactNode } from 'react';
import type { RoleCode } from '../api/types';
import { useAuth } from '../auth/AuthContext';

/**
 * Nav links, keyed by which roles see them.
 *
 * This is presentation only. Hiding a link here authorizes nothing — the
 * actual boundary is RouteAuthorizationFilter + PermissionRegistry on the
 * backend (ADR 0001), and every one of these routes is enforced there
 * regardless of what this component renders. A role that isn't shown a
 * link could still hit the underlying endpoint directly and would get the
 * exact same 403 anyone else without permission would; a role that *is*
 * shown a link gets nothing extra from that — the backend checks again on
 * every request either way. Treat this list as a menu, not a lock.
 */
const NAV_ITEMS: { label: string; roles: RoleCode[] }[] = [
  { label: 'My Expenses', roles: ['EMPLOYEE', 'MANAGER', 'PAYROLL_ADMIN', 'HR_ADMIN'] },
  { label: 'Approvals', roles: ['MANAGER', 'PAYROLL_ADMIN'] },
  { label: 'Payroll', roles: ['PAYROLL_ADMIN'] },
  { label: 'Onboarding', roles: ['HR_ADMIN', 'MANAGER'] },
];

export function Shell({ children }: { children: ReactNode }) {
  const { user, logout } = useAuth();
  const visibleItems = NAV_ITEMS.filter((item) => user && item.roles.includes(user.role));

  return (
    <div className="shell">
      <header className="shell-header">
        <span className="shell-title">Workforce Management Portal</span>
        {user && (
          <span className="shell-user">
            {user.email} &middot; {user.role}
            <button type="button" onClick={logout}>
              Sign out
            </button>
          </span>
        )}
      </header>
      <div className="shell-body">
        <nav className="shell-nav">
          <ul>
            {visibleItems.map((item) => (
              <li key={item.label}>{item.label}</li>
            ))}
          </ul>
        </nav>
        <main className="shell-main">{children}</main>
      </div>
    </div>
  );
}
