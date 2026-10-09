// Same-tab cancellation journal: minimal records, persistence before dispatch, corrupt-storage blocking and updates limited to the original intent UUID.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  findIntent, journalRecord, persistIntent, readJournal, removeIntent, removeUnsettledIntent, subscribeJournal, updateIntent,
  type JournalRecord,
} from '@/lib/command-flow/reservation-cancel-journal';
import { rev, withSessionStorage } from './fixtures/reservation-cancel';

const KEY = 'opensamguk.reservationCancel';
const scope = { accountId: 1, server: 'pep', generation: null, actor: 7, turnIdx: 0 };
const intent = { ...scope, revision: rev(1), requestId: rev(900) };

/** The records of a readable journal; an unreadable one fails the test instead of being cast away. */
function records(): readonly JournalRecord[] {
  const read = readJournal();
  if (!read.ok) throw new Error('the same-tab journal is unreadable');
  return read.records;
}

beforeEach(() => window.sessionStorage.clear());
afterEach(() => vi.restoreAllMocks());

describe('record', () => {
  it('keeps only the minimal fields — no token, command arg or profile; generation only when provided', () => {
    expect(journalRecord(intent, 'sending')).toEqual({
      schemaVersion: 1, accountId: 1, server: 'pep', actor: 7, turnIdx: 0, revision: rev(1), requestId: rev(900), cancelState: 'sending',
    });
    expect(journalRecord({ ...intent, generation: 'g-3' }, 'unknown')).toMatchObject({ generation: 'g-3' });
  });
});

describe('persist before dispatch', () => {
  it('stores and reads back the intent', () => {
    expect(persistIntent(journalRecord(intent, 'sending'))).toBe('ok');
    expect(readJournal()).toEqual({ ok: true, records: [journalRecord(intent, 'sending')] });
    expect(JSON.parse(window.sessionStorage.getItem(KEY) ?? '[]')).toHaveLength(1);
  });

  it('never replaces an older UUID for the same slot with a fresh one', () => {
    persistIntent(journalRecord(intent, 'unknown'));
    expect(persistIntent(journalRecord({ ...intent, requestId: rev(901) }, 'sending'))).toBe('occupied');
    expect(readJournal()).toEqual({ ok: true, records: [journalRecord(intent, 'unknown')] });
  });

  it('the same UUID may be stored again (manual resend) but not re-pointed at another revision', () => {
    persistIntent(journalRecord(intent, 'retryable'));
    expect(persistIntent(journalRecord(intent, 'sending'))).toBe('ok');
    expect(persistIntent(journalRecord({ ...intent, revision: rev(2) }, 'sending'))).toBe('occupied');
  });

  it('storage that throws or does not keep the value reports unavailable', () => {
    const store = new Map<string, string>();
    withSessionStorage({
      getItem: (k: string) => store.get(k) ?? null,
      setItem: () => { throw new DOMException('quota', 'QuotaExceededError'); },
      removeItem: (k: string) => { store.delete(k); },
    }, () => expect(persistIntent(journalRecord(intent, 'sending'))).toBe('unavailable'));
    withSessionStorage({
      getItem: () => null,
      setItem: () => {},
      removeItem: () => {},
    }, () => expect(persistIntent(journalRecord(intent, 'sending'))).toBe('unavailable'));
    withSessionStorage(null, () => expect(readJournal()).toEqual({ ok: false }));
  });

  it('a corrupt journal blocks new intents and is not overwritten', () => {
    window.sessionStorage.setItem(KEY, '{not json');
    expect(readJournal()).toEqual({ ok: false });
    expect(persistIntent(journalRecord(intent, 'sending'))).toBe('unavailable');
    expect(window.sessionStorage.getItem(KEY)).toBe('{not json');
    window.sessionStorage.setItem(KEY, JSON.stringify([{ ...journalRecord(intent, 'sending'), token: undefined, requestId: 'r1' }]));
    expect(readJournal()).toEqual({ ok: false });
  });
});

describe('scopes', () => {
  it('another account, server, actor, slot or generation does not see the record', () => {
    persistIntent(journalRecord(intent, 'unknown'));
    const stored = records();
    expect(findIntent(stored, scope)?.requestId).toBe(rev(900));
    expect(findIntent(stored, { ...scope, accountId: 2 })).toBeNull();
    expect(findIntent(stored, { ...scope, server: 'che' })).toBeNull();
    expect(findIntent(stored, { ...scope, actor: 8 })).toBeNull();
    expect(findIntent(stored, { ...scope, turnIdx: 1 })).toBeNull();
    expect(findIntent(stored, { ...scope, generation: 'g-1' })).toBeNull();
  });

  it('an older account keeps its record when another account cancels the same slot', () => {
    persistIntent(journalRecord(intent, 'unknown'));
    expect(persistIntent(journalRecord({ ...intent, accountId: 2, requestId: rev(902) }, 'sending'))).toBe('ok');
    expect(records()).toHaveLength(2);
  });
});

describe('update and end', () => {
  it('updates only its own account + UUID and does not recreate a missing record', () => {
    persistIntent(journalRecord(intent, 'sending'));
    expect(updateIntent(2, rev(900), 'confirmed')).toBe(false);
    expect(updateIntent(1, rev(999), 'confirmed')).toBe(false);
    expect(updateIntent(1, rev(900), 'confirmed')).toBe(true);
    expect(findIntent(records(), scope)?.cancelState).toBe('confirmed');
    expect(removeIntent(1, rev(900))).toBe(true);
    expect(updateIntent(1, rev(900), 'unknown')).toBe(false);
    expect(readJournal()).toEqual({ ok: true, records: [] });
  });

  it('a weaker, later outcome never downgrades or erases a stronger record for the same UUID', () => {
    persistIntent(journalRecord(intent, 'sending'));
    expect(updateIntent(1, rev(900), 'confirmed')).toBe(true);
    expect(updateIntent(1, rev(900), 'unknown')).toBe(true);
    expect(updateIntent(1, rev(900), 'retryable')).toBe(true);
    expect(records()).toEqual([journalRecord(intent, 'confirmed')]);
    expect(removeUnsettledIntent(1, rev(900))).toBe(false);
    expect(persistIntent(journalRecord(intent, 'unknown'))).toBe('settled');
    expect(records()).toEqual([journalRecord(intent, 'confirmed')]);
    removeIntent(1, rev(900));
    persistIntent(journalRecord(intent, 'unknown'));
    expect(updateIntent(1, rev(900), 'retryable')).toBe(true);
    expect(persistIntent(journalRecord(intent, 'sending'))).toBe('ok');
    expect(removeUnsettledIntent(1, rev(900))).toBe(false);
    expect(records()).toEqual([journalRecord(intent, 'unknown')]);
  });

  it('a clean non-admission still ends an intent with no ambiguity or receipt recorded', () => {
    persistIntent(journalRecord(intent, 'sending'));
    expect(removeUnsettledIntent(2, rev(900))).toBe(true);
    expect(records()).toHaveLength(1);
    expect(removeUnsettledIntent(1, rev(900))).toBe(true);
    expect(records()).toEqual([]);
  });

  it('a change wakes subscribers with the UUID only', () => {
    const heard = vi.fn();
    const stop = subscribeJournal(heard);
    persistIntent(journalRecord(intent, 'sending'));
    updateIntent(1, rev(900), 'unknown');
    stop();
    updateIntent(1, rev(900), 'confirmed');
    expect(heard.mock.calls).toEqual([[rev(900)], [rev(900)]]);
  });
});
