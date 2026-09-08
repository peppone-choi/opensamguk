'use client';

import { useCallback, useEffect, useState } from 'react';
import PageHead from '../PageHead';
import GameCard from '@/components/GameCard';
import { api } from '@/lib/api';
import type { AdminGameSettingsResponse } from '@/lib/api';

function errorText(e: unknown): string {
    const msg = e instanceof Error ? e.message : '';
    if (msg.startsWith('403')) return '관리자 권한이 필요합니다.';
    if (msg.startsWith('401')) return '로그인이 필요합니다.';
    return '데이터를 불러올 수 없습니다.';
}

export default function GameSettingsPanel() {
    const [data, setData] = useState<AdminGameSettingsResponse | null>(null);
    const [draft, setDraft] = useState<Record<string, string>>({});
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');
    const [saving, setSaving] = useState('');
    const [notice, setNotice] = useState('');

    const load = useCallback(async () => {
        setLoading(true);
        try {
            const next = await api.admin.gameSettings();
            setData(next);
            setDraft({
                msg: next.msg,
                starttime: next.starttime ?? '',
                maxgeneral: next.maxgeneral?.toString() ?? '',
                maxnation: next.maxnation?.toString() ?? '',
                startyear: next.startyear?.toString() ?? '',
            });
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

    const save = useCallback(async (key: string, value: string | number) => {
        setSaving(key);
        setNotice('');
        try {
            const result = await api.admin.patchGameSettings({ [key]: value });
            setNotice(result.restartRequired ? '저장했습니다. 엔진 재시작이 필요합니다.' : '저장했습니다.');
            await load();
        } catch (e) {
            setError(e instanceof Error ? e.message : '저장할 수 없습니다.');
        } finally {
            setSaving('');
        }
    }, [load]);

    const setValue = (key: string, value: string) => {
        setDraft((current) => ({ ...current, [key]: value }));
    };

    return (
        <>
            <PageHead title="게임 관리" />

            {loading && <p className="text-muted">로딩 중...</p>}
            {error && <p className="page-error">{error}</p>}
            {notice && <p className="dip-letter__opt">{notice}</p>}

            {data && !error && (
                <>
                    <GameCard className="adm-section">
                        <table className="game-table u-full">
                            <tbody>
                                <tr>
                                    <th className="adm-w140">운영자메세지</th>
                                    <td>
                                        <input aria-label="운영자메세지" value={draft.msg ?? ''} onChange={(e) => setValue('msg', e.target.value)} className="adm-text-input" />
                                    </td>
                                    <td className="adm-w100">
                                        <button disabled={saving !== ''} onClick={() => save('msg', draft.msg ?? '')}>변경</button>
                                    </td>
                                </tr>
                                <tr>
                                    <th className="u-right">중원정세추가</th>
                                    <td>
                                        <input aria-label="중원정세추가" readOnly value="" maxLength={80} className="adm-text-input" />
                                    </td>
                                    <td>
                                        <button disabled>로그쓰기</button>
                                    </td>
                                </tr>
                                <tr>
                                    <th className="u-right">시작시간변경</th>
                                    <td>
                                        <input aria-label="시작시간변경" value={draft.starttime ?? ''} onChange={(e) => setValue('starttime', e.target.value)} className="adm-text-input adm-num--wide" />
                                    </td>
                                    <td>
                                        <button disabled={saving !== ''} onClick={() => save('starttime', draft.starttime ?? '')}>변경1</button>
                                    </td>
                                </tr>
                                <tr>
                                    <th className="u-right">최대 장수</th>
                                    <td>
                                        <input aria-label="최대 장수" type="number" min={1} max={9999} value={draft.maxgeneral ?? ''} onChange={(e) => setValue('maxgeneral', e.target.value)} className="adm-text-input adm-num" />
                                    </td>
                                    <td>
                                        <button disabled={saving !== '' || draft.maxgeneral === ''} onClick={() => save('maxgeneral', Number(draft.maxgeneral))}>변경2</button>
                                    </td>
                                </tr>
                                <tr>
                                    <th className="u-right">최대 국가</th>
                                    <td>
                                        <input aria-label="최대 국가" type="number" min={1} max={999} value={draft.maxnation ?? ''} onChange={(e) => setValue('maxnation', e.target.value)} className="adm-text-input adm-num" />
                                    </td>
                                    <td>
                                        <button disabled={saving !== '' || draft.maxnation === ''} onClick={() => save('maxnation', Number(draft.maxnation))}>변경3</button>
                                    </td>
                                </tr>
                                <tr>
                                    <th className="u-right">시작 년도</th>
                                    <td>
                                        <input aria-label="시작 년도" type="number" min={1} max={9999} value={draft.startyear ?? ''} onChange={(e) => setValue('startyear', e.target.value)} className="adm-text-input adm-num" />
                                    </td>
                                    <td>
                                        <button disabled={saving !== '' || draft.startyear === ''} onClick={() => save('startyear', Number(draft.startyear))}>변경4</button>
                                    </td>
                                </tr>
                                <tr>
                                    <th className="u-right">최근 갱신 시간</th>
                                    <td colSpan={2}>{data.turntime ?? '-'}</td>
                                </tr>
                                <tr>
                                    <th className="u-right">현재 연월</th>
                                    <td colSpan={2}>
                                        {data.year ?? '-'}년 {data.month ?? '-'}월 · {data.scenarioCode ?? '-'}
                                    </td>
                                </tr>
                                <tr>
                                    <th className="u-right">턴시간</th>
                                    <td colSpan={2}>
                                        <div className="u-row-sm">
                                            {data.turnOptions.map((m) => (
                                                <button key={m} disabled={saving !== '' || m === data.turnterm} onClick={() => save('turnterm', m)} style={m === data.turnterm ? { borderColor: 'var(--gold)' } : undefined}>
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
                        <div className="u-row-sm">
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
        </>
    );
}
