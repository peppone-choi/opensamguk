'use client';

// 작전실 지도 — 무인증 공개 API 세 개로 그린다(기존 작전실 MapViewer 와 같은 경로).
//
//   GET /api/game/api/map/preview                       城·세력·영토 소유
//   GET /api/game/api/map/terrain?mapCode=han-world-v3  지형 격자·군국(juns)·城 칸
//   GET /api/game/api/map/provinces?mapCode=han-world-v3 칸 → 구역·군국 식별 PNG(안개가 본다)
//
// 지형은 여기서 한 번 받아 `HanMapCanvas` 에 `tiles` 로 넘긴다. 그래야 군국 표(8방향 이동·안개
// 번호)가 **서버가 지금 서빙하는 판**에서 나온다 — 저장소에 구운 표는 판이 바뀌면 조용히 어긋난다.

import { useEffect, useMemo, useState } from 'react';
import {
    isOwnedNationVisual,
    loadProvinceIdentityMap,
    mapCityToTile,
    juUrlForTerrain,
    verifiedJuByParent,
    type JuIndexResponse,
    type ProvinceIdentityMap,
    parseTerrainEtagHash,
    validStrategicBinding,
    type HanTiles,
    type IsoCityOverlay,
} from '@opensamguk/ui';
import { api } from './api';
import type { MapPreviewResponse } from './types';

export const HWIHA_MAP_CODE = 'han-world-v3';

export const HWIHA_PROVINCES_URL = `/api/game/api/map/provinces?mapCode=${HWIHA_MAP_CODE}`;

export function hwihaTerrainUrl(baseTilesSha256: string | null): string {
    return `/api/game/api/map/terrain?mapCode=${HWIHA_MAP_CODE}`
        + (baseTilesSha256 ? `&baseTilesSha256=${baseTilesSha256}` : '');
}

/** 8방향 이동과 안개의 단위 — 군국. `no` 는 식별 PNG 의 군국 번호(juns 색인)다. */
export interface HwihaCommanderyCell {
    readonly no: number;
    readonly name: string;
    readonly col: number;
    readonly row: number;
    /** 지도를 이 군국으로 옮길 때 초점으로 쓸 게임 城. 城 이 하나도 없는 군국은 null. */
    readonly focusCityId: number | null;
}

export interface HwihaLegendEntry {
    readonly nationId: number;
    readonly name: string;
    readonly color: string;
    readonly cities: number;
}

export type HwihaMapState =
    | { readonly kind: 'loading' }
    | { readonly kind: 'error'; readonly message: string }
    /** 휘하 지도(han-world-v3)가 아닌 월드 — 로컬 `che` 시나리오 등. */
    | { readonly kind: 'unsupported'; readonly mapCode: string }
    | {
        readonly kind: 'ready';
        readonly preview: MapPreviewResponse;
        readonly tiles: HanTiles;
        readonly tilesSha256: string | undefined;
        /** 칸 → 구역·군국 식별. 지도에 그대로 넘기고, 구역 중심 칸(군단 위치)도 여기서 잰다. */
        readonly provinceMap: ProvinceIdentityMap | null;
        readonly provinceCenter: (provinceId: string) => { col: number; row: number } | undefined;
        readonly cities: readonly IsoCityOverlay[];
        readonly markerPositions: ReadonlyMap<number, { col: number; row: number }>;
        readonly commanderies: readonly HwihaCommanderyCell[];
        readonly legend: readonly HwihaLegendEntry[];
        readonly sourceSize: { width: number; height: number };
        /**
         * 서버가 준 행정 소유. 셋 중 하나라도 비었으면 undefined — 그때 지도는 城 기준으로 영토를
         * 추정한다. 빈 배열을 넘기면 지도가 「coverage mismatch」로 통째로 죽는다.
         */
        readonly administrativeOwnership: {
            provinceOccupancy: NonNullable<MapPreviewResponse['provinceOccupancy']>;
            jurisdictionOwnership: NonNullable<MapPreviewResponse['jurisdictionOwnership']>;
            commanderyControl: NonNullable<MapPreviewResponse['commanderyControl']>;
        } | undefined;
    };

const NEUTRAL_NAME = '공백지';

