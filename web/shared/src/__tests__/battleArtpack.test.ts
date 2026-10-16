import { createHash } from 'node:crypto';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  ARTPACK_ATLAS_MAX,
  resolveUnitFrame,
  validateArtpackManifest,
  type ArtpackFrame,
  type ArtpackManifest,
} from '../battle/artpack/manifest';
import { BATTLE_SPRITE_CACHE_LIMIT, LruCache } from '../battle/artpack/lru';
import { buildProceduralArtpack, proceduralArtpack } from '../battle/artpack/procedural';
import type { LoadedArtpack } from '../battle/artpack/loader';
import { artPictureResolution, drawArtUnit, type ArtUnit } from '../battle/battleCanvas';

const f = (x: number, w = 64, h = 32, ax = w / 2, ay = h / 2): ArtpackFrame => ({ atlas: 0, x, y: 0, w, h, ax, ay });

/** 최소 유효 팩: 배율 2, 바닥 5등급, 보병 한 칸(sw · nw 그림, 마스크 포함). */
function pack(): ArtpackManifest {
  const ground = (x: number) => ({ ground: [f(x)] });
  return {
    schemaVersion: 1,
    artifactId: 'opensamguk-battle-artpack',
    packId: 'test-pack',
    pixelsPerBoardPx: 2,
    shadow: 'baked',
    atlases: [{ file: 'atlas-0.png', width: 1024, height: 128 }],
    terrain: { P: ground(0), F: ground(64), M: ground(128), R: ground(192), W: ground(256) },
    units: {
      infantry: {
        actions: {
          idle: {
            frames: 1,
            directions: {
              sw: { frames: [f(320, 48, 48, 20, 44)], masks: [f(368, 48, 48, 20, 44)] },
              nw: { frames: [f(416, 48, 48, 24, 44)] },
            },
          },
          attack: {
            frames: 2,
            directions: { sw: { frames: [f(464, 48, 48), f(512, 48, 48)] }, nw: { frames: [f(560, 48, 48), f(608, 48, 48)] }, se: { mirror: 'sw' } },
          },
        },
      },
    },
    provenance: [{ source: 'hand', note: 'test' }],
  };
}

const mutate = (change: (m: ArtpackManifest) => void) => {
  const m = structuredClone(pack());
  change(m);
  return validateArtpackManifest(m);
};

