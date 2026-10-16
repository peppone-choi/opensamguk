import { describe, expect, it } from 'vitest';
import { clampCamera, fitCellsView, fitZoom, visibleCellRect, zoomAt } from '../../map/topdown/camera';
import { terrainFragmentSource } from '../../map/topdown/gl/terrainLayer';
import { hitTest } from '../../map/topdown/hitTest';
import { panBy, pinch } from '../../map/topdown/input';
import { layoutLabels } from '../../map/topdown/labels';
import { TOPDOWN_PROJECTION, type Projection } from '../../map/topdown/projection';
import { DIMETRIC_PROJECTION, cellQuad, depthOrder, dimetricDepth, strategicProjection } from '../../map/topdown/projection-dimetric';
import { HAN_MAP_SHAPE, type Camera, type ScreenPoint, type Viewport } from '../../map/topdown/types';

// B4: 2:1 다이메트릭 투영. 칸 좌표 · 누르기 결과의 뜻은 탑다운과 같고 그리기만 바뀐다 — 칸 ↔ 화면 왕복, 마름모 안 점의 칸,
// 셰이더 식과 JS 식의 일치, 지도 전체 맞춤을 지킨다.
const P = DIMETRIC_PROJECTION;

function random(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const ZOOMS = [0.127, 0.3361, 0.5, 1, 2, 4, 8, 16, 32, 13.7];
function samples(count: number) {
  const next = random(20261019);
  return Array.from({ length: count }, (_, i) => ({
    viewport: { width: 151 + Math.floor(next() * 2400), height: 300 + Math.floor(next() * 1200), dpr: 1 + (i % 3) } as Viewport,
    cam: { center: { col: next() * HAN_MAP_SHAPE.cols, row: next() * HAN_MAP_SHAPE.rows }, zoom: i % 2 === 0 ? ZOOMS[i % ZOOMS.length] : 0.1 + next() * 32 } as Camera,
    cell: { col: Math.floor(next() * HAN_MAP_SHAPE.cols), row: Math.floor(next() * HAN_MAP_SHAPE.rows) },
    u: next(),
    v: next(),
  }));
}

// 셰이더 식(GLSL 부분집합: 수 · 이름 · + − × ÷ · 괄호 · vec2() · mat2() · .x .y)을 JS 배정밀도로 그대로 셈한다.
type Value = number | { v: [number, number] } | { m: [number, number, number, number] };
function evalGlsl(source: string, env: Record<string, Value>): Value {
  const tokens = source.match(/\d+\.\d*|\d+|[A-Za-z_]\w*|[-+*/(),.]/g) ?? [];
  let at = 0;
  const peek = () => tokens[at];
  const take = (expected?: string) => {
    const token = tokens[at++];
    if (expected && token !== expected) throw new Error(`expected ${expected}, got ${token}`);
    return token;
  };
  const isVec = (x: Value): x is { v: [number, number] } => typeof x === 'object' && 'v' in x;
  const binary = (op: string, a: Value, b: Value): Value => {
    const f = (x: number, y: number) => (op === '+' ? x + y : op === '-' ? x - y : op === '*' ? x * y : x / y);
    if (typeof a === 'object' && 'm' in a && isVec(b) && op === '*') {
      const [m0, m1, m2, m3] = a.m;
      return { v: [m0 * b.v[0] + m2 * b.v[1], m1 * b.v[0] + m3 * b.v[1]] };
    }
    if (typeof a === 'number' && typeof b === 'number') return f(a, b);
    if (isVec(a) && isVec(b)) return { v: [f(a.v[0], b.v[0]), f(a.v[1], b.v[1])] };
    if (isVec(a) && typeof b === 'number') return { v: [f(a.v[0], b), f(a.v[1], b)] };
    if (typeof a === 'number' && isVec(b)) return { v: [f(a, b.v[0]), f(a, b.v[1])] };
    throw new Error(`unsupported ${op}`);
  };
  const primary = (): Value => {
    const token = take();
    if (token === '(') {
      const inner = expr();
      take(')');
      return inner;
    }
    if (/^\d/.test(token)) return Number(token);
    if (peek() === '(') {
      take('(');
      const args: number[] = [];
      while (peek() !== ')') {
        args.push(expr() as number);
        if (peek() === ',') take(',');
      }
      take(')');
      if (token === 'vec2') return { v: [args[0], args[1]] };
      if (token === 'mat2') return { m: [args[0], args[1], args[2], args[3]] };
      throw new Error(`unknown function ${token}`);
    }
    if (!(token in env)) throw new Error(`unknown name ${token}`);
    return env[token];
  };
  const postfix = (): Value => {
    let value = primary();
    while (peek() === '.') {
      take('.');
      const field = take();
      if (!isVec(value)) throw new Error('field of non-vector');
      value = field === 'x' ? value.v[0] : value.v[1];
    }
    return value;
  };
  const unary = (): Value => {
    if (peek() === '-') {
      take('-');
      return binary('*', -1, unary());
    }
    return postfix();
  };
  const term = (): Value => {
    let value = unary();
    while (peek() === '*' || peek() === '/') value = binary(take(), value, unary());
    return value;
  };
  function expr(): Value {
    let value = term();
    while (peek() === '+' || peek() === '-') value = binary(take(), value, term());
    return value;
  }
  const out = expr();
  if (at !== tokens.length) throw new Error(`trailing ${tokens.slice(at).join(' ')}`);
  return out;
}

function shaderScreenToCell(projection: Projection, point: ScreenPoint, cam: Camera, viewport: Viewport) {
  const value = evalGlsl(projection.glslScreenToCell('screen', 'uCenter', 'uViewport', 'uZoom'), {
    screen: { v: [point.x, point.y] },
    uCenter: { v: [cam.center.col, cam.center.row] },
    uViewport: { v: [viewport.width, viewport.height] },
    uZoom: cam.zoom,
  });
  if (typeof value !== 'object' || !('v' in value)) throw new Error('shader expression is not a vec2');
  return { col: value.v[0], row: value.v[1] };
}

describe('DIMETRIC_PROJECTION (B4 2:1)', () => {
  it('칸 하나는 가로 2·zoom, 세로 zoom 마름모다', () => {
    const cam: Camera = { center: { col: 10, row: 20 }, zoom: 16 };
    const viewport: Viewport = { width: 800, height: 600, dpr: 1 };
    const [top, right, bottom, left] = cellQuad(P, 10, 20, 1, cam, viewport);
    expect(top).toEqual({ x: 400, y: 300 });
    expect(right).toEqual({ x: 416, y: 308 });
    expect(bottom).toEqual({ x: 400, y: 316 });
    expect(left).toEqual({ x: 384, y: 308 });
    expect(right.x - left.x).toBe(2 * (bottom.y - top.y));
  });

  it('① 칸 중심 왕복 오차 ≤ 1e-9', () => {
    let worst = 0;
    for (const { cam, viewport, cell } of samples(4000)) {
      const centre = { col: cell.col + 0.5, row: cell.row + 0.5 };
      const back = P.screenToCell(P.cellToScreen(centre, cam, viewport), cam, viewport);
      worst = Math.max(worst, Math.abs(back.col - centre.col), Math.abs(back.row - centre.row));
    }
    expect(worst).toBeLessThanOrEqual(1e-9);
  });

  it('② 칸 마름모 안쪽 점은 모두 그 칸으로 판정한다(hitTest 포함)', () => {
    const layers = { sprites: [], provinceAt: () => 0 };
    for (const { cam, viewport, cell, u, v } of samples(4000)) {
      // 마름모 안쪽(가장자리에서 2% 안) 점: 칸 안 연속 좌표 (u, v) 를 화면으로 보낸다
      const inside = { col: cell.col + 0.01 + u * 0.98, row: cell.row + 0.01 + v * 0.98 };
      const point = P.cellToScreen(inside, cam, viewport);
      const at = P.screenToCell(point, cam, viewport);
      expect([Math.floor(at.col), Math.floor(at.row)]).toEqual([cell.col, cell.row]);
      expect(hitTest(point, cam, viewport, layers, P).cell).toEqual(cell);
    }
    // 네 꼭짓점 바로 안쪽(꼭짓점 → 중심 쪽 1%)
    const cam: Camera = { center: { col: 1536.25, row: 1338.75 }, zoom: 16 };
    const viewport: Viewport = { width: 1280, height: 720, dpr: 2 };
    const cell = { col: 1530, row: 1340 };
    const centre = P.cellToScreen({ col: cell.col + 0.5, row: cell.row + 0.5 }, cam, viewport);
    for (const corner of cellQuad(P, cell.col, cell.row, 1, cam, viewport)) {
      const point = { x: corner.x + (centre.x - corner.x) * 0.01, y: corner.y + (centre.y - corner.y) * 0.01 };
      expect(hitTest(point, cam, viewport, layers, P).cell).toEqual(cell);
    }
  });

  it('③ 셰이더 screenToCell 식을 JS로 셈한 값이 screenToCell 과 같다(탑다운 · 다이메트릭)', () => {
    for (const projection of [TOPDOWN_PROJECTION, DIMETRIC_PROJECTION]) {
      for (const { cam, viewport, u, v } of samples(2000)) {
        const point = { x: u * viewport.width, y: v * viewport.height };
        const js = projection.screenToCell(point, cam, viewport);
        const glsl = shaderScreenToCell(projection, point, cam, viewport);
        expect(glsl.col).toBe(js.col);
        expect(glsl.row).toBe(js.row);
      }
    }
  });

  it('④ 지형 셰이더: 기본(탑다운) 원문은 그대로이고 다이메트릭은 화면 → 칸 식 한 줄만 다르다', () => {
    const topdown = terrainFragmentSource();
    expect(terrainFragmentSource(TOPDOWN_PROJECTION)).toBe(topdown);
    expect(topdown).toContain('vec2 cellF = uCenter + (screen - uViewport * 0.5) / uZoom;');
    const a = topdown.split('\n');
    const b = terrainFragmentSource(DIMETRIC_PROJECTION).split('\n');
    expect(b.length).toBe(a.length);
    const changed = a.flatMap((line, index) => (line === b[index] ? [] : [b[index]]));
    expect(changed).toEqual(['  vec2 cellF = uCenter + mat2(0.5, -0.5, 1.0, 1.0) * ((screen - uViewport * 0.5) / uZoom);']);
  });

  it('⑤ extent 가 3,072 × 2,676칸 전체를 감싸고, 맞춤 배율이면 지도 네 귀가 화면 안에 든다', () => {
    expect(P.extent(HAN_MAP_SHAPE)).toEqual({ width: 3072 + 2676, height: (3072 + 2676) / 2 });
    for (const viewport of [{ width: 1920, height: 1080, dpr: 1 }, { width: 390, height: 844, dpr: 3 }, { width: 151, height: 600, dpr: 2 }]) {
      const zoom = fitZoom(viewport, HAN_MAP_SHAPE, P);
      const cam = clampCamera({ center: { col: 0, row: 0 }, zoom }, viewport, HAN_MAP_SHAPE, P);
      expect(cam.center).toEqual({ col: HAN_MAP_SHAPE.cols / 2, row: HAN_MAP_SHAPE.rows / 2 });
      // 지도 전체 = 칸 (0, 0) 에서 (cols, rows) 까지 덩이 하나. 그 마름모 네 꼭짓점
      const corners = [
        P.cellToScreen({ col: 0, row: 0 }, cam, viewport),
        P.cellToScreen({ col: HAN_MAP_SHAPE.cols, row: 0 }, cam, viewport),
        P.cellToScreen({ col: HAN_MAP_SHAPE.cols, row: HAN_MAP_SHAPE.rows }, cam, viewport),
        P.cellToScreen({ col: 0, row: HAN_MAP_SHAPE.rows }, cam, viewport),
      ];
      for (const point of corners) {
        expect(point.x).toBeGreaterThanOrEqual(-1e-6);
        expect(point.x).toBeLessThanOrEqual(viewport.width + 1e-6);
        expect(point.y).toBeGreaterThanOrEqual(-1e-6);
        expect(point.y).toBeLessThanOrEqual(viewport.height + 1e-6);
      }
      // 화면이 지도를 다 담으면 보이는 칸 사각형은 지도 전체다
      expect(visibleCellRect(cam, viewport, HAN_MAP_SHAPE, P)).toEqual({ col0: 0, row0: 0, col1: HAN_MAP_SHAPE.cols, row1: HAN_MAP_SHAPE.rows });
    }
  });

  it('보이는 칸 사각형은 화면 네 귀와 화면 안 점의 칸을 모두 담는다', () => {
    for (const { cam, viewport, u, v } of samples(1000)) {
      const rect = visibleCellRect(cam, viewport, HAN_MAP_SHAPE, P);
      for (const point of [{ x: 0, y: 0 }, { x: viewport.width, y: 0 }, { x: 0, y: viewport.height }, { x: viewport.width, y: viewport.height },
        { x: u * viewport.width, y: v * viewport.height }]) {
        const at = P.screenToCell(point, cam, viewport);
        const col = Math.min(HAN_MAP_SHAPE.cols - 1, Math.max(0, Math.floor(at.col)));
        const row = Math.min(HAN_MAP_SHAPE.rows - 1, Math.max(0, Math.floor(at.row)));
        if (at.col < 0 || at.row < 0 || at.col >= HAN_MAP_SHAPE.cols || at.row >= HAN_MAP_SHAPE.rows) continue;
        expect(col).toBeGreaterThanOrEqual(rect.col0);
        expect(col).toBeLessThan(rect.col1);
        expect(row).toBeGreaterThanOrEqual(rect.row0);
        expect(row).toBeLessThan(rect.row1);
      }
    }
  });

  it('끌기 · 휠 · 핀치: 손가락 밑 칸이 손가락을 따라간다', () => {
    const viewport: Viewport = { width: 1280, height: 720, dpr: 1 };
    const cam: Camera = { center: { col: 1500, row: 900 }, zoom: 16 };
    const grab = { x: 300, y: 200 };
    const cell = P.screenToCell(grab, cam, viewport);
    const moved = panBy(cam, 37, -21, P);
    const after = P.cellToScreen(cell, moved, viewport);
    expect(after.x).toBeCloseTo(grab.x + 37, 9);
    expect(after.y).toBeCloseTo(grab.y - 21, 9);
    const zoomed = zoomAt(cam, grab, 4, viewport, HAN_MAP_SHAPE, P);
    expect(zoomed.zoom).toBe(4);
    const still = P.cellToScreen(cell, zoomed, viewport);
    expect(still.x).toBeCloseTo(grab.x, 9);
    expect(still.y).toBeCloseTo(grab.y, 9);
    const pinched = pinch([{ x: 200, y: 200 }, { x: 400, y: 200 }], [{ x: 180, y: 210 }, { x: 420, y: 210 }], cam, viewport, HAN_MAP_SHAPE, P);
    expect(pinched.zoom).toBeCloseTo(16 * 1.2, 9);
    const mid = P.screenToCell({ x: 300, y: 200 }, cam, viewport);
    const midAfter = P.cellToScreen(mid, pinched, viewport);
    expect(midAfter.x).toBeCloseTo(300, 9);
    expect(midAfter.y).toBeCloseTo(210, 9);
  });

  it('칸 여럿 맞춤 보기: 다이메트릭 extent 로 배율을 고르고 가운데는 칸 좌표다', () => {
    const viewport: Viewport = { width: 1280, height: 720, dpr: 1 };
    const cells = [{ col: 1500, row: 900 }, { col: 1540, row: 930 }];
    const view = fitCellsView(cells, viewport, 32, HAN_MAP_SHAPE, P)!;
    expect(view.center).toEqual({ col: 1520.5, row: 915.5 });
    for (const cell of cells) {
      const at = P.cellToScreen(cell, view, viewport);
      expect(at.x).toBeGreaterThanOrEqual(0);
      expect(at.x).toBeLessThanOrEqual(viewport.width);
      expect(at.y).toBeGreaterThanOrEqual(0);
      expect(at.y).toBeLessThanOrEqual(viewport.height);
    }
  });

  it('카메라 자르기: 지도보다 크게 보면 가운데 칸을 지도 안으로 자른다', () => {
    const viewport: Viewport = { width: 1280, height: 720, dpr: 1 };
    expect(clampCamera({ center: { col: -40, row: 9999 }, zoom: 16 }, viewport, HAN_MAP_SHAPE, P).center).toEqual({ col: 0, row: HAN_MAP_SHAPE.rows });
    expect(clampCamera({ center: { col: 12.5, row: 7.25 }, zoom: 16 }, viewport, HAN_MAP_SHAPE, P).center).toEqual({ col: 12.5, row: 7.25 });
  });

  it('이름표: 城 이름은 발자국 마름모 아래 꼭짓점 바로 밑, 가운데 x 에 선다', () => {
    const viewport: Viewport = { width: 1280, height: 720, dpr: 1 };
    const cam: Camera = { center: { col: 1500, row: 900 }, zoom: 16 };
    const [label] = layoutLabels(
      [{ id: 'city:1', text: '낙양', kind: 'county', anchor: { col: 1500, row: 900 }, priority: 1, footprintSpan: 3 }],
      cam, viewport, () => ({ width: 40, height: 20 }), { projection: P },
    );
    const centre = P.cellToScreen({ col: 1500.5, row: 900.5 }, cam, viewport);
    expect(label.x + label.width / 2).toBe(centre.x);
    expect(label.y).toBe(centre.y + 1.5 * 16 + 2);
  });
});

describe('strategicProjection · depthOrder', () => {
  it('빌드 플래그는 dimetric 만 2:1 이고 빈 값 · 다른 값은 탑다운이다', () => {
    expect(strategicProjection('dimetric')).toBe(DIMETRIC_PROJECTION);
    expect(strategicProjection(' dimetric ')).toBe(DIMETRIC_PROJECTION);
    for (const flag of [undefined, null, '', 'topdown', 'iso', 'DIMETRIC']) expect(strategicProjection(flag)).toBe(TOPDOWN_PROJECTION);
  });

  it('탑다운은 원래 배열을 그대로, 다이메트릭은 뒤(c + r 작음) → 앞 차례로(같은 깊이는 원래 차례)', () => {
    const items = [{ id: 'a', col: 5, row: 5 }, { id: 'b', col: 1, row: 2 }, { id: 'c', col: 3, row: 7 }, { id: 'd', col: 2, row: 1 }];
    const cellOf = (item: { col: number; row: number }) => item;
    expect(depthOrder(TOPDOWN_PROJECTION, items, cellOf)).toBe(items);
    expect(depthOrder(DIMETRIC_PROJECTION, items, cellOf).map((item) => item.id)).toEqual(['b', 'd', 'a', 'c']);
    expect(dimetricDepth({ col: 3, row: 7 })).toBe(10);
  });

  it('깊이가 큰 칸이 화면에서 더 아래(앞)다', () => {
    const cam: Camera = { center: { col: 100, row: 100 }, zoom: 8 };
    const viewport: Viewport = { width: 640, height: 480, dpr: 1 };
    const next = random(7);
    for (let i = 0; i < 500; i += 1) {
      const a = { col: next() * 200, row: next() * 200 };
      const b = { col: next() * 200, row: next() * 200 };
      const ya = P.cellToScreen(a, cam, viewport).y;
      const yb = P.cellToScreen(b, cam, viewport).y;
      expect(Math.sign(ya - yb)).toBe(Math.sign(dimetricDepth(a) - dimetricDepth(b)));
    }
  });
});
