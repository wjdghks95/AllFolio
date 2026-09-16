import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from './AuthProvider';
import { useAuth } from './useAuth';
import { getToken, getRefreshToken } from './tokenStorage';

// vi.mock의 factory는 다른 import보다 앞으로 호이스팅되므로, factory 안에서 참조하는 값은
// vi.hoisted로 함께 끌어올려야 한다(pushRegistration.test.ts와 동일한 이유).
const mocks = vi.hoisted(() => ({
  registerPushTokenMock: vi.fn(),
  unregisterPushTokenMock: vi.fn(),
}));

vi.mock('../lib/pushRegistration', () => ({
  registerPushToken: mocks.registerPushTokenMock,
  unregisterPushToken: mocks.unregisterPushTokenMock,
}));

function TestConsumer() {
  const { token, login, logout } = useAuth();
  return (
    <div>
      <span data-testid="token">{token ?? 'none'}</span>
      <button data-testid="login-btn" onClick={() => login('access-token', 'refresh-token')} />
      <button data-testid="logout-btn" onClick={() => logout()} />
    </div>
  );
}

afterEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
});

describe('AuthProvider logout', () => {
  it('로그아웃 시 토큰을 지우기 전에 unregisterPushToken을 호출한다', () => {
    let tokenAtUnregisterCall: string | null | undefined;
    mocks.unregisterPushTokenMock.mockImplementation(() => {
      // unregisterPushToken 내부(실제로는 authorizedRequest)가 Authorization 헤더에
      // 쓸 토큰을 아직 읽을 수 있어야 하므로, 이 시점엔 토큰이 지워지기 전이어야 한다.
      tokenAtUnregisterCall = getToken();
    });
    render(
      <AuthProvider>
        <TestConsumer />
      </AuthProvider>,
    );

    fireEvent.click(screen.getByTestId('login-btn'));
    expect(getToken()).toBe('access-token');

    fireEvent.click(screen.getByTestId('logout-btn'));

    expect(mocks.unregisterPushTokenMock).toHaveBeenCalledTimes(1);
    expect(tokenAtUnregisterCall).toBe('access-token');
    expect(getToken()).toBeNull();
    expect(getRefreshToken()).toBeNull();
    expect(screen.getByTestId('token').textContent).toBe('none');
  });

  it('로그인 시 registerPushToken을 호출한다', () => {
    render(
      <AuthProvider>
        <TestConsumer />
      </AuthProvider>,
    );

    fireEvent.click(screen.getByTestId('login-btn'));

    expect(mocks.registerPushTokenMock).toHaveBeenCalledTimes(1);
  });
});
