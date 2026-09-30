// 부(府) 편성 화면(P-R01)의 보기 모델 — 조회 응답을 화면 줄로 합친다. React 없음(단위 테스트로 고정한다).
//
// 합치는 것: `/api/retinue`(인물 · 부대 카드) + `/api/posts`(배치 자리) + `/api/yuedan`(명망 · 코스트).
// 두 조회는 같은 부 카드 id 를 쓴다(`CampReader.retinue` 의 retainerId = `DomesticViews.posts` 의 cardId).
// 설계서 §3 P-R01: 화면의 「자리」는 배치 원장 자리 하나다 — `/api/retinue` 의 roleLabel(RetainerRules 역할)은 쓰지 않는다(Q9).
// 서버가 주지 않는 칸(유일 · 공용, 결속 일곱 가지, 계책 기여)은 만들지 않는다 — 화면이 「서버 대기」로 그린다.

import type { Aptitudes, Blocked, Bond, FiveStats, PersonCard, PlacementCard, Posts, Retinue, UnitCard, Yuedan } from './campaign-reads';

/** 부 이름 — 가장 높은 자리에 따라 막부 → 군부 → 주부 …(ADR-LITE-057 2026-09-26). 자리 목록(2층 관직)이 오기 전에는 모두 막부다. */
export function buName(generalName: string, serverName?: string | null): string {
    if (serverName) return serverName; // 계약판 K8-16 `me.buName` 이 오면 그 값
    return `${generalName}의 막부`;
}

export type RetinueSort = 'registered' | 'cost' | 'loyalty' | 'departure';
export const RETINUE_SORT_LABEL: Readonly<Record<RetinueSort, string>> = {
    registered: '등록순',
    cost: '코스트',
    loyalty: '충성',
    departure: '이탈 판정',
};

export type Tone = 'moss' | 'rust' | 'neutral';

/** 충성 칩 색 — 지금 작전실 명부와 같은 문턱(`GeneralRoster.tsx:14`: 80 이상 이끼 · 50 미만 적갈). 문턱을 새로 짓지 않는다. */
export function loyaltyTone(loyalty: number): Tone {
    if (loyalty >= 80) return 'moss';
    if (loyalty < 50) return 'rust';
    return 'neutral';
}

export interface PostView {
    /** 지금 앉은 자리(「현령 · 장사현」). 없으면 null → 「미배치」. */
    readonly active: string | null;
    /** 부임 중 · 대기 같은 진행 상태 원문 라벨이 아닌, 다음 턴부터 바뀔 자리. */
    readonly pending: string | null;
    /** 배치할 수 있는 카드인가. `/api/posts` 를 못 받았으면 null(모름 — 단추 상태를 짓지 않는다). */
    readonly placeable: boolean | null;
    readonly blocked: Blocked | null;
}

export interface RetinueRow {
    readonly retainerId: number;
    readonly generalId: number | null;
    readonly name: string;
    readonly picture: string | null;
    readonly imageServer: number;
    readonly loyalty: number;
    readonly loyaltyTone: Tone;
    readonly cost: number | null;
    readonly departureOrder: number | null;
    readonly bonds: readonly Bond[];
    readonly stats: FiveStats | null;
    readonly aptitudes: Aptitudes | null;
    readonly post: PostView;
    readonly troops: number;
}

function placeLabel(label: string, target: { label?: string | null } | null | undefined): string {
    const where = target?.label?.trim();
    return where ? `${label} · ${where}` : label;
}

function postView(card: PlacementCard | undefined, postsLoaded: boolean): PostView {
    if (!card) return { active: null, pending: null, placeable: postsLoaded ? false : null, blocked: null };
    return {
        active: card.active ? placeLabel(card.active.postLabel, card.active.target) : null,
        pending: card.pending ? placeLabel(card.pending.postLabel, card.pending.target) : null,
        placeable: card.placeable,
        blocked: card.blocked,
    };
}

/** 그 인물이 지휘하는 부곡 병력 합. */
function troopsOf(retainerId: number, units: readonly UnitCard[]): number {
    return units.filter((u) => u.commanderRetainerId === retainerId).reduce((sum, u) => sum + u.troops, 0);
}

