import { fetchGame } from '@/lib/api';
import { isPathServerId, readServerCookie } from '@/lib/serverGameUrl';
import type { EnlistmentOptionsResponse, IntakeOutcome, ReservedSlot } from '@/lib/types';
import { isUuid } from './reservation-cancel-contract';

// E04 출사 — options, the owned 12-slot ring with its calendar metadata, and the slot-addressed write.

const SLOT_COUNT = 12;
const MALFORMED_OPTIONS = '출사 정보를 확인하지 못했습니다.';
const MALFORMED_SLOTS = '순 정보를 확인하지 못해 예약하지 않았습니다.';

export class EnlistHttpError extends Error {
  constructor(readonly status: number) {
    super(status === 401 ? '로그인이 만료되었습니다. 다시 로그인해 주세요.'
      : status === 403 ? '이 장수로 출사할 권한이 없습니다.' : '출사 정보를 불러오지 못했습니다.');
  }
}

/** The actor or selected server changed before the write; nothing was posted. */
export class EnlistScopeChanged extends Error {
  constructor() { super('장수 또는 서버가 바뀌어 예약하지 않았습니다.'); }
}

export type EnlistOption = EnlistmentOptionsResponse['options'][number];

/** Server metadata exactly as sent (already turn-adjusted); a missing or null value stays null. */
export interface EnlistCalendar {
  readonly year: number | null;
  readonly month: number | null;
  readonly turnPhase: 1 | 2 | 3 | null;
  /** 'yyyy-MM-dd HH:mm:ss' in UTC+09:00 — the first slot's turn time. */
  readonly turnTime: string | null;
  /** Minutes per slot. */
  readonly turnTerm: number | null;
}

export interface EnlistSlotsRead {
  readonly generalId: number;
  readonly slots: readonly ReservedSlot[];
  readonly calendar: EnlistCalendar;
}

const positive = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) > 0;
const record = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value);
const optionKey = (row: Record<string, unknown>) => row.mode === 'RANDOM' ? 'RANDOM' : `${String(row.mode)}:${String(row.targetId)}`;

/** Server chosen by this tab — URL path or `?server=` first, as fetchGame sends it, then the `sam_server` cookie. */
export function selectedEnlistServer(): string {
  if (typeof window === 'undefined') return '';
  const [game, pathServer] = window.location.pathname.split('/').slice(1);
  const fromUrl = game !== 'game' ? null
    : isPathServerId(pathServer ?? '') ? pathServer : new URLSearchParams(window.location.search).get('server');
  return fromUrl ?? readServerCookie() ?? '';
}

function validOption(value: unknown): value is EnlistOption {
  if (!record(value)) return false;
  if (!['RANDOM', 'NATION', 'GENERAL'].includes(String(value.mode)) || typeof value.label !== 'string') return false;
  if (value.mode !== 'RANDOM' && !positive(value.targetId)) return false;
  if (!record(value.availability)) return false;
  const availability = value.availability;
  return ['AVAILABLE', 'BLOCKED'].includes(String(availability.status))
    && (availability.code == null || typeof availability.code === 'string')
    && (availability.reason == null || typeof availability.reason === 'string');
}

export async function readEnlistOptions(generalId: number, signal?: AbortSignal): Promise<EnlistmentOptionsResponse> {
  const response = await fetchGame(`/api/commands/enlistment-options?generalId=${generalId}`, { cache: 'no-store', signal });
  if (!response.ok) throw new EnlistHttpError(response.status);
  const data = await response.json();
  if (data?.result !== true || data.inputId !== 'action.enlist' || data.maxReservedTurns !== SLOT_COUNT
      || !Array.isArray(data.options) || !data.options.every(validOption)) {
    throw new Error(MALFORMED_OPTIONS);
  }
  // Candidate identity is mode + original targetId; two rows with one identity cannot be told apart.
  const keys = (data.options as Record<string, unknown>[]).map(optionKey);
  if (new Set(keys).size !== keys.length) throw new Error(MALFORMED_OPTIONS);
  return data;
}

const TURN_TIME = /^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})$/;

// Protocol bounds of the backend `Int?` fields — not a game rule.
const INT_MAX = 2_147_483_647;
const intIn = (min: number, max: number) => (value: unknown): value is number =>
  Number.isInteger(value) && Number(value) >= min && Number(value) <= max;

function validTurnTime(value: unknown): value is string {
  if (typeof value !== 'string') return false;
  const m = TURN_TIME.exec(value);
  if (!m) return false;
  const [year, month, day, hour, minute, second] = m.slice(1).map(Number);
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1];
  return !!days && day >= 1 && day <= days && hour < 24 && minute < 60 && second < 60;
}

