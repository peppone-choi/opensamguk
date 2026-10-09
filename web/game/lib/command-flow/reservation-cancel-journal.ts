// Same-tab intent journal in sessionStorage; it does not continue in another tab or after closing the tab.
//
// Keep only account, server, optional generation, actor, slot, original revision, intent UUID and state; no credentials or command arguments.
// Verify persistence before dispatch. Unavailable or corrupt storage blocks a new cancellation rather than replacing its UUID.
// Removing a record proves neither success nor failure. Records have no arbitrary expiry.
import { isUuid } from '../api/reservation-cancel-contract';

const KEY = 'opensamguk.reservationCancel';
const SCHEMA = 1;

/** sending = dispatch may have left · unknown = ambiguous · retryable = WORLD_EXECUTING, not admitted · confirmed = receipt seen. */
export type JournalState = 'sending' | 'unknown' | 'retryable' | 'confirmed';
const STATES: readonly JournalState[] = ['sending', 'unknown', 'retryable', 'confirmed'];

export interface JournalRecord {
  readonly schemaVersion: typeof SCHEMA;
  readonly accountId: number;
  readonly server: string;
  /** Only when the server provided one; an absent generation is never guessed. */
  readonly generation?: string;
  readonly actor: number;
  readonly turnIdx: number;
  readonly revision: string;
  readonly requestId: string;
  readonly cancelState: JournalState;
}

export interface JournalScope {
  readonly accountId: number;
  readonly server: string;
  readonly generation: string | null;
  readonly actor: number;
  readonly turnIdx: number;
}

export type JournalRead = { readonly ok: true; readonly records: readonly JournalRecord[] } | { readonly ok: false };

const positive = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) > 0;

function validRecord(value: unknown): value is JournalRecord {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  const r = value as Record<string, unknown>;
  return r.schemaVersion === SCHEMA && positive(r.accountId) && typeof r.server === 'string' && r.server !== ''
    && (r.generation === undefined || (typeof r.generation === 'string' && r.generation !== ''))
    && positive(r.actor) && Number.isInteger(r.turnIdx) && Number(r.turnIdx) >= 0 && Number(r.turnIdx) < 12
    && isUuid(r.revision) && isUuid(r.requestId) && STATES.includes(r.cancelState as JournalState);
}

function storage(): Storage | null {
  try {
    return typeof window === 'undefined' ? null : window.sessionStorage;
  } catch {
    return null;
  }
}

export function readJournal(): JournalRead {
  const store = storage();
  if (!store) return { ok: false };
  try {
    const raw = store.getItem(KEY);
    if (raw == null) return { ok: true, records: [] };
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed) || !parsed.every(validRecord)) return { ok: false };
    const ids = parsed.map(r => r.requestId);
    return new Set(ids).size === ids.length ? { ok: true, records: parsed } : { ok: false };
  } catch {
    return { ok: false };
  }
}

/** Writes and reads back; false when the browser did not keep exactly this. */
function write(records: readonly JournalRecord[]): boolean {
  const store = storage();
  if (!store) return false;
  try {
    if (records.length === 0) {
      store.removeItem(KEY);
      return store.getItem(KEY) == null;
    }
    const text = JSON.stringify(records);
    store.setItem(KEY, text);
    return store.getItem(KEY) === text;
  } catch {
    return false;
  }
}

const listeners = new Set<(requestId: string) => void>();

