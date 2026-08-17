import { render, act } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import Shell from '@/components/Shell';
import { useTurnRefresh } from '@/hooks/useTurnRefresh';
import { __resetTurnListeners, deliverTurnCompleted } from '@/lib/turnEvents';

// OPENSAM-196 — 턴 SSE는 페이지를 리로드하지 않고 화면별 재조회를 깨운다.
//
// 이 파일이 지키는 것: (1) Shell이 `window.location.reload`를 부르지 않는다, (2) Shell의 연결 하나가
// 화면들에게 신호를 나눠 준다, (3) 언마운트하면 구독이 사라진다(리스너 누수 = 죽은 화면의 요청).

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

    emit(type: string) {
        for (const listener of this.listeners.get(type) ?? []) listener(new Event(type));
    }
}

vi.mock('next/navigation', () => ({
    usePathname: () => '/game/board',
    useRouter: () => ({ back: vi.fn(), push: vi.fn() }),
    useParams: () => ({}),
}));

vi.mock('@/components/Header', () => ({ default: () => <header data-testid="header" /> }));
vi.mock('@/components/BottomNav', () => ({ default: () => <nav data-testid="bottom-nav" /> }));

function Screen({ onTurn }: { onTurn: () => void }) {
    useTurnRefresh(onTurn);
    return null;
}

describe('useTurnRefresh', () => {
    beforeEach(() => {
        instances = [];
        __resetTurnListeners();
        vi.stubGlobal('EventSource', FakeEventSource);
    });

    afterEach(() => {
        __resetTurnListeners();
        vi.unstubAllGlobals();
    });

    it('구독자에게 턴 신호를 전달하고 해제 뒤에는 부르지 않는다', () => {
        const onTurn = vi.fn();
        const { unmount } = render(<Screen onTurn={onTurn} />);

        act(() => deliverTurnCompleted());
        expect(onTurn).toHaveBeenCalledTimes(1);

        unmount();
        act(() => deliverTurnCompleted());
        expect(onTurn).toHaveBeenCalledTimes(1);
    });

    it('콜백이 매 렌더 새로 만들어져도 구독을 다시 걸지 않고 최신 것을 부른다', () => {
        const first = vi.fn();
        const second = vi.fn();
        const { rerender } = render(<Screen onTurn={first} />);
        rerender(<Screen onTurn={second} />);

        act(() => deliverTurnCompleted());

        expect(first).not.toHaveBeenCalled();
        expect(second).toHaveBeenCalledTimes(1);
    });

    /**
     * 회귀 방지의 핵심. 예전 Shell은 턴마다 `window.location.reload()`를 불러 스크롤·입력 중이던
     * 폼·열린 모달을 전부 날렸다.
     */
    it('Shell은 턴 SSE에 리로드하지 않고 화면 구독자를 깨운다', () => {
        const reload = vi.fn();
        const onTurn = vi.fn();
        const original = window.location;
        Object.defineProperty(window, 'location', {
            configurable: true,
            value: { ...original, reload },
        });

        try {
            render(
                <Shell>
                    <Screen onTurn={onTurn} />
                </Shell>,
            );

            expect(instances).toHaveLength(1);
            act(() => instances[0].emit('turnCompleted'));

            expect(reload).not.toHaveBeenCalled();
            expect(onTurn).toHaveBeenCalledTimes(1);
        } finally {
            Object.defineProperty(window, 'location', { configurable: true, value: original });
        }
    });
});
