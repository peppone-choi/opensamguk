// Pure cancellation state machine: frozen target, response classification and state precedence; no React or transport.
//
// Freeze account, server, generation, actor, slot, revision and displayed sentence when confirmation starts.
// Once a receipt arrives, pending or failed late answers cannot downgrade it.
// A later nonadmission cannot erase an earlier dispatched request with an unknown outcome.
import { readCancelRejection, readCancelResult, isCancelReceipt, type CancelHttp, type CancelResultRead, type CancelWire } from '../api/reservation-cancel-contract';
import type { ReservedCommandsResponse } from '../types';
import type { JournalRecord, JournalState } from './reservation-cancel-journal';

export interface CancelTarget {
  readonly accountId: number;
  readonly server: string;
  readonly generation: string | null;
  readonly actor: number;
  readonly turnIdx: number;
  readonly revision: string;
  /** The stored command sentence shown when the target was frozen. */
  readonly sentence: string;
}

export interface CancelIntent extends CancelWire {
  readonly accountId: number;
  readonly server: string;
  readonly generation: string | null;
}

export type CancelPhase =
  | { readonly kind: 'idle' }
  | { readonly kind: 'confirming'; readonly target: CancelTarget }
  | { readonly kind: 'preflight'; readonly target: CancelTarget }
  | { readonly kind: 'sending'; readonly intent: CancelIntent }
  | { readonly kind: 'retryable'; readonly intent: CancelIntent; readonly checking: boolean }
  /** note = code of a later non-admitted call; it does not erase the earlier ambiguity. */
  | { readonly kind: 'unknown'; readonly intent: CancelIntent; readonly note: string | null; readonly checking: boolean }
  | { readonly kind: 'refreshing'; readonly intent: CancelIntent }
  | { readonly kind: 'readbackError'; readonly intent: CancelIntent }
  | { readonly kind: 'done'; readonly turnIdx: number; readonly after: 'empty' | 'replaced' }
  | { readonly kind: 'blocked'; readonly turnIdx: number; readonly code: string };

export const IDLE: CancelPhase = { kind: 'idle' };

/** Receipt-backed phases outrank everything an older or slower response can say. */
const RANK: Record<CancelPhase['kind'], number> = {
  idle: 0, confirming: 0, preflight: 0, blocked: 0, sending: 1, retryable: 1, unknown: 1, refreshing: 2, readbackError: 2, done: 3,
};

export type CancelOutcome =
  | { readonly kind: 'receipt' }
  | { readonly kind: 'worldExecuting' }
  | { readonly kind: 'rejected'; readonly code: string }
  | { readonly kind: 'unknown'; readonly code: string | null };

// Clear non-admissions and the status each one is defined with. Anything else stays UNKNOWN.
const STABLE_BLOCKED: Readonly<Record<string, number>> = {
  REVISION_MISMATCH: 409, IDEMPOTENCY_CONFLICT: 409, NOT_OWNER: 403, UNAUTHORIZED: 401,
  INVALID_SLOT: 400, INVALID_REVISION: 400, INVALID_IDEMPOTENCY_KEY: 400, INVALID_ARGUMENT: 400,
};
const STABLE_FILTER: Readonly<Record<string, number>> = { AUTH_REQUIRED: 401, SERVER_NOT_PUBLIC: 403 };

/** Codes whose refusal means the current list should be read again. */
export const RELOAD_CODES: ReadonlySet<string> = new Set(['REVISION_MISMATCH', 'IDEMPOTENCY_CONFLICT', 'CHANGED']);

/** Classifies a dispatched DELETE. Malformed 2xx, non-JSON, proxy text, 5xx and network loss are UNKNOWN. */
export function classifyCancel(http: CancelHttp, wire: CancelWire): CancelOutcome {
  if (http.kind === 'network') return { kind: 'unknown', code: null };
  const { status, body } = http;
  if (status >= 200 && status < 300) return isCancelReceipt(body, wire) ? { kind: 'receipt' } : { kind: 'unknown', code: null };
  const rejection = readCancelRejection(body);
  if (rejection?.kind === 'blocked') {
    if (rejection.code === 'WORLD_EXECUTING' && status === 409 && rejection.retryable) return { kind: 'worldExecuting' };
    if (STABLE_BLOCKED[rejection.code] === status) return { kind: 'rejected', code: rejection.code };
    return { kind: 'unknown', code: rejection.code };
  }
  if (rejection?.kind === 'filter') {
    if (STABLE_FILTER[rejection.code] === status) return { kind: 'rejected', code: rejection.code };
    return { kind: 'unknown', code: rejection.code };
  }
  if (body === undefined && status === 401) return { kind: 'rejected', code: 'AUTH_REQUIRED' };
  if (body === undefined && status === 403) return { kind: 'rejected', code: 'FORBIDDEN' };
  return { kind: 'unknown', code: null };
}

