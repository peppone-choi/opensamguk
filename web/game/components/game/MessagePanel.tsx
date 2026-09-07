'use client';

import { useEffect, useState } from 'react';
import { api } from '@/lib/api';
import { submitCommandAndAwaitResult } from '@/lib/commandSubmit';
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
            const out = await submitCommandAndAwaitResult(() => api.commands.sendMessage({ mailbox: active, text }, generalId));
            if (out.status === 'applied') {
                onToast('서신을 접수했습니다.', 'success');
                setSendText('');
                reload();
            } else if (out.status === 'rejected') {
                onToast(out.reason ?? '서신을 보낼 수 없습니다.', 'error');
            } else {
                onToast(out.reason, 'info');
            }
        } catch {
            onToast('서신 발송에 실패했습니다.', 'error');
        } finally {
            setSending(false);
        }
    }

    return (
        <section className="message-panel os-panel os-panel--static" id="msgPanel" aria-label="메시지">
            {/* 3탭(국가·전체·개인) — ADR-LITE-049 S2 「하단 메시지 3탭」. 라벨은 기존 채널 라벨 그대로. */}
            <div className="os-section-header msg-head">
                <span className="os-section-header__bar" aria-hidden="true" />
                <h3 className="os-section-header__title">메시지</h3>
                <span className="os-section-header__spacer" />
                <div className="os-pill-tabs" role="tablist" aria-label="메시지 채널">
                    {channels.map((c) => (
                        <button
                            key={c.key}
                            type="button"
                            role="tab"
                            aria-selected={active === c.mailbox}
                            className={active === c.mailbox ? 'os-pill-tabs__on' : undefined}
                            disabled={sending}
                            onClick={() => setActive(c.mailbox)}
                        >
                            {c.label}
                        </button>
                    ))}
                </div>
            </div>
            <form
                className="msg-input-form"
                onSubmit={(e) => {
                    e.preventDefault();
                    void handleSend();
                }}
            >
                <input
                    type="text"
                    maxLength={99}
                    className="msg-input os-inset"
                    placeholder="서신을 입력하세요"
                    value={sendText}
                    onChange={(e) => setSendText(e.target.value)}
                    disabled={sending}
                />
                <button type="submit" className="msg-send-btn os-button os-button--primary os-button--sm" disabled={sending || !sendText.trim()}>
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
