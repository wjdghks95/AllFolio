import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import ConfirmDialog from './ConfirmDialog';

describe('ConfirmDialog', () => {
  it('open=false면 dialog가 열리지 않는다', () => {
    render(
      <ConfirmDialog
        open={false}
        title="삭제하시겠습니까?"
        description="이 작업은 되돌릴 수 없습니다."
        confirmLabel="삭제"
        onConfirm={() => {}}
        onCancel={() => {}}
        testId="delete-confirm"
      />,
    );

    const dialog = screen.getByTestId('delete-confirm') as HTMLDialogElement;
    expect(dialog.open).toBe(false);
  });

  it('open=true면 showModal로 dialog가 열린다', () => {
    render(
      <ConfirmDialog
        open={true}
        title="삭제하시겠습니까?"
        description="이 작업은 되돌릴 수 없습니다."
        confirmLabel="삭제"
        onConfirm={() => {}}
        onCancel={() => {}}
        testId="delete-confirm"
      />,
    );

    const dialog = screen.getByTestId('delete-confirm') as HTMLDialogElement;
    expect(dialog.open).toBe(true);
  });

  it('confirm 버튼 클릭 시 onConfirm을 호출한다 (testId 접두 파생)', () => {
    const onConfirm = vi.fn();
    render(
      <ConfirmDialog
        open={true}
        title="삭제하시겠습니까?"
        description="이 작업은 되돌릴 수 없습니다."
        confirmLabel="삭제"
        onConfirm={onConfirm}
        onCancel={() => {}}
        testId="delete-confirm"
      />,
    );

    fireEvent.click(screen.getByTestId('delete-confirm-confirm'));
    expect(onConfirm).toHaveBeenCalledTimes(1);
  });

  it('cancel 버튼 클릭 시 onCancel을 호출한다 (testId 접두 파생)', () => {
    const onCancel = vi.fn();
    render(
      <ConfirmDialog
        open={true}
        title="삭제하시겠습니까?"
        description="이 작업은 되돌릴 수 없습니다."
        confirmLabel="삭제"
        onCancel={onCancel}
        onConfirm={() => {}}
        testId="delete-confirm"
      />,
    );

    fireEvent.click(screen.getByTestId('delete-confirm-cancel'));
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('confirmDisabled가 true면 confirm 버튼이 비활성화된다', () => {
    render(
      <ConfirmDialog
        open={true}
        title="삭제하시겠습니까?"
        description="이 작업은 되돌릴 수 없습니다."
        confirmLabel="삭제"
        confirmDisabled={true}
        onConfirm={() => {}}
        onCancel={() => {}}
        testId="delete-confirm"
      />,
    );

    expect(
      (screen.getByTestId('delete-confirm-confirm') as HTMLButtonElement).disabled,
    ).toBe(true);
  });

  it('브라우저 기본 닫기(cancel 이벤트, 예: Esc)도 onCancel을 호출한다', () => {
    const onCancel = vi.fn();
    render(
      <ConfirmDialog
        open={true}
        title="삭제하시겠습니까?"
        description="이 작업은 되돌릴 수 없습니다."
        confirmLabel="삭제"
        onCancel={onCancel}
        onConfirm={() => {}}
        testId="delete-confirm"
      />,
    );

    const dialog = screen.getByTestId('delete-confirm') as HTMLDialogElement;
    fireEvent(dialog, new Event('cancel', { cancelable: true }));

    expect(onCancel).toHaveBeenCalledTimes(1);
  });
});
