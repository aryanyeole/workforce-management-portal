import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useState } from 'react';
import { AuthProvider, useAuth } from './auth/AuthContext';
import { LoginPage } from './components/LoginPage';
import { Shell, type View } from './components/Shell';
import { ApprovalsTable } from './features/approvals/ApprovalsTable';
import { MyExpensesView } from './features/expenses/MyExpensesView';
import { OnboardingView } from './features/onboarding/OnboardingView';

const queryClient = new QueryClient();

function ViewContent({ view }: { view: View }) {
  switch (view) {
    case 'expenses':
      return <MyExpensesView />;
    case 'approvals':
      return <ApprovalsTable />;
    case 'onboarding':
      return <OnboardingView />;
    default:
      // Payroll is still out of scope — Phase 9 never asked for it.
      return <p>Not built yet.</p>;
  }
}

function AppShell() {
  const { status, user } = useAuth();
  // 'expenses' (My Expenses) is the one view every role's nav includes
  // (Shell.tsx's NAV_ITEMS), so it's a safe universal default — unlike the
  // old default of 'approvals', which an EMPLOYEE has no nav link to and
  // would land on to an immediate 403 before clicking anything.
  const [view, setView] = useState<View>('expenses');

  if (status === 'loading') {
    return <div className="app-loading">Loading…</div>;
  }

  if (status === 'anonymous' || !user) {
    return <LoginPage />;
  }

  return (
    <Shell activeView={view} onNavigate={setView}>
      <ViewContent view={view} />
    </Shell>
  );
}

function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <AppShell />
      </AuthProvider>
    </QueryClientProvider>
  );
}

export default App;
