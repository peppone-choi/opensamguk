// Cancellation state machine: response classification, journal transitions, receipt precedence, ambiguity and frozen-target comparison.
import { describe, expect, it } from 'vitest';
import {
  afterOutcome, afterReadback, afterResult, classifyCancel, classifyResult, freezeIntent, IDLE, journalAfter, phaseBusy,
  preflightMatches, restoredPhase, startChecking, type CancelIntent, type CancelOutcome, type CancelPhase,
} from '@/lib/command-flow/reservation-cancel-state';
import { cancelDialog, cancelEntry, cancelStatus } from '@/lib/command-flow/reservation-cancel-view';
import { journalRecord } from '@/lib/command-flow/reservation-cancel-journal';
import { blocked as blockedBody, farm, receiptBody, resultBody, rev } from './fixtures/reservation-cancel';

const target = { accountId: 1, server: 'pep', generation: null, actor: 1, turnIdx: 0, revision: rev(0), sentence: '농지개간' };
const intent: CancelIntent = freezeIntent(target, rev(800));
const committed = { actor: 1, turnIdx: 0, revision: rev(0) };
const response = async (r: Response) => ({ kind: 'response' as const, status: r.status, body: await r.json().catch(() => undefined) });

describe('classify DELETE', () => {
  it('only a matching receipt is success', async () => {
    expect(classifyCancel(await response(new Response(JSON.stringify(receiptBody(rev(800), committed)), { status: 200 })), intent)).toEqual({ kind: 'receipt' });
    expect(classifyCancel(await response(new Response(JSON.stringify(receiptBody(rev(801), committed)), { status: 200 })), intent)).toEqual({ kind: 'unknown', code: null });
    expect(classifyCancel({ kind: 'response', status: 200, body: { accepted: true, result: { slotEmpty: true } } }, intent)).toEqual({ kind: 'unknown', code: null });
    expect(classifyCancel({ kind: 'response', status: 204, body: undefined }, intent)).toEqual({ kind: 'unknown', code: null });
  });

  it.each<[string, Response, CancelOutcome]>([
    ['409 WORLD_EXECUTING retryable', blockedBody('WORLD_EXECUTING', 409, true), { kind: 'worldExecuting' }],
    ['409 REVISION_MISMATCH', blockedBody('REVISION_MISMATCH', 409), { kind: 'rejected', code: 'REVISION_MISMATCH' }],
    ['409 IDEMPOTENCY_CONFLICT', blockedBody('IDEMPOTENCY_CONFLICT', 409), { kind: 'rejected', code: 'IDEMPOTENCY_CONFLICT' }],
    ['403 NOT_OWNER', blockedBody('NOT_OWNER', 403), { kind: 'rejected', code: 'NOT_OWNER' }],
    ['401 UNAUTHORIZED', blockedBody('UNAUTHORIZED', 401), { kind: 'rejected', code: 'UNAUTHORIZED' }],
    ['400 INVALID_SLOT', blockedBody('INVALID_SLOT', 400), { kind: 'rejected', code: 'INVALID_SLOT' }],
    ['400 INVALID_REVISION', blockedBody('INVALID_REVISION', 400), { kind: 'rejected', code: 'INVALID_REVISION' }],
    ['400 INVALID_IDEMPOTENCY_KEY', blockedBody('INVALID_IDEMPOTENCY_KEY', 400), { kind: 'rejected', code: 'INVALID_IDEMPOTENCY_KEY' }],
    ['400 INVALID_ARGUMENT', blockedBody('INVALID_ARGUMENT', 400), { kind: 'rejected', code: 'INVALID_ARGUMENT' }],
    ['503 POLICY_UNAVAILABLE', blockedBody('POLICY_UNAVAILABLE', 503), { kind: 'unknown', code: 'POLICY_UNAVAILABLE' }],
    ['503 RECEIPT_UNAVAILABLE', blockedBody('RECEIPT_UNAVAILABLE', 503), { kind: 'unknown', code: 'RECEIPT_UNAVAILABLE' }],
    ['WORLD_EXECUTING not 409', blockedBody('WORLD_EXECUTING', 503, true), { kind: 'unknown', code: 'WORLD_EXECUTING' }],
    ['unknown BLOCKED code', blockedBody('SOMETHING_NEW', 409), { kind: 'unknown', code: 'SOMETHING_NEW' }],
    ['filter 401 AUTH_REQUIRED', new Response(JSON.stringify({ error: { code: 'AUTH_REQUIRED', message: '' } }), { status: 401 }), { kind: 'rejected', code: 'AUTH_REQUIRED' }],
    ['filter 403 SERVER_NOT_PUBLIC', new Response(JSON.stringify({ error: { code: 'SERVER_NOT_PUBLIC', message: '' } }), { status: 403 }), { kind: 'rejected', code: 'SERVER_NOT_PUBLIC' }],
    ['filter 503 SERVER_ADMISSION_UNAVAILABLE', new Response(JSON.stringify({ error: { code: 'SERVER_ADMISSION_UNAVAILABLE', message: '' } }), { status: 503 }), { kind: 'unknown', code: 'SERVER_ADMISSION_UNAVAILABLE' }],
    ['bodyless 401', new Response(null, { status: 401 }), { kind: 'rejected', code: 'AUTH_REQUIRED' }],
    ['bodyless 403', new Response(null, { status: 403 }), { kind: 'rejected', code: 'FORBIDDEN' }],
    ['proxy 503 text', new Response('Service Unavailable', { status: 503 }), { kind: 'unknown', code: null }],
    ['string error', new Response(JSON.stringify({ error: 'Bad Gateway' }), { status: 502 }), { kind: 'unknown', code: null }],
    ['bodyless 500', new Response(null, { status: 500 }), { kind: 'unknown', code: null }],
  ])('%s', async (_, r, outcome) => {
    expect(classifyCancel(await response(r), intent)).toEqual(outcome);
  });

  it('network loss / abort after dispatch is unknown', () => {
    expect(classifyCancel({ kind: 'network' }, intent)).toEqual({ kind: 'unknown', code: null });
  });

  it('result GET uses its own parser; non-200 is unknown', () => {
    expect(classifyResult({ kind: 'response', status: 200, body: resultBody(rev(800), committed) }, intent)).toBe('receipt');
    expect(classifyResult({ kind: 'response', status: 200, body: receiptBody(rev(800), committed) }, intent)).toBe('receipt');
    expect(classifyResult({ kind: 'response', status: 404, body: resultBody(rev(800), committed) }, intent)).toBe('unknown');
    expect(classifyResult({ kind: 'network' }, intent)).toBe('unknown');
  });
});

