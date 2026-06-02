'use client';

import { useEffect, useState, useCallback } from 'react';

const API_BASE = process.env.NEXT_PUBLIC_GAME_API_URL ?? 'http://localhost:8081';

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
            const res = await fetch(`${API_BASE}/api/mailbox?mailboxId=${mailboxId}`);
            if (res.ok) {
                const data = await res.json();
                setMessages(data);
            } else {
                setError('메일함을 불러올 수 없습니다.');
            }
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
        const es = new EventSource(`${API_BASE}/realtime/events`);
        es.addEventListener('realtime', () => fetchMessages());
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
        const res = await fetch(`${API_BASE}/api/command/${action}_agree?generalId=${generalId}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ messageId: msg.id }),
        });
        const data = await res.json();
        setToast(data.status === 'AVAILABLE' ? '수락이 접수되었습니다.' : (data.reason ?? '수락할 수 없습니다.'));
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
        const res = await fetch(`${API_BASE}/api/command/${action}_decline?generalId=${generalId}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ messageId: msg.id }),
        });
        const data = await res.json();
        setToast(data.status === 'AVAILABLE' ? '거절이 접수되었습니다.' : (data.reason ?? '거절할 수 없습니다.'));
        setTimeout(() => setToast(''), 3000);
        fetchMessages();
    }

    const unreadCount = messages.filter(m => !m.read).length;

    return (
        <main className="min-h-screen bg-gray-900 text-gray-100 p-4">
            <h1 className="text-2xl font-bold mb-4">메일함</h1>

            <div className="flex gap-4 mb-4 flex-wrap items-center">
                <label className="flex items-center gap-2">
                    <span className="text-sm text-gray-400">메일함 ID</span>
                    <input
                        type="number"
                        className="bg-gray-800 border border-gray-600 rounded px-2 py-1 text-sm w-24"
                        value={mailboxId}
                        onChange={e => setMailboxId(Number(e.target.value))}
                    />
                </label>
                <label className="flex items-center gap-2">
                    <span className="text-sm text-gray-400">장수 ID</span>
                    <input
                        type="number"
                        className="bg-gray-800 border border-gray-600 rounded px-2 py-1 text-sm w-20"
                        value={generalId}
                        onChange={e => setGeneralId(Number(e.target.value))}
                    />
                </label>
                <button
                    onClick={fetchMessages}
                    className="bg-blue-600 hover:bg-blue-500 text-white text-sm px-3 py-1 rounded"
                >
                    새로고침
                </button>
                {unreadCount > 0 && (
                    <span className="bg-red-600 text-white text-xs px-2 py-0.5 rounded-full">
                        미읽음 {unreadCount}
                    </span>
                )}
            </div>

            {loading && <p className="text-gray-400">로딩 중...</p>}
            {error && <p className="text-red-400">{error}</p>}

            {toast && (
                <div className="fixed top-4 right-4 bg-gray-800 border border-gray-600 text-white px-4 py-2 rounded shadow-lg z-50">
                    {toast}
                </div>
            )}

            <div className="space-y-2">
                {messages.length === 0 && !loading && (
                    <p className="text-gray-500">메시지가 없습니다.</p>
                )}
                {messages.map(msg => {
                    const isDiplomacy = msg.type === 'diplomacy';
                    const hasAction = !!msg.option?.action;
                    return (
                        <div
                            key={msg.id}
                            className={`border rounded p-3 ${msg.read ? 'border-gray-700 bg-gray-800/50' : 'border-gray-600 bg-gray-800'}`}
                        >
                            <div className="flex items-center gap-2 mb-1 flex-wrap">
                                {!msg.read && (
                                    <span className="w-2 h-2 bg-blue-400 rounded-full inline-block" />
                                )}
                                <span className="text-xs bg-gray-700 px-1.5 py-0.5 rounded">
                                    {TYPE_LABEL[msg.type] ?? msg.type}
                                </span>
                                <span className="text-sm font-medium">{msg.srcName}</span>
                                <span className="text-xs text-gray-500">{msg.date}</span>
                                {msg.validUntil && msg.validUntil !== '9999-12-31' && (
                                    <span className="text-xs text-gray-500">
                                        ~{msg.validUntil}
                                    </span>
                                )}
                            </div>
                            <p className="text-sm text-gray-200 mb-2 whitespace-pre-wrap">{msg.text}</p>
                            {isDiplomacy && hasAction && (
                                <div className="flex gap-2">
                                    <button
                                        onClick={() => handleAgree(msg)}
                                        className="bg-green-600 hover:bg-green-500 text-white text-xs px-3 py-1 rounded"
                                    >
                                        수락
                                    </button>
                                    <button
                                        onClick={() => handleDecline(msg)}
                                        className="bg-red-600 hover:bg-red-500 text-white text-xs px-3 py-1 rounded"
                                    >
                                        거절
                                    </button>
                                </div>
                            )}
                        </div>
                    );
                })}
            </div>
        </main>
    );
}
