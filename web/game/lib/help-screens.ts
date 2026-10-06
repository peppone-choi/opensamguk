// 「이 화면」 도움말 — 화면이 보내는 입력(설계서 §6 「결정 화면」 열). 화면 → 입력은 화면이 아는 사실이라 여기 둔다.
// 단계 · 이름은 help-index(원장)에서 온다.
import type { InputKind } from './help';
import { HELP_INDEX, type HelpIndexEntry } from './help-index';
import { SLOT_PHASE_LABEL } from './help-labels';

export type HelpScreen =
    | 'war-room' | 'territory' | 'county' | 'commandery' | 'supply' | 'court' | 'diplomacy' | 'stratagem' | 'siege' | 'retinue' | 'corps' | 'intel'
    | 'enlist' | 'realm' | 'other';

export const SCREEN_LABEL: Record<HelpScreen, string> = {
    'war-room': '작전실',
    territory: '영지 — 배치 · 방침 · 공사',
    county: '현 상세',
    commandery: '군 내정 현황',
    supply: '창고망 · 보급',
    court: '조정',
    diplomacy: '외교',
    stratagem: '계책',
    siege: '공성',
    retinue: '부',
    corps: '군단',
    intel: '시야 · 첩보',
    enlist: '출사',
    realm: '세력',
    other: '이 화면',
};

const LIST: Partial<Record<HelpScreen, readonly string[]>> = {
    territory: ['placement.assign', 'policy.set', 'work.start', 'work.reduce'],
    // 현 상세(10-02 K4): 그 현의 배치 · 방침 · 공사 단추와 「다시 첩보」(CountyParts).
    county: ['placement.assign', 'policy.set', 'work.start', 'action.scout'],
    // 군 내정 현황(10-04 K4 #1274): 군 방침 「바꾸기」와 현 줄 「첩보」(CommanderyParts).
    commandery: ['policy.set', 'action.scout'],
    supply: ['action.transport'],
    court: ['court.dispatchReply', 'court.politicalConsent', 'court.dispatch', 'court.reward', 'court.releaseCorps', 'court.abandonCounty',
        'court.moveCapital', 'court.confiscate',
        // 관직 · 속관 · 추천(D32 · D43, /court/offices). 원장 PLANNED 라 화면 단추는 아직 없다(K8).
        'court.offerReply', 'court.officeNominate', 'court.officeNominationReview', 'court.officeNominationReply',
        'court.appointSubordinate', 'court.dismissSubordinate',
        // 지방 관직 · 봉신 · 자칭(/court/offices), 조서 · 조정 방침 · 칭제(/court/imperial). 모두 원장 PLANNED(K8 화면 단추 없음).
        'court.appoint', 'court.dismiss', 'court.foundVassal', 'court.amendVassal', 'court.endVassal',
        'court.officeClaim', 'court.officeRecognize',
        'court.edictPropose', 'court.edictReview', 'court.edictRegister', 'court.edictSeal', 'court.edictReply',
        'court.settlementPolicy', 'court.proclaimEmperor'],
    diplomacy: ['court.diplomacy', 'court.nonAggression', 'court.declareWar', 'court.offerPeace', 'court.breakNonAggression'],
    siege: ['action.assault', 'action.demandSurrender', 'action.siegeRoadFort'],
    retinue: ['action.search', 'action.employ', 'action.persuadeCaptive', 'action.gift', 'placement.assign'],
    corps: ['action.deploy', 'action.muster', 'action.scout', 'court.releaseCorps'],
    intel: ['action.scout'],
    enlist: ['action.enlist'],
    // 정체성 · 편제 전통 탭(/court/realm)의 바꾸는 결정 — 원장 PLANNED.
    realm: ['court.institution', 'court.identityAdopt', 'court.unitTraditionAdopt'],
};

export interface HelpGroup {
    readonly key: string;
    readonly label: string;
    readonly entries: readonly HelpIndexEntry[];
}

const PHASE_ORDER = ['FIELD', 'MOVE', 'POLITICS', 'SIEGE'] as const;

function byIds(ids: readonly string[]): HelpIndexEntry[] {
    return ids.map((id) => HELP_INDEX.find((e) => e.inputId === id)).filter((e): e is HelpIndexEntry => Boolean(e));
}

/** 직접 행동을 명령 목록 단계(설계 §5.1)로 나눈다. */
export function generalActionGroups(): HelpGroup[] {
    return PHASE_ORDER.map((phase) => ({
        key: phase,
        label: SLOT_PHASE_LABEL[phase],
        entries: HELP_INDEX.filter((e) => e.kind === 'GENERAL_ACTION' && e.phase === phase),
    }));
}

/** 「이 화면에서 하는 일」 묶음. 작전실은 직접 행동 전체를 단계별로, 나머지는 한 묶음. 없으면 빈 배열. */
export function screenGroups(screen: HelpScreen): HelpGroup[] {
    if (screen === 'war-room') return generalActionGroups();
    if (screen === 'stratagem') {
        return [{ key: screen, label: SCREEN_LABEL[screen], entries: HELP_INDEX.filter((e) => e.kind === 'STRATAGEM') }];
    }
    const ids = LIST[screen];
    return ids ? [{ key: screen, label: SCREEN_LABEL[screen], entries: byIds(ids) }] : [];
}

const KIND_ORDER: readonly InputKind[] = ['GENERAL_ACTION', 'PLACEMENT', 'POLICY', 'WORK', 'STRATAGEM', 'COURT_DECISION'];

/** 「분류」 — 입력 여섯 가지. */
export function kindGroups(): { kind: InputKind; entries: HelpIndexEntry[] }[] {
    return KIND_ORDER.map((kind) => ({ kind, entries: HELP_INDEX.filter((e) => e.kind === kind) }));
}

/** 화면에 딸린 입력 전체(시험 · 앵커 확인용). */
export function screenInputIds(screen: HelpScreen): string[] {
    return screenGroups(screen).flatMap((g) => g.entries.map((e) => e.inputId));
}

// 셸(모든 게임 화면)이 부르는 대응은 원장 표를 끌고 오지 않게 따로 둔다.
export { helpScreenOf } from './help-screen-of';
