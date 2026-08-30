import { Link, Outlet } from 'react-router';
import { useAuth } from '../auth/useAuth';
import { logout as logoutApi } from '../api/authApi';
import { getRefreshToken } from '../auth/tokenStorage';

export default function AppLayout() {
  const { token, logout } = useAuth();

  // 서버 로그아웃(Refresh Token 폐기)을 먼저 시도한다. 실패해도(네트워크 오류·이미 만료된
  // 토큰 등) 사용자가 로그인 상태에 갇히면 안 되므로 로컬 정리(logout())는 항상 진행한다.
  async function handleLogout() {
    const refreshToken = getRefreshToken();
    if (refreshToken) {
      try {
        await logoutApi(refreshToken);
      } catch {
        // 서버 호출 실패는 무시 — 로컬 정리로 폴백한다.
      }
    }
    logout();
  }

  return (
    <div className="flex min-h-screen flex-col">
      <header className="border-b border-rule bg-surface/85 backdrop-blur">
        <div className="mx-auto flex w-full max-w-3xl items-center justify-between px-5 py-3.5">
          <Link to="/portfolio" className="text-base font-bold tracking-tight text-ink">
            AllFolio
          </Link>
          {token ? (
            <button
              onClick={handleLogout}
              className="rounded-control px-2 py-1 text-sm text-ink-soft transition-colors hover:text-ink"
              data-testid="app-logout"
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
