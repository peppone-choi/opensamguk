// 전술 아트 팩 목록(manifest.json) 계약 v1 — 렌더러 v2(boardComposer · battleCanvas)와 자산 처리 도구(tools/assets/artpack)가 함께 쓴다.
// 판 투영은 원작 그대로다(칸 (r, c) 발 = 판 px (r + c)·16 + 16, (r − c)·8 + side·8 + top + 8). 팩은 그 위에 얹을 그림만 정한다.
// - 프레임: 아틀라스 번호 + 사각형(팩 px) + 발 기준점(ax, ay — 프레임 안 좌표). 발 기준점이 칸 발(마름모 가운데)에 놓인다.
// - 배율: pixelsPerBoardPx = 판 px 1에 해당하는 팩 px(바닥 타일 너비 = 32 · 배율, 높이 ≥ 16 · 배율).
// - 방향: sw · nw 는 그려 넣고, ne · se 는 따로 주지 않으면 nw · sw 의 좌우 반전이다(반전하면 ax → w − ax).
// - 동작: idle(필수) · move · attack · hit · fall, 동작마다 장 수(frames)를 적고 방향마다 그 장 수만큼 프레임을 둔다.
// - 진영색: 프레임마다 같은 크기의 회색 마스크 프레임(masks)을 둘 수 있다. 렌더러가 진영색 × 회색 밝기로 칠한다.
// - 그림자: v1 은 구운 그림자(baked)만 — 프레임에 그림자가 들어 있다.
// - 아틀라스: 장당 2048 × 2048 이하. 순수 TS라 노드에서 시험한다.
import { UNIT_FACINGS, type UnitFacing } from '../battleUnits';

export const ARTPACK_SCHEMA_VERSION = 1;
export const ARTPACK_ARTIFACT_ID = 'opensamguk-battle-artpack';
export const ARTPACK_MANIFEST_FILE = 'manifest.json';
/** Atlas side limit (px). */
export const ARTPACK_ATLAS_MAX = 2048;
/** Pack px per board px: 1 (32 × 16 tiles) … 4 (128 × 64 tiles). */
export const ARTPACK_MAX_PIXELS_PER_BOARD_PX = 4;

/** Battle terrain letters (kit recordClass = server catalog): plain, forest, mountain (impassable), river, wall/gate. */
export const TERRAIN_LETTERS = ['P', 'F', 'M', 'R', 'W'] as const;
export type TerrainLetter = (typeof TERRAIN_LETTERS)[number];

/** Edge bits of a cell diamond, by neighbour: NE = (r, c + 1), SE = (r + 1, c), SW = (r, c − 1), NW = (r − 1, c). */
export const EDGE_NE = 1;
export const EDGE_SE = 2;
export const EDGE_SW = 4;
export const EDGE_NW = 8;
/** Letters whose edges may spill onto a neighbouring different letter (transition overlays). */
export const TRANSITION_LETTERS = ['R', 'F', 'M'] as const;
export type TransitionLetter = (typeof TRANSITION_LETTERS)[number];

/** Unit actions and their maximum frame counts (MVP: idle 1). */
export const ARTPACK_ACTIONS = { idle: 2, move: 4, attack: 4, hit: 2, fall: 4 } as const;
export type ArtpackAction = keyof typeof ARTPACK_ACTIONS;
/** Directions drawn in the pack; the other two default to their mirror. */
export const ARTPACK_DRAWN_FACINGS = ['sw', 'nw'] as const;
export const ARTPACK_DEFAULT_MIRROR: Record<UnitFacing, UnitFacing | null> = { sw: null, nw: null, ne: 'nw', se: 'sw' };

const SLOT_KEY = /^[a-z0-9][a-z0-9_.-]{0,63}$/;
const PACK_ID = /^[a-z0-9][a-z0-9_.-]{0,63}$/;

