import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import SegmentToggle from './SegmentToggle';

const OPTIONS = [
  { value: 'STOCK', label: '주식', testIdSuffix: 'stock' },
  { value: 'COIN', label: '코인', testIdSuffix: 'coin' },
  { value: 'CASH', label: '현금', testIdSuffix: 'cash' },
] as const;

describe('SegmentToggle', () => {
  it('옵션 클릭 시 onChange가 해당 옵션의 값으로 호출된다', () => {
    const onChange = vi.fn();
    render(
      <SegmentToggle
        value="STOCK"
        options={OPTIONS}
        onChange={onChange}
        ariaLabelledBy="type-label"
        testId="asset-type"
      />,
    );

    fireEvent.click(screen.getByTestId('asset-type-coin'));

    expect(onChange).toHaveBeenCalledWith('COIN');
  });

  it('선택된 옵션만 aria-pressed=true이고 나머지는 false다', () => {
    render(
      <SegmentToggle
        value="COIN"
        options={OPTIONS}
        onChange={() => {}}
        ariaLabelledBy="type-label"
        testId="asset-type"
      />,
    );

    expect(screen.getByTestId('asset-type-stock').getAttribute('aria-pressed')).toBe('false');
    expect(screen.getByTestId('asset-type-coin').getAttribute('aria-pressed')).toBe('true');
    expect(screen.getByTestId('asset-type-cash').getAttribute('aria-pressed')).toBe('false');
  });

  it('testId와 옵션의 testIdSuffix를 조합해 각 버튼의 data-testid를 생성한다', () => {
    render(
      <SegmentToggle
        value="STOCK"
        options={OPTIONS}
        onChange={() => {}}
        ariaLabelledBy="type-label"
        testId="asset-type"
      />,
    );

    expect(screen.getByTestId('asset-type-stock')).toBeTruthy();
    expect(screen.getByTestId('asset-type-coin')).toBeTruthy();
    expect(screen.getByTestId('asset-type-cash')).toBeTruthy();
  });
});
