// 구조·동작(props/상태/핸들러/data-testid): senior-frontend / className·마크업·문구: ui-ux-designer
import { useId, type ReactNode } from 'react';

export interface FieldControlProps {
  id: string;
  'aria-invalid'?: boolean;
  'aria-describedby'?: string;
}

export interface FieldProps {
  label: string;
  error?: string | null;
  hint?: string;
  children: (control: FieldControlProps) => ReactNode;
}

// 렌더 프롭 패턴: label/error/hint에 맞는 id를 생성해 제어 요소(children)에 aria 배선을 주입한다.
export default function Field({ label, error, hint, children }: FieldProps) {
  const id = useId();
  const errorId = `${id}-error`;
  const hintId = `${id}-hint`;

  const describedBy =
    [error ? errorId : null, hint ? hintId : null].filter(Boolean).join(' ') || undefined;

  const control: FieldControlProps = {
    id,
    'aria-invalid': error ? true : undefined,
    'aria-describedby': describedBy,
  };

  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-[13px] font-medium tracking-tight text-ink-soft">
        {label}
      </label>
      {children(control)}
      {hint ? (
        <p id={hintId} className="text-xs leading-5 text-ink-soft">
          {hint}
        </p>
      ) : null}
      {error ? (
        // 색만으로 에러를 알리지 않는다 — 아이콘(형태)과 굵기가 함께 신호를 준다.
        // 아이콘은 텍스트 노드가 없는 svg라 이 <p>의 textContent는 에러 문구와 정확히 일치한다.
        <p
          id={errorId}
          className="flex items-start gap-1.5 text-xs leading-5 font-medium text-alarm"
        >
          <svg
            aria-hidden="true"
            viewBox="0 0 16 16"
            className="mt-0.5 size-3.5 shrink-0 fill-current"
          >
            <path d="M8 1.5 15 14H1L8 1.5Zm0 4.25a.75.75 0 0 0-.75.75v2.75a.75.75 0 0 0 1.5 0V6.5A.75.75 0 0 0 8 5.75Zm0 5.25a.9.9 0 1 0 0 1.8.9.9 0 0 0 0-1.8Z" />
          </svg>
          {error}
        </p>
      ) : null}
    </div>
  );
}
