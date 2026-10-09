// Cancellation flow: preflight, durable intent, manual resend, result recovery, late responses and replacement preservation; fixture HTTP only.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  restoreIntent, runCheckResult, runFirstCancel, runReadback, runResend, wakeOnJournal, type CancelRun,
} from '@/lib/command-flow/reservation-cancel-flow';
import { readJournal, journalRecord, persistIntent } from '@/lib/command-flow/reservation-cancel-journal';
import { freezeIntent, IDLE, type CancelPhase } from '@/lib/command-flow/reservation-cancel-state';
import {
  blocked, commitDelete, deferred, farm, installCancelServer, json, onServerPath, resetTab, rev, withSessionStorage, type FakeCancelServer,
} from './fixtures/reservation-cancel';

const target = { accountId: 1, server: 'pep', generation: null, actor: 1, turnIdx: 0, revision: rev(0), sentence: '농지개간' };
// Lets fire-and-forget reads (readback after a receipt) settle; responses are local, so a few macrotasks suffice.
const flush = async () => { for (let i = 0; i < 5; i += 1) await new Promise(r => setTimeout(r, 0)); };

function harness() {
  let phase: CancelPhase = IDLE;
  let alive = true;
  const phases: CancelPhase[] = [];
  const run: CancelRun = {
    alive: () => alive,
    current: () => (alive ? phase : IDLE),
    commit: (next) => { if (alive) { phase = next; phases.push(next); } },
    listChanged: vi.fn(),
    tokens: { result: 0, readback: 0 },
  };
  return { run, phases, phase: () => phase, kill: () => { alive = false; } };
}

const records = () => (readJournal() as { ok: true; records: ReturnType<typeof journalRecord>[] }).records;
let server: FakeCancelServer;
let uuid = 0;
beforeEach(() => {
  resetTab();
  onServerPath('pep');
  server = installCancelServer([farm(0), farm(1)]);
  uuid = 0;
  vi.spyOn(globalThis.crypto, 'randomUUID').mockImplementation(() => rev(800 + ++uuid) as `${string}-${string}-${string}-${string}-${string}`);
});
afterEach(() => {
  vi.unstubAllGlobals();
  resetTab();
});

describe('first cancellation', () => {
  it('fresh read → one UUID saved BEFORE the bodyless DELETE → receipt → fresh list shows the slot empty', async () => {
    const h = harness();
    let savedAtDispatch: unknown = null;
    server.onDelete = (call) => { savedAtDispatch = records(); return commitDelete(server, call); };
    h.run.commit({ kind: 'preflight', target });
    await runFirstCancel(h.run, target);
    await flush();
    expect(savedAtDispatch).toEqual([journalRecord(freezeIntent(target, rev(801)), 'sending')]);
    expect(server.deletes).toHaveLength(1);
    expect(server.deletes[0].key).toBe(rev(801));
    expect(server.deletes[0].url.searchParams.get('revision')).toBe(rev(0));
    expect(server.deletes[0].body).toBeUndefined();
    expect(h.phase()).toEqual({ kind: 'done', turnIdx: 0, after: 'empty' });
    expect(server.slots).toEqual([farm(1)]);
    expect(records()).toEqual([]);
    expect(h.run.listChanged).toHaveBeenCalled();
    expect(globalThis.crypto.randomUUID).toHaveBeenCalledTimes(1);
  });

  it('a changed revision in the fresh read sends no DELETE and asks for a new choice', async () => {
    server.slots = [farm(0, rev(50)), farm(1)];
    const h = harness();
    await runFirstCancel(h.run, target);
    expect(server.deletes).toEqual([]);
    expect(h.phase()).toEqual({ kind: 'blocked', turnIdx: 0, code: 'CHANGED' });
    expect(records()).toEqual([]);
    expect(globalThis.crypto.randomUUID).not.toHaveBeenCalled();
  });

  it('an unreadable fresh list sends nothing', async () => {
    server.onRing = () => json({ result: true, generalId: 1, slots: [{ turnIdx: 0, action: 'action.farm', brief: '', arg: {} }] });
    const h = harness();
    await runFirstCancel(h.run, target);
    expect(server.deletes).toEqual([]);
    expect(h.phase()).toEqual({ kind: 'blocked', turnIdx: 0, code: 'READ_FAILED' });
  });

  it('storage failure writes nothing and sends nothing', async () => {
    const h = harness();
    await withSessionStorage(null, () => runFirstCancel(h.run, target));
    expect(server.deletes).toEqual([]);
    expect(h.phase()).toEqual({ kind: 'blocked', turnIdx: 0, code: 'STORAGE' });
  });

  it('an existing intent for the slot is never replaced by a new UUID', async () => {
    persistIntent(journalRecord(freezeIntent(target, rev(700)), 'unknown'));
    const h = harness();
    await runFirstCancel(h.run, target);
    expect(server.deletes).toEqual([]);
    expect(h.phase()).toEqual({ kind: 'blocked', turnIdx: 0, code: 'OCCUPIED' });
    expect(records().map(r => r.requestId)).toEqual([rev(700)]);
  });

  it('a scope that closes before dispatch drops its own fresh record and sends nothing', async () => {
    const h = harness();
    const ring = deferred<Response>();
    server.onRing = () => ring.promise;
    const started = runFirstCancel(h.run, target);
    h.kill();
    ring.resolve(json({ result: true, generalId: 1, slots: [farm(0)] }));
    await started;
    expect(server.deletes).toEqual([]);
    expect(records()).toEqual([]);
  });
});