/**
 * Absent or null metadata is a legitimate unknown and stays null. A present value must fit its protocol domain;
 * anything else is a corrupt ring and fails closed instead of being shown as unknown.
 */
function calendarOf(data: Record<string, unknown>): EnlistCalendar {
  const field = <T>(key: string, valid: (value: unknown) => value is T): T | null => {
    const value = data[key];
    if (value == null) return null;
    if (!valid(value)) throw new Error(MALFORMED_SLOTS);
    return value;
  };
  return {
    year: field('year', intIn(1, INT_MAX)),
    month: field('month', intIn(1, 12)),
    turnPhase: field('turnPhase', intIn(1, 3)) as 1 | 2 | 3 | null,
    turnTime: field('turnTime', validTurnTime),
    // tickSeconds / 60 on the server; under a minute it is a real 0, and there is no upper cap.
    turnTerm: field('turnTerm', intIn(0, INT_MAX)),
  };
}

function validSlot(value: unknown): value is ReservedSlot {
  return record(value) && Number.isInteger(value.turnIdx) && Number(value.turnIdx) >= 0 && Number(value.turnIdx) < SLOT_COUNT
    && typeof value.action === 'string' && value.action.trim() !== '' && typeof value.brief === 'string' && record(value.arg)
    && isUuid(value.revision);
}

/** Strict ring read: owned body, unique 0–11 slots, full rows, in-domain metadata. Malformed or foreign is an error, never an empty ring. */
export function parseEnlistSlots(data: unknown, generalId: number): EnlistSlotsRead {
  if (!positive(generalId) || !record(data) || data.result !== true || data.generalId !== generalId
      || !Array.isArray(data.slots) || data.slots.length > SLOT_COUNT || !data.slots.every(validSlot)) {
    throw new Error(MALFORMED_SLOTS);
  }
  const slots = data.slots as ReservedSlot[];
  if (new Set(slots.map(slot => slot.turnIdx)).size !== slots.length) throw new Error(MALFORMED_SLOTS);
  return { generalId, slots: slots.map(slot => ({ ...slot, arg: { ...slot.arg } })), calendar: calendarOf(data) };
}

export async function readEnlistSlots(generalId: number, signal?: AbortSignal): Promise<EnlistSlotsRead> {
  if (!positive(generalId)) throw new Error(MALFORMED_SLOTS);
  const response = await fetchGame(`/api/reserved-commands?generalId=${generalId}`, { cache: 'no-store', signal });
  if (!response.ok) throw new EnlistHttpError(response.status);
  return parseEnlistSlots(await response.json(), generalId);
}

export interface EnlistSendGuards {
  /** Runs on the fresh ring right before the POST; return a reason to stop, or throw EnlistScopeChanged. */
  readonly preflight: (fresh: EnlistSlotsRead) => string | null;
  /** Called synchronously just before the POST leaves. */
  readonly onPost?: () => void;
  readonly signal?: AbortSignal;
}

/** Re-reads the ring, refuses an occupied slot, then posts the original server target to exactly `turnIdx`. */
export async function sendEnlist(generalId: number, option: EnlistOption, turnIdx: number, guards: EnlistSendGuards): Promise<IntakeOutcome> {
  if (!Number.isInteger(turnIdx) || turnIdx < 0 || turnIdx >= SLOT_COUNT) throw new Error(MALFORMED_SLOTS);
  if (option.availability.status !== 'AVAILABLE') throw new Error('출사할 수 없는 후보입니다.');
  if (option.mode !== 'RANDOM' && !positive(option.targetId)) throw new Error(MALFORMED_OPTIONS);
  const fresh = await readEnlistSlots(generalId, guards.signal);
  const stop = guards.preflight(fresh);
  if (stop) return { status: 'BLOCKED', reason: stop };
  if (fresh.slots.some(slot => slot.turnIdx === turnIdx)) {
    return { status: 'BLOCKED', reason: `${String(turnIdx + 1).padStart(2, '0')}순에는 이미 명령이 예약돼 있습니다. 덮어쓰지 않습니다.` };
  }
  guards.onPost?.();
  const response = await fetchGame(`/api/command/action.enlist?generalId=${generalId}&turnIdx=${turnIdx}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(option.mode === 'RANDOM' ? { mode: option.mode } : { mode: option.mode, targetId: option.targetId }),
  });
  if (!response.ok) throw new EnlistHttpError(response.status);
  return response.json();
}