export function retinueRows(retinue: Retinue, posts: Posts | null): RetinueRow[] {
    const postsLoaded = posts?.status === 'READY';
    const byCard = new Map((postsLoaded ? posts!.cards : []).map((c) => [c.cardId, c]));
    return retinue.people.map((p: PersonCard) => ({
        retainerId: p.retainerId,
        generalId: p.generalId,
        name: p.name,
        picture: p.picture,
        imageServer: p.imageServer,
        loyalty: p.loyalty,
        loyaltyTone: loyaltyTone(p.loyalty),
        cost: p.cost,
        departureOrder: p.departureOrder,
        bonds: p.bonds,
        stats: p.stats,
        aptitudes: p.aptitudes,
        post: postView(byCard.get(p.retainerId), postsLoaded),
        troops: troopsOf(p.retainerId, retinue.units),
    }));
}

/** 정렬 — 원본을 바꾸지 않는다. 값이 없는(null) 줄은 늘 뒤로. 같으면 등록순(카드 id). */
export function sortRetinue(rows: readonly RetinueRow[], sort: RetinueSort): RetinueRow[] {
    const byId = (a: RetinueRow, b: RetinueRow) => a.retainerId - b.retainerId;
    const nullsLast = (a: number | null, b: number | null, dir: 1 | -1) => {
        if (a == null && b == null) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        return (a - b) * dir;
    };
    const cmp: Record<RetinueSort, (a: RetinueRow, b: RetinueRow) => number> = {
        registered: byId,
        cost: (a, b) => nullsLast(a.cost, b.cost, -1) || byId(a, b),
        loyalty: (a, b) => a.loyalty - b.loyalty || byId(a, b),
        departure: (a, b) => nullsLast(a.departureOrder, b.departureOrder, 1) || byId(a, b),
    };
    return [...rows].sort(cmp[sort]);
}

export interface UnitRow {
    readonly id: number;
    readonly name: string;
    readonly crewTypeName: string;
    readonly troops: number;
    readonly training: number;
    readonly morale: number;
    readonly fatigue: number;
    /** 「쌀 n달 분」의 n. */
    readonly provisionMonths: number;
    /** 지휘 인물 이름. 없으면 null → 「지휘 없음 — 움직일 수 없음」. */
    readonly commander: string | null;
}

export function unitRows(retinue: Retinue): UnitRow[] {
    const names = new Map(retinue.people.map((p) => [p.retainerId, p.name]));
    return retinue.units.map((u) => ({
        id: u.id,
        name: u.name,
        crewTypeName: u.crewTypeName,
        troops: u.troops,
        training: u.training,
        morale: u.morale,
        fatigue: u.fatigue,
        provisionMonths: u.provisionMonths,
        commander: u.commanderRetainerId != null ? names.get(u.commanderRetainerId) ?? null : null,
    }));
}

export interface RenownBand {
    /** 명망 = 거느릴 수 있는 부 코스트의 상한. 없으면 null(첫 월단평 전 등). */
    readonly renown: number | null;
    readonly costSum: number | null;
    readonly overCapacity: boolean;
    /** 막대 비율 0–1(둘 다 있을 때만). 1 을 넘으면 1 로 자르고 overCapacity 로 알린다. */
    readonly ratio: number | null;
}

/** 명망 띠 — 부 조회 값을 먼저, 없으면 월단평 본인 값. 두 조회가 같은 값을 준다(`GeneralRoster` · `yuedan` 화면이 따로 불렀다). */
export function renownBand(retinue: Retinue | null, yuedan: Yuedan | null): RenownBand {
    const renown = retinue?.renown ?? yuedan?.self?.renown ?? null;
    const costSum = retinue?.costSum ?? yuedan?.self?.retinueCost ?? null;
    const overCapacity = retinue?.overCapacity ?? yuedan?.self?.overCapacity ?? false;
    const ratio = renown != null && costSum != null && renown > 0 ? Math.min(1, costSum / renown) : null;
    return { renown, costSum, overCapacity, ratio };
}
