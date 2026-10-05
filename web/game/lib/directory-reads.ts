'use client';

// 인물 일람 · 세력 요약 · 현 목록 조회(계약판 K4-05 · K4-09 · K4-11 첫 판).
//
// game-api `CampaignDirectoryController` · `CountyDirectoryController`의 응답을 그대로 옮긴다(Jackson camelCase).
// null 은 「볼 수 없거나 권한 밖」이다 — 0 으로 바꾸지 않는다(서버 DTO 주석과 같은 약속).
// 서버가 아직 주지 않는 칸(적성 정렬 · 부상 · 나이 · 현 7지표 등)은 여기서 만들지 않는다. 화면이 「서버 대기」로 그린다.

import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from './api';
import { useGameSession } from './campaign-session';
import type { ReadStatus, Stamp, Stock } from './campaign-reads';
import { plainReadError } from '@opensamguk/ui';

/**
 * 세 조회가 실제로 주는 상태(`CampaignDirectoryReader` · `CountyDirectoryReader`):
 * - `NO_GENERAL` — 이 계정에 장수가 없다(`/api/people`)
 * - `NO_NATION` — 재야라 세력 요약 · 현 목록이 없다(`/api/nation/summary` · `/api/counties`)
 * - `PARTIAL` — 받은 값은 맞지만 일부(창고 · 병력 · FULL 현의 세입)를 못 읽었다. 받은 것은 그대로 보인다.
 * `campaignReadNotice` 가 앞의 둘을, `campaignPartialNotice` 가 PARTIAL 을 알린다 — 빈 칸으로 두지 않는다.
 */
export type DirectoryStatus = ReadStatus | 'NO_GENERAL' | 'NO_NATION' | 'PARTIAL';

// ── 인물 일람 (`GET /api/people?scope=&q=&sort=ID&cursor=&limit=`) ─────────────────────
export type PeopleScope = 'ALL' | 'NATION' | 'RETINUE';

export interface DirectoryPortrait {
    readonly picture: string | null;
    readonly imageServer: number;
}
export interface DirectoryAffiliation {
    readonly nationId: number;
    readonly name: string;
    readonly color: string;
}
export interface DirectoryStats {
    readonly leadership: number;
    readonly strength: number;
    readonly intel: number;
    readonly politics: number;
    readonly charm: number;
}
export interface DirectoryAptitudes {
    readonly command: number;
    readonly administration: number;
    readonly strategy: number;
    readonly envoy: number;
}
export interface DirectoryBond {
    readonly kind: string;
    readonly targetId: string;
}
export interface DirectoryPerson {
    readonly generalId: number;
    readonly name: string;
    readonly portrait: DirectoryPortrait;
    /** 무소속(재야)이면 null. */
    readonly affiliation: DirectoryAffiliation | null;
    readonly role: string | null;
    readonly lordGeneralId: number | null;
    /** 시야 · 권한 밖이면 null. */
    readonly stats: DirectoryStats | null;
    readonly aptitudes: DirectoryAptitudes | null;
    readonly locationCityId: number | null;
    readonly bonds: readonly DirectoryBond[] | null;
    // ---- 계약판 K4-05 보강(행 322, C9 「K4 생산자 후속 타입 · ACL 합의」 · C10 accepted-fields) — 모두 `|null`.
    // 서버가 아직 주지 않는다. 오면 쓰고, 빠지면(undefined) 지금처럼 그린다. 목록의 `injured` 는 확정 표에 없어 받지 않는다.
    // 나이 · 코스트는 승인 보드(V31K4People)에 칸이 없어 받기만 하고 그리지 않는다.
    /** 사람 장수인지. null · 빠짐 = 모름(NPC 로 바꿔 쓰지 않는다). */
    readonly human?: boolean | null;
    /** 5능력 합(서버 값). 빠지면 화면이 stats 로 더한다. */
    readonly statTotal?: number | null;
    /** 소재 — 城 id 와 이름. 빠지면 locationCityId 를 화면의 城 표로 푼다. */
    readonly location?: { readonly cityId: number; readonly name: string } | null;
    readonly age?: number | null;
    /** 명망 코스트(부양비, 녹봉 아님) — 내가 직접 거느린 부 카드만, 나머지는 null. */
    readonly cost?: number | null;
}
export interface PeoplePage {
    readonly status: DirectoryStatus;
    readonly people: readonly DirectoryPerson[];
    readonly nextCursor: string | null;
    /** 범위 전체 수(계약판 K4-05 보강 `total`). 빠지면 「n명 · 더 있음」. */
    readonly total?: number | null;
}
export interface PeopleQuery {
    readonly scope: PeopleScope;
    /** 이름 찾기(서버가 자른다, 100자 이하). */
    readonly q: string;
    /** 한 번에 받는 수(서버 1–100). */
    readonly limit: number;
    /** 정렬 키(기본 ID — 등록순). 서버가 정렬한다. */
    readonly sort?: import('./directory-paths').PeopleSort;
    /** 정렬 방향(기본 ASC). */
    readonly direction?: import('./directory-paths').PeopleDirection;
}

export { PEOPLE_SORTS, PEOPLE_PAGE_LIMIT, peoplePath, countiesPath, type PeopleSort, type PeopleDirection } from './directory-paths';

// ── 세력 요약 (`GET /api/nation/summary?generalId=`) ─────────────────────────────
export interface SummaryNation {
    readonly id: number;
    readonly name: string;
    readonly color: string;
}
export interface SummaryLord {
    readonly generalId: number;
    readonly name: string;
    readonly portrait: DirectoryPortrait;
}
export interface NationSummary {
    readonly status: DirectoryStatus;
    readonly nation: SummaryNation | null;
    readonly lord: SummaryLord | null;
    readonly capitalCityId: number | null;
    readonly countyCount: number | null;
    /** 이 세력에 속한 주인이 거느린 부 인물 카드 수. */
    readonly retinueCount: number | null;
    readonly stockTotal: Stock | null;
    readonly population: number | null;
    readonly troops: { readonly city: number | null; readonly bugok: number | null } | null;
}

