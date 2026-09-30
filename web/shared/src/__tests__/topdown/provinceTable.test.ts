import { describe, expect, it } from 'vitest';
import {
  FLAG_HOVERED,
  FLAG_SELECTED,
  TABLE_WIDTH,
  buildProvinceTable,
  shadeRgb,
  type ProvinceTableInput,
} from '../../map/topdown/provinceTable';

const base: ProvinceTableInput = { provinceCount: 6, occupancy: [], nations: [] };

/** RGBA of the texel for a province index (plane value = index + 1). */
function texel(bytes: Uint8Array, provinceIndex: number): number[] {
  const at = (provinceIndex + 1) * 4;
  return Array.from(bytes.subarray(at, at + 4));
}

describe('구역 표 모양', () => {
  it('폭 4096, 높이는 (구역 수 + 1) 텍셀을 담을 만큼', () => {
    expect(buildProvinceTable(base).height).toBe(1);
    expect(buildProvinceTable({ ...base, provinceCount: 4095 }).height).toBe(1);
    expect(buildProvinceTable({ ...base, provinceCount: 4096 }).height).toBe(2);
    const table = buildProvinceTable({ ...base, provinceCount: 1608 });
    expect(table.width).toBe(TABLE_WIDTH);
    expect(table.bytes).toHaveLength(TABLE_WIDTH * 4);
  });
});

describe('R = 세력 칸', () => {
  it('구역 번호 p 는 텍셀 p + 1 에 들어가고 텍셀 0 은 비어 있다', () => {
    const table = buildProvinceTable({
      ...base,
      occupancy: [{ provinceIndex: 0, nationId: 9 }, { provinceIndex: 5, nationId: 9 }],
      nations: [{ id: 9, color: '#c62828' }],
    });
    expect(Array.from(table.bytes.subarray(0, 4))).toEqual([0, 0, 0, 0]);
    expect(table.bytes[4]).toBe(1);
    expect(table.bytes[6 * 4]).toBe(1);
    expect(texel(table.bytes, 1)[0]).toBe(0);
  });

  it('세력 칸은 세력 id 오름차순이고, 색이 없거나 틀린 세력 · id 0 은 무소속', () => {
    const table = buildProvinceTable({
      ...base,
      occupancy: [
        { provinceIndex: 0, nationId: 30 },
        { provinceIndex: 1, nationId: 4 },
        { provinceIndex: 2, nationId: 17 },
        { provinceIndex: 3, nationId: 0 },
        { provinceIndex: 4, nationId: 55 },
        { provinceIndex: 5, nationId: 99 },
      ],
      nations: [
        { id: 30, color: '#1565C0' },
        { id: 4, color: '#2e7d32' },
        { id: 17, color: 'red' },
        { id: 0, color: '#ffffff' },
        { id: 55, color: '#12345' },
      ],
    });
    expect([...table.nationSlots]).toEqual([[4, 1], [30, 2]]);
    expect([0, 1, 2, 3, 4, 5].map((p) => texel(table.bytes, p)[0])).toEqual([2, 1, 0, 0, 0, 0]);
  });

  it('범위 밖 구역 번호는 무시한다', () => {
    const table = buildProvinceTable({
      ...base,
      occupancy: [{ provinceIndex: 6, nationId: 1 }, { provinceIndex: -1, nationId: 1 }],
      nations: [{ id: 1, color: '#ff0000' }],
    });
    expect(table.bytes.every((value) => value === 0)).toBe(true);
  });

  it('소유 세력이 255 를 넘으면 멈춘다', () => {
    const nations = Array.from({ length: 256 }, (_, i) => ({ id: i + 1, color: '#102030' }));
    expect(() => buildProvinceTable({ ...base, nations })).toThrow(/255/);
    expect(() => buildProvinceTable({ ...base, nations: nations.slice(0, 255) })).not.toThrow();
  });
});

