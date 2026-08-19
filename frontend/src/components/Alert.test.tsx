import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import Alert from './Alert';

describe('Alert', () => {
  it("tone='error'는 role='alert'다", () => {
    render(
      <Alert tone="error" testId="form-alert">
        오류가 발생했습니다.
      </Alert>,
    );
    expect(screen.getByTestId('form-alert').getAttribute('role')).toBe('alert');
  });

  it("tone='success'는 role='status'다", () => {
    render(
      <Alert tone="success" testId="form-alert">
        저장되었습니다.
      </Alert>,
    );
    expect(screen.getByTestId('form-alert').getAttribute('role')).toBe('status');
  });

  it("tone='info'는 role='status'다", () => {
    render(
      <Alert tone="info" testId="form-alert">
        안내
      </Alert>,
    );
    expect(screen.getByTestId('form-alert').getAttribute('role')).toBe('status');
  });
});
