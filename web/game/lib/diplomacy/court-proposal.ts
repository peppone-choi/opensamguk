// 외교 칸에서 서버 옵션을 읽어 실제로 보내는 제의 한 종의 모양. 입력 ID · 요청 · 문구는 각 입력 모듈이 갖고,
// 화면(DiplomacyPanel)은 이 모양만 안다 — 옵션 판정 · 제출 · 거절 표시를 입력마다 다시 쓰지 않는다.
import type { CommandSubmitResult } from '@/lib/commandSubmit';
import type { OptionsVerdict } from '@/lib/input-availability';
import type { CourtActionId, CourtActionOptions } from '@/lib/types';
import type { RelationKind } from './relations';

export interface CourtProposal {
    readonly inputId: CourtActionId;
    readonly label: string;
    readonly options: (generalId: number) => Promise<CourtActionOptions>;
    readonly submit: (generalId: number, targetNationId: number) => Promise<CommandSubmitResult>;
    readonly text: {
        readonly notRuler: string; readonly rejected: string; readonly reserved: string;
        readonly applied: string; readonly pending: string; readonly failed: string;
    };
    /** 있으면 보내기 전에 위험 확인을 받는다. */
    readonly confirm?: {
        readonly title: string; readonly action: string;
        readonly message: (nationName: string, relation: RelationKind) => string;
    };
}

export type CourtOptionsState = CourtActionOptions | 'loading' | 'failed';

/** 한 세력 줄의 입력 판정 — 군주가 아님 · 읽는 중 · 읽기 실패 · 서버 선택지(없으면 옵션 전체 사유). */
export function courtVerdict(proposal: CourtProposal, options: CourtOptionsState, nationId: number, canAct: boolean): OptionsVerdict {
    if (!canAct) return { available: false, code: 'NOT_RULER', reason: proposal.text.notRuler };
    if (typeof options === 'string') return { available: false, code: 'STATE_UNAVAILABLE', reason: options === 'loading'
        ? `${proposal.label} 가능 여부를 확인하고 있습니다.` : `${proposal.label} 가능 여부를 불러오지 못했습니다.` };
    const choice = options.choices.find((it) => it.arguments.targetNationId === nationId);
    return choice ? { available: choice.available, code: choice.code, reason: choice.reason }
        : { available: false, code: options.code, reason: options.reason };
}

export function canChoose(options: CourtOptionsState, nationId: number): boolean {
    return typeof options !== 'string' && options.choices.some((it) => it.arguments.targetNationId === nationId && it.available);
}
