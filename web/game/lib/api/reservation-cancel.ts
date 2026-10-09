// Single reservation cancellation transport — reservation read · bodyless DELETE · result recovery GET
// (docs/api/reservation-cancellation.md).
//
// Every request names the server of the tab that froze the intent (`server=`), so a changed cookie or a
// re-authentication never reaches another world. The API process pins the world, so no worldId is invented.
// A resend after 401 happens at most once and only when the caller's guard allows it.
import { fetchGame, type RefreshGuard } from '../api';
import type { ReservedCommandsResponse } from '../types';
import { parseReservedCommands, type CancelHttp, type CancelWire } from './reservation-cancel-contract';

const withServer = (path: string, server: string) => `${path}${path.includes('?') ? '&' : '?'}server=${encodeURIComponent(server)}`;

async function bodyOf(response: Response): Promise<unknown> {
  try {
    const text = await response.text();
    return text ? JSON.parse(text) as unknown : undefined;
  } catch {
    return undefined;
  }
}

/** Upper bound for one cancellation exchange (including a 401 refresh and its single resend). */
export const CANCEL_EXCHANGE_TIMEOUT_MS = 20_000;

// A finite wait: past the deadline the request is aborted and reported as `network`, which after a
// dispatched DELETE means UNKNOWN (the intent UUID is kept). The timeout is never a rollback. A late
// `/api/auth/me` refresh after the abort must not resend, so the caller's guard is gated on liveness.
// The deadline races the whole exchange: a step that ignores the signal (the refresh) cannot hold it open.
async function exchange(path: string, init: RequestInit, guard: RefreshGuard): Promise<CancelHttp> {
  const controller = new AbortController();
  const live: RefreshGuard = user => !controller.signal.aborted && guard(user);
  const attempt = (async (): Promise<CancelHttp> => {
    const response = await fetchGame(path, { ...init, signal: controller.signal }, live);
    const body = await bodyOf(response);
    if (controller.signal.aborted) return { kind: 'network' };
    return { kind: 'response', status: response.status, body };
  })().catch((): CancelHttp => ({ kind: 'network' }));
  let timer: ReturnType<typeof setTimeout> | undefined;
  const deadline = new Promise<CancelHttp>(resolve => {
    timer = setTimeout(() => { controller.abort(); resolve({ kind: 'network' }); }, CANCEL_EXCHANGE_TIMEOUT_MS);
  });
  try {
    return await Promise.race([attempt, deadline]);
  } finally {
    clearTimeout(timer);
  }
}

/** A strict reservation read, or why it could not be read. A read error is never an empty ring. */
export type ReservedRead =
  | { readonly kind: 'ok'; readonly ring: ReservedCommandsResponse }
  | { readonly kind: 'error'; readonly status: number | null };

export async function readReservedRing(actor: number, server: string, guard: RefreshGuard): Promise<ReservedRead> {
  const http = await exchange(withServer(`/api/reserved-commands?generalId=${actor}`, server), { cache: 'no-store' }, guard);
  if (http.kind === 'network') return { kind: 'error', status: null };
  if (http.status !== 200) return { kind: 'error', status: http.status };
  try {
    return { kind: 'ok', ring: parseReservedCommands(http.body, actor) };
  } catch {
    return { kind: 'error', status: http.status };
  }
}

/** `DELETE` with the original slot · revision and the intent UUID as `Idempotency-Key`. No body, no Content-Type. */
export function sendReservationCancel(wire: CancelWire, server: string, guard: RefreshGuard): Promise<CancelHttp> {
  const path = `/api/reserved-commands?generalId=${wire.actor}&turnIdx=${wire.turnIdx}&revision=${encodeURIComponent(wire.revision)}`;
  return exchange(withServer(path, server), {
    method: 'DELETE', cache: 'no-store', headers: { 'Idempotency-Key': wire.requestId },
  }, guard);
}

/** The submitter's durable result for this intent UUID (separate parser from the direct DELETE). */
export function readCancelResultHttp(wire: CancelWire, server: string, guard: RefreshGuard): Promise<CancelHttp> {
  return exchange(withServer(`/api/command/result/${encodeURIComponent(wire.requestId)}`, server), { cache: 'no-store' }, guard);
}
