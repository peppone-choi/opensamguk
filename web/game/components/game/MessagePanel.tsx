'use client';

// MessagePanel — multi-channel messaging (spec §7, legacy MessagePanel.vue). The legacy panel has four
// stacked channels (전체/국가/개인/외교) fed by a polling SammoAPI.Message feed + a contact-list selector
// + a SendMessage write. game-api today exposes only READS by mailbox id (GET /api/mailbox/{mailbox})
// and accept/decline; there is NO contact-list endpoint and NO send endpoint.
//
// So this is a READ-ONLY port (flagged): a mailbox selector over the favorites the legacy hardcodes
// (【전체 메세지】 = 9999, 【아국 메세지】 = 9000+nationId, 개인 = own generalId), each rendering its
// MessagePlate rows. Verbatim labels (전체/국가/개인 메시지, 메시지가 없습니다., 서신전달&갱신). SSE soft
// refresh is driven by the parent's refreshKey (re-mounts via key) — NOT a raw 2.5s poll. SEND is disabled
// with a flag note (no backend send endpoint).

import { useEffect, useState } from 'react';
import { api } from '@/lib/api';
import MessagePlate from './MessagePlate';

// game-api MessageResponse (MessageDto.kt) — the lean read shape.
export interface MailboxMessage {
    id: number | null;
    mailbox: number;
    type: string;
    src: number;
    dest: number;
    time: string;
    validUntil: string;
    message: string;
}

const PUBLIC_MAILBOX = 9999;

export interface MessagePanelProps {
    generalId: number;
    nationId?: number;
    /** Parent refresh signal (SSE turnCompleted) — bump re-fetches the active mailbox. */
    refreshKey?: number;
    onToast: (msg: string, type: 'success' | 'error' | 'info') => void;
}

type Channel = { key: string; label: string; mailbox: number };

export default function MessagePanel({ generalId, nationId, refreshKey, onToast }: MessagePanelProps) {
    const channels: Channel[] = [
        { key: 'public', label: '전체 메시지', mailbox: PUBLIC_MAILBOX },
        ...(nationId != null && nationId !== 0
            ? [{ key: 'national', label: '국가 메시지', mailbox: 9000 + nationId }]
            : []),
        { key: 'private', label: '개인 메시지', mailbox: generalId },
    ];

    const [active, setActive] = useState<number>(channels[0].mailbox);
    const [messages, setMessages] = useState<MailboxMessage[] | null>(null);
    const [failed, setFailed] = useState(false);

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
    }, [active, refreshKey]);

    const reload = () => setActive((m) => m); // no-op state churn; the effect dep refreshKey drives reload

    return (
        <section className="message-panel" id="msgPanel" aria-label="메시지">
            <div className="msg-input-form">
                <select
                    className="msg-mailbox-select"
                    value={active}
                    onChange={(e) => setActive(Number(e.target.value))}
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
                    placeholder="서신 보내기 (서버 미지원)"
                    disabled
                />
                <button type="button" className="msg-send-btn" disabled>
                    서신전달&amp;갱신
                </button>
            </div>

            <p className="msg-flag">서신 전송/연락처 API가 아직 없어 읽기 전용입니다.</p>

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
