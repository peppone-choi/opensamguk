'use client';

import { useEffect, useState, useCallback } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import StatusBadge from '../../../components/StatusBadge';
import { api } from '../../../lib/api';

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
    const [mailboxId, setMailboxId] = useState<number>(1);
    const [generalId, setGeneralId] = useState<number>(1);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string>('');
    const [toast, setToast] = useState<string>('');

    const fetchMessages = useCallback(async () => {
        setLoading(true);
        try {
            const data = await api.mailbox<MailMessage[]>();
            setMessages(data);
            setError('');
        } catch {
            setError('메일함을 불러올 수 없습니다.');
        } finally {
            setLoading(false);
        }
    }, [mailboxId]);

    useEffect(() => {
        fetchMessages();
    }, [fetchMessages]);

    useEffect(() => {
        const es = new EventSource('/api/game/sse/turn');
        es.addEventListener('turnCompleted', () => fetchMessages());
        es.onerror = () => es.close();
        return () => es.close();
    }, [fetchMessages]);

    async function handleAgree(msg: MailMessage) {
        const action = msg.option?.action as string | undefined;
        if (!action) {
            setToast('수락할 수 없는 메시지입니다.');
            setTimeout(() => setToast(''), 3000);
            return;
        }
        try {
            const data = await api.command<{ status: string; reason?: string }>(`${action}_agree`, { messageId: msg.id });
            setToast(data.status === 'AVAILABLE' ? '수락이 접수되었습니다.' : (data.reason ?? '수락할 수 없습니다.'));
        } catch {
            setToast('수락 요청에 실패했습니다.');
        }
        setTimeout(() => setToast(''), 3000);
        fetchMessages();
    }

    async function handleDecline(msg: MailMessage) {
        const action = msg.option?.action as string | undefined;
        if (!action) {
            setToast('거절할 수 없는 메시지입니다.');
            setTimeout(() => setToast(''), 3000);
            return;
        }
        try {
            const data = await api.command<{ status: string; reason?: string }>(`${action}_decline`, { messageId: msg.id });
            setToast(data.status === 'AVAILABLE' ? '거절이 접수되었습니다.' : (data.reason ?? '거절할 수 없습니다.'));
        } catch {
            setToast('거절 요청에 실패했습니다.');
        }
        setTimeout(() => setToast(''), 3000);
        fetchMessages();
    }

    const unreadCount = messages.filter(m => !m.read).length;

    return (
        <Shell>
            <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-md)' }}>메일함</h1>

            <div style={{ display: 'flex', gap: 'var(--space-md)', marginBottom: 'var(--space-md)', flexWrap: 'wrap', alignItems: 'center' }}>
                <label style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)' }}>
                    <span style={{ fontSize: 'var(--text-sm)', color: 'var(--text-secondary)' }}>메일함 ID</span>
                    <input type="number" style={{ width: '6rem' }} value={mailboxId} onChange={e => setMailboxId(Number(e.target.value))} />
                </label>
                <label style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)' }}>
                    <span style={{ fontSize: 'var(--text-sm)', color: 'var(--text-secondary)' }}>장수 ID</span>
                    <input type="number" style={{ width: '5rem' }} value={generalId} onChange={e => setGeneralId(Number(e.target.value))} />
                </label>
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
                                {msg.validUntil && msg.validUntil !== '9999-12-31' && (
                                    <span style={{ fontSize: 'var(--text-xs)', color: 'var(--text-muted)' }}>~{msg.validUntil}</span>
                                )}
                            </div>
                            <p style={{ fontSize: 'var(--text-sm)', color: 'var(--text-primary)', marginBottom: 'var(--space-sm)', whiteSpace: 'pre-wrap' }}>{msg.text}</p>
                            {isDiplomacy && hasAction && (
                                <div style={{ display: 'flex', gap: 'var(--space-sm)' }}>
                                    <button onClick={() => handleAgree(msg)}>수락</button>
                                    <button onClick={() => handleDecline(msg)}>거절</button>
                                </div>
                            )}
                        </GameCard>
                    );
                })}
            </div>
        </Shell>
    );
}
