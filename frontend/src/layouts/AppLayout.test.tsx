import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import AppLayout from './AppLayout';
import { AuthProvider } from '../auth/AuthProvider';
import { getToken, getRefreshToken, setToken, setRefreshToken } from '../auth/tokenStorage';

function renderAppLayout() {
  return render(
    <AuthProvider>
      <MemoryRouter initialEntries={['/portfolio']}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="/portfolio" element={<div data-testid="portfolio-page">포트폴리오</div>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </AuthProvider>,
  );
}

afterEach(() => {
  vi.restoreAllMocks();
  localStorage.clear();
});

describe('AppLayout 로그아웃', () => {
  it('로그아웃 클릭 시 서버 로그아웃(Refresh Token 폐기)을 호출하고 로컬 토큰을 정리한다', async () => {
    setToken('access-tok');
    setRefreshToken('refresh-tok');
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 204, json: async () => undefined });
    vi.stubGlobal('fetch', fetchMock);
    renderAppLayout();

    fireEvent.click(screen.getByTestId('app-logout'));

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        '/v1/auth/logout',
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({ refreshToken: 'refresh-tok' }),
        }),
      ),
    );
    await waitFor(() => expect(screen.getByText('로그인')).toBeTruthy());
    expect(getToken()).toBeNull();
    expect(getRefreshToken()).toBeNull();
  });

  it('서버 로그아웃 호출이 실패해도 로컬 토큰 정리와 UI 상태 갱신은 진행된다', async () => {
    setToken('access-tok');
    setRefreshToken('refresh-tok');
    const fetchMock = vi.fn().mockRejectedValue(new TypeError('network error'));
    vi.stubGlobal('fetch', fetchMock);
    renderAppLayout();

    fireEvent.click(screen.getByTestId('app-logout'));

    await waitFor(() => expect(screen.getByText('로그인')).toBeTruthy());
    expect(getToken()).toBeNull();
    expect(getRefreshToken()).toBeNull();
  });

  it('로그인 상태가 아니면 로그인 링크가 표시되고 로그아웃 버튼은 없다', () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    renderAppLayout();

    expect(screen.queryByTestId('app-logout')).toBeNull();
    expect(screen.getByText('로그인')).toBeTruthy();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
