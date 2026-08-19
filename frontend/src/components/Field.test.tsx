import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import Field, { type FieldControlProps } from './Field';

function renderField(props: Omit<Parameters<typeof Field>[0], 'children'>) {
  render(
    <Field {...props}>
      {(control: FieldControlProps) => <input data-testid="control" {...control} />}
    </Field>,
  );
  return screen.getByTestId('control');
}

describe('Field', () => {
  it('error/hint가 없으면 aria-invalid/aria-describedby를 control에 전달하지 않는다', () => {
    const control = renderField({ label: '이메일' });

    expect(control.getAttribute('id')).toBeTruthy();
    expect(control.getAttribute('aria-invalid')).toBeNull();
    expect(control.getAttribute('aria-describedby')).toBeNull();
  });

  it('error가 있으면 aria-invalid=true와 error 문구를 가리키는 aria-describedby를 전달한다', () => {
    const control = renderField({ label: '이메일', error: '이메일 형식이 올바르지 않습니다.' });

    expect(control.getAttribute('aria-invalid')).toBe('true');
    const describedBy = control.getAttribute('aria-describedby');
    expect(describedBy).not.toBeNull();
    expect(document.getElementById(describedBy as string)?.textContent).toBe(
      '이메일 형식이 올바르지 않습니다.',
    );
  });

  it('hint만 있으면 aria-invalid 없이 hint 문구를 가리키는 aria-describedby를 전달한다', () => {
    const control = renderField({ label: '이메일', hint: '힌트 텍스트' });

    expect(control.getAttribute('aria-invalid')).toBeNull();
    const describedBy = control.getAttribute('aria-describedby');
    expect(describedBy).not.toBeNull();
    expect(document.getElementById(describedBy as string)?.textContent).toBe('힌트 텍스트');
  });

  it('error와 hint가 모두 있으면 aria-describedby에 두 id가 모두 담긴다', () => {
    const control = renderField({
      label: '이메일',
      error: '이메일 형식이 올바르지 않습니다.',
      hint: '힌트 텍스트',
    });

    const describedBy = control.getAttribute('aria-describedby') ?? '';
    const ids = describedBy.split(' ');
    expect(ids).toHaveLength(2);
    expect(ids.map((id) => document.getElementById(id)?.textContent)).toEqual(
      expect.arrayContaining(['이메일 형식이 올바르지 않습니다.', '힌트 텍스트']),
    );
  });

  it('control.id로 label의 htmlFor가 연결된다', () => {
    render(
      <Field label="이메일">
        {(control: FieldControlProps) => <input data-testid="control" {...control} />}
      </Field>,
    );

    const control = screen.getByTestId('control');
    const label = screen.getByText('이메일');
    expect(label.getAttribute('for')).toBe(control.getAttribute('id'));
  });
});
