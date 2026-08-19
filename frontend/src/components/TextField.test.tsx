import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import TextField from './TextField';

describe('TextField', () => {
  it('onChange에 입력값을 문자열 그대로 전달한다', () => {
    const onChange = vi.fn();
    render(
      <TextField label="이메일" value="" onChange={onChange} testId="email-input" />,
    );

    fireEvent.change(screen.getByTestId('email-input'), { target: { value: 'a@b.com' } });

    expect(onChange).toHaveBeenCalledWith('a@b.com');
  });

  it('error가 없으면 aria-invalid/aria-describedby를 부여하지 않는다', () => {
    render(<TextField label="이메일" value="" onChange={() => {}} testId="email-input" />);

    const input = screen.getByTestId('email-input');
    expect(input.getAttribute('aria-invalid')).toBeNull();
    expect(input.getAttribute('aria-describedby')).toBeNull();
  });

  it('error가 있으면 aria-invalid=true와 aria-describedby를 배선한다', () => {
    render(
      <TextField
        label="이메일"
        value=""
        onChange={() => {}}
        error="이메일 형식이 올바르지 않습니다."
        testId="email-input"
      />,
    );

    const input = screen.getByTestId('email-input');
    expect(input.getAttribute('aria-invalid')).toBe('true');
    const describedBy = input.getAttribute('aria-describedby');
    expect(describedBy).not.toBeNull();
    expect(document.getElementById(describedBy as string)?.textContent).toBe(
      '이메일 형식이 올바르지 않습니다.',
    );
  });

  it('inputMode로 숫자류 입력을 지원한다 (기본 type은 text)', () => {
    render(
      <TextField
        label="수량"
        value="10"
        onChange={() => {}}
        inputMode="decimal"
        testId="quantity-input"
      />,
    );
    const input = screen.getByTestId('quantity-input');
    expect(input.getAttribute('type')).toBe('text');
    expect(input.getAttribute('inputmode')).toBe('decimal');
  });
});
