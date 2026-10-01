import { fetchGame } from '@/lib/api';
import type { EnlistmentOptionsResponse, IntakeOutcome, ReservedCommandsResponse } from '@/lib/types';

export class EnlistHttpError extends Error {
  constructor(readonly status: number) {
    super(status === 401 ? '로그인이 만료되었습니다. 다시 로그인해 주세요.'
      : status === 403 ? '이 장수로 출사할 권한이 없습니다.' : '출사 정보를 불러오지 못했습니다.');
  }
}

export type EnlistOption = EnlistmentOptionsResponse['options'][number];

function validOption(value: unknown): value is EnlistOption {
  if (!value || typeof value !== 'object') return false;
  const row = value as Record<string, unknown>;
  if (!['RANDOM', 'NATION', 'GENERAL'].includes(String(row.mode)) || typeof row.label !== 'string') return false;
  if (row.mode !== 'RANDOM' && (!Number.isSafeInteger(row.targetId) || Number(row.targetId) <= 0)) return false;
  if (!row.availability || typeof row.availability !== 'object') return false;
  const availability = row.availability as Record<string, unknown>;
  return ['AVAILABLE', 'BLOCKED'].includes(String(availability.status))
    && (availability.code == null || typeof availability.code === 'string')
    && (availability.reason == null || typeof availability.reason === 'string');
}

export async function readEnlistOptions(generalId: number, signal?: AbortSignal): Promise<EnlistmentOptionsResponse> {
  const response = await fetchGame(`/api/commands/enlistment-options?generalId=${generalId}`, { cache: 'no-store', signal });
  if (!response.ok) throw new EnlistHttpError(response.status);
  const data = await response.json();
  if (data?.result !== true || data.inputId !== 'action.enlist' || data.maxReservedTurns !== 12
      || !Array.isArray(data.options) || !data.options.every(validOption)) {
    throw new Error('출사 정보를 확인하지 못했습니다.');
  }
  return data;
}

export async function sendEnlist(generalId: number, option: EnlistOption): Promise<IntakeOutcome> {
  if (option.availability.status !== 'AVAILABLE') throw new Error('출사할 수 없는 후보입니다.');
  // Read the first slot before writing; E04 does not silently replace an existing order.
  const slotsResponse = await fetchGame(`/api/reserved-commands?generalId=${generalId}`, { cache: 'no-store' });
  if (!slotsResponse.ok) throw new EnlistHttpError(slotsResponse.status);
  const slots: ReservedCommandsResponse = await slotsResponse.json();
  if (slots.result !== true || slots.generalId !== generalId || !Array.isArray(slots.slots)
      || !slots.slots.every(slot => slot && Number.isInteger(slot.turnIdx) && slot.turnIdx >= 0 && slot.turnIdx < 12)) throw new Error('순 정보를 확인하지 못해 예약하지 않았습니다.');
  if (slots.slots.some(slot => slot.turnIdx === 0)) {
    return { status: 'BLOCKED', reason: '1순에는 이미 명령이 예약돼 있습니다. 작전실에서 순을 확인해 주세요.' };
  }
  // Until K4-02 provides slot selection, the approved fallback is the first slot.
  const response = await fetchGame(`/api/command/action.enlist?generalId=${generalId}&turnIdx=0`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(option.mode === 'RANDOM' ? { mode: option.mode } : { mode: option.mode, targetId: option.targetId }),
  });
  if (!response.ok) throw new EnlistHttpError(response.status);
  return response.json();
}
