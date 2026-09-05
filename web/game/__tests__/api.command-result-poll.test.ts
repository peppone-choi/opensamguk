import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, pollCommandResultResponse, type CommandResultResponse } from '@/lib/api';

const pendingResult: CommandResultResponse = {
  status: 'PENDING',
  requestId: 'req-poll',
};

const resolvedReservation: CommandResultResponse = {
  status: 'RESOLVED',
  requestId: 'req-poll',
  ok: true,
  type: 'reservationAccepted',
  result: { commandKind: 'RESERVED_TURN' },
};

describe('pollCommandResultResponse', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it('reads the canonical result immediately before waiting for a retry interval', async () => {
    const lookup = vi.spyOn(api, 'commandResult').mockResolvedValue(resolvedReservation);

    const resultPromise = pollCommandResultResponse('req-poll');
    await Promise.resolve();

    expect(lookup).toHaveBeenCalledWith('req-poll');
    await expect(resultPromise).resolves.toEqual(resolvedReservation);
  });

  it('waits 300 milliseconds between a pending result and the next lookup', async () => {
    const lookup = vi.spyOn(api, 'commandResult')
      .mockResolvedValueOnce(pendingResult)
      .mockResolvedValueOnce(resolvedReservation);

    const resultPromise = pollCommandResultResponse('req-poll');
    await Promise.resolve();
    expect(lookup).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(299);
    expect(lookup).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(1);
    await expect(resultPromise).resolves.toEqual(resolvedReservation);
    expect(lookup).toHaveBeenCalledTimes(2);
  });
});
