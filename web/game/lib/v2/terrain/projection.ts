// OPENSAM-41 [G0-C] T1-G09/G10 정사영 투영 + 공유 picking (G0C-b~e).
//
// 렌더러(three.js)와 picking이 **같은 수식**을 쓰도록 순수 TS로 둔다.
// three.Raycaster를 쓰지 않는 이유: 렌더와 판정이 서로 다른 수학을 타면
// "화면에서 집은 것"과 "read model이 돌려준 것"이 조용히 어긋난다.
//
// 서버가 좌표·가시성·충돌·피해를 계산한다(T1-G10). 여기 있는 건 표현뿐이며
// 카메라/LOD는 어떤 판정에도 입력되지 않는다.

import type {
  BattlefieldSeed,
  CommandCamera,
  GeographyVersions,
  OperationPath,
  PhysicalPlace,
  RouteCorridor,
  SpaceReadModel,
  Viewport,
  WorldPoint,
} from './contracts';
import { PROJECTION_VERSION } from './contracts';

export interface ScreenPoint {
  readonly sx: number;
  readonly sy: number;
  /** 카메라에서 멀수록 큼. 동률 picking 타이브레이크에 쓴다. */
  readonly depth: number;
}

/** 투영 버전이 다른 두 read model은 좌표 비교 자체가 무의미하다 → 하드 거부. */
export class ProjectionVersionMismatchError extends Error {
  constructor(
    readonly expected: string,
    readonly actual: string,
  ) {
    super(`projectionVersion mismatch: expected ${expected}, got ${actual}`);
    this.name = 'ProjectionVersionMismatchError';
  }
}

export function assertSameProjection(a: GeographyVersions, b: GeographyVersions): void {
  if (a.projectionVersion !== b.projectionVersion) {
    throw new ProjectionVersionMismatchError(a.projectionVersion, b.projectionVersion);
  }
}

export function assertSupportedProjection(v: GeographyVersions): void {
  if (v.projectionVersion !== PROJECTION_VERSION) {
    throw new ProjectionVersionMismatchError(PROJECTION_VERSION, v.projectionVersion);
  }
}

/**
 * 정사영(orthographic). 원근 나눗셈이 없으므로 화면 거리 = 월드 거리 × zoom 이고,
 * 그 덕분에 picking 허용반경을 월드 단위로 되돌릴 수 있다.
 */
export function project(p: WorldPoint, cam: CommandCamera, vp: Viewport): ScreenPoint {
  const dx = p.x - cam.target.x;
  const dy = p.y - cam.target.y;
  const dz = p.z - cam.target.z;

  const ca = Math.cos(cam.azimuth);
  const sa = Math.sin(cam.azimuth);
  const ex = dx * ca + dz * sa;
  const ez = -dx * sa + dz * ca;

  const cp = Math.cos(cam.pitch);
  const sp = Math.sin(cam.pitch);
  // 화면 y는 아래로 증가한다. 고도(dy)가 클수록 위로 올라가야 하므로 부호가 음수.
  const up = ez * cp - dy * sp;

  return {
    sx: vp.width / 2 + ex * cam.zoom,
    sy: vp.height / 2 + up * cam.zoom,
    depth: ez * sp + dy * cp,
  };
}

/** project()의 역함수 — 평면(y=평면고도) 위 한 점으로 되돌린다. */
export function unproject(s: { sx: number; sy: number }, cam: CommandCamera, vp: Viewport, planeY = 0): WorldPoint {
  const ex = (s.sx - vp.width / 2) / cam.zoom;
  const up = (s.sy - vp.height / 2) / cam.zoom;

  const cp = Math.cos(cam.pitch);
  const sp = Math.sin(cam.pitch);
  const dy = planeY - cam.target.y;
  // up = ez*cp - dy*sp  →  ez = (up + dy*sp) / cp
  if (Math.abs(cp) < 1e-9) throw new Error('unproject: pitch가 90°라 평면 역투영이 불가능하다');
  const ez = (up + dy * sp) / cp;

  const ca = Math.cos(cam.azimuth);
  const sa = Math.sin(cam.azimuth);
  // ex =  dx*ca + dz*sa ; ez = -dx*sa + dz*ca  (회전 역변환)
  const dx = ex * ca - ez * sa;
  const dz = ex * sa + ez * ca;

  return { x: cam.target.x + dx, y: planeY, z: cam.target.z + dz };
}

// ─────────────────────────────────────────────────────────────────────────────
// G0C-b 공유 picking 왕복 — PhysicalPlace
// ─────────────────────────────────────────────────────────────────────────────

export interface PlacePick {
  readonly placeId: string;
  readonly placeIdentityKey: string;
  readonly screen: ScreenPoint;
  /** 화면 픽셀 거리. */
  readonly distancePx: number;
}

/**
 * 화면 좌표에서 가장 가까운 거점을 집는다. 동률이면 카메라에 가까운 쪽(depth 작은 쪽),
 * 그래도 동률이면 placeId 사전순 — 결정적이어야 replay가 재현된다.
 */
