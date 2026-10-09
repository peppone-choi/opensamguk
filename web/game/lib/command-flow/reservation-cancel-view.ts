// Pure cancellation view model: translate state into band text, dialog and actions; no React or API calls.
// Do not expose raw server codes or turn an unknown refusal into permanent failure, success or an empty slot.
import type { CancelPhase, CancelTarget } from './reservation-cancel-state';

export type CancelAction = 'retrySend' | 'checkResult' | 'retryReadback' | 'abandon' | 'dismiss';

export const CANCEL_ACTION_LABEL: Readonly<Record<CancelAction, string>> = {
  retrySend: '같은 취소 다시 보내기',
  checkResult: '취소 결과 확인',
  retryReadback: '현재 목록 다시 읽기',
  abandon: '취소 그만두기',
  dismiss: '알림 닫기',
};

export interface CancelStatus {
  readonly tone: 'info' | 'ok' | 'warn';
  readonly text: string;
  readonly actions: readonly CancelAction[];
  /** A request for this status is in flight — its actions wait. */
  readonly busy: boolean;
}

/** The '예약 취소' entry of the current reservation band. hidden = nothing to offer (empty slot, account unknown). */
export type CancelEntry = { readonly kind: 'hidden' } | { readonly kind: 'available' } | { readonly kind: 'blocked'; readonly reason: string };

export interface CancelDialog {
  readonly open: boolean;
  readonly title: string;
  readonly message: string;
  readonly busy: boolean;
}

export const slotNo = (turnIdx: number) => String(turnIdx + 1).padStart(2, '0');

const REASONS: Readonly<Record<string, string>> = {
  CHANGED: '예약이 그새 바뀌어 취소를 보내지 않았습니다 — 현재 예약을 확인한 뒤 다시 골라 주세요.',
  REVISION_MISMATCH: '예약이 그새 바뀌어 취소하지 않았습니다 — 현재 목록을 다시 읽었습니다. 새 예약을 취소하려면 다시 골라 확인해 주세요.',
  IDEMPOTENCY_CONFLICT: '같은 요청 번호가 다른 요청에 쓰여 취소를 멈췄습니다 — 현재 목록을 확인해 주세요.',
  NOT_OWNER: '이 장수의 예약을 취소할 권한이 없습니다.',
  FORBIDDEN: '이 예약을 취소할 권한이 없습니다.',
  UNAUTHORIZED: '로그인이 필요합니다 — 다시 로그인한 뒤 확인해 주세요.',
  AUTH_REQUIRED: '로그인이 필요합니다 — 다시 로그인한 뒤 확인해 주세요.',
  SERVER_NOT_PUBLIC: '이 서버는 지금 들어갈 수 없습니다.',
  INVALID_SLOT: '서버가 취소 요청을 받지 않았습니다(순 확인 필요) — 현재 목록을 확인해 주세요.',
  INVALID_REVISION: '서버가 취소 요청을 받지 않았습니다(예약 확인 필요) — 현재 목록을 확인해 주세요.',
  INVALID_IDEMPOTENCY_KEY: '서버가 취소 요청을 받지 않았습니다(요청 번호 확인 필요).',
  INVALID_ARGUMENT: '서버가 취소 요청을 받지 않았습니다(요청 값 확인 필요).',
  WORLD_EXECUTING: '턴을 처리하는 중이라 다시 보낸 취소는 받지 않았습니다.',
  READ_FAILED: '현재 목록을 확인하지 못해 취소를 보내지 않았습니다 — 다시 눌러 주세요.',
  STORAGE: '이 탭에 취소 기록을 남길 수 없어 취소를 보내지 않았습니다 — 브라우저 저장 공간 설정을 확인해 주세요.',
  OCCUPIED: '이 순에 아직 끝나지 않은 취소 기록이 있어 새 취소를 보내지 않았습니다.',
  NO_UUID: '이 브라우저에서 요청 번호를 만들 수 없어 취소를 보내지 않았습니다.',
  BUSY: '이 순에 다른 요청을 보내는 중입니다 — 끝난 뒤 다시 눌러 주세요.',
};

export function cancelReason(code: string): string {
  return REASONS[code] ?? '서버가 취소를 받지 않았습니다 — 현재 목록을 확인해 주세요.';
}

