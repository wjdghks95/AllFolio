const TOKEN_KEY = 'allfolio_token';
const REFRESH_TOKEN_KEY = 'allfolio_refresh_token';
const DEVICE_ID_KEY = 'allfolio_device_id';

export const getToken = (): string | null => localStorage.getItem(TOKEN_KEY);

export const setToken = (token: string): void => localStorage.setItem(TOKEN_KEY, token);

export const removeToken = (): void => localStorage.removeItem(TOKEN_KEY);

export const getRefreshToken = (): string | null => localStorage.getItem(REFRESH_TOKEN_KEY);

export const setRefreshToken = (token: string): void =>
  localStorage.setItem(REFRESH_TOKEN_KEY, token);

export const removeRefreshToken = (): void => localStorage.removeItem(REFRESH_TOKEN_KEY);

// POST /v1/devices 응답의 id — 로그아웃 시 DELETE /v1/devices/{id}로 기기 토큰을 해제하기 위해 보관한다(Task 029).
export const getDeviceId = (): string | null => localStorage.getItem(DEVICE_ID_KEY);

export const setDeviceId = (id: string): void => localStorage.setItem(DEVICE_ID_KEY, id);

export const removeDeviceId = (): void => localStorage.removeItem(DEVICE_ID_KEY);