export function pickPlace(
  point: { sx: number; sy: number },
  places: readonly PhysicalPlace[],
  cam: CommandCamera,
  vp: Viewport,
  hitRadiusPx = 12,
): PlacePick | null {
  let best: PlacePick | null = null;
  let bestDepth = Number.POSITIVE_INFINITY;

  for (const place of places) {
    const screen = project(place.position, cam, vp);
    const distancePx = Math.hypot(screen.sx - point.sx, screen.sy - point.sy);
    if (distancePx > hitRadiusPx) continue;

    if (best === null) {
      best = { placeId: place.placeId, placeIdentityKey: place.placeIdentityKey, screen, distancePx };
      bestDepth = screen.depth;
      continue;
    }
    const closer =
      distancePx < best.distancePx - 1e-9 ||
      (Math.abs(distancePx - best.distancePx) <= 1e-9 &&
        (screen.depth < bestDepth - 1e-9 ||
          (Math.abs(screen.depth - bestDepth) <= 1e-9 && place.placeId < best.placeId)));
    if (closer) {
      best = { placeId: place.placeId, placeIdentityKey: place.placeIdentityKey, screen, distancePx };
      bestDepth = screen.depth;
    }
  }
  return best;
}

/** 거점 → 화면 → 거점 왕복. 같은 identity가 돌아오지 않으면 실패 이유를 담아 반환한다. */
export function roundTripPlace(
  place: PhysicalPlace,
  model: SpaceReadModel,
  cam: CommandCamera,
  vp: Viewport,
): { ok: true; pick: PlacePick } | { ok: false; reason: string } {
  assertSameProjection(model.versions, cam.versions);
  const screen = project(place.position, cam, vp);
  const pick = pickPlace(screen, model.places, cam, vp);
  if (!pick) return { ok: false, reason: `${place.placeId}: 투영점에서 아무것도 집히지 않음` };
  if (pick.placeId !== place.placeId) {
    return { ok: false, reason: `${place.placeId}: ${pick.placeId}가 집힘(마커 겹침)` };
  }
  if (pick.placeIdentityKey !== place.placeIdentityKey) {
    return { ok: false, reason: `${place.placeId}: identityKey 불일치` };
  }
  return { ok: true, pick };
}

// ─────────────────────────────────────────────────────────────────────────────
// G0C-c 작전 경로 왕복 — corridor polyline 위 한 점 → corridorId → operationId
// ─────────────────────────────────────────────────────────────────────────────

export interface CorridorPick {
  readonly corridorId: string;
  /** polyline 상 몇 번째 선분인지. */
  readonly segmentIndex: number;
  readonly distancePx: number;
}

function pointSegmentDistance(
  p: { sx: number; sy: number },
  a: ScreenPoint,
  b: ScreenPoint,
): number {
  const vx = b.sx - a.sx;
  const vy = b.sy - a.sy;
  const len2 = vx * vx + vy * vy;
  const t = len2 === 0 ? 0 : Math.max(0, Math.min(1, ((p.sx - a.sx) * vx + (p.sy - a.sy) * vy) / len2));
  return Math.hypot(a.sx + t * vx - p.sx, a.sy + t * vy - p.sy);
}

export function pickCorridor(
  point: { sx: number; sy: number },
  corridors: readonly RouteCorridor[],
  cam: CommandCamera,
  vp: Viewport,
  hitRadiusPx = 8,
): CorridorPick | null {
  let best: CorridorPick | null = null;
  for (const corridor of corridors) {
    const pts = corridor.geometry.map((g) => project(g, cam, vp));
    for (let i = 0; i + 1 < pts.length; i += 1) {
      const distancePx = pointSegmentDistance(point, pts[i], pts[i + 1]);
      if (distancePx > hitRadiusPx) continue;
      const closer =
        best === null ||
        distancePx < best.distancePx - 1e-9 ||
        (Math.abs(distancePx - best.distancePx) <= 1e-9 && corridor.corridorId < best.corridorId);
      if (closer) best = { corridorId: corridor.corridorId, segmentIndex: i, distancePx };
    }
  }
  return best;
}

/** 작전 경로의 각 corridor 중점을 투영 → 집기 → 같은 corridor·같은 작전으로 복귀. */
export function roundTripOperationPath(
  op: OperationPath,
  model: SpaceReadModel,
  cam: CommandCamera,
  vp: Viewport,
): { ok: true; picks: readonly CorridorPick[] } | { ok: false; reason: string } {
  assertSameProjection(model.versions, cam.versions);
  assertSameProjection(model.versions, op.versions);

  const byId = new Map(model.corridors.map((c) => [c.corridorId, c]));
  const picks: CorridorPick[] = [];
  for (const corridorId of op.corridorIds) {
    const corridor = byId.get(corridorId);
    if (!corridor) return { ok: false, reason: `${op.operationId}: corridor ${corridorId} 미존재` };
    // 중점 선분의 중앙점 — 끝점은 인접 corridor와 공유되므로 집으면 모호하다.
    const mid = Math.max(0, Math.floor((corridor.geometry.length - 1) / 2));
    const a = project(corridor.geometry[mid], cam, vp);
    const b = project(corridor.geometry[mid + 1] ?? corridor.geometry[mid], cam, vp);
    const pick = pickCorridor({ sx: (a.sx + b.sx) / 2, sy: (a.sy + b.sy) / 2 }, model.corridors, cam, vp);
    if (!pick) return { ok: false, reason: `${corridorId}: 투영점에서 경로가 집히지 않음` };
    if (pick.corridorId !== corridorId) {
      return { ok: false, reason: `${corridorId}: ${pick.corridorId}가 집힘` };
    }
    picks.push(pick);
  }

  const owning = model.operations.filter((o) => picks.every((p) => o.corridorIds.includes(p.corridorId)));
  if (!owning.some((o) => o.operationId === op.operationId)) {
    return { ok: false, reason: `${op.operationId}: 집힌 corridor 집합이 작전으로 되돌아오지 않음` };
  }
  return { ok: true, picks };
}

