'use client';

// 전투 참가 대기 · 배치(P-C03) — 보드 V31K6v2BattleJoin · MBattleJoin(D24 승인 · D29 배율 데 1.25 · 모 1.6). K6 설계서 §3.6.
// 윗줄: 개전까지 남은 시간 · 제목 · 「내 군단 부곡 n개가 모두 나간다」(2C).
// 왼쪽: 장수별 부곡 목록(1A) — 하나 고르고 판의 칸을 누른다. 가운데: 아이소 판(배치 구역 초록 점선). 오른쪽: 상대(서버 대기) · 전장 · 안내.
// 서버가 주지 않는 것 — 장수 이름 · 초상 · 부곡 이름 · 병종 · 장소 · 양쪽 · 날씨 · 목표 · 길이 — 은 「서버 대기」로 둔다(지어내지 않음).
// 「입장」 · 「기본 배치 그대로」 · 「나가기 — AI 에게 맡긴다」는 전술 입력 원장 행이 없어(C1/C7 전술 registry 대기) 그리지 않는다.
// 보드의 「5분 · 3,000틱」은 예시 값이다 — 길이는 서버 tickHz · maxTicks 로만 그린다(CEO 10-05 b).
// 서버 대기 칸에는 기다리는 계약판 행을 data-server-wait 로 단다(#1335): K6-14 · units(C2 v2 답 #2) · K6-14 · visibleEnemy(#5 · #6) ·
// A11(날씨 · 밤 · 계절 · 목표, #16) · K6-14 · rulePin(길이, #15) · K6-14 · deployment(남은 시간이 없을 때).
import { useEffect, useState } from 'react';
import { BattleBoardCanvas } from '@/components/battle/BattleBoardCanvas';
import { useViewportClass } from '@opensamguk/ui';
import { formatClock, secondsLeft, type JoinView } from '@/lib/battle/join-view';
import { REJECT_TEXT, RESYNC_CAUSE, type Cell, type Maybe } from '@/lib/battle/protocol';
import type { MoveNotice, PendingMove } from '@/lib/battle/use-battle-session';
import styles from './BattleJoin.module.css';

export interface BattleJoinProps {
    readonly view: JoinView;
    readonly terrainInputSha256: string | null;
    readonly pending: PendingMove | null;
    readonly notice: MoveNotice | null;
    readonly onMove: (unitId: string, cell: Cell) => void;
}

/** D29 배치 배율 — 보드 그림(절반 크기) 기준. */
const DEPLOY_SCALE = { desktop: 1.25, mobile: 1.6 } as const;

