'use client';

import { useState } from 'react';
import { Modal } from '@opensamguk/ui';
import { TurnSlots } from '@/components/turn-slots/TurnSlots';
import { firstEmpty, type TurnSlotsLoad } from '@/lib/turn-slots';
import StandingGrid from './StandingGrid';
import type { Works } from '@/lib/campaign-reads';
import styles from './WarRoomPage.module.css';

export interface WarRoomTurnsProps {
    readonly load: TurnSlotsLoad;
    readonly onRetry: () => void;
    /** 순을 누르면 그 순으로 명령 흐름(빈 순 = 예약, 찬 순 = 편집). */
    readonly onSlot: (turnIdx: number) => void;
    /** 「이번 순에 할 일」 — 순을 정하지 않고 연다(흐름이 첫 빈 순을 고른다). */
    readonly onDoNow: () => void;
    /** 작전실이 지도 표지용으로 읽은 공사(맡겨 둔 일 「공사」 칸이 같이 쓴다). */
    readonly works: { readonly data: Works | null; readonly error: string | null };
}

const two = (n: number) => String(n + 1).padStart(2, '0');

/** 「이번 순에 할 일 — 02순」(첫 빈 순). 다 찼거나 아직 모르면 순 번호 없이. */
function doNowLabel(load: TurnSlotsLoad): string {
    const at = load.state === 'ready' ? firstEmpty(load.slots) : null;
    return at == null ? '이번 순에 할 일' : `이번 순에 할 일 — ${two(at)}순`;
}

/**
 * 데스크톱 12순 열(보드 V31K4WarRoom turns_aside 336) — 「명령 목록 12순」 · 순 12 · 「맡겨 둔 일」 · 아래 「이번 순에 할 일」.
 * 순 전체 당기기 · 밀기(K0 Q6)는 입력이 아직 없어 그리지 않는다.
 */
export function WarRoomTurnsColumn({ load, onRetry, onSlot, onDoNow, works }: WarRoomTurnsProps) {
    return (
        <aside className={styles.turns} aria-label="명령 목록 12순">
            <div className={styles.turnsHead}>
                <h2 className={styles.turnsTitle}>명령 목록 12순</h2>
                <span className={styles.muted}>직접 행동 · 한 순에 하나</span>
            </div>
            <TurnSlots mode="column" load={load} onSelect={onSlot} onRetry={onRetry} />
            <div className={styles.turnsHead}>
                <h2 className={styles.turnsTitle}>맡겨 둔 일</h2>
                <span className={styles.muted}>순마다 스스로 굴러간다</span>
            </div>
            <div className={styles.standing}><StandingGrid works={works} /></div>
            <div className={styles.turnsFoot}>
                <button type="button" className="os-button os-button--primary os-button--block" onClick={onDoNow}>{doNowLabel(load)}</button>
            </div>
        </aside>
    );
}

/**
 * 모바일 12순 엿보기 시트(보드 V31K4MWarRoom peek 124) — 다음 순 한 줄 + 「이번 순에 할 일」 + 「12순 · 맡겨 둔 일」(전체 시트).
 * 전체 시트는 09-26 승인 V3MSheet 그대로(12순 + 맡겨 둔 일). 순을 누르면 시트를 닫고 그 순으로 흐름을 연다.
 */
export function WarRoomTurnsPeek({ load, onRetry, onSlot, onDoNow, works }: WarRoomTurnsProps) {
    const [open, setOpen] = useState(false);
    const next = load.state === 'ready' ? load.slots[0] ?? null : null;
    return (
        <>
            <section className={styles.peek} aria-label="명령 목록 12순 — 다음 순">
                {next ? (
                    <button type="button" className={styles.peekRow} onClick={() => onSlot(next.turnIdx)}
                        aria-label={`${two(next.turnIdx)}순 — ${next.state === 'empty' ? '빈 순' : next.name ?? '명령'}`}>
                        <span className={`os-mono ${styles.muted}`}>{two(next.turnIdx)}</span>
                        <span className={styles.peekText}>
                            {next.when || next.at ? <span className={`os-mono ${styles.muted}`}>{[next.when, next.at].filter(Boolean).join(' · ')}</span> : null}
                            <span className="os-serif">{next.state === 'empty' ? '빈 순' : next.name}</span>
                        </span>
                        {next.state === 'reserved' ? <span className="os-chip os-chip--info">예약</span> : null}
                        {next.state === 'blocked' ? <span className="os-chip os-chip--rust">막힘</span> : null}
                    </button>
                ) : (
                    <p className={styles.peekRowText} role="status">
                        {load.state === 'error' ? '명령 목록을 불러오지 못했습니다.' : '명령 목록을 불러오는 중입니다.'}
                    </p>
                )}
                <div className={styles.peekActions}>
                    <button type="button" className="os-button os-button--primary" onClick={onDoNow}>이번 순에 할 일</button>
                    <button type="button" className="os-button" aria-expanded={open} onClick={() => setOpen(true)}>12순 · 맡겨 둔 일</button>
                </div>
            </section>
            {open ? (
                <Modal ariaLabel="명령 목록 12순 · 맡겨 둔 일" onClose={() => setOpen(false)} overlayClassName={styles.sheetBottom}>
                    <div className={styles.sheet}>
                        <div className={styles.turnsHead}>
                            <h2 className={styles.turnsTitle}>명령 목록 12순</h2>
                            <button type="button" className={`os-button os-button--sm ${styles.push}`} onClick={() => setOpen(false)}>닫기</button>
                        </div>
                        <TurnSlots mode="column" load={load} onRetry={onRetry} onSelect={(turnIdx) => { setOpen(false); onSlot(turnIdx); }} />
                        <div className={styles.turnsHead}><h2 className={styles.turnsTitle}>맡겨 둔 일</h2></div>
                        <div className={styles.standing}><StandingGrid works={works} /></div>
                    </div>
                </Modal>
            ) : null}
        </>
    );
}
