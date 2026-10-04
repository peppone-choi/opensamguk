import { describe, expect, it } from 'vitest';
import { bakeCommanderyAnchors, commanderyOfProvince } from '../../map/topdown/bakePlaces';
import type { PlacesData } from '../../map/topdown/places';

// 새 지도 화면의 군국 표 재료: 옛 지형(juns) 대신 bake 장소 표. 배열 자리가 군국 번호(城 commanderyIndex · 서버 郡 번호와 같은 순서).
function places(): PlacesData {
  const city = (id: number, cell: [number, number], commanderyIndex: number) => ({
    id, name: `${id}현`, level: 5, cell, provinceIndex: 0, countyIndex: 0, commanderyIndex, isSeat: true,
    footprint: { originCol: cell[0] - 1, originRow: cell[1] - 1, span: 3, innerSpan: 1 },
    roofCell: null, gates: '', site: null, households: null,
  });
  return {
    schemaVersion: 1, provinceCount: 0, provinceAdmin: [], counties: [], ju: [], passes: [],
    commanderies: [
      { id: 'PARENT-0000', name: '하남윤', kind: 'METROPOLITAN', seatCityId: 46 },
      { id: 'PARENT-0001', name: '하내군', kind: 'COMMANDERY', seatCityId: 30 },
      { id: 'PARENT-0002', name: '빈군', kind: 'COMMANDERY', seatCityId: null },
    ],
    cities: [city(46, [1503, 930], 0), city(30, [1560, 897], 1)],
    labels: [
      { id: 'commandery:0', text: '하남윤', kind: 'commandery', anchor: [1505, 933], priority: 1, footprintSpan: 13 },
      { id: 'city:30', text: '회현', kind: 'commanderySeat', anchor: [1560, 897], priority: 1, footprintSpan: 9 },
    ],
  };
}

describe('bake 군국 표 재료', () => {
  it('郡 이름표 자리 → 없으면 치소 城 칸 → 둘 다 없으면 NaN(군국 표가 뺀다), 순서는 군국 번호', () => {
    const anchors = bakeCommanderyAnchors(places());
    expect(anchors[0]).toEqual({ name: '하남윤', col: 1505, row: 933 });
    // 城 이름표(city:30)는 郡 대표 칸이 아니다 — 치소 城 칸으로 간다
    expect(anchors[1]).toEqual({ name: '하내군', col: 1560, row: 897 });
    expect(anchors[2].name).toBe('빈군');
    expect(Number.isNaN(anchors[2].col) && Number.isNaN(anchors[2].row)).toBe(true);
  });

  it('번호는 bake 가 싣는 commanderyNo(서버 郡 번호)를 따른다 — 장소 표 자리와 어긋나도 그 번호 자리에 둔다', () => {
    const data = places();
    data.commanderies = [
      { ...data.commanderies[0], commanderyNo: 2 },
      { ...data.commanderies[1], commanderyNo: 0 },
      { ...data.commanderies[2], commanderyNo: 3 },
    ];
    const anchors = bakeCommanderyAnchors(data);
    expect(anchors.map((entry) => entry.name)).toEqual(['하내군', '', '하남윤', '빈군']);
    // 이름표 자리는 장소 표 자리(commandery:0)로 찾는다 — 번호 2 자리에 하남윤의 이름표 칸
    expect(anchors[2]).toEqual({ name: '하남윤', col: 1505, row: 933 });
    expect(Number.isNaN(anchors[1].col)).toBe(true);
  });

  it('구역 → 서버 郡 번호(시야 칠하기의 키): bake commanderyNo, 없으면 장소 표 자리, 모르는 구역은 −1', () => {
    const data = places();
    data.provinceCount = 5;
    // 구역 4는 장소 표에 없는 郡 자리(9)를 가리킨다 — 지어내지 않고 −1
    data.provinceAdmin = [[0, 0, 0], [1, 1, 0], [2, 1, 0], [-1, -1, -1], [3, 9, 0]];
    expect(Array.from(commanderyOfProvince(data))).toEqual([0, 1, 1, -1, -1]);
    data.commanderies = [
      { ...data.commanderies[0], commanderyNo: 2 },
      { ...data.commanderies[1], commanderyNo: 0 },
      data.commanderies[2],
    ];
    expect(Array.from(commanderyOfProvince(data))).toEqual([2, 0, 0, -1, -1]);
  });
});
