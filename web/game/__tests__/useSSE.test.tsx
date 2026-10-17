import { render, act, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DISCONNECT_NOTICE_MS, useSSE } from '@/hooks/useSSE';
import { subscribeCommandSettled } from '@/lib/commandResultEvents';

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
    const { disconnected } = useSSE(onEvent);
    return <span data-testid="sse-disconnected">{String(disconnected)}</span>;
}

/** Drop the stream: onerror → /api/auth/me check (alive) → the reconnect timer is armed. */
async function dropStream(es: FakeEventSource) {
    await act(async () => {
        es.onerror?.(new Event('error'));
        await Promise.resolve();
        await Promise.resolve();
        await Promise.resolve();
    });
}

describe('useSSE', () => {
    let originalLocation: Location;

    beforeEach(() => {
        instances = [];
        originalLocation = window.location;
        vi.stubGlobal('EventSource', FakeEventSource);
    });

    afterEach(() => {
        window.history.replaceState(null, '', '/');
        // 개별 테스트 본문의 마지막 줄이 아니라 여기서 복원한다 — 도중에 assertion이 던지면
        // 본문 끝의 복원 줄은 실행되지 않고 fake timers/mocked location이 다음 테스트로 샌다.
        vi.unstubAllGlobals();
        vi.useRealTimers();
        Object.defineProperty(window, 'location', { configurable: true, value: originalLocation });
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
    });

    it('#514: reloads instead of looping forever once /api/auth/me confirms the session is gone', async () => {
        const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 401 }));
        vi.stubGlobal('fetch', fetchMock);
        const reloadSpy = vi.fn();
        // jsdom's window.location isn't writable-in-place; replace it for this test only
        // (restored in afterEach).
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
    });

    it('#514: a transient 502 from /api/auth/me does not evict a still-valid session', async () => {
        vi.useFakeTimers();
        // /api/auth/me intentionally returns 502 (never touches cookies) for upstream hiccups —
        // only 401/403 mean "the session is actually gone" (see app/api/auth/me/route.ts).
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 502 })));
        const reloadSpy = vi.fn();
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
        });

        expect(reloadSpy).not.toHaveBeenCalled();

        // A 502 is transient — the existing backoff reconnect must still fire.
        await act(async () => {
            await vi.advanceTimersByTimeAsync(2000);
        });
        expect(instances).toHaveLength(2);
    });

    describe('gap 4 — reconnect', () => {
        beforeEach(() => {
            vi.useFakeTimers();
            vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 200 })));
        });

        it('first open reads nothing; a reopened stream re-reads once (turn paths + command results)', async () => {
            const onEvent = vi.fn();
            const settled = vi.fn();
            const unsubscribe = subscribeCommandSettled(settled);
            try {
                render(<Harness onEvent={onEvent} />);
                act(() => instances[0].open());
                expect(onEvent).not.toHaveBeenCalled();
                expect(settled).not.toHaveBeenCalled();

                await dropStream(instances[0]);
                await act(async () => { await vi.advanceTimersByTimeAsync(2000); });
                expect(instances).toHaveLength(2);
                expect(onEvent).not.toHaveBeenCalled();

                act(() => instances[1].open());
                expect(onEvent).toHaveBeenCalledTimes(1);
                expect(settled).toHaveBeenCalledTimes(1);
            } finally {
                unsubscribe();
            }
        });

        it('shows disconnected after the notice delay and clears it when the stream reopens', async () => {
            render(<Harness onEvent={vi.fn()} />);
            act(() => instances[0].open());
            expect(screen.getByTestId('sse-disconnected')).toHaveTextContent('false');

            await dropStream(instances[0]);
            // the first retry (2s) also fails to open before the notice delay passes
            await act(async () => { await vi.advanceTimersByTimeAsync(DISCONNECT_NOTICE_MS - 1); });
            expect(screen.getByTestId('sse-disconnected')).toHaveTextContent('false');
            await act(async () => { await vi.advanceTimersByTimeAsync(1); });
            expect(screen.getByTestId('sse-disconnected')).toHaveTextContent('true');

            act(() => instances[instances.length - 1].open());
            expect(screen.getByTestId('sse-disconnected')).toHaveTextContent('false');
        });

        it('a blip that reconnects on the first retry never shows disconnected', async () => {
            render(<Harness onEvent={vi.fn()} />);
            act(() => instances[0].open());
            await dropStream(instances[0]);
            await act(async () => { await vi.advanceTimersByTimeAsync(2000); });
            act(() => instances[1].open());
            await act(async () => { await vi.advanceTimersByTimeAsync(DISCONNECT_NOTICE_MS * 2); });
            expect(screen.getByTestId('sse-disconnected')).toHaveTextContent('false');
        });
    });

    it('pins the tab world from /game/<id> so another tab rewriting sam_server cannot redirect the stream', () => {
        window.history.replaceState(null, '', '/game/pep/retinue');
        render(<Harness onEvent={vi.fn()} />);
        expect(instances[0].url).toBe('/api/game/sse/turn?server=pep');
    });

    it('pins the tab world from /game?server=', () => {
        window.history.replaceState(null, '', '/game?server=s2');
        render(<Harness onEvent={vi.fn()} />);
        expect(instances[0].url).toBe('/api/game/sse/turn?server=s2');
    });
});
