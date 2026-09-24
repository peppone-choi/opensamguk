import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({ frontInfo: vi.fn(), hwihaVisibility: vi.fn(), hwihaCorps: vi.fn(),
  hwihaWorks: vi.fn(), hwihaSieges: vi.fn(), hwihaScoutOptions: vi.fn() }));
vi.mock('@/lib/api', () => ({ api: mocks }));
import { useMapLayers } from '@/lib/use-map-layers';

beforeEach(() => {
  mocks.frontInfo.mockReset().mockResolvedValue({ general: { generalId: 7 } });
  mocks.hwihaVisibility.mockReset().mockResolvedValue({ status: 'READY', commanderies: [{ no: 1, tier: 'INTEL', ageTurns: 2 }] });
  mocks.hwihaCorps.mockReset().mockResolvedValue({ status: 'READY', corps: [{ corpsId: 'seen', commanderyNo: 1 }] });
  mocks.hwihaWorks.mockReset().mockResolvedValue({ status: 'READY', counties: [] });
  mocks.hwihaSieges.mockReset().mockResolvedValue({ status: 'READY', sieges: [] });
  mocks.hwihaScoutOptions.mockReset().mockResolvedValue({ status: 'READY', options: [] });
});

describe('map layer refresh', () => {
  it('uses the supplied general and retains fog and corps until the same general refresh resolves', async () => {
    const { result, rerender } = renderHook(({ refreshKey }) => useMapLayers('full', refreshKey, 7),
      { initialProps: { refreshKey: 0 } });
    await waitFor(() => expect(result.current.visibility?.get(1)).toBe('INTEL'));
    await waitFor(() => expect(result.current.corps).toHaveLength(1));
    let finish!: (value: unknown) => void;
    mocks.hwihaVisibility.mockImplementationOnce(() => new Promise((resolve) => { finish = resolve; }));
    rerender({ refreshKey: 1 });
    await waitFor(() => expect(finish).toBeTypeOf('function'));
    expect(result.current.visibility?.get(1)).toBe('INTEL');
    expect(result.current.corps).toHaveLength(1);
    await act(async () => { finish({ status: 'READY', commanderies: [{ no: 1, tier: 'FULL' }] }); });
    await waitFor(() => expect(result.current.visibility?.get(1)).toBe('FULL'));
    expect(result.current.intelAge.size).toBe(0);
    expect(mocks.frontInfo).not.toHaveBeenCalled();
  });

  it('clears the failed layer and old intel ages while the map stays mounted', async () => {
    const { result, rerender } = renderHook(({ refreshKey }) => useMapLayers('full', refreshKey, 7),
      { initialProps: { refreshKey: 0 } });
    await waitFor(() => expect(result.current.intelAge.get(1)).toBe(2));
    mocks.hwihaVisibility.mockRejectedValueOnce(new Error('offline'));
    rerender({ refreshKey: 1 });
    await waitFor(() => expect(result.current.visibilityError).toBe(true));
    expect(result.current.visibility).toBeNull();
    expect(result.current.intelAge.size).toBe(0);
    expect(result.current.corps).toEqual([]);
  });
});
