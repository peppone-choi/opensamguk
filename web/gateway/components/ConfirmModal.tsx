'use client';

import { ConfirmDialog } from '@opensamguk/ui';
import type { ReactNode } from 'react';

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
    message: ReactNode;
    confirmLabel?: string;
    cancelLabel?: string;
    danger?: boolean;
    busy?: boolean;
    onConfirm: () => void;
    onCancel: () => void;
}) {
    return (
        <ConfirmDialog
            open={open}
            title={title}
            message={message}
            confirmLabel={confirmLabel}
            cancelLabel={cancelLabel}
            danger={danger}
            busy={busy}
            onConfirm={onConfirm}
            onCancel={onCancel}
        />
    );
}