/** Classifies a recovery result GET with its own parser. */
export function classifyResult(http: CancelHttp, wire: CancelWire): CancelResultRead {
  return http.kind === 'response' && http.status === 200 ? readCancelResult(http.body, wire) : 'unknown';
}

/** Journal state after a dispatch; null ends the intent. Earlier ambiguity survives a later non-admission. */
export function journalAfter(outcome: CancelOutcome, ambiguousBefore: boolean): JournalState | null {
  switch (outcome.kind) {
    case 'receipt': return 'confirmed';
    case 'worldExecuting': return ambiguousBefore ? 'unknown' : 'retryable';
    case 'rejected': return ambiguousBefore ? 'unknown' : null;
    case 'unknown': return 'unknown';
  }
}

export function intentOf(phase: CancelPhase): CancelIntent | null {
  return 'intent' in phase ? phase.intent : null;
}

const owns = (phase: CancelPhase, requestId: string) => intentOf(phase)?.requestId === requestId;
const unknown = (intent: CancelIntent, note: string | null): CancelPhase => ({ kind: 'unknown', intent, note, checking: false });

export function afterOutcome(phase: CancelPhase, intent: CancelIntent, outcome: CancelOutcome, ambiguousBefore: boolean): CancelPhase {
  if (!owns(phase, intent.requestId) || RANK[phase.kind] >= 2) return phase;
  switch (outcome.kind) {
    case 'receipt': return { kind: 'refreshing', intent };
    case 'worldExecuting': return ambiguousBefore ? unknown(intent, 'WORLD_EXECUTING') : { kind: 'retryable', intent, checking: false };
    case 'rejected': return ambiguousBefore ? unknown(intent, outcome.code) : { kind: 'blocked', turnIdx: intent.turnIdx, code: outcome.code };
    // An unknown answer is not a refusal; it carries no later-refusal note.
    case 'unknown': return unknown(intent, null);
  }
}

/** A result GET started for the shown intent (unknown · retryable only). */
export function startChecking(phase: CancelPhase): CancelPhase {
  return phase.kind === 'unknown' || phase.kind === 'retryable' ? { ...phase, checking: true } : phase;
}

export function afterResult(phase: CancelPhase, intent: CancelIntent, read: CancelResultRead): CancelPhase {
  if (!owns(phase, intent.requestId)) return phase;
  if (read === 'receipt') return RANK[phase.kind] >= 2 ? phase : { kind: 'refreshing', intent };
  return phase.kind === 'unknown' || phase.kind === 'retryable' ? { ...phase, checking: false } : phase;
}

/** Fresh list after a receipt. The same revision still in the slot contradicts the receipt and is a read error. */
export function afterReadback(phase: CancelPhase, intent: CancelIntent, ring: ReservedCommandsResponse | null): CancelPhase {
  if (!owns(phase, intent.requestId) || (phase.kind !== 'refreshing' && phase.kind !== 'readbackError')) return phase;
  if (!ring) return { kind: 'readbackError', intent };
  const row = ring.slots.find(slot => slot.turnIdx === intent.turnIdx);
  if (row?.revision === intent.revision) return { kind: 'readbackError', intent };
  return { kind: 'done', turnIdx: intent.turnIdx, after: row ? 'replaced' : 'empty' };
}

/** First-cancel preflight: the fresh read must still hold the frozen revision in the frozen slot. */
export function preflightMatches(target: CancelTarget, ring: ReservedCommandsResponse): boolean {
  return ring.generalId === target.actor && ring.slots.some(slot => slot.turnIdx === target.turnIdx && slot.revision === target.revision);
}

export function freezeIntent(target: CancelTarget, requestId: string): CancelIntent {
  const { accountId, server, generation, actor, turnIdx, revision } = target;
  return { accountId, server, generation, actor, turnIdx, revision, requestId };
}

/** Phase shown when a same-tab journal record is found again. 'sending' may have left, so it is ambiguous. */
export function restoredPhase(record: JournalRecord): CancelPhase {
  const intent: CancelIntent = {
    accountId: record.accountId, server: record.server, generation: record.generation ?? null,
    actor: record.actor, turnIdx: record.turnIdx, revision: record.revision, requestId: record.requestId,
  };
  if (record.cancelState === 'confirmed') return { kind: 'refreshing', intent };
  if (record.cancelState === 'retryable') return { kind: 'retryable', intent, checking: false };
  return unknown(intent, null);
}

/** Phases that hold the slot's send lock or an open confirmation — the flow's close · slot switch wait for them. */
export function phaseBusy(phase: CancelPhase): boolean {
  return phase.kind === 'preflight' || phase.kind === 'sending';
}
