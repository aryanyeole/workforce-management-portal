import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider, useAuth } from './auth/AuthContext';
import { LoginPage } from './components/LoginPage';
import { Shell } from './components/Shell';

const queryClient = new QueryClient();

function AppShell() {
  const { status, user } = useAuth();

  if (status === 'loading') {
    return <div className="app-loading">Loading…</div>;
  }

  if (status === 'anonymous' || !user) {
    return <LoginPage />;
  }

  return (
    <Shell>
      <p>
        Signed in as <strong>{user.email}</strong> ({user.role}).
      </p>
      <p>The approvals table and everything else lands in Phase 9 Task 2 onward.</p>
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
