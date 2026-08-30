import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import LoginPage from './LoginPage';
import { AuthProvider } from '../auth/AuthProvider';
import { VALIDATION_MESSAGES, ERROR_MESSAGES } from '../lib/messages';

function renderLoginPage(initialEntries: Array<{ pathname: string; state?: unknown }> = [{ pathname: '/login' }]) {
  return render(
    <MemoryRouter initialEntries={initialEntries}>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/assets/new" element={<div data-testid="assets-new-page">자산 등록</div>} />
          <Route path="/portfolio" element={<div data-testid="portfolio-page">포트폴리오</div>} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  );
}

afterEach(() => {
  vi.restoreAllMocks();
  localStorage.clear();
});

describe('LoginPage', () => {
  it('이메일이 비어 있으면 제출을 막고 필수 입력 에러를 보여준다', () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    renderLoginPage();

    fireEvent.change(screen.getByTestId('login-password'), { target: { value: 'abc12345' } });
    // form에 noValidate가 있어 필드가 비어 있어도 브라우저 네이티브 검증이 click 기반 submit을
    // 막지 않는다 — noValidate가 실수로 제거되면 이 click이 네이티브 검증에 막혀 아래 REQUIRED
    // 문구 검증이 실패하므로, 이 테스트가 noValidate 회귀도 함께 잡는다.
    fireEvent.click(screen.getByTestId('login-submit'));

    expect(fetchMock).not.toHaveBeenCalled();
    expect(screen.getByText(VALIDATION_MESSAGES.REQUIRED)).toBeTruthy();
  });

  it('비밀번호가 비어 있으면 제출을 막고 필수 입력 에러를 보여준다', () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    renderLoginPage();

    fireEvent.change(screen.getByTestId('login-email'), { target: { value: 'user@example.com' } });
    fireEvent.click(screen.getByTestId('login-submit'));

    expect(fetchMock).not.toHaveBeenCalled();
    expect(screen.getByText(VALIDATION_MESSAGES.REQUIRED)).toBeTruthy();
  });

  it('7자 비밀번호로도 클라이언트 검증을 통과해 API 호출까지 도달한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ accessToken: 'tok', tokenType: 'Bearer', expiresIn: 900, refreshToken: 'refresh-tok' }),
    });
    vi.stubGlobal('fetch', fetchMock);
    renderLoginPage();

    fireEvent.change(screen.getByTestId('login-email'), { target: { value: 'user@example.com' } });
    fireEvent.change(screen.getByTestId('login-password'), { target: { value: 'abc1234' } });
    fireEvent.click(screen.getByTestId('login-submit'));

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
  });

  it('로그인 성공 시 토큰을 저장하고 이전 경로로 이동한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ accessToken: 'tok', tokenType: 'Bearer', expiresIn: 900, refreshToken: 'refresh-tok' }),
    });
    vi.stubGlobal('fetch', fetchMock);
    renderLoginPage([{ pathname: '/login', state: { from: { pathname: '/assets/new' } } }]);

    fireEvent.change(screen.getByTestId('login-email'), { target: { value: 'user@example.com' } });
    fireEvent.change(screen.getByTestId('login-password'), { target: { value: 'abc12345' } });
    fireEvent.click(screen.getByTestId('login-submit'));

    await waitFor(() => expect(localStorage.getItem('allfolio_token')).toBe('tok'));
    await waitFor(() => expect(screen.getByTestId('assets-new-page')).toBeTruthy());
    expect(fetchMock).toHaveBeenCalledWith(
      '/v1/auth/login',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ email: 'user@example.com', password: 'abc12345' }),
      }),
    );
  });

  it('돌아갈 경로가 없으면 로그인 성공 후 포트폴리오로 이동한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ accessToken: 'tok', tokenType: 'Bearer', expiresIn: 900, refreshToken: 'refresh-tok' }),
    });
    vi.stubGlobal('fetch', fetchMock);
    renderLoginPage();

    fireEvent.change(screen.getByTestId('login-email'), { target: { value: 'user@example.com' } });
    fireEvent.change(screen.getByTestId('login-password'), { target: { value: 'abc12345' } });
    fireEvent.click(screen.getByTestId('login-submit'));

    await waitFor(() => expect(screen.getByTestId('portfolio-page')).toBeTruthy());
  });

  it('INVALID_CREDENTIALS 에러를 그대로 화면에 보여준다', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 401,
      json: async () => ({
        code: 'INVALID_CREDENTIALS',
        message: '이메일 또는 비밀번호가 일치하지 않습니다.',
        timestamp: '2026-08-20T00:00:00Z',
      }),
    });
    vi.stubGlobal('fetch', fetchMock);
    renderLoginPage();

    fireEvent.change(screen.getByTestId('login-email'), { target: { value: 'user@example.com' } });
    fireEvent.change(screen.getByTestId('login-password'), { target: { value: 'abc12345' } });
    fireEvent.click(screen.getByTestId('login-submit'));

    await waitFor(() =>
      expect(screen.getByTestId('login-error').textContent).toContain(ERROR_MESSAGES.INVALID_CREDENTIALS),
    );
  });
});
