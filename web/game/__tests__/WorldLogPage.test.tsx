import { act, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import WorldLogPage from '@/app/game/world-log/page';

type Listener = (event: Event) => void;

class FakeEventSource {
  static CONNECTING = 0;
  static OPEN = 1;
  static CLOSED = 2;
  readonly url: string;
  readyState = FakeEventSource.CONNECTING;
  onerror: ((event: Event) => void) | null = null;
  close = vi.fn(() => {
    this.readyState = FakeEventSource.CLOSED;
  });
  private readonly listeners = new Map<string, Listener[]>();

  constructor(url: string) {
    this.url = url;
    eventSources.push(this);
  }

  addEventListener(type: string, listener: EventListenerOrEventListenerObject) {
    const callback: Listener = typeof listener === 'function' ? listener : (event) => listener.handleEvent(event);
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), callback]);
  }

  emit(type: string) {
    for (const listener of this.listeners.get(type) ?? []) listener(new Event(type));
  }
}

const mocks = vi.hoisted(() => ({
  worldLog: vi.fn(),
}));

let eventSources: FakeEventSource[] = [];

vi.mock('@/lib/api', () => ({
  api: {
    worldLog: mocks.worldLog,
  },
}));

vi.mock('@/components/Shell', () => ({
  default: ({ children }: { children: ReactNode }) => <main>{children}</main>,
}));

vi.mock('@/components/GameCard', () => ({
  default: ({ children }: { children: ReactNode }) => <section>{children}</section>,
}));

describe('WorldLogPage', () => {
  beforeEach(() => {
    eventSources = [];
    vi.stubGlobal('EventSource', FakeEventSource);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
  });

  it('refetches world logs when a turn completes', async () => {
    mocks.worldLog
      .mockResolvedValueOnce({
        entries: [{ id: 1, year: 187, month: 1, phase: 1, phaseText: '상순', text: '첫 기록' }],
      })
      .mockResolvedValueOnce({
        entries: [{ id: 2, year: 187, month: 1, phase: 2, phaseText: '중순', text: '새 기록' }],
      });

    render(<WorldLogPage />);

    await waitFor(() => expect(screen.getByText('첫 기록')).toBeInTheDocument());
    expect(eventSources).toHaveLength(1);

    await act(async () => {
      eventSources[0].emit('turnCompleted');
    });

    await waitFor(() => expect(screen.getByText('새 기록')).toBeInTheDocument());
    expect(screen.getByText('187년 1월 중순')).toBeInTheDocument();
    expect(mocks.worldLog).toHaveBeenCalledTimes(2);
  });
});
