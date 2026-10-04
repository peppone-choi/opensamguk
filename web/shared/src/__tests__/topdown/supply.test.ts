import { describe, expect, it, vi } from 'vitest';
import { SUPPLY_TOKENS, drawSupply, supplySegments, tokenColor, type SupplyMapLine } from '../../map/topdown/supply';

// 「보급선」 층(계약판 K4-06): 城 사이 곧은 선, 보드 00c 범례 모양(이어짐 --moss-2 실선 · 끊김 --rust-2 점선 + ×).
const CENTRES: Record<number, { col: number; row: number }> = { 1: { col: 0, row: 0 }, 2: { col: 10, row: 0 }, 3: { col: 0, row: 10 }, 9: { col: 500, row: 500 } };
const centerOf = (id: number) => CENTRES[id] ?? null;
const toScreen = (cell: { col: number; row: number }) => ({ x: cell.col * 10, y: cell.row * 10 });
const VIEW = { width: 200, height: 200 };

describe('supplySegments', () => {
  it('城 가운데 → 화면 좌표, 끊김 표시를 싣는다', () => {
    const lines: SupplyMapLine[] = [{ fromCityId: 1, toCityId: 2, state: 'OPEN' }, { fromCityId: 1, toCityId: 3, state: 'CUT' }];
    expect(supplySegments(lines, centerOf, toScreen, VIEW)).toEqual([
      { from: { x: 0, y: 0 }, to: { x: 100, y: 0 }, cut: false },
      { from: { x: 0, y: 0 }, to: { x: 0, y: 100 }, cut: true },
    ]);
  });

  it('끝 城을 모르면 빼고, 두 끝을 담은 상자가 화면 밖이면 뺀다(한 끝만 밖이면 남긴다)', () => {
    expect(supplySegments([{ fromCityId: 1, toCityId: 7, state: 'OPEN' }], centerOf, toScreen, VIEW)).toEqual([]);
    expect(supplySegments([{ fromCityId: 9, toCityId: 9, state: 'OPEN' }], centerOf, toScreen, VIEW)).toEqual([]);
    expect(supplySegments([{ fromCityId: 1, toCityId: 9, state: 'OPEN' }], centerOf, toScreen, VIEW)).toHaveLength(1);
  });
});

describe('drawSupply', () => {
  it('이어진 선(실선) 다음 끊긴 선(점선 6 5) — 굵기 2.5, 끊긴 선 가운데에 ×(±7, 굵기 3)', () => {
    const calls: string[] = [];
    const ctx = {
      save: vi.fn(), restore: vi.fn(), beginPath: vi.fn(),
      moveTo: (x: number, y: number) => calls.push(`M${x},${y}`), lineTo: (x: number, y: number) => calls.push(`L${x},${y}`),
      stroke: function (this: { strokeStyle: string; lineWidth: number; dash: number[] }) { calls.push(`S ${this.strokeStyle} ${this.lineWidth} [${this.dash.join(' ')}]`); },
      setLineDash: function (this: { dash: number[] }, d: number[]) { this.dash = d; },
      dash: [] as number[], strokeStyle: '', lineWidth: 0, lineCap: '',
    } as unknown as CanvasRenderingContext2D;
    drawSupply(ctx, [
      { from: { x: 0, y: 0 }, to: { x: 100, y: 0 }, cut: false },
      { from: { x: 0, y: 0 }, to: { x: 0, y: 100 }, cut: true },
    ], { open: 'OPEN', cut: 'CUT' });
    expect(calls).toEqual([
      'M0,0', 'L100,0', 'S OPEN 2.5 []',
      'M0,0', 'L0,100', 'S CUT 2.5 [6 5]',
      'M-7,43', 'L7,57', 'M7,43', 'L-7,57', 'S CUT 3 []',
    ]);
  });

  it('선이 없으면 아무것도 안 그린다', () => {
    const ctx = { save: vi.fn() } as unknown as CanvasRenderingContext2D;
    drawSupply(ctx, [], { open: 'a', cut: 'b' });
    expect(ctx.save).not.toHaveBeenCalled();
  });
});

describe('tokenColor', () => {
  it('문서 뿌리의 토큰 값을 읽고, 없으면 같은 값의 기본색', () => {
    document.documentElement.style.setProperty('--moss-2', '#123456');
    expect(tokenColor(SUPPLY_TOKENS.open)).toBe('#123456');
    document.documentElement.style.removeProperty('--moss-2');
    expect(tokenColor(SUPPLY_TOKENS.open)).toBe('#8fa77a');
    expect(tokenColor(SUPPLY_TOKENS.cut)).toBe('#e08a7c');
  });
});