describe('journal transition', () => {
  it('receipt confirms; WORLD_EXECUTING keeps the intent; clean refusal ends it; unknown keeps it', () => {
    expect(journalAfter({ kind: 'receipt' }, false)).toBe('confirmed');
    expect(journalAfter({ kind: 'worldExecuting' }, false)).toBe('retryable');
    expect(journalAfter({ kind: 'rejected', code: 'REVISION_MISMATCH' }, false)).toBeNull();
    expect(journalAfter({ kind: 'unknown', code: null }, false)).toBe('unknown');
  });

  it('an earlier ambiguous send survives a later non-admission', () => {
    expect(journalAfter({ kind: 'worldExecuting' }, true)).toBe('unknown');
    expect(journalAfter({ kind: 'rejected', code: 'REVISION_MISMATCH' }, true)).toBe('unknown');
    expect(journalAfter({ kind: 'rejected', code: 'NOT_OWNER' }, true)).toBe('unknown');
    expect(journalAfter({ kind: 'receipt' }, true)).toBe('confirmed');
  });
});

describe('phase precedence', () => {
  const sending: CancelPhase = { kind: 'sending', intent };
  it('maps outcomes of the shown intent', () => {
    expect(afterOutcome(sending, intent, { kind: 'receipt' }, false)).toEqual({ kind: 'refreshing', intent });
    expect(afterOutcome(sending, intent, { kind: 'worldExecuting' }, false)).toEqual({ kind: 'retryable', intent, checking: false });
    expect(afterOutcome(sending, intent, { kind: 'rejected', code: 'REVISION_MISMATCH' }, false)).toEqual({ kind: 'blocked', turnIdx: 0, code: 'REVISION_MISMATCH' });
    expect(afterOutcome(sending, intent, { kind: 'unknown', code: 'POLICY_UNAVAILABLE' }, false)).toEqual({ kind: 'unknown', intent, note: null, checking: false });
    expect(afterOutcome(sending, intent, { kind: 'rejected', code: 'REVISION_MISMATCH' }, true)).toEqual({ kind: 'unknown', intent, note: 'REVISION_MISMATCH', checking: false });
  });

  it('nothing after a receipt downgrades it (late resend answer, PENDING or error)', () => {
    const refreshing: CancelPhase = { kind: 'refreshing', intent };
    expect(afterOutcome(refreshing, intent, { kind: 'unknown', code: null }, true)).toBe(refreshing);
    expect(afterResult(refreshing, intent, 'pending')).toBe(refreshing);
    expect(afterResult(refreshing, intent, 'receipt')).toBe(refreshing);
    const done: CancelPhase = { kind: 'done', turnIdx: 0, after: 'empty' };
    expect(afterOutcome(done, intent, { kind: 'receipt' }, false)).toBe(done);
  });

  it('a response for another UUID or after the phase moved on is ignored', () => {
    const other = freezeIntent(target, rev(801));
    expect(afterOutcome(sending, other, { kind: 'receipt' }, false)).toBe(sending);
    expect(afterResult(IDLE, intent, 'receipt')).toBe(IDLE);
  });

  it('PENDING after a check only clears the busy flag; receipt moves to refreshing', () => {
    const checking = startChecking({ kind: 'unknown', intent, note: null, checking: false });
    expect(checking).toEqual({ kind: 'unknown', intent, note: null, checking: true });
    expect(afterResult(checking, intent, 'pending')).toEqual({ kind: 'unknown', intent, note: null, checking: false });
    expect(afterResult(checking, intent, 'receipt')).toEqual({ kind: 'refreshing', intent });
    expect(startChecking(IDLE)).toBe(IDLE);
  });
});

