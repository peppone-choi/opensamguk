'use client';

import { Button } from '@opensamguk/ui';
import { useEffect, useRef } from 'react';

/**
 * 가벼운 확인 모달. window.confirm 대신 사용 — 비가역 작업(재배포 등)의 확인 단계.
 *
 * - open=false면 아무것도 렌더하지 않는다.
 * - 백드롭 클릭 / Esc / 취소 버튼 → onCancel, 실행 버튼 → onConfirm.
 * - busy=true면 실행 중 — 버튼 비활성 + 라벨 교체(중복 트리거 방지).
 */
export default function ConfirmModal({
    open,
    title,
    message,
    confirmLabel = '실행',
    cancelLabel = '취소',
    danger = false,
    busy = false,
    onConfirm,
    onCancel,
}: {
    open: boolean;
    title: string;
    message: React.ReactNode;
    confirmLabel?: string;
    cancelLabel?: string;
    danger?: boolean;
    busy?: boolean;
    onConfirm: () => void;
    onCancel: () => void;
}) {
    const confirmRef = useRef<HTMLButtonElement>(null);

    // 열릴 때 실행 버튼에 포커스 + Esc로 취소 (busy 중에는 닫기 차단).
    useEffect(() => {
        if (!open) return;
        confirmRef.current?.focus();
        const onKey = (e: KeyboardEvent) => {
            if (e.key === 'Escape' && !busy) onCancel();
        };
        window.addEventListener('keydown', onKey);
        return () => window.removeEventListener('keydown', onKey);
    }, [open, busy, onCancel]);

    if (!open) return null;

    return (
        <div
            className="modal-backdrop"
            role="presentation"
            onClick={() => {
                if (!busy) onCancel();
            }}
        >
            <div
                className="modal-card"
                role="dialog"
                aria-modal="true"
                aria-label={title}
                onClick={(e) => e.stopPropagation()}
            >
                <h3 className="modal-title">{title}</h3>
                <div className="modal-body">{message}</div>
                <div className="modal-actions">
                    <Button variant="ghost" onClick={onCancel} disabled={busy}>
                        {cancelLabel}
                    </Button>
                    <Button
                        ref={confirmRef}
                        variant={danger ? 'danger' : 'primary'}
                        onClick={onConfirm}
                        disabled={busy}
                    >
                        {busy ? '처리 중…' : confirmLabel}
                    </Button>
                </div>
            </div>
        </div>
    );
}
