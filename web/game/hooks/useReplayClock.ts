'use client';

// 다시 보기 재생 시계(P-H03) — 공용 TimeBar(replay)의 지금 자리 · 재생 · 빠르기를 움직인다.
// 리플레이 본문 모양과 무관하다: 길이(ms)만 받는다. tick → ms 는 본문 어댑터 몫이다
// (계약판 「K10 → C2 리플레이 읽기 모양 제안」 rulePin.tickHz — v1 10Hz 를 여기서 가정하지 않는다).
//  - 재생 중이면 흐른 시간 × 빠르기만큼 나아가고, 끝에 닿으면 끝에 서서 멈춘다.
//  - 끝에 선 채 재생을 누르면 처음부터 다시.
//  - 자리 옮기기는 0 ~ 길이로 자른다. 길이가 0 이면 재생하지 않는다.

import { useCallback, useEffect, useRef, useState } from 'react';
import type { TimeBarEvent, TimeBarSpeed } from '@opensamguk/ui';

/** 화면을 다시 그리는 간격(ms). 원작 tick 길이와 무관하다 — 흐른 실제 시간으로 나아간다. */
export const REPLAY_FRAME_MS = 100;

export interface ReplayClockState {
    readonly position: number;
    readonly playing: boolean;
    readonly speed: TimeBarSpeed;
}

const clamp = (ms: number, duration: number) => Math.min(Math.max(0, duration), Math.max(0, ms));

/** 한 걸음 — 재생 중이면 흐른 시간 × 빠르기만큼 나아가고 끝에 닿으면 멈춘다. */
export function advanceClock(state: ReplayClockState, elapsedMs: number, duration: number): ReplayClockState {
    if (!state.playing || elapsedMs <= 0) return state;
    const next = state.position + elapsedMs * state.speed;
    if (next >= duration) return { ...state, position: Math.max(0, duration), playing: false };
    return { ...state, position: next };
}

/** 재생 · 멈춤 — 끝에 선 채 누르면 처음부터, 길이가 없으면 재생하지 않는다. */
export function togglePlayback(state: ReplayClockState, duration: number): ReplayClockState {
    if (state.playing) return { ...state, playing: false };
    if (duration <= 0) return state;
    return { ...state, playing: true, position: state.position >= duration ? 0 : state.position };
}

/** 지금 자리에서 이미 일어난 마지막 사건 — 「지금」 줄. 아직 아무 일도 없으면 null. */
export function currentEvent(events: readonly TimeBarEvent[], position: number): TimeBarEvent | null {
    let found: TimeBarEvent | null = null;
    for (const event of events) {
        if (event.at <= position && (found === null || event.at >= found.at)) found = event;
    }
    return found;
}

export function useReplayClock(duration: number) {
    const [state, setState] = useState<ReplayClockState>({ position: 0, playing: false, speed: 1 });
    const last = useRef(0);

    useEffect(() => {
        if (!state.playing) return undefined;
        last.current = Date.now();
        const timer = window.setInterval(() => {
            const now = Date.now();
            const elapsed = now - last.current;
            last.current = now;
            setState((s) => advanceClock(s, elapsed, duration));
        }, REPLAY_FRAME_MS);
        return () => window.clearInterval(timer);
    }, [state.playing, duration]);

    // 길이가 줄면(다른 전투로 바뀜) 자리를 안으로 들인다.
    useEffect(() => {
        setState((s) => (s.position > duration ? { ...s, position: clamp(s.position, duration), playing: false } : s));
    }, [duration]);

    const togglePlay = useCallback(() => setState((s) => togglePlayback(s, duration)), [duration]);
    const seek = useCallback((ms: number) => setState((s) => ({ ...s, position: clamp(ms, duration) })), [duration]);
    const setSpeed = useCallback((speed: TimeBarSpeed) => setState((s) => ({ ...s, speed })), []);

    return { ...state, togglePlay, seek, setSpeed };
}
