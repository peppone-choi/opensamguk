'use client';

import { useEffect, useMemo, useState } from 'react';
import { ReasonTooltip } from '@opensamguk/ui';
import { api } from '@/lib/api';
import { submitCommandAndAwaitResult } from '@/lib/commandSubmit';
import type { MailboxMessage, PublicGeneral } from '@/types/game';
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
    // 개인 서신은 받는 장수를 골라야 보낼 수 있다. 읽기는 내 메일함(generalId)이지만, 보내기 주소는
    // **받는 사람의 장수 id** 다(MessageHandler: `0 < mailbox < 9000` → mailbox 가 곧 수신 장수).
    // 이 칸이 없으면 mailbox 가 내 id 로 나가서 엔진이 「자기 자신에게는 개인 서신을 보낼 수 없습니다」로 거절한다.
    const [recipients, setRecipients] = useState<PublicGeneral[] | null>(null);
    const [recipientsFailed, setRecipientsFailed] = useState(false);
    const [recipientId, setRecipientId] = useState<number | ''>('');

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

    const isPrivate = active === generalId;

    // 장수 목록은 개인 탭을 처음 열 때만 가져온다.
    useEffect(() => {
        if (!isPrivate || recipients != null || recipientsFailed) return;
        let on = true;
        api.generalsList()
            .then((list) => on && setRecipients(list))
            .catch(() => on && setRecipientsFailed(true));
        return () => {
            on = false;
        };
    }, [isPrivate, recipients, recipientsFailed]);

    // 엔진이 거절하는 주소는 후보에서 뺀다 — 자기 자신(「자기 자신에게는…」)과 순수 NPC(npc >= 2,
    // 「수신할 수 없는 장수입니다」). MessageHandler 의 수신자 계약과 같은 규칙이다.
    const recipientOptions = useMemo(
        () =>
            (recipients ?? [])
                .filter((g) => g.generalId !== generalId && g.npc < 2)
                .sort((a, b) => a.name.localeCompare(b.name, 'ko')),
        [generalId, recipients],
    );

    const sendBlockedReason = (() => {
        if (!isPrivate) return null;
        if (recipientsFailed) return '장수 목록을 불러오지 못해 받는 사람을 고를 수 없습니다.';
        if (recipients == null) return '장수 목록을 불러오는 중입니다.';
        if (recipientId === '') return '받는 장수를 먼저 고르세요.';
        return null;
    })();

    const reload = () => setLoadSeq((n) => n + 1);

    async function handleSend() {
        if (sending) return;
        const text = sendText.trim();
        if (!text) return;
        if (sendBlockedReason) return;
        // 개인 탭에서만 주소가 달라진다 — 읽는 메일함(내 id)이 아니라 받는 장수의 id 로 보낸다.
        const mailbox = isPrivate ? Number(recipientId) : active;
        setSending(true);
        try {
            const out = await submitCommandAndAwaitResult(() => api.commands.sendMessage({ mailbox, text }, generalId));
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
                {isPrivate && (
                    <label className="msg-to">
                        <span className="msg-to__label">받는 장수</span>
                        <select
                            className="msg-to__select os-inset"
                            aria-label="받는 장수"
                            value={recipientId}
                            disabled={sending || recipients == null || recipientsFailed}
                            onChange={(e) => setRecipientId(e.target.value === '' ? '' : Number(e.target.value))}
                        >
                            <option value="">
                                {recipientsFailed ? '목록 없음' : recipients == null ? '불러오는 중…' : '선택'}
                            </option>
                            {recipientOptions.map((g) => (
                                <option key={g.generalId} value={g.generalId}>
                                    {g.name}
                                    {g.nationName ? ` · ${g.nationName}` : ' · 재야'}
                                </option>
                            ))}
                        </select>
                    </label>
                )}
                <input
                    type="text"
                    maxLength={99}
                    className="msg-input os-inset"
                    placeholder="서신을 입력하세요"
                    value={sendText}
                    onChange={(e) => setSendText(e.target.value)}
                    disabled={sending}
                />
                {/* 비활성 버튼은 사유를 달고 점선으로 둔다(리디자인 규칙) — 왜 못 누르는지 화면에서 알 수 있어야 한다. */}
                {sendBlockedReason ? (
                    <ReasonTooltip reason={sendBlockedReason}>
                        <button type="submit" className="msg-send-btn os-button os-button--sm msg-send-btn--blocked" disabled aria-disabled="true">
                            서신전달&amp;갱신
                        </button>
                    </ReasonTooltip>
                ) : (
                    <button type="submit" className="msg-send-btn os-button os-button--primary os-button--sm" disabled={sending || !sendText.trim()}>
                        서신전달&amp;갱신
                    </button>
                )}
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
