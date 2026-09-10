// 아이소메트릭 지도 타일 격자 파생 — 2D 스프라이트 렌더러와 3D glTF 렌더러가 함께 쓴다.
//
// 입력이 둘이다.
//   1. /api/game/api/map/terrain 의 `terrain` — 768×669 지형 코드 문자열 669줄.
//   2. /map/elevation/han-world-v3-levels.png — 192×167 DEM 단차 레벨(0..6).
//      tools/map/build_elevation_grid.py 가 NOAA ETOPO1 에서 뽑아 같은 투영으로 리샘플한 것이다.
//
// 두 애셋 매니페스트(iso2d·iso3d)가 tileGrid 를 192×167 로 못박아 뒀다.
// 768/4 = 192, 669/4 = 167.25 이므로 마지막 부분 행은 버린다(채우면 인덱스가 한 줄 어긋난다).
//
// React 도, three 도, DOM 도 쓰지 않는다. 순수 함수만 둔다.

export const TERRAIN = {
  SEA: 0,
  PLAIN: 1,
  MOUNTAIN: 2,
  RIVER: 3,
  LAKE: 4,
  DESERT: 5,
  PLATEAU: 6,
  BASIN: 7,
  HILL: 8,
  OUT_OF_SCOPE: 9,
} as const;

export type TerrainCode = (typeof TERRAIN)[keyof typeof TERRAIN];

/** 애셋 파일명 순서. iso2d/iso3d 매니페스트의 terrain 목록과 같은 철자를 쓴다. */
export const TERRAIN_ASSET_NAME: readonly string[] = [
  'sea', 'plain', 'mountain', 'river', 'lake', 'desert', 'plateau', 'basin', 'hill', '',
];

/** 물이라 평평하게 그려야 하는 지형. */
export function isWater(code: number): boolean {
  return code === TERRAIN.SEA || code === TERRAIN.RIVER || code === TERRAIN.LAKE;
}

/** 원본 지형 격자를 타일 격자로 줄이는 배수. 두 매니페스트가 rasterGroup: 4 로 고정했다. */
export const RASTER_GROUP = 4;

/** DEM 레벨 사다리의 최대값. build_elevation_grid.py 의 LEVEL_LADDER 와 짝이다. */
export const MAX_LEVEL = 6;

// 다수결 동률을 깨는 순서. 앞이 이긴다.
// 육지를 바다·범위밖보다 앞에 둔다 — 해안 블록이 8:8 로 갈릴 때 바다가 이기면
// 대륙이 타일 한 겹씩 깎여 나간다.
const TIE_ORDER: readonly number[] = [
  TERRAIN.PLAIN, TERRAIN.HILL, TERRAIN.BASIN, TERRAIN.PLATEAU, TERRAIN.DESERT,
  TERRAIN.MOUNTAIN, TERRAIN.LAKE, TERRAIN.RIVER, TERRAIN.SEA, TERRAIN.OUT_OF_SCOPE,
];

export interface TerrainTiles {
  cols: number;
  rows: number;
  /** row-major, 길이 cols*rows. 값은 TerrainCode. */
  code: Uint8Array;
}

/**
* 지형 코드 문자열 격자를 rasterGroup 배수로 줄인다.
*
* 단순 다수결이면 가는 지형이 통째로 사라진다. 실측(196년 판, 768×669):
* 강 3,421셀(0.67%) · 호수 1,079셀(0.21%) · 구릉 129셀(0.03%). 4×4 블록 다수결에서
* 이 셋은 한 타일도 남지 않는다. 그래서 희소한 순서로 우선권을 준다.
*
* 강은 한 셀만 걸려도 강으로 친다. 선형 지형이라 끊기면 강이 아니게 되기 때문이다.
* 이 규칙은 강 비중을 0.67% → 2.97% 로 부풀리지만, 원본 강 자체가 580조각으로
* 끊겨 있던 것이 38조각으로 이어진다. 화면에서 물줄기로 읽히는 쪽을 택했다.
* 호수·구릉은 면 지형이라 두 셀 이상을 요구해 잡티를 거른다.
*/
export function downsampleTerrain(
  sourceRows: readonly string[],
  group: number = RASTER_GROUP,
): TerrainTiles {
  if (sourceRows.length === 0) throw new Error('빈 지형 격자');
  const srcRows = sourceRows.length;
  const srcCols = sourceRows[0].length;
  const cols = Math.floor(srcCols / group);
  const rows = Math.floor(srcRows / group);
  const code = new Uint8Array(cols * rows);
  const count = new Int32Array(10);

  for (let r = 0; r < rows; r += 1) {
    for (let c = 0; c < cols; c += 1) {
      count.fill(0);
      for (let dr = 0; dr < group; dr += 1) {
        const line = sourceRows[r * group + dr];
        for (let dc = 0; dc < group; dc += 1) {
          const v = line.charCodeAt(c * group + dc) - 48;
          if (v >= 0 && v < 10) count[v] += 1;
        }
      }
      code[r * cols + c] = pickTile(count);
    }
  }
  return { cols, rows, code };
}

