import { describe, expect, it } from 'vitest';
import { EDGE_MARGIN, myLocationHitRect, myLocationPinBoxes, placeMyLocation } from '../../map/topdown/myLocation';
import { cellToScreen } from '../../map/topdown/camera';
import { layoutLabels } from '../../map/topdown/labels';

const VIEW = { width: 400, height: 300, dpr: 1 };

describe('내 위치 표지 자리', () => {
  it('화면 안이면 핀 끝을 그 자리에 둔다', () => {
    const p = placeMyLocation({ x: 200, y: 200 }, VIEW);
    expect(p.onScreen).toBe(true);
    expect(p.point).toEqual({ x: 200, y: 200 });
  });

  it('핀 머리가 위로 잘리면 화면 밖으로 본다', () => {
    expect(placeMyLocation({ x: 200, y: 10 }, VIEW).onScreen).toBe(false);
  });

  it('밖이면 가운데에서 그쪽으로 뻗은 선이 가장자리(여백 안)에 닿는 곳에 화살표를 둔다', () => {
    const right = placeMyLocation({ x: 5000, y: 150 }, VIEW);
    expect(right.onScreen).toBe(false);
    expect(right.point.x).toBeCloseTo(400 - EDGE_MARGIN, 9);
    expect(right.point.y).toBeCloseTo(150, 9);
    expect(right.angle).toBeCloseTo(0, 9);
    const upLeft = placeMyLocation({ x: -1000, y: -1000 }, VIEW);
    expect(upLeft.point.y).toBeCloseTo(EDGE_MARGIN, 9); // 세로가 먼저 닿는다
    expect(upLeft.point.x).toBeGreaterThanOrEqual(EDGE_MARGIN);
    expect(upLeft.angle).toBeLessThan(0);
  });

  it('누르는 영역은 44 × 44 이상이다', () => {
    for (const target of [{ x: 200, y: 200 }, { x: -500, y: 150 }]) {
      const rect = myLocationHitRect(placeMyLocation(target, VIEW));
      expect(Math.min(rect.width, rect.height)).toBeGreaterThanOrEqual(44);
    }
  });
});

describe('이름표가 지도 위 DOM 핀을 피한다(보드 V31SystemMarker)', () => {
  it('핀 머리 48 × 62(끝 위), 현 보기면 오른쪽 꼬리표 띠까지', () => {
    expect(myLocationPinBoxes({ x: 200, y: 300 }, false)).toEqual([{ x: 176, y: 238, width: 48, height: 62 }]);
    expect(myLocationPinBoxes({ x: 200, y: 300 }, true)[1]).toEqual({ x: 228, y: 248, width: 104, height: 24 });
  });

  it('핀 자리에 걸린 郡 이름표는 빼고, 먼 이름표는 그대로 둔다', () => {
    const viewport = { width: 800, height: 600, dpr: 1 };
    const cam = { center: { col: 100, row: 100 }, zoom: 6 };
    const measure = (text: string, fontPx: number) => ({ width: text.length * fontPx, height: fontPx });
    const labels = [
      { id: 'under', text: '영천군', kind: 'commandery' as const, anchor: { col: 100, row: 99 }, priority: 1 },
      { id: 'far', text: '진류군', kind: 'commandery' as const, anchor: { col: 140, row: 99 }, priority: 1 },
    ];
    const tip = cellToScreen({ col: 100.5, row: 101 }, cam, viewport);
    expect(layoutLabels(labels, cam, viewport, measure).map((l) => l.id)).toEqual(['far', 'under']);
    expect(layoutLabels(labels, cam, viewport, measure, { avoid: myLocationPinBoxes(tip, false) }).map((l) => l.id)).toEqual(['far']);
  });
});
