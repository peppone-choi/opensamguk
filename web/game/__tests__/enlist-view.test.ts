// E04 view model — slot calendar from already-adjusted server metadata, selection, gate and read-back proof.
import { describe, expect, it } from 'vitest';
import {
  candidateKey, effectiveSlot, enlistAvailability, receiptText, reservationMatches, slotWhen, withCalendar, type EnlistReceipt,
} from '@/lib/enlist/enlist-view';

const calendar = { year: 190, month: 12, turnPhase: 3 as const, turnTime: '2026-10-09 23:30:00', turnTerm: 60 };
const empty = (turnIdx: number) => ({ turnIdx, state: 'empty' as const, name: null });
const taken = (turnIdx: number) => ({ turnIdx, state: 'reserved' as const, name: '훈련' });
const nation = { mode: 'NATION' as const, targetId: 2, label: '조조', availability: { status: 'AVAILABLE' as const } };

describe('E04 slot calendar', () => {
  it('slot 0 is exactly the server metadata; later slots add only the slot offset', () => {
    expect(slotWhen(calendar, 0)).toEqual({ when: '190년 12월 하순', at: '23:30' });
    expect(slotWhen(calendar, 1)).toEqual({ when: '191년 1월 상순', at: '00:30' });
    expect(slotWhen(calendar, 5)).toEqual({ when: '191년 2월 중순', at: '04:30' });
    expect(slotWhen(calendar, 11)).toEqual({ when: '191년 4월 중순', at: '10:30' });
  });
  it('turnTerm is minutes and the clock is UTC+09:00 regardless of the runtime zone', () => {
    expect(slotWhen({ ...calendar, turnTime: '2026-10-09 14:50:00', turnTerm: 7 }, 11).at).toBe('16:07');
    expect(slotWhen({ ...calendar, turnTime: '2026-10-09 00:00:00', turnTerm: 1440 }, 3).at).toBe('00:00');
  });
  it('a term over a day still offsets each slot by its real minutes; a 0 term keeps every slot at slot 0', () => {
    expect(slotWhen({ ...calendar, turnTime: '2026-10-09 00:00:00', turnTerm: 1441 }, 1).at).toBe('00:01');
    expect(slotWhen({ ...calendar, turnTime: '2026-10-09 00:00:00', turnTerm: 1441 }, 11).at).toBe('00:11');
    expect(slotWhen({ ...calendar, turnTerm: 0 }, 11).at).toBe('23:30');
  });
  it('month and phase boundaries roll inside the year: 1월 상순 → 4월 하순', () => {
    expect(slotWhen({ ...calendar, month: 1, turnPhase: 1 }, 11).when).toBe('190년 4월 하순');
    expect(slotWhen({ ...calendar, month: 3, turnPhase: 2 }, 1).when).toBe('190년 3월 하순');
  });
  it('null metadata invents nothing; a missing term only labels slot 0', () => {
    const none = { year: null, month: null, turnPhase: null, turnTime: null, turnTerm: null };
    expect(withCalendar([empty(0), empty(11)], none).map(s => [s.when, s.at])).toEqual([[null, null], [null, null]]);
    expect(slotWhen({ ...calendar, turnTerm: null }, 0).at).toBe('23:30');
    expect(slotWhen({ ...calendar, turnTerm: null }, 5).at).toBeNull();
    expect(slotWhen({ ...calendar, year: null }, 5).when).toBeNull();
  });
});

describe('E04 selection and gate', () => {
  it('candidate identity is mode + original targetId', () => {
    expect(candidateKey(nation)).toBe('NATION:2');
    expect(candidateKey({ mode: 'GENERAL', targetId: 2 })).toBe('GENERAL:2');
    expect(candidateKey({ mode: 'RANDOM' })).toBe('RANDOM');
  });
  it('first empty by default; an explicit occupied choice is kept, not moved', () => {
    const slots = [taken(0), taken(1), empty(2), empty(3)];
    expect(effectiveSlot(slots, null)).toBe(2);
    expect(effectiveSlot(slots, 1)).toBe(1);
    expect(effectiveSlot([taken(0)], null)).toBeNull();
  });
  it('blocks loading, vanished candidates, a full ring and occupied slots before trusting the option', () => {
    const gate = { receipt: null, ready: true, option: nation, candidateMissing: false, slot: empty(5) };
    expect(enlistAvailability(gate)).toMatchObject({ status: 'AVAILABLE', inputId: 'action.enlist' });
    expect(enlistAvailability({ ...gate, ready: false }).status).toBe('BLOCKED');
    expect(enlistAvailability({ ...gate, option: undefined, candidateMissing: true }).reason).toContain('사라졌습니다');
    expect(enlistAvailability({ ...gate, slot: undefined }).reason).toContain('12순이 모두 차 있습니다');
    expect(enlistAvailability({ ...gate, slot: taken(5) }).reason).toBe('06순에는 이미 「훈련」이 예약돼 있습니다. 덮어쓰지 않습니다.');
  });
  it.each(['submitting', 'pending', 'reserved', 'applied', 'unknown', 'rejected'] as const)('a %s receipt blocks another write', status => {
    const receipt: EnlistReceipt = { id: 1, status, turnIdx: 4, candidate: 'NATION:2', reason: '거절', code: 'CAPACITY' };
    expect(enlistAvailability({ receipt, ready: true, option: nation, candidateMissing: false, slot: empty(5) }).status).toBe('BLOCKED');
    expect(receiptText(receipt)).toBeTruthy();
  });
  it('only reserved and applied read as success', () => {
    const r = (status: EnlistReceipt['status']) => receiptText({ id: 1, status, turnIdx: 4, candidate: 'RANDOM' });
    expect(r('reserved')).toBe('출사 명령이 05순에 예약되었습니다.');
    expect(r('pending')).not.toMatch(/예약되었습니다|실행되었습니다/);
    expect(r('unknown')).not.toMatch(/예약되었습니다|실행되었습니다/);
  });
});

describe('E04 reservation read-back proof', () => {
  const stored = (turnIdx: number, arg: Record<string, unknown>, action = 'action.enlist') => [{ turnIdx, action, brief: '출사', arg }];
  it('matches only the exact action, slot, mode and original target', () => {
    expect(reservationMatches(stored(5, { mode: 'NATION', targetId: 2 }), 5, nation)).toBe(true);
    expect(reservationMatches(stored(4, { mode: 'NATION', targetId: 2 }), 5, nation)).toBe(false);
    expect(reservationMatches(stored(5, { mode: 'NATION', targetId: 3 }), 5, nation)).toBe(false);
    expect(reservationMatches(stored(5, { mode: 'GENERAL', targetId: 2 }), 5, nation)).toBe(false);
    expect(reservationMatches(stored(5, { mode: 'NATION', targetId: 2 }, 'che_훈련'), 5, nation)).toBe(false);
    expect(reservationMatches(stored(5, { mode: 'NATION', targetId: '2' }), 5, nation)).toBe(false);
    expect(reservationMatches(stored(11, { mode: 'RANDOM' }), 11, { mode: 'RANDOM' })).toBe(true);
    expect(reservationMatches(stored(11, { mode: 'RANDOM', targetId: 2 }), 11, { mode: 'RANDOM' })).toBe(false);
    expect(reservationMatches([], 0, { mode: 'RANDOM' })).toBe(false);
  });
});
