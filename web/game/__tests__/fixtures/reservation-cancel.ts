// Fixture HTTP server for the B1 cancellation contract (docs/api/reservation-cancellation.md).
// No real JWT or PostgreSQL: replay an existing receipt first, preserving replacements, then compare the current revision.
import { vi } from 'vitest';
import type { ReservedSlot } from '@/lib/types';

export const SERVER = 'pep';
export const rev = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
export const farm = (turnIdx: number, revision = rev(turnIdx)): ReservedSlot => ({ turnIdx, action: 'action.farm', brief: '', arg: {}, revision });

export const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { 'Content-Type': 'application/json' },
});

export const blocked = (code: string, status: number, retryable = false) =>
  json({ status: 'BLOCKED', code, accepted: false, receiptRecorded: false, retryable }, status);

export interface Committed { readonly actor: number; readonly turnIdx: number; readonly revision: string }

export function receiptBody(requestId: string, c: Committed): Record<string, unknown> {
  return {
    requestId, status: 'RESOLVED', type: 'reservationCancelled', ok: true, accepted: true, receiptRecorded: true, committedWorldVersion: 42,
    result: {
      type: 'reservationCancelled', ok: true, commandKind: 'QUEUE_MUTATION', actionCode: 'cancelReservedTurn',
      generalId: c.actor, turnIdx: c.turnIdx, reservationRevision: c.revision, slotEmpty: true, reason: null, phase: null,
    },
  };
}

/** Recovery shape: no accepted / receiptRecorded. */
export function resultBody(requestId: string, c: Committed): Record<string, unknown> {
  return {
    status: 'RESOLVED', requestId, ok: true, type: 'reservationCancelled', committedWorldVersion: 42,
    result: { type: 'reservationCancelled', ok: true, generalId: c.actor, turnIdx: c.turnIdx, reservationRevision: c.revision, slotEmpty: true },
  };
}

export interface GameCall {
  readonly method: string;
  readonly url: URL;
  readonly key: string | null;
  readonly body: unknown;
  readonly contentType: string | null;
}

export interface FakeCancelServer {
  slots: ReservedSlot[];
  readonly calls: GameCall[];
  readonly deletes: GameCall[];
  readonly posts: GameCall[];
  readonly resultReads: string[];
  ringReads: number;
  /** `/api/auth/me` user after a 401 refresh. */
  user: unknown;
  /** Committed cancellation receipts by intent UUID. */
  readonly receipts: Map<string, Committed>;
  onDelete: ((call: GameCall) => Response | Promise<Response>) | null;
  onResult: ((requestId: string) => Response | Promise<Response>) | null;
  onRing: (() => Response | Promise<Response>) | null;
  onPost: ((call: GameCall) => Response | Promise<Response>) | null;
  /** Next revision handed to a normal reservation write. */
  nextRevision: number;
}

/** B1 DELETE: an existing receipt for the UUID is replayed first; otherwise the slot must still hold that revision. */
export function commitDelete(server: FakeCancelServer, call: GameCall): Response {
  const actor = Number(call.url.searchParams.get('generalId'));
  const turnIdx = Number(call.url.searchParams.get('turnIdx'));
  const revision = call.url.searchParams.get('revision') ?? '';
  const key = call.key ?? '';
  const prior = server.receipts.get(key);
  if (prior) {
    if (prior.actor !== actor || prior.turnIdx !== turnIdx || prior.revision !== revision) return blocked('IDEMPOTENCY_CONFLICT', 409);
    return json(receiptBody(key, prior));
  }
  const row = server.slots.find(slot => slot.turnIdx === turnIdx);
  if (!row || row.revision !== revision) return blocked('REVISION_MISMATCH', 409);
  server.slots = server.slots.filter(slot => slot !== row);
  const committed = { actor, turnIdx, revision };
  server.receipts.set(key, committed);
  return json(receiptBody(key, committed));
}