export interface ArtpackFrame {
  atlas: number;
  x: number;
  y: number;
  w: number;
  h: number;
  /** Foot anchor inside the frame (pack px). */
  ax: number;
  ay: number;
}

export interface ArtpackAtlas {
  /** File name next to manifest.json (PNG RGBA). */
  file: string;
  width: number;
  height: number;
  sha256?: string;
}

export interface ArtpackTerrain {
  /** Ground tile variants (≥ 1), width 32 · pixelsPerBoardPx, anchor = diamond centre. */
  ground: ArtpackFrame[];
  /** Standing piece variants drawn back to front (trees, rocks, wall blocks). */
  tall?: ArtpackFrame[];
  /** Standing piece by same-letter neighbour edges (index = EDGE_* bits, 16 entries, null = use `tall`). */
  tallByEdges?: (ArtpackFrame | null)[];
}

export type ArtpackDirection = { frames: ArtpackFrame[]; masks?: ArtpackFrame[] } | { mirror: UnitFacing };

export interface ArtpackActionFrames {
  frames: number;
  directions: Partial<Record<UnitFacing, ArtpackDirection>>;
}

export interface ArtpackUnitSlot {
  actions: Partial<Record<ArtpackAction, ArtpackActionFrames>>;
}

export interface ArtpackProvenance {
  /** procedural = made in code (no image input); generated = image model; hand = drawn. */
  source: 'procedural' | 'generated' | 'hand';
  note: string;
  model?: string;
  prompt?: string;
  createdAt?: string;
  sourceSha256?: string;
  steps?: string[];
}

export interface ArtpackManifest {
  schemaVersion: 1;
  artifactId: typeof ARTPACK_ARTIFACT_ID;
  packId: string;
  pixelsPerBoardPx: number;
  shadow: 'baked';
  atlases: ArtpackAtlas[];
  terrain: Record<TerrainLetter, ArtpackTerrain>;
  /** Edge overlays per transition letter (16 entries by EDGE_* bits of neighbours with that letter; index 0 unused). */
  transitions?: Partial<Record<TransitionLetter, (ArtpackFrame | null)[]>>;
  /** Unit sprites by slot key (unit type code); a missing slot is drawn as the plain diamond. */
  units: Record<string, ArtpackUnitSlot>;
  provenance: ArtpackProvenance[];
}

const isInt = (value: unknown): value is number => typeof value === 'number' && Number.isInteger(value);

