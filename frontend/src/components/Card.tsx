// 구조·동작(props/상태/핸들러/data-testid): senior-frontend / className·마크업·문구: ui-ux-designer
import { useId, type ReactNode } from 'react';

export interface CardProps {
  title?: ReactNode;
  children: ReactNode;
  testId?: string;
}

export default function Card({ title, children, testId }: CardProps) {
  const titleId = useId();

  return (
    <section
      aria-labelledby={title ? titleId : undefined}
      data-testid={testId}
      className="overflow-hidden rounded-card border border-rule bg-surface"
    >
      {title ? (
        <h2
          id={titleId}
          className="border-b border-rule px-5 py-3.5 text-sm font-semibold tracking-tight text-ink"
        >
          {title}
        </h2>
      ) : null}
      <div className="px-5 py-4 text-[15px] leading-6 text-ink">{children}</div>
    </section>
  );
}
