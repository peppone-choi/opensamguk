import { afterEach, describe, expect, it, vi } from 'vitest';

import {
    __resetCommandSettledWaiters,
    deliverCommandSettled,
    waitForCommandSettled,
} from '@/lib/commandResultEvents';

describe('waitForCommandSettled', () => {
    afterEach(() => {
        __resetCommandSettledWaiters();
        vi.useRealTimers();
    });

    it('같은 requestId 신호만 깨우고 다른 명령의 신호는 무시한다', async () => {
        const waited = waitForCommandSettled('req-a', 1_000);

        deliverCommandSettled(JSON.stringify({ requestId: 'req-b' }));
        deliverCommandSettled(JSON.stringify({ requestId: 'req-a' }));

        await expect(waited).resolves.toBe(true);
    });

    it('신호가 오지 않으면 타임아웃에 false로 끝난다', async () => {
        vi.useFakeTimers();
        const waited = waitForCommandSettled('req-c', 500);

        await vi.advanceTimersByTimeAsync(500);

        await expect(waited).resolves.toBe(false);
    });

    /** 깨진 프레임에 대기자가 끌려가면 아직 준비 안 된 정본을 읽고 PENDING으로 접힌다. */
    it('해독할 수 없거나 requestId 없는 프레임은 아무도 깨우지 않는다', async () => {
        vi.useFakeTimers();
        const waited = waitForCommandSettled('req-d', 500);

        deliverCommandSettled('{not json');
        deliverCommandSettled(JSON.stringify({ at: '2026-08-17T00:00:00Z' }));
        await vi.advanceTimersByTimeAsync(499);
        deliverCommandSettled(JSON.stringify({ requestId: '' }));
        await vi.advanceTimersByTimeAsync(1);

        await expect(waited).resolves.toBe(false);
    });

    /** 같은 화면에서 두 명령을 연달아 보내면 각자 자기 신호에만 깨어나야 한다. */
    it('여러 대기자가 서로의 신호에 끌려가지 않는다', async () => {
        vi.useFakeTimers();
        const first = waitForCommandSettled('req-e', 1_000);
        const second = waitForCommandSettled('req-f', 1_000);

        deliverCommandSettled(JSON.stringify({ requestId: 'req-f' }));
        await expect(second).resolves.toBe(true);

        await vi.advanceTimersByTimeAsync(1_000);
        await expect(first).resolves.toBe(false);
    });
});
