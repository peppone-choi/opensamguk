'use client';

// 휘하 조회 API 의 응답 형태와 화면용 훅.
//
// game-api 의 DTO 를 그대로 옮긴다(Jackson camelCase). 휘하 규칙이 아닌 월드는 HTTP 오류가 아니라
// `status: 'WRONG_RULE_PROFILE'` 으로 알린다 — 화면은 그걸 빈 상태로 보인다.

import { useEffect, useState } from 'react';
import { api } from './api';
import { useHwihaSession } from './hwiha-session';

export type ReadStatus = 'READY' | 'NOT_ASSESSED' | 'NOT_READY' | 'UNAVAILABLE' | 'WRONG_RULE_PROFILE';

// ── 계책 손패 (`GET /api/commands/stratagem-hand`) ─────────────────────────────
export interface StratagemCard {
    readonly instanceId: number;
    readonly type: 'FORTIFY' | 'INSIGHT' | string;
    readonly label: string;
}
export interface StratagemHand {
    readonly status: 'READY' | 'NOT_READY' | 'UNAVAILABLE' | 'WRONG_RULE_PROFILE';
    readonly cards: readonly StratagemCard[];
    readonly handLimit: number;
    readonly canUse: boolean;
}

// ── 월단평 (`GET /api/hwiha/yuedan`) ─────────────────────────────────────────
export interface YuedanRow {
    readonly rank: number;
    readonly generalId: number;
    readonly name: string;
    readonly nationId: number;
    readonly nationName: string | null;
    readonly nationColor: string | null;
    readonly renown: number;
    /** 지난 월단평이 적용한 사건 종류(원인은 싣지 않는다). */
    readonly reasons?: readonly RenownReason[];
}
export interface Yuedan {
    readonly status: ReadStatus;
    readonly stamp: string | null;
    readonly self: {
        readonly generalId: number;
        readonly renown: number | null;
        readonly retinueCost: number | null;
        readonly overCapacity: boolean;
    } | null;
    readonly ranking: readonly YuedanRow[];
    /** 다음 월단평에 반영될 내 사건 — 본인에게만 온다. */
    readonly selfPendingEvents?: readonly RenownPendingEvent[];
}

// ── 창고 (`GET /api/hwiha/warehouses`) ───────────────────────────────────────
export interface Stock {
    readonly money: number;
    readonly grain: number;
    readonly iron: number;
    readonly timber: number;
    readonly horses: number;
}
export interface Warehouse {
    readonly cityId: number;
    readonly name: string;
    readonly commanderyName: string | null;
    readonly isCapital: boolean;
    readonly supplied: boolean;
    readonly stock: Stock;
}
export interface Warehouses {
    readonly status: ReadStatus;
    readonly warehouses: readonly Warehouse[];
    readonly invalidCount?: number;
}

// ── 현 특산 (`GET /api/hwiha/county/{cityId}`) ───────────────────────────────
export interface County {
    readonly status: ReadStatus;
    readonly cityId: number;
    readonly name: string;
    /** monthly 는 이번 달 엔진이 실제로 넣을 월 산출(주인 없음·보급 끊김·창고 없음이면 0, 창고가 깨졌으면 null), ledgerMonthly 는 원장 설계값. */
    readonly specialties: readonly { resource: string; label: string; monthly: number | null; ledgerMonthly?: number | null }[];
}

// ── 휘하 인물 카드 (`GET /api/hwiha/retinue`) ────────────────────────────────
export interface FiveStats {
    readonly leadership: number;
    readonly strength: number;
    readonly intel: number;
    readonly politics: number;
    readonly charm: number;
}
export interface Aptitudes {
    /** 장 — 군단. */
    readonly command: number;
    /** 리 — 내정. */
    readonly administration: number;
    /** 사 — 계책. */
    readonly strategy: number;
    /** 사자 — 외교. */
    readonly envoy: number;
}
export interface Bond {
    readonly kind: 'HYANGDANG' | string;
    readonly label: string;
    /** 본관 현의 한글 이름(「패국 초현」). 城 표에서 유일하게 풀리지 않으면 null — 칩만 보인다. */
    readonly nativeCountyName: string | null;
    readonly sameAsLord: boolean;
}
export interface PersonCard {
    readonly retainerId: number;
    readonly generalId: number | null;
    readonly name: string;
    readonly picture: string | null;
    readonly imageServer: number;
    readonly loyalty: number;
    readonly roleLabel: string | null;
    readonly taskLabel: string | null;
    readonly stats: FiveStats | null;
    readonly cost: number | null;
    readonly aptitudes: Aptitudes | null;
    readonly bonds: readonly Bond[];
    readonly departureOrder: number | null;
    readonly locationCityId: number | null;
}
export interface UnitCard {
    readonly id: number;
    readonly name: string;
    readonly troops: number;
    readonly crewTypeId: number;
    readonly crewTypeName: string;
    readonly training: number;
    readonly morale: number;
    readonly fatigue: number;
    readonly provisions: number;
    readonly provisionMonths: number;
    readonly commanderRetainerId: number | null;
}
export interface Retinue {
    readonly status: ReadStatus;
    readonly renown: number | null;
    readonly costSum: number | null;
    readonly overCapacity: boolean;
    readonly people: readonly PersonCard[];
    readonly units: readonly UnitCard[];
}

