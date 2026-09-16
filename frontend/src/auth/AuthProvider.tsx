import { useState, type ReactNode } from 'react';
import { AuthContext } from './authContext';
import {
  getToken,
  setToken,
  removeToken,
  setRefreshToken,
  removeRefreshToken,
} from './tokenStorage';
import { registerPushToken, unregisterPushToken } from '../lib/pushRegistration';

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setTokenState] = useState<string | null>(getToken);

  const login = (accessToken: string, refreshToken: string) => {
    setToken(accessToken);
    setRefreshToken(refreshToken);
    setTokenState(accessToken);
    // 네이티브 앱(Capacitor)에서만 동작, 웹에서는 조용히 스킵된다(Task 029).
    void registerPushToken();
  };

  const logout = () => {
    // 토큰을 지우기 전에 호출해야 DELETE /v1/devices/{id} 요청에 Authorization 헤더가 실린다.
    unregisterPushToken();
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