export function installCancelServer(initial: ReservedSlot[]): FakeCancelServer {
  const server: FakeCancelServer = {
    slots: initial.map(slot => ({ ...slot, arg: { ...slot.arg } })), calls: [], deletes: [], posts: [], resultReads: [], ringReads: 0,
    user: { id: 1 }, receipts: new Map(), onDelete: null, onResult: null, onRing: null, onPost: null, nextRevision: 500,
  };
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), 'http://localhost');
    if (url.pathname === '/api/auth/me') return json({ user: server.user });
    const method = init?.method ?? 'GET';
    const headers = new Headers(init?.headers);
    const call: GameCall = { method, url, key: headers.get('Idempotency-Key'), body: init?.body, contentType: headers.get('Content-Type') };
    server.calls.push(call);
    if (url.pathname === '/api/game/api/reserved-commands') {
      if (method === 'DELETE') {
        server.deletes.push(call);
        return server.onDelete ? server.onDelete(call) : commitDelete(server, call);
      }
      server.ringReads += 1;
      if (server.onRing) return server.onRing();
      return json({ result: true, generalId: Number(url.searchParams.get('generalId')), slots: server.slots, turnTime: '2026-10-09 22:40:00', turnTerm: 60 });
    }
    const result = /^\/api\/game\/api\/command\/result\/(.+)$/.exec(url.pathname);
    if (result) {
      const id = decodeURIComponent(result[1]);
      server.resultReads.push(id);
      if (server.onResult) return server.onResult(id);
      const committed = server.receipts.get(id);
      if (committed) return json(resultBody(id, committed));
      // Normal reservation writes resolve as accepted (the reserve contract, not a cancellation).
      if (server.posts.some((_, i) => rev(9001 + i) === id)) {
        return json({ status: 'RESOLVED', requestId: id, ok: true, type: 'reservationAccepted', result: { commandKind: 'RESERVED_TURN' } });
      }
      return json({ status: 'PENDING', requestId: id });
    }
    if (url.pathname.startsWith('/api/game/api/command/action.') && method === 'POST') {
      server.posts.push(call);
      if (server.onPost) return server.onPost(call);
      const turnIdx = Number(url.searchParams.get('turnIdx'));
      const inputId = url.pathname.slice('/api/game/api/command/'.length);
      server.slots = [...server.slots.filter(slot => slot.turnIdx !== turnIdx),
        { turnIdx, action: inputId, brief: '', arg: JSON.parse(String(init?.body ?? '{}')) as Record<string, unknown>, revision: rev(server.nextRevision++) }];
      return json({ status: 'AVAILABLE', requestId: rev(9000 + server.posts.length), turnIdx }, 202);
    }
    if (url.pathname.endsWith('/farm-options')) return json({ inputId: 'action.farm', available: true, countyName: '허현' });
    return json({}, 404);
  }));
  return server;
}

/** A promise the test resolves by hand (a held response). */
export function deferred<T>() {
  let resolve: (value: T) => void = () => {};
  const promise = new Promise<T>(r => { resolve = r; });
  return { promise, resolve };
}

/** Puts this tab on `/game/<server>` so the cancellation addresses that world explicitly. */
export function onServerPath(server = SERVER) {
  window.history.replaceState(null, '', `/game/${server}`);
}

/** Runs `fn` with `window.sessionStorage` replaced (null = access throws, like a blocked storage). */
export function withSessionStorage<T>(stub: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'> | null, fn: () => T): T {
  const original = Object.getOwnPropertyDescriptor(window, 'sessionStorage');
  Object.defineProperty(window, 'sessionStorage', {
    configurable: true,
    get: () => { if (!stub) throw new DOMException('denied', 'SecurityError'); return stub; },
  });
  const restore = () => {
    if (original) Object.defineProperty(window, 'sessionStorage', original);
    else delete (window as unknown as Record<string, unknown>).sessionStorage;
  };
  let result: T;
  try {
    result = fn();
  } catch (error) {
    restore();
    throw error;
  }
  // An async body keeps the replacement until it settles.
  if (result instanceof Promise) return result.finally(restore) as T;
  restore();
  return result;
}

export function resetTab() {
  window.history.replaceState(null, '', '/');
  window.sessionStorage.clear();
}