describe('아트 팩 목록 계약 v1', () => {
  it('유효한 팩은 문제가 없다', () => {
    expect(validateArtpackManifest(pack())).toEqual([]);
  });

  it('아틀라스는 장당 2048 이하', () => {
    expect(mutate((m) => (m.atlases[0].width = ARTPACK_ATLAS_MAX + 1)).join()).toMatch(/size must be/);
    expect(mutate((m) => (m.atlases[0].height = ARTPACK_ATLAS_MAX))).toEqual([]);
  });

  it('프레임이 아틀라스 밖이거나 발 기준점이 프레임 밖이면 실패', () => {
    expect(mutate((m) => (m.terrain.P.ground[0].x = 1000)).join()).toMatch(/outside atlas/);
    expect(mutate((m) => (m.terrain.P.ground[0].atlas = 3)).join()).toMatch(/atlas 3 does not exist/);
    expect(mutate((m) => ((m.units.infantry.actions.idle!.directions.sw as { frames: ArtpackFrame[] }).frames[0].ay = 49)).join()).toMatch(/anchor outside/);
  });

  it('바닥 타일은 너비 32 · 배율, 높이 ≥ 16 · 배율', () => {
    expect(mutate((m) => (m.terrain.R.ground[0].w = 60)).join()).toMatch(/ground tile must be 64 wide/);
    expect(mutate((m) => (m.terrain.R.ground = [])).join()).toMatch(/terrain.R.ground: at least one/);
  });

  it('동작: idle 필수, 장 수 상한, 방향마다 장 수만큼, 그려 넣는 방향 sw · nw', () => {
    expect(mutate((m) => delete m.units.infantry.actions.idle).join()).toMatch(/idle: required/);
    expect(mutate((m) => (m.units.infantry.actions.attack!.frames = 5)).join()).toMatch(/attack.frames: 1–4/);
    expect(mutate((m) => (m.units.infantry.actions.attack!.frames = 3)).join()).toMatch(/3 frames expected/);
    expect(mutate((m) => delete m.units.infantry.actions.idle!.directions.nw).join()).toMatch(/nw: drawn direction required/);
    expect(mutate((m) => ((m.units.infantry.actions as Record<string, unknown>).dance = m.units.infantry.actions.idle)).join()).toMatch(/unknown action/);
  });

  it('units 는 필수 객체(빈 {} 는 허용) — 없으면 그리기 전에 거부', () => {
    expect(mutate((m) => (m.units = {}))).toEqual([]);
    expect(mutate((m) => delete (m as Partial<ArtpackManifest>).units).join()).toMatch(/units: object/);
    expect(mutate((m) => ((m as { units: unknown }).units = null)).join()).toMatch(/units: object/);
    expect(mutate((m) => ((m as { units: unknown }).units = [])).join()).toMatch(/units: object/);
  });

  it('sw · nw 는 반전이 아니라 직접 그린 프레임', () => {
    expect(mutate((m) => (m.units.infantry.actions.idle!.directions.sw = { mirror: 'nw' })).join()).toMatch(/idle.sw: must be drawn/);
    expect(mutate((m) => (m.units.infantry.actions.attack!.directions.nw = { mirror: 'sw' })).join()).toMatch(/attack.nw: must be drawn/);
  });

  it('반전은 그려 넣은 방향만 가리키고, 마스크는 프레임마다 같은 크기', () => {
    expect(mutate((m) => (m.units.infantry.actions.attack!.directions.ne = { mirror: 'se' })).join()).toMatch(/mirror must name a drawn direction/);
    expect(mutate((m) => ((m.units.infantry.actions.idle!.directions.sw as { masks: ArtpackFrame[] }).masks[0].w = 40)).join()).toMatch(/same size/);
  });

  it('칸 이름 · 팩 id · 출처 원장', () => {
    expect(mutate((m) => (m.units['Infantry!'] = m.units.infantry)).join()).toMatch(/slot key/);
    expect(mutate((m) => (m.packId = 'Bad Id')).join()).toMatch(/packId/);
    expect(mutate((m) => (m.provenance = [{ source: 'generated', note: 'x' }])).join()).toMatch(/model, prompt and createdAt/);
    expect(mutate((m) => (m.provenance = [])).join()).toMatch(/provenance: at least one/);
  });

  it('전이 목록은 16칸, 0번은 비운다', () => {
    const list: (ArtpackFrame | null)[] = Array.from({ length: 16 }, (_, i) => (i ? f(0) : null));
    expect(mutate((m) => (m.transitions = { R: list }))).toEqual([]);
    expect(mutate((m) => (m.transitions = { R: list.slice(1) })).join()).toMatch(/16 entries/);
    expect(mutate((m) => (m.transitions = { R: [f(0), ...list.slice(1)] })).join()).toMatch(/\[0\]: must be null/);
  });
});

describe('유닛 프레임 고르기', () => {
  it('그려 넣은 방향 · 마스크', () => {
    const r = resolveUnitFrame(pack(), 'infantry', 'idle', 'sw', 0)!;
    expect(r.flip).toBe(false);
    expect(r.frame.x).toBe(320);
    expect(r.mask?.x).toBe(368);
  });

  it('ne · se 는 따로 없으면 nw · sw 의 좌우 반전', () => {
    expect(resolveUnitFrame(pack(), 'infantry', 'idle', 'ne', 0)).toMatchObject({ flip: true, frame: { x: 416 } });
    expect(resolveUnitFrame(pack(), 'infantry', 'idle', 'se', 0)).toMatchObject({ flip: true, frame: { x: 320 } });
  });

  it('장 번호는 동작 장 수로 돈다, 없는 동작은 idle', () => {
    expect(resolveUnitFrame(pack(), 'infantry', 'attack', 'nw', 3)!.frame.x).toBe(608);
    expect(resolveUnitFrame(pack(), 'infantry', 'attack', 'nw', -1)!.frame.x).toBe(608);
    expect(resolveUnitFrame(pack(), 'infantry', 'move', 'sw', 0)!.frame.x).toBe(320);
  });

  it('팩에 없는 칸 · 칸 없음은 null(마름모로 그린다)', () => {
    expect(resolveUnitFrame(pack(), 'archer', 'idle', 'sw', 0)).toBeNull();
    expect(resolveUnitFrame(pack(), undefined, 'idle', 'sw', 0)).toBeNull();
    expect(resolveUnitFrame(pack(), 'constructor', 'idle', 'sw', 0)).toBeNull();
    expect(resolveUnitFrame(pack(), '__proto__', 'idle', 'sw', 0)).toBeNull();
  });
});

