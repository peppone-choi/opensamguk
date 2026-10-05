'use client';

// 실시간 전투(P-C05) — 보드 V31K6v2BattleLive · LiveMany · MBattleLive · MBattleLiveSheet(D24 승인). K6 설계서 §3.5.
// 위: 남은 시간 · 틱. 왼쪽: 내 군단 부곡 여럿 고르기(장수 머리 = 그 장수 부곡 전부, 일부만이면 −) · 「내 부곡 전부」 · 「다 풀기」.
// 가운데: 아이소 판(원작 2배로 시작, 「+」 · 「−」 · 「전체」). 오른쪽: 사건 · 상대(서버 대기) · 내가 없을 때. 아래: 「고른 부곡 n개에게」 6명령.
// - 명령은 고른 부곡 모두에게 한 번에 간다(전부 받거나 전부 거절, C2 v2 #10). 영수증(ACK)으로 받음 · 거절(쉬운 말)을 보인다.
// - 집결 단추(원장 D114): 집결 1 = HOME(우리 쪽) · 2 = CENTER(가운데) · 3 = ENEMY(적 쪽). 명령은 고른 집결점과 함께 간다.
//   집결을 누르지 않았으면 고른 부곡들의 지금 집결점이 모두 같을 때 그 값을 쓴다. 섞였으면 집결을 고르라고 막는다(집결점을 지어내지 않는다).
// - 판에서 고르기(D24 세부 2): 데스크톱은 마우스로 판을 끌어 사각형 안 내 부곡을 고른다. 「판에서 고르기」를 켜면 두 점을 눌러 고른다(모바일은 이것만 —
//   터치 끌기는 판 움직이기). 고르기는 화면 안 일이라 서버 계약이 필요 없다.
// - 많을 때 묶기(D24 세부 1): 원작 2배보다 작게 보면 같은 장수의 가까이 모인 부곡을 깃발 하나 + 숫자로 묶는다. 묶음을 누르면 그 자리에서 2배로 다가간다.
// - 「목표」 · 「계책」 · 「일기토」 · 「나가기」는 전술 입력 원장 행 · 서버 계약이 없어 그리지 않는다.
// - 서버 대기 칸에는 기다리는 계약판 행을 data-server-wait 로 단다(#1335).
import { useId, useMemo, useState } from 'react';
import { BattleBoardCanvas, type BoardScale } from '@/components/battle/BattleBoardCanvas';
import { formatClock } from '@/lib/battle/join-view';
import {
    commandScope, EMPTY_SELECTION, groupNumbers, groupState, selectAllMine, selectOnly, sharedRally, toggleGroup, toggleUnit, type LiveView, type Selection,
} from '@/lib/battle/live-view';
import {
    BATTLE_ORDERS, ORDER_LABEL, RALLY_HINT, RALLY_NUMBER, RALLY_POINTS, REJECT_TEXT, RESYNC_CAUSE, type BattleOrder, type CommandScope, type RallyPoint,
} from '@/lib/battle/protocol';
import type { MoveNotice, PendingCommand } from '@/lib/battle/use-battle-session';
import styles from './BattleLive.module.css';

export interface BattleLiveProps {
    readonly view: LiveView;
    readonly terrainInputSha256: string | null;
    readonly pendingCommand: PendingCommand | null;
    readonly notice: MoveNotice | null;
    readonly onCommand: (scope: CommandScope, count: number, order: BattleOrder, rally: RallyPoint) => void;
}

/** 실시간 전투는 원작 2배로 시작한다(D24 세부 결정 3 — 부곡 수와 상관없이). */
const LIVE_START_SCALE = 2;
const ZOOM_STEP = 1.25;

