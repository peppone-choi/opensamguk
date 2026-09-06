'use client';

import { useRef, type ReactNode } from 'react';
import { Button } from './Button';
import { Modal } from './Modal';

export type ConfirmDialogProps = {
  readonly busy?: boolean;
  readonly cancelLabel?: string;
  readonly confirmLabel?: string;
  readonly danger?: boolean;
  readonly message: ReactNode;
  readonly onCancel: () => void;
  readonly onConfirm: () => void;
  readonly open: boolean;
  readonly title: string;
};

export function ConfirmDialog({
  busy = false,
  cancelLabel = '취소',
  confirmLabel = '실행',
  danger = false,
  message,
  onCancel,
  onConfirm,
  open,
  title,
}: ConfirmDialogProps) {
  const confirmRef = useRef<HTMLButtonElement>(null);

  if (!open) return null;

  const busyProps = busy ? ({ disabled: true, reason: '처리 중입니다' } as const) : ({} as const);

  return (
    <Modal
      ariaLabel={title}
      className="os-confirm-dialog"
      closeOnBackdrop={!busy}
      closeOnEscape={!busy}
      initialFocusRef={busy ? undefined : confirmRef}
      onClose={onCancel}
    >
      <h3 className="os-confirm-dialog__title">{title}</h3>
      <div className="os-confirm-dialog__body">{message}</div>
      <div className="os-confirm-dialog__actions">
        <Button variant="ghost" onClick={onCancel} {...busyProps}>
          {cancelLabel}
        </Button>
        <Button
          ref={confirmRef}
          variant={danger ? 'danger' : 'primary'}
          onClick={onConfirm}
          {...busyProps}
        >
          {busy ? '처리 중…' : confirmLabel}
        </Button>
      </div>
    </Modal>
  );
}
