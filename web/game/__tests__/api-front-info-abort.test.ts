import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from '@/lib/api';

describe('front-info request cancellation', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('forwards the caller abort signal to fetch', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ general: { hasGeneral: true } }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    const controller = new AbortController();

    await api.frontInfo(controller.signal);

    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/api/front-info'),
      expect.objectContaining({ signal: controller.signal }),
    );
  });
});
