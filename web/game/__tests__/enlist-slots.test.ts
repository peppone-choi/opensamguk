// E04 owned 12-slot ring read — strict row shapes, owner and calendar metadata boundaries.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchGame } from '@/lib/api';
import { EnlistHttpError, parseEnlistSlots, readEnlistSlots, selectedEnlistServer } from '@/lib/api/enlist-slots';
vi.mock('@/lib/api', () => ({ fetchGame: vi.fn() }));
const fetchMock = vi.mocked(fetchGame);
const row = (turnIdx: number) => ({ turnIdx, action: 'action.train', brief: '훈련', arg: {} });
const meta = { year: 190, month: 12, turnPhase: 3, turnTime: '2026-10-09 23:30:00', turnTerm: 60 };
beforeEach(() => fetchMock.mockReset());
afterEach(() => { window.history.replaceState(null, '', '/'); document.cookie = 'sam_server=; max-age=0; path=/'; });

describe('E04 owned ring read', () => {
  it('a valid empty list is twelve empty slots, not an error', () => {
    expect(parseEnlistSlots({ result: true, generalId: 7, slots: [], ...meta }, 7)).toEqual({
      generalId: 7, slots: [], calendar: { year: 190, month: 12, turnPhase: 3, turnTime: '2026-10-09 23:30:00', turnTerm: 60 },
    });
  });
  it('keeps full rows including action and arg', () => {
    const rows = [row(0), { turnIdx: 11, action: 'action.enlist', brief: '출사', arg: { mode: 'NATION', targetId: 2 } }];
    expect(parseEnlistSlots({ result: true, generalId: 7, slots: rows }, 7).slots).toEqual(rows);
  });
  it.each([
    ['result false', { result: false, generalId: 7, slots: [] }],
    ['missing result', { generalId: 7, slots: [] }],
    ['foreign owner', { result: true, generalId: 8, slots: [] }],
    ['null owner', { result: true, generalId: null, slots: [] }],
    ['slots not array', { result: true, generalId: 7, slots: {} }],
    ['duplicate slot', { result: true, generalId: 7, slots: [row(3), row(3)] }],
    ['slot 12', { result: true, generalId: 7, slots: [row(12)] }],
    ['negative slot', { result: true, generalId: 7, slots: [row(-1)] }],
    ['fractional slot', { result: true, generalId: 7, slots: [row(1.5)] }],
    ['overflow', { result: true, generalId: 7, slots: Array.from({ length: 13 }, (_, i) => row(i % 12)) }],
    ['missing action', { result: true, generalId: 7, slots: [{ turnIdx: 0, brief: '', arg: {} }] }],
    ['blank action', { result: true, generalId: 7, slots: [{ ...row(0), action: ' ' }] }],
    ['missing brief', { result: true, generalId: 7, slots: [{ turnIdx: 0, action: '휴식', arg: {} }] }],
    ['array arg', { result: true, generalId: 7, slots: [{ ...row(0), arg: [] }] }],
    ['null arg', { result: true, generalId: 7, slots: [{ ...row(0), arg: null }] }],
    ['null row', { result: true, generalId: 7, slots: [null] }],
  ])('%s is malformed, never empty or default', (_, body) => {
    expect(() => parseEnlistSlots(body, 7)).toThrow('순 정보를 확인하지 못해 예약하지 않았습니다.');
  });
  it('valid null metadata stays null and does not invalidate the ring', () => {
    const read = parseEnlistSlots({ result: true, generalId: 7, slots: [], year: null, month: null, turnPhase: null, turnTime: null, turnTerm: null }, 7);
    expect(read.calendar).toEqual({ year: null, month: null, turnPhase: null, turnTime: null, turnTerm: null });
  });
  it('absent metadata is unknown, not malformed', () => {
    expect(parseEnlistSlots({ result: true, generalId: 7, slots: [] }, 7).calendar)
      .toEqual({ year: null, month: null, turnPhase: null, turnTime: null, turnTerm: null });
  });
  it.each([
    ['month 0', { month: 0 }], ['month 13', { month: 13 }], ['month fractional', { month: 1.5 }], ['phase 0', { turnPhase: 0 }],
    ['phase 4', { turnPhase: 4 }], ['phase string', { turnPhase: '1' }], ['year 0', { year: 0 }], ['year string', { year: '190' }],
    ['year over Int', { year: 2_147_483_648 }], ['term negative', { turnTerm: -1 }], ['term fractional', { turnTerm: 1.5 }],
    ['term string', { turnTerm: '60' }], ['term over Int', { turnTerm: 2_147_483_648 }], ['term object', { turnTerm: {} }],
    ['time 24h', { turnTime: '2026-10-09 24:00:00' }], ['time Feb 30', { turnTime: '2026-02-30 10:00:00' }],
    ['time ISO', { turnTime: '2026-10-09T21:40:00+09:00' }], ['time number', { turnTime: 0 }],
  ])('present malformed %s fails closed, never a null calendar or empty ring', (_, bad) => {
    expect(() => parseEnlistSlots({ result: true, generalId: 7, slots: [], ...meta, ...bad }, 7)).toThrow('순 정보를 확인하지 못해 예약하지 않았습니다.');
  });
  it('accepts leap day and real turn terms of 0, 1440, 1441 and the Int maximum', () => {
    const read = parseEnlistSlots({ result: true, generalId: 7, slots: [], ...meta, turnTime: '2028-02-29 00:00:00', turnTerm: 1440 }, 7);
    expect(read.calendar).toMatchObject({ turnTime: '2028-02-29 00:00:00', turnTerm: 1440 });
    for (const turnTerm of [0, 1441, 2_147_483_647]) {
      expect(parseEnlistSlots({ result: true, generalId: 7, slots: [], ...meta, turnTerm }, 7).calendar.turnTerm).toBe(turnTerm);
    }
  });
  it.each([401, 403, 500])('keeps exact HTTP %s', async status => {
    fetchMock.mockResolvedValue(new Response('{}', { status }));
    const error = await readEnlistSlots(7).catch(e => e);
    expect(error).toBeInstanceOf(EnlistHttpError);
    expect(error.status).toBe(status);
    expect(fetchMock).toHaveBeenCalledWith('/api/reserved-commands?generalId=7', expect.objectContaining({ cache: 'no-store' }));
  });
  it('never reads for a non-positive actor', async () => {
    await expect(readEnlistSlots(0)).rejects.toThrow();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe('E04 selected server follows the transport', () => {
  it('URL path server wins over the shared cookie', () => {
    document.cookie = 'sam_server=beta; path=/';
    window.history.replaceState(null, '', '/game/alpha/join');
    expect(selectedEnlistServer()).toBe('alpha');
  });
  it('explicit ?server= wins when the path has no server', () => {
    document.cookie = 'sam_server=beta; path=/';
    window.history.replaceState(null, '', '/game/join?server=gamma');
    expect(selectedEnlistServer()).toBe('gamma');
  });
  it('falls back to the sam_server cookie, else none', () => {
    window.history.replaceState(null, '', '/game/join');
    expect(selectedEnlistServer()).toBe('');
    document.cookie = 'sam_server=beta; path=/';
    expect(selectedEnlistServer()).toBe('beta');
  });
});
