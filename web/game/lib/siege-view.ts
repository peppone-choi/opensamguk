// 공성(P-C02)의 보기 모델. `/api/sieges` · `/api/road-forts`를 화면 줄로 바꾼다. React 없음.
// 판정 값(강공 가능 · 항복 권고 수락 · 급식)은 서버가 엔진과 같은 SiegeRules 로 준다 — 화면은 다시 계산하지 않는다.
import type { ChipTone } from '@opensamguk/ui';
import type { RoadFort, Siege } from './campaign-reads';

/**
 * 강공은 포위 몇 순째부터인가 — 서버 `CampaignBalance.ASSAULT_MIN_SIEGE_TURNS`(logic/.../war/CampaignBalance.kt)와 같은 수.
 * 포위 읽기에 기준 순(K6-08 보강 `assaultReadyAt`)이 오면 그 값으로 바꾼다. 강공 거절 판정은 늘 서버가 한다.
 */
export const ASSAULT_MIN_SIEGE_TURNS = 3;

/** 서버 `SiegeService.Failure.ASSAULT_NOT_READY` — 사유 코드와 서버 문장 그대로(도움말이 이 코드로 「이렇게 하면 됩니다」를 읽는다). */
export const ASSAULT_NOT_READY = { code: 'ASSAULT_NOT_READY', reason: '포위한 지 한 달(3순)이 지나야 강공할 수 있습니다.' } as const;
/**
 * canAct = 포위 중이고 지휘관이 나(서버 SiegeReader). 포위 중인데 canAct 가 거짓이면 지휘관이 아닌 것 — 서버 사유 코드가 없어
 * 문장만 둔다(코드를 지어 도움말 원장에 없는 사유를 찾게 하지 않는다).
 */
export const NOT_COMMANDER = { reason: '포위 지휘관만 명령할 수 있습니다.' } as const;
/** 끝난 포위(함락 · 해제)에는 명령이 없다. */
export const SIEGE_ENDED = { reason: '끝난 포위입니다.' } as const;

const STATUS: Readonly<Record<string, { readonly label: string; readonly tone: ChipTone }>> = {
    ACTIVE: { label: '포위 중', tone: 'bronze' },
    FALLEN: { label: '함락', tone: 'moss' },
    LIFTED: { label: '포위 해제', tone: 'neutral' },
};
const UNKNOWN_STATUS = { label: '알 수 없음', tone: 'neutral' } as const;

/** 포위 기록 사건 — 모르는 코드는 원문 대신 「공성 사건」. */
const EVENT: Readonly<Record<string, string>> = {
    START: '포위 시작', TURN: '포위 유지', FALLEN: '함락', LIFTED: '포위 해제',
    DEMAND_ACCEPTED: '항복 권고 수락', DEMAND_REFUSED: '항복 권고 거절',
    ASSAULT_CAPTURED: '강공 함락', ASSAULT_REPULSED: '강공 격퇴',
};
const PHASE = ['상순', '중순', '하순'];
const number = new Intl.NumberFormat('ko-KR');

export const UNKNOWN_COUNTY = '이름 모를 현';
export const UNKNOWN_PROVINCE = '이름 모를 구역';

export function siegeStatus(status: string): { readonly label: string; readonly tone: ChipTone } {
    return STATUS[status] ?? UNKNOWN_STATUS;
}

/** 사기(0–10000, 서버 SiegeMorale.MAX_MORALE) → 「n%」. */
export function moralePercent(morale: number): string {
    return `${Math.round(morale / 100)}%`;
}

/** 강공까지 — 끝난 포위는 null, 됐으면 0. */
export function turnsToAssault(siege: Pick<Siege, 'status' | 'turns'>): number | null {
    if (siege.status !== 'ACTIVE') return null;
    return Math.max(0, ASSAULT_MIN_SIEGE_TURNS - siege.turns);
}

/** 목록 · 머리 부제의 「포위 n순째 · 강공까지 k순」. */
export function siegeProgressText(siege: Pick<Siege, 'status' | 'turns'> & Partial<Pick<Siege, 'canAssault'>>): string {
    const left = turnsToAssault(siege);
    if (left == null) return `포위 ${siege.turns}순`;
    if (left > 0) return `포위 ${siege.turns}순째 · 강공까지 ${left}순`;
    return `포위 ${siege.turns}순째 · ${siege.canAssault === false ? '강공 조건 확인 필요' : '강공 가능'}`;
}

