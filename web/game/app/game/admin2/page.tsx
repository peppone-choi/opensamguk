'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import { api } from '../../../lib/api';
import type { AdminBlockedWrite, AdminGeneralModerationResponse, AdminGeneralModerationRow } from '../../../lib/api';

function errorText(e: unknown, fallback = '데이터를 불러올 수 없습니다.'): string {
    const msg = e instanceof Error ? e.message : '';
    if (msg.startsWith('403')) return '관리자 권한이 필요합니다.';
    if (msg.startsWith('401')) return '로그인이 필요합니다.';
    return msg || fallback;
}

function optionStyle(g: AdminGeneralModerationRow): React.CSSProperties {
    const style: React.CSSProperties = {};
    if (g.block > 0) style.backgroundColor = 'red';
    if (g.npc >= 2) style.color = 'cyan';
    else if (g.npc === 1) style.color = 'skyblue';
    return style;
}

function ActionButtons({
    actions,
    disabled,
    onAction,
}: {
    actions: AdminBlockedWrite[];
    disabled: boolean;
    onAction: (action: AdminBlockedWrite) => void;
}) {
    return (
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            {actions.map((a) => (
                <button
                    key={a.label}
                    disabled={disabled || !a.enabled || !a.code}
                    title={a.enabled ? a.reason : a.reason}
                    onClick={() => onAction(a)}
                >
                    {a.label}
                </button>
            ))}
        </div>
    );
}

