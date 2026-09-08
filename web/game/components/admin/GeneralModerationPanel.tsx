'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import PageHead from '../PageHead';
import GameCard from '@/components/GameCard';
import { api } from '@/lib/api';
import type { AdminBlockedWrite, AdminGeneralModerationResponse, AdminGeneralModerationRow } from '@/lib/api';
import { useTurnRefresh } from '@/hooks/useTurnRefresh';

function errorText(e: unknown, fallback = '데이터를 불러올 수 없습니다.'): string {
    const msg = e instanceof Error ? e.message : '';
    if (msg.startsWith('403')) return '관리자 권한이 필요합니다.';
    if (msg.startsWith('401')) return '로그인이 필요합니다.';
    return msg || fallback;
}

// 범례와 같은 이름의 클래스로 칠한다 — 리터럴 색(cyan/skyblue/red)을 팔레트 토큰으로 사상했다.
function optionClass(g: AdminGeneralModerationRow): string | undefined {
    const classes: string[] = [];
    if (g.block > 0) classes.push('adm-mod--blocked');
    if (g.npc >= 2) classes.push('adm-mod--npc');
    else if (g.npc === 1) classes.push('adm-mod--npc-user');
    return classes.length > 0 ? classes.join(' ') : undefined;
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
        <div className="u-row-sm">
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

export default function GeneralModerationPanel() {
    const [data, setData] = useState<AdminGeneralModerationResponse | null>(null);
    const [selected, setSelected] = useState<number[]>([]);
    const [message, setMessage] = useState('');
    const [loading, setLoading] = useState(true);
    const [actionLoading, setActionLoading] = useState<string | null>(null);
    const [error, setError] = useState('');
    const [notice, setNotice] = useState('');

    // background=true는 턴 갱신용 — 이미 보고 있는 목록을 로딩 표시로 지우지 않는다.
    const load = useCallback(async (background = false) => {
        if (!background) setLoading(true);
        try {
            const d = await api.admin.generalModeration();
            setData(d);
            setError('');
        } catch (e) {
            // 턴 갱신(background) 실패는 보고 있던 화면을 지우지 않는다.
            if (!background) setError(errorText(e));
        } finally {
            if (!background) setLoading(false);
        }
    }, []);

    useEffect(() => {
        load();
    }, [load]);

    useTurnRefresh(() => void load(true));

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
        <>
            <PageHead title="회원 관리" />

            {loading && <p className="text-muted">로딩 중...</p>}
            {error && <p className="page-error">{error}</p>}

            {data && !error && (
                <>
                    <GameCard className="adm-section">
                        <table className="game-table u-full">
                            <tbody>
                                <tr>
                                    <th className="adm-w120">접속제한</th>
                                    <td>
                                        <ActionButtons actions={data.bulkActions} disabled={actionLoading != null} onAction={runAction} />
                                    </td>
                                </tr>
                            </tbody>
                        </table>
                    </GameCard>

                    <GameCard className="adm-section">
                        <div className="adm-split">
                            <div>
                                <div className="adm-para">
                                    회원선택<br />
                                    <span className="adm-mod--npc">NPC</span><br />
                                    <span className="adm-mod--npc-user">NPC유저</span><br />
                                    <span className="adm-mod--limited">접속제한</span><br />
                                    <b className="adm-mod--blocked">블럭회원</b>
                                </div>
                                <select
                                    multiple
                                    size={20}
                                    value={selected.map(String)}
                                    onChange={(e) => setSelected(Array.from(e.currentTarget.selectedOptions, (o) => Number(o.value)))}
                                    className="adm-console"
                                >
                                    {data.generals.map((g) => (
                                        <option key={g.no} value={g.no} className={optionClass(g)}>
                                            {g.name}
                                        </option>
                                    ))}
                                </select>
                            </div>

                            <div className="adm-stack-grid">
                                {notice && <p className="adm-notice">{notice}</p>}
                                <section>
                                    <h2 className="adm-h3">블럭</h2>
                                    <ActionButtons actions={data.selectedActions.slice(0, 5)} disabled={actionLoading != null} onAction={runAction} />
                                    <p className="adm-note adm-note--gap">1단계:발언권, 2단계:턴블럭</p>
                                </section>
                                <section>
                                    <h2 className="adm-h3">강제 사망</h2>
                                    <ActionButtons actions={data.selectedActions.slice(5, 6)} disabled={actionLoading != null} onAction={runAction} />
                                </section>
                                <section>
                                    <h2 className="adm-h3">이벤트2</h2>
                                    <ActionButtons actions={data.selectedActions.slice(6, 11)} disabled={actionLoading != null} onAction={runAction} />
                                </section>
                                <section>
                                    <h2 className="adm-h3">접속제한</h2>
                                    <ActionButtons actions={data.selectedActions.slice(11, 13)} disabled={actionLoading != null} onAction={runAction} />
                                </section>
                                <section>
                                    <h2 className="adm-h3">명령 설정</h2>
                                    <ActionButtons actions={data.selectedActions.slice(13, 15)} disabled={actionLoading != null} onAction={runAction} />
                                </section>
                                <section>
                                    <h2 className="adm-h3">메세지 전달</h2>
                                    <input
                                        value={message}
                                        onChange={(e) => setMessage(e.target.value.slice(0, 255))}
                                        maxLength={255}
                                        className="adm-console-input"
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
                        <div className="u-row-sm">
                            <button onClick={() => void load()}>새로고침</button>
                            <span className="text-muted">
                                선택 {selectedRows.length}명 / 전체 {data.generals.length}명
                            </span>
                        </div>
                        {selectedRows.length > 0 && (
                            <div className="adm-table-wrap">
                                <table className="game-table adm-table--wide">
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
                                                <td className="u-center">{g.npc}</td>
                                                <td className="u-center">{g.block}</td>
                                                <td className="u-center">{g.killturn ?? '-'}</td>
                                                <td className="u-center">{g.nationId}</td>
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
        </>
    );
}
