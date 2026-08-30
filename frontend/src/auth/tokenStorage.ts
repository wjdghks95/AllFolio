const TOKEN_KEY = 'allfolio_token';
const REFRESH_TOKEN_KEY = 'allfolio_refresh_token';

export const getToken = (): string | null => localStorage.getItem(TOKEN_KEY);

export const setToken = (token: string): void => localStorage.setItem(TOKEN_KEY, token);

export const removeToken = (): void => localStorage.removeItem(TOKEN_KEY);

export const getRefreshToken = (): string | null => localStorage.getItem(REFRESH_TOKEN_KEY);

export const setRefreshToken = (token: string): void =>
  localStorage.setItem(REFRESH_TOKEN_KEY, token);

export const removeRefreshToken = (): void => localStorage.removeItem(REFRESH_TOKEN_KEY);
