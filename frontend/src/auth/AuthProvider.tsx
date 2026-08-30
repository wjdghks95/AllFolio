import { useState, type ReactNode } from 'react';
import { AuthContext } from './authContext';
import {
  getToken,
  setToken,
  removeToken,
  setRefreshToken,
  removeRefreshToken,
} from './tokenStorage';

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setTokenState] = useState<string | null>(getToken);

  const login = (accessToken: string, refreshToken: string) => {
    setToken(accessToken);
    setRefreshToken(refreshToken);
    setTokenState(accessToken);
  };

  const logout = () => {
    removeToken();
    removeRefreshToken();
    setTokenState(null);
  };

  return (
    <AuthContext.Provider value={{ token, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}
