'use client';

// 순 띠 — 12칸(데스크톱 2줄 × 6칸, 모바일 가로로 미는 한 줄). 지금 채우는 순은 청동 테두리.
// 칸에는 서버가 준 것만 싣는다: 명령 이름 또는 「빈 순」. 월 · 순 · 실행 시각은 K4-02 turn-slots가 오면 붙인다.
import type { StripSlot } from '@/lib/command-flow/slots';
import { slotLabel } from '@/lib/command-flow/slots';
import styles from './CommandFlow.module.css';

export interface TurnStripProps {
    readonly slots: readonly StripSlot[];
    readonly current: number;
    readonly onSelect: (turnIdx: number) => void;
}

export default function TurnStrip({ slots, current, onSelect }: TurnStripProps) {
    return (
        <div className={styles.strip} role="group" aria-label="12순 — 채울 순 고르기">
            {slots.map((slot) => {
                const empty = slot.state === 'empty';
                return (
                    <button
                        key={slot.turnIdx}
                        type="button"
                        className={styles.slot}
                        data-empty={empty}
                        data-turn-idx={slot.turnIdx}
                        aria-pressed={slot.turnIdx === current}
                        aria-label={slotLabel(slot)}
                        onClick={() => onSelect(slot.turnIdx)}
                    >
                        <span className={styles.slotNo} aria-hidden="true">{String(slot.turnIdx + 1).padStart(2, '0')}</span>
                        <span className={styles.slotName} aria-hidden="true">{empty ? '빈 순' : slot.name}</span>
                    </button>
                );
            })}
        </div>
    );
}