export interface SeatCells {
  /** 원본 셀 col. -1 은 좌표를 못 얻은 縣이다. */
  col: Int32Array;
  row: Int32Array;
}

/**
* 城 이 선 타일이 통째로 물이 된 것을 그 타일의 **육지 다수결**로 되돌린다.
* 제자리에서 code 를 고치고, 되돌린 타일 수와 손대지 못한 수를 돌려준다.
*
* 왜 필요한가. downsampleTerrain 은 강을 한 셀만 걸려도 강으로 친다(위 주석 참조).
* 그런데 漢 治所는 강가에 앉힌다 — 우연이 아니라 필연으로 겹친다. 실측(196년 판):
* 縣 治所 961 곳 중 **100 곳(10.4%)이 물 타일 위**에 섰고(강 87·호수 10·바다 3),
* 그 중 72 곳은 城 이 선 셀 자체가 뭍이다. 열여섯 셀 중 열둘이 뭍인데 타일이 통째로
* 강이 되어, 건업현(建業)·합비현·파양현 같은 城 이 물 위에 떠 보였다.
* 郡國 밖 세력 37 중에도 5 곳(주호·대마국·일대국·말로국·유구)이 그랬다 — 이쪽은
* 진짜 섬인데 4×4 다수결에서 섬이 통째로 지워진 것이다.
*
* 그래서 「城 이 서는 칸은 뭍이다」를 규칙으로 둔다. 물줄기는 城 자리에서 한 칸 끊기는데,
* 강안 도시가 실제로 그렇게 보인다. 바뀌는 것은 32,064 타일 중 100 장 남짓이다.
*
* 열여섯 셀이 전부 물이면 손대지 않는다. 그건 다운샘플 탓이 아니라 城 좌표 자체가
* 물에 있다는 뜻이고, 여기서 뭍으로 만들면 데이터의 결함을 화면이 덮어 버린다.
*
* 부르는 자리가 중요하다. downsampleTerrain·fillSeaEnclosedGaps **뒤**, flat/level
* **앞**이다. 그래야 되돌린 타일이 물 평탄화에서 빠지고, 코너 격자는 그 뒤에
* relaxCornerLattice 를 거치므로 1-립시츠 계약은 그대로 지켜진다.
*/
export function landUnderSeats(
  code: Uint8Array,
  cols: number,
  rows: number,
  sourceRows: readonly string[],
  seats: SeatCells,
  group: number = RASTER_GROUP,
): { restored: number; allWater: number } {
  const count = new Int32Array(10);
  let restored = 0;
  let allWater = 0;
  for (let n = 0; n < seats.col.length; n += 1) {
    const sc = seats.col[n];
    const sr = seats.row[n];
    if (sc < 0 || sr < 0) continue;
    const [c, r] = sourceCellToTile(sc, sr, group);
    if (c < 0 || c >= cols || r < 0 || r >= rows) continue;
    const i = r * cols + c;
    if (!isWater(code[i])) continue;
    count.fill(0);
    for (let dr = 0; dr < group; dr += 1) {
      const line = sourceRows[r * group + dr];
      if (line === undefined) continue;
      for (let dc = 0; dc < group; dc += 1) {
        const v = line.charCodeAt(c * group + dc) - 48;
        if (v >= 0 && v < 10) count[v] += 1;
      }
    }
    const land = pickLand(count);
    if (land === TERRAIN.OUT_OF_SCOPE) {
      allWater += 1;
      continue;
    }
    code[i] = land;
    restored += 1;
  }
  return { restored, allWater };
}

/** 물과 지도밖을 빼고 고르는 다수결. 동률은 TIE_ORDER 를 따른다. */
function pickLand(count: Int32Array): number {
  if (count[TERRAIN.HILL] >= 2) return TERRAIN.HILL;
  let best: number = TERRAIN.OUT_OF_SCOPE;
  let bestCount = 0;
  let bestRank = TIE_ORDER.length;
  for (let i = 0; i < TIE_ORDER.length; i += 1) {
    const t = TIE_ORDER[i];
    if (isWater(t) || t === TERRAIN.OUT_OF_SCOPE) continue;
    const n = count[t];
    if (n === 0) continue;
    if (n > bestCount || (n === bestCount && i < bestRank)) {
      best = t;
      bestCount = n;
      bestRank = i;
    }
  }
  return best;
}

