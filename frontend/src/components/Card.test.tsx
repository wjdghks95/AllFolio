import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import Card from './Card';

describe('Card', () => {
  it('title이 있으면 aria-labelledby로 제목과 연결한다', () => {
    render(
      <Card title="보유 자산" testId="asset-card">
        내용
      </Card>,
    );

    const card = screen.getByTestId('asset-card');
    const labelledBy = card.getAttribute('aria-labelledby');
    expect(labelledBy).not.toBeNull();
    expect(document.getElementById(labelledBy as string)?.textContent).toBe('보유 자산');
  });

  it('title이 없으면 aria-labelledby를 부여하지 않는다', () => {
    render(<Card testId="asset-card">내용</Card>);
    expect(screen.getByTestId('asset-card').getAttribute('aria-labelledby')).toBeNull();
  });
});
