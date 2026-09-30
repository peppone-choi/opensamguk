import { describe, expect, it, vi } from 'vitest';
import { CORPS_MIN_HIT_PX, corpsMarkerSize, corpsPlacement, headingOf, type CorpsMarker } from '../../map/topdown/corps';
import { createKitCorpsArt } from '../../map/topdown/corpsArt';

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