/** A journal change for one intent UUID — a wake-up signal only, never a receipt. */
export function subscribeJournal(listener: (requestId: string) => void): () => void {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

function notify(requestId: string) {
  for (const listener of [...listeners]) listener(requestId);
}

const sameScope = (r: JournalRecord, s: JournalScope) => r.accountId === s.accountId && r.server === s.server
  && (r.generation ?? null) === s.generation && r.actor === s.actor && r.turnIdx === s.turnIdx;
const scopeOf = (r: JournalRecord): JournalScope => ({
  accountId: r.accountId, server: r.server, generation: r.generation ?? null, actor: r.actor, turnIdx: r.turnIdx,
});

export function findIntent(records: readonly JournalRecord[], scope: JournalScope): JournalRecord | null {
  return records.find(r => sameScope(r, scope)) ?? null;
}

export function journalRecord(intent: JournalScope & { readonly revision: string; readonly requestId: string }, cancelState: JournalState): JournalRecord {
  return {
    schemaVersion: SCHEMA, accountId: intent.accountId, server: intent.server,
    ...(intent.generation != null ? { generation: intent.generation } : {}),
    actor: intent.actor, turnIdx: intent.turnIdx, revision: intent.revision, requestId: intent.requestId, cancelState,
  };
}

// What the record already knows about this UUID. Different screens (an unmounted old dispatch, a restored new screen)
// write the same record in any order, so a weaker, later outcome never overwrites a stronger one: a receipt stays a
// receipt, an ambiguity stays an ambiguity. sending and retryable both mean "no ambiguity seen yet".
const STRENGTH: Readonly<Record<JournalState, number>> = { sending: 0, retryable: 0, unknown: 1, confirmed: 2 };
const atLeast = (own: JournalState, next: JournalState): JournalState => STRENGTH[next] >= STRENGTH[own] ? next : own;

/**
 * Stores this intent (new or the same UUID again). Refuses a corrupt or unavailable journal and a slot that already holds a
 * different UUID — an older intent is never replaced. The same UUID keeps its stronger state; `settled` means a receipt is
 * already recorded, so nothing may be sent again for it.
 */
export function persistIntent(next: JournalRecord): 'ok' | 'unavailable' | 'occupied' | 'settled' {
  const read = readJournal();
  if (!read.ok) return 'unavailable';
  const scope = scopeOf(next);
  const other = read.records.find(r => r.requestId !== next.requestId && sameScope(r, scope));
  if (other) return 'occupied';
  const own = read.records.find(r => r.requestId === next.requestId);
  if (own && (own.revision !== next.revision || !sameScope(own, scope))) return 'occupied';
  if (own?.cancelState === 'confirmed') return 'settled';
  const stored = own ? { ...next, cancelState: atLeast(own.cancelState, next.cancelState) } : next;
  const records = own ? read.records.map(r => r === own ? stored : r) : [...read.records, stored];
  if (!write(records)) return 'unavailable';
  notify(next.requestId);
  return 'ok';
}

/**
 * Updates only this account's record for this UUID; a missing record is not recreated. A weaker state than the stored one
 * is ignored (true: the record is already at least as strong).
 */
export function updateIntent(accountId: number, requestId: string, cancelState: JournalState): boolean {
  const read = readJournal();
  if (!read.ok) return false;
  const own = read.records.find(r => r.requestId === requestId && r.accountId === accountId);
  if (!own) return false;
  const stored = atLeast(own.cancelState, cancelState);
  if (own.cancelState === stored) return true;
  const ok = write(read.records.map(r => r === own ? { ...own, cancelState: stored } : r));
  if (ok) notify(requestId);
  return ok;
}

/** Ends this account's intent for this UUID (confirmed receipt read back, or a clear final non-admission). */
export function removeIntent(accountId: number, requestId: string): boolean {
  const read = readJournal();
  if (!read.ok) return false;
  if (!read.records.some(r => r.requestId === requestId && r.accountId === accountId)) return true;
  return write(read.records.filter(r => !(r.requestId === requestId && r.accountId === accountId)));
}

/**
 * Ends this intent after a clear non-admission only while nothing stronger is recorded. A receipt or an ambiguity
 * recorded meanwhile (possibly by another screen) is kept; false then means the record stays.
 */
export function removeUnsettledIntent(accountId: number, requestId: string): boolean {
  const read = readJournal();
  if (!read.ok) return false;
  const own = read.records.find(r => r.requestId === requestId && r.accountId === accountId);
  if (!own) return true;
  if (STRENGTH[own.cancelState] > 0) return false;
  return write(read.records.filter(r => r !== own));
}
