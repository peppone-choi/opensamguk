// E04 출사 view model — pure: candidate identity, per-slot calendar labels, selection, gate and receipt text.
import type { InputAvailability } from '@opensamguk/ui';
import { momentFrom, momentLabel, phaseIndex, PHASES_PER_MONTH, PHASES_PER_YEAR } from '@/lib/season';
import type { ReservedSlot } from '@/lib/types';

export const ENLIST_INPUT = 'action.enlist';
const KST_MS = 9 * 60 * 60 * 1000;

interface Candidate { readonly mode: 'RANDOM' | 'NATION' | 'GENERAL'; readonly targetId?: number; readonly label: string;
  readonly availability: { readonly status: 'AVAILABLE' | 'BLOCKED'; readonly code?: string; readonly reason?: string } }
interface Calendar { readonly year: number | null; readonly month: number | null; readonly turnPhase: 1 | 2 | 3 | null;
  readonly turnTime: string | null; readonly turnTerm: number | null }
interface Slot { readonly turnIdx: number; readonly state: 'empty' | 'reserved' | 'blocked'; readonly name?: string | null }

export type ReceiptStatus = 'submitting' | 'pending' | 'reserved' | 'applied' | 'unknown' | 'rejected';
export interface EnlistReceipt {
  readonly id: number;
  readonly status: ReceiptStatus;
  readonly turnIdx: number;
  readonly candidate: string;
  readonly reason?: string;
  readonly code?: string;
}

/** Identity of a choice: mode + the server's original targetId, never a list position. */
export function candidateKey(option: Pick<Candidate, 'mode' | 'targetId'>): string {
  return option.mode === 'RANDOM' ? 'RANDOM' : `${option.mode}:${option.targetId}`;
}

export const slotNo = (turnIdx: number) => `${String(turnIdx + 1).padStart(2, '0')}순`;

/**
 * Labels for slot `turnIdx`. The server metadata already describes slot 0, so only the slot offset is added:
 * one phase per slot for the calendar, `turnTerm` minutes per slot for the UTC+09:00 clock. Missing values stay null.
 */
export function slotWhen(calendar: Calendar, turnIdx: number): { readonly when: string | null; readonly at: string | null } {
  const moment = momentFrom(calendar.month, calendar.turnPhase);
  let when: string | null = null;
  if (moment && calendar.year != null) {
    const absolute = phaseIndex(moment) + turnIdx;
    const inYear = absolute % PHASES_PER_YEAR;
    const shifted = momentFrom(Math.floor(inYear / PHASES_PER_MONTH) + 1, (inYear % PHASES_PER_MONTH) + 1);
    if (shifted) when = `${calendar.year + Math.floor(absolute / PHASES_PER_YEAR)}년 ${momentLabel(shifted)}`;
  }
  let at: string | null = null;
  if (calendar.turnTime && (turnIdx === 0 || calendar.turnTerm != null)) {
    const base = Date.parse(`${calendar.turnTime.replace(' ', 'T')}+09:00`);
    if (Number.isFinite(base)) {
      const local = new Date(base + turnIdx * (calendar.turnTerm ?? 0) * 60_000 + KST_MS);
      at = `${String(local.getUTCHours()).padStart(2, '0')}:${String(local.getUTCMinutes()).padStart(2, '0')}`;
    }
  }
  return { when, at };
}

export function withCalendar<T extends { readonly turnIdx: number }>(slots: readonly T[], calendar: Calendar): (T & { when: string | null; at: string | null })[] {
  return slots.map(slot => ({ ...slot, ...slotWhen(calendar, slot.turnIdx) }));
}

/** Stable comparison of the metadata a selection was made against. */
export const calendarKey = (calendar: Calendar) =>
  JSON.stringify([calendar.year, calendar.month, calendar.turnPhase, calendar.turnTime, calendar.turnTerm]);

/** A held choice stays even when it became occupied (it then blocks); otherwise the first empty slot. */
export function effectiveSlot(slots: readonly Slot[], explicit: number | null): number | null {
  if (explicit != null) return explicit;
  return slots.find(slot => slot.state === 'empty')?.turnIdx ?? null;
}

/** Fresh read-back proof: the slot holds this exact enlist order with the original target. */
export function reservationMatches(slots: readonly ReservedSlot[], turnIdx: number, option: Pick<Candidate, 'mode' | 'targetId'>): boolean {
  const row = slots.find(slot => slot.turnIdx === turnIdx);
  if (!row || row.action !== ENLIST_INPUT || row.arg.mode !== option.mode) return false;
  const keys = Object.keys(row.arg).sort().join(',');
  return option.mode === 'RANDOM' ? keys === 'mode' : keys === 'mode,targetId' && row.arg.targetId === option.targetId;
}

export interface GateInput {
  readonly receipt: EnlistReceipt | null;
  readonly ready: boolean;
  readonly option: Candidate | undefined;
  readonly candidateMissing: boolean;
  readonly slot: Slot | undefined;
}

const blocked = (reason: string, code?: string): InputAvailability =>
  ({ inputId: ENLIST_INPUT, status: 'BLOCKED', reason, ...(code ? { code } : {}) });

export function enlistAvailability({ receipt, ready, option, candidateMissing, slot }: GateInput): InputAvailability {
  if (receipt?.status === 'rejected') return blocked(receipt.reason || '출사를 예약할 수 없습니다.', receipt.code);
  if (receipt?.status === 'submitting') return blocked('출사 예약을 처리하는 중입니다.');
  if (receipt?.status === 'pending' || receipt?.status === 'unknown') return blocked('처리 결과를 확인한 뒤 다시 예약해 주세요.');
  if (receipt) return blocked('이미 예약을 보냈습니다.');
  if (!ready) return blocked('후보와 12순을 불러오는 중입니다.');
  if (candidateMissing) return blocked('고른 후보가 목록에서 사라졌습니다. 다시 골라 주세요.');
  if (!option) return blocked('섬길 주공을 골라 주세요.');
  if (!slot) return blocked('12순이 모두 차 있습니다. 작전실에서 순을 비운 뒤 예약해 주세요.');
  if (slot.state !== 'empty') return blocked(`${slotNo(slot.turnIdx)}에는 이미 「${slot.name ?? '명령'}」이 예약돼 있습니다. 덮어쓰지 않습니다.`);
  return { inputId: ENLIST_INPUT, ...option.availability };
}

export function receiptText(receipt: EnlistReceipt): string {
  const at = slotNo(receipt.turnIdx);
  switch (receipt.status) {
    case 'submitting': return `${at}에 출사 예약을 보내는 중입니다.`;
    case 'reserved': return `출사 명령이 ${at}에 예약되었습니다.`;
    case 'applied': return '출사 명령이 실행되었습니다.';
    case 'pending': return '출사는 접수됐지만 처리 결과를 아직 확인하지 못했습니다.';
    case 'unknown': return `${at} 출사 예약 결과를 확인하지 못했습니다. 작전실 12순에서 확인해 주세요.`;
    case 'rejected': return receipt.reason || '출사를 예약할 수 없습니다.';
  }
}