/** Every rule of the v1 contract; returns problems (empty = valid). */
export function validateArtpackManifest(value: unknown): string[] {
  const problems: string[] = [];
  const fail = (message: string) => problems.push(message);
  if (!value || typeof value !== 'object') return ['manifest: not an object'];
  const m = value as Partial<ArtpackManifest>;
  if (m.schemaVersion !== ARTPACK_SCHEMA_VERSION) fail(`schemaVersion: expected ${ARTPACK_SCHEMA_VERSION}`);
  if (m.artifactId !== ARTPACK_ARTIFACT_ID) fail(`artifactId: expected ${ARTPACK_ARTIFACT_ID}`);
  if (typeof m.packId !== 'string' || !PACK_ID.test(m.packId)) fail('packId: lower-case [a-z0-9_.-], 1–64 chars');
  const ppb = m.pixelsPerBoardPx;
  if (!isInt(ppb) || ppb < 1 || ppb > ARTPACK_MAX_PIXELS_PER_BOARD_PX) fail(`pixelsPerBoardPx: integer 1–${ARTPACK_MAX_PIXELS_PER_BOARD_PX}`);
  if (m.shadow !== 'baked') fail("shadow: v1 accepts 'baked' only");
  const atlases = Array.isArray(m.atlases) ? m.atlases : [];
  if (!Array.isArray(m.atlases) || atlases.length === 0) fail('atlases: at least one');
  atlases.forEach((a, i) => {
    if (!a || typeof a.file !== 'string' || !/^[A-Za-z0-9_.-]+\.png$/.test(a.file)) fail(`atlases[${i}].file: plain *.png name`);
    if (!isInt(a?.width) || !isInt(a?.height) || a.width < 1 || a.height < 1 || a.width > ARTPACK_ATLAS_MAX || a.height > ARTPACK_ATLAS_MAX) {
      fail(`atlases[${i}]: size must be 1–${ARTPACK_ATLAS_MAX} px per side`);
    }
    if (a?.sha256 !== undefined && !/^[0-9a-f]{64}$/.test(a.sha256)) fail(`atlases[${i}].sha256: 64 lower-case hex`);
  });
  const frame = (f: ArtpackFrame | null | undefined, where: string, size?: { w: number; minH: number }) => {
    if (!f || typeof f !== 'object') return fail(`${where}: missing frame`);
    if (![f.atlas, f.x, f.y, f.w, f.h].every(isInt) || f.w < 1 || f.h < 1 || f.x < 0 || f.y < 0) return fail(`${where}: integer rect with positive size`);
    const atlas = atlases[f.atlas];
    if (!atlas) return fail(`${where}: atlas ${f.atlas} does not exist`);
    if (f.x + f.w > atlas.width || f.y + f.h > atlas.height) fail(`${where}: outside atlas ${f.atlas}`);
    if (typeof f.ax !== 'number' || typeof f.ay !== 'number' || f.ax < 0 || f.ax > f.w || f.ay < 0 || f.ay > f.h) fail(`${where}: anchor outside the frame`);
    if (size && (f.w !== size.w || f.h < size.minH)) fail(`${where}: ground tile must be ${size.w} wide and ≥ ${size.minH} high`);
  };
  const ground = isInt(ppb) ? { w: 32 * ppb, minH: 16 * ppb } : undefined;
  for (const letter of TERRAIN_LETTERS) {
    const t = m.terrain?.[letter];
    if (!t || !Array.isArray(t.ground) || t.ground.length === 0) {
      fail(`terrain.${letter}.ground: at least one variant`);
      continue;
    }
    t.ground.forEach((f, i) => frame(f, `terrain.${letter}.ground[${i}]`, ground));
    t.tall?.forEach((f, i) => frame(f, `terrain.${letter}.tall[${i}]`));
    if (t.tallByEdges !== undefined) {
      if (!Array.isArray(t.tallByEdges) || t.tallByEdges.length !== 16) fail(`terrain.${letter}.tallByEdges: 16 entries`);
      else t.tallByEdges.forEach((f, i) => f !== null && frame(f, `terrain.${letter}.tallByEdges[${i}]`));
    }
  }
  for (const [letter, list] of Object.entries(m.transitions ?? {})) {
    if (!(TRANSITION_LETTERS as readonly string[]).includes(letter)) fail(`transitions.${letter}: not a transition letter`);
    if (!Array.isArray(list) || list.length !== 16) fail(`transitions.${letter}: 16 entries`);
    else list.forEach((f, i) => f !== null && (i === 0 ? fail(`transitions.${letter}[0]: must be null`) : frame(f, `transitions.${letter}[${i}]`, ground)));
  }
  const units = m.units && typeof m.units === 'object' && !Array.isArray(m.units) ? m.units : null;
  if (!units) fail('units: object by slot key (may be empty)');
  for (const [slot, unit] of Object.entries(units ?? {})) {
    const where = `units.${slot}`;
    if (!SLOT_KEY.test(slot)) fail(`${where}: slot key lower-case [a-z0-9_.-]`);
    if (!unit?.actions?.idle) fail(`${where}.actions.idle: required`);
    for (const [action, def] of Object.entries(unit?.actions ?? {})) {
      const max = ARTPACK_ACTIONS[action as ArtpackAction];
      if (max === undefined) {
        fail(`${where}.${action}: unknown action`);
        continue;
      }
      if (!isInt(def?.frames) || def.frames < 1 || def.frames > max) fail(`${where}.${action}.frames: 1–${max}`);
      for (const facing of ARTPACK_DRAWN_FACINGS) {
        const drawn = def?.directions?.[facing];
        if (!drawn) fail(`${where}.${action}.${facing}: drawn direction required`);
        else if (typeof drawn === 'object' && 'mirror' in drawn) fail(`${where}.${action}.${facing}: must be drawn (frames), not a mirror`);
      }
      for (const [facing, dir] of Object.entries(def?.directions ?? {})) {
        const at = `${where}.${action}.${facing}`;
        if (!(UNIT_FACINGS as readonly string[]).includes(facing)) {
          fail(`${at}: unknown direction`);
          continue;
        }
        if (!dir) continue;
        if ('mirror' in dir) {
          const target = def.directions[dir.mirror];
          if (!target || 'mirror' in target) fail(`${at}: mirror must name a drawn direction`);
          continue;
        }
        if (!Array.isArray(dir.frames) || dir.frames.length !== def.frames) fail(`${at}: ${def?.frames} frames expected`);
        dir.frames?.forEach((f, i) => frame(f, `${at}[${i}]`));
        if (dir.masks !== undefined) {
          if (!Array.isArray(dir.masks) || dir.masks.length !== dir.frames?.length) fail(`${at}.masks: one per frame`);
          else dir.masks.forEach((f, i) => {
            frame(f, `${at}.masks[${i}]`);
            const body = dir.frames[i];
            if (body && f && (f.w !== body.w || f.h !== body.h)) fail(`${at}.masks[${i}]: same size as the frame`);
          });
        }
      }
    }
  }
  if (!Array.isArray(m.provenance) || m.provenance.length === 0) fail('provenance: at least one entry');
  m.provenance?.forEach((p, i) => {
    if (!p || !['procedural', 'generated', 'hand'].includes(p.source)) fail(`provenance[${i}].source: procedural | generated | hand`);
    if (p?.source === 'generated' && (!p.model || !p.prompt || !p.createdAt)) fail(`provenance[${i}]: generated art needs model, prompt and createdAt`);
  });
  return problems;
}

