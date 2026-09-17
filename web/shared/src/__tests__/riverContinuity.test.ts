// 끊긴 물줄기 잇기. 앞 절반은 손으로 짠 작은 격자로 규칙 하나씩을, 뒤 절반은 실제
// han-tiles.json + DEM 으로 「지도에서 강이 이어져 보이는가」를 본다.
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { inflateSync } from 'node:zlib';
import { RASTER_GROUP, TERRAIN, buildIsoTileGrid, isWater, sourceCellToTile } from '../isoTileGrid';
import { connectRivers, inlandWaterSystems, labelComponents } from '../riverContinuity';
import { applyCitySeedReseats } from '../iso/citySeedReseat';
import { buildProvinceSeatCells } from '../iso/useIsoTileGrid';
import type { HanTiles } from '../HanMapCanvas';

const { PLAIN: P, RIVER: R, LAKE: L, MOUNTAIN: M, SEA: S, OUT_OF_SCOPE: X } = TERRAIN;

function grid(rowsOfCodes: number[][], options: { level?: number[][]; dry?: [number, number][]; cutBy?: [number, number][] } = {}) {
  const rows = rowsOfCodes.length;
  const cols = rowsOfCodes[0].length;
  const code = Uint8Array.from(rowsOfCodes.flat());
  // cutBy: 城 이 지운 강 칸. 수계는 그 칸이 강이던 때로 뜬다.
  const before = Uint8Array.from(code);
  for (const [r, c] of options.cutBy ?? []) before[r * cols + c] = R;
  const system = inlandWaterSystems(before, cols, rows);
  const dry = new Uint8Array(cols * rows);
  for (const [r, c] of [...(options.dry ?? []), ...(options.cutBy ?? [])]) dry[r * cols + c] = 1;
  const level = Uint8Array.from((options.level ?? rowsOfCodes.map((row) => row.map(() => 1))).flat());
  return { code, cols, rows, level, dry, system };
}

const inlandPieces = (code: Uint8Array, cols: number, rows: number) =>
  labelComponents(code, cols, rows, (v) => v === R || v === L, false).count;

