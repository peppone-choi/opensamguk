import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useFrontInfo } from '@/hooks/useFrontInfo';

const mocks = vi.hoisted(() => ({
  frontInfo: vi.fn(),
  gameConst: vi.fn(),
  globalMenu: vi.fn(),
}));

vi.mock('@/lib/api', () => ({
  api: {
    frontInfo: mocks.frontInfo,
    gameConst: mocks.gameConst,
    globalMenu: mocks.globalMenu,
  },
}));

vi.mock('@/hooks/useTurnRefresh', () => ({
  useTurnRefresh: vi.fn(),
}));

describe('useFrontInfo stalled response', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    mocks.frontInfo.mockReset().mockReturnValue(new Promise(() => undefined));
    mocks.gameConst.mockReset().mockResolvedValue({});
    mocks.globalMenu.mockReset().mockResolvedValue({ menu: [] });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('ends the initial loading state with an actionable error after ten seconds', async () => {
    const { result } = renderHook(() => useFrontInfo());

    const signal = mocks.frontInfo.mock.calls[0]?.[0] as AbortSignal;
    expect(signal).toBeInstanceOf(AbortSignal);
    expect(signal.aborted).toBe(false);

    expect(result.current.loading).toBe(true);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_000);
    });

    expect(result.current.loading).toBe(false);
    expect(result.current.error).toBe('서버 응답이 지연되고 있습니다. 잠시 후 다시 시도해 주세요.');
    expect(signal.aborted).toBe(true);
  });

  it('aborts a pending request when the consumer unmounts', () => {
    const { unmount } = renderHook(() => useFrontInfo());
    const signal = mocks.frontInfo.mock.calls[0]?.[0] as AbortSignal;

    unmount();

    expect(signal.aborted).toBe(true);
  });

  it('can retry successfully after a timed-out request without accepting its late result', async () => {
    const timedOut = Promise.withResolvers<{ marker: string }>();
    mocks.frontInfo
      .mockReset()
      .mockReturnValueOnce(timedOut.promise)
      .mockResolvedValueOnce({ marker: 'fresh' });
    const { result } = renderHook(() => useFrontInfo());

    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_000);
    });
    await act(async () => {
      result.current.refresh();
    });

    expect(result.current.frontInfo).toEqual({ marker: 'fresh' });
    expect(result.current.error).toBeNull();

    await act(async () => {
      timedOut.resolve({ marker: 'stale' });
      await Promise.resolve();
    });

    expect(result.current.frontInfo).toEqual({ marker: 'fresh' });
  });
});
