'use client';

import { useEffect, useState } from 'react';
import { api, isIntakeDenied, isIntakeQueued } from '@/lib/api';
import type { MailboxMessage } from '@/types/game';
import MessagePlate from './MessagePlate';

const PUBLIC_MAILBOX = 9999;
const NATIONAL_MAILBOX_BASE = 9000;

export interface MessagePanelProps {
    generalId: number;
    nationId?: number;
    /** Parent refresh signal (SSE turnCompleted) — bump re-fetches the active mailbox. */
    refreshKey?: number;
    onToast: (msg: string, type: 'success' | 'error' | 'info') => void;
}

type Channel = { key: string; label: string; mailbox: number };

function defaultMailbox(generalId: number, nationId?: number): number {
    return nationId != null && nationId !== 0 ? NATIONAL_MAILBOX_BASE + nationId : generalId;
}

export default function MessagePanel({ generalId, nationId, refreshKey, onToast }: MessagePanelProps) {
    const channels: Channel[] = [
        ...(nationId != null && nationId !== 0
            ? [{ key: 'national', label: '국가 메시지', mailbox: NATIONAL_MAILBOX_BASE + nationId }]
            : []),
        { key: 'public', label: '전체 메시지', mailbox: PUBLIC_MAILBOX },
        { key: 'private', label: '개인 메시지', mailbox: generalId },
    ];

    const [active, setActive] = useState<number>(() => defaultMailbox(generalId, nationId));
    const [messages, setMessages] = useState<MailboxMessage[] | null>(null);
    const [failed, setFailed] = useState(false);
    const [loadSeq, setLoadSeq] = useState(0);
    const [sendText, setSendText] = useState('');
    const [sending, setSending] = useState(false);

    useEffect(() => {
        let on = true;
        setMessages(null);
        setFailed(false);
        api.mailbox<MailboxMessage[]>(active)
            .then((list) => on && setMessages([...list].sort((a, b) => (b.id ?? 0) - (a.id ?? 0))))
            .catch(() => on && setFailed(true));
        return () => {
            on = false;
        };
    }, [active, refreshKey, loadSeq]);

    const reload = () => setLoadSeq((n) => n + 1);

    async function handleSend() {
        if (sending) return;
        const text = sendText.trim();
        if (!text) return;
        setSending(true);
        try {
            const out = await api.commands.sendMessage({ mailbox: active, text }, generalId);
            if (isIntakeQueued(out)) {
                onToast('서신을 접수했습니다.', 'success');
                setSendText('');
                reload();
            } else if (isIntakeDenied(out)) {
                onToast(out.reason ?? '서신을 보낼 수 없습니다.', 'error');
            } else {
                onToast('서신 처리 상태를 확인할 수 없습니다.', 'error');
            }
        } catch {
            onToast('서신 발송에 실패했습니다.', 'error');
        } finally {
            setSending(false);
        }
    }

    return (
        <section className="message-panel" id="msgPanel" aria-label="메시지">
            <form
                className="msg-input-form"
                onSubmit={(e) => {
                    e.preventDefault();
                    void handleSend();
                }}
            >
                <select
                    className="msg-mailbox-select"
                    value={active}
                    onChange={(e) => setActive(Number(e.target.value))}
                    disabled={sending}
                >
                    <optgroup label="즐겨찾기">
                        {channels.map((c) => (
                            <option key={c.key} value={c.mailbox}>
                                {c.label}
                            </option>
                        ))}
                    </optgroup>
                </select>
                <input
                    type="text"
                    maxLength={99}
                    className="msg-input"
                    placeholder="서신을 입력하세요"
                    value={sendText}
                    onChange={(e) => setSendText(e.target.value)}
                    disabled={sending}
                />
                <button type="submit" className="msg-send-btn" disabled={sending || !sendText.trim()}>
                    서신전달&amp;갱신
                </button>
            </form>

            <div className="msg-list">
                {messages == null && !failed && (
                    <div className="msg-empty"><span className="spinner" /></div>
                )}
                {failed && <div className="msg-empty">메시지를 불러올 수 없습니다.</div>}
                {messages != null && messages.length === 0 && (
                    <div className="msg-empty">메시지가 없습니다.</div>
                )}
                {messages?.map((m) => (
                    <MessagePlate
                        key={m.id ?? `${m.src}-${m.time}`}
                        message={m}
                        generalId={generalId}
                        onActed={reload}
                        onToast={onToast}
                    />
                ))}
            </div>
        </section>
    );
}
