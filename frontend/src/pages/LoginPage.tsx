// 구조·동작: senior-frontend / 시각 표현·문구: ui-ux-designer
import { useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import { login, ApiError } from '../api/authApi';
import { validateEmail, type ValidationCode } from '../lib/validation';
import { VALIDATION_MESSAGES, messageForErrorCode } from '../lib/messages';
import { useAuth } from '../auth/useAuth';
import TextField from '../components/TextField';
import Alert from '../components/Alert';
import Button from '../components/Button';

export default function LoginPage() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [emailError, setEmailError] = useState<ValidationCode | null>(null);
  const [passwordError, setPasswordError] = useState<ValidationCode | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  const auth = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setSubmitError(null);

    const emailCode = validateEmail(email);
    // 로그인은 회원가입과 달리 비밀번호 길이 정책을 클라이언트에서 검증하지 않는다.
    // 백엔드 LoginRequest는 의도적으로 길이 제약이 없다(정책 변경 전 가입자 로그인 차단 방지,
    // 400/401 응답 차이로 비밀번호 정책이 유추되는 것도 방지). 필수 입력 여부만 확인한다.
    const passwordCode = password.length === 0 ? 'REQUIRED' : null;
    setEmailError(emailCode);
    setPasswordError(passwordCode);
    if (emailCode || passwordCode) return;

    setSubmitting(true);
    try {
      const res = await login({ email, password });
      auth.login(res.accessToken);
      const from = (location.state as { from?: { pathname: string } } | null)?.from?.pathname;
      navigate(from ?? '/portfolio', { replace: true });
    } catch (e) {
      setSubmitError(messageForErrorCode(e instanceof ApiError ? e.code : 'NETWORK_ERROR'));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div>
      {/* 인증 화면에는 AppLayout 헤더가 없다 — 워드마크가 여기서 유일한 브랜드 표식이다. */}
      <span className="text-base font-bold tracking-tight text-ink">AllFolio</span>

      <h1 className="mt-7 text-2xl font-bold tracking-tight text-ink sm:text-3xl">로그인</h1>

      <form onSubmit={handleSubmit} noValidate className="mt-8 flex flex-col gap-4">
        {submitError ? (
          <Alert tone="error" testId="login-error">
            {submitError}
          </Alert>
        ) : null}
        <TextField
          label="이메일"
          value={email}
          onChange={setEmail}
          type="email"
          placeholder="name@example.com"
          autoComplete="email"
          required
          error={emailError ? VALIDATION_MESSAGES[emailError] : null}
          testId="login-email"
        />
        <TextField
          label="비밀번호"
          value={password}
          onChange={setPassword}
          type="password"
          autoComplete="current-password"
          required
          error={passwordError ? VALIDATION_MESSAGES[passwordError] : null}
          testId="login-password"
        />
        <div className="mt-2 flex flex-col [&>button]:w-full">
          <Button type="submit" variant="primary" disabled={submitting} testId="login-submit">
            {submitting ? '로그인 중' : '로그인'}
          </Button>
        </div>
      </form>

      <p className="mt-8 border-t border-rule pt-5 text-sm leading-6 text-ink-soft">
        아직 계정이 없다면
        <Link
          to="/signup"
          className="ml-1 rounded-control font-medium text-ink underline decoration-ink-soft/60 underline-offset-4 transition-colors duration-150 hover:decoration-ink"
        >
          회원가입
        </Link>
      </p>
    </div>
  );
}
