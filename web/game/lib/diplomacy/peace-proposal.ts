import { api } from '@/lib/api';
import { submitCommandAndAwaitResult } from '@/lib/commandSubmit';
import type { MailOutcome } from '@/lib/mail/use-mail';

export const peaceOfferOptions = (generalId: number) => api.legacyCourtOptions('court.offerPeace', generalId);

export const submitPeaceOffer = (generalId: number, targetNationId: number) =>
    submitCommandAndAwaitResult(() => api.courtLegacy('court.offerPeace', generalId, { targetNationId }));

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
