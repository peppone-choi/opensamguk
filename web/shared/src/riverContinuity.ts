// 타일 격자의 끊긴 물줄기를 잇는다 — buildIsoTileGrid 가 城 복원(landUnderSeats) 바로 뒤에 부른다.
//
// 왜 끊기나. 실측(han-world-v3, 384×334, 城·거점 1,263 곳): 뭍에 갇힌 물(강+호수)이
// 변으로 이어진 덩어리 183 개로 보인다. 원인이 셋이다.
//   1. 대각 계단. 원본 강은 선을 셀에 찍은 것이라 꼭짓점으로만 닿는 자리가 많다
//      (8-연결 23 덩어리 · 4-연결 135 덩어리). 아이소 타일은 대각 이웃과 점 하나로만
//      만나므로 비스듬히 흐르는 구간마다 끊겨 보인다.
//   2. 城. 「城 이 선 칸은 뭍」 규칙이 강 칸 81 곳을 지운다(135 → 183).
//   3. 원본의 틈. Natural Earth 50m 중심선이 하구 앞에서 멎거나(長江 — 하구 만입까지
//      두세 칸), 호수 앞에서 멎는다.
//
// 세 단계로 잇고, 뒤로 갈수록 규칙이 엄하다 — 앞 단계는 「원래 이어져 있던 것」을 되돌릴
// 뿐이지만 마지막 단계는 없던 물길을 지어내기 때문이다.
//   A. 대각 메우기 — 꼭짓점으로만 닿는 물 한 쌍 사이에 한 칸.
//   B. 城 우회 — 城 복원 **전에** 한 수계(8-연결)였던 덩어리끼리만, 城 칸을 피해 잇는다.
//   C. 틈 잇기 — 물줄기 **끝점**에서만, 흐르던 방향 앞쪽으로만, 능선을 넘지 않고.
//
// C 의 규칙은 전부 실측에서 나온 오접합을 막으려는 것이다.
//   · 끝점 제한: 金沙江·瀾滄江·怒江은 한두 칸 간격으로 나란히 흐른다. 가까운 물끼리 그냥
//     이으면 長江과 瀾滄江이 한 강이 된다. 나란한 강은 중간끼리 가깝지 끝이 가깝지 않다.
//   · 지도 밖에 닿은 끝점은 틈이 아니라 강이 판도를 나가는 자리다. 두 칸까지만 잇는다.
//   · 호수는 산을 넘어 잇지 않는다 — 靑海湖는 내륙호인데 黃河 상류와 네 칸 거리다.
//   · 능선: 길 위 표고 단이 출발 칸보다 높으면 분수령을 넘는 것이다. 다만 세 칸까지는
//     한 단을 봐준다 — DEM 단이 거칠어 강 칸(1)과 바로 옆 둔치(2)가 갈리는 일이 흔하다.
//
// React 도 DOM 도 쓰지 않는다. 입력 code 를 제자리에서 고친다.

const SEA = 0;
const MOUNTAIN = 2;
const RIVER = 3;
const LAKE = 4;
const OUT_OF_SCOPE = 9;

/** 새 물길 한 줄의 최대 길이(타일). 실측에서 8 을 넘겨도 더 이어지는 덩어리가 없었다. */
export const MAX_JOIN_TILES = 8;
/**
 * 城 우회(B)의 최대 길이. 같은 수계끼리만 잇는 단계라 오접합 걱정이 없어 넉넉히 준다.
 * 실측: 弘農 앞 黃河는 城 칸이 세로로 여섯 장 늘어서 있어 돌아가는 데 열 칸이 든다.
 */
export const MAX_BYPASS_TILES = 16;
/** 지도 밖에 닿은 끝점에서 허용하는 길이. */
const MAX_EDGE_JOIN_TILES = 2;
/** 이보다 작은 바다 조각은 떨어져 나온 만입으로 보고 본바다에 잇는다. */
const MAX_SEA_FRAGMENT_TILES = 16;
/** 이 길이까지는 표고 한 단 오르는 것을 봐준다. */
const RIDGE_SLACK_TILES = 3;

// 물길이 지나기 쉬운 순서. 평지·분지 1, 구릉 2, 사막·고원 3, 산 4.
const STEP_COST: readonly number[] = [0, 1, 4, 0, 0, 3, 3, 1, 2, 0];

const NEIGHBOURS_4: readonly (readonly [number, number])[] = [[0, 1], [0, -1], [1, 0], [-1, 0]];

function isWet(code: number): boolean {
  return code === SEA || code === RIVER || code === LAKE;
}

function isInland(code: number): boolean {
  return code === RIVER || code === LAKE;
}

