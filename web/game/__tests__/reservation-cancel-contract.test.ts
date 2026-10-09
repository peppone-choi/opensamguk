// B1 cancellation wire contracts: reservation read, direct DELETE receipt, recovery GET and rejection envelopes are parsed separately.
import { describe, expect, it } from 'vitest';
import {
  isCancelReceipt, isUuid, parseReservedCommands, parseReservedDisplay, readCancelRejection, readCancelResult, ReservedReadError,
} from '@/lib/api/reservation-cancel-contract';
import { farm, receiptBody, resultBody, rev } from './fixtures/reservation-cancel';

const wire = { actor: 1, turnIdx: 0, revision: rev(0), requestId: rev(777) };
const committed = { actor: 1, turnIdx: 0, revision: rev(0) };

describe('reservation GET', () => {
  it('keeps rows with their revision and the calendar metadata exactly as sent', () => {
    const body = { result: true, generalId: 1, slots: [farm(0), farm(11)], year: 190, month: 3, turnPhase: 2, turnTime: '2026-10-09 22:40:00', turnTerm: 60 };
    const read = parseReservedCommands(body, 1);
    expect(read.slots).toEqual([farm(0), farm(11)]);
    expect(read).toMatchObject({ year: 190, month: 3, turnPhase: 2, turnTime: '2026-10-09 22:40:00', turnTerm: 60 });
  });

  it('an empty owned list is a real empty ring — absent slots are absent rows, not rest', () => {
    expect(parseReservedCommands({ result: true, generalId: 1, slots: [] }, 1).slots).toEqual([]);
  });

  it.each([
    ['result not boolean true', { result: 'true', generalId: 1, slots: [] }],
    ['result false', { result: false, generalId: 1, slots: [] }],
    ['foreign actor', { result: true, generalId: 2, slots: [] }],
    ['null actor', { result: true, generalId: null, slots: [] }],
    ['slots missing', { result: true, generalId: 1 }],
    ['slots not array', { result: true, generalId: 1, slots: {} }],
    ['duplicate slot', { result: true, generalId: 1, slots: [farm(3), farm(3, rev(33))] }],
    ['slot 12', { result: true, generalId: 1, slots: [farm(12)] }],
    ['negative slot', { result: true, generalId: 1, slots: [{ ...farm(0), turnIdx: -1 }] }],
    ['fractional slot', { result: true, generalId: 1, slots: [{ ...farm(0), turnIdx: 1.5 }] }],
    ['missing revision', { result: true, generalId: 1, slots: [{ turnIdx: 0, action: 'action.farm', brief: '', arg: {} }] }],
    ['non-UUID revision', { result: true, generalId: 1, slots: [{ ...farm(0), revision: '7' }] }],
    ['missing arg', { result: true, generalId: 1, slots: [{ turnIdx: 0, action: 'action.farm', brief: '', revision: rev(0) }] }],
    ['blank action', { result: true, generalId: 1, slots: [{ ...farm(0), action: ' ' }] }],
    ['missing brief', { result: true, generalId: 1, slots: [{ turnIdx: 0, action: 'action.farm', arg: {}, revision: rev(0) }] }],
    ['over twelve rows', { result: true, generalId: 1, slots: Array.from({ length: 13 }, (_, i) => farm(i % 12, rev(100 + i))) }],
    ['not an object', 'ok'],
  ])('%s is a read error, never an empty ring', (_, body) => {
    expect(() => parseReservedCommands(body, 1)).toThrow(ReservedReadError);
  });

  it('display read keeps a pre-B1 row read-only with revision null; the strict read refuses the same body', () => {
    const legacy = { turnIdx: 0, action: 'action.farm', brief: '', arg: {} };
    const body = { result: true, generalId: 1, slots: [legacy, farm(1)] };
    expect(parseReservedDisplay(body, 1).slots).toEqual([{ ...legacy, revision: null }, farm(1)]);
    expect(() => parseReservedCommands(body, 1)).toThrow(ReservedReadError);
  });

  it.each([
    ['non-UUID revision', { result: true, generalId: 1, slots: [{ ...farm(0), revision: '7' }] }],
    ['null revision', { result: true, generalId: 1, slots: [{ ...farm(0), revision: null }] }],
    ['foreign actor', { result: true, generalId: 2, slots: [] }],
    ['duplicate slot', { result: true, generalId: 1, slots: [farm(3), farm(3, rev(33))] }],
    ['slot 12', { result: true, generalId: 1, slots: [farm(12)] }],
    ['missing arg', { result: true, generalId: 1, slots: [{ turnIdx: 0, action: 'action.farm', brief: '' }] }],
    ['result false', { result: false, generalId: 1, slots: [] }],
  ])('display read: %s is still a read error', (_, body) => {
    expect(() => parseReservedDisplay(body, 1)).toThrow(ReservedReadError);
  });

  it('UUID check accepts only the canonical 8-4-4-4-12 form', () => {
    expect(isUuid(rev(1))).toBe(true);
    expect(isUuid('r1')).toBe(false);
    expect(isUuid(`${rev(1)}x`)).toBe(false);
    expect(isUuid(1)).toBe(false);
  });
});

