// 순 띠(TurnStrip) 모델 — 지금은 `GET /api/reserved-commands`(삼모 CommandRegistry 기반, 계약판 K4-02
// `turn-slots`가 오면 바꾼다)를 12칸으로 편다. 서버가 안 준 값(월 · 순 · 시각)은 지어내지 않는다 — null.
import type { ReservedCommandsResponse, ReservedSlot } from '../types';
import { flowCommand } from './catalog';
import { SLOT_COUNT } from './flow-state';

export interface StripSlot {
    /** 0–11 */
    readonly turnIdx: number;
    readonly state: 'empty' | 'reserved';
    readonly inputId: string | null;
    /** 칸에 쓰는 명령 이름 — 표에 있으면 표 이름, 없으면 서버 요약(brief) · 코드. 빈 순은 null. */
    readonly name: string | null;
    readonly brief: string | null;
}

export function buildStrip(res: ReservedCommandsResponse | null | undefined): StripSlot[] {
    const byIdx = new Map<number, ReservedSlot>();
    for (const s of res?.slots ?? []) {
        if (Number.isInteger(s.turnIdx) && s.turnIdx >= 0 && s.turnIdx < SLOT_COUNT) byIdx.set(s.turnIdx, s);
    }
    return Array.from({ length: SLOT_COUNT }, (_, turnIdx) => {
        const s = byIdx.get(turnIdx);
        if (!s) return { turnIdx, state: 'empty', inputId: null, name: null, brief: null };
        const known = flowCommand(s.action);
        return {
            turnIdx,
            state: 'reserved',
            inputId: known ? known.inputId : null,
            name: known ? known.name : (s.brief || s.action || null),
            brief: s.brief || null,
        };
    });
}

export function filledSet(strip: readonly StripSlot[]): Set<number> {
    return new Set(strip.filter(s => s.state === 'reserved').map(s => s.turnIdx));
}

/** 칸 이름표 — 「04 · 빈 순」. 월 · 순 · 시각은 서버가 슬롯별로 줄 때(K4-02) 붙인다. */
export function slotLabel(slot: StripSlot): string {
    const no = String(slot.turnIdx + 1).padStart(2, '0');
    return `${no}순 — ${slot.name ?? '빈 순'}`;
}