/** 덩어리 라벨(1..count). 0 은 대상 아님. diagonal 이면 8-연결이다. */
export function labelComponents(
  code: Uint8Array,
  cols: number,
  rows: number,
  member: (code: number) => boolean,
  diagonal: boolean,
): { label: Int32Array; count: number } {
  const label = new Int32Array(cols * rows);
  const stack: number[] = [];
  let count = 0;
  for (let start = 0; start < code.length; start += 1) {
    if (label[start] !== 0 || !member(code[start])) continue;
    count += 1;
    label[start] = count;
    stack.push(start);
    while (stack.length > 0) {
      const k = stack.pop() as number;
      const kc = k % cols;
      const kr = (k - kc) / cols;
      for (let dr = -1; dr <= 1; dr += 1) {
        for (let dc = -1; dc <= 1; dc += 1) {
          if ((dr === 0 && dc === 0) || (!diagonal && dr !== 0 && dc !== 0)) continue;
          const nr = kr + dr;
          const nc = kc + dc;
          if (nr < 0 || nc < 0 || nr >= rows || nc >= cols) continue;
          const j = nr * cols + nc;
          if (label[j] !== 0 || !member(code[j])) continue;
          label[j] = count;
          stack.push(j);
        }
      }
    }
  }
  return { label, count };
}

/** 뭍에 갇힌 물(강+호수)의 8-연결 수계. landUnderSeats **전에** 떠 둔다. */
export function inlandWaterSystems(code: Uint8Array, cols: number, rows: number): Int32Array {
  return labelComponents(code, cols, rows, isInland, true).label;
}

export interface RiverContinuityInput {
  code: Uint8Array;
  cols: number;
  rows: number;
  /** 날 DEM 단(0..6). 바다를 0 으로 누르기 전 값이어야 강 칸의 표고가 살아 있다. */
  level: Uint8Array;
  /** 1 = 城 이 선 칸. 물길이 지나지 못한다. */
  dry: Uint8Array;
  /** 城 복원 전의 수계 라벨(inlandWaterSystems). */
  system: Int32Array;
}

export interface RiverContinuityResult {
  /** A — 대각 메우기로 놓은 칸. */
  diagonal: number;
  /** B — 城 우회로 이은 줄 수와 칸 수. */
  bypassJoins: number;
  bypassTiles: number;
  /** C — 틈 잇기로 이은 줄 수와 칸 수. */
  gapJoins: number;
  gapTiles: number;
}

function bridgeDiagonals({ code, cols, rows, level, dry }: RiverContinuityInput): number {
  let placed = 0;
  for (let r = 0; r + 1 < rows; r += 1) {
    for (let c = 0; c + 1 < cols; c += 1) {
      const a = r * cols + c;
      const quad = [[a, a + cols + 1, a + 1, a + cols], [a + 1, a + cols, a, a + cols + 1]];
      for (const [p, q, x, y] of quad) {
        const touching = (code[p] === RIVER && isWet(code[q])) || (code[q] === RIVER && isWet(code[p]));
        if (!touching || isWet(code[x]) || isWet(code[y])) continue;
        let pick = -1;
        for (const k of [x, y]) {
          if (dry[k] === 1 || code[k] === OUT_OF_SCOPE) continue;
          if (pick < 0
            || STEP_COST[code[k]] < STEP_COST[code[pick]]
            || (STEP_COST[code[k]] === STEP_COST[code[pick]] && level[k] < level[pick])) pick = k;
        }
        if (pick < 0) continue;
        code[pick] = RIVER;
        placed += 1;
      }
    }
  }
  return placed;
}

interface Join {
  path: number[];
  cost: number;
  start: number;
  target: number;
}

/** 비용이 작은 정수(한 줄 ≤ 16 칸 × 칸당 ≤ 4)라 양동이 큐로 충분하다. 넣은 순서대로 꺼내므로 결과가 실행마다 같다. */
class Frontier {
  private readonly buckets: number[][] = [];
  private lowest = 0;
  size = 0;

  push(cost: number, cell: number): void {
    while (this.buckets.length <= cost) this.buckets.push([]);
    this.buckets[cost].push(cell);
    if (cost < this.lowest) this.lowest = cost;
    this.size += 1;
  }

  pop(): [number, number] {
    while (this.buckets[this.lowest].length === 0) this.lowest += 1;
    this.size -= 1;
    return [this.lowest, this.buckets[this.lowest].pop() as number];
  }
}

/** 탐색 상태. 칸 수만큼의 타입 배열을 한 번 잡아 두고 세대 도장으로 재사용한다. */
class Scratch {
  readonly stamp: Int32Array;
  readonly cost: Int32Array;
  readonly steps: Uint8Array;
  readonly parent: Int32Array;
  /** 이 길이 떠난 물 칸. */
  readonly origin: Int32Array;
  readonly crossedMountain: Uint8Array;
  // 아래 넷은 출발 칸에만 적는다: 흐르던 방향(없으면 0,0)·길이 한도·표고 상한.
  readonly dirR: Int8Array;
  readonly dirC: Int8Array;
  readonly limit: Uint8Array;
  readonly cap: Uint8Array;
  generation = 0;