describe('direct DELETE receipt', () => {
  it('accepts the exact success shape for the frozen intent; other nullable lifecycle fields are not required', () => {
    expect(isCancelReceipt(receiptBody(wire.requestId, committed), wire)).toBe(true);
    const minimal = receiptBody(wire.requestId, committed);
    const inner = { ...(minimal.result as Record<string, unknown>) };
    delete inner.reason;
    delete inner.phase;
    expect(isCancelReceipt({ ...minimal, result: inner }, wire)).toBe(true);
  });

  const body = receiptBody(wire.requestId, committed);
  const inner = body.result as Record<string, unknown>;
  it.each([
    ['other UUID', { ...body, requestId: rev(778) }],
    ['other type', { ...body, type: 'reservationAccepted' }],
    ['not ok', { ...body, ok: false }],
    ['not accepted', { ...body, accepted: false }],
    ['receipt not recorded', { ...body, receiptRecorded: false }],
    ['status PENDING', { ...body, status: 'PENDING' }],
    ['missing committed version', { ...body, committedWorldVersion: undefined }],
    ['other actor', { ...body, result: { ...inner, generalId: 2 } }],
    ['other slot', { ...body, result: { ...inner, turnIdx: 1 } }],
    ['other revision', { ...body, result: { ...inner, reservationRevision: rev(1) } }],
    ['slot not empty', { ...body, result: { ...inner, slotEmpty: false } }],
    ['inner not ok', { ...body, result: { ...inner, ok: false } }],
    ['other kind', { ...body, result: { ...inner, commandKind: 'RESERVED_TURN' } }],
    ['other action code', { ...body, result: { ...inner, actionCode: 'reserve' } }],
    ['inner type', { ...body, result: { ...inner, type: 'reservationAccepted' } }],
    ['only accepted and slotEmpty', { accepted: true, result: { slotEmpty: true } }],
    ['empty body', undefined],
  ])('%s is not a receipt', (_, data) => {
    expect(isCancelReceipt(data, wire)).toBe(false);
  });
});

describe('recovery result GET', () => {
  it('reads its own shape (no accepted / receiptRecorded) as a receipt', () => {
    expect(readCancelResult(resultBody(wire.requestId, committed), wire)).toBe('receipt');
  });

  it('PENDING is pending — never failure, refusal or completion', () => {
    expect(readCancelResult({ status: 'PENDING', requestId: wire.requestId }, wire)).toBe('pending');
    expect(readCancelResult({ status: 'PENDING', requestId: wire.requestId, phase: 'x' }, wire)).toBe('pending');
  });

  const body = resultBody(wire.requestId, committed);
  const inner = body.result as Record<string, unknown>;
  it.each([
    ['other UUID', { ...body, requestId: rev(778) }],
    ['PENDING for other UUID', { status: 'PENDING', requestId: rev(778) }],
    ['not ok', { ...body, ok: false }],
    ['other type', { ...body, type: 'reservationAccepted' }],
    ['other actor', { ...body, result: { ...inner, generalId: 2 } }],
    ['other slot', { ...body, result: { ...inner, turnIdx: 5 } }],
    ['other revision', { ...body, result: { ...inner, reservationRevision: rev(9) } }],
    ['slot not empty', { ...body, result: { ...inner, slotEmpty: false } }],
    ['wrong kind when present', { ...body, result: { ...inner, commandKind: 'RESERVED_TURN' } }],
    ['malformed', 'RESOLVED'],
  ])('%s is unknown', (_, data) => {
    expect(readCancelResult(data, wire)).toBe('unknown');
  });
});

describe('refusal envelopes', () => {
  it('controller BLOCKED keeps code and retryable as sent', () => {
    expect(readCancelRejection({ status: 'BLOCKED', code: 'WORLD_EXECUTING', accepted: false, receiptRecorded: false, retryable: true }))
      .toEqual({ kind: 'blocked', code: 'WORLD_EXECUTING', retryable: true });
  });

  it('auth / admission filter envelope has only error code and message', () => {
    expect(readCancelRejection({ error: { code: 'AUTH_REQUIRED', message: '로그인' } })).toEqual({ kind: 'filter', code: 'AUTH_REQUIRED' });
  });

  it.each([
    ['BLOCKED without receiptRecorded', { status: 'BLOCKED', code: 'X', accepted: false, retryable: false }],
    ['BLOCKED without retryable', { status: 'BLOCKED', code: 'X', accepted: false, receiptRecorded: false }],
    ['BLOCKED that claims acceptance', { status: 'BLOCKED', code: 'X', accepted: true, receiptRecorded: false, retryable: false }],
    ['filter with acceptance fields', { error: { code: 'X', message: '' }, accepted: false }],
    ['string error', { error: 'Bad Gateway' }],
    ['proxy text', 'Service Unavailable'],
    ['no body', undefined],
  ])('%s is not a known refusal', (_, data) => {
    expect(readCancelRejection(data)).toBeNull();
  });
});