function pickTile(count: Int32Array): number {
  if (count[TERRAIN.RIVER] >= 1) return TERRAIN.RIVER;
  if (count[TERRAIN.LAKE] >= 2) return TERRAIN.LAKE;
  if (count[TERRAIN.HILL] >= 2) return TERRAIN.HILL;

  // 범위밖은 투표에서 뺀다. 전부 범위밖일 때만 범위밖이다.
  let best: number = TERRAIN.OUT_OF_SCOPE;
  let bestCount = 0;
  let bestRank = TIE_ORDER.length;
  for (let i = 0; i < TIE_ORDER.length - 1; i += 1) {
    const t = TIE_ORDER[i];
    const n = count[t];
    if (n === 0) continue;
    if (n > bestCount || (n === bestCount && i < bestRank)) {
      best = t;
      bestCount = n;
      bestRank = i;
    }
  }
  return best;
}

/**
* DEM 레벨 PNG 의 픽셀에서 레벨 격자를 뽑는다.
* 회색조 8비트를 RGBA 로 디코드한 것이므로 R 채널만 읽으면 된다.
* 바다·범위밖은 0 으로 눌러 수면을 평평하게 만든다 — DEM 은 해저 지형까지 갖고 있어
* 그대로 쓰면 대륙붕이 계단으로 솟는다.
*/
export function levelsFromImageData(
  rgba: Uint8ClampedArray | Uint8Array,
  cols: number,
  rows: number,
  terrain?: TerrainTiles,
): Uint8Array {
  const level = new Uint8Array(cols * rows);
  for (let i = 0; i < cols * rows; i += 1) {
    const v = rgba[i * 4];
    level[i] = v > MAX_LEVEL ? MAX_LEVEL : v;
  }
  if (terrain) {
    if (terrain.cols !== cols || terrain.rows !== rows) {
      throw new Error(`격자 불일치: DEM ${cols}×${rows} vs 지형 ${terrain.cols}×${terrain.rows}`);
    }
    for (let i = 0; i < level.length; i += 1) {
      const t = terrain.code[i];
      if (t === TERRAIN.SEA || t === TERRAIN.OUT_OF_SCOPE) level[i] = 0;
    }
  }
  return level;
}

/**
* 타일 안 낙차를 1 단으로 눌러 준다. 제자리에서 고치고 같은 배열을 돌려준다.
*
* 왜 필요한가. 스프라이트가 실제로 그리는 코너 높이는 min(코너, base+1) 이다.
* 타일 안 낙차가 2 이상이면 높은 코너가 base 로 눌려 그려지는데, 같은 코너를 공유하는
* 옆 타일은 그 코너를 base+1 로 그린다. 두 실루엣이 어긋난 만큼 아무도 칠하지 않는
* 삼각형이 남고, 거기로 배경색(#0c0f0e)이 그대로 비친다 — 화면에서 봤던 검은 ∧ 쐐기다.
* 물도 같은 이유로 눌러야 한다. 매니페스트가 물에 마스크 0 한 장만 주므로 렌더러는
* 경사진 물 타일도 평평하게 그릴 수밖에 없다. 실측 어긋난 변 1,448 개
* (절벽 612 장 + 마스크≠0 인 물 1,001 장) → 0 개.
*
* 내리기만 하는 이유. 위아래로 당기면 물 제약과 뭍 제약이 서로를 되돌려 256 패스에도
* 안 멈췄다(절벽 489 장 잔존). 내리기만 하면 합이 매 패스 엄격히 줄어 반드시 멈춘다.
*
* 값은 잃는다. 실측 꼭짓점 6,750/32,424(20.8%) 하향, 평균 높이 1.343 → 0.891,
* 최고단 코너 682 → 76. 코너가 네 타일에 걸쳐 있으므로 "타일 안 낙차 ≤ 1" 은 곧
* "격자 전체가 1-립시츠" 이고, 급애는 정의상 그릴 수 없다. 이건 애셋 계약의 값이다
* (매니페스트 maxWithinTileHeightDifference: 1 · sharedCornersRequired: true).
* 그래서 3D 는 이 격자를 쓰지 않고 level 을 그대로 단으로 쓴다 — glTF 쪽은 윗면만
* 있는 타일 + 임의 높이로 늘리는 벽이라 이 제약이 없다.
*/
export function relaxCornerLattice(
  corner: Uint8Array, cols: number, rows: number, flat?: Uint8Array,
): Uint8Array {
  const w = cols + 1;
  // 내리기만 한다. 올렸다 내렸다 하면 물 제약과 뭍 제약이 서로를 되돌려 진동한다
  // (실측: 양방향으로 당기면 256 패스에도 안 멈추고 절벽 489 장이 남았다).
  // 내리기만 하면 합이 매 패스 엄격히 줄어드니 반드시 고정점에 닿는다.
  for (;;) {
    let changed = 0;
    for (let r = 0; r < rows; r += 1) {
      for (let c = 0; c < cols; c += 1) {
        const k0 = r * w + c;
        const k1 = r * w + c + 1;
        const k2 = (r + 1) * w + c + 1;
        const k3 = (r + 1) * w + c;
        const span = flat !== undefined && flat[r * cols + c] === 1 ? 0 : 1;
        const lo = Math.min(corner[k0], corner[k1], corner[k2], corner[k3]);
        const cap = lo + span;
        for (const k of [k0, k1, k2, k3]) {
          if (corner[k] > cap) {
            corner[k] = cap;
            changed += 1;
          }
        }
      }
    }
    if (changed === 0) break;
  }
  return corner;
}

