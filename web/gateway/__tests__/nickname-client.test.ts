import { beforeEach, describe, expect, it, vi } from 'vitest';
import { changeNickname } from '@/lib/client';

describe('nickname client response boundary', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('rejects a successful response whose user payload is incomplete', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ user: { id: 1, nickname: '새별명' } }),
      { status: 200, headers: { 'Content-Type': 'application/json' } },
    )));

    await expect(changeNickname('새별명')).rejects.toThrow('계정 서버 응답이 올바르지 않습니다.');
  });
});
