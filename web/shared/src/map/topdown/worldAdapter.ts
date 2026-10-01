// 서버 지도 미리보기(preview) → 탑다운 지도 세계 상태 · 원천 URL. 제품 화면 교체(K2 3단계)의 자료 층이다.
// 구역 번호: 서버 provinceIndex = han-tiles provinceRecords 순서의 0부터 번호(모든 구역),
// bake 구역 평면 값 n = provinceRecords[n − 1], 엔진도 provinceIndex + 1을 쓴다 — 같은 원천 · 같은 순서라 번호로 잇는다.
// 서버는 tiles 지문이 맞을 때만 bakeId를 준다. 화면은 개수 · 번호 범위를 한 번 더 보고, 어긋나면 칠하지 않는다.
import type { TopdownSource, WorldNation, WorldState } from './renderer';

export interface PreviewProvinceOccupancy { provinceRecordId: string; provinceIndex: number; nationId: number }
export interface PreviewNation { id: number; name: string; color: string }
export interface TopdownPreview {
  nations: readonly PreviewNation[];
  provinceOccupancy?: readonly PreviewProvinceOccupancy[];
  topdownBakeId?: string | null;
}

export type WorldFromPreview = { ok: true; world: WorldState } | { ok: false; reason: string };

/** Occupancy and nations for the renderer, or why the preview does not fit this bake (`provinceCount` from places.json). */
export function worldFromPreview(preview: TopdownPreview, provinceCount: number): WorldFromPreview {
  const occupancy = preview.provinceOccupancy ?? [];
  if (occupancy.length !== provinceCount) {
    return { ok: false, reason: `구역 수가 다릅니다(서버 ${occupancy.length}, 지도 ${provinceCount})` };
  }
  const seen = new Uint8Array(provinceCount);
  for (const { provinceIndex } of occupancy) {
    if (!Number.isInteger(provinceIndex) || provinceIndex < 0 || provinceIndex >= provinceCount) {
      return { ok: false, reason: `구역 번호 ${provinceIndex}가 지도 범위 밖입니다` };
    }
    if (seen[provinceIndex]) return { ok: false, reason: `구역 번호 ${provinceIndex}가 두 번 나옵니다` };
    seen[provinceIndex] = 1;
  }
  const nations: WorldNation[] = preview.nations.map(({ id, name, color }) => ({ id, name, color }));
  return { ok: true, world: { occupancy: occupancy.map(({ provinceIndex, nationId }) => ({ provinceIndex, nationId })), nations } };
}

/** Approved kit export served from both apps' public folders (opensamguk-images waryong/map, owner-accepted). */
export const TOPDOWN_KIT_URL = '/map/waryong/273d596';
const BAKE_ID = /^[0-9a-f]{64}$/;

/**
 * Where to read the bake for a preview's `topdownBakeId` (via the game proxy). Null when the server offered none —
 * the screen then keeps the old map. `serverId` picks the game server the way the other map reads do; the query
 * rides on `bakeUrl` and joinUrl keeps it on every bake file.
 */
export function topdownSourceFor(bakeId: string | null | undefined, serverId?: string): TopdownSource | null {
  if (!bakeId || !BAKE_ID.test(bakeId)) return null;
  const query = serverId ? `?server=${encodeURIComponent(serverId)}` : '';
  return { bakeUrl: `/api/game/api/map/topdown/${bakeId}${query}`, kitUrl: TOPDOWN_KIT_URL };
}

/**
 * Product-screen switch. Separate from NEXT_PUBLIC_MAP_RENDERER (lab pages and the CI smoke build turn that one on).
 * Read as a literal `process.env.NEXT_PUBLIC_…` so Next inlines it into client bundles.
 */
export function topdownScreensEnabled(value: string | undefined = process.env.NEXT_PUBLIC_TOPDOWN_SCREENS): boolean {
  return value === '1';
}
