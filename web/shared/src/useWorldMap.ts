'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import { isOwnedNationVisual } from './nationVisual';
import { loadProvinceIdentityMap, type ProvinceIdentityMap } from './provinceMap';
import { buildCanonicalMarkerPositions, parseTerrainEtagHash } from './WorldMapCanvas';
import { juUrlForTerrain, verifiedJuByParent, type JuIndexResponse } from './iso/juLod';
import { validStrategicBinding, type StrategicTopologyBinding } from './strategicMap';
import type { WorldTiles, IsoCityOverlay, IsoMarkerPosition } from './WorldMapCanvas';
import { cityBadgesById } from './worldCityBadges';

export const WORLD_MAP_CODE = 'han-world-v3';

export interface WorldMapPreview {
  mapCode: string;
  width: number;
  height: number;
  cities: IsoCityOverlay[];
  nations: { id: number; name: string; color: string }[];
  strategicTopology?: StrategicTopologyBinding | null;
  provinceOccupancy?: { provinceRecordId: string; provinceIndex: number; nationId: number }[];
  jurisdictionOwnership?: { jurisdictionId: string; nationId: number }[];
  commanderyControl?: { commanderyId: string; nationId: number }[];
}

export interface CommanderyCell {
  readonly no: number;
  readonly name: string;
  readonly col: number;
  readonly row: number;
  readonly focusCityId: number | null;
}

export interface LegendEntry {
  readonly nationId: number;
  readonly name: string;
  readonly color: string;
  readonly cities: number;
}

export type WorldMapState<P extends WorldMapPreview> =
  | { readonly kind: 'loading' }
  | { readonly kind: 'error'; readonly message: string }
  | { readonly kind: 'unsupported'; readonly mapCode: string }
  | {
    readonly kind: 'ready';
    readonly preview: P;
    readonly refreshError?: string;
    readonly tiles: WorldTiles;
    readonly tilesSha256: string | undefined;
    readonly provinceMap: ProvinceIdentityMap | null;
    readonly provinceCenter: (provinceId: string) => { col: number; row: number } | undefined;
    readonly cities: readonly IsoCityOverlay[];
    readonly markerPositions: ReadonlyMap<number, IsoMarkerPosition>;
    readonly commanderies: readonly CommanderyCell[];
    readonly legend: readonly LegendEntry[];
    readonly sourceSize: { width: number; height: number };
    readonly administrativeOwnership: {
      provinceOccupancy: NonNullable<WorldMapPreview['provinceOccupancy']>;
      jurisdictionOwnership: NonNullable<WorldMapPreview['jurisdictionOwnership']>;
      commanderyControl: NonNullable<WorldMapPreview['commanderyControl']>;
    } | undefined;
  };

const NEUTRAL_NAME = '공백지';

export function worldTerrainUrl(baseTilesSha256: string | null, serverId?: string): string {
  const server = serverId ? `server=${encodeURIComponent(serverId)}&` : '';
  return `/api/game/api/map/terrain?${server}mapCode=${WORLD_MAP_CODE}`
    + (baseTilesSha256 ? `&baseTilesSha256=${encodeURIComponent(baseTilesSha256)}` : '');
}

export function worldProvincesUrl(serverId?: string): string {
  const server = serverId ? `server=${encodeURIComponent(serverId)}&` : '';
  return `/api/game/api/map/provinces?${server}mapCode=${WORLD_MAP_CODE}`;
}

export function buildWorldCities(preview: WorldMapPreview, badges = cityBadgesById(preview.cities, null, null)): IsoCityOverlay[] {
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
      cityBadges: (badges.get(city.id) ?? []).filter((badge) => badge.kind !== 'event'),
      interactive: true,
    } satisfies IsoCityOverlay;
  });
}

/**
* 城 아이콘을 자기 省의 치소 칸에 앉힌다. 省 색인이 없을 때만 원본 좌표의 칸을 쓴다.
*/
export function buildMarkerPositions(
  cities: readonly IsoCityOverlay[],
  tiles: WorldTiles,
  sourceSize: { width: number; height: number },
  provinceMap: ProvinceIdentityMap | null = null,
): Map<number, IsoMarkerPosition> {
  return buildCanonicalMarkerPositions(tiles, cities, sourceSize, provinceMap);
}

