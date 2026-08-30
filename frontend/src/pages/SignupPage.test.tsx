import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router';
import SignupPage from './SignupPage';
import { AuthProvider } from '../auth/AuthProvider';
import { VALIDATION_MESSAGES, ERROR_MESSAGES } from '../lib/messages';

function renderSignupPage() {
  return render(
    <MemoryRouter initialEntries={['/signup']}>
      <AuthProvider>
        <SignupPage />
      </AuthProvider>
    </MemoryRouter>,
  );
}

afterEach(() => {
  vi.restoreAllMocks();
  localStorage.clear();
});

describe('SignupPage', () => {
  it('7자 비밀번호로 제출하면 API를 호출하지 않고 PASSWORD_TOO_SHORT 문구를 보여준다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    renderSignupPage();

    fireEvent.change(screen.getByTestId('signup-email'), { target: { value: 'user@example.com' } });
    fireEvent.change(screen.getByTestId('signup-password'), { target: { value: 'abc1234' } });
    fireEvent.click(screen.getByTestId('signup-submit'));

    await waitFor(() => {
      expect(screen.getByText(VALIDATION_MESSAGES.PASSWORD_TOO_SHORT)).toBeTruthy();
    });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('회원가입 성공 시 자동 로그인되어 토큰이 저장된다', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ accessToken: 'tok', tokenType: 'Bearer', expiresIn: 900, refreshToken: 'refresh-tok' }),
    });
    vi.stubGlobal('fetch', fetchMock);

    renderSignupPage();

    fireEvent.change(screen.getByTestId('signup-email'), { target: { value: 'user@example.com' } });
    fireEvent.change(screen.getByTestId('signup-password'), { target: { value: 'password123' } });
    fireEvent.click(screen.getByTestId('signup-submit'));

    await waitFor(() => {
      expect(localStorage.getItem('allfolio_token')).toBe('tok');
    });
    expect(fetchMock).toHaveBeenCalledWith(
      '/v1/auth/signup',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ email: 'user@example.com', password: 'password123' }),
      }),
    );
  });

  it('EMAIL_ALREADY_EXISTS 에러 응답 시 에러 문구를 표시한다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 409,
        json: async () => ({
          code: 'EMAIL_ALREADY_EXISTS',
          message: '이미 가입된 이메일입니다. 로그인하거나 다른 이메일로 가입하세요.',
          timestamp: '2026-08-20T00:00:00Z',
        }),
      }),
    );

    renderSignupPage();

    fireEvent.change(screen.getByTestId('signup-email'), { target: { value: 'user@example.com' } });
    fireEvent.change(screen.getByTestId('signup-password'), { target: { value: 'password123' } });
    fireEvent.click(screen.getByTestId('signup-submit'));

    await waitFor(() => {
      expect(screen.getByTestId('signup-error').textContent).toContain(
        ERROR_MESSAGES.EMAIL_ALREADY_EXISTS,
      );
    });
  });
});
