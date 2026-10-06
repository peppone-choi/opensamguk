import { describe, expect, it } from 'vitest';
import { FootprintIndex, hitTest, type SpriteHit } from '../../map/topdown/hitTest';
import { myLocationPinHits } from '../../map/topdown/myLocation';
import type { Camera, Viewport } from '../../map/topdown/types';

const viewport: Viewport = { width: 800, height: 600, dpr: 1 };
const cam: Camera = { center: { col: 100, row: 100 }, zoom: 16 };
// 화면 (400, 300) = 칸 (100, 100) 왼쪽 위 모서리.
const provinceAt = (col: number, row: number) => (col >= 90 && col < 110 && row >= 90 && row < 110 ? 42 : 0);
const footprints = new FootprintIndex([
  { cityId: 7, originCol: 98, originRow: 98, span: 5 },
  { cityId: 8, originCol: 102, originRow: 102, span: 3 },
]);
const sprite = (kind: SpriteHit['kind'], id: string, x: number, y: number, z: number): SpriteHit => ({
  kind, id, rect: { x, y, width: 40, height: 40 }, z,
});

describe('FootprintIndex', () => {
  it('발자국 칸마다 그 城, 밖은 undefined, 겹치면 먼저 적힌 城', () => {
    expect(footprints.cityAt(98, 98)).toBe(7);
    expect(footprints.cityAt(102, 102)).toBe(7);
    expect(footprints.cityAt(103, 103)).toBe(8);
    expect(footprints.cityAt(104, 104)).toBe(8);
    expect(footprints.cityAt(105, 105)).toBeUndefined();
    expect(footprints.cityAt(97, 100)).toBeUndefined();
    expect(footprints.cityAt(-1, 99)).toBeUndefined();
  });
});

describe('hitTest', () => {
  it('스프라이트가 가장 먼저, z 큰 쪽, 같은 z 는 배열 뒤쪽', () => {
    const sprites = [
      sprite('city', 'c1', 380, 280, 1),
      sprite('corps', 'k1', 390, 290, 5),
      sprite('flag', 'f1', 395, 295, 5),
      sprite('me', 'me', 1000, 1000, 99),
    ];
    const hit = hitTest({ x: 405, y: 305 }, cam, viewport, { sprites, footprints, provinceAt });
    expect(hit).toEqual({ kind: 'flag', id: 'f1', cell: { col: 100, row: 100 } });
    const onlyCity = hitTest({ x: 381, y: 281 }, cam, viewport, { sprites, footprints, provinceAt });
    expect(onlyCity.kind).toBe('city');
    expect(onlyCity.id).toBe('c1');
    const meTop = hitTest({ x: 405, y: 305 }, cam, viewport, {
      sprites: [...sprites, sprite('me', 'me', 400, 300, 10)], footprints, provinceAt,
    });
    expect(meTop).toMatchObject({ kind: 'me', id: 'me' });
  });

  it('스프라이트가 없으면 城 발자국', () => {
    const hit = hitTest({ x: 400 + 16 * 3.5, y: 300 + 16 * 3.5 }, cam, viewport, { sprites: [], footprints, provinceAt });
    expect(hit).toEqual({ kind: 'city', id: 8, cell: { col: 103, row: 103 } });
  });

  it('城 밖이면 구역(평면 값 − 1)', () => {
    const hit = hitTest({ x: 400 - 16 * 5 + 1, y: 300 }, cam, viewport, { sprites: [], footprints, provinceAt });
    expect(hit).toEqual({ kind: 'province', id: 41, cell: { col: 95, row: 100 } });
    const noIndex = hitTest({ x: 401, y: 301 }, cam, viewport, { sprites: [], provinceAt });
    expect(noIndex).toMatchObject({ kind: 'province', id: 41 });
  });

  it('구역도 없으면 none', () => {
    const hit = hitTest({ x: 10, y: 10 }, cam, viewport, { sprites: [], footprints, provinceAt });
    expect(hit.kind).toBe('none');
    expect(hit.id).toBeNull();
    expect(hit.cell).toEqual({ col: 100 - 25 + 0, row: 100 - 19 + 0 });
  });

  it('칸 경계 바로 왼쪽 · 위는 이전 칸이다', () => {
    const hit = hitTest({ x: 399.999, y: 299.999 }, cam, viewport, { sprites: [], provinceAt });
    expect(hit.cell).toEqual({ col: 99, row: 99 });
  });
});

describe('지도 위 DOM 핀(meOverlay)의 누를 자리', () => {
  // 핀 끝 = 화면 (400, 300). 핀 머리 48 × 62 는 끝 위로 선다. 핀 단추는 포인터를 받지 않으니 탭은 이 히트가 받는다.
  const tip = { x: 400, y: 300 };
  it('핀 머리를 누르면 내 위치, 다른 표지보다 위(z 10), 핀 밖은 지도(구역)', () => {
    const sprites = [sprite('corps', 'k1', 380, 250, 5), ...myLocationPinHits(tip, false)];
    expect(hitTest({ x: 400, y: 269 }, cam, viewport, { sprites, footprints, provinceAt }).kind).toBe('me');
    expect(hitTest({ x: 395, y: 255 }, cam, viewport, { sprites, footprints, provinceAt }).kind).toBe('me');
    expect(hitTest({ x: 400, y: 330 }, cam, viewport, { sprites, footprints, provinceAt }).kind).not.toBe('me');
  });

  it('현 보기 꼬리표도 누를 자리, 그 밖 보기는 핀 머리만', () => {
    const withTag = myLocationPinHits(tip, true);
    expect(withTag).toHaveLength(2);
    expect(myLocationPinHits(tip, false)).toHaveLength(1);
    const tag = withTag[1].rect;
    expect(hitTest({ x: tag.x + tag.width / 2, y: tag.y + tag.height / 2 }, cam, viewport, { sprites: withTag, footprints, provinceAt }).kind).toBe('me');
  });
});
