import { describe, expect, it, vi } from 'vitest';
import {
  CORPS_MIN_HIT_PX, corpsBandText, corpsDrawOrder, corpsHitZ, corpsMarkerSize, corpsMarkRect, corpsPlacement, headingOf, nudgeFromPin, placeCorpsBands,
  type CorpsMarker, type CorpsPlacement, type Rect,
} from '../../map/topdown/corps';
import { myLocationPinBoxes } from '../../map/topdown/myLocation';
import type { CellPoint } from '../../map/topdown/types';
import { CORPS_INTEL_ALPHA, CORPS_OWN_STROKE, createKitCorpsArt } from '../../map/topdown/corpsArt';

const toScreen = (cell: { col: number; row: number }) => ({ x: cell.col * 10, y: cell.row * 10 });
const marker = (extra: Partial<CorpsMarker> = {}): CorpsMarker => ({
  id: 'c1',
  cell: { col: 10, row: 10 },
  nationColor: '#3366cc',
  leaderName: '관우',
  heading: null,
  ...extra,
});

describe('부대 표지', () => {
  it('첫 경로 칸 쪽으로 방향을 정한다(가로세로 중 큰 쪽)', () => {
    const at = { col: 10, row: 10 };
    expect(headingOf(at, [{ col: 12, row: 11 }])).toBe('right');
    expect(headingOf(at, [{ col: 9, row: 10 }])).toBe('left');
    expect(headingOf(at, [{ col: 10, row: 7 }])).toBe('up');
    expect(headingOf(at, [{ col: 11, row: 14 }])).toBe('down');
  });

  it('경로가 없거나 제자리면 멈춤(깃발만)', () => {
    expect(headingOf({ col: 1, row: 1 })).toBeNull();
    expect(headingOf({ col: 1, row: 1 }, [])).toBeNull();
    expect(headingOf({ col: 1, row: 1 }, [{ col: 1, row: 1 }])).toBeNull();
  });

  it('몸통은 원작 16px 표지의 정수 배: 48px/칸 아래 2배, 그 위 3배', () => {
    expect(corpsMarkerSize(4)).toBe(32);
    expect(corpsMarkerSize(32)).toBe(32);
    expect(corpsMarkerSize(48)).toBe(48);
    expect(corpsMarkerSize(64)).toBe(48);
  });

  it('자리: 몸통은 칸 가운데, 깃발은 몸통 왼쪽 위, 누를 영역은 둘을 덮고 44px 이상', () => {
    const place = corpsPlacement(marker(), 4, toScreen);
    expect(place.at).toEqual({ x: 100, y: 100 });
    expect(place.body).toEqual({ x: 84, y: 84, width: 32, height: 32 });
    expect(place.flag.y + place.flag.height).toBeGreaterThan(place.body.y);
    expect(place.flag.x).toBeLessThan(place.body.x);
    for (const r of [place.body, place.flag]) {
      expect(r.x).toBeGreaterThanOrEqual(place.hit.x);
      expect(r.y).toBeGreaterThanOrEqual(place.hit.y);
      expect(r.x + r.width).toBeLessThanOrEqual(place.hit.x + place.hit.width);
      expect(r.y + r.height).toBeLessThanOrEqual(place.hit.y + place.hit.height);
    }
    expect(place.hit.width).toBeGreaterThanOrEqual(CORPS_MIN_HIT_PX);
    expect(place.hit.height).toBeGreaterThanOrEqual(CORPS_MIN_HIT_PX);
    expect(place.route).toEqual([]);
  });

  it('경로 점은 지금 칸에서 시작해 남은 칸을 따른다', () => {
    const place = corpsPlacement(marker({ route: [{ col: 11, row: 10 }, { col: 12, row: 11 }] }), 16, toScreen);
    expect(place.route).toEqual([{ x: 100, y: 100 }, { x: 110, y: 100 }, { x: 120, y: 110 }]);
  });

  it('그림 판이 아직 없으면 아무것도 그리지 않는다(누를 영역은 렌더러가 따로 둔다)', () => {
    const drawImage = vi.fn();
    const ctx = { drawImage } as unknown as CanvasRenderingContext2D;
    const art = createKitCorpsArt({ sheets: () => ({ markers: null, flags: null }), cached: (_k, make) => make(), font: 'serif' });
    art.drawBody(ctx, { ...marker(), heading: 'left' }, { x: 0, y: 0, width: 24, height: 24 });
    art.drawFlag(ctx, marker(), { x: 0, y: 0, width: 32, height: 32 });
    expect(drawImage).not.toHaveBeenCalled();
  });

  it('경로는 점이 둘 이상일 때만 긋는다', () => {
    const stroke = vi.fn();
    const ctx = { save: vi.fn(), restore: vi.fn(), setLineDash: vi.fn(), beginPath: vi.fn(), moveTo: vi.fn(), lineTo: vi.fn(), stroke } as unknown as CanvasRenderingContext2D;
    const art = createKitCorpsArt({ sheets: () => ({ markers: null, flags: null }), cached: (_k, make) => make(), font: 'serif' });
    art.drawRoute(ctx, marker(), [{ x: 0, y: 0 }]);
    expect(stroke).not.toHaveBeenCalled();
    art.drawRoute(ctx, marker(), [{ x: 0, y: 0 }, { x: 5, y: 5 }]);
    expect(stroke).toHaveBeenCalledOnce();
  });
});