export function parseArtpackManifest(value: unknown): ArtpackManifest {
  const problems = validateArtpackManifest(value);
  if (problems.length > 0) throw new Error(`battle artpack: ${problems.slice(0, 5).join('; ')}`);
  return value as ArtpackManifest;
}

export interface ResolvedUnitFrame {
  frame: ArtpackFrame;
  mask: ArtpackFrame | null;
  /** Draw mirrored left–right (anchor becomes w − ax). */
  flip: boolean;
}

/**
 * The frame to draw for a unit slot, or null when the pack has no art for it (draw the plain diamond).
 * Missing actions fall back to idle; `index` wraps over the action's frame count.
 */
export function resolveUnitFrame(manifest: ArtpackManifest, slot: string | undefined, action: ArtpackAction, facing: UnitFacing, index: number): ResolvedUnitFrame | null {
  if (!slot) return null;
  const unit = Object.prototype.hasOwnProperty.call(manifest.units, slot) ? manifest.units[slot] : undefined;
  const def = unit?.actions[action] ?? unit?.actions.idle;
  if (!def) return null;
  let dir = def.directions[facing];
  let flip = false;
  if (!dir && ARTPACK_DEFAULT_MIRROR[facing]) dir = { mirror: ARTPACK_DEFAULT_MIRROR[facing]! };
  if (dir && 'mirror' in dir) {
    flip = true;
    dir = def.directions[dir.mirror];
  }
  if (!dir || 'mirror' in dir || dir.frames.length === 0) return null;
  const i = ((Math.trunc(index) % dir.frames.length) + dir.frames.length) % dir.frames.length;
  return { frame: dir.frames[i], mask: dir.masks?.[i] ?? null, flip };
}