// ─────────────────────────────────────────────────────────────────────────────
// G0C-d 전장 anchor 왕복 — 전략 좌표 ↔ 전술 seed
// ─────────────────────────────────────────────────────────────────────────────

export function roundTripBattlefieldAnchor(
  seed: BattlefieldSeed,
  model: SpaceReadModel,
  cam: CommandCamera,
  vp: Viewport,
  tolerance = 1e-6,
): { ok: true } | { ok: false; reason: string } {
  // 전술 scene은 전략 scene과 같은 projectionVersion을 써야 한다(T1-G09).
  assertSameProjection(model.versions, seed.versions);
  assertSameProjection(model.versions, cam.versions);

  const screen = project(seed.engagementGeoAnchor, cam, vp);
  const back = unproject(screen, cam, vp, seed.engagementGeoAnchor.y);
  const drift = Math.hypot(back.x - seed.engagementGeoAnchor.x, back.z - seed.engagementGeoAnchor.z);
  if (drift > tolerance) {
    return { ok: false, reason: `${seed.operationId}: anchor 역투영 오차 ${drift}` };
  }
  if (!model.operations.some((o) => o.operationId === seed.operationId)) {
    return { ok: false, reason: `${seed.operationId}: 전략 read model에 해당 작전이 없다` };
  }
  return { ok: true };
}

// ─────────────────────────────────────────────────────────────────────────────
// G0C-e replay camera 왕복 — 직렬화 → 복원 → 같은 화면
// ─────────────────────────────────────────────────────────────────────────────

export function serializeCamera(cam: CommandCamera): string {
  // 키 순서 고정 — 문자열 자체가 replay 비교 대상이 된다.
  return JSON.stringify({
    target: [cam.target.x, cam.target.y, cam.target.z],
    zoom: cam.zoom,
    azimuth: cam.azimuth,
    pitch: cam.pitch,
    versions: [
      cam.versions.geographyVersion,
      cam.versions.terrainTileVersion,
      cam.versions.projectionVersion,
      cam.versions.assetManifestVersion,
    ],
  });
}

export function deserializeCamera(raw: string): CommandCamera {
  const j = JSON.parse(raw) as {
    target: [number, number, number];
    zoom: number;
    azimuth: number;
    pitch: number;
    versions: [string, string, string, string];
  };
  const cam: CommandCamera = {
    target: { x: j.target[0], y: j.target[1], z: j.target[2] },
    zoom: j.zoom,
    azimuth: j.azimuth,
    pitch: j.pitch,
    versions: {
      geographyVersion: j.versions[0],
      terrainTileVersion: j.versions[1],
      projectionVersion: j.versions[2],
      assetManifestVersion: j.versions[3],
    },
  };
  // 저장 당시와 투영 수식이 다르면 같은 화면이 재현되지 않는다 → 조용히 그리지 않는다.
  assertSupportedProjection(cam.versions);
  return cam;
}

/** 카메라 상태에서 나온 화면 배치의 지문. 같으면 같은 그림이다. */
export function projectionFingerprint(
  model: SpaceReadModel,
  cam: CommandCamera,
  vp: Viewport,
): string {
  const round = (n: number) => Math.round(n * 1e6) / 1e6;
  return model.places
    .map((p) => {
      const s = project(p.position, cam, vp);
      return `${p.placeId}:${round(s.sx)},${round(s.sy)},${round(s.depth)}`;
    })
    .join('|');
}

export function roundTripReplayCamera(
  cam: CommandCamera,
  model: SpaceReadModel,
  vp: Viewport,
): { ok: true; fingerprint: string } | { ok: false; reason: string } {
  const restored = deserializeCamera(serializeCamera(cam));
  const before = projectionFingerprint(model, cam, vp);
  const after = projectionFingerprint(model, restored, vp);
  if (before !== after) return { ok: false, reason: 'replay 카메라 복원 후 화면 배치가 달라졌다' };
  if (serializeCamera(restored) !== serializeCamera(cam)) {
    return { ok: false, reason: 'replay 카메라 재직렬화가 원본과 다르다' };
  }
  return { ok: true, fingerprint: after };
}