export function BattleJoin({ view, terrainInputSha256, pending, notice, onMove }: BattleJoinProps) {
    const viewport = useViewportClass();
    const mobile = viewport === 'mobile';
    const [selected, setSelected] = useState<string | null>(view.units[0]?.id ?? null);
    const [now, setNow] = useState(() => Date.now());

    useEffect(() => {
        const timer = window.setInterval(() => setNow(Date.now()), 1000);
        return () => window.clearInterval(timer);
    }, []);

    const left = secondsLeft(view.deadline, now);
    const clock = left == null ? null : `${view.deadline?.approx ? '약 ' : ''}${formatClock(left)}`;
    const pick = (cell: Cell) => {
        if (selected) onMove(selected, cell);
    };
    const text = noticeText(notice);

    return (
        <div className={styles.join} data-testid="battle-join">
            <header className={styles.top}>
                <span className={styles.clock} role="timer" aria-label="개전까지 남은 시간" data-server-wait={clock == null ? 'K6-14 · deployment' : undefined}>{clock ?? '서버 대기'}</span>
                <span className={styles.topText}>
                    <h2 className={styles.title}>개전까지 — 참가 대기 · 배치</h2>
                    <span className={styles.sub}>{`내 군단 부곡 ${view.units.length}개가 모두 나간다`}{view.deadline?.approx ? ' · 남은 시간은 이 기기 시계로 셈' : ''}</span>
                </span>
            </header>
            <div className={styles.body}>
                <section className={styles.list} aria-label="내 군단 부곡">
                    <h3 className={styles.listHead}>
                        <span>{`내 군단 부곡 ${view.units.length}`}</span>
                        <span className={styles.muted}>장수별 · 하나 골라 판의 칸을 누른다</span>
                    </h3>
                    <p className={styles.note} data-server-wait="K6-14 · units">장수 이름 · 초상 · 부곡 이름 · 병종은 서버가 아직 주지 않습니다.</p>
                    <div role="listbox" aria-label="내 군단 부곡" className={styles.rows}>
                        {view.groups.map((g, gi) => (
                            <div key={g.generalId} role="group" aria-label={`장수 ${gi + 1}`} className={styles.group}>
                                <div className={styles.groupHead}>
                                    <span className={styles.groupName}>{`장수 ${gi + 1}`}</span>
                                    <span className={styles.muted}>{`부곡 ${g.units.length} · 병력 합 ${g.troopsTotal.toLocaleString('ko-KR')}`}</span>
                                </div>
                                {g.units.map((u) => (
                                    <button key={u.id} type="button" role="option" aria-selected={u.id === selected} className={styles.row} onClick={() => setSelected(u.id)}>
                                        <span className={styles.rowName}>{`부곡 ${u.index}`}</span>
                                        <span className={styles.rowSub}>{`병력 ${u.troops.toLocaleString('ko-KR')} · 사기 ${u.morale} · 칸 ${u.cell.row},${u.cell.col}`}</span>
                                    </button>
                                ))}
                            </div>
                        ))}
                    </div>
                </section>
                <section className={styles.board} aria-label="전투 판">
                    <BattleBoardCanvas
                        boardId={view.boardId}
                        terrainInputSha256={terrainInputSha256}
                        units={view.units}
                        allowedCells={view.allowedCells}
                        selectedId={selected}
                        boardScale={mobile ? DEPLOY_SCALE.mobile : DEPLOY_SCALE.desktop}
                        onPickUnit={setSelected}
                        onPickCell={pick}
                        label="전투 판 — 초록 점선 안 칸을 누르면 고른 부곡이 그리로 옮긴다. 내 부곡이 있는 칸이면 맞바꾼다"
                    />
                    <p className={styles.boardNote} role="status" aria-live="polite">
                        {pending ? '옮기는 중 — 서버 영수증을 기다립니다' : text ?? '초록 점선(배치 구역) 안 칸을 누르면 옮긴다. 내 부곡이 있는 칸이면 맞바꾼다. 끌기는 없다.'}
                    </p>
                </section>
                <aside className={styles.side} aria-label="전장 정보">
                    <section className={styles.panel} aria-label="상대 — 보이는 만큼">
                        <h3 className={styles.panelHead}>상대 — 보이는 만큼</h3>
                        <p className={styles.panelBody} data-server-wait="K6-14 · visibleEnemy"><span className="os-chip os-chip--info">서버 대기</span> 공개 범위가 아직 정해지지 않았습니다. 정해지기 전에는 추정값을 만들지 않습니다.</p>
                    </section>
                    <section className={styles.panel} aria-label="전장">
                        <h3 className={styles.panelHead}>전장 <span className={styles.muted}>티켓에 고정</span></h3>
                        <dl className={styles.facts}>
                            <div><dt>판</dt><dd>{`${view.boardId}번 판`}</dd></div>
                            <div><dt>날씨 · 밤 · 계절</dt><dd><Waiting row="A11" value={view.environment.weather.value ?? view.environment.season.value} /></dd></div>
                            <div><dt>목표</dt><dd><Waiting row="A11" value={view.environment.objective.value} /></dd></div>
                            <div><dt>길이</dt><dd><Waiting row="K6-14 · rulePin" value={lengthText(view.tickHz, view.maxTicks)} /></dd></div>
                        </dl>
                    </section>
                    <section className={styles.panel} aria-label="안내">
                        <h3 className={styles.panelHead}>안내</h3>
                        <ul className={styles.guide}>
                            <li><b>모두 나간다</b> — 내 군단 부곡은 수와 상관없이 전부 판에 선다. 예비대 · 빠지는 부곡은 없다.</li>
                            <li><b>옮기기</b> — 부곡 하나를 고르고 칸을 누른다. 내 부곡끼리는 맞바꾼다. 끌기는 없다.</li>
                            <li><b>기본 배치</b> — 서버가 정한 자리. 안 고치면 개전 때 그대로 선다.</li>
                            <li><b>다른 군단</b> — 같은 편이어도 그 장수의 부곡은 옮기지 못한다.</li>
                        </ul>
                    </section>
                </aside>
            </div>
        </div>
    );
}

/** 알림 줄 — 화면이 막은 사유 · 서버 거절(쉬운 말) · 다시 맞추는 중 · 다시 맞춤. */
function noticeText(notice: MoveNotice | null): string | null {
    if (!notice) return null;
    const cause = (notice.code && RESYNC_CAUSE[notice.code]) ?? '서버 상태가 먼저 바뀌었습니다';
    switch (notice.kind) {
        case 'blocked': return notice.text;
        case 'resyncing': return `${cause} — 최신 배치를 다시 받는 중입니다`;
        case 'resynced': return `${cause} — 최신 배치로 다시 맞췄습니다. 자리를 보고 다시 옮기세요`;
        default: return notice.code ? REJECT_TEXT[notice.code] : '서버가 거절했습니다';
    }
}

/** 서버 값이 있으면 그 값, 없으면 「서버 대기」 칩 — 기다리는 계약판 행을 data-server-wait 로 단다. */
function Waiting({ value, row }: { readonly value: string | null; readonly row: string }) {
    return value ? <>{value}</> : <span className="os-chip os-chip--info" data-server-wait={row}>서버 대기</span>;
}

/** 길이 — 서버 규칙 핀(tickHz · maxTicks)이 있을 때만. 없으면 null(「서버 대기」). */
function lengthText(tickHz: Maybe<number>, maxTicks: Maybe<number>): string | null {
    if (tickHz.value == null || maxTicks.value == null) return null;
    const seconds = Math.round(maxTicks.value / tickHz.value);
    const time = seconds % 60 === 0 ? `${seconds / 60}분` : `${Math.floor(seconds / 60)}분 ${seconds % 60}초`;
    return `${time} · ${maxTicks.value.toLocaleString('ko-KR')}틱`;
}

export default BattleJoin;
