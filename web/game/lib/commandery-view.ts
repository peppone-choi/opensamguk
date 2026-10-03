// 군 내정 현황(P-T03)의 보기 모델 — 현 목록(`/api/counties`, 계약판 K4-11 첫 판) + 방침 · 공사 · 창고를 현 id 로 잇는다. React 없음.
//
// 지금 판에 없는 것(7지표 · 등급 · 민심 추이 · 적 군단 · 이 현의 인물 · 이웃 군)은 짓지 않는다 — 화면이 「준비 중」 으로 둔다.
// 경고는 서버 값에서 곧장 읽히는 셋만: 빈 현령(방침 seat 없음) · 고립(창고 supplied=false) · 자재 부족(공사 멈춤 INSUFFICIENT_STOCK).

import type { CountyDirectory } from './directory-reads';
import type { Policies, Warehouses, Works } from './campaign-reads';
import { countyPolicyRows } from './territory-view';

export type CountyWarning = 'NO_MAGISTRATE' | 'ISOLATED' | 'MATERIAL_SHORT';
export const COUNTY_WARNING_LABEL: Readonly<Record<CountyWarning, string>> = {
    NO_MAGISTRATE: '빈 현령',
    ISOLATED: '고립',
    MATERIAL_SHORT: '자재 부족',
};

/** 서버 `DomesticEffects.INSUFFICIENT_STOCK`. */
export const INSUFFICIENT_STOCK = 'INSUFFICIENT_STOCK';

export const VISIBILITY_LABEL: Readonly<Record<string, string>> = { FULL: '보임', INTEL: '첩보', FOG: '안 보임' };

/** 보조 조회(방침 · 공사 · 창고)를 아직 못 읽었거나 실패한 칸 — 「—」(없음)와 다르다. 0 이나 「없음」으로 바꾸지 않는다(#1274 리뷰). */
export const UNKNOWN_CELL = '?';

export interface CommanderyCountyRow {
    readonly cityId: number;
    readonly name: string;
    readonly visibility: string;
    /** 「월 금 120 · 쌀 80」(한 달 생산 예측). 볼 수 없으면 null. */
    readonly income: string | null;
    readonly incomeMoney: number | null;
    readonly incomeGrain: number | null;
    /** 현령 — 방침 조회에 이 현이 없으면(관할 밖) null(「—」), 방침을 못 읽었으면 UNKNOWN_CELL. */
    readonly magistrate: string | null;
    readonly policy: string | null;
    readonly policySource: string | null;
    readonly work: string | null;
    readonly warnings: readonly CountyWarning[];
}

/** policies · works · warehouses 가 null 이면 「못 읽음」 — 그 칸은 UNKNOWN_CELL, 그 경고는 세지 않는다(요약은 commanderySummary 가 「?」). */
export function commanderyRows(directory: CountyDirectory, policies: Policies | null, works: Works | null, warehouses: Warehouses | null): CommanderyCountyRow[] {
    const pol = new Map((policies ? countyPolicyRows(policies) : []).map((r) => [Number(r.targetId), r]));
    const wk = new Map((works?.counties ?? []).map((c) => [c.countyId, c]));
    const wh = new Map((warehouses?.warehouses ?? []).map((w) => [w.cityId, w]));
    return directory.counties.map((c) => {
        const p = pol.get(c.cityId) ?? null;
        const w = wk.get(c.cityId) ?? null;
        const house = wh.get(c.cityId) ?? null;
        const warnings: CountyWarning[] = [];
        if (p && p.seat === '빈자리') warnings.push('NO_MAGISTRATE');
        if (house && !house.supplied) warnings.push('ISOLATED');
        if (w?.active?.stopReason === INSUFFICIENT_STOCK) warnings.push('MATERIAL_SHORT');
        return {
            cityId: c.cityId,
            name: c.name,
            visibility: c.visibility,
            income: c.income ? `월 금 ${c.income.money} · 쌀 ${c.income.grain}` : null,
            incomeMoney: c.income?.money ?? null,
            incomeGrain: c.income?.grain ?? null,
            magistrate: policies ? p?.seat ?? null : UNKNOWN_CELL,
            policy: policies ? p?.now ?? null : UNKNOWN_CELL,
            policySource: p?.source ?? null,
            work: !works ? UNKNOWN_CELL : w?.active ? `${w.active.label} ${Math.max(0, Math.min(100, w.active.percent))}%` : null,
            warnings,
        };
    });
}

export type CommanderySort = 'name' | 'money' | 'grain' | 'warnings';
export const COMMANDERY_SORT_LABEL: Readonly<Record<CommanderySort, string>> = { name: '이름', money: '월 금', grain: '월 쌀', warnings: '경고 수' };

/** 정렬 — 원본은 그대로, 값이 없는 줄은 뒤로, 같으면 이름순. 7지표 정렬은 K4-11 보강 뒤. */
export function sortCommandery(rows: readonly CommanderyCountyRow[], sort: CommanderySort): CommanderyCountyRow[] {
    const byName = (a: CommanderyCountyRow, b: CommanderyCountyRow) => a.name.localeCompare(b.name, 'ko');
    const desc = (x: number | null, y: number | null) => (x == null && y == null ? 0 : x == null ? 1 : y == null ? -1 : y - x);
    const cmp: Record<CommanderySort, (a: CommanderyCountyRow, b: CommanderyCountyRow) => number> = {
        name: byName,
        money: (a, b) => desc(a.incomeMoney, b.incomeMoney) || byName(a, b),
        grain: (a, b) => desc(a.incomeGrain, b.incomeGrain) || byName(a, b),
        warnings: (a, b) => b.warnings.length - a.warnings.length || byName(a, b),
    };
    return [...rows].sort(cmp[sort]);
}

/** 경고 수 — 그 경고의 원천(방침 · 창고 · 공사)을 못 읽었으면 null(「?」). 0 은 「읽었고 없음」뿐이다. */
export interface CommanderySummary {
    readonly total: number;
    readonly noMagistrate: number | null;
    readonly isolated: number | null;
    readonly materialShort: number | null;
}

/** 각 원천을 읽었는지(data 가 왔는지). 빠지면 읽은 것으로 본다(보기 모델 단위 시험용). */
export interface CommanderySourcesRead {
    readonly policies: boolean;
    readonly works: boolean;
    readonly warehouses: boolean;
}

export function commanderySummary(rows: readonly CommanderyCountyRow[], read: CommanderySourcesRead = { policies: true, works: true, warehouses: true }): CommanderySummary {
    const count = (w: CountyWarning, known: boolean) => (known ? rows.filter((r) => r.warnings.includes(w)).length : null);
    return {
        total: rows.length,
        noMagistrate: count('NO_MAGISTRATE', read.policies),
        isolated: count('ISOLATED', read.warehouses),
        materialShort: count('MATERIAL_SHORT', read.works),
    };
}