describe('임시 절차 타일 팩', () => {
  it('계약을 지키고 유닛 그림은 없다', () => {
    const { manifest, atlases } = proceduralArtpack();
    expect(validateArtpackManifest(manifest)).toEqual([]);
    expect(manifest.units).toEqual({});
    expect(manifest.provenance[0].source).toBe('procedural');
    for (const a of atlases) {
      expect(a.width).toBeLessThanOrEqual(ARTPACK_ATLAS_MAX);
      expect(a.height).toBeLessThanOrEqual(ARTPACK_ATLAS_MAX);
      expect(a.rgba.length).toBe(a.width * a.height * 4);
    }
    expect(manifest.atlases[0]).toMatchObject({ width: atlases[0].width, height: atlases[0].height });
  });

  it('같은 입력 → 같은 바이트', () => {
    const hash = (rgba: Uint8ClampedArray) => createHash('sha256').update(rgba).digest('hex');
    const a = buildProceduralArtpack();
    const b = buildProceduralArtpack();
    expect(hash(a.atlases[0].rgba)).toBe(hash(b.atlases[0].rgba));
    expect(JSON.stringify(a.manifest)).toBe(JSON.stringify(b.manifest));
  });

  it('바닥 타일은 칸 마름모 안만 칠한다(모서리는 투명)', () => {
    const { manifest, atlases } = proceduralArtpack();
    const tile = manifest.terrain.P.ground[0];
    const alpha = (x: number, y: number) => atlases[0].rgba[((tile.y + y) * atlases[0].width + tile.x + x) * 4 + 3];
    expect(alpha(0, 0)).toBe(0);
    expect(alpha(tile.w - 1, tile.h - 1)).toBe(0);
    expect(alpha(tile.w / 2, tile.h / 2)).toBe(255);
  });
});

describe('그림 캐시 LRU', () => {
  it('상한 512, 가장 오래 안 쓴 것부터 버린다', () => {
    expect(BATTLE_SPRITE_CACHE_LIMIT).toBe(512);
    const lru = new LruCache<string, number>(3);
    lru.set('a', 1);
    lru.set('b', 2);
    lru.set('c', 3);
    expect(lru.get('a')).toBe(1);
    lru.set('d', 4);
    expect(lru.get('b')).toBeUndefined();
    expect(lru.get('a')).toBe(1);
    expect(lru.size).toBe(3);
  });

  it('기본 상한까지 쌓고 넘치면 하나만 버린다', () => {
    const lru = new LruCache<number, number>();
    for (let i = 0; i <= 512; i += 1) lru.set(i, i);
    expect(lru.size).toBe(512);
    expect(lru.get(0)).toBeUndefined();
    expect(lru.get(512)).toBe(512);
  });
});

describe('판 그림 해상도', () => {
  it('키트 판 2080 × 1160 은 팩 배율과 2 중 작은 값', () => {
    const board = { width: 2080, height: 1160 };
    expect(artPictureResolution(board, 4)).toBe(2);
    expect(artPictureResolution(board, 2)).toBe(2);
    expect(artPictureResolution(board, 1)).toBe(1);
  });

  it('넓이 상한(16 M px)을 넘기면 낮춘다', () => {
    expect(artPictureResolution({ width: 4096, height: 4096 }, 2)).toBe(1);
  });
});

