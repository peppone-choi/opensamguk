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
});