/** 알림 줄 — 명령 받음 · 거절(쉬운 말) · 다시 맞추는 중 · 다시 맞춤(지휘권 · 회차가 서버에서 먼저 바뀜). */
function liveNoticeText(notice: MoveNotice | null): string | null {
    if (!notice) return null;
    const cause = (notice.code && RESYNC_CAUSE[notice.code]) ?? '서버 상태가 먼저 바뀌었습니다';
    switch (notice.kind) {
        case 'rejected': return `명령 거절 — ${notice.code ? REJECT_TEXT[notice.code] : '서버가 거절했습니다'}`;
        case 'resyncing': return `${cause} — 전투 상황을 다시 받는 중입니다`;
        case 'resynced': return `${cause} — 최신 상황으로 다시 맞췄습니다. 부곡을 보고 다시 명령하세요`;
        default: return notice.text;
    }
}

export function BattleLive({ view, terrainInputSha256, pendingCommand, notice, onCommand }: BattleLiveProps) {
    const [sel, setSel] = useState<Selection>(EMPTY_SELECTION);
    const [scale, setScale] = useState<BoardScale>({ kind: 'renderer', value: LIVE_START_SCALE });
    const [local, setLocal] = useState<string | null>(null);
    // 「판에서 고르기」(두 점) — 켜짐 · 첫 점을 찍었는지.
    const [rectMode, setRectMode] = useState(false);
    const [anchorSet, setAnchorSet] = useState(false);
    const marks = useMemo(() => {
        const group = groupNumbers(view);
        return view.units.map((u) => ({ id: u.id, cell: u.cell, index: u.index, ai: u.controller === 'AI', group: group.get(u.id) }));
    }, [view]);
    // 집결 — 누른 값(없으면 고른 부곡들의 같은 집결점).
    const [rallyPick, setRallyPick] = useState<RallyPoint | null>(null);
    const rallyHintId = useId();
    const count = sel.ids.size;
    const rally = rallyPick ?? sharedRally(view, sel);

    const send = (order: BattleOrder) => {
        const scope = commandScope(view, sel);
        if (!scope) return setLocal('부곡을 먼저 고르세요');
        if (!rally) return setLocal('고른 부곡들의 집결점이 서로 다릅니다 — 집결 1 · 2 · 3 중 하나를 고르세요');
        if (pendingCommand) return setLocal('앞 명령의 영수증을 기다립니다');
        setLocal(null);
        onCommand(scope, count, order, rally);
    };
    const zoom = (factor: number | 'fit') => setScale((s) => {
        if (factor === 'fit') return { kind: 'fit' };
        const base = s.kind === 'renderer' ? s.value : LIVE_START_SCALE;
        return { kind: 'renderer', value: Math.min(4, Math.max(0.5, base * factor)) };
    });

    const pickRect = (ids: string[]) => {
        setRectMode(false);
        setAnchorSet(false);
        const next = selectOnly(view, ids);
        if (next.ids.size === 0) return setLocal('사각형 안에 내 부곡이 없습니다 — 다시 고르세요');
        setSel(next);
        setLocal(`판에서 고름 — 부곡 ${next.ids.size}개`);
    };
    const toggleRect = () => {
        setRectMode((on) => !on);
        setAnchorSet(false);
        setLocal(null);
    };

    const status = (rectMode ? (anchorSet ? '판에서 고르기 — 두 번째 점을 누르세요' : '판에서 고르기 — 사각형의 첫 점을 누르세요') : null)
        ?? local
        ?? (pendingCommand ? `보내는 중 — ${ORDER_LABEL[pendingCommand.order]} · 고른 부곡 ${pendingCommand.count}개` : null)
        ?? liveNoticeText(notice)
        ?? '부곡을 고르고 아래 명령을 누른다. 고른 부곡 모두에게 한 번에 간다.';

    return (
        <div className={styles.live} data-testid="battle-live">
            <header className={styles.top}>
                <span className={styles.clock} role="timer" aria-label="남은 시간" data-server-wait={view.remainingSeconds == null ? 'K6-14 · rulePin' : undefined}>
                    {view.remainingSeconds == null ? '서버 대기' : formatClock(view.remainingSeconds)}
                </span>
                <span className={styles.tick}>{`틱 ${view.tick.toLocaleString('ko-KR')}`}{view.maxTicks.value != null ? ` / ${view.maxTicks.value.toLocaleString('ko-KR')}` : ''}</span>
                <p className={styles.status} role="status" aria-live="polite">{status}</p>
            </header>
            <div className={styles.body}>
                <section className={styles.list} aria-label="내 군단 부곡">
                    <h3 className={styles.listHead}>
                        <span>{`내 군단 부곡 ${view.units.length}`}</span>
                        <span className={styles.muted}>여럿 고르기 · 장수 머리로 묶음 고르기</span>
                    </h3>
                    <div className={styles.selectBar}>
                        <span className={styles.selectCount}>{`고른 부곡 ${count} / ${view.units.length}`}</span>
                        <button type="button" className="os-button" onClick={() => setSel(selectAllMine(view))}>내 부곡 전부</button>
                        <button type="button" className="os-button" onClick={() => setSel(EMPTY_SELECTION)}>다 풀기</button>
                    </div>
                    <p className={styles.note} data-server-wait="K6-14 · units">장수 이름 · 초상 · 부곡 이름 · 병종은 서버가 아직 주지 않습니다.</p>
                    <div role="group" aria-label="내 군단 부곡 고르기" className={styles.rows}>
                        {view.groups.map((g, gi) => {
                            const state = groupState(g, sel);
                            return (
                                <div key={g.generalId} className={styles.group}>
                                    <button type="button" role="checkbox" aria-checked={state === 'all' ? true : state === 'mixed' ? 'mixed' : false}
                                        className={styles.groupHead} onClick={() => setSel(toggleGroup(g, sel))}>
                                        <span className={styles.box} aria-hidden="true">{state === 'all' ? '✓' : state === 'mixed' ? '−' : ''}</span>
                                        <span className={styles.groupName}>{`장수 ${gi + 1}`}</span>
                                        <span className={styles.muted}>{`부곡 ${g.units.length} · 병력 합 ${g.troopsTotal.toLocaleString('ko-KR')} · 전부 고르기`}</span>
                                    </button>
                                    {g.units.map((u) => (
                                        <button key={u.id} type="button" role="checkbox" aria-checked={sel.ids.has(u.id)} className={styles.row} onClick={() => setSel(toggleUnit(sel, u.id))}>
                                            <span className={styles.box} aria-hidden="true">{sel.ids.has(u.id) ? '✓' : ''}</span>
                                            <span className={styles.rowText}>
                                                <span className={styles.rowName}>{`부곡 ${u.index}`}{u.controller === 'AI' ? <span className="os-chip os-chip--info">AI</span> : null}</span>
                                                <span className={styles.rowSub}>
                                                    {`병력 ${u.troops.toLocaleString('ko-KR')} · 사기 ${u.morale} · ${u.order ? ORDER_LABEL[u.order] : '명령 없음'}`}
                                                    {u.rally ? ` · 집결 ${RALLY_NUMBER[u.rally]}` : ''}
                                                </span>
                                            </span>
                                        </button>
                                    ))}
                                </div>
                            );
                        })}
                    </div>
                </section>
                <section className={styles.board} aria-label="전투 판">
                    <BattleBoardCanvas
                        boardId={view.boardId}
                        terrainInputSha256={terrainInputSha256}
                        units={marks}
                        allowedCells={[]}
                        selectedIds={sel.ids}
                        scale={scale}
                        onPickUnit={(id) => setSel(toggleUnit(sel, id))}
                        onPickCell={() => {}}
                        label="전투 판 — 내 부곡 칸을 누르면 고르거나 푼다. 끌면 사각형으로 고르거나(마우스) 판을 움직인다(터치)"
                        live={{ rectMode, onRectAnchor: setAnchorSet, onPickRect: pickRect, clusterBelow: LIVE_START_SCALE, onZoomTo: (value) => setScale({ kind: 'renderer', value }) }}
                    />
                    <div className={styles.zoom} role="group" aria-label="판 크기 · 판에서 고르기">
                        <button type="button" className="os-button" aria-pressed={rectMode} onClick={toggleRect}>판에서 고르기</button>
                        <button type="button" className="os-button" aria-label="크게" onClick={() => zoom(ZOOM_STEP)}>+</button>
                        <button type="button" className="os-button" aria-label="작게" onClick={() => zoom(1 / ZOOM_STEP)}>−</button>
                        <button type="button" className="os-button" onClick={() => zoom('fit')}>전체</button>
                    </div>
                </section>
                <aside className={styles.side} aria-label="전투 정보">
                    <section className={styles.panel} aria-label="판에서 고르기">
                        <h3 className={styles.panelHead}>판에서 고르기</h3>
                        <ul className={styles.panelList}>
                            <li><b>데스크톱</b> — 마우스로 판을 끌어 사각형 안의 내 부곡을 고른다. 「판에서 고르기」를 켜고 두 점을 눌러도 된다.</li>
                            <li><b>모바일</b> — 「판에서 고르기」를 켜고 두 점을 누른다. 끌기는 판 움직이기다.</li>
                            <li><b>작게 보면</b> — 같은 장수의 가까이 모인 부곡은 깃발 하나와 숫자로 묶인다. 깃발을 누르면 그 자리로 다가간다.</li>
                        </ul>
                    </section>
                    <section className={styles.panel} aria-label="사건">
                        <h3 className={styles.panelHead}>사건</h3>
                        <p className={styles.panelBody} data-server-wait="K6-14 · DELTA"><span className="os-chip os-chip--info">서버 대기</span> 교전 · 후퇴 · 괴멸 같은 사건 줄은 서버가 아직 보내지 않습니다.</p>
                    </section>
                    <section className={styles.panel} aria-label="상대 — 보이는 만큼">
                        <h3 className={styles.panelHead}>상대 — 보이는 만큼</h3>
                        <p className={styles.panelBody} data-server-wait="K6-14 · visibleEnemy"><span className="os-chip os-chip--info">서버 대기</span> 공개 범위가 정해지기 전에는 추정값을 만들지 않습니다.</p>
                    </section>
                    <section className={styles.panel} aria-label="내가 없을 때">
                        <h3 className={styles.panelHead}>내가 없을 때</h3>
                        <p className={styles.panelBody}>내가 없으면 내 군단 부곡은 AI 가 맡는다. 서버가 AI 로 넘긴 부곡은 목록과 판에 「AI」가 붙는다.</p>
                    </section>
                </aside>
            </div>
            <div className={styles.commands} role="group" aria-label="명령">
                <span className={styles.commandsLead}>{`고른 부곡 ${count}개에게`}</span>
                <div className={styles.orders}>
                    {BATTLE_ORDERS.map((o) => (
                        <button key={o} type="button" className="os-button" aria-disabled={count === 0 || !rally || pendingCommand != null ? true : undefined} onClick={() => send(o)}>
                            {ORDER_LABEL[o]}
                        </button>
                    ))}
                </div>
                <div className={styles.rally} role="group" aria-label="집결점" aria-describedby={rallyHintId}>
                    {RALLY_POINTS.map((r) => (
                        <button key={r} type="button" className="os-button" aria-pressed={rally === r} onClick={() => { setRallyPick(r); setLocal(null); }}>
                            {`집결 ${RALLY_NUMBER[r]}`}
                        </button>
                    ))}
                    <span id={rallyHintId} className={styles.rallyHint}>{RALLY_POINTS.map((r) => `${RALLY_NUMBER[r]} ${RALLY_HINT[r]}`).join(' · ')}</span>
                </div>
            </div>
        </div>
    );
}

export default BattleLive;
