import { afterEach, describe, expect, it, vi } from 'vitest';

import {
    __resetCommandSettledListeners,
    deliverCommandSettled,
    subscribeCommandSettled,
} from '@/lib/commandResultEvents';

describe('subscribeCommandSettled', () => {
    afterEach(() => {
        __resetCommandSettledListeners();
    });

    /** 신호에는 식별자가 없다 — 기다리는 쪽이 전부 깨어나 각자 자기 정본을 읽는다. */
    it('신호가 오면 구독자 전원이 깨어난다', () => {
        const first = vi.fn();
        const second = vi.fn();
        subscribeCommandSettled(first);
        subscribeCommandSettled(second);

        deliverCommandSettled();
        deliverCommandSettled();

        expect(first).toHaveBeenCalledTimes(2);
        expect(second).toHaveBeenCalledTimes(2);
    });

    /** 해제가 안 되면 제출할 때마다 리스너가 쌓여 신호 한 번에 죽은 요청이 그만큼 나간다. */
    it('해제한 구독자는 더 이상 깨어나지 않는다', () => {
        const listener = vi.fn();
        const unsubscribe = subscribeCommandSettled(listener);

        unsubscribe();
        deliverCommandSettled();

        expect(listener).not.toHaveBeenCalled();
    });

    /** 콜백 안에서 자기 자신을 해제해도 그 순회가 깨지면 안 된다. */
    it('콜백이 순회 중에 자기 구독을 해제해도 나머지가 깨어난다', () => {
        const other = vi.fn();
        const unsubscribe = subscribeCommandSettled(() => unsubscribe());
        subscribeCommandSettled(other);

        expect(() => deliverCommandSettled()).not.toThrow();
        expect(other).toHaveBeenCalledTimes(1);
    });

    it('구독자가 없어도 신호는 조용히 지나간다', () => {
        expect(() => deliverCommandSettled()).not.toThrow();
    });
});