describe('refusals', () => {
  it('WORLD_EXECUTING keeps UUID · slot · revision and resends only by hand, with the SAME intent', async () => {
    server.onDelete = () => blocked('WORLD_EXECUTING', 409, true);
    const h = harness();
    await runFirstCancel(h.run, target);
    await flush();
    expect(h.phase()).toMatchObject({ kind: 'retryable', intent: { requestId: rev(801), revision: rev(0) } });
    expect(records()).toEqual([journalRecord(freezeIntent(target, rev(801)), 'retryable')]);
    await flush();
    expect(server.deletes).toHaveLength(1);
    server.onDelete = null;
    await runResend(h.run);
    await flush();
    expect(server.deletes.map(d => [d.key, d.url.searchParams.get('revision')])).toEqual([[rev(801), rev(0)], [rev(801), rev(0)]]);
    expect(h.phase()).toEqual({ kind: 'done', turnIdx: 0, after: 'empty' });
    expect(globalThis.crypto.randomUUID).toHaveBeenCalledTimes(1);
  });

  it('REVISION_MISMATCH ends the intent and reloads; a replacement is never cancelled automatically', async () => {
    server.onDelete = (call) => { server.slots = [farm(0, rev(60)), farm(1)]; return commitDelete(server, call); };
    const h = harness();
    await runFirstCancel(h.run, target);
    await flush();
    expect(h.phase()).toEqual({ kind: 'blocked', turnIdx: 0, code: 'REVISION_MISMATCH' });
    expect(server.deletes).toHaveLength(1);
    expect(server.slots).toEqual([farm(0, rev(60)), farm(1)]);
    expect(records()).toEqual([]);
    expect(h.run.listChanged).toHaveBeenCalled();
  });

  it('IDEMPOTENCY_CONFLICT stops the intent; no new UUID, no automatic send', async () => {
    server.onDelete = () => blocked('IDEMPOTENCY_CONFLICT', 409);
    const h = harness();
    await runFirstCancel(h.run, target);
    await flush();
    expect(h.phase()).toEqual({ kind: 'blocked', turnIdx: 0, code: 'IDEMPOTENCY_CONFLICT' });
    expect(server.deletes).toHaveLength(1);
    expect(globalThis.crypto.randomUUID).toHaveBeenCalledTimes(1);
  });

  it.each(['NOT_OWNER:403', 'INVALID_REVISION:400'])('%s is shown and not retried', async (pair) => {
    const [code, status] = pair.split(':');
    server.onDelete = () => blocked(code, Number(status));
    const h = harness();
    await runFirstCancel(h.run, target);
    expect(h.phase()).toEqual({ kind: 'blocked', turnIdx: 0, code });
    expect(server.deletes).toHaveLength(1);
    expect(server.slots).toEqual([farm(0), farm(1)]);
  });

  it.each([
    ['503 POLICY_UNAVAILABLE', () => blocked('POLICY_UNAVAILABLE', 503)],
    ['filter 503', () => json({ error: { code: 'SERVER_ADMISSION_UNAVAILABLE', message: '' } }, 503)],
    ['proxy text', () => new Response('Bad Gateway', { status: 502 })],
    ['malformed 2xx', () => json({ ok: true }, 200)],
    ['network', () => { throw new TypeError('Failed to fetch'); }],
  ])('%s is UNKNOWN with the UUID kept', async (_, answer) => {
    server.onDelete = answer;
    const h = harness();
    await runFirstCancel(h.run, target);
    expect(h.phase()).toMatchObject({ kind: 'unknown', intent: { requestId: rev(801) } });
    expect(records()).toEqual([journalRecord(freezeIntent(target, rev(801)), 'unknown')]);
  });

  it('a later non-admission does not erase an earlier ambiguous send', async () => {
    server.onDelete = () => new Response('Gateway Timeout', { status: 504 });
    const h = harness();
    await runFirstCancel(h.run, target);
    server.onDelete = () => blocked('REVISION_MISMATCH', 409);
    await runResend(h.run);
    expect(h.phase()).toMatchObject({ kind: 'unknown', note: 'REVISION_MISMATCH', intent: { requestId: rev(801) } });
    expect(records()[0].cancelState).toBe('unknown');
  });
});