describe('connectRivers — 규칙', () => {
  it('꼭짓점으로만 닿는 강 사이에 한 칸을 놓는다', () => {
    const input = grid([
      [R, P, P],
      [P, R, P],
      [P, P, R],
    ]);
    const result = connectRivers(input);
    expect(result.diagonal).toBe(2);
    expect(inlandPieces(input.code, 3, 3)).toBe(1);
  });

  it('城 이 자른 강은 城 칸을 피해 돌아서 잇는다', () => {
    const input = grid([
      [P, P, P, P, P],
      [R, R, P, R, R],
      [P, P, P, P, P],
    ], { cutBy: [[1, 2]] });
    const result = connectRivers(input);
    expect(result.bypassJoins).toBe(1);
    expect(result.bypassTiles).toBe(3);
    expect(input.code[1 * 5 + 2]).toBe(P);
    expect(inlandPieces(input.code, 5, 3)).toBe(1);
  });

  it('나란히 흐르는 두 강은 잇지 않는다 — 가까운 것은 중간이지 끝이 아니다', () => {
    const input = grid([
      [R, P, R],
      [R, P, R],
      [R, P, R],
      [R, P, R],
    ].map((row) => [X, ...row, X]));
    // 위아래 끝은 격자 가장자리(지도 밖)에 닿아 있고 길이 한도 2 안에 옆 강이 있지만,
    // 끝점이 흐르던 방향(위·아래) 앞쪽에 있지 않으므로 잇지 않는다.
    connectRivers(input);
    expect(inlandPieces(input.code, 5, 4)).toBe(2);
  });

  it('하구 앞에서 멎은 강은 바다까지 잇는다', () => {
    const input = grid([
      [P, P, P, P, P, S],
      [R, R, R, P, P, S],
      [P, P, P, P, P, S],
    ]);
    const result = connectRivers(input);
    expect(result.gapJoins).toBe(1);
    expect(result.gapTiles).toBe(2);
    expect(labelComponents(input.code, 6, 3, isWater, false).count).toBe(1);
  });

  it('능선을 넘어서는 잇지 않는다', () => {
    const codes = [[R, R, P, P, P, P, R, R]];
    const level = [[1, 1, 1, 3, 3, 1, 1, 1]];
    const input = grid(codes, { level });
    connectRivers(input);
    expect(inlandPieces(input.code, 8, 1)).toBe(2);
  });

  it('호수는 산을 넘어 잇지 않는다 — 靑海湖는 내륙호다', () => {
    const input = grid([
      [R, R, M, M, L, L],
      [P, P, M, M, L, L],
    ]);
    connectRivers(input);
    expect(inlandPieces(input.code, 6, 2)).toBe(2);
  });

  it('지도 밖에 닿은 끝점은 두 칸까지만 잇는다 — 강이 판도를 나가는 자리다', () => {
    const far = grid([
      [X, X, X, X, X, X, X],
      [R, R, P, P, P, R, R],
    ]);
    connectRivers(far);
    expect(inlandPieces(far.code, 7, 2)).toBe(2);
    const near = grid([
      [X, X, X, X, X, X],
      [R, R, P, P, R, R],
    ]);
    connectRivers(near);
    expect(inlandPieces(near.code, 6, 2)).toBe(1);
  });

  it('한 수계의 조각들은 길을 따로 내지 않고 먼저 난 물길에 붙는다', () => {
    // 城 세 장이 강을 네 토막 냈다. 토막마다 제 우회로를 내면 물길이 두 겹으로 깔린다.
    const input = grid([
      [P, P, P, P, P, P, P],
      [R, P, R, P, R, P, R],
      [P, P, P, P, P, P, P],
    ], { cutBy: [[1, 1], [1, 3], [1, 5]] });
    const result = connectRivers(input);
    expect(inlandPieces(input.code, 7, 3)).toBe(1);
    // 2×2 가 통째로 강인 자리가 없어야 한 줄이다.
    for (let r = 0; r + 1 < 3; r += 1) {
      for (let c = 0; c + 1 < 7; c += 1) {
        const block = [input.code[r * 7 + c], input.code[r * 7 + c + 1], input.code[(r + 1) * 7 + c], input.code[(r + 1) * 7 + c + 1]];
        expect(block.every((v) => v === R)).toBe(false);
      }
    }
    expect(result.bypassJoins).toBe(3);
  });

  it('아무것도 잇지 않는 새 칸은 걷어 낸다 — 원본 강 칸은 그대로 둔다', () => {
    // 대각 두 쌍이 한 칸씩 놓으면 가운데 2×2 가 통째로 강이 된다. 하나는 없어도 이어져 있다.
    const input = grid([
      [R, R, P],
      [R, P, R],
      [P, R, R],
    ]);
    const before = Uint8Array.from(input.code);
    const result = connectRivers(input);
    expect(inlandPieces(input.code, 3, 3)).toBe(1);
    expect(result.diagonal - result.pruned).toBe(1);
    for (let i = 0; i < before.length; i += 1) if (before[i] === R) expect(input.code[i]).toBe(R);
  });

  it('城 칸과 지도 밖 칸에는 물길을 놓지 않는다', () => {
    const input = grid([
      [X, X, X, X, X],
      [R, R, P, R, R],
      [P, P, P, P, P],
    ], { dry: [[1, 2], [2, 2]] });
    connectRivers(input);
    expect(input.code[1 * 5 + 2]).toBe(P);
    expect(input.code[2 * 5 + 2]).toBe(P);
    expect(input.code[0 * 5 + 2]).toBe(X);
  });
});

// ── 실데이터 ────────────────────────────────────────────────────────────────