/**
* 코너 높이 격자. (cols+1)×(rows+1), row-major.
*
* 코너 값 = 그 코너에 닿는 타일 레벨의 평균을 반올림한 것. 코너를 격자로 따로 두므로
* 이웃 타일은 정의상 같은 코너 값을 본다(iso2d 계약의 sharedCornersRequired).
* 마지막에 relaxCornerLattice 로 타일 안 낙차를 1 단까지 눌러, 스프라이트가 표현할 수
* 있는 범위 안으로 넣는다. flat 은 그대로 넘긴다(물은 낙차 0).
*/
export function buildCornerLattice(
  level: Uint8Array, cols: number, rows: number, flat?: Uint8Array,
): Uint8Array {
  const w = cols + 1;
  const h = rows + 1;
  const sum = new Float64Array(w * h);
  const num = new Int32Array(w * h);
  for (let r = 0; r < rows; r += 1) {
    for (let c = 0; c < cols; c += 1) {
      const v = level[r * cols + c];
      for (let dr = 0; dr < 2; dr += 1) {
        for (let dc = 0; dc < 2; dc += 1) {
          const k = (r + dr) * w + (c + dc);
          sum[k] += v;
          num[k] += 1;
        }
      }
    }
  }
  const corner = new Uint8Array(w * h);
  for (let k = 0; k < corner.length; k += 1) {
    corner[k] = num[k] === 0 ? 0 : Math.round(sum[k] / num[k]);
  }
  return relaxCornerLattice(corner, cols, rows, flat);
}

/**
* 바다에만 둘러싸인 「지도 밖」 덩어리를 바다로 바꾼다. 제자리에서 고친다.
*
* OUT_OF_SCOPE 는 그릴 스프라이트가 없어 배경색이 그대로 비친다. 마름모 가장자리라면
* 그게 맞지만, 남중국해 쪽 147 타일처럼 사방이 바다인 덩어리는 화면에서 바다에 뚫린
* 검은 구멍으로 읽힌다(배포본에서도 같은 증상이 지적됐다 — 지형 코드 #000000).
* 뭍에 닿은 덩어리는 손대지 않는다. 초원을 바다로 바꾸면 그건 거짓말이다.
*/
export function fillSeaEnclosedGaps(code: Uint8Array, cols: number, rows: number): Uint8Array {
  const seen = new Uint8Array(cols * rows);
  const stack: number[] = [];
  const group: number[] = [];
  for (let start = 0; start < code.length; start += 1) {
    if (seen[start] === 1 || code[start] !== TERRAIN.OUT_OF_SCOPE) continue;
    seen[start] = 1;
    stack.length = 0;
    group.length = 0;
    stack.push(start);
    let seaOnly = true;
    while (stack.length > 0) {
      const k = stack.pop() as number;
      group.push(k);
      const kc = k % cols;
      const kr = (k - kc) / cols;
      for (const [dc, dr] of [[1, 0], [-1, 0], [0, 1], [0, -1]] as const) {
        const nc = kc + dc;
        const nr = kr + dr;
        if (nc < 0 || nr < 0 || nc >= cols || nr >= rows) continue;
        const j = nr * cols + nc;
        if (code[j] === TERRAIN.OUT_OF_SCOPE) {
          if (seen[j] === 0) {
            seen[j] = 1;
            stack.push(j);
          }
        } else if (code[j] !== TERRAIN.SEA) {
          seaOnly = false;
        }
      }
    }
    if (seaOnly) for (const k of group) code[k] = TERRAIN.SEA;
  }
  return code;
}

