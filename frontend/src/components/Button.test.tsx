import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import Button from './Button';

describe('Button', () => {
  it('클릭 시 onClick을 호출한다', () => {
    const onClick = vi.fn();
    render(
      <Button onClick={onClick} testId="submit-btn">
        확인
      </Button>,
    );

    fireEvent.click(screen.getByTestId('submit-btn'));

    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('disabled면 onClick을 호출하지 않는다', () => {
    const onClick = vi.fn();
    render(
      <Button onClick={onClick} disabled testId="submit-btn">
        확인
      </Button>,
    );

    fireEvent.click(screen.getByTestId('submit-btn'));

    expect(onClick).not.toHaveBeenCalled();
  });

  it('기본 type은 button이다', () => {
    render(<Button testId="btn">확인</Button>);
    expect(screen.getByTestId('btn').getAttribute('type')).toBe('button');
  });

  it("type='submit'을 지정하면 그대로 반영한다", () => {
    render(
      <Button type="submit" testId="btn">
        확인
      </Button>,
    );
    expect(screen.getByTestId('btn').getAttribute('type')).toBe('submit');
  });
});