  constructor(size: number) {
    this.stamp = new Int32Array(size);
    this.cost = new Int32Array(size);
    this.steps = new Uint8Array(size);
    this.parent = new Int32Array(size);
    this.origin = new Int32Array(size);
    this.crossedMountain = new Uint8Array(size);
    this.dirR = new Int8Array(size);
    this.dirC = new Int8Array(size);
    this.limit = new Uint8Array(size);
    this.cap = new Uint8Array(size);
  }
}

/**
 * 덩어리 하나에서 가장 싼 이음 하나를 찾는다. 없으면 null.
 * strict 가 false 면 B(같은 수계끼리, 아무 칸에서나), true 면 C(끝점에서만, 규칙 전부).
 */
function findJoin(
  input: RiverContinuityInput,
  scratch: Scratch,
  label: Int32Array,
  cells: readonly number[],
  strict: boolean,
): Join | null {
  const { code, cols, rows, level, dry, system } = input;
  const own = label[cells[0]];
  let lakeOnly = true;
  for (const k of cells) if (code[k] === RIVER) { lakeOnly = false; break; }

  scratch.generation += 1;
  const generation = scratch.generation;
  const frontier = new Frontier();

  for (const k of cells) {
    if (!strict && system[k] === 0) continue;
    const kc = k % cols;
    const kr = (k - kc) / cols;
    let wetNeighbours = 0;
    let fromR = 0;
    let fromC = 0;
    let atEdge = false;
    for (let dr = -1; dr <= 1; dr += 1) {
      for (let dc = -1; dc <= 1; dc += 1) {
        if (dr === 0 && dc === 0) continue;
        const nr = kr + dr;
        const nc = kc + dc;
        if (nr < 0 || nc < 0 || nr >= rows || nc >= cols) { atEdge = true; continue; }
        const v = code[nr * cols + nc];
        if (v === OUT_OF_SCOPE) atEdge = true;
        if (dr !== 0 && dc !== 0) continue;
        if (isWet(v)) { wetNeighbours += 1; fromR = dr; fromC = dc; }
      }
    }
    if (wetNeighbours === 4 || (strict && wetNeighbours > 1)) continue;
    scratch.stamp[k] = generation;
    scratch.cost[k] = 0;
    scratch.steps[k] = 0;
    scratch.parent[k] = -1;
    scratch.origin[k] = k;
    scratch.crossedMountain[k] = 0;
    scratch.dirR[k] = wetNeighbours === 1 ? -fromR : 0;
    scratch.dirC[k] = wetNeighbours === 1 ? -fromC : 0;
    scratch.limit[k] = !strict ? MAX_BYPASS_TILES : atEdge ? MAX_EDGE_JOIN_TILES : MAX_JOIN_TILES;
    scratch.cap[k] = level[k];
    frontier.push(0, k);
  }

  while (frontier.size > 0) {
    const [cost, k] = frontier.pop();
    if (scratch.cost[k] < cost) continue;
    const origin = scratch.origin[k];
    const steps = scratch.steps[k];
    const kc = k % cols;
    const kr = (k - kc) / cols;
    for (const [dr, dc] of NEIGHBOURS_4) {
      const nr = kr + dr;
      const nc = kc + dc;
      if (nr < 0 || nc < 0 || nr >= rows || nc >= cols) continue;
      const j = nr * cols + nc;
      const v = code[j];
      if (isWet(v)) {
        if (label[j] === own || steps === 0) continue;
        if (!strict) {
          if (system[j] === 0 || system[j] !== system[origin]) continue;
        } else {
          if ((v === LAKE || lakeOnly) && scratch.crossedMountain[k] === 1) continue;
          // 흐르던 방향을 아는 끝점은 그 앞쪽 물만 본다. 바로 옆(내적 0)은 나란한 강이다.
          const oc = origin % cols;
          const or = (origin - oc) / cols;
          const ahead = (nr - or) * scratch.dirR[origin] + (nc - oc) * scratch.dirC[origin];
          if ((scratch.dirR[origin] !== 0 || scratch.dirC[origin] !== 0) && ahead <= 0) continue;
        }
        const path: number[] = [];
        for (let p = k; p !== origin; p = scratch.parent[p]) path.push(p);
        path.reverse();
        return { path, cost, start: origin, target: j };
      }
      if (v === OUT_OF_SCOPE || dry[j] === 1 || steps >= scratch.limit[origin]) continue;
      const cap = scratch.cap[origin];
      if (strict && level[j] > cap && !(steps < RIDGE_SLACK_TILES && level[j] <= cap + 1)) continue;
      const next = cost + STEP_COST[v];
      if (scratch.stamp[j] === generation && scratch.cost[j] <= next) continue;
      scratch.stamp[j] = generation;
      scratch.cost[j] = next;
      scratch.steps[j] = steps + 1;
      scratch.parent[j] = k;
      scratch.origin[j] = origin;
      scratch.crossedMountain[j] = scratch.crossedMountain[k] === 1 || v === MOUNTAIN ? 1 : 0;
      frontier.push(next, j);
    }
  }
  return null;
}

