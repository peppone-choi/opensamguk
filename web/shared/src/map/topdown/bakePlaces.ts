// 제품 화면이 bake 장소 표(places.json)를 가볍게 읽는다: 구역 수(세력색 대조) · 城 칸(초점 · 내 위치).
// 렌더러와 같은 URL을 같은 캐시(fetchJson keep)로 받으니 요청이 늘지 않는다.
import { fetchJson, joinUrl } from './loaders';
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