/**
* 지형의 juns → 군국 표. 초점 城 은 미리보기에서 **이름이 같은 군국의 치소**를 먼저, 없으면 그
* 군국의 아무 城 이나 id 가 가장 작은 것을 고른다. 城 없는 군국은 초점이 null 이다 — 지어내지 않는다.
*/
export function buildCommanderies(tiles: WorldTiles, preview: WorldMapPreview): CommanderyCell[] {
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
  tiles: WorldTiles,
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

export function buildLegend(preview: WorldMapPreview): LegendEntry[] {
  const counts = new Map<number, number>();
  for (const city of preview.cities) counts.set(city.nationId, (counts.get(city.nationId) ?? 0) + 1);
  return preview.nations
    .filter((n) => isOwnedNationVisual(n.id, n.color))
    .map((n) => ({ nationId: n.id, name: n.name, color: n.color, cities: counts.get(n.id) ?? 0 }))
    .sort((a, b) => b.cities - a.cities || a.nationId - b.nationId);
}


export interface WorldMapOptions<P extends WorldMapPreview> {
  loadPreview: (signal: AbortSignal) => Promise<P>;
  mapData?: P | null;
  refreshKey?: unknown;
  serverId?: string;
  cacheScope?: string;
  works?: Parameters<typeof cityBadgesById>[1];
  sieges?: Parameters<typeof cityBadgesById>[2];
}

/** All maps consume the same served terrain and the same city placement rules. */
export function useWorldMap<P extends WorldMapPreview>({
  loadPreview, mapData, refreshKey = 0, serverId, cacheScope, works = null, sieges = null,
}: WorldMapOptions<P>): WorldMapState<P> {
  const terrainCache = useRef<{ base: string; scope: string | undefined; serverId: string | undefined;
    tiles: WorldTiles; hash: string | null; provinceMap: ProvinceIdentityMap | null } | null>(null);
  const [raw, setRaw] = useState<
    | { kind: 'loading' }
    | { kind: 'error'; message: string }
    | { kind: 'unsupported'; mapCode: string }
    | { kind: 'loaded'; preview: P; tiles: WorldTiles; hash: string | null; provinceMap: ProvinceIdentityMap | null; refreshError?: string }
  >({ kind: 'loading' });

  useEffect(() => {
    const controller = new AbortController();
    (async () => {
      const preview = mapData ?? await loadPreview(controller.signal);
      if (controller.signal.aborted) return;
      if (preview.mapCode !== WORLD_MAP_CODE) {
        setRaw({ kind: 'unsupported', mapCode: preview.mapCode });
        return;
      }
      const binding = preview.strategicTopology;
      const base = binding && validStrategicBinding(binding) ? binding.baseTilesSha256 : null;
      const cached = terrainCache.current;
      if (base && cached?.base === base && cached.scope === cacheScope && cached.serverId === serverId) {
        setRaw({ kind: 'loaded', preview: { ...preview }, tiles: cached.tiles, hash: cached.hash, provinceMap: cached.provinceMap });
        return;
      }
      const terrainUrl = worldTerrainUrl(base, serverId);
      const response = await fetch(terrainUrl, { signal: controller.signal });
      if (!response.ok) throw new Error(`지형을 받지 못했습니다(${response.status})`);
      const hash = parseTerrainEtagHash(response.headers.get('etag'));
      const tiles = (await response.json()) as WorldTiles;
      // Province identity is loaded before the optional Ju index. A missing PNG only disables overlays.
      const provinceMap = await loadProvinceIdentityMap(worldProvincesUrl(serverId)).catch(() => null);
      if (controller.signal.aborted) return;
      const juAddress = juUrlForTerrain(terrainUrl);
      if (juAddress && tiles.parentRegions) {
        try {
          const juResponse = await fetch(juAddress, { signal: controller.signal });
          if (juResponse.ok) {
            const assigned = verifiedJuByParent(await juResponse.json() as JuIndexResponse,
              hash, tiles.parentRegions.length);
            if (assigned) tiles.parentRegions = tiles.parentRegions.map((parent, index) => ({ ...parent, ju: assigned[index] }));
          }
        } catch { if (controller.signal.aborted) return; }
      }
      if (controller.signal.aborted) return;
      if (base) terrainCache.current = { base, scope: cacheScope, serverId, tiles, hash, provinceMap };
      setRaw({ kind: 'loaded', preview: { ...preview }, tiles, hash, provinceMap });
    })().catch((error: unknown) => {
      if (!controller.signal.aborted) {
        const message = error instanceof Error ? error.message : '지도를 불러오지 못했습니다.';
        setRaw((previous) => previous.kind === 'loaded'
          ? { ...previous, refreshError: message } : { kind: 'error', message });
      }
    });
    return () => controller.abort();
  }, [loadPreview, mapData, refreshKey, serverId, cacheScope]);

  const base = useMemo(() => {
    if (raw.kind !== 'loaded') return null;
    const { preview, tiles, provinceMap } = raw;
    const sourceSize = { width: preview.width || 700, height: preview.height || 610 };
    return {
      provinceCenter: buildProvinceCenters(tiles, provinceMap),
      markerPositions: buildMarkerPositions(buildWorldCities(preview), tiles, sourceSize, provinceMap),
      commanderies: buildCommanderies(tiles, preview),
      legend: buildLegend(preview),
      sourceSize,
    };
  }, [raw]);

  return useMemo<WorldMapState<P>>(() => {
    if (raw.kind !== 'loaded' || !base) return raw as WorldMapState<P>;
    const { preview, tiles, hash, provinceMap } = raw;
    const nations = new Map(preview.nations.map((nation) => [nation.id, nation]));
    const colorOf = (nationId: number) => ({
      nationColor: nations.get(nationId)?.color,
      nationName: nations.get(nationId)?.name,
    });
    const cities = buildWorldCities(preview, cityBadgesById(preview.cities, works, sieges));
    return {
      kind: 'ready', preview, refreshError: raw.refreshError, tiles, tilesSha256: hash ?? undefined, provinceMap,
      ...base, cities,
      administrativeOwnership: preview.provinceOccupancy?.length && preview.jurisdictionOwnership?.length
        && preview.commanderyControl?.length
        ? {
          provinceOccupancy: preview.provinceOccupancy.map((owner) => ({ ...owner, ...colorOf(owner.nationId) })),
          jurisdictionOwnership: preview.jurisdictionOwnership.map((owner) => ({ ...owner, ...colorOf(owner.nationId) })),
          commanderyControl: preview.commanderyControl.map((owner) => ({ ...owner, ...colorOf(owner.nationId) })),
        } : undefined,
    };
  }, [raw, base, works, sieges]);
}
