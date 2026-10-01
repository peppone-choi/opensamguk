import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchGame } from '@/lib/api';
import { EnlistHttpError, readEnlistOptions, sendEnlist, type EnlistOption } from '@/components/enlist/enlist-api';
vi.mock('@/lib/api', () => ({ fetchGame: vi.fn() }));
const fetchMock = vi.mocked(fetchGame);
const nation: EnlistOption = { mode: 'NATION', targetId: 2, label: '조조', availability: { status: 'AVAILABLE' } };
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
beforeEach(() => fetchMock.mockReset());

describe('E04 authenticated options and writes', () => {
  it.each([401, 403])('preserves exact final HTTP %s on options read', async status => {
    fetchMock.mockResolvedValue(json({ code: status === 401 ? 'UNAUTHORIZED' : 'FORBIDDEN' }, status));
    await expect(readEnlistOptions(7)).rejects.toMatchObject({ status });
    expect(fetchMock).toHaveBeenCalledWith('/api/commands/enlistment-options?generalId=7', expect.objectContaining({ cache: 'no-store' }));
  });
  it('does not treat another 4xx as authentication or empty candidates', async () => {
    fetchMock.mockResolvedValue(json({}, 404));
    await expect(readEnlistOptions(7)).rejects.toMatchObject({ status: 404 });
  });
  it.each([
    { ...nation, targetId: undefined },
    { ...nation, availability: { status: 'UNKNOWN' } },
    { ...nation, targetId: -1 },
  ])('rejects malformed options instead of enabling a write: %j', async bad => {
    fetchMock.mockResolvedValue(json({ result: true, inputId: 'action.enlist', maxReservedTurns: 12, options: [bad] }));
    await expect(readEnlistOptions(7)).rejects.toThrow('출사 정보를 확인하지 못했습니다.');
  });
  it('accepts empty candidates and keeps server reason unchanged', async () => {
    const options = [{ ...nation, availability: { status: 'BLOCKED', code: 'CAPACITY', reason: '해당 주공의 명망 수용량이 부족합니다.' } }];
    fetchMock.mockResolvedValue(json({ result: true, inputId: 'action.enlist', maxReservedTurns: 12, options }));
    expect((await readEnlistOptions(7)).options).toEqual(options);
    fetchMock.mockResolvedValue(json({ result: true, inputId: 'action.enlist', maxReservedTurns: 12, options: [] }));
    expect((await readEnlistOptions(7)).options).toEqual([]);
  });
  it('never submits a blocked candidate', async () => {
    await expect(sendEnlist(7, { ...nation, availability: { status: 'BLOCKED', reason: '명망 부족' } })).rejects.toThrow();
    expect(fetchMock).not.toHaveBeenCalled();
  });
  it('does not replace an occupied first slot', async () => {
    fetchMock.mockResolvedValue(json({ result: true, generalId: 7, slots: [{ turnIdx: 0, action: '훈련' }] }));
    expect(await sendEnlist(7, nation)).toMatchObject({ status: 'BLOCKED' });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
  it.each([401, 403])('preserves exact HTTP %s on slot preflight and never posts', async status => {
    fetchMock.mockResolvedValue(json({}, status));
    await expect(sendEnlist(7, nation)).rejects.toEqual(expect.objectContaining({ status }));
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
  it.each([401, 403])('preserves exact HTTP %s on submit', async status => {
    fetchMock.mockResolvedValueOnce(json({ result: true, generalId: 7, slots: [] })).mockResolvedValueOnce(json({}, status));
    await expect(sendEnlist(7, nation)).rejects.toBeInstanceOf(EnlistHttpError);
    fetchMock.mockResolvedValueOnce(json({ result: true, generalId: 7, slots: [] })).mockResolvedValueOnce(json({}, status));
    await expect(sendEnlist(7, nation)).rejects.toMatchObject({ status });
  });
  it.each([nation, { mode: 'RANDOM', label: '무작위 출사', availability: { status: 'AVAILABLE' } } as EnlistOption])('posts exact server target and first slot; 202 stays queued', async option => {
    fetchMock.mockResolvedValueOnce(json({ result: true, generalId: 7, slots: [] })).mockResolvedValueOnce(json({ status: 'AVAILABLE', requestId: 'req-1' }, 202));
    expect(await sendEnlist(7, option)).toEqual({ status: 'AVAILABLE', requestId: 'req-1' });
    const [path, init] = fetchMock.mock.calls[1];
    expect(path).toBe('/api/command/action.enlist?generalId=7&turnIdx=0');
    expect(JSON.parse(init?.body as string)).toEqual(option.mode === 'RANDOM' ? { mode: 'RANDOM' } : { mode: 'NATION', targetId: 2 });
  });
});
