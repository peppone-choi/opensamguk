import { api } from '@/lib/api';
import { submitCommandAndAwaitResult } from '@/lib/commandSubmit';
import { DECLARE_WAR_POLICY } from './relations';

export const declareWarOptions = (generalId: number) => api.legacyCourtOptions('court.declareWar', generalId);

export const submitDeclareWar = (generalId: number, targetNationId: number) =>
    submitCommandAndAwaitResult(() => api.courtLegacy('court.declareWar', generalId, { targetNationId }));

/** 위험 확인 대화 본문 — 교전까지의 기간은 정책 상수에서 읽는다. 비용 · 사자 문구는 쓰지 않는다(서버 무소비). */
export function declareWarWarning(nationName: string, duringNonAggression: boolean): string {
    return `${nationName}에 선전포고합니다. 게임 시간 ${DECLARE_WAR_POLICY.termMonths}개월 뒤 교전이 시작됩니다.`
        + (duringNonAggression ? ' 맺고 있는 불가침은 선포와 함께 끝납니다.' : '')
        + ' 되돌리려면 상대가 종전 제의를 수락해야 합니다.';
}
