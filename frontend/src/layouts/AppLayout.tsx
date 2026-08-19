import { Link, Outlet } from 'react-router';
import { useAuth } from '../auth/useAuth';

export default function AppLayout() {
  const { token, logout } = useAuth();

  return (
    <div className="flex min-h-screen flex-col">
      <header className="border-b border-rule bg-surface/85 backdrop-blur">
        <div className="mx-auto flex w-full max-w-3xl items-center justify-between px-5 py-3.5">
          <Link to="/portfolio" className="text-base font-bold tracking-tight text-ink">
            AllFolio
          </Link>
          {token ? (
            <button
              onClick={logout}
              className="rounded-control px-2 py-1 text-sm text-ink-soft transition-colors hover:text-ink"
            >
              로그아웃
            </button>
          ) : (
            <Link
              to="/login"
              className="rounded-control px-2 py-1 text-sm text-ink-soft transition-colors hover:text-ink"
            >
              로그인
            </Link>
          )}
        </div>
      </header>
      <main className="mx-auto w-full max-w-3xl flex-1 px-5 py-6">
        <Outlet />
      </main>
    </div>
  );
}
