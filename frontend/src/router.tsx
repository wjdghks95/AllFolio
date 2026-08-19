import { createBrowserRouter, Navigate } from 'react-router';
import AuthLayout from './layouts/AuthLayout';
import AppLayout from './layouts/AppLayout';
import RequireAuth from './auth/RequireAuth';
import LoginPage from './pages/LoginPage';
import SignupPage from './pages/SignupPage';
import PortfolioPage from './pages/PortfolioPage';
import AssetNewPage from './pages/AssetNewPage';
import AssetDetailPage from './pages/AssetDetailPage';
import DevUiPage from './pages/DevUiPage';

const router = createBrowserRouter([
  // 개발 전용 컴포넌트 쇼케이스 (Task 018 착수 시 제거 예정). import.meta.env.DEV로만
  // 등록해 프로덕션 빌드에서 DevUiPage가 트리셰이킹되게 한다. RequireAuth 밖이라 인증 없이 볼 수 있다.
  ...(import.meta.env.DEV ? [{ path: '/dev/ui', element: <DevUiPage /> }] : []),
  {
    element: <AuthLayout />,
    children: [
      { path: '/login', element: <LoginPage /> },
      { path: '/signup', element: <SignupPage /> },
    ],
  },
  {
    element: <AppLayout />,
    children: [
      {
        element: <RequireAuth />,
        children: [
          { index: true, element: <Navigate to="/portfolio" replace /> },
          { path: '/portfolio', element: <PortfolioPage /> },
          { path: '/assets/new', element: <AssetNewPage /> },
          { path: '/assets/:id', element: <AssetDetailPage /> },
        ],
      },
    ],
  },
]);

export default router;
