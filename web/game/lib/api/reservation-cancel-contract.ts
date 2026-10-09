// Cancellation and reservation wire parsers (docs/api/reservation-cancellation.md); no transport.
//
// Separate parsers handle display reads, strict cancellation reads, direct DELETE receipts, result recovery GETs and rejection envelopes.
// Recovery GETs do not require the direct response fields accepted or receiptRecorded.
import type { ReservedCommandsResponse, ReservedSlot } from '../types';

const RING = 12;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const record = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value);

export const isUuid = (value: unknown): value is string => typeof value === 'string' && UUID.test(value);

/** A reservation read that is malformed, foreign or incomplete — never an empty ring. */
export class ReservedReadError extends Error {
  constructor() {
    super('12순을 확인하지 못했습니다');
    this.name = 'ReservedReadError';
  }
}

/** A row for display only. `revision` is null when a pre-B1 reader sent none; such a row can never be cancelled. */
export type ReservedDisplaySlot = Omit<ReservedSlot, 'revision'> & { revision: string | null };
export type ReservedDisplayResponse = Omit<ReservedCommandsResponse, 'slots'> & { slots: ReservedDisplaySlot[] };

function reservedRow(value: unknown): ReservedDisplaySlot | null {
  if (!record(value) || !Number.isInteger(value.turnIdx)) return null;
  const turnIdx = value.turnIdx as number;
  if (turnIdx < 0 || turnIdx >= RING || typeof value.action !== 'string' || !value.action.trim()
    || typeof value.brief !== 'string' || !record(value.arg)) return null;
  // An absent revision is null; a revision that is present must be a UUID.
  const revision = isUuid(value.revision) ? value.revision : !('revision' in value) ? null : undefined;
  if (revision === undefined) return null;
  return { turnIdx, action: value.action, brief: value.brief, arg: { ...value.arg }, revision };
}

/**
 * Display read of `GET /api/reserved-commands?generalId=<actor>` (12-slot strip): same checks as the strict read — `result === true`,
 * the requested actor, unique complete 0–11 rows, a supplied revision must be a UUID — except that a row without a revision
 * (pre-B1 reader) is kept read-only with `revision: null`. Never used for a cancellation preflight or readback.
 */
export function parseReservedDisplay(data: unknown, actor: number): ReservedDisplayResponse {
  if (!Number.isSafeInteger(actor) || actor <= 0 || !record(data) || data.result !== true || data.generalId !== actor
    || !Array.isArray(data.slots) || data.slots.length > RING) throw new ReservedReadError();
  const seen = new Set<number>();
  const slots = data.slots.map((value: unknown) => {
    const slot = reservedRow(value);
    if (!slot || seen.has(slot.turnIdx)) throw new ReservedReadError();
    seen.add(slot.turnIdx);
    return slot;
  });
  return { ...(data as unknown as ReservedDisplayResponse), result: true, generalId: actor, slots };
}

/**
 * Strict B1 read used by cancellation: the display read plus a UUID revision on every row. Anything else throws.
 * Calendar metadata and other fields are kept as sent.
 */
export function parseReservedCommands(data: unknown, actor: number): ReservedCommandsResponse {
  const read = parseReservedDisplay(data, actor);
  const slots = read.slots.map(({ revision, ...slot }) => {
    if (revision == null) throw new ReservedReadError();
    return { ...slot, revision };
  });
  return { ...read, slots };
}

/** What every cancellation response must echo — the frozen intent it was sent with. */
export interface CancelWire {
  readonly actor: number;
  readonly turnIdx: number;
  /** The ORIGINAL revision read before confirmation; never upgraded to a replacement. */
  readonly revision: string;
  /** The cancellation intent UUID (Idempotency-Key). */
  readonly requestId: string;
}

/** One HTTP exchange as seen by the client. `body` is undefined when it was empty or not JSON. */
export type CancelHttp =
  | { readonly kind: 'response'; readonly status: number; readonly body: unknown }
  | { readonly kind: 'network' };

/** Inner cancellation lifecycle. Kind · action code are mandatory on the direct DELETE and checked when present on recovery. */
function cancelledResult(value: unknown, wire: CancelWire, direct: boolean): boolean {
  if (!record(value)) return false;
  const kindOk = direct ? value.commandKind === 'QUEUE_MUTATION' : value.commandKind == null || value.commandKind === 'QUEUE_MUTATION';
  const codeOk = direct ? value.actionCode === 'cancelReservedTurn' : value.actionCode == null || value.actionCode === 'cancelReservedTurn';
  return kindOk && codeOk && value.type === 'reservationCancelled' && value.ok === true && value.generalId === wire.actor
    && value.turnIdx === wire.turnIdx && value.reservationRevision === wire.revision && value.slotEmpty === true;
}

/**
 * Direct `DELETE` success. Every field of the frozen intent must match; HTTP 200, `accepted` or `slotEmpty` alone prove nothing.
 * Other nullable lifecycle fields are not extra requirements.
 */
export function isCancelReceipt(data: unknown, wire: CancelWire): boolean {
  return record(data) && data.requestId === wire.requestId && data.status === 'RESOLVED' && data.type === 'reservationCancelled'
    && data.ok === true && data.accepted === true && data.receiptRecorded === true
    && typeof data.committedWorldVersion === 'number' && Number.isFinite(data.committedWorldVersion)
    && cancelledResult(data.result, wire, true);
}

/** Recovery read of `GET /api/command/result/<requestId>`. PENDING never means failure, refusal or completion. */
export type CancelResultRead = 'receipt' | 'pending' | 'unknown';

export function readCancelResult(data: unknown, wire: CancelWire): CancelResultRead {
  if (!record(data) || data.requestId !== wire.requestId) return 'unknown';
  if (data.status === 'PENDING') return 'pending';
  if (data.status === 'RESOLVED' && data.ok === true && data.type === 'reservationCancelled'
    && cancelledResult(data.result, wire, false)) return 'receipt';
  return 'unknown';
}

/** Controller refusal (`BLOCKED`, nothing recorded) or auth/admission filter envelope. Missing fields are not invented. */
export type CancelRejection =
  | { readonly kind: 'blocked'; readonly code: string; readonly retryable: boolean }
  | { readonly kind: 'filter'; readonly code: string };

export function readCancelRejection(data: unknown): CancelRejection | null {
  if (!record(data)) return null;
  if (data.status === 'BLOCKED' && typeof data.code === 'string' && data.code !== '' && data.accepted === false
    && data.receiptRecorded === false && typeof data.retryable === 'boolean') {
    return { kind: 'blocked', code: data.code, retryable: data.retryable };
  }
  const error = data.error;
  if (record(error) && typeof error.code === 'string' && error.code !== '' && typeof error.message === 'string'
    && !('accepted' in data) && !('receiptRecorded' in data)) {
    return { kind: 'filter', code: error.code };
  }
  return null;
}