// ── 공성 (`GET /api/hwiha/sieges`) ───────────────────────────────────────────
export interface Siege {
    readonly countyId: number;
    readonly countyName: string | null;
    readonly status: string;
    readonly endReason: string | null;
    readonly besieger: { readonly generalId: number; readonly name: string | null; readonly nationId: number; readonly nationName: string | null };
    readonly defenderNationId: number;
    readonly defenderNationName: string | null;
    readonly startedAt: { readonly year: number; readonly month: number; readonly phase: number };
    readonly turns: number;
    readonly grain: number | null;
    readonly morale: number;
    readonly garrison: number;
    readonly trust: number;
    readonly countySupplied: boolean;
    readonly besiegerTroops: number | null;
    readonly besiegerFed: boolean | null;
    readonly canAct: boolean;
    readonly surrenderDemandAccepted: boolean;
    readonly timeline: readonly Record<string, unknown>[];
}
export interface Sieges {
    readonly status: ReadStatus;
    readonly sieges: readonly Siege[];
}

export interface RoadFort {
    readonly id: string;
    readonly edgeId: string;
    readonly provinceId: string;
    readonly row: number;
    readonly col: number;
    readonly ownerNationId: number;
    readonly wall: number;
    readonly garrison: number;
    readonly besiegerGeneralId: number | null;
    readonly siegeProgress: number;
    readonly canBesiege: boolean;
}

export interface RoadForts {
    readonly status: string;
    readonly roadMode: boolean;
    readonly forts: readonly RoadFort[];
    readonly gates: readonly {
        edgeId: string;
        fromProvinceId: string;
        toProvinceId: string;
        active: boolean;
        buildable: boolean;
        historicalRouteIds: readonly string[];
        fortCells: readonly { provinceId: string; row: number; col: number }[];
    }[];
}

// ── 공용 훅 ──────────────────────────────────────────────────────────────────
export interface Read<T> {
    readonly data: T | null;
    readonly error: string | null;
    readonly loading: boolean;
}

/**
 * 본인 장수로 휘하 조회 하나를 부른다. 장수가 없거나 휘하 월드가 아니면 부르지 않는다 — 그때
 * 화면은 셸의 차단 사유를 보인다. [deps] 가 바뀌면 다시 부른다.
 */
export function useHwihaRead<T>(
    load: (generalId: number, signal: AbortSignal) => Promise<T>,
    deps: readonly unknown[] = [],
): Read<T> {
    const { generalId, isHwihaWorld, frontInfo } = useHwihaSession();
    const [state, setState] = useState<Read<T>>({ data: null, error: null, loading: true });
    const turnKey = frontInfo ? `${frontInfo.global.year}-${frontInfo.global.month}-${frontInfo.global.turnPhase ?? ''}` : '';

    useEffect(() => {
        if (generalId == null || !isHwihaWorld) {
            setState({ data: null, error: null, loading: false });
            return;
        }
        const controller = new AbortController();
        setState((prev) => ({ ...prev, loading: true, error: null }));
        load(generalId, controller.signal)
            .then((data) => setState({ data, error: null, loading: false }))
            .catch((e: unknown) => {
                if (controller.signal.aborted) return;
                setState({ data: null, error: e instanceof Error ? e.message : '불러오지 못했습니다.', loading: false });
            });
        return () => controller.abort();
        // eslint-disable-next-line react-hooks/exhaustive-deps -- load 는 호출부의 인라인 화살표다
    }, [generalId, isHwihaWorld, turnKey, ...deps]);

    return state;
}

/** 머리의 명망 칩. 월단평 조회의 본인 값을 쓴다. */
export function useHwihaRenown(): number | null {
    const { data } = useHwihaRead((id, signal) => api.hwihaYuedan(id, signal));
    return data?.self?.renown ?? null;
}

/** 자원 다섯의 화면 이름 — 사용자 확정 표기(전→금, 곡→쌀). */
export const HWIHA_RESOURCE_LABELS: ReadonlyArray<{ key: keyof Stock; label: string }> = [
    { key: 'money', label: '금' },
    { key: 'grain', label: '쌀' },
    { key: 'iron', label: '철' },
    { key: 'timber', label: '목재' },
    { key: 'horses', label: '말' },
];

// ── 지난 순 (`GET /api/hwiha/last-turns`) ────────────────────────────────────
export interface LastTurnEntry {
    readonly kind: string;
    readonly text: string;
    readonly refs?: Record<string, unknown>;
}
export interface LastTurn {
    readonly year: number;
    readonly month: number;
    readonly phase: number;
    readonly phaseLabel: string;
    readonly entries: readonly LastTurnEntry[];
}
export interface NationSummaryEntry extends LastTurnEntry {
    readonly year: number;
    readonly month: number;
    readonly phase: number;
    readonly phaseLabel: string;
}
export interface LastTurns {
    readonly status: ReadStatus;
    readonly turns: readonly LastTurn[];
    readonly nationSummary: readonly NationSummaryEntry[];
}

