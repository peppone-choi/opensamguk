import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchGame } from '@/lib/api';
import { EnlistHttpError, EnlistScopeChanged, readEnlistOptions, sendEnlist, type EnlistOption } from '@/components/enlist/enlist-api';
vi.mock('@/lib/api', () => ({ fetchGame: vi.fn() }));
const fetchMock = vi.mocked(fetchGame);
const nation: EnlistOption = { mode: 'NATION', targetId: 2, label: '조조', availability: { status: 'AVAILABLE' } };
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const ring = (slots: unknown[] = []) => json({ result: true, generalId: 7, slots, year: 190, month: 1, turnPhase: 1, turnTime: '2026-10-09 21:40:00', turnTerm: 60 });
const open = { preflight: () => null };
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
    [{ ...nation, targetId: undefined }],
    [{ ...nation, availability: { status: 'UNKNOWN' } }],
    [{ ...nation, targetId: -1 }],
    [nation, { ...nation, label: '조조 중복' }],
  ])('rejects malformed or ambiguous options instead of enabling a write: %j', async (...bad) => {
    fetchMock.mockResolvedValue(json({ result: true, inputId: 'action.enlist', maxReservedTurns: 12, options: bad }));
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
    await expect(sendEnlist(7, { ...nation, availability: { status: 'BLOCKED', reason: '명망 부족' } }, 0, open)).rejects.toThrow();
    expect(fetchMock).not.toHaveBeenCalled();
  });
  it.each([undefined, -1, 12, 1.5, Number.NaN])('requires an explicit 0–11 turnIdx before any read (%s)', async turnIdx => {
    await expect(sendEnlist(7, nation, turnIdx as unknown as number, open)).rejects.toThrow();
    expect(fetchMock).not.toHaveBeenCalled();
  });
  it.each([0, 5, 11])('does not replace an occupied slot %s found by the fresh preflight read', async turnIdx => {
    fetchMock.mockResolvedValue(ring([{ turnIdx, action: 'che_훈련', brief: '훈련', arg: {} }]));
    expect(await sendEnlist(7, nation, turnIdx, open)).toMatchObject({ status: 'BLOCKED' });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
  it.each([401, 403])('preserves exact HTTP %s on slot preflight and never posts', async status => {
    fetchMock.mockResolvedValue(json({}, status));
    await expect(sendEnlist(7, nation, 0, open)).rejects.toEqual(expect.objectContaining({ status }));
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
  it.each([
    { result: true, generalId: 8, slots: [] },
    { result: false, generalId: 7, slots: [] },
    { result: true, generalId: 7, slots: [{ turnIdx: 0, action: '휴식', brief: '휴식' }] },
  ])('malformed or foreign preflight ring never posts: %j', async body => {
    fetchMock.mockResolvedValue(json(body));
    await expect(sendEnlist(7, nation, 0, open)).rejects.toThrow('순 정보를 확인하지 못해 예약하지 않았습니다.');
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
  it('scope guard and stale calendar stop before the POST', async () => {
    // A Response body is single-use: each preflight read gets its own.
    fetchMock.mockImplementation(async () => ring());
    await expect(sendEnlist(7, nation, 3, { preflight: () => { throw new EnlistScopeChanged(); } })).rejects.toBeInstanceOf(EnlistScopeChanged);
    expect(await sendEnlist(7, nation, 3, { preflight: () => '순 시각이 바뀌었습니다.' })).toEqual({ status: 'BLOCKED', reason: '순 시각이 바뀌었습니다.' });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock.mock.calls.every(([path]) => path === '/api/reserved-commands?generalId=7')).toBe(true);
  });
  it.each([
    ['null', { year: null, month: null, turnPhase: null, turnTime: null, turnTerm: null }],
    ['absent', {}],
    ['term 0', { year: 190, month: 1, turnPhase: 1, turnTime: '2026-10-09 21:40:00', turnTerm: 0 }],
    ['term 1441', { year: 190, month: 1, turnPhase: 1, turnTime: '2026-10-09 21:40:00', turnTerm: 1441 }],
  ])('%s calendar metadata is valid and still posts', async (_, meta) => {
    fetchMock.mockResolvedValueOnce(json({ result: true, generalId: 7, slots: [], ...meta }))
      .mockResolvedValueOnce(json({ status: 'AVAILABLE', requestId: 'req-1' }, 202));
    expect(await sendEnlist(7, nation, 4, open)).toEqual({ status: 'AVAILABLE', requestId: 'req-1' });
    expect(fetchMock.mock.calls[1][0]).toBe('/api/command/action.enlist?generalId=7&turnIdx=4');
  });
  it.each([
    ['wrong type', { turnTerm: '60' }], ['fraction', { turnTerm: 1.5 }], ['invalid date', { turnTime: '2026-02-30 10:00:00' }],
    ['Int overflow', { turnTerm: 2_147_483_648 }], ['phase 4', { turnPhase: 4 }],
  ])('present malformed metadata (%s) fails the preflight and never posts', async (_, bad) => {
    const meta = { year: 190, month: 1, turnPhase: 1, turnTime: '2026-10-09 21:40:00', turnTerm: 60 };
    fetchMock.mockResolvedValue(json({ result: true, generalId: 7, slots: [], ...meta, ...bad }));
    await expect(sendEnlist(7, nation, 0, open)).rejects.toThrow('순 정보를 확인하지 못해 예약하지 않았습니다.');
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
  it.each([401, 403])('preserves exact HTTP %s on submit', async status => {
    fetchMock.mockResolvedValueOnce(ring()).mockResolvedValueOnce(json({}, status));
    await expect(sendEnlist(7, nation, 0, open)).rejects.toBeInstanceOf(EnlistHttpError);
    fetchMock.mockResolvedValueOnce(ring()).mockResolvedValueOnce(json({}, status));
    await expect(sendEnlist(7, nation, 0, open)).rejects.toMatchObject({ status });
  });
  it('GENERAL sends the selected general unchanged, never its superior or a picker index', async () => {
    const onPost = vi.fn();
    fetchMock.mockResolvedValueOnce(ring()).mockResolvedValueOnce(json({ status: 'AVAILABLE', requestId: 'direct' }, 202));
    await sendEnlist(7, { mode: 'GENERAL', targetId: 101, label: '가상 직속', availability: { status: 'AVAILABLE' } }, 5, { ...open, onPost });
    const [path, init] = fetchMock.mock.calls[1];
    expect(path).toBe('/api/command/action.enlist?generalId=7&turnIdx=5');
    expect(JSON.parse(init?.body as string)).toEqual({ mode: 'GENERAL', targetId: 101 });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(onPost).toHaveBeenCalledTimes(1);
  });
  it.each([
    [nation, 0], [nation, 5], [nation, 11],
    [{ mode: 'RANDOM', label: '무작위 출사', availability: { status: 'AVAILABLE' } } as EnlistOption, 11],
  ] as const)('posts exact server target %j to slot %s; 202 stays queued', async (option, turnIdx) => {
    fetchMock.mockResolvedValueOnce(ring()).mockResolvedValueOnce(json({ status: 'AVAILABLE', requestId: 'req-1' }, 202));
    expect(await sendEnlist(7, option, turnIdx, open)).toEqual({ status: 'AVAILABLE', requestId: 'req-1' });
    const [path, init] = fetchMock.mock.calls[1];
    expect(path).toBe(`/api/command/action.enlist?generalId=7&turnIdx=${turnIdx}`);
    expect(JSON.parse(init?.body as string)).toEqual(option.mode === 'RANDOM' ? { mode: 'RANDOM' } : { mode: 'NATION', targetId: 2 });
  });
});
