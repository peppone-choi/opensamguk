import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  RASTER_GROUP, downsampleOwner, expandRunLength, sourceCellToTile, stampSeatOwners,
} from '../isoTileGrid';
import { buildProvinceSeatCells } from '../iso/useIsoTileGrid';
import type { HanTiles } from '../HanMapCanvas';

const ROOT = resolve(__dirname, '../../../..');
const tiles = JSON.parse(
  readFileSync(resolve(ROOT, 'data/map/han-tiles.json'), 'utf-8'),
) as HanTiles;
const world = JSON.parse(
  readFileSync(resolve(ROOT, 'infra/src/main/resources/map/han-world-v3.json'), 'utf-8'),
) as { cities: { name: string; provinceId?: number }[] };

const srcCols = tiles._meta.cols;
const srcRows = tiles._meta.rows;
const cols = Math.ceil(srcCols / RASTER_GROUP);
const rows = Math.ceil(srcRows / RASTER_GROUP);
const source = expandRunLength(tiles.owner, srcCols * srcRows);
const seat = buildProvinceSeatCells(tiles);

/** 게임 城 이 선 칸의 주인이 그 城 의 縣 이 아닌 곳. */
function displaced(owner: Int32Array): string[] {
  const out: string[] = [];
  for (const city of world.cities) {
    const pid = city.provinceId;
    if (pid == null || seat.col[pid] < 0) continue;
    const [c, r] = sourceCellToTile(seat.col[pid], seat.row[pid], RASTER_GROUP);
    if (owner[r * cols + c] !== pid) out.push(city.name);
  }
  return out;
}

describe('stampSeatOwners', () => {
  it('다수결만 쓰면 게임 城 35 곳이 남의 縣 색 위에 선다 — 이것이 고치는 대상이다', () => {
    // 이 수가 0 이 되면 downsampleOwner 쪽이 이미 고쳐졌다는 뜻이니 이 게이트를 다시 봐라.
    // 실측: 781 城 시절 162 → 변경 縣 51 곳이 城 782–832 로 서면서 189 → 2026-09-11
    // rasterGroup 을 4 에서 2 로 내리면서 35. 블록이 좁아지니 治所가 제 縣 땅을
    // 다수결로 지켜 내는 자리가 늘었다.
    expect(displaced(downsampleOwner(source, srcCols, cols, rows, RASTER_GROUP))).toHaveLength(35);
  });

  it('治所 칸을 되돌리면 35 → 5 로 줄고, 남는 5 는 전부 칸을 나눠 쓰는 城 이다', () => {
    const owner = stampSeatOwners(
      downsampleOwner(source, srcCols, cols, rows, RASTER_GROUP),
      source, srcCols, cols, rows, seat, RASTER_GROUP,
    );
    const left = displaced(owner);
    // 781 城 시절 44 → 832 城 시절 48 → rasterGroup 2 에서 5.
    // 남는 다섯은 광척·치평·성무·성양·양추 — 원본 셀이 한두 칸 차이라 더 못 갈린다.
    expect(left).toHaveLength(5);

    // 남는 것은 물리적으로 못 고친다 — 한 칸에 治所가 둘 이상 들면 색은 하나뿐이다.
    // 그래도 「그냥 남았다」로 두지 않는다: 남은 城 은 전부 그런 칸에 있어야 한다.
    const perTile = new Map<number, number>();
    for (let i = 0; i < seat.col.length; i += 1) {
      if (seat.col[i] < 0) continue;
      const [c, r] = sourceCellToTile(seat.col[i], seat.row[i], RASTER_GROUP);
      const index = r * cols + c;
      perTile.set(index, (perTile.get(index) ?? 0) + 1);
    }
    const byName = new Map(world.cities.map((city) => [city.name, city.provinceId]));
    for (const name of left) {
      const pid = byName.get(name)!;
      const [c, r] = sourceCellToTile(seat.col[pid], seat.row[pid], RASTER_GROUP);
      expect(perTile.get(r * cols + c), name).toBeGreaterThan(1);
    }
  });

  it('원본 셀에 없던 주인을 지어내지 않는다 — 찍는 값은 그 셀에 적힌 값 그대로다', () => {
    const owner = stampSeatOwners(
      downsampleOwner(source, srcCols, cols, rows, RASTER_GROUP),
      source, srcCols, cols, rows, seat, RASTER_GROUP,
    );
    // 한 칸에 治所가 둘 이상 들면 **먼저 나온 쪽**이 칸을 갖는다(실측 54 칸). 그래서
    // 기대값은 「그 칸에 드는 첫 治所의 원본 셀 값」이다.
    const first = new Map<number, number>();
    for (let i = 0; i < seat.col.length; i += 1) {
      if (seat.col[i] < 0) continue;
      const [c, r] = sourceCellToTile(seat.col[i], seat.row[i], RASTER_GROUP);
      const index = r * cols + c;
      if (!first.has(index)) first.set(index, source[seat.row[i] * srcCols + seat.col[i]]);
    }
    expect(first.size).toBeGreaterThan(0);
    for (const [index, value] of first) expect(owner[index]).toBe(value);
  });

  it('治所가 없는 칸은 다수결 그대로다', () => {
    const majority = downsampleOwner(source, srcCols, cols, rows, RASTER_GROUP);
    const owner = stampSeatOwners(
      downsampleOwner(source, srcCols, cols, rows, RASTER_GROUP),
      source, srcCols, cols, rows, seat, RASTER_GROUP,
    );
    const seatTiles = new Set<number>();
    for (let i = 0; i < seat.col.length; i += 1) {
      if (seat.col[i] < 0) continue;
      const [c, r] = sourceCellToTile(seat.col[i], seat.row[i], RASTER_GROUP);
      seatTiles.add(r * cols + c);
    }
    let changed = 0;
    for (let i = 0; i < owner.length; i += 1) {
      if (owner[i] === majority[i]) continue;
      changed += 1;
      expect(seatTiles.has(i)).toBe(true);
    }
    // 실제로 값이 바뀐 칸이 있어야 게이트가 뜻을 갖는다.
    expect(changed).toBeGreaterThan(0);
  });
});