describe('fresh list after a receipt', () => {
  const refreshing: CancelPhase = { kind: 'refreshing', intent };
  it('absent slot is empty; another revision is a replacement that stays', () => {
    expect(afterReadback(refreshing, intent, { result: true, generalId: 1, slots: [farm(1)] })).toEqual({ kind: 'done', turnIdx: 0, after: 'empty' });
    expect(afterReadback(refreshing, intent, { result: true, generalId: 1, slots: [farm(0, rev(55))] })).toEqual({ kind: 'done', turnIdx: 0, after: 'replaced' });
  });

  it('a failed read keeps the receipt; the same revision still present contradicts it and is a read error', () => {
    expect(afterReadback(refreshing, intent, null)).toEqual({ kind: 'readbackError', intent });
    expect(afterReadback(refreshing, intent, { result: true, generalId: 1, slots: [farm(0)] })).toEqual({ kind: 'readbackError', intent });
  });
});

describe('frozen target and restore', () => {
  it('preflight needs the same actor, slot and revision', () => {
    expect(preflightMatches(target, { result: true, generalId: 1, slots: [farm(0)] })).toBe(true);
    expect(preflightMatches(target, { result: true, generalId: 1, slots: [farm(0, rev(9))] })).toBe(false);
    expect(preflightMatches(target, { result: true, generalId: 1, slots: [] })).toBe(false);
    expect(preflightMatches(target, { result: true, generalId: 2, slots: [farm(0)] })).toBe(false);
  });

  it('a stored sending intent comes back as unknown (it may have left); confirmed comes back refreshing', () => {
    expect(restoredPhase(journalRecord(intent, 'sending'))).toEqual({ kind: 'unknown', intent, note: null, checking: false });
    expect(restoredPhase(journalRecord(intent, 'unknown'))).toEqual({ kind: 'unknown', intent, note: null, checking: false });
    expect(restoredPhase(journalRecord(intent, 'retryable'))).toEqual({ kind: 'retryable', intent, checking: false });
    expect(restoredPhase(journalRecord(intent, 'confirmed'))).toEqual({ kind: 'refreshing', intent });
    expect(restoredPhase(journalRecord({ ...intent, generation: 'g-2' }, 'unknown'))).toMatchObject({ intent: { generation: 'g-2' } });
  });

  it('busy only while a read or send is in flight', () => {
    expect(phaseBusy({ kind: 'preflight', target })).toBe(true);
    expect(phaseBusy({ kind: 'sending', intent })).toBe(true);
    expect(phaseBusy({ kind: 'unknown', intent, note: null, checking: false })).toBe(false);
  });
});