/**
* 「지도 밖」 타일에 표고에서 뽑은 지형을 준다. code 를 제자리에서 고친다.
*
* 원본 지형 격자는 후한 판도만 분류해 놨고 그 밖은 전부 OUT_OF_SCOPE 다. 4×4 블록이
* 통째로 OUT_OF_SCOPE 일 때만 타일이 OUT_OF_SCOPE 가 되므로, 남은 6,395 타일에는
* 지형 정보가 한 조각도 없다. 그릴 스프라이트도 glTF 도 없어 화면에서는 배경색
* #0c0f0e 가 그대로 비쳤다 — 대륙 서쪽·북쪽이 잘려 나간 검은 벽으로 읽혔다.
*
* 지형 클래스는 지어내지 않는다. ETOPO1 표고는 그 타일에도 실측값이 있으므로
* 표고 사다리를 지형 등급으로 그대로 옮긴다. 실측 분포(6,395 타일):
* level 1 846 · 2 686 · 3 1,069 · 4 1,815 · 5 429 · 6 1,549 · 0 은 1 장.
* 서쪽 덩어리는 파미르·티베트라 4~6 이 몰려 산악으로, 북쪽은 3~4 라 고원으로 나온다.
*
* 사막은 만들지 않는다. 타클라마칸은 표고가 ~1,000 m 라 고원으로 나오는데, 이걸
* 사막으로 고치려면 표고 아닌 두 번째 출처가 있어야 한다. 없으므로 하지 않는다.
*
* 이렇게 채운 타일은 playable 0 으로 표시해 렌더러가 어둡게 눌러 그린다.
*/
export function terrainFromElevation(code: Uint8Array, level: Uint8Array): Uint8Array {
  for (let i = 0; i < code.length; i += 1) {
    if (code[i] !== TERRAIN.OUT_OF_SCOPE) continue;
    code[i] = OUT_OF_SCOPE_TERRAIN_BY_LEVEL[Math.min(level[i], MAX_LEVEL)];
  }
  return code;
}

/** 표고 단(0..6) → 지도 밖 타일에 줄 지형. build_elevation_grid.py 의 LEVEL_LADDER 와 짝이다. */
const OUT_OF_SCOPE_TERRAIN_BY_LEVEL: readonly number[] = [
  TERRAIN.SEA,      // 0 · 해수면 아래
  TERRAIN.PLAIN,    // 1 · ~200 m
  TERRAIN.HILL,     // 2 · ~500 m
  TERRAIN.PLATEAU,  // 3 · ~1,000 m
  TERRAIN.PLATEAU,  // 4 · ~2,000 m
  TERRAIN.MOUNTAIN, // 5 · ~3,500 m
  TERRAIN.MOUNTAIN, // 6 · 3,500 m 이상
];

export interface TileHeights {
  /** 타일 바닥 높이. 코너 넷의 최소값. */
  baseHeight: Uint8Array;
  /**
  * 경사 마스크 0..14. 비트 1=N 2=E 4=S 8=W 가 baseHeight+1 인 코너다.
  * baseHeight 가 최소값이라 코너 하나는 반드시 base 와 같으므로 15 는 나올 수 없다.
  * (iso2d 계약의 "mask 15 는 base+1 의 mask 0 으로 정규화한다" 규칙은 여기선 발동하지 않는다.)
  */
  mask: Uint8Array;
  /**
  * 코너 넷의 낙차가 2 이상이라 스프라이트 한 장으로 못 그리는 타일.
  * buildCornerLattice 를 거친 격자에서는 0 이어야 한다 — 0 이 아니면 실루엣이
  * 어긋나 배경이 새어 나온다. 렌더러가 아니라 검사용으로 남겨 둔 값이다.
  */
  cliff: Uint8Array;
  /** 코너 높이 넷을 그대로 담은 사본. 순서는 N, E, S, W. 3D 렌더러가 정점에 그대로 쓴다. */
  cornerNESW: Uint8Array;
}

