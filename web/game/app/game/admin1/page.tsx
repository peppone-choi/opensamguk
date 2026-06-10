'use client';

import { useCallback, useEffect, useState } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import { api } from '../../../lib/api';
import type { AdminGameSettingsResponse } from '../../../lib/api';

const inputStyle: React.CSSProperties = {
    width: '100%',
    maxWidth: 720,
    background: '#000',
    color: '#fff',
};

function errorText(e: unknown): string {
    const msg = e instanceof Error ? e.message : '';
    if (msg.startsWith('403')) return '관리자 권한이 필요합니다.';
    if (msg.startsWith('401')) return '로그인이 필요합니다.';
    return '데이터를 불러올 수 없습니다.';
}

export default function Admin1Page() {
    const [data, setData] = useState<AdminGameSettingsResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');

    const load = useCallback(async () => {
        setLoading(true);
        try {
            setData(await api.admin.gameSettings());
            setError('');
        } catch (e) {
            setError(errorText(e));
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        load();
    }, [load]);

    return (
        <Shell>
            <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-md)' }}>게임 관리</h1>

            {loading && <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>}
            {error && <p style={{ color: 'var(--crimson)' }}>{error}</p>}

            {data && !error && (
                <>
                    <GameCard style={{ marginBottom: 'var(--space-lg)' }}>
                        <table className="game-table" style={{ width: '100%' }}>
                            <tbody>
                                <tr>
                                    <th style={{ width: 140, textAlign: 'right' }}>운영자메세지</th>
                                    <td>
                                        <input readOnly value={data.msg} style={inputStyle} />
                                    </td>
                                    <td style={{ width: 100 }}>
                                        <button disabled>변경</button>
                                    </td>
                                </tr>
                                <tr>
                                    <th style={{ textAlign: 'right' }}>중원정세추가</th>
                                    <td>
                                        <input readOnly value="" maxLength={80} style={inputStyle} />
                                    </td>
                                    <td>
                                        <button disabled>로그쓰기</button>
                                    </td>
                                </tr>
                                <tr>
                                    <th style={{ textAlign: 'right' }}>시작시간변경</th>
                                    <td>
                                        <input readOnly value={data.starttime ?? ''} style={{ ...inputStyle, textAlign: 'right', maxWidth: 260 }} />
                                    </td>
                                    <td>
                                        <button disabled>변경1</button>
                                    </td>
                                </tr>
                                <tr>
                                    <th style={{ textAlign: 'right' }}>최대 장수</th>
                                    <td>
                                        <input readOnly value={data.maxgeneral ?? ''} style={{ ...inputStyle, textAlign: 'right', maxWidth: 80 }} />
                                    </td>
                                    <td>
                                        <button disabled>변경2</button>
                                    </td>
                                </tr>
                                <tr>
                                    <th style={{ textAlign: 'right' }}>최대 국가</th>
                                    <td>
                                        <input readOnly value={data.maxnation ?? ''} style={{ ...inputStyle, textAlign: 'right', maxWidth: 80 }} />
                                    </td>
                                    <td>
                                        <button disabled>변경3</button>
                                    </td>
                                </tr>
                                <tr>
                                    <th style={{ textAlign: 'right' }}>시작 년도</th>
                                    <td>
                                        <input readOnly value={data.startyear ?? ''} style={{ ...inputStyle, textAlign: 'right', maxWidth: 80 }} />
                                    </td>
                                    <td>
                                        <button disabled>변경4</button>
                                    </td>
                                </tr>
                                <tr>
                                    <th style={{ textAlign: 'right' }}>최근 갱신 시간</th>
                                    <td colSpan={2}>{data.turntime ?? '-'}</td>
                                </tr>
                                <tr>
                                    <th style={{ textAlign: 'right' }}>현재 연월</th>
                                    <td colSpan={2}>
                                        {data.year ?? '-'}년 {data.month ?? '-'}월 · {data.scenarioCode ?? '-'}
                                    </td>
                                </tr>
                                <tr>
                                    <th style={{ textAlign: 'right' }}>턴시간</th>
                                    <td colSpan={2}>
                                        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                                            {data.turnOptions.map((m) => (
                                                <button key={m} disabled style={m === data.turnterm ? { borderColor: 'var(--gold)' } : undefined}>
                                                    {m}분턴
                                                </button>
                                            ))}
                                        </div>
                                    </td>
                                </tr>
                            </tbody>
                        </table>
                    </GameCard>

                    <GameCard>
                        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                            <button onClick={load}>새로고침</button>
                            {data.blockedWrites.map((w) => (
                                <button key={w.label} disabled title={w.reason}>
                                    {w.label}
                                </button>
                            ))}
                        </div>
                    </GameCard>
                </>
            )}
        </Shell>
    );
}
