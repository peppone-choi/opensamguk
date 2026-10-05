// 다시 보기 재생 시계(P-H03) — 공용 TimeBar 를 움직이는 시계: 나아가기 · 끝에서 멈춤 · 끝에서 다시 · 자리 자르기 · 빠르기 · 「지금」 사건.
import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { REPLAY_FRAME_MS, advanceClock, currentEvent, togglePlayback, useReplayClock } from '@/hooks/useReplayClock';

describe('advanceClock · togglePlayback', () => {
    const at = (position: number, playing = true, speed: 0.5 | 1 | 2 = 1) => ({ position, playing, speed });

    it('재생 중이면 흐른 시간 × 빠르기만큼 나아가고, 멈춘 동안은 그대로다', () => {
        expect(advanceClock(at(1000), 500, 10_000).position).toBe(1500);
        expect(advanceClock(at(1000, true, 2), 500, 10_000).position).toBe(2000);
        expect(advanceClock(at(1000, true, 0.5), 500, 10_000).position).toBe(1250);
        expect(advanceClock(at(1000, false), 500, 10_000)).toEqual(at(1000, false));
    });

    it('끝에 닿으면 끝에 서서 멈춘다', () => {
        expect(advanceClock(at(9800), 500, 10_000)).toEqual(at(10_000, false));
    });

    it('끝에 선 채 재생하면 처음부터, 길이가 0 이면 재생하지 않는다', () => {
        expect(togglePlayback(at(10_000, false), 10_000)).toEqual(at(0, true));
        expect(togglePlayback(at(4000, false), 10_000)).toEqual(at(4000, true));
        expect(togglePlayback(at(4000, true), 10_000)).toEqual(at(4000, false));
        expect(togglePlayback(at(0, false), 0)).toEqual(at(0, false));
    });
});

describe('currentEvent', () => {
    const events = [
        { id: 'a', at: 0, label: '개전' },
        { id: 'c', at: 9000, label: '후퇴' },
        { id: 'b', at: 4000, label: '일기토' },
    ];
    it('지금 자리까지 일어난 마지막 사건 — 순서가 섞여 와도 시각으로 고른다', () => {
        expect(currentEvent(events, 0)?.id).toBe('a');
        expect(currentEvent(events, 5000)?.id).toBe('b');
        expect(currentEvent(events, 9000)?.id).toBe('c');
        expect(currentEvent(events.slice(1), 100)).toBeNull();
    });
});

describe('useReplayClock', () => {
    beforeEach(() => vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] }));
    afterEach(() => vi.useRealTimers());

    it('재생 → 시간이 흐르면 나아가고, 빠르기 2배면 두 배로, 끝에서 멈춘다', () => {
        const { result } = renderHook(() => useReplayClock(3000));
        expect(result.current.position).toBe(0);
        act(() => result.current.togglePlay());
        act(() => { vi.advanceTimersByTime(1000); });
        expect(result.current.position).toBeCloseTo(1000, -2);
        act(() => result.current.setSpeed(2));
        act(() => { vi.advanceTimersByTime(500); });
        expect(result.current.position).toBeCloseTo(2000, -2);
        act(() => { vi.advanceTimersByTime(5000); });
        expect(result.current.position).toBe(3000);
        expect(result.current.playing).toBe(false);
    });

    it('멈추면 더 나아가지 않고, 자리 옮기기는 0 ~ 길이로 자른다', () => {
        const { result } = renderHook(() => useReplayClock(3000));
        act(() => result.current.togglePlay());
        act(() => { vi.advanceTimersByTime(REPLAY_FRAME_MS * 3); });
        act(() => result.current.togglePlay());
        const stopped = result.current.position;
        act(() => { vi.advanceTimersByTime(2000); });
        expect(result.current.position).toBe(stopped);
        act(() => result.current.seek(99_999));
        expect(result.current.position).toBe(3000);
        act(() => result.current.seek(-5));
        expect(result.current.position).toBe(0);
    });

    it('길이가 줄면 자리를 안으로 들이고 멈춘다', () => {
        const { result, rerender } = renderHook(({ d }) => useReplayClock(d), { initialProps: { d: 3000 } });
        act(() => result.current.seek(2500));
        rerender({ d: 1000 });
        expect(result.current.position).toBe(1000);
        expect(result.current.playing).toBe(false);
    });
});
