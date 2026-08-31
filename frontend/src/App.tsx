import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useState } from 'react';
import { AuthProvider, useAuth } from './auth/AuthContext';
import { LoginPage } from './components/LoginPage';
import { Shell, type View } from './components/Shell';
import { ApprovalsTable } from './features/approvals/ApprovalsTable';

const queryClient = new QueryClient();

function ViewContent({ view }: { view: View }) {
  switch (view) {
    case 'approvals':
      return <ApprovalsTable />;
    default:
      // My Expenses / Payroll / Onboarding land in Phase 9 Task 4.
      return <p>Not built yet — Phase 9 Task 4.</p>;
  }
}

function AppShell() {
  const { status, user } = useAuth();
  const [view, setView] = useState<View>('approvals');

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