export interface KvCell {
    readonly label: string;
    readonly value: string;
    /** 경고 색(급식 부족 등). */
    readonly warn?: boolean;
}

/** 형편 6칸(보드 siege_kv) — 성 안 수비 · 성 안 사기 · 성 안 쌀 · 민심 · 포위 병력 · 포위군 급식. 못 읽은 값은 「?」. */
export function siegeCells(siege: Siege): readonly KvCell[] {
    return [
        { label: '성 안 수비', value: `${number.format(siege.garrison)}명` },
        { label: '성 안 사기', value: moralePercent(siege.morale) },
        { label: '성 안 쌀', value: siege.grain == null ? '?' : number.format(siege.grain) },
        { label: '민심', value: number.format(Math.round(siege.trust)) },
        { label: '포위 병력', value: siege.besiegerTroops == null ? '?' : `${number.format(siege.besiegerTroops)}명` },
        { label: '포위군 급식', value: siege.besiegerFed == null ? '?' : siege.besiegerFed ? '받는 중' : '부족', warn: siege.besiegerFed === false },
    ];
}

export interface TimelineRow {
    readonly when: string;
    readonly what: string;
    readonly detail: string | null;
}

/** 포위 기록 한 줄 — 서버 timeline 은 열린 맵이라 아는 키만 읽는다(코드 원문 · 틀린 형은 버린다). */
export function timelineRow(entry: Readonly<Record<string, unknown>>): TimelineRow {
    const { year, month, phase, event, morale, garrison } = entry;
    const when = typeof year === 'number' && typeof month === 'number' && typeof phase === 'number'
        ? `${year}년 ${month}월 ${PHASE[phase - 1] ?? `${phase}순`}` : '때 모름';
    const what = typeof event === 'string' && EVENT[event] ? EVENT[event] : '공성 사건';
    const parts = [
        typeof morale === 'number' ? `사기 ${moralePercent(morale)}` : null,
        typeof garrison === 'number' ? `수비 ${number.format(garrison)}` : null,
    ].filter((p): p is string => p != null);
    return { when, what, detail: parts.length ? parts.join(' · ') : null };
}

export interface FortRow {
    readonly id: string;
    readonly name: string;
    readonly ownerName: string;
    readonly mine: boolean;
    readonly cells: readonly KvCell[];
    readonly besieged: boolean;
    readonly canBesiege: boolean;
}

/** 도로 보루 한 줄 — 구역 id · 좌표 · 세력 번호는 화면에 내지 않는다(이름을 못 풀면 「이름 모를 구역」 · 「어느 세력」). */
export function fortRow(fort: RoadFort, names: {
    readonly province: (id: string) => string | null | undefined;
    readonly nation: (id: number) => string | null | undefined;
    readonly myNationId: number | null;
}): FortRow {
    const besieged = fort.besiegerGeneralId != null;
    return {
        id: fort.id,
        name: `보루 — ${names.province(fort.provinceId) ?? UNKNOWN_PROVINCE}`,
        ownerName: names.nation(fort.ownerNationId) ?? '어느 세력',
        mine: names.myNationId != null && fort.ownerNationId === names.myNationId,
        cells: [
            { label: '성벽', value: number.format(fort.wall) },
            { label: '수비', value: number.format(fort.garrison) },
            { label: '포위 진척', value: besieged ? `${fort.siegeProgress}%` : '포위 없음' },
        ],
        besieged,
        canBesiege: fort.canBesiege,
    };
}

/** 강공은 서버 판정만 소비한다. 항복 권고의 기존 지휘관 조건은 canAct로 본다. */
export function siegeVerdict(siege: Pick<Siege, 'status' | 'turns' | 'canAct' | 'canAssault' | 'assaultCode' | 'assaultReason'>,
    inputId: 'action.assault' | 'action.demandSurrender'):
    { readonly available: boolean; readonly code?: string; readonly reason?: string } {
    if (siege.status !== 'ACTIVE') return { available: false, ...SIEGE_ENDED };
    if (!siege.canAct) return { available: false, ...NOT_COMMANDER };
    if (inputId === 'action.assault') return siege.canAssault ? { available: true } : {
        available: false,
        ...(siege.assaultCode ? { code: siege.assaultCode } : {}),
        reason: siege.assaultReason ?? '강공 조건을 확인할 수 없습니다.',
    };
    return { available: true };
}