describe('recovery', () => {
  it('commit response lost → own UUID result GET RESOLVED → NEW fresh GET shows the slot empty', async () => {
    server.onDelete = (call) => { commitDelete(server, call); throw new TypeError('connection reset'); };
    const h = harness();
    await runFirstCancel(h.run, target);
    expect(h.phase()).toMatchObject({ kind: 'unknown' });
    const readsBefore = server.ringReads;
    await runCheckResult(h.run, freezeIntent(target, rev(801)));
    await flush();
    expect(server.resultReads).toEqual([rev(801)]);
    expect(server.ringReads).toBe(readsBefore + 1);
    expect(h.phase()).toEqual({ kind: 'done', turnIdx: 0, after: 'empty' });
    expect(server.deletes).toHaveLength(1);
  });

  it('PENDING and failed result reads keep the UUID and say nothing about failure', async () => {
    const intent = freezeIntent(target, rev(700));
    persistIntent(journalRecord(intent, 'unknown'));
    const h = harness();
    h.run.commit({ kind: 'unknown', intent, note: null, checking: false });
    await runCheckResult(h.run, intent);
    expect(h.phase()).toEqual({ kind: 'unknown', intent, note: null, checking: false });
    server.onResult = () => { throw new TypeError('offline'); };
    await runCheckResult(h.run, intent);
    expect(h.phase()).toEqual({ kind: 'unknown', intent, note: null, checking: false });
    expect(records()[0]).toMatchObject({ requestId: rev(700), cancelState: 'unknown' });
    expect(server.deletes).toEqual([]);
  });

  it('result GET and resend answers in reverse order cannot downgrade a receipt', async () => {
    const intent = freezeIntent(target, rev(700));
    persistIntent(journalRecord(intent, 'unknown'));
    const h = harness();
    h.run.commit({ kind: 'unknown', intent, note: null, checking: false });
    const pending = deferred<Response>();
    server.onResult = () => pending.promise;
    const check = runCheckResult(h.run, intent);
    // The check holds the busy flag; a resend waits — let the result answer late instead.
    h.run.commit({ kind: 'unknown', intent, note: null, checking: false });
    await runResend(h.run);
    await flush();
    expect(h.phase()).toEqual({ kind: 'done', turnIdx: 0, after: 'empty' });
    pending.resolve(json({ status: 'PENDING', requestId: rev(700) }));
    await check;
    expect(h.phase()).toEqual({ kind: 'done', turnIdx: 0, after: 'empty' });
  });

  it('r1 cancelled, then r2 reserved: replay / result GET is the r1 receipt and the fresh list KEEPS r2', async () => {
    const intent = freezeIntent(target, rev(700));
    commitDelete(server, { method: 'DELETE', url: new URL(`http://x/?generalId=1&turnIdx=0&revision=${rev(0)}`), key: rev(700), body: undefined, contentType: null });
    server.slots = [farm(0, rev(61)), farm(1)];
    persistIntent(journalRecord(intent, 'unknown'));
    const h = harness();
    h.run.commit({ kind: 'unknown', intent, note: null, checking: false });
    await runResend(h.run);
    await flush();
    expect(server.deletes.map(d => d.url.searchParams.get('revision'))).toEqual([rev(0)]);
    expect(h.phase()).toEqual({ kind: 'done', turnIdx: 0, after: 'replaced' });
    expect(server.slots).toEqual([farm(0, rev(61)), farm(1)]);
    const g = harness();
    g.run.commit({ kind: 'unknown', intent, note: null, checking: false });
    await runCheckResult(g.run, intent);
    await flush();
    expect(g.phase()).toEqual({ kind: 'done', turnIdx: 0, after: 'replaced' });
  });

  it('fresh-list failure keeps the receipt and retries the GET only', async () => {
    const h = harness();
    server.onRing = null;
    let reads = 0;
    const original = server.slots;
    server.onRing = () => (++reads === 1 ? json({ result: true, generalId: 1, slots: original }) : json({}, 503));
    await runFirstCancel(h.run, target);
    await flush();
    expect(h.phase()).toMatchObject({ kind: 'readbackError', intent: { requestId: rev(801) } });
    expect(records()[0].cancelState).toBe('confirmed');
    server.onRing = null;
    await runReadback(h.run, freezeIntent(target, rev(801)));
    expect(h.phase()).toEqual({ kind: 'done', turnIdx: 0, after: 'empty' });
    expect(server.deletes).toHaveLength(1);
  });

  it('two fresh-list reads answering out of order: only the newest decides', async () => {
    const intent = freezeIntent(target, rev(700));
    server.slots = [farm(1)];
    const h = harness();
    h.run.commit({ kind: 'refreshing', intent });
    const slow = deferred<Response>();
    let reads = 0;
    server.onRing = () => (++reads === 1 ? slow.promise : json({ result: true, generalId: 1, slots: server.slots }));
    const first = runReadback(h.run, intent);
    await runReadback(h.run, intent);
    expect(h.phase()).toEqual({ kind: 'done', turnIdx: 0, after: 'empty' });
    slow.resolve(json({}, 503));
    await first;
    expect(h.phase()).toEqual({ kind: 'done', turnIdx: 0, after: 'empty' });
  });

  it('an empty current list alone is not this UUID\'s success', async () => {
    server.slots = [];
    const intent = freezeIntent(target, rev(700));
    persistIntent(journalRecord(intent, 'unknown'));
    const h = harness();
    expect(restoreIntent(h.run, target)).toBe('restored');
    await flush();
    expect(h.phase()).toMatchObject({ kind: 'unknown', intent: { requestId: rev(700) } });
    expect(server.deletes).toEqual([]);
  });

  it('restoring on return sends no DELETE; a confirmed record goes straight to the fresh list', async () => {
    const intent = freezeIntent(target, rev(700));
    persistIntent(journalRecord(intent, 'confirmed'));
    server.slots = [farm(1)];
    // Another account never sees (or resumes) this account's intent.
    expect(restoreIntent(harness().run, { ...target, accountId: 2 })).toBe('none');
    const h = harness();
    expect(restoreIntent(h.run, target)).toBe('restored');
    await flush();
    expect(h.phase()).toEqual({ kind: 'done', turnIdx: 0, after: 'empty' });
    expect(server.deletes).toEqual([]);
  });
});