const ROOT = resolve(__dirname, '../../../..');

/** 8비트 회색조 비인터레이스 PNG → RGBA. DEM 단 PNG 가 그 형식이다. */
function decodeGrayAsRgba(path: string): { width: number; height: number; rgba: Uint8Array } {
  const file = readFileSync(path);
  let offset = 8;
  let width = 0;
  let height = 0;
  const idat: Buffer[] = [];
  while (offset < file.length) {
    const length = file.readUInt32BE(offset);
    const type = file.toString('ascii', offset + 4, offset + 8);
    const body = file.subarray(offset + 8, offset + 8 + length);
    if (type === 'IHDR') {
      width = body.readUInt32BE(0);
      height = body.readUInt32BE(4);
      expect([body[8], body[9], body[12]]).toEqual([8, 0, 0]);
    } else if (type === 'IDAT') idat.push(body);
    offset += 12 + length;
  }
  const raw = inflateSync(Buffer.concat(idat));
  const gray = new Uint8Array(width * height);
  for (let y = 0; y < height; y += 1) {
    const filter = raw[y * (width + 1)];
    for (let x = 0; x < width; x += 1) {
      const v = raw[y * (width + 1) + 1 + x];
      const a = x > 0 ? gray[y * width + x - 1] : 0;
      const b = y > 0 ? gray[(y - 1) * width + x] : 0;
      const c = x > 0 && y > 0 ? gray[(y - 1) * width + x - 1] : 0;
      let predicted = 0;
      if (filter === 1) predicted = a;
      else if (filter === 2) predicted = b;
      else if (filter === 3) predicted = (a + b) >> 1;
      else if (filter === 4) {
        const p = a + b - c;
        const pa = Math.abs(p - a);
        const pb = Math.abs(p - b);
        const pc = Math.abs(p - c);
        predicted = pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
      }
      gray[y * width + x] = (v + predicted) & 255;
    }
  }
  const rgba = new Uint8Array(width * height * 4);
  for (let i = 0; i < gray.length; i += 1) rgba[i * 4] = gray[i];
  return { width, height, rgba };
}