/**
* 타일별 바닥 높이·경사 마스크를 낸다.
*
* 타일 (c,r) 의 코너 대응은 iso2d 계약의 N,E,S,W 순서를 따른다.
* 화면 좌표가 x=(c−r)·128, y=(c+r)·64 이므로 격자에서
*   N = P[r][c] · E = P[r][c+1] · S = P[r+1][c+1] · W = P[r+1][c] 이다.
*/
export function buildTileHeights(corner: Uint8Array, cols: number, rows: number): TileHeights {
  const w = cols + 1;
  const n = cols * rows;
  const baseHeight = new Uint8Array(n);
  const mask = new Uint8Array(n);
  const cliff = new Uint8Array(n);
  const cornerNESW = new Uint8Array(n * 4);

  for (let r = 0; r < rows; r += 1) {
    for (let c = 0; c < cols; c += 1) {
      const cn = corner[r * w + c];
      const ce = corner[r * w + c + 1];
      const cs = corner[(r + 1) * w + c + 1];
      const cw = corner[(r + 1) * w + c];
      const lo = Math.min(cn, ce, cs, cw);
      const hi = Math.max(cn, ce, cs, cw);
      const i = r * cols + c;
      baseHeight[i] = lo;
      mask[i] = (cn === lo + 1 ? 1 : 0)
        | (ce === lo + 1 ? 2 : 0)
        | (cs === lo + 1 ? 4 : 0)
        | (cw === lo + 1 ? 8 : 0);
      cliff[i] = hi - lo > 1 ? 1 : 0;
      cornerNESW[i * 4] = cn;
      cornerNESW[i * 4 + 1] = ce;
      cornerNESW[i * 4 + 2] = cs;
      cornerNESW[i * 4 + 3] = cw;
    }
  }
  return { baseHeight, mask, cliff, cornerNESW };
}

export interface IsoTileGrid extends TerrainTiles, TileHeights {
  level: Uint8Array;
  corner: Uint8Array;
  /**
  * 1 = 원본 지형 데이터가 있는 「지도 안」, 0 = 표고에서 유추한 「지도 밖」.
  * 0 인 타일은 플레이 대상이 아니다 — 소유도 城도 없고, 렌더러가 어둡게 눌러 그린다.
  * 지도 안 바다는 1 이지만 owner 가 -1 이라 이쪽도 플레이 대상은 아니다.
  * 이 값이 가리는 것은 「행정 구역 밖」이 아니라 「지도 밖」이다.
  */
  playable: Uint8Array;
}

/** 지형 문자열 + DEM 레벨 픽셀 → 렌더러가 바로 먹는 격자 한 덩어리. */
export function buildIsoTileGrid(
  sourceRows: readonly string[],
  demRgba: Uint8ClampedArray | Uint8Array,
  demCols: number,
  demRows: number,
  group: number = RASTER_GROUP,
  /** 縣 治所의 원본 셀. 주면 그 칸이 물로 칠해진 것을 뭍으로 되돌린다. */
  seats?: SeatCells,
): IsoTileGrid {
  const terrain = downsampleTerrain(sourceRows, group);
  // 바다에 뚫린 구멍부터 메운다. 이걸 먼저 해야 표고 유추가 바다 한가운데
  // 가짜 섬을 만들지 않는다(레벨 1 이면 평지가 돼 버린다).
  fillSeaEnclosedGaps(terrain.code, terrain.cols, terrain.rows);
  // 城 이 선 칸은 뭍이다. 구멍 메우기 뒤라야 방금 메운 바다를 다시 뚫지 않는다.
  if (seats) landUnderSeats(terrain.code, terrain.cols, terrain.rows, sourceRows, seats, group);
  // 지도 안팎은 지형을 채우기 **전에** 기록해 둔다. 채우고 나면 구분할 방법이 없다.
  const playable = new Uint8Array(terrain.code.length);
  for (let i = 0; i < playable.length; i += 1) {
    playable[i] = terrain.code[i] === TERRAIN.OUT_OF_SCOPE ? 0 : 1;
  }
  // 지형으로 누르기 전의 날 표고에서 지도 밖 타일의 지형 등급을 뽑는다.
  // 이 줄 뒤로 code 에 OUT_OF_SCOPE 는 없다.
  terrainFromElevation(terrain.code, levelsFromImageData(demRgba, demCols, demRows));
  const level = levelsFromImageData(demRgba, demCols, demRows, terrain);
  // 물 타일은 완전히 평평해야 한다 — 스프라이트가 마스크 0 한 장뿐이다.
  const flat = new Uint8Array(terrain.code.length);
  for (let i = 0; i < flat.length; i += 1) flat[i] = isWater(terrain.code[i]) ? 1 : 0;
  const corner = buildCornerLattice(level, terrain.cols, terrain.rows, flat);
  const heights = buildTileHeights(corner, terrain.cols, terrain.rows);
  return { ...terrain, level, corner, playable, ...heights };
}

// ── 화면 기하 ───────────────────────────────────────────────────────────────
// iso2d 계약: 평지 타일 발자국 256×128, 앵커 (128,96), 한 단차 = 화면 32px.

export const TILE_SCREEN_WIDTH = 256;
export const TILE_SCREEN_HEIGHT = 128;
export const STEP_SCREEN_PIXELS = 32;

