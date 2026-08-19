// 구조·동작(props/상태/핸들러/data-testid): senior-frontend / className·마크업·문구: ui-ux-designer
import type { MouseEventHandler, ReactNode } from 'react';

export interface ButtonProps {
  children: ReactNode;
  type?: 'button' | 'submit';
  variant?: 'primary' | 'secondary' | 'danger';
  disabled?: boolean;
  onClick?: MouseEventHandler<HTMLButtonElement>;
  testId?: string;
}

// 높이 44px = 모바일 터치 타깃 최소치. 라벨은 sans, 자간은 살짝 좁혀 잉크 블록처럼 보이게 한다.
const BASE =
  'inline-flex h-11 items-center justify-center gap-2 rounded-control border px-4 text-sm font-semibold tracking-tight transition-colors duration-150 disabled:cursor-not-allowed disabled:opacity-40';

const VARIANT: Record<NonNullable<ButtonProps['variant']>, string> = {
  primary:
    'border-ink bg-ink text-grid hover:not-disabled:bg-ink-soft hover:not-disabled:border-ink-soft',
  secondary:
    'border-rule bg-surface text-ink hover:not-disabled:border-ink hover:not-disabled:bg-grid',
  danger:
    'border-alarm bg-alarm text-white hover:not-disabled:bg-ink hover:not-disabled:border-ink',
};

export default function Button({
  children,
  type = 'button',
  variant = 'primary',
  disabled = false,
  onClick,
  testId,
}: ButtonProps) {
  return (
    <button
      type={type}
      data-variant={variant}
      disabled={disabled}
      onClick={onClick}
      data-testid={testId}
      className={`${BASE} ${VARIANT[variant]}`}
    >
      {children}
    </button>
  );
}