/** 미리보기 → 지도 城 겹. 세력 판정은 기존 작전실과 같다(isOwnedNationVisual). */
export function buildHwihaCities(preview: MapPreviewResponse): IsoCityOverlay[] {
    const nations = new Map(preview.nations.map((n) => [n.id, n]));
    return preview.cities.map((city) => {
        const nation = nations.get(city.nationId);
        const owned = isOwnedNationVisual(city.nationId, nation?.color);
        return {
            ...city,
            // 지도 이름표는 縣 이름만 — 동명이지 구분 郡 은 commanderyName 으로 따로 간다.
            mapLabel: city.name,
            nationName: owned ? nation?.name : NEUTRAL_NAME,
            nationColor: owned ? nation?.color : undefined,
            // 보급이 끊긴 세력 城 은 「고립」 배지. 무주 城 의 보급 값은 뜻이 없어 쓰지 않는다.
            statusBadges: owned && city.supply === false ? ['isolated'] : undefined,
            interactive: true,
        } satisfies IsoCityOverlay;
    });
}

/**
 * 城 아이콘을 칸 중앙에 앉힌다 — 칸이 게임 단위인 화면이다. 격자 크기는 받은 지형의 `_meta` 에서,
 * 원본 좌표계는 미리보기의 width/height 에서 읽는다(두 좌표계, 축마다 배율이 다르다).
 */
export function buildMarkerPositions(
    cities: readonly IsoCityOverlay[],
    tiles: HanTiles,
    sourceSize: { width: number; height: number },
): Map<number, { col: number; row: number }> {
    const grid = { cols: tiles._meta.cols, rows: tiles._meta.rows };
    return new Map(cities.map((city) => {
        const tile = mapCityToTile(city, grid, sourceSize);
        return [city.id, { col: Math.round(tile.col), row: Math.round(tile.row) }] as const;
    }));
}

/**
 * 지형의 juns → 군국 표. 초점 城 은 미리보기에서 **이름이 같은 군국의 치소**를 먼저, 없으면 그
 * 군국의 아무 城 이나 id 가 가장 작은 것을 고른다. 城 없는 군국은 초점이 null 이다 — 지어내지 않는다.
 */
export function buildCommanderies(tiles: HanTiles, preview: MapPreviewResponse): HwihaCommanderyCell[] {
    const byCommandery = new Map<string, { seat: number | null; first: number }>();
    for (const city of [...preview.cities].sort((a, b) => a.id - b.id)) {
        const name = city.commanderyName;
        if (!name) continue;
        const entry = byCommandery.get(name) ?? { seat: null, first: city.id };
        if (city.isCommanderySeat && entry.seat == null) entry.seat = city.id;
        byCommandery.set(name, entry);
    }
    return tiles.juns.flatMap((jun, no) => {
        if (!Number.isFinite(jun.col) || !Number.isFinite(jun.row)) return [];
        const hit = byCommandery.get(jun.name);
        return [{ no, name: jun.name, col: jun.col, row: jun.row, focusCityId: hit ? hit.seat ?? hit.first : null }];
    });
}

/**
 * 구역 id → 그 구역 안의 대표 칸. 칸들의 무게중심에 가장 가까운 **구역 안** 칸을 고른다 — 초승달 꼴
 * 구역의 무게중심은 밖에 떨어질 수 있다. 처음 물을 때 한 번 훑고 담아 둔다.
 */
export function buildProvinceCenters(
    tiles: HanTiles,
    provinceMap: ProvinceIdentityMap | null,
): (provinceId: string) => { col: number; row: number } | undefined {
    const records = tiles.provinceRecords ?? [];
    const indexById = new Map(records.map((record, index) => [record.id, index]));
    let centers: Map<number, { col: number; row: number }> | null = null;
    const compute = () => {
        const out = new Map<number, { col: number; row: number }>();
        if (!provinceMap) return out;
        const { width, provinces } = provinceMap;
        const sum = new Map<number, { c: number; r: number; n: number }>();
        for (let i = 0; i < provinces.length; i += 1) {
            const p = provinces[i];
            if (p < 0) continue;
            const acc = sum.get(p) ?? { c: 0, r: 0, n: 0 };
            acc.c += i % width;
            acc.r += Math.floor(i / width);
            acc.n += 1;
            sum.set(p, acc);
        }
        const best = new Map<number, { col: number; row: number; d: number }>();
        for (let i = 0; i < provinces.length; i += 1) {
            const p = provinces[i];
            if (p < 0) continue;
            const acc = sum.get(p)!;
            const col = i % width;
            const row = Math.floor(i / width);
            const d = (col - acc.c / acc.n) ** 2 + (row - acc.r / acc.n) ** 2;
            const cur = best.get(p);
            if (!cur || d < cur.d) best.set(p, { col, row, d });
        }
        for (const [p, v] of best) out.set(p, { col: v.col, row: v.row });
        return out;
    };
    return (provinceId: string) => {
        const index = indexById.get(provinceId);
        if (index === undefined) return undefined;
        centers ??= compute();
        return centers.get(index);
    };
}

