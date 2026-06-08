'use client';

import { useEffect, useState, useCallback } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import StatusBadge from '../../../components/StatusBadge';
import { api } from '../../../lib/api';
import { INFINITE_DATE, TOAST_DURATION_MS } from '../../../lib/constants';
import { mailboxIdForScope, type MailboxScope } from '../../../lib/mailbox';
import type { FrontInfoResponse } from '../../../lib/types';

interface MailMessage {
    id: number;
    type: string;
    srcId: number;
    srcName: string;
    destId: number;
    text: string;
    date: string;
    validUntil: string;
    isInboxMail: boolean;
    option?: Record<string, unknown>;
    read?: boolean;
}

const TYPE_LABEL: Record<string, string> = {
    private: '개인',
    public: '전체',
    national: '국가',
    diplomacy: '외교',
};

const TYPE_VARIANT: Record<string, 'gold' | 'jade' | 'muted' | 'crimson'> = {
    private: 'muted',
    public: 'jade',
    national: 'gold',
    diplomacy: 'crimson',
};

export default function MailboxPage() {
    const [messages, setMessages] = useState<MailMessage[]>([]);
    const [scope, setScope] = useState<MailboxScope>('private');
    const [identity, setIdentity] = useState<{ generalId: number | null; nationId: number }>({ generalId: null, nationId: 0 });
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string>('');
    const [toast, setToast] = useState<string>('');
    // 수락/거절 요청 진행 중인 메시지 id — 중복 클릭(이중 수락) 방지용
    const [pendingId, setPendingId] = useState<number | null>(null);

    const fetchMessages = useCallback(async () => {
        setLoading(true);
        try {
            const mailboxId = mailboxIdForScope(scope, identity);
            if (mailboxId == null) {
                setMessages([]);
                setError(scope === 'national' ? '소속 국가가 없습니다.' : '장수 정보가 없습니다.');
                return;
            }
            const data = await api.mailbox<MailMessage[]>(mailboxId);
            setMessages(data);
            setError('');
        } catch {
            setError('메일함을 불러올 수 없습니다.');
        } finally {
            setLoading(false);
        }
    }, [identity, scope]);

    useEffect(() => {
        let on = true;
        api.frontInfo()
            .then((info: FrontInfoResponse) => {
                if (!on) return;
                setIdentity({
                    generalId: info.general?.generalId ?? null,
                    nationId: info.general?.nationId ?? 0,
                });
            })
            .catch(() => {
                if (on) setIdentity({ generalId: null, nationId: 0 });
            });
        return () => {
            on = false;
        };
    }, []);

    useEffect(() => {
        fetchMessages();
    }, [fetchMessages]);

    useEffect(() => {
        const es = new EventSource('/api/game/sse/turn');
        es.addEventListener('turnCompleted', () => fetchMessages());
        es.onerror = () => es.close();
        return () => es.close();
    }, [fetchMessages]);

    // 수락/거절은 game-api의 /api/messages/{id}/accept|decline로 직접 호출한다.
    // NEW CONTRACT: 수락 시 서버가 수락 명령(예: che_불가침수락)을 턴 데몬에 직접 예약(reserve)하므로
    // 클라이언트는 commandKey를 읽어 /api/command를 다시 호출하지 않는다(예전 디스패치 설계 폐기).
    async function handleAgree(msg: MailMessage) {
        // 요청 진행 중이면 재진입 금지 — 빠른 더블클릭에 의한 이중 수락 차단
        if (pendingId !== null) return;
        if (identity.generalId == null) {
            setToast('장수 정보가 없습니다.');
            setTimeout(() => setToast(''), TOAST_DURATION_MS);
            return;
        }
        setPendingId(msg.id);
        try {
            await api.messageAccept(msg.id, identity.generalId);
            // 수락은 턴 명령으로 예약된다 — 즉시 적용을 함의하지 않는 표현.
            setToast('수락했습니다.');
        } catch {
            setToast('수락 요청에 실패했습니다.');
        } finally {
            setPendingId(null);
        }
        setTimeout(() => setToast(''), TOAST_DURATION_MS);
        fetchMessages();
    }

    async function handleDecline(msg: MailMessage) {
        // 요청 진행 중이면 재진입 금지 — 빠른 더블클릭에 의한 이중 거절 차단
        if (pendingId !== null) return;
        if (identity.generalId == null) {
            setToast('장수 정보가 없습니다.');
            setTimeout(() => setToast(''), TOAST_DURATION_MS);
            return;
        }
        setPendingId(msg.id);
        try {
            await api.messageDecline(msg.id, identity.generalId);
            setToast('거절했습니다.');
        } catch {
            setToast('거절 요청에 실패했습니다.');
        } finally {
            setPendingId(null);
        }
        setTimeout(() => setToast(''), TOAST_DURATION_MS);
        fetchMessages();
    }

    const unreadCount = messages.filter(m => !m.read).length;

    return (
        <Shell>
            <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-md)' }}>메일함</h1>

            <div style={{ display: 'flex', gap: 'var(--space-md)', marginBottom: 'var(--space-md)', flexWrap: 'wrap', alignItems: 'center' }}>
                <div style={{ display: 'flex', gap: 'var(--space-xs)', flexWrap: 'wrap' }}>
                    {([
                        ['private', '개인'],
                        ['national', '국가'],
                        ['public', '전체'],
                    ] as const).map(([value, label]) => (
                        <button
                            key={value}
                            type="button"
                            onClick={() => setScope(value)}
                            style={{
                                background: scope === value ? 'var(--gold)' : 'transparent',
                                color: scope === value ? 'var(--bg-base)' : 'var(--text-secondary)',
                            }}
                        >
                            {label}
                        </button>
                    ))}
                </div>
                <button onClick={fetchMessages}>새로고침</button>
                {unreadCount > 0 && (
                    <StatusBadge variant="crimson">미읽음 {unreadCount}</StatusBadge>
                )}
            </div>

            {loading && <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>}
            {error && <p style={{ color: 'var(--crimson)' }}>{error}</p>}

            {toast && (
                <div className="toast" style={{ position: 'fixed', top: 'var(--space-md)', right: 'var(--space-md)', zIndex: 200 }}>
                    {toast}
                </div>
            )}

            <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-sm)' }}>
                {messages.length === 0 && !loading && (
                    <p style={{ color: 'var(--text-muted)' }}>메시지가 없습니다.</p>
                )}
                {messages.map(msg => {
                    const isDiplomacy = msg.type === 'diplomacy';
                    const hasAction = !!msg.option?.action;
                    const variant = TYPE_VARIANT[msg.type] ?? 'muted';
                    return (
                        <GameCard key={msg.id} className={msg.read ? 'muted' : ''}>
                            <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)', marginBottom: 'var(--space-xs)', flexWrap: 'wrap' }}>
                                {!msg.read && (
                                    <span style={{ width: 8, height: 8, background: 'var(--gold)', borderRadius: '50%', display: 'inline-block' }} />
                                )}
                                <StatusBadge variant={variant}>{TYPE_LABEL[msg.type] ?? msg.type}</StatusBadge>
                                <span style={{ fontSize: 'var(--text-sm)', fontWeight: 500 }}>{msg.srcName}</span>
                                <span style={{ fontSize: 'var(--text-xs)', color: 'var(--text-muted)' }}>{msg.date}</span>
                                {msg.validUntil && msg.validUntil !== INFINITE_DATE && (
                                    <span style={{ fontSize: 'var(--text-xs)', color: 'var(--text-muted)' }}>~{msg.validUntil}</span>
                                )}
                            </div>
                            <p style={{ fontSize: 'var(--text-sm)', color: 'var(--text-primary)', marginBottom: 'var(--space-sm)', whiteSpace: 'pre-wrap' }}>{msg.text}</p>
                            {isDiplomacy && hasAction && (
                                <div style={{ display: 'flex', gap: 'var(--space-sm)' }}>
                                    {/* 요청 진행 중에는 수락/거절 모두 비활성화 — 이중 제출 방지 */}
                                    <button onClick={() => handleAgree(msg)} disabled={pendingId !== null || identity.generalId == null}>수락</button>
                                    <button onClick={() => handleDecline(msg)} disabled={pendingId !== null || identity.generalId == null}>거절</button>
                                </div>
                            )}
                        </GameCard>
                    );
                })}
            </div>
        </Shell>
    );
}
