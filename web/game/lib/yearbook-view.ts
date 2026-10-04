// 연감(P-H02) 보기 모델 — React 없음(단위 시험으로 고정한다). 계약에 없는 것(판도 지도 · 세력별 현 목록)은 만들지 않는다.

import type { GameEvent } from '@opensamguk/ui';
import type { YearbookTerritory, YearbookYear } from './yearbook-contract';

/** 발행된 해만 오름차순. */
export function publishedYears(years: readonly YearbookYear[]): readonly number[] {
    return years.filter((y) => y.published).map((y) => y.year).sort((a, b) => a - b);
}

/** 앞 · 뒤 해(발행된 해 안에서). 없으면 null. */
export function neighbours(years: readonly number[], year: number): { readonly prev: number | null; readonly next: number | null } {
    const i = years.indexOf(year);
    return { prev: i > 0 ? years[i - 1] : null, next: i >= 0 && i < years.length - 1 ? years[i + 1] : null };
}

/** 연말 판도 행 — 현 수가 많은 쪽부터, 무주(nationId 0)는 맨 뒤. 같은 수는 서버 순서. */
export function territoryRows(rows: readonly YearbookTerritory[]): readonly YearbookTerritory[] {
    return rows
        .map((row, index) => ({ row, index }))
        .sort((a, b) => {
            const ronin = Number(a.row.nationId === 0) - Number(b.row.nationId === 0);
            if (ronin !== 0) return ronin;
            return b.row.countyCount - a.row.countyCount || a.index - b.index;
        })
        .map((e) => e.row);
}

/** 사건이 그 세력과 관계있는지 — refs 의 세력 키(`nationId` · `fromNationId` · `toNationId` …) 가운데 하나가 같으면. */
export function eventTouchesNation(event: GameEvent, nationId: number): boolean {
    return Object.entries(event.refs).some(([key, value]) => /nation/i.test(key) && Number(value) === nationId);
}
