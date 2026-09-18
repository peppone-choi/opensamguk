import { describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { expandRunLength } from '../isoTileGrid';
import { buildProvinceSeatCells } from '../iso/useIsoTileGrid';
import { drawSeaRoute } from '../iso/marker';
import type { HanTiles } from '../HanMapCanvas';

// 「거점 아이콘이 해당 프로빈스 밖에 있는 경우가 아직 있네. 좌표 대로 따라가되, 해당 프로빈스 밖에 있으면
// 안으로 밀어넣어야지」(2026-09-17). 城 아이콘 자리(buildProvinceSeatCells)는 경위도 투영점을 따르고,
// 그 점이 제 縣(province) 밖이면 제 縣 칸 중 가장 가까운 칸으로 밀린다.
const ROOT = resolve(__dirname, '../../../..');
const tiles = JSON.parse(readFileSync(resolve(ROOT, 'data/map/han-tiles.json'), 'utf-8')) as HanTiles;
const world = JSON.parse(
  readFileSync(resolve(ROOT, 'infra/src/main/resources/map/han-world-v3.json'), 'utf-8'),
) as { cities: { id: number; provinceId?: number }[]; seaRoutes: { from: number; to: number; kind: string }[] };

describe('城 아이콘은 제 省 안에 선다', () => {
  const owner = expandRunLength(tiles.owner, tiles._meta.cols * tiles._meta.rows);
  const seat = buildProvinceSeatCells(tiles);

  it('縣 구획이 있는 모든 게임 城 의 자리가 제 省 칸이다', () => {
    const outside = world.cities.filter((city) => {
      const pid = city.provinceId;
      if (pid == null || seat.col[pid] < 0) return false;
      return owner[seat.row[pid] * tiles._meta.cols + seat.col[pid]] !== pid;
    }).map((city) => city.id);
    expect(outside).toEqual([]);
  });

  it('縣 구획이 없는 城(직할 省)도 좌표를 제 省 안으로 민다', () => {
    const pid = world.cities.find((city) => city.id === 704)!.provinceId!;
    // 투영점이 어디든(격자 원점) 결과는 그 省 칸이어야 한다 — 밀어 넣기가 살아 있음을 같은 축으로 보인다.
    const moved = seat.insideProvince!(pid, 0, 0)!;
    expect(owner[moved.row * tiles._meta.cols + moved.col]).toBe(pid);
  });

  it('투영점이 이미 제 省 안이면 옮기지 않는다', () => {
    const pid = world.cities.find((city) => city.id === 1)!.provinceId!;
    expect(seat.insideProvince!(pid, seat.col[pid] + 0.3, seat.row[pid] + 0.4))
      .toEqual({ col: seat.col[pid], row: seat.row[pid] });
  });
});

describe('뱃길', () => {
  it('세계 파일이 바닷길 13 줄 + 강 뱃길 3 줄을 싣고, 끝점은 모두 게임 城 이다', () => {
    const ids = new Set(world.cities.map((city) => city.id));
    // 2026-09-18 강 뱃길(kind=RIVER, 수로 망 원장 portLinks: 江州–夷陵–樊口–濡須口)이 같은 목록에 들어왔다.
    expect(world.seaRoutes.filter((route) => route.kind === 'SEA')).toHaveLength(13);
    expect(world.seaRoutes.filter((route) => route.kind === 'RIVER')).toHaveLength(3);
    expect(world.seaRoutes).toHaveLength(16);
    expect(world.seaRoutes.every((route) => ids.has(route.from) && ids.has(route.to))).toBe(true);
  });

  it('두 城 사이를 한 줄의 곡선으로 긋는다', () => {
    const calls: string[] = [];
    const context = new Proxy({}, {
      get: (_target, key) => (typeof key === 'string' && ['save', 'restore', 'beginPath', 'moveTo', 'quadraticCurveTo', 'stroke', 'setLineDash'].includes(key)
        ? vi.fn(() => calls.push(key)) : undefined),
      set: () => true,
    }) as unknown as CanvasRenderingContext2D;
    drawSeaRoute(context, 0, 0, 100, 0, { k: 1 });
    expect(calls.filter((call) => call === 'quadraticCurveTo')).toHaveLength(1);
    expect(calls.filter((call) => call === 'moveTo')).toHaveLength(1);
  });
});
