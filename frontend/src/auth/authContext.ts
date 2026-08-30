import { createContext } from 'react';

export interface AuthContextValue {
  token: string | null;
  login: (accessToken: string, refreshToken: string) => void;
  logout: () => void;
}

export const AuthContext = createContext<AuthContextValue | null>(null);