describe('late responses', () => {
  it('a DELETE answering after its scope closed updates only its own journal and signals the list', async () => {
    const answer = deferred<Response>();
    server.onDelete = (call) => { const r = commitDelete(server, call); return answer.promise.then(() => r); };
    const h = harness();
    const sent = runFirstCancel(h.run, target);
    await flush();
    await flush();
    const shown = h.phase();
    h.kill();
    answer.resolve(json({}));
    await sent;
    expect(h.phase()).toBe(shown);
    expect(records()[0]).toMatchObject({ requestId: rev(801), cancelState: 'confirmed' });
    expect(h.run.listChanged).toHaveBeenCalled();
  });

  it('a late receipt recorded by an older screen wakes this screen\'s own result GET', async () => {
    const intent = freezeIntent(target, rev(700));
    persistIntent(journalRecord(intent, 'confirmed'));
    server.receipts.set(rev(700), { actor: 1, turnIdx: 0, revision: rev(0) });
    server.slots = [farm(1)];
    const h = harness();
    h.run.commit({ kind: 'unknown', intent, note: null, checking: false });
    wakeOnJournal(h.run, rev(700));
    await flush();
    await flush();
    expect(server.resultReads).toEqual([rev(700)]);
    expect(h.phase()).toEqual({ kind: 'done', turnIdx: 0, after: 'empty' });
    wakeOnJournal(h.run, rev(701));
    expect(server.resultReads).toHaveLength(1);
  });
});
