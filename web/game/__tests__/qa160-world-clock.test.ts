import { expect, it } from 'vitest';
import { checkWorldClockContract, checkWorldTickPersisted, sanitizeEvidence } from '../e2e/qa160-evidence';

const before = { year: 190, month: 1, phase: 3, lastTurnTime: '2026-10-16T01:00:00Z' };
const advanced = { year: 190, month: 2, phase: 1, lastTurnTime: '2026-10-16T01:01:00Z' };
const front = (world = before) => ({ serverId: 'qa160', generation: 0, year: world.year, month: world.month, phase: world.phase });
const check = (after = before, f1 = front(after), f2 = f1) => checkWorldClockContract(before, after, f1, f2, front());

it('G1: unchanged SQL YMP and lastTurnTime pass for a personal-only turn (desktop 190/1/3)', () => {
  expect(check()).toEqual({ worldClockBranch: 'UNCHANGED', worldClockOk: true });
});

it('G2: an advanced world clock and persisted timestamp pass', () => {
  expect(check(advanced)).toEqual({ worldClockBranch: 'ADVANCED', worldClockOk: true });
});

it('G3: year rollover and multiple ticks do not require exactly one phase', () => {
  const end = { ...before, month: 12 };
  const next = { ...advanced, year: 191, month: 1 };
  expect(checkWorldClockContract(end, next, front(end), front(next), front(end)).worldClockOk).toBe(true);
});

it('G4: a tick between bracketing reads accepts either SQL endpoint', () => {
  const later = { ...advanced, phase: 2 };
  expect(check(advanced, front(advanced), front(later)).worldClockOk).toBe(true);
  expect(check(later, front(advanced), front(later)).worldClockOk).toBe(true);
});

it('R1: unchanged YMP with a changed timestamp fails', () => {
  expect(check({ ...before, lastTurnTime: advanced.lastTurnTime }).worldClockOk).toBe(false);
});

it('R2: advanced YMP with an unchanged timestamp fails', () => {
  expect(check({ ...advanced, lastTurnTime: before.lastTurnTime }).worldClockOk).toBe(false);
});

it('R3: a backward world date fails', () => {
  expect(check({ ...advanced, year: 189 }).worldClockOk).toBe(false);
});

it('R4: SQL outside the endpoints, including between them or reversed reads, fails', () => {
  expect(check(advanced, front(before), front(before)).worldClockOk).toBe(false);
  const middle = { ...advanced, phase: 2 }, later = { ...advanced, phase: 3 };
  expect(check(middle, front(advanced), front(later)).worldClockOk).toBe(false);
  expect(check(advanced, front(later), front(advanced)).worldClockOk).toBe(false);
});

it('R5: null, empty, whitespace and non-string timestamps on either side fail', () => {
  for (const lastTurnTime of [null, '', '   ', 123]) {
    for (const after of [before, advanced]) {
      expect(checkWorldClockContract({ ...before, lastTurnTime }, after, front(after), front(after), front()).worldClockOk).toBe(false);
      expect(checkWorldClockContract(before, { ...after, lastTurnTime }, front(after), front(after), front()).worldClockOk).toBe(false);
      expect(checkWorldClockContract({ ...before, lastTurnTime }, { ...after, lastTurnTime }, front(after), front(after), front()).worldClockOk).toBe(false);
    }
  }
});

it('R6: generation/server identity changes at either front boundary fail', () => {
  for (const identity of [{ generation: 1 }, { serverId: 'other' }]) {
    expect(check(advanced, { ...front(advanced), ...identity }, front(advanced)).worldClockOk).toBe(false);
    expect(check(advanced, front(advanced), { ...front(advanced), ...identity }).worldClockOk).toBe(false);
  }
  expect(checkWorldClockContract(before, advanced, front(advanced), front(advanced), { ...front(), serverId: 'other' }).worldClockOk).toBe(false);
});

it('R7: S05 rejects advanced YMP without a persisted timestamp change', () => {
  expect(checkWorldTickPersisted(before, { ...advanced, lastTurnTime: before.lastTurnTime }, front(advanced)).ok).toBe(false);
});

it('R8: S05 rejects unchanged SQL YMP even when front advanced', () => {
  expect(checkWorldTickPersisted(before, before, front(advanced)).ok).toBe(false);
});

it('R9: S05 rejects a stray timestamp write without date advancement', () => {
  expect(checkWorldTickPersisted(before, { ...before, lastTurnTime: advanced.lastTurnTime }, front()).ok).toBe(false);
});

it('G5: S05 requires SQL date and timestamp advancement at least to the passed front', () => {
  expect(checkWorldTickPersisted(before, advanced, front(advanced))).toEqual({ clockAdvanced: true, lastTurnChanged: true, ok: true });
  expect(checkWorldTickPersisted(before, advanced, front({ ...advanced, phase: 2 })).ok).toBe(false);
  expect(checkWorldTickPersisted(before, { ...advanced, lastTurnTime: null }, front(advanced)).ok).toBe(false);
});

it('world probes admit only fixed branch enums and booleans, never timestamp/SQL/account text', () => {
  const persisted = { worldClockBranch: 'UNCHANGED', worldClockOk: true, worldLastTurnChanged: false };
  const worldTick = { clockAdvanced: true, lastTurnChanged: true };
  expect(sanitizeEvidence({ status: 'FAIL', probe: { persisted, worldTick } }, 'turn-reservation'))
    .toEqual({ status: 'FAIL', probe: { persisted, worldTick } });
  for (const secret of ['2026-10-16T01:00:00Z', 'SELECT * FROM private', 'account-private']) {
    const value = sanitizeEvidence({ status: 'FAIL', probe: {
      persisted: { worldClockBranch: secret, worldClockOk: secret, lastTurnTime: secret },
      worldTick: { clockAdvanced: secret, lastTurnChanged: secret, sql: secret, account: secret },
    } }, 'turn-reservation');
    expect(JSON.stringify(value)).not.toContain(secret);
    expect(value.sanitizerDropped).toBe(7);
  }
});
