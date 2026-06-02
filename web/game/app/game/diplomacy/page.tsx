'use client';

import { useEffect, useState, useCallback } from 'react';

const API_BASE = process.env.NEXT_PUBLIC_GAME_API_URL ?? 'http://localhost:8081';

interface Nation {
    id: number;
    name: string;
    color: string;
}

interface DiplomacyRow {
    id: number;
    srcNationId: number;
    destNationId: number;
    state: number;
    term: number;
}

const STATE_LABEL: Record<number, { label: string; bg: string; text: string }> = {
    0: { label: '교전', bg: 'bg-red-600', text: 'text-white' },
    1: { label: '선전포고', bg: 'bg-orange-500', text: 'text-white' },
    2: { label: '통상', bg: 'bg-blue-500', text: 'text-white' },
    7: { label: '불가침', bg: 'bg-green-600', text: 'text-white' },
};

const DEFAULT_STYLE = { label: '중립', bg: 'bg-gray-400', text: 'text-white' };

function getStateStyle(state: number) {
    return STATE_LABEL[state] ?? DEFAULT_STYLE;
}

export default function DiplomacyPage() {
    const [nations, setNations] = useState<Nation[]>([]);
    const [diplomacy, setDiplomacy] = useState<DiplomacyRow[]>([]);
    const [myNationId, setMyNationId] = useState<number>(1);
    const [generalId, setGeneralId] = useState<number>(1);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string>('');
    const [toast, setToast] = useState<string>('');

    const fetchData = useCallback(async () => {
        try {
            const [nRes, dRes] = await Promise.all([
                fetch(`${API_BASE}/api/nations`),
                fetch(`${API_BASE}/api/diplomacy?nationId=${myNationId}`),
            ]);
            if (nRes.ok) setNations(await nRes.json());
            if (dRes.ok) setDiplomacy(await dRes.json());
            setError('');
        } catch (e) {
            setError('데이터를 불러올 수 없습니다.');
        } finally {
            setLoading(false);
        }
    }, [myNationId]);

    useEffect(() => {
        fetchData();
    }, [fetchData]);

    useEffect(() => {
        const es = new EventSource(`${API_BASE}/realtime/events`);
        es.addEventListener('realtime', () => fetchData());
        es.onerror = () => es.close();
        return () => es.close();
    }, [fetchData]);

    async function sendCommand(code: string, argJson?: string) {
        const res = await fetch(`${API_BASE}/api/command/${code}?generalId=${generalId}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: argJson ?? '{}',
        });
        const data = await res.json();
        if (data.status === 'AVAILABLE') {
            setToast('명령이 접수되었습니다.');
        } else {
            setToast(data.reason ?? '명령을 실행할 수 없습니다.');
        }
        setTimeout(() => setToast(''), 3000);
    }

    function getState(src: number, dest: number): number {
        const row = diplomacy.find(d => d.srcNationId === src && d.destNationId === dest);
        return row?.state ?? -1;
    }

    function getTerm(src: number, dest: number): number {
        const row = diplomacy.find(d => d.srcNationId === src && d.destNationId === dest);
        return row?.term ?? 0;
    }

    const sortedNations = [...nations].sort((a, b) => a.id - b.id);

    return (
        <main className="min-h-screen bg-gray-900 text-gray-100 p-4">
            <h1 className="text-2xl font-bold mb-4">외교 관계</h1>

            <div className="flex gap-4 mb-4 flex-wrap">
                <label className="flex items-center gap-2">
                    <span className="text-sm text-gray-400">내 국가</span>
                    <select
                        className="bg-gray-800 border border-gray-600 rounded px-2 py-1 text-sm"
                        value={myNationId}
                        onChange={e => setMyNationId(Number(e.target.value))}
                    >
                        {sortedNations.map(n => (
                            <option key={n.id} value={n.id}>{n.name}</option>
                        ))}
                    </select>
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
                    onClick={fetchData}
                    className="bg-blue-600 hover:bg-blue-500 text-white text-sm px-3 py-1 rounded"
                >
                    새로고침
                </button>
            </div>

            {loading && <p className="text-gray-400">로딩 중...</p>}
            {error && <p className="text-red-400">{error}</p>}

            {toast && (
                <div className="fixed top-4 right-4 bg-gray-800 border border-gray-600 text-white px-4 py-2 rounded shadow-lg z-50">
                    {toast}
                </div>
            )}

            <div className="overflow-x-auto">
                <table className="w-full text-sm border-collapse">
                    <thead>
                        <tr className="bg-gray-800">
                            <th className="border border-gray-700 px-2 py-1 text-left">국가</th>
                            {sortedNations.map(n => (
                                <th key={n.id} className="border border-gray-700 px-2 py-1 text-center min-w-[80px]">
                                    {n.name}
                                </th>
                            ))}
                        </tr>
                    </thead>
                    <tbody>
                        {sortedNations.map(src => (
                            <tr key={src.id} className="hover:bg-gray-800/50">
                                <td className="border border-gray-700 px-2 py-1 font-medium" style={{ color: src.color }}>
                                    {src.name}
                                </td>
                                {sortedNations.map(dest => {
                                    if (src.id === dest.id) {
                                        return (
                                            <td key={dest.id} className="border border-gray-700 px-2 py-1 text-center bg-gray-800/30">
                                                —
                                            </td>
                                        );
                                    }
                                    const state = getState(src.id, dest.id);
                                    const term = getTerm(src.id, dest.id);
                                    const style = getStateStyle(state);
                                    return (
                                        <td key={dest.id} className="border border-gray-700 px-1 py-1 text-center">
                                            <div className={`inline-block px-2 py-0.5 rounded text-xs font-medium ${style.bg} ${style.text}`}>
                                                {style.label}
                                                {term > 0 && <span className="ml-1 opacity-80">({term})</span>}
                                            </div>
                                            {src.id === myNationId && (
                                                <div className="flex gap-0.5 mt-1 justify-center">
                                                    {state === 0 && (
                                                        <button
                                                            onClick={() => sendCommand('che_종전제의', JSON.stringify({ targetNationId: dest.id }))}
                                                            className="text-[10px] bg-green-700 hover:bg-green-600 text-white px-1 rounded"
                                                        >
                                                            종전
                                                        </button>
                                                    )}
                                                    {state === 7 && (
                                                        <button
                                                            onClick={() => sendCommand('che_불가침파기제의', JSON.stringify({ targetNationId: dest.id }))}
                                                            className="text-[10px] bg-red-700 hover:bg-red-600 text-white px-1 rounded"
                                                        >
                                                            파기
                                                        </button>
                                                    )}
                                                    {(state === -1 || state === 2) && (
                                                        <>
                                                            <button
                                                                onClick={() => sendCommand('che_불가침제의', JSON.stringify({ targetNationId: dest.id }))}
                                                                className="text-[10px] bg-green-700 hover:bg-green-600 text-white px-1 rounded"
                                                            >
                                                                불가침
                                                            </button>
                                                            <button
                                                                onClick={() => sendCommand('che_선전포고', JSON.stringify({ targetNationId: dest.id }))}
                                                                className="text-[10px] bg-red-700 hover:bg-red-600 text-white px-1 rounded"
                                                            >
                                                                선전
                                                            </button>
                                                        </>
                                                    )}
                                                </div>
                                            )}
                                        </td>
                                    );
                                })}
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>
        </main>
    );
}
