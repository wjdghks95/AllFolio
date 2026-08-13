import { Link, Outlet } from 'react-router';
import { useAuth } from '../auth/useAuth';

export default function AppLayout() {
  const { token, logout } = useAuth();

  return (
    <div className="min-h-screen flex flex-col">
      <header className="px-4 py-3 border-b border-gray-200 flex justify-between items-center">
        <Link to="/portfolio" className="text-lg font-bold text-gray-900">
          AllFolio
        </Link>
        <div>
          {token ? (
            <button
              onClick={logout}
              className="text-sm text-gray-600 hover:text-gray-900"
            >
              로그아웃
            </button>
          ) : (
            <Link to="/login" className="text-sm text-gray-600 hover:text-gray-900">
              로그인
            </Link>
          )}
        </div>
      </header>
      <main className="p-4 flex-1">
        <Outlet />
      </main>
    </div>
  );
}
