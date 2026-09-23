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
  it('다수결만 쓰면 게임 城 67 곳이 남의 縣 색 위에 선다 — 이것이 고치는 대상이다', () => {
    // 이 수가 0 이 되면 downsampleOwner 쪽이 이미 고쳐졌다는 뜻이니 이 게이트를 다시 봐라.
    // 실측: 781 城 시절 162 → 변경 縣 51 곳이 城 782–832 로 서면서 189 → 2026-09-11
    // rasterGroup 을 4 에서 2 로 내리면서 35. 블록이 좁아지니 治所가 제 縣 땅을
    // 다수결로 지켜 내는 자리가 늘었다. → 2026-09-12 오배정 縣 8 곳을 제자리로 되돌리며
    // han-tiles 815 칸이 주인을 바꾸어 39(#704 가 이 핀을 같이 안 옮겨 빨갛게 남아 있었다).
    // → 2026-09-15 城 없던 縣 176 곳과 4~8 칸짜리 수·진·관 거점 省 73 곳이 서며 71. 작은 省의 治所는
    // 다수결 블록에서 이웃에게 지기 쉽다 — 아래 도장 찍기가 고치는 몫이다.
    // → 2026-09-16 城 1098 에서 70. 구원(680)이 忻州 飛地를 떠나 1007 과 칸을 나누지 않고, 정양(773)이 上郡 제자리로
    // 가서 빠졌다. 하음(56)은 바오터우의 60 칸 省이 九原과 블록을 나누며 새로 들었다.
    // → 2026-09-17 城 1133 에서 225. 城 아이콘이 씨앗 칸이 아니라 경위도 투영점(제 省 밖이면 안으로 민 칸)에
    // 서게 되면서(buildProvinceSeatCells) 省 가장자리에 선 治所가 늘었다 — 도장 찍기가 고치는 몫이다.
    // → 2026-09-17 巴郡 漢昌(579)이 閬中 쪽 省 341 에서 巴中 省 343 으로 옮기며(38cf118a, 讀史方輿紀要 卷68) 226.
    // 治所 칸(269,295)은 제 省 343 땅이지만 서쪽 가장자리라 2×2 블록이 341 과 2:2 로 갈려 다수결에서 진다
    // (#704 때처럼 데이터 커밋이 이 핀을 같이 안 옮겼다). 도장 찍기 뒤에는 제 색을 되찾아 아래 13 은 그대로다.
    // → 2026-09-18 지리 재분할(GH #806)에서 64. 郡 안 縣 경계를 城의 실제 위치로 다시 잘라 治所가 제 省 한가운데에
    // 서게 되면서 2×2 다수결에서 지는 자리가 226 → 64 로 줄었다. 남은 것은 8칸 안팎의 작은 省·거점 省과 같은 칸
    // 이웃이다 — 여전히 도장 찍기가 고치는 몫이다.
    // → 2026-09-21 근거 없는 조선반도·만주 취락 26 곳을 거두며(2288e886) 68 → 67.
    // 2026-09-23 결손 縣 56곳을 세우며 67 → 83. 도장 찍기가 고치는 대상이 늘어난 것이고, 찍은 뒤는 30 이다.
    expect(displaced(downsampleOwner(source, srcCols, cols, rows, RASTER_GROUP))).toHaveLength(83);
  });

  it('治所 칸을 되돌리면 68 → 27 로 줄고, 남는 27 은 전부 칸을 나눠 쓰는 城 이다', () => {
    const owner = stampSeatOwners(
      downsampleOwner(source, srcCols, cols, rows, RASTER_GROUP),
      source, srcCols, cols, rows, seat, RASTER_GROUP,
    );
    const left = displaced(owner);
    // 781 城 시절 44 → 832 城 시절 48 → rasterGroup 2 에서 5.
    // 남는 다섯은 광척·치평·성무·성양·양추 — 원본 셀이 한두 칸 차이라 더 못 갈린다.
    // 2026-09-15 城 1097 에서 11 — 아래 단언대로 전부 한 타일에 治所가 둘 이상 드는 자리다.
    // 2026-09-17 城 1133(경위도 투영 자리)에서 13 — 아래 단언대로 전부 한 타일에 治所가 둘 이상 드는 자리다.
    // 2026-09-18 지리 재분할(GH #806)에서 27. 治所가 실제 위치로 돌아와 서로 가까운 縣(씨앗 충돌 14쌍과 그 이웃,
    // 거점과 기증 縣 治所)이 같은 2×2 타일에 드는 자리가 13 → 27 로 늘었다 — 아래 단언대로 전부 한 타일에 治所가 둘 이상이다.
    // 2026-09-23 결손 縣 56곳을 세우며 27 → 30. 새 縣이 제 실제 자리에 서면서 이웃 縣과 같은 2×2
    // 타일에 드는 자리가 셋 늘었다 — 아래 단언이 그 셋도 전부 治所가 둘 이상인 칸임을 본다.
    expect(left).toHaveLength(30);

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
