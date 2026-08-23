import { render, act } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useSSE } from '@/hooks/useSSE';

type Listener = (event: Event) => void;

let instances: FakeEventSource[];

class FakeEventSource {
    static CONNECTING = 0;
    static OPEN = 1;
    static CLOSED = 2;

    readonly url: string;
    readyState = FakeEventSource.CONNECTING;
    onopen: ((event: Event) => void) | null = null;
    onerror: ((event: Event) => void) | null = null;
    close = vi.fn(() => {
        this.readyState = FakeEventSource.CLOSED;
    });

    private readonly listeners = new Map<string, Listener[]>();

    constructor(url: string) {
        this.url = url;
        instances.push(this);
    }

    addEventListener(type: string, listener: EventListenerOrEventListenerObject) {
        const callback: Listener =
            typeof listener === 'function' ? listener : (event) => listener.handleEvent(event);
        this.listeners.set(type, [...(this.listeners.get(type) ?? []), callback]);
    }

    open() {
        this.readyState = FakeEventSource.OPEN;
        this.onopen?.(new Event('open'));
    }

    emit(type: string) {
        for (const listener of this.listeners.get(type) ?? []) listener(new Event(type));
    }
}

function Harness({ onEvent }: { onEvent: () => void }) {
    useSSE(onEvent);
    return null;
}

describe('useSSE', () => {
    beforeEach(() => {
        instances = [];
        vi.stubGlobal('EventSource', FakeEventSource);
    });

    afterEach(() => {
        vi.unstubAllGlobals();
    });

    it('keeps a connecting EventSource and dispatches to the latest callback', () => {
        const first = vi.fn();
        const second = vi.fn();
        const { rerender, unmount } = render(<Harness onEvent={first} />);

        expect(instances).toHaveLength(1);
        expect(instances[0].url).toBe('/api/game/sse/turn');

        rerender(<Harness onEvent={second} />);
        expect(instances).toHaveLength(1);

        act(() => {
            instances[0].open();
            instances[0].emit('turnCompleted');
        });

        expect(first).not.toHaveBeenCalled();
        expect(second).toHaveBeenCalledTimes(1);

        unmount();
        expect(instances[0].close).toHaveBeenCalledTimes(1);
        expect(instances[0].readyState).toBe(FakeEventSource.CLOSED);
    });

    it('#514: does not open a zombie EventSource when unmounted while the onerror session check is in flight', async () => {
        vi.useFakeTimers();
        let resolveAuthMe: (res: Response) => void = () => {};
        const fetchMock = vi.fn(
            () => new Promise<Response>((resolve) => {
                resolveAuthMe = resolve;
            }),
        );
        vi.stubGlobal('fetch', fetchMock);

        const { unmount } = render(<Harness onEvent={vi.fn()} />);
        expect(instances).toHaveLength(1);

        act(() => {
            instances[0].onerror?.(new Event('error'));
        });
        expect(fetchMock).toHaveBeenCalledWith('/api/auth/me', { cache: 'no-store' });

        // unmount before the /api/auth/me check resolves — cleanup already ran, aliveRef flipped.
        unmount();

        await act(async () => {
            resolveAuthMe(new Response(null, { status: 200 }));
            await Promise.resolve();
            await Promise.resolve();
        });

        // Advance past the reconnect delay: a leaking implementation schedules a timer here that
        // creates a second EventSource nothing will ever close.
        await act(async () => {
            await vi.advanceTimersByTimeAsync(30000);
        });

        expect(instances).toHaveLength(1);
        vi.unstubAllGlobals();
        vi.useRealTimers();
    });

    it('#514: reloads instead of looping forever once /api/auth/me confirms the session is gone', async () => {
        const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 401 }));
        vi.stubGlobal('fetch', fetchMock);
        const reloadSpy = vi.fn();
        const originalLocation = window.location;
        // jsdom's window.location isn't writable-in-place; replace it for this test only.
        Object.defineProperty(window, 'location', {
            configurable: true,
            value: { ...originalLocation, reload: reloadSpy },
        });

        render(<Harness onEvent={vi.fn()} />);
        expect(instances).toHaveLength(1);

        await act(async () => {
            instances[0].onerror?.(new Event('error'));
            await Promise.resolve();
            await Promise.resolve();
            await Promise.resolve();
        });

        expect(reloadSpy).toHaveBeenCalledTimes(1);
        // no new EventSource was scheduled after the confirmed-expired session.
        expect(instances).toHaveLength(1);

        Object.defineProperty(window, 'location', { configurable: true, value: originalLocation });
        vi.unstubAllGlobals();
    });
});
