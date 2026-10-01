'use client';

// 12순 — 작전실 12순 열(P-W01, K4가 붙임)과 명령 흐름 순 띠(P-W02, K6)가 같이 쓰는 한 모델 · 한 읽기(K0 결정 2026-10-01).
//
// 정본 읽기는 계약판 K4-02 `GET /api/turn-slots`(순마다 날짜 · 시각 · displayName · argsSummary · 상태 · 효력 표식)다.
// 서버에 아직 없어서(NOT_STARTED) 지금은 `/api/reserved-commands` 예약 링을 같은 모양으로 편다 — 링이 주지 않는
// 날짜 · 시각 · 대상 한 줄 · 효력 표식은 null · 빈 목록으로 둔다(지어내지 않는다). K4-02가 오면 fromTurnSlots를 더하고
// useTurnSlots가 그쪽을 읽는다. 화면은 이 모양만 본다.
import { useCallback, useEffect, useState } from 'react';
import { useTurnRefresh } from '@/hooks/useTurnRefresh';
import { api } from './api';
import { flowCommand } from './command-flow/catalog';
import type { ReservedCommandsResponse, ReservedSlot } from './types';

export const SLOT_COUNT = 12;

export interface TurnSlotView {
    /** 0–11. 01순 = 0 = 다음에 실행될 순. */
    readonly turnIdx: number;
    /** K4-02 state(EMPTY · RESERVED · BLOCKED). 링에는 막힘이 없어 empty · reserved 둘뿐이다. */
    readonly state: 'empty' | 'reserved' | 'blocked';
    readonly inputId: string | null;
    /** 칸에 쓰는 명령 이름 — K4-02 displayName → 명령 표 이름 → 링의 brief · 코드. 빈 순은 null. */
    readonly name: string | null;
    /** 대상 한 줄(K4-02 argsSummary). 링에는 없다. */
    readonly summary: string | null;
    /** 「3월 하순」(K4-02 phaseLabel). 링에는 없다. */
    readonly when: string | null;
    /** 「22:40」(K4-02 scheduledAt). 링에는 없다. */
    readonly at: string | null;
    readonly blockedCode: string | null;
    /** 배치 · 방침 효력 표식(K4-02 standingMarkers). 링에는 없다. */
    readonly markers: readonly ('placement' | 'policy')[];
}

export type TurnSlotsLoad =
    | { readonly state: 'loading' }
    | { readonly state: 'error'; readonly message: string }
    | { readonly state: 'ready'; readonly slots: readonly TurnSlotView[] };

const empty = (turnIdx: number): TurnSlotView => ({
    turnIdx, state: 'empty', inputId: null, name: null, summary: null, when: null, at: null, blockedCode: null, markers: [],
});

/** 예약 링(`/api/reserved-commands`) → 12칸. 0–11 밖의 순은 버린다. */
export function fromReservedCommands(res: ReservedCommandsResponse | null | undefined): TurnSlotView[] {
    const byIdx = new Map<number, ReservedSlot>();
    for (const s of res?.slots ?? []) {
        if (Number.isInteger(s.turnIdx) && s.turnIdx >= 0 && s.turnIdx < SLOT_COUNT) byIdx.set(s.turnIdx, s);
    }
    return Array.from({ length: SLOT_COUNT }, (_, turnIdx) => {
        const s = byIdx.get(turnIdx);
        if (!s) return empty(turnIdx);
        const known = flowCommand(s.action);
        return {
            ...empty(turnIdx),
            state: 'reserved',
            inputId: known ? known.inputId : null,
            name: known ? known.name : (s.brief || s.action || null),
        };
    });
}

export function filledSet(slots: readonly TurnSlotView[]): Set<number> {
    return new Set(slots.filter((s) => s.state !== 'empty').map((s) => s.turnIdx));
}

export function filledCount(slots: readonly TurnSlotView[]): number {
    return slots.filter((s) => s.state !== 'empty').length;
}

/** 「이번 순에 할 일」이 여는 순 — 첫 빈 순. 12순이 다 찼으면 null(흐름은 01순을 열고 「다 찼습니다」를 보인다). */
export function firstEmpty(slots: readonly TurnSlotView[]): number | null {
    return slots.find((s) => s.state === 'empty')?.turnIdx ?? null;
}

/** 「04순 — 빈 순」 · 「01순 — 농지개간」. 읽는 이름표(칸이 좁아 보이는 글자와 따로). */
export function slotLabel(slot: TurnSlotView): string {
    const no = String(slot.turnIdx + 1).padStart(2, '0');
    return `${no}순 — ${slot.name ?? '빈 순'}`;
}

// ── 한 읽기: 예약하면 마운트된 모든 사용처(12순 열 · 순 띠 · 부 명부)가 다시 읽는다 ─────────────
const listeners = new Set<() => void>();
export function announceTurnSlotsChanged() { for (const l of [...listeners]) l(); }

/** generalId가 없으면 부르지 않는다. 턴 갱신 신호 · refreshKey · 다른 곳의 예약에 다시 읽는다. */
export function useTurnSlots(generalId: number | null, refreshKey = 0): { load: TurnSlotsLoad; reload: () => void } {
    const [load, setLoad] = useState<TurnSlotsLoad>({ state: 'loading' });
    const [seq, setSeq] = useState(0);
    const reload = useCallback(() => setSeq((n) => n + 1), []);
    useTurnRefresh(reload);
    useEffect(() => {
        listeners.add(reload);
        return () => { listeners.delete(reload); };
    }, [reload]);
    useEffect(() => {
        if (generalId == null) return undefined;
        let alive = true;
        api.reservedCommands(generalId)
            .then((res) => { if (alive) setLoad({ state: 'ready', slots: fromReservedCommands(res) }); })
            .catch((e: unknown) => { if (alive) setLoad({ state: 'error', message: e instanceof Error ? e.message : '12순을 불러오지 못했습니다' }); });
        return () => { alive = false; };
    }, [generalId, refreshKey, seq]);
    return { load, reload };
}
