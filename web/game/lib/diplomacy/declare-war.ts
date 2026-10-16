import { api } from '@/lib/api';
import { submitCommandAndAwaitResult } from '@/lib/commandSubmit';
import type { CourtProposal } from './court-proposal';
import { DECLARE_WAR_POLICY } from './relations';

/** 위험 확인 대화 본문 — 교전까지의 기간은 정책 상수에서 읽는다. 비용 · 사자 문구는 쓰지 않는다(서버 무소비). */
export function declareWarWarning(nationName: string, duringNonAggression: boolean): string {
    return `${nationName}에 선전포고합니다. 게임 시간 ${DECLARE_WAR_POLICY.termMonths}개월 뒤 교전이 시작됩니다.`
        + (duringNonAggression ? ' 맺고 있는 불가침은 선포와 함께 끝납니다.' : '')
        + ' 되돌리려면 상대가 종전 제의를 수락해야 합니다.';
}

/** 선전포고 — 옵션은 legacy-court-options, 제출 본문은 정확히 { targetNationId }, 보내기 전에 위험 확인. */
export const DECLARE_WAR: CourtProposal = {
    inputId: 'court.declareWar',
    label: '선전포고',
    options: (generalId) => api.legacyCourtOptions('court.declareWar', generalId),
    submit: (generalId, targetNationId) =>
        submitCommandAndAwaitResult(() => api.courtLegacy('court.declareWar', generalId, { targetNationId })),
    text: {
        notRuler: '소속 세력의 군주만 선전포고할 수 있습니다.',
        rejected: '선전포고를 접수하지 못했습니다.',
        reserved: '선전포고를 예약했습니다. 다음 개인 턴에 선포합니다.',
        applied: `선전포고했습니다. 게임 시간 ${DECLARE_WAR_POLICY.termMonths}개월 뒤 교전이 시작됩니다.`,
        pending: '처리가 늦어지고 있습니다. 결과와 외교 관계를 다시 확인해 주세요.',
        failed: '선전포고 결과를 확인하지 못했습니다. 다시 조회해 주세요.',
    },
    confirm: {
        title: '선전포고합니다', action: '선전포고',
        message: (nationName, relation) => declareWarWarning(nationName, relation === 'nonAggression'),
    },
};