// ── 현 목록 (`GET /api/counties?generalId=&scope=&commanderyId=`) ────────────────────
export type CountyScope = 'NATION' | 'COMMANDERY';
export interface CountyIncome {
    readonly money: number;
    readonly grain: number;
}
export interface CountyDirectoryRow {
    readonly cityId: number;
    readonly name: string;
    readonly commanderyId: string;
    readonly visibility: 'FULL' | 'INTEL' | 'FOG' | string;
    /** 한 달 총생산 예측(이미 받은 세입이 아니다). 볼 수 없으면 null. */
    readonly income: CountyIncome | null;
}
export interface CountyDirectory {
    readonly status: DirectoryStatus;
    readonly scope: CountyScope | string;
    readonly commandery: { readonly id: string; readonly name: string } | null;
    readonly period: 'GAME_MONTH' | string;
    readonly basis: 'CURRENT_STATE_FORECAST' | string;
    readonly stamp: Stamp | null;
    readonly counties: readonly CountyDirectoryRow[];
}


// ── 인물 일람 훅 — 커서를 이어 받는다 ────────────────────────────────────────────
export interface PeopleList {
    readonly people: readonly DirectoryPerson[];
    readonly status: DirectoryStatus | null;
    readonly loading: boolean;
    /** 첫 쪽을 못 받은 오류(쉬운 말 한 문장). 빈 목록과 다르게 보인다. */
    readonly error: string | null;
    /** 그 오류의 번호(HTTP 등, 없으면 null) — StatusView 오류 번호 칸. */
    readonly errorCode: string | null;
    /** 「더 보기」를 누를 수 있는가(서버가 다음 커서를 줬는가). */
    readonly hasMore: boolean;
    /** 범위 전체 수(K4-05 보강) — 서버가 주지 않으면 null. */
    readonly total: number | null;
    /** 다음 쪽 오류 — 이미 받은 목록은 지우지 않는다. */
    readonly moreError: string | null;
    readonly loadMore: () => void;
}

/**
 * 인물 일람 한 벌. 범위 · 찾기가 바뀌면 처음부터, 「더 보기」는 서버 커서로 이어 붙인다.
 * 장수가 없으면 부르지 않는다(셸이 입구로 보낸다).
 */
export function usePeopleList(query: PeopleQuery): PeopleList {
    const { generalId } = useGameSession();
    const [people, setPeople] = useState<readonly DirectoryPerson[]>([]);
    const [status, setStatus] = useState<DirectoryStatus | null>(null);
    const [cursor, setCursor] = useState<string | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [errorCode, setErrorCode] = useState<string | null>(null);
    const [moreError, setMoreError] = useState<string | null>(null);
    const [total, setTotal] = useState<number | null>(null);
    const inflight = useRef<AbortController | null>(null);
    const key = `${query.scope}|${query.q.trim()}|${query.limit}|${query.sort ?? 'ID'}|${query.direction ?? 'ASC'}`;

    useEffect(() => {
        inflight.current?.abort();
        if (generalId == null) {
            setPeople([]); setStatus(null); setCursor(null); setTotal(null); setLoading(false); setError(null); setErrorCode(null); setMoreError(null);
            return;
        }
        const controller = new AbortController();
        inflight.current = controller;
        // 범위 · 찾기가 바뀌면 이전 범위 목록을 비운다 — 불러오는 동안 다른 범위의 인물이 보이지 않게.
        setPeople([]); setStatus(null); setCursor(null); setTotal(null);
        setLoading(true); setError(null); setErrorCode(null); setMoreError(null);
        api.people(query, null, controller.signal)
            .then((page) => {
                setPeople(page.people); setStatus(page.status); setCursor(page.nextCursor); setTotal(page.total ?? null); setLoading(false);
            })
            .catch((e: unknown) => {
                if (controller.signal.aborted) return;
                setPeople([]); setStatus(null); setCursor(null); setTotal(null); setLoading(false);
                const failure = e instanceof Error ? plainReadError(e.message) : { text: '불러오지 못했습니다.', code: null };
                setError(failure.text); setErrorCode(failure.code);
            });
        return () => controller.abort();
        // eslint-disable-next-line react-hooks/exhaustive-deps -- key 가 query 를 대신한다
    }, [generalId, key]);

    // 화면을 떠나면 진행 중인 「더 보기」 요청도 끊는다.
    useEffect(() => () => inflight.current?.abort(), []);

    const loadMore = useCallback(() => {
        if (cursor == null || loading) return;
        const controller = new AbortController();
        inflight.current = controller;
        setLoading(true); setMoreError(null);
        api.people(query, cursor, controller.signal)
            .then((page) => {
                setPeople((prev) => [...prev, ...page.people]); setStatus(page.status); setCursor(page.nextCursor); setTotal(page.total ?? null); setLoading(false);
            })
            .catch((e: unknown) => {
                if (controller.signal.aborted) return;
                setLoading(false);
                setMoreError(e instanceof Error ? plainReadError(e.message).text : '더 불러오지 못했습니다.');
            });
        // eslint-disable-next-line react-hooks/exhaustive-deps -- key 가 query 를 대신한다
    }, [cursor, loading, key]);

    return { people, status, loading, error, errorCode, hasMore: cursor != null, total, moreError, loadMore };
}
