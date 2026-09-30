'use client';

// 12순 — 작전실 12순 열(column, K4가 붙임)과 명령 흐름 순 띠(strip, K6)가 같이 쓰는 한 부품(K0 결정 2026-10-01).
// column: 12 × 52 = 624 고정(넘치면 안에서 스크롤) · 번호 · 「3월 하순 · 22:40」 · 명령 + 대상 · 상태 칩 · 효력 표식, 다음 순(01순) 청동 띠.
// strip : 2줄 × 6칸(칸 44), 모바일은 한 줄 가로 밀기(칸 96). 지금 채우는 순은 청동 테두리.
// 어느 행이든 누르면 onSelect — 빈 순 = 예약, 채운 순 · 막힌 순 = 흐름에서 바꾸기(한 경로). 순 비우기 · 옮기기는 원장 행이 없어 그리지 않는다.
// 열 머리 · 맡겨 둔 일 · 아래 줄은 작전실(K4) 몫이라 부품 밖이다.
import { StatusView } from '@opensamguk/ui';
import { slotLabel, SLOT_COUNT, type TurnSlotView, type TurnSlotsLoad } from '@/lib/turn-slots';
import styles from './TurnSlots.module.css';

export interface TurnSlotsProps {
    readonly mode: 'column' | 'strip';
    readonly load: TurnSlotsLoad;
    /** strip: 채우는 순(청동 테두리). column: 고른 순이 있으면 그 행을 강조(없으면 다음 순 01순 띠만). */
    readonly current?: number | null;
    readonly onSelect: (turnIdx: number, slot: TurnSlotView) => void;
    readonly onRetry: () => void;
    readonly className?: string;
}

const no = (i: number) => String(i + 1).padStart(2, '0');

export function TurnSlots({ mode, load, current = null, onSelect, onRetry, className = '' }: TurnSlotsProps) {
    const root = [styles.root, mode === 'column' ? styles.column : styles.strip, className].filter(Boolean).join(' ');
    if (load.state === 'loading') {
        return (
            <div className={root} role="status" aria-label="12순을 불러오는 중" data-mode={mode}>
                {Array.from({ length: SLOT_COUNT }, (_, i) => <span key={i} className={styles.skeleton} aria-hidden="true" />)}
            </div>
        );
    }
    if (load.state === 'error') {
        return (
            <div className={root} data-mode={mode}>
                <StatusView kind="error" title="12순을 불러오지 못했습니다" body="빈 순이 아닙니다 — 불러오기가 실패했습니다." onRetry={onRetry} />
            </div>
        );
    }
    return (
        <div className={root} role="group" aria-label="명령 목록 12순" data-mode={mode} data-testid={`turn-slots-${mode}`}>
            {load.slots.map((slot) => {
                const selected = slot.turnIdx === current;
                const next = mode === 'column' && slot.turnIdx === 0;
                return (
                    <button
                        key={slot.turnIdx}
                        type="button"
                        className={styles.slot}
                        data-state={slot.state}
                        data-next={next || undefined}
                        data-turn-idx={slot.turnIdx}
                        aria-pressed={selected}
                        aria-label={slotLabel(slot)}
                        onClick={() => onSelect(slot.turnIdx, slot)}
                    >
                        <span className={styles.no} aria-hidden="true">{no(slot.turnIdx)}</span>
                        {mode === 'column' && (slot.when || slot.at) ? (
                            <span className={styles.when} aria-hidden="true">{[slot.when, slot.at].filter(Boolean).join(' · ')}</span>
                        ) : null}
                        <span className={styles.name} aria-hidden="true">
                            {slot.state === 'empty' ? '빈 순' : slot.name}
                            {mode === 'column' && slot.summary ? <span className={styles.summary}> · {slot.summary}</span> : null}
                        </span>
                        {mode === 'column' ? (
                            <span className={styles.chips} aria-hidden="true">
                                {slot.state === 'reserved' ? <span className="os-chip os-chip--info">예약</span> : null}
                                {slot.state === 'blocked' ? <span className="os-chip os-chip--rust">막힘</span> : null}
                                {slot.state === 'empty' ? <span className={styles.add}>+ 예약</span> : null}
                                {slot.markers.map((m) => <span key={m} className="os-chip">{m === 'placement' ? '배치' : '방침'}</span>)}
                            </span>
                        ) : null}
                    </button>
                );
            })}
        </div>
    );
}

export default TurnSlots;
