// 구조·동작(props/상태/핸들러/data-testid): senior-frontend / className·마크업·문구: ui-ux-designer
import type { ReactNode } from 'react';

export interface AlertProps {
  tone: 'error' | 'success' | 'info';
  children: ReactNode;
  testId?: string;
}

// 톤은 색뿐 아니라 좌측 바 + 아이콘(형태)으로도 구분된다 — 색각 이상 사용자를 위해 이중 신호를 둔다.
const TONE: Record<AlertProps['tone'], { box: string; icon: string; path: string }> = {
  error: {
    box: 'border-l-alarm',
    icon: 'text-alarm',
    path: 'M8 1.5 15 14H1L8 1.5Zm0 4.25a.75.75 0 0 0-.75.75v2.75a.75.75 0 0 0 1.5 0V6.5A.75.75 0 0 0 8 5.75Zm0 5.25a.9.9 0 1 0 0 1.8.9.9 0 0 0 0-1.8Z',
  },
  success: {
    box: 'border-l-affirm',
    icon: 'text-affirm',
    path: 'M8 .8a7.2 7.2 0 1 0 0 14.4A7.2 7.2 0 0 0 8 .8Zm3.6 5.2-4.3 4.7a.75.75 0 0 1-1.1.02L4.4 8.9a.75.75 0 1 1 1.08-1.04l1.25 1.3 3.76-4.1A.75.75 0 1 1 11.6 6Z',
  },
  info: {
    box: 'border-l-ink',
    icon: 'text-ink',
    path: 'M8 .8a7.2 7.2 0 1 0 0 14.4A7.2 7.2 0 0 0 8 .8Zm0 2.6a.95.95 0 1 1 0 1.9.95.95 0 0 1 0-1.9ZM7.25 6.9h1.5v5.4h-1.5V6.9Z',
  },
};

export default function Alert({ tone, children, testId }: AlertProps) {
  const style = TONE[tone];

  return (
    <div
      role={tone === 'error' ? 'alert' : 'status'}
      data-tone={tone}
      data-testid={testId}
      className={`flex items-start gap-2.5 rounded-control border border-rule border-l-4 bg-surface px-3.5 py-3 text-sm leading-6 text-ink ${style.box}`}
    >
      <svg
        aria-hidden="true"
        viewBox="0 0 16 16"
        className={`mt-0.5 size-4 shrink-0 fill-current ${style.icon}`}
      >
        <path d={style.path} />
      </svg>
      <span className="min-w-0 flex-1">{children}</span>
    </div>
  );
}