const UNKNOWN_TEXT = '취소 결과를 아직 확인하지 못했습니다';
const CONFIRMED_TEXT = '이 예약의 취소를 확인했습니다';

export function cancelStatus(phase: CancelPhase): CancelStatus | null {
  switch (phase.kind) {
    case 'idle':
    case 'confirming':
    case 'preflight':
      return null;
    case 'sending':
      return { tone: 'info', text: '예약 취소를 보내는 중입니다…', actions: [], busy: true };
    case 'retryable':
      return { tone: 'warn', busy: phase.checking, actions: ['retrySend', 'abandon'],
        text: '턴을 처리하는 중이라 취소를 받지 않았습니다 — 잠시 뒤 같은 취소를 다시 보내 주세요. 저절로 다시 보내지 않습니다.' };
    case 'unknown': {
      // A later refusal is shown, but never as the answer to the earlier, ambiguous send.
      const later = phase.note === 'STORAGE' ? ' 이 탭에 기록을 남길 수 없어 다시 보내지 않았습니다.'
        : phase.note ? ' 다시 보낸 요청은 받지 않았지만, 앞서 보낸 요청의 결과는 아직 모릅니다.' : '';
      return { tone: 'warn', busy: phase.checking, actions: ['checkResult', 'retrySend'],
        text: `${UNKNOWN_TEXT} — 결과를 확인하거나 같은 취소를 다시 보내 주세요. 새 취소는 만들지 않습니다.${later}` };
    }
    case 'refreshing':
      return { tone: 'ok', text: `${CONFIRMED_TEXT} — 현재 목록 확인 중`, actions: [], busy: true };
    case 'readbackError':
      return { tone: 'warn', text: `${CONFIRMED_TEXT} — 현재 목록을 확인하지 못했습니다`, actions: ['retryReadback'], busy: false };
    case 'done':
      return { tone: 'ok', actions: ['dismiss'], busy: false, text: phase.after === 'empty'
        ? `${CONFIRMED_TEXT} — ${slotNo(phase.turnIdx)}순은 지금 비어 있습니다.`
        : `${CONFIRMED_TEXT} — ${slotNo(phase.turnIdx)}순에는 그 뒤 들어온 다른 예약이 있습니다. 그 예약은 그대로 둡니다.` };
    case 'blocked':
      return { tone: 'warn', text: cancelReason(phase.code), actions: ['dismiss'], busy: false };
  }
}

export interface CancelEntryInput {
  readonly verified: boolean;
  readonly server: string | null;
  readonly journalOk: boolean;
  readonly row: { readonly state: 'empty' | 'reserved' | 'blocked'; readonly revision: string | null; readonly name: string | null } | null;
  readonly phase: CancelPhase;
}

/** Only a verified, filled slot whose revision was read can start a new cancellation — and only with no intent pending. */
export function cancelEntry({ verified, server, journalOk, row, phase }: CancelEntryInput): CancelEntry {
  if (!verified || !row || row.state === 'empty') return { kind: 'hidden' };
  if (phase.kind !== 'idle' && phase.kind !== 'done' && phase.kind !== 'blocked') return { kind: 'hidden' };
  if (!server) return { kind: 'blocked', reason: '서버를 확인하지 못해 취소할 수 없습니다.' };
  if (!journalOk) return { kind: 'blocked', reason: cancelReason('STORAGE') };
  if (!row.revision || !row.name) return { kind: 'blocked', reason: '예약 정보를 확인하지 못했습니다 — 순 띠를 다시 불러와 주세요.' };
  return { kind: 'available' };
}

export function cancelDialog(phase: CancelPhase, rowRevision: string | null): CancelDialog {
  const target: CancelTarget | null = phase.kind === 'confirming' || phase.kind === 'preflight' ? phase.target : null;
  // A refreshed row with another revision closes the confirmation before it can be pressed.
  const open = !!target && (phase.kind === 'preflight' || rowRevision === target.revision);
  const no = slotNo(target?.turnIdx ?? 0);
  return {
    open,
    title: `${no}순 예약을 취소합니다`,
    message: target ? `${no}순의 「${target.sentence}」 예약을 지웁니다. 다른 순의 예약은 그대로입니다.` : '',
    busy: phase.kind === 'preflight',
  };
}
