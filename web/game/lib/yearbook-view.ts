// 연감(P-H02) 보기 모델 — React 없음(단위 시험으로 고정한다). 서버가 주지 않은 칸(연말 판도 · 세력별 현 목록)은 지금 값으로 만들지 않는다.

import type { EventNames, GameEvent } from '@opensamguk/ui';
import type { TopdownPreview } from '@opensamguk/ui/map/topdown';
import type { YearbookOwnership, YearbookPage, YearbookTerritory, YearbookYear } from './yearbook-contract';

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

/**
 * 그해 칸 하나의 상태 — 서버 대기(칸이 아직 없음) · 결손(발행됐지만 원천이 없음, absent) · 있음.
 * 결손을 빈 목록이나 지금 값으로 바꾸지 않는다.
 */
export type YearbookPart<T> = { readonly kind: 'waiting' } | { readonly kind: 'absent' } | { readonly kind: 'ready'; readonly value: T };

/** Validate supplied enhancements without inventing missing values. */
export function yearbookPartsError(page: Pick<YearbookPage, 'snapshot' | 'ownership' | 'absent' | 'territory'>): string | null {
    const absentOwnership = page.absent?.includes('ownership') ?? false;
    const absentCounties = page.absent?.includes('counties') ?? false;
    const rows = page.territory;
    const supplied = page.ownership !== undefined || rows.some((row) => row.counties !== undefined)
        || (page.absent?.length ?? 0) > 0;
    if (supplied && !page.snapshot) return '연감 snapshot이 없습니다';
    if (absentOwnership ? page.ownership !== null : page.ownership === null) return '연말 소유의 결손 선언이 맞지 않습니다';
    if (page.ownership && page.ownership.revision !== page.snapshot?.revision) return '연말 소유와 연감 판이 다릅니다';
    if (rows.some((row) => row.counties !== undefined && row.counties !== null && row.counties.length !== row.countyCount)) return '소속 현 수와 현 목록이 다릅니다';
    if (absentCounties ? rows.some((row) => row.counties !== null) : rows.some((row) => row.counties === null)) return '현 목록의 결손 선언이 맞지 않습니다';
    return null;
}

export function ownershipPart(page: Pick<YearbookPage, 'ownership' | 'absent'>): YearbookPart<YearbookOwnership> {
    if (page.absent?.includes('ownership') && page.ownership === null) return { kind: 'absent' };
    return page.ownership == null ? { kind: 'waiting' } : { kind: 'ready', value: page.ownership };
}

/** 세력별 현 목록 — 모든 행이 counties 를 가져야 있음으로 본다(일부만 오면 섞어 그리지 않고 기다린다). */
export function countiesPart(page: Pick<YearbookPage, 'territory' | 'absent'>): YearbookPart<ReadonlyMap<number, readonly string[]>> {
    if (page.territory.length > 0 && page.absent?.includes('counties') && page.territory.every((row) => row.counties === null)) return { kind: 'absent' };
    if (page.territory.length === 0 || page.territory.some((row) => row.counties == null)) return { kind: 'waiting' };
    return { kind: 'ready', value: new Map(page.territory.map((row) => [row.nationId, (row.counties ?? []).map((c) => c.name)])) };
}

/** 연말 판도를 지도 세계 상태 입력으로 — 세력 이름 · 색은 그해 판도 표의 것(지금 세력 목록이 아니다), 무주는 칠하지 않는다. */
export function yearEndPreview(ownership: YearbookOwnership, territory: readonly YearbookTerritory[]): TopdownPreview {
    return {
        nations: territory.filter((row) => row.nationId !== 0).map((row) => ({ id: row.nationId, name: row.name, color: row.color })),
        // worldFromPreview 는 구역 번호만 본다 — 연감은 구역 레코드 id 를 주지 않는다
        provinceOccupancy: ownership.provinces.map((p) => ({ provinceRecordId: '', provinceIndex: p.index, nationId: p.nationId })),
    };
}


/** Historical names from this yearbook only; no preview or session fallback. */
export function yearbookNames(territory: readonly YearbookTerritory[]): EventNames {
    const cities = new Map(territory.flatMap((row) => (row.counties ?? []).map((city) => [city.cityId, city.name] as const)));
    const nations = new Map(territory.filter((row) => row.nationId !== 0).map((row) => [row.nationId, row.name] as const));
    return { city: (id) => cities.get(id), nation: (id) => nations.get(id), general: () => undefined };
}
