import { describe, expect, it } from 'vitest';
import type { Warehouse, Warehouses } from '@/lib/campaign-reads';
import { supplyLinesOf } from '@/lib/supply-lines';

// 작전실 새 지도 「보급선」 층(계약판 K4-06 `warehouses[].links`). 서버가 칸을 안 주면 서버 대기(null), 주면 바로 선.
const STOCK = { money: 0, grain: 0, iron: 0, timber: 0, horses: 0 };
const wh = (cityId: number, links?: Warehouse['links']): Warehouse => ({ cityId, name: `城${cityId}`, commanderyName: null, isCapital: cityId === 1, supplied: true, stock: STOCK, ...(links ? { links } : {}) });
const read = (warehouses: Warehouse[], status: Warehouses['status'] = 'READY'): Warehouses => ({ status, warehouses });

describe('supplyLinesOf', () => {
  it('연결 칸(links)이 어느 창고에도 없거나 READY 가 아니면 null — 서버 대기', () => {
    expect(supplyLinesOf(null)).toBeNull();
    expect(supplyLinesOf(read([wh(1), wh(2)]))).toBeNull();
    expect(supplyLinesOf(read([wh(1, [{ toCityId: 2, via: 'ROAD', state: 'OPEN' }])], 'WRONG_RULE_PROFILE'))).toBeNull();
  });

  it('칸이 있으면 빈 배열이어도 목록(이을 곳 없음) — 서버 대기와 가른다', () => {
    expect(supplyLinesOf(read([wh(1, []), wh(2)]))).toEqual([]);
  });

  it('선: 끊긴 까닭은 서버 문구 그대로(앞뒤 빈칸만 뺌), 이어진 선 · 빈 문구는 null', () => {
    expect(supplyLinesOf(read([wh(1, [
      { toCityId: 2, via: 'ROAD', state: 'OPEN', cutReason: '무시' },
      { toCityId: 3, via: 'WATER', state: 'CUT', cutReason: ' 적이 나루를 쥐었다 ' },
      { toCityId: 4, via: 'ROAD', state: 'CUT', cutReason: '  ' },
    ])]))).toEqual([
      { fromCityId: 1, toCityId: 2, via: 'ROAD', state: 'OPEN', cutReason: null },
      { fromCityId: 1, toCityId: 3, via: 'WATER', state: 'CUT', cutReason: '적이 나루를 쥐었다' },
      { fromCityId: 1, toCityId: 4, via: 'ROAD', state: 'CUT', cutReason: null },
    ]);
  });

  it('모르는 길 · 상태, 정수 아닌 城, 제 자신은 버리고, 양쪽 창고에서 온 같은 선은 하나', () => {
    const odd = [
      { toCityId: 2, via: 'AIR', state: 'OPEN' },
      { toCityId: 2, via: 'ROAD', state: 'BROKEN' },
      { toCityId: 2.5, via: 'ROAD', state: 'OPEN' },
      { toCityId: 1, via: 'ROAD', state: 'OPEN' },
    ] as unknown as Warehouse['links'];
    expect(supplyLinesOf(read([wh(1, odd)]))).toEqual([]);
    expect(supplyLinesOf(read([
      wh(1, [{ toCityId: 2, via: 'ROAD', state: 'OPEN' }]),
      wh(2, [{ toCityId: 1, via: 'ROAD', state: 'OPEN' }, { toCityId: 1, via: 'WATER', state: 'OPEN' }]),
    ]))).toEqual([
      { fromCityId: 1, toCityId: 2, via: 'ROAD', state: 'OPEN', cutReason: null },
      { fromCityId: 2, toCityId: 1, via: 'WATER', state: 'OPEN', cutReason: null },
    ]);
  });
});
