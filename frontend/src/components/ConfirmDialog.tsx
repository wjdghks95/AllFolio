// 구조·동작(props/상태/핸들러/data-testid): senior-frontend / className·마크업·문구: ui-ux-designer
import { useEffect, useRef, type ReactNode } from 'react';
import Button from './Button';

export interface ConfirmDialogProps {
  open: boolean;
  title: string;
  description: ReactNode;
  confirmLabel: string;
  cancelLabel?: string;
  tone?: 'default' | 'danger';
  onConfirm: () => void;
  onCancel: () => void;
  testId?: string;
}

// 네이티브 <dialog> 기반. open prop 변화에 맞춰 showModal()/close()를 동기화하고,
// Esc 등 브라우저 기본 닫기(onCancel 이벤트)는 preventDefault 후 onCancel()을 호출해
// React state(open)가 단일 진실 소스로 유지되게 한다.
export default function ConfirmDialog({
  open,
  title,
  description,
  confirmLabel,
  cancelLabel = '취소',
  tone = 'default',
  onConfirm,
  onCancel,
  testId,
}: ConfirmDialogProps) {
  const dialogRef = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (open && !dialog.open) {
      dialog.showModal();
    } else if (!open && dialog.open) {
      dialog.close();
    }
  }, [open]);

  return (
    <dialog
      ref={dialogRef}
      data-tone={tone}
      data-testid={testId}
      aria-label={title}
      onCancel={(e) => {
        e.preventDefault();
        onCancel();
      }}
      className="m-auto w-[min(26rem,calc(100vw-2rem))] rounded-card border border-rule bg-surface p-0 text-ink backdrop:bg-ink/45"
    >
      <div className="px-5 pt-5 pb-4">
        <h2 className="text-base font-semibold tracking-tight text-ink">{title}</h2>
        <div className="mt-2 text-sm leading-6 text-ink-soft">{description}</div>
      </div>
      {/* 모바일에서는 버튼을 세로로 쌓고(주 액션이 엄지에 가까운 아래쪽), 넓어지면 우측 정렬 가로 배치. */}
      <div className="flex flex-col gap-2 border-t border-rule bg-grid px-5 py-3.5 [&>button]:w-full sm:flex-row sm:justify-end sm:[&>button]:w-auto">
        <Button
          variant="secondary"
          onClick={onCancel}
          testId={testId ? `${testId}-cancel` : undefined}
        >
          {cancelLabel}
        </Button>
        <Button
          variant={tone === 'danger' ? 'danger' : 'primary'}
          onClick={onConfirm}
          testId={testId ? `${testId}-confirm` : undefined}
        >
          {confirmLabel}
        </Button>
      </div>
    </dialog>
  );
}
