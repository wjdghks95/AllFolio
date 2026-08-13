import { Navigate, Outlet, useLocation } from 'react-router';
import { useAuth } from './useAuth';

export default function RequireAuth() {
  const { token } = useAuth();
  const location = useLocation();
  return token ? <Outlet /> : <Navigate to="/login" state={{ from: location }} replace />;
}