describe('세력 색표', () => {
  it('칸마다 주색 RGBA + 그늘 RGBA, 칸 0 은 0', () => {
    const table = buildProvinceTable({ ...base, nations: [{ id: 3, color: '#ff0000' }] });
    expect(table.nationPalette).toHaveLength(256 * 8);
    expect(Array.from(table.nationPalette.subarray(0, 8))).toEqual([0, 0, 0, 0, 0, 0, 0, 0]);
    // 순수 빨강: 명도 0.5 × 0.55 = 0.275, 채도 1 → (0.55, 0, 0) × 255 = 140.25 → 140.
    expect(Array.from(table.nationPalette.subarray(8, 16))).toEqual([255, 0, 0, 255, 140, 0, 0, 255]);
  });

  it('그늘은 참고 렌더러 shade(c, 0.55) 와 같은 바이트다', () => {
    // 기대값은 Python 3.14 colorsys + numpy round(짝수 쪽 반올림)로 구웠다:
    // shade(c,k): hh,l,s=colorsys.rgb_to_hls(*(c/255)); (np.array(colorsys.hls_to_rgb(hh,l*k,s))*255).round()
    const fixture: [string, [number, number, number]][] = [
      ['#ff0000', [140, 0, 0]],
      ['#c62828', [109, 22, 22]],
      ['#1565c0', [12, 56, 106]],
      ['#2e7d32', [25, 69, 28]],
      ['#808080', [70, 70, 70]],
      ['#ffffff', [140, 140, 140]],
      ['#000000', [0, 0, 0]],
      ['#f9a825', [153, 96, 4]],
      ['#6a1b9a', [58, 15, 85]],
      ['#00ffff', [0, 140, 140]],
      ['#123456', [10, 29, 47]],
      ['#abcdef', [36, 113, 189]],
      ['#fe01fe', [140, 1, 140]],
    ];
    for (const [hex, expected] of fixture) {
      const rgb: [number, number, number] = [
        Number.parseInt(hex.slice(1, 3), 16),
        Number.parseInt(hex.slice(3, 5), 16),
        Number.parseInt(hex.slice(5, 7), 16),
      ];
      expect([hex, shadeRgb(rgb, 0.55)]).toEqual([hex, expected]);
    }
  });

  it('회색은 채도 0 이라 명도만 줄어든다(따로 계산한 값과 같다)', () => {
    // l = 128/255, l × 0.55 × 255 = 70.4 → 70
    expect(shadeRgb([128, 128, 128], 0.55)).toEqual([70, 70, 70]);
    expect(shadeRgb([200, 200, 200], 0.55)).toEqual([110, 110, 110]);
  });
});

describe('G = 시야', () => {
  it('郡 번호를 거쳐 구역으로 펼친다. 시야 표가 없거나 郡을 모르면 보임(0)', () => {
    const table = buildProvinceTable({
      ...base,
      commanderyOfProvince: Int32Array.from([10, 10, 11, 12, -1, 13]),
      vision: new Map([[10, 'FOG'], [11, 'INTEL'], [12, 'FULL'], [99, 'FOG']] as const),
    });
    expect([0, 1, 2, 3, 4, 5].map((p) => texel(table.bytes, p)[1])).toEqual([2, 2, 1, 0, 0, 0]);
    const none = buildProvinceTable({ ...base, commanderyOfProvince: [10, 10, 11, 12, -1, 13] });
    expect(none.bytes.every((value) => value === 0)).toBe(true);
  });
});

describe('B = 대상 고르기', () => {
  it('고르기 모드가 아니면 0, 모드면 가능 1 · 불가 2 · 후보 밖 3', () => {
    expect(buildProvinceTable(base).bytes.every((value) => value === 0)).toBe(true);
    const table = buildProvinceTable({
      ...base,
      pick: { candidates: new Map([[0, true], [2, false], [5, true]]) },
    });
    expect([0, 1, 2, 3, 4, 5].map((p) => texel(table.bytes, p)[2])).toEqual([1, 3, 2, 3, 3, 1]);
    expect(table.bytes[2]).toBe(0);
  });
});

describe('A = 선택 · 강조 비트', () => {
  it('선택 1, 강조 2, 둘 다면 3', () => {
    const table = buildProvinceTable({ ...base, selected: new Set([1, 3]), hovered: 3 });
    expect([0, 1, 2, 3].map((p) => texel(table.bytes, p)[3])).toEqual([0, FLAG_SELECTED, 0, FLAG_SELECTED | FLAG_HOVERED]);
    const hoverOnly = buildProvinceTable({ ...base, hovered: 0 });
    expect(texel(hoverOnly.bytes, 0)[3]).toBe(FLAG_HOVERED);
    expect(buildProvinceTable({ ...base, hovered: null }).bytes.every((value) => value === 0)).toBe(true);
  });
});