export default function Admin2Page() {
    const [data, setData] = useState<AdminGeneralModerationResponse | null>(null);
    const [selected, setSelected] = useState<number[]>([]);
    const [message, setMessage] = useState('');
    const [loading, setLoading] = useState(true);
    const [actionLoading, setActionLoading] = useState<string | null>(null);
    const [error, setError] = useState('');
    const [notice, setNotice] = useState('');

    const load = useCallback(async () => {
        setLoading(true);
        try {
            const d = await api.admin.generalModeration();
            setData(d);
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

    const selectedRows = useMemo(
        () => data?.generals.filter((g) => selected.includes(g.no)) ?? [],
        [data?.generals, selected],
    );

    const runAction = useCallback(
        async (action: AdminBlockedWrite) => {
            if (!action.enabled || !action.code) return;
            const isBulk = action.code.endsWith('All');
            const targetIds = isBulk ? data?.generals.map((g) => g.no) ?? [] : selected;
            if (targetIds.length === 0) {
                setError('대상 장수를 선택하세요.');
                return;
            }
            const text = message.slice(0, 255);
            if (action.code === 'sendMessage' && text.length > 255) {
                setError('메세지는 255자 이하여야 합니다.');
                return;
            }
            setActionLoading(action.code);
            setError('');
            setNotice('');
            try {
                const result = await api.admin.generalModerationAction({
                    action: action.code,
                    generalIds: targetIds,
                    ...(action.code === 'sendMessage' ? { message: text } : {}),
                });
                setNotice(`${action.label}: ${result.affected}건 요청했습니다.`);
                if (action.code === 'sendMessage') setMessage('');
                await load();
            } catch (e) {
                setError(errorText(e, '관리자 조치에 실패했습니다.'));
            } finally {
                setActionLoading(null);
            }
        },
        [data?.generals, load, message, selected],
    );

    return (
        <Shell>
            <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-md)' }}>회원 관리</h1>

            {loading && <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>}
            {error && <p style={{ color: 'var(--crimson)' }}>{error}</p>}

            {data && !error && (
                <>
                    <GameCard style={{ marginBottom: 'var(--space-lg)' }}>
                        <table className="game-table" style={{ width: '100%' }}>
                            <tbody>
                                <tr>
                                    <th style={{ width: 120, textAlign: 'center' }}>접속제한</th>
                                    <td>
                                        <ActionButtons actions={data.bulkActions} disabled={actionLoading != null} onAction={runAction} />
                                    </td>
                                </tr>
                            </tbody>
                        </table>
                    </GameCard>

                    <GameCard style={{ marginBottom: 'var(--space-lg)' }}>
                        <div style={{ display: 'grid', gridTemplateColumns: 'minmax(220px, 280px) 1fr', gap: 'var(--space-md)' }}>
                            <div>
                                <div style={{ marginBottom: 8, lineHeight: 1.7 }}>
                                    회원선택<br />
                                    <span style={{ color: 'cyan' }}>NPC</span><br />
                                    <span style={{ color: 'skyblue' }}>NPC유저</span><br />
                                    <span style={{ color: 'red' }}>접속제한</span><br />
                                    <b style={{ backgroundColor: 'red' }}>블럭회원</b>
                                </div>
                                <select
                                    multiple
                                    size={20}
                                    value={selected.map(String)}
                                    onChange={(e) => setSelected(Array.from(e.currentTarget.selectedOptions, (o) => Number(o.value)))}
                                    style={{ width: '100%', minHeight: 360, background: '#000', color: '#fff', fontSize: 14 }}
                                >
                                    {data.generals.map((g) => (
                                        <option key={g.no} value={g.no} style={optionStyle(g)}>
                                            {g.name}
                                        </option>
                                    ))}
                                </select>
                            </div>

                            <div style={{ display: 'grid', gap: 12, alignContent: 'start' }}>
                                {notice && <p style={{ margin: 0, color: 'var(--sam-green)' }}>{notice}</p>}
                                <section>
                                    <h2 style={{ fontSize: 'var(--text-base)', margin: '0 0 8px' }}>블럭</h2>
                                    <ActionButtons actions={data.selectedActions.slice(0, 5)} disabled={actionLoading != null} onAction={runAction} />
                                    <p style={{ margin: '8px 0 0', color: 'var(--text-muted)' }}>1단계:발언권, 2단계:턴블럭</p>
                                </section>
                                <section>
                                    <h2 style={{ fontSize: 'var(--text-base)', margin: '0 0 8px' }}>강제 사망</h2>
                                    <ActionButtons actions={data.selectedActions.slice(5, 6)} disabled={actionLoading != null} onAction={runAction} />
                                </section>
                                <section>
                                    <h2 style={{ fontSize: 'var(--text-base)', margin: '0 0 8px' }}>이벤트2</h2>
                                    <ActionButtons actions={data.selectedActions.slice(6, 11)} disabled={actionLoading != null} onAction={runAction} />
                                </section>
                                <section>
                                    <h2 style={{ fontSize: 'var(--text-base)', margin: '0 0 8px' }}>접속제한</h2>
                                    <ActionButtons actions={data.selectedActions.slice(11, 13)} disabled={actionLoading != null} onAction={runAction} />
                                </section>
                                <section>
                                    <h2 style={{ fontSize: 'var(--text-base)', margin: '0 0 8px' }}>명령 설정</h2>
                                    <ActionButtons actions={data.selectedActions.slice(13, 15)} disabled={actionLoading != null} onAction={runAction} />
                                </section>
                                <section>
                                    <h2 style={{ fontSize: 'var(--text-base)', margin: '0 0 8px' }}>메세지 전달</h2>
                                    <input
                                        value={message}
                                        onChange={(e) => setMessage(e.target.value.slice(0, 255))}
                                        maxLength={255}
                                        style={{ width: 'min(100%, 520px)', background: '#000', color: '#fff', marginRight: 8 }}
                                    />
                                    <button
                                        disabled={
                                            actionLoading != null ||
                                            selected.length === 0 ||
                                            !data.selectedActions[15]?.enabled ||
                                            !data.selectedActions[15]?.code
                                        }
                                        title={data.selectedActions[15]?.reason}
                                        onClick={() => runAction(data.selectedActions[15])}
                                    >
                                        {actionLoading === data.selectedActions[15]?.code ? '처리 중...' : '메세지 전달'}
                                    </button>
                                </section>
                            </div>
                        </div>
                    </GameCard>

                    <GameCard>
                        <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
                            <button onClick={load}>새로고침</button>
                            <span style={{ color: 'var(--text-muted)' }}>
                                선택 {selectedRows.length}명 / 전체 {data.generals.length}명
                            </span>
                        </div>
                        {selectedRows.length > 0 && (
                            <div style={{ overflowX: 'auto', marginTop: 12 }}>
                                <table className="game-table" style={{ minWidth: 720 }}>
                                    <thead>
                                        <tr>
                                            <th>장수</th>
                                            <th>NPC</th>
                                            <th>블럭</th>
                                            <th>삭턴</th>
                                            <th>국가</th>
                                            <th>턴시각</th>
                                            <th>0턴</th>
                                            <th>1턴</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {selectedRows.map((g) => (
                                            <tr key={g.no}>
                                                <td>{g.name}</td>
                                                <td style={{ textAlign: 'center' }}>{g.npc}</td>
                                                <td style={{ textAlign: 'center' }}>{g.block}</td>
                                                <td style={{ textAlign: 'center' }}>{g.killturn ?? '-'}</td>
                                                <td style={{ textAlign: 'center' }}>{g.nationId}</td>
                                                <td>{g.turnTime ?? '-'}</td>
                                                <td>{g.command0 ?? '-'}</td>
                                                <td>{g.command1 ?? '-'}</td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </div>
                        )}
                    </GameCard>
                </>
            )}
        </Shell>
    );
}
