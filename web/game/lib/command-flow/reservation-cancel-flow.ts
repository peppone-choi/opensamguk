// Cancellation flow: first cancellation, manual resend, result checking, current readback and same-tab restoration.
//
// The hook supplies the live epoch, current state and commit operation. Late answers update only their own intent journal.
// They never change the current selection or draft, and this flow never automatically resends a DELETE.
import type { RefreshGuard } from '../api';
import { readCancelResultHttp, readReservedRing, sendReservationCancel } from '../api/reservation-cancel';
import { selectedTabServer } from '../serverGameUrl';
import {
  findIntent, journalRecord, persistIntent, readJournal, removeIntent, removeUnsettledIntent, updateIntent, type JournalScope,
} from './reservation-cancel-journal';
import {
  afterOutcome, afterReadback, afterResult, classifyCancel, classifyResult, freezeIntent, intentOf, journalAfter, preflightMatches,
  restoredPhase, startChecking, RELOAD_CODES, type CancelIntent, type CancelPhase, type CancelTarget,
} from './reservation-cancel-state';

export interface CancelRun {
  /** The epoch that started this work is still the shown, mounted one. */
  readonly alive: () => boolean;
  readonly current: () => CancelPhase;
  /** Applied only while alive. */
  readonly commit: (next: CancelPhase) => void;
  /** Asks every mounted 12-slot reader to read again — a signal, not a receipt. */
  readonly listChanged: () => void;
  /** Newest request token per GET kind; an older GET may still land a receipt but nothing weaker. */
  readonly tokens: { result: number; readback: number };
}

// Never reused, so a token from an earlier scope cannot match a later one.
let lastToken = 0;
const nextToken = () => ++lastToken;

/** A 401 is sent again once only for the same account, the live epoch and the original server. */
export function refreshGuard(run: CancelRun, intent: { readonly accountId: number; readonly server: string }): RefreshGuard {
  return (user) => !!user && typeof user === 'object' && (user as { id?: unknown }).id === intent.accountId
    && run.alive() && selectedTabServer() === intent.server;
}

const blocked = (turnIdx: number, code: string): CancelPhase => ({ kind: 'blocked', turnIdx, code });
const readCode = (status: number | null) => status === 401 ? 'AUTH_REQUIRED' : status === 403 ? 'FORBIDDEN' : 'READ_FAILED';

async function dispatch(run: CancelRun, intent: CancelIntent, ambiguousBefore: boolean): Promise<void> {
  const http = await sendReservationCancel(intent, intent.server, refreshGuard(run, intent));
  const outcome = classifyCancel(http, intent);
  // The journal belongs to this UUID, whoever is on screen now. Another screen may have recorded a receipt or an
  // ambiguity for it meanwhile; this (possibly late) answer never weakens or erases that.
  const state = journalAfter(outcome, ambiguousBefore);
  if (state) updateIntent(intent.accountId, intent.requestId, state);
  else removeUnsettledIntent(intent.accountId, intent.requestId);
  const reload = outcome.kind === 'receipt' || (outcome.kind === 'rejected' && RELOAD_CODES.has(outcome.code));
  if (!run.alive()) {
    if (reload) run.listChanged();
    return;
  }
  const before = run.current();
  const next = afterOutcome(before, intent, outcome, ambiguousBefore);
  run.commit(next);
  if (outcome.kind === 'rejected' && reload) run.listChanged();
  if (next.kind === 'refreshing' && before.kind !== 'refreshing') void runReadback(run, intent);
}

/** First cancellation: fresh read of the frozen row → one UUID → journal saved → DELETE. A changed row sends nothing. */
export async function runFirstCancel(run: CancelRun, target: CancelTarget): Promise<void> {
  const read = await readReservedRing(target.actor, target.server, refreshGuard(run, target));
  if (!run.alive()) return;
  if (read.kind === 'error') { run.commit(blocked(target.turnIdx, readCode(read.status))); return; }
  if (!preflightMatches(target, read.ring)) {
    run.commit(blocked(target.turnIdx, 'CHANGED'));
    run.listChanged();
    return;
  }
  const uuid = typeof globalThis.crypto?.randomUUID === 'function' ? globalThis.crypto.randomUUID() : null;
  if (!uuid) { run.commit(blocked(target.turnIdx, 'NO_UUID')); return; }
  const intent = freezeIntent(target, uuid);
  const saved = persistIntent(journalRecord(intent, 'sending'));
  if (saved !== 'ok') { run.commit(blocked(target.turnIdx, saved === 'occupied' ? 'OCCUPIED' : 'STORAGE')); return; }
  if (!run.alive()) { removeIntent(intent.accountId, intent.requestId); return; }
  run.commit({ kind: 'sending', intent });
  await dispatch(run, intent, false);
}