/** OffscreenCanvas stand-in: records drawImage calls on its context. */
class FakeCanvas {
  readonly draws: unknown[][] = [];
  constructor(
    readonly width: number,
    readonly height: number,
  ) {}
  getContext() {
    return {
      setTransform() {},
      drawImage: (...args: unknown[]) => this.draws.push(args),
      getImageData: (_x: number, _y: number, w: number, h: number) => ({ data: new Uint8ClampedArray(w * h * 4) }),
      putImageData() {},
    };
  }
}

describe('팩 유닛 그림 캐시 — 결과가 다르면 항목도 다르다', () => {
  const view = { scale: 2, offsetX: 0, offsetY: 0 };
  const unitWith = (frame: ArtpackFrame, mask?: ArtpackFrame) => ({
    actions: { idle: { frames: 1, directions: { sw: { frames: [frame], ...(mask ? { masks: [mask] } : {}) }, nw: { frames: [frame] } } } },
  });
  const loaded = (units: ArtpackManifest['units'], atlas: object = {}): LoadedArtpack => {
    const manifest = structuredClone(pack());
    manifest.units = units;
    return { manifest, atlases: [Object.assign(Object.create(null), atlas)] };
  };
  /** The sprite canvases drawArtUnit draws, in order. */
  const sprites = (art: LoadedArtpack, units: ArtUnit[]): FakeCanvas[] => {
    const drawn: FakeCanvas[] = [];
    const ctx: CanvasRenderingContext2D = Object.create(null);
    Object.assign(ctx, { imageSmoothingEnabled: false, drawImage: (source: FakeCanvas) => drawn.push(source) });
    for (const unit of units) drawArtUnit(ctx, art, unit, { x: 100, y: 100 }, view);
    return drawn;
  };

  beforeEach(() => vi.stubGlobal('OffscreenCanvas', FakeCanvas));
  afterEach(() => vi.unstubAllGlobals());

  it('같은 원점 · 다른 크기 프레임', () => {
    const art = loaded({ small: unitWith(f(0, 48, 48, 20, 44)), wide: unitWith(f(0, 64, 48, 20, 44)) });
    expect(validateArtpackManifest(art.manifest)).toEqual([]);
    const [small, wide] = sprites(art, [{ slot: 'small' }, { slot: 'wide' }]);
    expect(small).not.toBe(wide);
    expect([small.width, wide.width]).toEqual([48, 64]);
  });

  it('같은 몸 · 다른 회색 마스크(같은 진영색)', () => {
    const body = f(0, 48, 48, 20, 44);
    const art = loaded({ first: unitWith(body, f(64, 48, 48, 20, 44)), second: unitWith(body, f(128, 48, 48, 20, 44)) });
    expect(validateArtpackManifest(art.manifest)).toEqual([]);
    const [first, second] = sprites(art, [{ slot: 'first', colour: '#ff0000' }, { slot: 'second', colour: '#ff0000' }]);
    expect(first).not.toBe(second);
    const maskX = (sprite: FakeCanvas) => (sprite.draws[1][0] as FakeCanvas).draws[0][1];
    expect([maskX(first), maskX(second)]).toEqual([64, 128]);
  });

  it('packId 가 같아도 다른 팩 객체는 섞이지 않는다', () => {
    const unit = { infantry: unitWith(f(0, 48, 48, 20, 44)) };
    const a = loaded(unit, { name: 'a' });
    const b = loaded(unit, { name: 'b' });
    expect(a.manifest.packId).toBe(b.manifest.packId);
    const [fromA] = sprites(a, [{ slot: 'infantry' }]);
    const [fromB] = sprites(b, [{ slot: 'infantry' }]);
    expect(fromA).not.toBe(fromB);
    expect(fromB.draws[0][0]).toBe(b.atlases[0]);
  });

  it('같은 팩 · 같은 그림은 다시 만들지 않는다', () => {
    const art = loaded({ infantry: unitWith(f(0, 48, 48, 20, 44)) });
    const [once, again] = sprites(art, [{ slot: 'infantry' }, { slot: 'infantry' }]);
    expect(again).toBe(once);
  });
});
