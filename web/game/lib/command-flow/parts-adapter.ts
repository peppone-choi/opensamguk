// 명령 흐름 후보 → K3 공용 부품 타입(TargetCandidate · PersonOption · InputAvailability).
//
// 서버 옵션에 없는 칸은 채우지 않는다: 지도 칸 · 거리(U-01), 소속 · 자리 · 사람 여부(사람 고르기 읽기).
// 부품은 없는 칸을 그리지 않는다(K3 타입 보강 요청 2026-09-30 — cell · nation · location · isHuman 선택 칸).
import type { InputAvailability, PersonOption, TargetCandidate, TargetKind } from '@opensamguk/ui';
import { availabilityOf } from '../input-availability';
import type { ArgField, CommandOptions } from './options';

/** 서버가 사유 없이 막았을 때 — InputAction의 MISSING_REASON과 같은 글자. 사유를 지어내지 않는다. */
export const NO_REASON = '사유를 받지 못했습니다';

/** 지도 대상 종류 — 구역 · 현 목적지는 place, 군(첩보)은 jurisdiction. */
export function targetKindOf(field: ArgField): TargetKind | null {
    if (field.kind === 'province' || field.kind === 'county') return 'place';
    if (field.kind === 'commandery') return 'jurisdiction';
    return null;
}

export function toTargetCandidates(field: ArgField): TargetCandidate[] {
    const kind = targetKindOf(field);
    if (!kind) return [];
    return field.candidates.map((c) => ({
        targetKind: kind,
        targetId: c.value,
        ...(field.kind === 'province' ? { provinceId: c.value } : {}),
        available: c.available,
        ...(c.available ? {} : { reason: c.reason ?? NO_REASON }),
        name: c.label,
        ...(c.detail ? { sub: c.detail } : {}),
    }));
}

export function toPersonOptions(field: ArgField): PersonOption[] {
    if (field.kind !== 'person') return [];
    return field.candidates.map((c) => ({
        generalId: Number(c.value),
        name: c.label,
        groups: [],
        ...(c.available ? {} : { blockedReason: c.reason ?? NO_REASON }),
    }));
}

/** 흐름이 들고 있는 옵션 상태 — 읽는 중 · 실패 · 받은 것. */
export type OptionsLoad =
    | { readonly state: 'loading' }
    | { readonly state: 'error'; readonly message: string }
    | CommandOptions;

/**
 * 제출 단추의 입력 상태 — 공용 어댑터(availabilityOf)로. 빈 칸은 여기서 막지 않는다(누르면 빈 칸을 알린다).
 * 읽는 중 · 못 읽음 = 누를 수 있게 둔다(모름을 막힘으로 그리지 않는다, K0 2026-09-30). 서버 거절은 그 code · reason으로 막는다.
 */
export function submitAvailability(
    inputId: string,
    options: OptionsLoad | undefined,
    rejected?: { readonly code?: string | null; readonly reason?: string | null } | null,
): InputAvailability | null {
    const verdict = !options || options.state === 'loading' ? 'loading' as const : options.state === 'READY' ? options : null;
    return availabilityOf(inputId, { options: verdict, rejected });
}