// ── 월단평 사유(추가 필드) ────────────────────────────────────────────────────
export interface RenownReason {
    readonly kind: string;
    readonly label: string;
    readonly count: number;
    readonly amount: number;
}
export interface RenownPendingEvent {
    readonly kind: string;
    readonly label: string;
    readonly stamp: string;
    readonly source?: string;
    readonly sourceLabel?: string;
    readonly amount: number;
}

// ── 시야 (`GET /api/hwiha/visibility` · `corps` · `scout-options`) ─────────────
export interface Stamp {
    readonly year: number;
    readonly month: number;
    readonly phase: number;
}
export type VisionTier = 'FULL' | 'INTEL' | 'FOG';
export interface VisibilityCommandery {
    readonly no: number;
    readonly id: string;
    readonly name: string;
    readonly tier: VisionTier;
    readonly seenAtStamp?: Stamp;
    readonly ageTurns?: number;
}
export interface Visibility {
    readonly status: ReadStatus;
    readonly stamp?: Stamp;
    readonly commanderies?: readonly VisibilityCommandery[];
}
export interface Corps {
    readonly corpsId: string;
    readonly ownerGeneralId: number;
    readonly ownerName?: string;
    readonly commanderGeneralId: number;
    readonly commanderName?: string;
    readonly nationId: number;
    readonly nationColor?: string;
    readonly provinceId: string;
    readonly commanderyNo: number;
    readonly visibility: VisionTier;
    readonly own: boolean;
    readonly troops?: number;
    readonly troopsBand?: { code: string; label: string };
    readonly marchPath?: readonly string[];
    readonly destinationProvinceId?: string;
    readonly lastSeenStamp?: Stamp;
    readonly ageTurns?: number;
}
export interface CorpsList {
    readonly status: ReadStatus;
    readonly corps?: readonly Corps[];
}
export interface ScoutOption {
    readonly no: number;
    readonly id: string;
    readonly name: string;
    readonly tier: VisionTier;
    readonly available: boolean;
    readonly code?: string;
    readonly reason?: string;
    readonly ageTurns?: number;
}
export interface ScoutOptions {
    readonly status: ReadStatus;
    readonly inputId?: string;
    readonly available?: boolean;
    readonly code?: string;
    readonly reason?: string;
    readonly options?: readonly ScoutOption[];
}

// ── 배치·방침·공사 (`GET /api/hwiha/posts` · `policies` · `works`) ──────────────
export interface CodeLabel {
    readonly code: string;
    readonly label: string;
}
export interface Blocked {
    readonly code: string;
    readonly reason: string;
}
export interface PlacementCard {
    readonly cardId: number;
    readonly generalId: number | null;
    readonly name: string;
    readonly relation: string;
    readonly provinceId: string | null;
    readonly placeable: boolean;
    readonly blocked: Blocked | null;
    readonly active: { post: string; postLabel: string; target: { label?: string | null }; state: string } | null;
    readonly pending: { post: string; postLabel: string; target: { label?: string | null } } | null;
}
export interface PostOption {
    readonly post: string;
    readonly label: string;
    readonly available: boolean;
    readonly blocked: Blocked | null;
    readonly targets: readonly { countyId?: number | null; nationId?: number | null; name: string; commanderyName?: string | null; occupied: boolean }[] | null;
}
export interface Posts {
    readonly status: ReadStatus;
    readonly cards: readonly PlacementCard[];
    readonly posts: readonly PostOption[];
}
export interface CountyPolicy {
    readonly countyId: number;
    readonly name: string;
    readonly commanderyName: string | null;
    readonly active: { policy: string; label: string } | null;
    readonly pending: { policy: string | null; label: string | null } | null;
    readonly effective: { policy: string; label: string; source: string } | null;
    readonly seat: { generalId: number; name: string; placed: boolean } | null;
    readonly settable: boolean;
    readonly blocked: Blocked | null;
}
export interface Policies {
    readonly status: ReadStatus;
    readonly countyOptions: readonly CodeLabel[];
    readonly corpsOptions: readonly CodeLabel[];
    readonly defaultPolicy: CodeLabel | null;
    readonly counties: readonly CountyPolicy[];
    readonly corps: readonly { orderId: string; commanderName: string | null; active: { policy: string; label: string } | null; pending: { policy: string | null; label: string | null } | null; settable: boolean; blocked: Blocked | null }[];
}
export interface CountyWorks {
    readonly countyId: number;
    readonly provinceId: string | null;
    readonly provinceIds: readonly string[];
    readonly name: string;
    readonly commanderyName: string | null;
    readonly warehouse: Stock | null;
    readonly active: {
        work: string; label: string; percent: number; remainingPhases: number;
        remainingCost: Stock; stopReasonText: string | null; startsAtNextBoundary: boolean;
    } | null;
    readonly completed: readonly { work: string; label: string; edgeId: string | null }[];
    readonly startable: readonly { work: string; label: string; available: boolean; blocked: Blocked | null; cost: Stock; estimatedPhases: number }[];
}
export interface Works {
    readonly status: ReadStatus;
    readonly counties: readonly CountyWorks[];
}
