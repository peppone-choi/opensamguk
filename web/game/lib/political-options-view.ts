// 대상이 있는 정치 행동(선양 · 결의)의 위쪽 판정 — 후보별 사유와 장수 공통 사유를 나눈다.
//
// 서버는 고를 대상이 하나도 없으면 첫 후보의 code · reason을 위쪽에 올린다. 후보 순서가 바뀌면 위쪽 사유도 바뀌고,
// 한 후보의 사정(같은 세력 아님 · 수락 없음)이 행동 전체의 사유처럼 보인다.
// 위쪽의 대상과 무관한 사유는 서버의 행위자 공통 판정으로 보존한다. 후보별 거절을 대신 쓰지 않는다.
// 후보별 사유는 후보에만 둔다. 계약(available · code · reason · targets)은 그대로 읽는다.
import type { PoliticalOption } from './types';

/** 고를 대상이 없고 장수 공통 사유도 없을 때 위쪽에 쓰는 문장. */
export const NO_ELIGIBLE_TARGET = '지금 선택할 수 있는 대상 장수가 없습니다.';

/** PoliticalFailure 중 대상 장수 하나의 사정인 것 — 위쪽 사유로 올리지 않는다. */
const TARGET_FAILURES: ReadonlySet<string> = new Set([
    'TARGET_NOT_FOUND', 'TARGET_NOT_HUMAN', 'SAME_NATION_REQUIRED', 'SAME_PROVINCE_REQUIRED',
    'CONSENT_REQUIRED', 'CONSENT_DECLINED', 'ALREADY_BOUND',
]);

export interface PoliticalTargetVerdict {
    readonly available: boolean;
    readonly code: string | null;
    readonly reason: string | null;
}

const s = (v: string | null | undefined): string | null => (v == null || v === '' ? null : v);

/** 대상 후보가 있는 정치 행동의 위쪽 판정. 고를 후보가 있으면 서버 판정 그대로다. */
export function politicalTargetVerdict(o: PoliticalOption): PoliticalTargetVerdict {
    const targets = o.targets ?? [];
    if (targets.some(t => t.available)) return { available: o.available, code: s(o.code), reason: s(o.reason) };
    const code = s(o.code);
    // The agreed server contract reserves non-target top-level codes for actor-common failures.
    const common = code != null && !TARGET_FAILURES.has(code);
    return common
        ? { available: false, code, reason: s(o.reason) }
        : { available: false, code: null, reason: NO_ELIGIBLE_TARGET };
}
