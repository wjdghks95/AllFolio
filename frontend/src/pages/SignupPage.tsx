// 구조·동작: senior-frontend / 시각 표현·문구: ui-ux-designer
import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { signup, ApiError } from '../api/authApi';
import { useAuth } from '../auth/useAuth';
import { validateEmail, validatePassword, type ValidationCode } from '../lib/validation';
import { VALIDATION_MESSAGES, messageForErrorCode } from '../lib/messages';
import TextField from '../components/TextField';
import Alert from '../components/Alert';
import Button from '../components/Button';

export default function SignupPage() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [emailError, setEmailError] = useState<ValidationCode | null>(null);
  const [passwordError, setPasswordError] = useState<ValidationCode | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  const auth = useAuth();
  const navigate = useNavigate();

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setSubmitError(null);

    const emailCode = validateEmail(email);
    const passwordCode = validatePassword(password);
    setEmailError(emailCode);
    setPasswordError(passwordCode);
    if (emailCode || passwordCode) return;

    setSubmitting(true);
    try {
      const res = await signup({ email, password });
      auth.login(res.accessToken);
      navigate('/portfolio', { replace: true });
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

      <h1 className="mt-7 text-2xl font-bold tracking-tight text-ink sm:text-3xl">회원가입</h1>
      <p className="mt-2 text-sm leading-6 text-ink-soft">
        흩어진 자산을 한 곳에 모아 포트폴리오로 관리하세요.
      </p>

      <form onSubmit={handleSubmit} noValidate className="mt-8 flex flex-col gap-4">
        {submitError ? (
          <Alert tone="error" testId="signup-error">
            {submitError}
          </Alert>
        ) : null}
        <TextField
          label="이메일"
          type="email"
          placeholder="name@example.com"
          autoComplete="email"
          required
          value={email}
          onChange={setEmail}
          error={emailError ? VALIDATION_MESSAGES[emailError] : null}
          testId="signup-email"
        />
        <TextField
          label="비밀번호"
          type="password"
          autoComplete="new-password"
          required
          value={password}
          onChange={setPassword}
          hint="8자 이상 입력하세요."
          error={passwordError ? VALIDATION_MESSAGES[passwordError] : null}
          testId="signup-password"
        />
        <div className="mt-2 flex flex-col [&>button]:w-full">
          <Button type="submit" variant="primary" disabled={submitting} testId="signup-submit">
            {submitting ? '회원가입 중' : '회원가입'}
          </Button>
        </div>
      </form>

      <p className="mt-8 border-t border-rule pt-5 text-sm leading-6 text-ink-soft">
        이미 가입했다면
        <Link
          to="/login"
          className="ml-1 rounded-control font-medium text-ink underline decoration-ink-soft/60 underline-offset-4 transition-colors duration-150 hover:decoration-ink"
        >
          로그인
        </Link>
      </p>
    </div>
  );
}