describe('view', () => {
  const row = { state: 'reserved' as const, revision: rev(0), name: '농지개간' };
  it('entry: hidden for empty / unverified / pending intent; reasons for missing server, storage or revision', () => {
    expect(cancelEntry({ verified: true, server: 'pep', journalOk: true, row, phase: IDLE })).toEqual({ kind: 'available' });
    expect(cancelEntry({ verified: false, server: 'pep', journalOk: true, row, phase: IDLE })).toEqual({ kind: 'hidden' });
    expect(cancelEntry({ verified: true, server: 'pep', journalOk: true, row: { ...row, state: 'empty' }, phase: IDLE })).toEqual({ kind: 'hidden' });
    expect(cancelEntry({ verified: true, server: 'pep', journalOk: true, row: null, phase: IDLE })).toEqual({ kind: 'hidden' });
    expect(cancelEntry({ verified: true, server: 'pep', journalOk: true, row, phase: { kind: 'unknown', intent, note: null, checking: false } })).toEqual({ kind: 'hidden' });
    expect(cancelEntry({ verified: true, server: null, journalOk: true, row, phase: IDLE }).kind).toBe('blocked');
    expect(cancelEntry({ verified: true, server: 'pep', journalOk: false, row, phase: IDLE }).kind).toBe('blocked');
    expect(cancelEntry({ verified: true, server: 'pep', journalOk: true, row: { ...row, revision: null }, phase: IDLE }).kind).toBe('blocked');
  });

  it('dialog shows the frozen stored sentence and closes when the row revision changes', () => {
    const confirming: CancelPhase = { kind: 'confirming', target };
    expect(cancelDialog(confirming, rev(0))).toEqual({
      open: true, title: '01순 예약을 취소합니다', message: '01순의 「농지개간」 예약을 지웁니다. 다른 순의 예약은 그대로입니다.', busy: false,
    });
    expect(cancelDialog(confirming, rev(9)).open).toBe(false);
    expect(cancelDialog({ kind: 'preflight', target }, rev(9))).toMatchObject({ open: true, busy: true });
  });

  it('status texts never claim success before a receipt', () => {
    expect(cancelStatus({ kind: 'unknown', intent, note: null, checking: false })?.text).toMatch(/^취소 결과를 아직 확인하지 못했습니다/);
    expect(cancelStatus({ kind: 'unknown', intent, note: null, checking: false })?.actions).toEqual(['checkResult', 'retrySend']);
    expect(cancelStatus({ kind: 'refreshing', intent })?.text).toBe('이 예약의 취소를 확인했습니다 — 현재 목록 확인 중');
    expect(cancelStatus({ kind: 'readbackError', intent })).toMatchObject({ text: '이 예약의 취소를 확인했습니다 — 현재 목록을 확인하지 못했습니다', actions: ['retryReadback'] });
    expect(cancelStatus({ kind: 'retryable', intent, checking: false })?.actions).toEqual(['retrySend', 'abandon']);
    expect(cancelStatus({ kind: 'sending', intent })?.busy).toBe(true);
    expect(cancelStatus(IDLE)).toBeNull();
    expect(cancelStatus({ kind: 'blocked', turnIdx: 0, code: 'X_NEW' })?.text).not.toMatch(/X_NEW/);
  });
});
