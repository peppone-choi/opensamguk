'use client';

// MessagePlate — a single message row (spec §7, legacy MessagePlate.vue). 64px icon slot + body. Header
// rules mirror the .vue: own-sent vs received bg colors, `【Name】` self-prefix, `<HH:MM:SS>` timestamp.
// Deleted → grey `삭제된 메시지입니다`. Action buttons 수락/거절 when the message carries an actionable
// proposal (here surfaced via accept/decline through api.messageAccept/Decline — confirm dialog).
import { useState } from 'react';
import { api, pollCommandResult } from '@/lib/api';
import type { MailboxMessage } from '@/types/game';

function timeText(iso: string): string {
    try {
        return new Date(iso).toLocaleTimeString('ko-KR', { hour12: false });
    } catch {
        return '';
    }
}

export interface MessagePlateProps {
    message: MailboxMessage;
    /** Caller's own general id — sent vs received styling + accept/decline target. */
    generalId: number;
    onActed?: () => void;
    onToast: (msg: string, type: 'success' | 'error' | 'info') => void;
}

export default function MessagePlate({ message, generalId, onActed, onToast }: MessagePlateProps) {
    const [busy, setBusy] = useState(false);
    const isSent = message.src === generalId;
    const text = message.text ?? '';
    const deleted = text.length === 0 || text === '삭제된 메시지입니다.';
    const srcName = message.srcTarget?.name || String(message.src);
    const destName = message.destTarget?.name || String(message.dest);
    const actionable = message.type === 'diplomacy' && Boolean(message.option?.action);

    async function act(kind: 'accept' | 'decline') {
        if (message.id == null) return;
        if (!window.confirm(kind === 'accept' ? '수락하시겠습니까?' : '거절하시겠습니까?')) return;
        setBusy(true);
        try {
            const accepted = kind === 'accept'
                ? await api.messageAccept(message.id, generalId)
                : await api.messageDecline(message.id, generalId);
            const result = await pollCommandResult(accepted.requestId);
            if (result == null) {
                onToast(kind === 'accept' ? '수락 요청을 접수했습니다.' : '거절 요청을 접수했습니다.', 'info');
            } else if (!result.ok) {
                onToast(result.reason ?? '처리 실패', 'error');
            } else {
                onToast(kind === 'accept' ? '수락했습니다.' : '거절했습니다.', 'success');
                onActed?.();
            }
        } catch (e) {
            onToast(e instanceof Error ? e.message : '처리 실패', 'error');
        } finally {
            setBusy(false);
        }
    }

    return (
        <div className={`msg-plate${isSent ? ' msg-sent' : ' msg-recv'}`}>
            <div className="msg-plate-head">
                {isSent ? `나 ▶ 【${destName}】` : `【${srcName}】 ▶ 나`}
                <span className="msg-plate-time"> &lt;{timeText(message.time)}&gt;</span>
            </div>
            <div className="msg-plate-body">
                {deleted ? <span className="msg-deleted">삭제된 메시지입니다</span> : text}
            </div>
            {/* Diplomacy/proposal accept-decline (game-api accept|decline endpoint). */}
            {!deleted && !isSent && actionable && (
                <div className="msg-plate-actions">
                    <button type="button" disabled={busy} onClick={() => act('accept')}>수락</button>
                    <button type="button" disabled={busy} onClick={() => act('decline')}>거절</button>
                </div>
            )}
        </div>
    );
}
