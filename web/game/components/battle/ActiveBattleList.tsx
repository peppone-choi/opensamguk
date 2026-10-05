'use client';

// 내 전투 목록(P-C04) — 보드 V31K6Battles(표: 종류 · 장소 · 양쪽 · 내 자리 · 상태 · 행동) · MBattles(카드). 같은 행을 폭에 따라 표 줄 · 카드로 그린다.
// - 장소 · 양쪽은 서버가 아직 만들지 않아 「서버 대기」(data-server-wait K6-11 · place / sides, #1335).
// - 내 자리는 v1 「중앙 · 주장」 대신 「내 부곡 n개」(D-BATTLE 2C · 1A — 내 군단 부곡 전부 출전).
// - 참가 대기는 남은 시간을 이 기기 시계로 세고 「약」을 붙인다(서버 마감 시각 기준, 참가 화면과 같은 규칙).
// - 행동은 서버 단계와 내 부곡 · 리플레이 참조가 있을 때만(rowAction). 결과 반영이 막힌 전투를 성공처럼 보이지 않는다.
import Link from 'next/link';
import { useEffect, useState } from 'react';
import { formatClock, secondsLeft } from '@/lib/battle/join-view';
import { kindLabel, PHASE_VIEW, rowAction, UNKNOWN_PHASE, type ActiveBattleRow } from '@/lib/battle/active-list';
import styles from './Battle.module.css';

export interface ActiveBattleListProps {
    readonly rows: readonly ActiveBattleRow[];
    /** 방 주소 — `<base>/<battleId>?world=<worldId>`. */
    readonly roomBase: string;
    /** 리플레이 주소 — `<base>/<replayId>`. */
    readonly replayBase: string;
}

export function ActiveBattleList({ rows, roomBase, replayBase }: ActiveBattleListProps) {
    const joining = rows.some((r) => r.phase === 'JOINING');
    const [now, setNow] = useState(() => Date.now());
    useEffect(() => {
        if (!joining) return undefined;
        const timer = window.setInterval(() => setNow(Date.now()), 1000);
        return () => window.clearInterval(timer);
    }, [joining]);

    return (
        <ul className={styles.battleRows} aria-label="내 전투 목록">
            {rows.map((r) => {
                const view = r.phase ? PHASE_VIEW[r.phase] : UNKNOWN_PHASE;
                const left = r.phase === 'JOINING' ? secondsLeft(r.joinDeadlineAt == null ? null : { at: r.joinDeadlineAt, approx: true }, now) : null;
                const action = rowAction(r);
                return (
                    <li key={`${r.worldId}:${r.battleId}`} className={styles.battleRow}>
                        <span className={styles.battleKind}><span className="os-chip">{kindLabel(r.kind)}</span></span>
                        <span className={styles.battlePlace}>{r.place ?? <Waiting row="K6-11 · place" />}</span>
                        <span className={styles.battleSides}>{r.sides ? r.sides.join(' 대 ') : <Waiting row="K6-11 · sides" />}</span>
                        <span className={styles.battleSeat}>{r.seatCount > 0 ? `내 부곡 ${r.seatCount}개` : '내 부곡 없음'}</span>
                        <span className={styles.battleState}>
                            <span className={['os-chip', view.tone ? `os-chip--${view.tone}` : ''].filter(Boolean).join(' ')}>{view.label}</span>
                            {left != null ? <span className={styles.battleClock} role="timer" aria-label="개전까지 남은 시간">{`약 ${formatClock(left)}`}</span> : null}
                        </span>
                        <span className={styles.battleAct}>
                            {action === 'enter' ? (
                                <Link className={`os-button ${r.phase === 'JOINING' ? 'os-button--primary' : ''}`} href={`${roomBase}/${encodeURIComponent(r.battleId)}?world=${r.worldId}`}>입장</Link>
                            ) : action === 'result' && r.replayId ? (
                                <Link className="os-button os-button--ghost" href={`${replayBase}/${encodeURIComponent(r.replayId)}`}>리플레이</Link>
                            ) : null}
                        </span>
                    </li>
                );
            })}
        </ul>
    );
}

function Waiting({ row }: { readonly row: string }) {
    return <span className="os-chip os-chip--info" data-server-wait={row}>서버 대기</span>;
}

export default ActiveBattleList;
