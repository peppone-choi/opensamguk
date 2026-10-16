import { api } from '@/lib/api';
import { submitCommandAndAwaitResult } from '@/lib/commandSubmit';
import type { MailOutcome } from '@/lib/mail/use-mail';
import type { CourtProposal } from './court-proposal';

export const PEACE_OFFER: CourtProposal = {
    inputId: 'court.offerPeace',
    label: '종전 제의',
    options: (generalId) => api.legacyCourtOptions('court.offerPeace', generalId),
    submit: (generalId, targetNationId) =>
        submitCommandAndAwaitResult(() => api.courtLegacy('court.offerPeace', generalId, { targetNationId })),
    text: {
        notRuler: '소속 세력의 군주만 종전 제의를 보낼 수 있습니다.',
        rejected: '종전 제의를 접수하지 못했습니다.',
        reserved: '종전 제의를 예약했습니다. 다음 개인 턴에 상대 세력으로 서신을 보냅니다.',
        applied: '종전 제의 서신을 보냈습니다. 상대의 수락 전까지 교전은 계속됩니다.',
        pending: '처리가 늦어지고 있습니다. 결과와 외교 서신을 다시 확인해 주세요.',
        failed: '종전 제의 결과를 확인하지 못했습니다. 다시 조회해 주세요.',
    },
};

export async function answerPeaceProposal(messageId: number, generalId: number, accept: boolean): Promise<{
    notice: MailOutcome; refreshDiplomacy: boolean;
}> {
    const out = await submitCommandAndAwaitResult(() => accept
        ? api.messageAccept(messageId, generalId) : api.messageDecline(messageId, generalId));
    if (out.status === 'applied') {
        return { notice: { kind: 'ok', text: accept ? '종전 제의를 수락했습니다. 양 세력의 교전이 끝났습니다.' : '종전 제의를 거절했습니다.' },
            refreshDiplomacy: accept };
    }
    if (out.status === 'rejected') {
        return { notice: { kind: 'error', text: out.reason ?? '종전 제의에 답하지 못했습니다.',
            ...(out.code ? { code: out.code } : {}) }, refreshDiplomacy: false };
    }
    return { notice: { kind: 'info', text: '응답 처리 중입니다. 결과와 외교 서신을 다시 확인해 주세요.' },
        refreshDiplomacy: false };
}