export function buildLegend(preview: MapPreviewResponse): HwihaLegendEntry[] {
    const counts = new Map<number, number>();
    for (const city of preview.cities) counts.set(city.nationId, (counts.get(city.nationId) ?? 0) + 1);
    return preview.nations
        .filter((n) => isOwnedNationVisual(n.id, n.color))
        .map((n) => ({ nationId: n.id, name: n.name, color: n.color, cities: counts.get(n.id) ?? 0 }))
        .sort((a, b) => b.cities - a.cities || a.nationId - b.nationId);
}

export function useHwihaWorldMap(refreshKey: unknown = 0): HwihaMapState {
    const [raw, setRaw] = useState<
        | { kind: 'loading' }
        | { kind: 'error'; message: string }
        | { kind: 'unsupported'; mapCode: string }
        | { kind: 'loaded'; preview: MapPreviewResponse; tiles: HanTiles; hash: string | null; provinceMap: ProvinceIdentityMap | null }
    >({ kind: 'loading' });

    useEffect(() => {
        const controller = new AbortController();
        (async () => {
            const preview = await api.mapPreview(controller.signal);
            if (preview.mapCode !== HWIHA_MAP_CODE) {
                setRaw({ kind: 'unsupported', mapCode: preview.mapCode });
                return;
            }
            const binding = preview.strategicTopology;
            const base = binding && validStrategicBinding(binding) ? binding.baseTilesSha256 : null;
            const response = await fetch(hwihaTerrainUrl(base), { signal: controller.signal });
            if (!response.ok) throw new Error(`지형을 받지 못했습니다(${response.status})`);
            const hash = parseTerrainEtagHash(response.headers.get('etag'));
            const tiles = (await response.json()) as HanTiles;
            const juAddress = juUrlForTerrain(hwihaTerrainUrl(base));
            if (juAddress && tiles.parentRegions) {
                try {
                    const juResponse = await fetch(juAddress, { signal: controller.signal });
                    if (juResponse.ok) {
                        const assigned = verifiedJuByParent(await juResponse.json() as JuIndexResponse,
                            hash, tiles.parentRegions.length);
                        if (assigned) tiles.parentRegions = tiles.parentRegions.map((parent, index) => ({
                            ...parent, ju: assigned[index],
                        }));
                    }
                } catch { if (controller.signal.aborted) return; }
            }
            // 구역 식별 PNG 가 없어도 지도는 그린다 — 안개·군단 위치만 빠진다.
            const provinceMap = await loadProvinceIdentityMap(HWIHA_PROVINCES_URL).catch(() => null);
            setRaw({ kind: 'loaded', preview, tiles, hash, provinceMap });
        })().catch((e: unknown) => {
            if (controller.signal.aborted) return;
            setRaw({ kind: 'error', message: e instanceof Error ? e.message : '지도를 불러오지 못했습니다.' });
        });
        return () => controller.abort();
    }, [refreshKey]);

    return useMemo<HwihaMapState>(() => {
        if (raw.kind !== 'loaded') return raw;
        const { preview, tiles, hash, provinceMap } = raw;
        const nations = new Map(preview.nations.map((n) => [n.id, n]));
        const colorOf = (nationId: number) => ({
            nationColor: nations.get(nationId)?.color,
            nationName: nations.get(nationId)?.name,
        });
        const sourceSize = { width: preview.width || 700, height: preview.height || 610 };
        const cities = buildHwihaCities(preview);
        return {
            kind: 'ready',
            preview,
            tiles,
            tilesSha256: hash ?? undefined,
            provinceMap,
            provinceCenter: buildProvinceCenters(tiles, provinceMap),
            cities,
            markerPositions: buildMarkerPositions(cities, tiles, sourceSize),
            commanderies: buildCommanderies(tiles, preview),
            legend: buildLegend(preview),
            sourceSize,
            administrativeOwnership:
                preview.provinceOccupancy?.length && preview.jurisdictionOwnership?.length && preview.commanderyControl?.length
                    ? {
                        provinceOccupancy: preview.provinceOccupancy.map((o) => ({ ...o, ...colorOf(o.nationId) })),
                        jurisdictionOwnership: preview.jurisdictionOwnership.map((o) => ({ ...o, ...colorOf(o.nationId) })),
                        commanderyControl: preview.commanderyControl.map((o) => ({ ...o, ...colorOf(o.nationId) })),
                    }
                    : undefined,
        };
    }, [raw]);
}