// ADR-LITE-049 개정 · 원장 §1 D34(보드 V31K2CorpsStates): 첩보 α 0.55 · 점선 · 「?」 · N순 전, 내 군단 청동 2px, 겹침 내 군단 > 보임 > 첩보.
describe('군단 표지 세 상태(D34)', () => {
  const item = (standing: CorpsMarker['standing'], col: number, extra: Partial<CorpsMarker> = {}) => {
    const m = marker({ id: `${standing}-${col}`, cell: { col, row: 10 }, standing, ...extra });
    return { marker: m, place: corpsPlacement(m, 16, toScreen) };
  };

  it('그리는 차례는 첩보 → 보임 → 내 군단, 누르기도 내 군단이 위(모두 城 깃발 1 위 · 내 위치 10 아래)', () => {
    const order = corpsDrawOrder([item('own', 1), item('intel', 2), item(undefined, 3), item('seen', 4), item('intel', 5)]);
    expect(order.map((entry) => entry.marker.id)).toEqual(['intel-2', 'intel-5', 'undefined-3', 'seen-4', 'own-1']);
    expect(corpsHitZ('intel')).toBeLessThan(corpsHitZ('seen'));
    expect(corpsHitZ('seen')).toBe(corpsHitZ(undefined));
    expect(corpsHitZ('seen')).toBeLessThan(corpsHitZ('own'));
    expect(corpsHitZ('intel')).toBeGreaterThan(1);
    expect(corpsHitZ('own')).toBeLessThan(10);
  });

  it('병력 띠 글: 병력, 첩보만 나이를 더한다, 둘 다 없으면 띠 없음', () => {
    expect(corpsBandText(marker({ standing: 'own', troopsLabel: '3,200명' }))).toBe('3,200명');
    expect(corpsBandText(marker({ standing: 'seen', troopsLabel: '5천~1만', ageLabel: '2순 전' }))).toBe('5천~1만');
    expect(corpsBandText(marker({ standing: 'intel', troopsLabel: '5천~1만', ageLabel: '2순 전' }))).toBe('5천~1만 · 2순 전');
    expect(corpsBandText(marker({ standing: 'intel', ageLabel: '2순 전' }))).toBe('2순 전');
    expect(corpsBandText(marker())).toBeNull();
  });

  it('띠는 표지 바로 아래 가운데, 겹치면 첩보 · 보임 띠부터 빼고 내 군단 띠는 남긴다', () => {
    const measure = () => ({ width: 60, height: 18 });
    const apart = placeCorpsBands([item('seen', 10, { troopsLabel: '5천~1만' }), item('own', 30, { troopsLabel: '3,200명' })], measure);
    expect(apart.map((band) => band.item.marker.id)).toEqual(['own-30', 'seen-10']);
    const mark = corpsMarkRect(apart[1].item.marker, apart[1].item.place);
    expect(apart[1].rect).toEqual({ x: mark.x + mark.width / 2 - 30, y: mark.y + mark.height + 2, width: 60, height: 18 });
    // 칸 하나 차이(10px) — 띠가 겹친다: 내 군단만 남는다
    const close = placeCorpsBands([item('intel', 10, { troopsLabel: '5천~1만', ageLabel: '2순 전' }),
      item('seen', 11, { troopsLabel: '5천~1만' }), item('own', 12, { troopsLabel: '3,200명' })], measure);
    expect(close.map((band) => band.item.marker.id)).toEqual(['own-12']);
  });

  it('그림: 내 군단은 토큰 청동 2px 테두리, 첩보는 점선 + 「?」 표, 보임은 상태 표 없음', () => {
    const calls: string[] = [];
    const ctx = new Proxy({} as Record<string, unknown>, {
      get: (target, key) => (key in target ? target[key as string] : (...args: unknown[]) => { calls.push(`${String(key)}(${args.map(String).join(',')})`); }),
      set: (target, key, value) => { target[key as string] = value; calls.push(`${String(key)}=${String(value)}`); return true; },
    }) as unknown as CanvasRenderingContext2D;
    const art = createKitCorpsArt({ sheets: () => ({ markers: null, flags: null }), cached: (_k, make) => make(), font: 'serif' });
    const rect = { x: 0, y: 0, width: 32, height: 32 };
    art.drawStanding(ctx, marker({ standing: 'seen' }), rect);
    expect(calls).toEqual([]);
    art.drawStanding(ctx, marker({ standing: 'own' }), rect);
    expect(CORPS_OWN_STROKE).toBe('#d3b064'); // D34 「가」 토큰 청동
    expect(calls).toContain('strokeStyle=#d3b064');
    expect(calls).toContain('lineWidth=2');
    calls.length = 0;
    art.drawStanding(ctx, marker({ standing: 'intel' }), rect);
    expect(calls).toContain('setLineDash(3,2)');
    expect(calls.some((call) => call.startsWith('fillText(?'))).toBe(true);
  });

  it('첩보 표지의 몸통 · 깃발은 α 0.55로 흐리게 그린다', () => {
    const alphas: number[] = [];
    const ctx = { globalAlpha: 1, imageSmoothingEnabled: true, save: vi.fn(), restore: vi.fn(),
      drawImage: vi.fn(function (this: { globalAlpha: number }) { alphas.push(this.globalAlpha); }) };
    const art = createKitCorpsArt({ sheets: () => ({ markers: {} as never, flags: {} as never }), cached: () => ({}) as OffscreenCanvas, font: 'serif' });
    art.drawFlag(ctx as unknown as CanvasRenderingContext2D, marker({ standing: 'intel' }), { x: 0, y: 0, width: 32, height: 32 });
    expect(alphas.at(-1)).toBeCloseTo(CORPS_INTEL_ALPHA);
  });
});

