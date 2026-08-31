import type { ReactNode } from 'react';
import type { RoleCode } from '../api/types';
import { useAuth } from '../auth/AuthContext';

/** No router yet (none approved beyond the Task 1 stack) — Shell just swaps `children` based on this. */
export type View = 'expenses' | 'approvals' | 'payroll' | 'onboarding';

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
const NAV_ITEMS: { label: string; view: View; roles: RoleCode[] }[] = [
  { label: 'My Expenses', view: 'expenses', roles: ['EMPLOYEE', 'MANAGER', 'PAYROLL_ADMIN', 'HR_ADMIN'] },
  { label: 'Approvals', view: 'approvals', roles: ['MANAGER', 'PAYROLL_ADMIN'] },
  { label: 'Payroll', view: 'payroll', roles: ['PAYROLL_ADMIN'] },
  { label: 'Onboarding', view: 'onboarding', roles: ['HR_ADMIN', 'MANAGER'] },
];

interface ShellProps {
  activeView: View;
  onNavigate: (view: View) => void;
  children: ReactNode;
}

export function Shell({ activeView, onNavigate, children }: ShellProps) {
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
              <li key={item.view}>
                <button
                  type="button"
                  className={item.view === activeView ? 'shell-nav-active' : undefined}
                  onClick={() => onNavigate(item.view)}
                >
                  {item.label}
                </button>
              </li>
            ))}
          </ul>
        </nav>
        <main className="shell-main">{children}</main>
      </div>
    </div>
  );
}
