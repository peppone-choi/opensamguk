// 제품 화면이 bake 장소 표(places.json)를 가볍게 읽는다: 구역 수(세력색 대조) · 城 칸(초점 · 내 위치) · 구역 대표 칸(군단 자리).
// 렌더러와 같은 URL을 같은 캐시(fetchJson · fetchBytes keep)로 받으니 요청이 늘지 않는다.
import { fetchJson, fetchOverview, joinUrl } from './loaders';
import { parsePlaces, type PlacesData } from './places';
import type { TopdownSource } from './renderer';
import type { BakeManifest, CellPoint } from './types';

export async function loadBakePlaces(source: TopdownSource): Promise<PlacesData> {
  const manifest = await fetchJson<BakeManifest>(joinUrl(source.bakeUrl, 'manifest.json'));
  return parsePlaces(await fetchJson<unknown>(joinUrl(source.bakeUrl, manifest.places.file)));
}

/** Centre of a city's footprint on the map grid, or null when the bake has no such city. */
export function cityCell(places: PlacesData, cityId: number): CellPoint | null {
  const city = places.cities.find((entry) => entry.id === cityId);
  if (!city) return null;
  const { originCol, originRow, span } = city.footprint;
  return { col: originCol + span / 2, row: originRow + span / 2 };
}

/**
 * 구역마다 대표 칸 하나: bake 개관 격자에서 무게중심에 가장 가까운, 그 구역에 속한 블록의 가운데 칸(정수 칸).
 * 개관 격자 값은 구역 번호 + 1(0 = 구역 없음)이고 블록 하나가 block × block 칸이다. 격자에 없는 구역은 null.
 * 옛 省 식별 PNG(운영 24.7MB — 16MiB 상한으로 버려진다) 없이 군단 · 행군 경로 자리를 구한다.
 */
export function provinceCentersFromOverview(
  provinces: Uint16Array, cols: number, block: number, provinceCount: number,
): (CellPoint | null)[] {
  const sumCol = new Float64Array(provinceCount);
  const sumRow = new Float64Array(provinceCount);
  const count = new Uint32Array(provinceCount);
  for (let i = 0; i < provinces.length; i += 1) {
    const p = provinces[i] - 1;
    if (p < 0 || p >= provinceCount) continue;
    sumCol[p] += i % cols;
    sumRow[p] += Math.floor(i / cols);
    count[p] += 1;
  }
  const best = new Int32Array(provinceCount).fill(-1);
  const bestDistance = new Float64Array(provinceCount).fill(Infinity);
  for (let i = 0; i < provinces.length; i += 1) {
    const p = provinces[i] - 1;
    if (p < 0 || p >= provinceCount) continue;
    const distance = (i % cols - sumCol[p] / count[p]) ** 2 + (Math.floor(i / cols) - sumRow[p] / count[p]) ** 2;
    if (distance < bestDistance[p]) { bestDistance[p] = distance; best[p] = i; }
  }
  const half = Math.floor(block / 2);
  return Array.from(best, (i) => (i < 0 ? null : { col: (i % cols) * block + half, row: Math.floor(i / cols) * block + half }));
}

export async function loadBakeProvinceCenters(source: TopdownSource): Promise<(CellPoint | null)[]> {
  const manifest = await fetchJson<BakeManifest>(joinUrl(source.bakeUrl, 'manifest.json'));
  const places = parsePlaces(await fetchJson<unknown>(joinUrl(source.bakeUrl, manifest.places.file)));
  const overview = await fetchOverview(source.bakeUrl, manifest);
  return provinceCentersFromOverview(overview.provinces, manifest.overview.cols, manifest.overview.block, places.provinceCount);
}