// D55(실지도 결함 4): 내 위치 핀과 같은 구역의 내 군단 표지가 핀 밑에 반쯤 깔렸다 — 같은 구역이면 표지를 핀 옆으로 비킨다(차례는 그대로)
describe('핀 옆으로 비키기', () => {
  const overlap = (a: Rect, b: Rect) => a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height;
  const mark = (place: CorpsPlacement) => {
    const x = Math.min(place.body.x, place.flag.x);
    const y = Math.min(place.body.y, place.flag.y);
    return { x, y, width: Math.max(place.body.x + place.body.width, place.flag.x + place.flag.width) - x,
      height: Math.max(place.body.y + place.body.height, place.flag.y + place.flag.height) - y };
  };
  const marker: CorpsMarker = { id: 'own', cell: { col: 10, row: 10 }, nationColor: '#8B1E1E', leaderName: '조조', heading: null, standing: 'own' };
  const toScreen = (cell: CellPoint) => ({ x: cell.col * 16, y: cell.row * 16 });

  it.each([['군 보기(핀 머리만)', false], ['현 보기(머리 + 꼬리표)', true]] as const)('%s: 핀 끝 바로 아래 군단 → 비킨 뒤 표지 · 핀 상자가 겹치지 않는다', (_name, withTag) => {
    const place = corpsPlacement(marker, 16, toScreen);
    // 핀 끝이 표지 몸통 가운데 바로 위 — 실지도에서 핀(위) · 내 군단(아래)이 겹친 꼴
    const pin = myLocationPinBoxes({ x: place.at.x, y: place.at.y + 20 }, withTag);
    expect(pin.some((box) => overlap(mark(place), box)), '전제: 비키기 전에는 겹친다').toBe(true);
    const nudged = nudgeFromPin(place, pin);
    expect(pin.filter((box) => overlap(mark(nudged), box))).toEqual([]);
    // 가로로만 옮기고, 경로 · 누를 자리도 같이 간다
    expect(nudged.at.y).toBe(place.at.y);
    expect(nudged.hit.x - place.hit.x).toBe(nudged.at.x - place.at.x);
  });

  it('겹치지 않으면 그대로 · 경로 시작점은 옮긴 자리', () => {
    const place = corpsPlacement(marker, 16, toScreen);
    const far = myLocationPinBoxes({ x: place.at.x + 400, y: place.at.y }, true);
    expect(nudgeFromPin(place, far)).toBe(place);
    const moving = corpsPlacement({ ...marker, heading: 'right', route: [{ col: 11, row: 10 }] }, 16, toScreen);
    const nudged = nudgeFromPin(moving, myLocationPinBoxes({ x: moving.at.x, y: moving.at.y + 20 }, false));
    expect(nudged.route[0]).toEqual(nudged.at);
    expect(nudged.route[1]).toEqual(moving.route[1]);
  });
});