/** Manual retry of the ORIGINAL intent (same UUID · slot · revision), never upgraded to a replacement. */
export async function runResend(run: CancelRun): Promise<void> {
  const phase = run.current();
  if ((phase.kind !== 'unknown' && phase.kind !== 'retryable') || phase.checking) return;
  const { intent } = phase;
  const ambiguousBefore = phase.kind === 'unknown';
  // Saved as possibly-sent before it leaves; an ambiguous record stays ambiguous. A receipt already recorded (by any
  // screen) is never sent again — its result is read instead.
  const saved = persistIntent(journalRecord(intent, ambiguousBefore ? 'unknown' : 'sending'));
  if (saved === 'settled') {
    await runCheckResult(run, intent);
    return;
  }
  if (saved !== 'ok') {
    run.commit({ kind: 'unknown', intent, note: 'STORAGE', checking: false });
    return;
  }
  run.commit({ kind: 'sending', intent });
  await dispatch(run, intent, ambiguousBefore);
}

/** The submitter's durable result for this UUID. PENDING or errors keep the UUID and say nothing about failure. */
export async function runCheckResult(run: CancelRun, intent: CancelIntent): Promise<void> {
  const token = nextToken();
  run.tokens.result = token;
  run.commit(startChecking(run.current()));
  const http = await readCancelResultHttp(intent, intent.server, refreshGuard(run, intent));
  const read = classifyResult(http, intent);
  if (!run.alive()) {
    if (read === 'receipt') {
      updateIntent(intent.accountId, intent.requestId, 'confirmed');
      run.listChanged();
    }
    return;
  }
  // A newer check owns the busy flag; only a receipt (which nothing outranks) still lands.
  if (run.tokens.result !== token && read !== 'receipt') return;
  const before = run.current();
  const next = afterResult(before, intent, read);
  // Shown before it is recorded: the journal notification then finds this screen past unknown/retryable, so its own
  // receipt wakes no second GET. A receipt recorded by another screen still wakes this one (wakeOnJournal).
  run.commit(next);
  if (read === 'receipt') updateIntent(intent.accountId, intent.requestId, 'confirmed');
  if (next.kind === 'refreshing' && before.kind !== 'refreshing') void runReadback(run, intent);
}

/** A NEW current read after a receipt: slotEmpty was true only at commit, so a replacement may be there now. */
export async function runReadback(run: CancelRun, intent: CancelIntent): Promise<void> {
  const token = nextToken();
  run.tokens.readback = token;
  if (run.current().kind === 'readbackError') run.commit({ kind: 'refreshing', intent });
  const read = await readReservedRing(intent.actor, intent.server, refreshGuard(run, intent));
  if (!run.alive() || run.tokens.readback !== token) return;
  const next = afterReadback(run.current(), intent, read.kind === 'ok' ? read.ring : null);
  run.commit(next);
  if (next.kind === 'done') {
    removeIntent(intent.accountId, intent.requestId);
    run.listChanged();
  }
}

/** Same-tab return: shows the stored intent and reads its result (and, after a receipt, the list). Never sends a DELETE. */
export function restoreIntent(run: CancelRun, scope: JournalScope): 'none' | 'broken' | 'restored' {
  const journal = readJournal();
  if (!journal.ok) return 'broken';
  const record = findIntent(journal.records, scope);
  if (!record) return 'none';
  const phase = restoredPhase(record);
  const intent = intentOf(phase);
  if (!intent) return 'none';
  run.commit(phase);
  if (phase.kind === 'refreshing') void runReadback(run, intent);
  else void runCheckResult(run, intent);
  return 'restored';
}

/**
 * A late receipt for the shown UUID (recorded by an older screen) wakes this screen's own result GET. A check already
 * in flight may have been answered before that commit, so a newer one supersedes it (tokens).
 */
export function wakeOnJournal(run: CancelRun, requestId: string): void {
  const phase = run.current();
  if ((phase.kind !== 'unknown' && phase.kind !== 'retryable') || phase.intent.requestId !== requestId) return;
  const journal = readJournal();
  const own = journal.ok ? journal.records.find(r => r.requestId === requestId) : undefined;
  if (own?.cancelState === 'confirmed') void runCheckResult(run, phase.intent);
}