describe('connectRivers — han-world-v3 실측', () => {
  const tiles = JSON.parse(readFileSync(resolve(ROOT, 'data/map/han-tiles.json'), 'utf-8')) as HanTiles;
  applyCitySeedReseats(tiles.cities);
  const seats = buildProvinceSeatCells(tiles);
  const landmarks = {
    col: Int32Array.from(tiles.cities, (city) => city.col),
    row: Int32Array.from(tiles.cities, (city) => city.row),
  };
  const dem = decodeGrayAsRgba(resolve(ROOT, 'web/game/public/map/elevation/han-world-v3-levels.png'));
  const built = buildIsoTileGrid(tiles.terrain, dem.rgba, dem.width, dem.height, RASTER_GROUP, seats, landmarks);
  const { code, cols, rows } = built;
  const at = (row: number, col: number) => row * cols + col;

  const inland = labelComponents(code, cols, rows, (v) => v === R || v === L, false);
  const water = labelComponents(code, cols, rows, isWater, false);

  it('끊긴 덩어리가 크게 준다 — 잇기 전 183(강+호수, 변 연결)', () => {
    // 남는 것은 (가) 바다로 따로 나가는 수계들, (나) 靑海湖 같은 내륙호, (다) 판도 가장자리에서
    // 들고 나는 강 토막이다. 이 수가 다시 불어나면 잇기가 어디선가 꺼진 것이다.
    // 실측 33. 바다로 따로 나가는 강은 바다를 거쳐서만 이어지므로 여기서는 따로 센다 —
    // 그래서 0 이 목표가 아니다. 城·거점이 늘면 한둘 움직일 수 있어 여유를 둔다.
    expect(inland.count).toBeLessThanOrEqual(40);
  });

  it('물 전체로 보면 본바다에 닿지 못한 덩어리는 한 줌이다', () => {
    // 실측 14: 북쪽 가장자리 강 2 · 내륙호 5(靑海湖 등) · 太湖 · 남쪽으로 판도를 나가는
    // 瀾滄江·怒江계 2 · 벵골만 쪽 바다 1 과 그 앞 조각 2 · 본바다.
    expect(water.count).toBeLessThanOrEqual(16);
  });

  it('長江은 하구에서 바다에 닿는다', () => {
    // 원본은 하구 만입 두세 칸 앞에서 중심선이 멎어 있었다(타일 r146 c257 부근).
    const sea = water.label[at(200, 380)];
    expect(code[at(200, 380)]).toBe(S);
    expect(water.label[at(163, 210)]).toBe(sea);
  });

  it('나란히 흐르는 金沙江과 瀾滄江·怒江은 한 강이 되지 않는다', () => {
    const jinsha = inland.label[at(202, 64)];
    const nu = inland.label[at(202, 56)];
    expect(code[at(202, 64)]).toBe(R);
    expect(code[at(202, 56)]).toBe(R);
    expect(jinsha).not.toBe(nu);
  });

  it('靑海湖는 黃河에 이어지지 않는다', () => {
    expect(code[at(93, 73)]).toBe(L);
    const lake = inland.label[at(93, 73)];
    let riverTiles = 0;
    for (let i = 0; i < code.length; i += 1) if (inland.label[i] === lake && code[i] === R) riverTiles += 1;
    expect(riverTiles).toBe(0);
  });

  it('城 이 선 칸에는 여전히 물이 없다', () => {
    const wet: string[] = [];
    for (const cells of [seats, landmarks]) {
      for (let n = 0; n < cells.col.length; n += 1) {
        if (cells.col[n] < 0) continue;
        const [c, r] = sourceCellToTile(cells.col[n], cells.row[n], RASTER_GROUP);
        if (r < 0 || r >= rows || c < 0 || c >= cols || !isWater(code[at(r, c)])) continue;
        // 원본 네 셀이 전부 물인 城(진짜 섬)은 landUnderSeats 가 일부러 손대지 않는다.
        let land = 0;
        for (let dr = 0; dr < RASTER_GROUP; dr += 1) {
          for (let dc = 0; dc < RASTER_GROUP; dc += 1) {
            const v = tiles.terrain[r * RASTER_GROUP + dr].charCodeAt(c * RASTER_GROUP + dc) - 48;
            if (!isWater(v) && v !== X) land += 1;
          }
        }
        if (land > 0 && cells === seats) wet.push(`${r},${c}`);
        if (cells === landmarks && code[at(r, c)] === R && land === RASTER_GROUP * RASTER_GROUP) wet.push(`${r},${c}`);
      }
    }
    expect(wet).toEqual([]);
  });

  it('새 물길이 두세 겹으로 뭉치지 않는다 — 潼關 굽이', () => {
    // 2×2 가 통째로 강인 자리 수. 원본만으로 50, 잇기 직후 93 이었다.
    // 수계 물려주기 + 걷어 내기 뒤 실측 71. 다시 90 을 넘으면 뭉침이 돌아온 것이다.
    let blocks = 0;
    for (let r = 0; r + 1 < rows; r += 1) {
      for (let c = 0; c + 1 < cols; c += 1) {
        if (code[at(r, c)] === R && code[at(r, c + 1)] === R && code[at(r + 1, c)] === R && code[at(r + 1, c + 1)] === R) blocks += 1;
      }
    }
    expect(blocks).toBeLessThanOrEqual(80);
  });

  it('강이 지도를 덮지 않는다 — 비중 2% 아래', () => {
    let river = 0;
    for (let i = 0; i < code.length; i += 1) if (code[i] === R) river += 1;
    expect(river / code.length).toBeLessThan(0.02);
  });
});
