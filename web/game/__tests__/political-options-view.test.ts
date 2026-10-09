import { describe, expect, it } from 'vitest';
import { NO_ELIGIBLE_TARGET, politicalTargetVerdict } from '@/lib/political-options-view';
import type { PoliticalOption } from '@/lib/types';

type Target = NonNullable<PoliticalOption['targets']>[number];

const sameNation: Target = { generalId: 8, name: '관우', available: false, code: 'SAME_NATION_REQUIRED', reason: '같은 세력의 장수를 선택해 주세요.' };
const noConsent: Target = { generalId: 9, name: '장비', available: false, code: 'CONSENT_REQUIRED', reason: '대상 장수의 수락이 필요합니다.' };
const ok: Target = { generalId: 10, name: '조운', available: true };

/** What the server sends today: when nothing is selectable, the first candidate's failure is promoted. */
function served(targets: Target[]): PoliticalOption {
    const first = targets[0];
    const any = targets.some(t => t.available);
    return {
        inputId: 'action.abdicate', available: any, targets,
        code: any ? null : first?.code ?? 'CONSENT_REQUIRED',
        reason: any ? null : first?.reason ?? '대상 장수의 수락이 필요합니다.',
    };
}

describe('politicalTargetVerdict', () => {
    it('does not let candidate order decide the top-level reason when every candidate fails differently', () => {
        const a = politicalTargetVerdict(served([sameNation, noConsent]));
        const b = politicalTargetVerdict(served([noConsent, sameNation]));
        expect(a).toEqual({ available: false, code: null, reason: NO_ELIGIBLE_TARGET });
        expect(b).toEqual(a);
    });

    it('does not promote a candidate-specific failure even when every candidate shares it', () => {
        const other = { ...noConsent, generalId: 11, name: '마초' };
        expect(politicalTargetVerdict(served([noConsent, other])))
            .toEqual({ available: false, code: null, reason: NO_ELIGIBLE_TARGET });
    });

    it('keeps the server text of an actor-common failure every candidate shares', () => {
        const notLord = (generalId: number): Target => ({ generalId, name: `장수${generalId}`, available: false,
            code: 'NOT_LORD', reason: '주공만 세력을 해산할 수 있습니다.' });
        expect(politicalTargetVerdict(served([notLord(9), notLord(8)])))
            .toEqual({ available: false, code: 'NOT_LORD', reason: '주공만 세력을 해산할 수 있습니다.' });
    });

    it('keeps actor-wide failures that arrive without candidates', () => {
        expect(politicalTargetVerdict({ inputId: 'action.abdicate', available: false, targets: [],
            code: 'STATE_UNAVAILABLE', reason: '현재 세력·명망 상태를 확인할 수 없습니다.' }))
            .toEqual({ available: false, code: 'STATE_UNAVAILABLE', reason: '현재 세력·명망 상태를 확인할 수 없습니다.' });
        expect(politicalTargetVerdict({ inputId: 'action.abdicate', available: false,
            code: 'NOT_DELIVERED', reason: '아직 열리지 않은 행동입니다.' }))
            .toMatchObject({ available: false, code: 'NOT_DELIVERED', reason: '아직 열리지 않은 행동입니다.' });
    });

    it('uses the neutral sentence for an empty list instead of the server consent fallback', () => {
        expect(politicalTargetVerdict(served([])))
            .toEqual({ available: false, code: null, reason: NO_ELIGIBLE_TARGET });
    });

    it('never reports the action available when no candidate is selectable', () => {
        expect(politicalTargetVerdict({ inputId: 'action.abdicate', available: true, targets: [sameNation] }))
            .toEqual({ available: false, code: null, reason: NO_ELIGIBLE_TARGET });
    });

    it('passes the server verdict through when some candidate is selectable', () => {
        expect(politicalTargetVerdict(served([sameNation, ok, noConsent])))
            .toEqual({ available: true, code: null, reason: null });
    });

    it('follows a consent change between reads', () => {
        expect(politicalTargetVerdict(served([sameNation, noConsent])).available).toBe(false);
        const accepted: Target = { generalId: 9, name: '장비', available: true };
        expect(politicalTargetVerdict(served([sameNation, accepted]))).toEqual({ available: true, code: null, reason: null });
        const declined: Target = { ...noConsent, code: 'CONSENT_DECLINED', reason: '대상 장수가 거절했습니다.' };
        expect(politicalTargetVerdict(served([declined, sameNation])))
            .toEqual({ available: false, code: null, reason: NO_ELIGIBLE_TARGET });
    });
});
