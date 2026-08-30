import { createBrowserRouter, Navigate } from 'react-router';
import AuthLayout from './layouts/AuthLayout';
import AppLayout from './layouts/AppLayout';
import RequireAuth from './auth/RequireAuth';
import LoginPage from './pages/LoginPage';
import SignupPage from './pages/SignupPage';
import PortfolioPage from './pages/PortfolioPage';
import AssetNewPage from './pages/AssetNewPage';
import AssetDetailPage from './pages/AssetDetailPage';

const router = createBrowserRouter([
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