/**
* 城 LOD 임계값. 타일 폭이 이 픽셀보다 좁아지면 治所(seat)만 남기고 縣은 감춘다.
*
* 밀도가 진짜 문제다. 후한 縣 1,138 곳이 25,521 타일에 들어 있고 한 타일에 최대 3 곳,
* 낙양 둘레 20×20 타일 안에만 138 곳이다. 다 그리면 지도가 아이콘 밭이 된다.
* 治所는 172 곳 — 郡 하나에 하나꼴이라 축소 상태에서도 행정 골격이 읽힌다.
* 확대해서 이 눈금을 넘으면 그 郡 안의 縣이 함께 나타난다.
*
* 값은 오브젝트 스프라이트(256px)가 화면에서 64px 아래로 내려가는 지점에 여유를 조금
* 둔 것이다. 2D 는 배율로, 3D 는 직교 카메라 span 으로 같은 눈금을 잰다.
*/
export const SEAT_ONLY_TILE_PIXELS = 76;

/** 타일 (c,r) 의 다이아몬드 중심 화면 좌표(높이 0 기준). */
export function tileToScreen(col: number, row: number): [number, number] {
  return [(col - row) * (TILE_SCREEN_WIDTH / 2), (col + row) * (TILE_SCREEN_HEIGHT / 2)];
}

/**
 * 화면 좌표(배율·이동을 이미 되돌린 값) → 그 점에서 실제로 보이는 타일.
 *
 * tileToScreen 의 역변환은 높이 0 을 가정한다. 그리기는 타일을 baseHeight 만큼 위로
 * 올리므로 그대로 쓰면 높은 곳에서 한두 칸 어긋나고, 클릭한 것과 다른 타일이 잡힌다.
 * 대신 높은 단부터 훑는다 — 화면 한 점을 「높이 h 의 지면」으로 되돌린 좌표가 실제로
 * 높이 h 인 타일이면 그게 답이다. h 가 클수록 (col+row) 이 커지고 그리기 순서상
 * (col+row) 이 큰 쪽이 앞에 있으므로, 위에서부터 찾으면 가려진 타일이 아니라
 * 눈에 보이는 타일이 잡힌다.
 *
 * 지도 밖(playable 0) 타일은 잡히지 않는다 — 그 위를 눌렀으면 null 이다.
 */
export function pickTileAtScreen(
  x: number,
  y: number,
  grid: Pick<IsoTileGrid, 'cols' | 'rows' | 'baseHeight' | 'playable'>,
): { col: number; row: number } | null {
  const { cols, rows, baseHeight, playable } = grid;
  let maxHeight = 0;
  for (let i = 0; i < baseHeight.length; i += 1) {
    if (baseHeight[i] > maxHeight) maxHeight = baseHeight[i];
  }
  const halfW = TILE_SCREEN_WIDTH / 2;
  const halfH = TILE_SCREEN_HEIGHT / 2;
  for (let h = maxHeight; h >= 0; h -= 1) {
    const yh = y + h * STEP_SCREEN_PIXELS;
    const col = Math.round((x / halfW + yh / halfH) / 2);
    const row = Math.round((yh / halfH - x / halfW) / 2);
    if (col < 0 || col >= cols || row < 0 || row >= rows) continue;
    const i = row * cols + col;
    if (baseHeight[i] !== h) continue;
    return playable[i] === 1 ? { col, row } : null;
  }
  return null;
}

// ── 3D 세계 좌표 ────────────────────────────────────────────────────────────
// iso3d 계약: 타일은 XZ 평면 1×1, Y 가 위. 아이소 다이아몬드는 카메라가 만든다.
//
// 2:1 픽셀 아이소와 눈금을 맞추려면 카메라 고도가 30°(sin=0.5)여야 하고,
// 그때 세로 한 칸이 화면에서 차지하는 길이는 y·cos30°다. 타일 폭은 화면에서 √2 이므로
//   step / width = 32 / 256 = 0.125  →  y·cos30° / √2 = 0.125  →  y ≈ 0.2041.
export const CAMERA_ELEVATION_RAD = Math.PI / 6;
export const CAMERA_AZIMUTH_RAD = Math.PI / 4;
export const HEIGHT_STEP_WORLD = (STEP_SCREEN_PIXELS / TILE_SCREEN_WIDTH) * Math.SQRT2
  / Math.cos(CAMERA_ELEVATION_RAD);

/** 타일 (c,r) 의 세계 중심. 격자 중앙이 원점에 오도록 옮긴다. */
export function tileToWorld(
  col: number,
  row: number,
  cols: number,
  rows: number,
): [number, number] {
  return [col - (cols - 1) / 2, row - (rows - 1) / 2];
}

// ── 소유 격자 ───────────────────────────────────────────────────────────────