function joinComponents(input: RiverContinuityInput, scratch: Scratch, strict: boolean): { joins: number; tiles: number } {
  const { code, cols, rows } = input;
  // 라벨은 단계마다 한 번만 뜬다. 이은 뒤에는 작은 덩어리의 칸을 큰 쪽으로 옮겨 적는다 —
  // 바퀴마다 바다 4만 칸을 다시 칠하는 값이 탐색보다 비쌌다(실측 10 ms × 13 바퀴).
  const { label, count } = labelComponents(code, cols, rows, isWet, false);
  const cells: number[][] = Array.from({ length: count + 1 }, () => []);
  const inland = new Uint8Array(count + 1);
  for (let i = 0; i < code.length; i += 1) {
    if (label[i] === 0) continue;
    cells[label[i]].push(i);
    if (isInland(code[i])) inland[label[i]] = 1;
  }
  const merge = (a: number, b: number): number => {
    if (a === b) return a;
    const [keep, drop] = cells[a].length >= cells[b].length ? [a, b] : [b, a];
    for (const k of cells[drop]) { label[k] = keep; cells[keep].push(k); }
    cells[drop] = [];
    inland[keep] = inland[keep] | inland[drop];
    return keep;
  };

  let joins = 0;
  let tiles = 0;
  for (;;) {
    // 찾는 쪽은 강·호수가 든 덩어리 가운데 작은 것들이다. 바다에 이미 닿은 큰 덩어리는
    // 찾아지는 쪽이면 충분하고, 그 해안선 전체를 출발점으로 삼으면 헛돌기만 한다.
    let largest = 1;
    for (let id = 2; id <= count; id += 1) if (cells[id].length > cells[largest].length) largest = id;
    const found: Join[] = [];
    for (let id = 1; id <= count; id += 1) {
      if (cells[id].length === 0 || id === largest) continue;
      // 강 없는 덩어리는 작은 것만 찾는다 — 해안 앞에 한두 칸 떨어져 나온 만입 조각이다.
      if (inland[id] === 0 && (!strict || cells[id].length > MAX_SEA_FRAGMENT_TILES)) continue;
      const join = findJoin(input, scratch, label, cells[id], strict);
      if (join) found.push(join);
    }
    if (found.length === 0) break;
    found.sort((a, b) => a.path.length - b.path.length || a.cost - b.cost || a.start - b.start);
    // 한 바퀴에 덩어리마다 이음 하나. 이미 손댄 덩어리의 이음은 다음 바퀴에 다시 찾는다.
    const touched = new Set<number>();
    for (const join of found) {
      const a = label[join.start];
      const b = label[join.target];
      if (touched.has(a) || touched.has(b)) continue;
      let merged = merge(a, b);
      for (const k of join.path) {
        code[k] = RIVER;
        label[k] = merged;
        cells[merged].push(k);
      }
      inland[merged] = 1;
      // 새 물길이 제3의 덩어리 옆을 스치면 그것도 한 덩어리다.
      for (const k of join.path) {
        const kc = k % cols;
        const kr = (k - kc) / cols;
        for (const [dr, dc] of NEIGHBOURS_4) {
          const nr = kr + dr;
          const nc = kc + dc;
          if (nr < 0 || nc < 0 || nr >= rows || nc >= cols) continue;
          const other = label[nr * cols + nc];
          if (other === 0 || other === merged) continue;
          touched.add(other);
          merged = merge(merged, other);
        }
      }
      touched.add(a);
      touched.add(b);
      touched.add(merged);
      joins += 1;
      tiles += join.path.length;
    }
  }
  return { joins, tiles };
}

/** 끊긴 물줄기를 잇는다. code 를 제자리에서 고치고 단계별로 놓은 수를 돌려준다. */
export function connectRivers(input: RiverContinuityInput): RiverContinuityResult {
  let diagonal = bridgeDiagonals(input);
  const scratch = new Scratch(input.code.length);
  const bypass = joinComponents(input, scratch, false);
  const gap = joinComponents(input, scratch, true);
  // 새로 놓은 물길이 옆 물과 꼭짓점으로 닿는 자리가 생길 수 있다.
  diagonal += bridgeDiagonals(input);
  return {
    diagonal,
    bypassJoins: bypass.joins,
    bypassTiles: bypass.tiles,
    gapJoins: gap.joins,
    gapTiles: gap.tiles,
  };
}