/**
 * `owner` / `parentOwner` 의 런렝스 [[값, 개수], …] 를 펼친다.
 * -1 은 바다·비플레이 영역이다.
 */
export function expandRunLength(
  runs: readonly (readonly [number, number])[],
  length: number,
): Int32Array {
  const out = new Int32Array(length).fill(-1);
  let at = 0;
  for (const [value, count] of runs) {
    const end = Math.min(at + count, length);
    out.fill(value, at, end);
    at = end;
    if (at >= length) break;
  }
  return out;
}

/**
 * 원본 셀 단위 소유를 타일 격자로 줄인다. 블록 안에서 가장 많이 나온 값을 쓰되
 * -1(비소유)은 투표에서 뺀다 — 해안 타일이 바다 때문에 주인을 잃으면
 * 세력색이 해안선을 따라 한 겹씩 벗겨진다.
 * 동수면 값이 작은 쪽이 이긴다(결정적 결과를 위해서일 뿐, 의미는 없다).
 */
export function downsampleOwner(
  source: Int32Array,
  srcCols: number,
  cols: number,
  rows: number,
  group: number = RASTER_GROUP,
): Int32Array {
  const out = new Int32Array(cols * rows).fill(-1);
  const seen = new Map<number, number>();
  for (let r = 0; r < rows; r += 1) {
    for (let c = 0; c < cols; c += 1) {
      seen.clear();
      for (let dr = 0; dr < group; dr += 1) {
        const base = (r * group + dr) * srcCols + c * group;
        for (let dc = 0; dc < group; dc += 1) {
          const v = source[base + dc];
          if (v < 0) continue;
          seen.set(v, (seen.get(v) ?? 0) + 1);
        }
      }
      let best = -1;
      let bestCount = 0;
      for (const [value, count] of seen) {
        if (count > bestCount || (count === bestCount && value < best)) {
          best = value;
          bestCount = count;
        }
      }
      out[r * cols + c] = best;
    }
  }
  return out;
}

/**
 * 城 이 선 칸의 주인은 **그 城 자신**이다.
 *
 * downsampleOwner 는 4×4 원본 셀의 다수결로 칸 주인을 정한다. 그래서 縣 경계에 바짝 붙어
 * 선 治所는 제 칸을 이웃에게 넘긴다 — 원본 셀 해상도에서는 998/998 이 제 縣 땅 위에 서
 * 있는데(실측), 4 배로 줄이고 나면 **773 중 162 곳(21%)** 이 남의 색 위에 서 있었다.
 * 화면에서는 城 하나가 제 세력권 밖으로 한 칸 밀려 나간 것으로 보인다
 * (2026-09-10 사용자 보고: 「현이 한칸씩 밀려서 밖으로 나가는 케이스가 있네?」).
 *
 * 다수결 뒤에 治所 칸만 제 값으로 되돌린다. 새 정보를 지어내는 게 아니라 이미 원본 셀에
 * 적혀 있던 그 값이다 — 같은 이유로 `buildIsoTileGrid` 도 「城 이 선 칸은 뭍」을
 * 따로 못박는다(landUnderSeats).
 *
 * 한 칸에 서로 다른 縣의 治所가 둘 이상 드는 자리가 실측 54 칸(城 108 곳) 있다. 칸은
 * 하나뿐이라 색도 하나다 — **먼저 나온 治所가 이긴다**(= 낮은 province 인덱스).
 * downsampleOwner 의 동수 규칙과 같은 방향이고, 무엇보다 결과가 결정적이다.
 */
export function stampSeatOwners(
  tile: Int32Array,
  source: Int32Array,
  srcCols: number,
  cols: number,
  rows: number,
  seat: { col: Int32Array; row: Int32Array },
  group: number = RASTER_GROUP,
): Int32Array {
  const stamped = new Set<number>();
  for (let i = 0; i < seat.col.length; i += 1) {
    const sc = seat.col[i];
    const sr = seat.row[i];
    if (sc < 0 || sr < 0) continue;
    const [c, r] = sourceCellToTile(sc, sr, group);
    if (c < 0 || c >= cols || r < 0 || r >= rows) continue;
    const index = r * cols + c;
    if (stamped.has(index)) continue;
    const value = source[sr * srcCols + sc];
    if (value < 0) continue;
    tile[index] = value;
    stamped.add(index);
  }
  return tile;
}

/** 원본 셀 좌표(han-tiles col/row)를 타일 좌표로 옮긴다. */
export function sourceCellToTile(
  col: number,
  row: number,
  group: number = RASTER_GROUP,
): [number, number] {
  return [Math.floor(col / group), Math.floor(row / group)];
}
