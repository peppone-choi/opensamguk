// 와룡전 전장 판 조립(전투 화면 · 리플레이 공용). 원작 조각 키트(opensamguk-images waryong/battle/kit)를
// 원작 규칙 그대로 쌓는다: 칸 (r, c)의 층 L 조각 → x = (r + c)·16, y = (r − c)·8 + side·8 + top − L·step.
// 층 0 전부 → 층 1 … 순서, 한 층 안에서는 (r − c, r + c) 작은 칸(뒤)부터. 색인 단계는 순수 TS라 노드에서 시험한다.

export interface BattleKitLayout {
  boards: number;
  side: number;
  layers: number;
  tilesets: number;
  piecesPerTileset: number;
  piece: [number, number];
  step: number;
  top: number;
  canvas: [number, number];
  empty: number;
}

export interface BattleKitBoard {
  id: number;
  tileset: number;
  layoutSha256: string;
  composedSha256: string;
  /**
   * Per-board classification hash (kit field name). It is what the server SNAPSHOT calls
   * `terrainInputSha256` — not the world terrain pin that the ticket root calls `terrainSha256`.
   */
  terrainSha256?: string;
}

export interface BattleKit {
  layout: BattleKitLayout;
  /** [tileset][piece][row][col] palette index, `layout.empty` = transparent. */
  pieces: Uint8Array;
  /** [tileset][record][8] = [layer count, piece id for layers 0..6]. */
  records: Uint8Array;
  /** [board][r][c] record id. */
  boards: Uint8Array;
  boardInfo: BattleKitBoard[];
  /** Per tileset, 256 terrain letters P/F/M/R/W — the server catalog's rule (absent in older kits). */
  recordClass?: string[];
  /** 16 day-palette colours. */
  palette: [number, number, number][];
}

export interface KitJson {
  schemaVersion: 1;
  artifactId: 'waryong-battle-kit';
  layout: BattleKitLayout;
  palette: { rgb: [number, number, number][] };
  boards: BattleKitBoard[];
  recordClass?: string[];
}

export function parseBattleKit(json: KitJson, files: { pieces: ArrayBuffer; records: ArrayBuffer; boards: ArrayBuffer }): BattleKit {
  if (json.schemaVersion !== 1 || json.artifactId !== 'waryong-battle-kit') throw new Error('battle kit: unsupported format');
  const { layout } = json;
  const [pw, ph] = layout.piece;
  const expect = (name: string, buffer: ArrayBuffer, bytes: number) => {
    if (buffer.byteLength !== bytes) throw new Error(`battle kit: ${name} has ${buffer.byteLength} bytes, expected ${bytes}`);
  };
  expect('pieces', files.pieces, layout.tilesets * layout.piecesPerTileset * pw * ph);
  expect('records', files.records, layout.tilesets * layout.piecesPerTileset * 8);
  expect('boards', files.boards, layout.boards * layout.side * layout.side);
  return {
    layout,
    pieces: new Uint8Array(files.pieces),
    records: new Uint8Array(files.records),
    boards: new Uint8Array(files.boards),
    boardInfo: json.boards,
    recordClass: json.recordClass,
    palette: json.palette.rgb,
  };
}

/** A composed board: palette indices plus, per pixel, which cell drew it (for taps). */
export interface ComposedBoard {
  boardId: number;
  width: number;
  height: number;
  indices: Uint8Array;
  /** r · side + c of the cell whose piece is on top at that pixel, −1 where nothing is drawn. */
  cellOf: Int16Array;
  /** Layer of that piece (with `cellOf`, its place in the original draw order — units are depth-tested against it). */
  layerOf: Uint8Array;
  /** Record id per cell after overrides. */
  grid: Uint8Array;
}

/** Board-pixel top-left of the piece for cell (r, c) at `layer`. */
export function pieceOrigin(layout: BattleKitLayout, r: number, c: number, layer: number): { x: number; y: number } {
  return { x: (r + c) * 16, y: (r - c) * 8 + layout.side * 8 + layout.top - layer * layout.step };
}

let drawOrderCache: { side: number; order: Int32Array } | null = null;
let drawRankCache: { side: number; rank: Int32Array } | null = null;

/** Cells back to front: sorted by (r − c, r + c) as the original list builder does. */
export function drawOrder(side: number): Int32Array {
  if (drawOrderCache?.side === side) return drawOrderCache.order;
  const cells: [number, number, number][] = [];
  for (let r = 0; r < side; r += 1) for (let c = 0; c < side; c += 1) cells.push([r - c, r + c, r * side + c]);
  cells.sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  const order = Int32Array.from(cells, (cell) => cell[2]);
  drawOrderCache = { side, order };
  return order;
}

/** Inverse of drawOrder: rank[r · side + c] = position of that cell within a layer pass. */
export function drawRank(side: number): Int32Array {
  if (drawRankCache?.side === side) return drawRankCache.rank;
  const order = drawOrder(side);
  const rank = new Int32Array(order.length);
  for (let i = 0; i < order.length; i += 1) rank[order[i]] = i;
  drawRankCache = { side, rank };
  return rank;
}

function boardGrid(kit: BattleKit, boardId: number, overrides?: ReadonlyMap<string, number>): Uint8Array {
  const { side } = kit.layout;
  const grid = kit.boards.slice(boardId * side * side, (boardId + 1) * side * side);
  overrides?.forEach((record, key) => {
    const [r, c] = key.split(',').map(Number);
    if (r >= 0 && r < side && c >= 0 && c < side) grid[r * side + c] = record;
  });
  return grid;
}

/**
 * Draws every layer of every cell whose pieces intersect `rect` (board px), clipped to it, in original order.
 * The whole board is the rect covering the canvas.
 */
function paint(kit: BattleKit, tileset: number, grid: Uint8Array, out: ComposedBoard, rect: { x0: number; y0: number; x1: number; y1: number }): void {
  const { layout } = kit;
  const { side, layers, empty } = layout;
  const [pw, ph] = layout.piece;
  const pieceBytes = pw * ph;
  const tilesetBase = tileset * layout.piecesPerTileset;
  const order = drawOrder(side);
  for (let y = rect.y0; y < rect.y1; y += 1) {
    out.indices.fill(empty, y * out.width + rect.x0, y * out.width + rect.x1);
    out.cellOf.fill(-1, y * out.width + rect.x0, y * out.width + rect.x1);
    out.layerOf.fill(0, y * out.width + rect.x0, y * out.width + rect.x1);
  }
  for (let layer = 0; layer < layers; layer += 1) {
    for (let i = 0; i < order.length; i += 1) {
      const cell = order[i];
      const r = (cell / side) | 0;
      const c = cell - r * side;
      const record = grid[cell];
      const pid = kit.records[((tilesetBase + record) * 8) + 1 + layer];
      if (pid === 0) continue;
      const ox = (r + c) * 16;
      const oy = (r - c) * 8 + side * 8 + layout.top - layer * layout.step;
      if (ox >= rect.x1 || oy >= rect.y1 || ox + pw <= rect.x0 || oy + ph <= rect.y0) continue;
      const base = (tilesetBase + pid) * pieceBytes;
      const yStart = Math.max(0, rect.y0 - oy);
      const yEnd = Math.min(ph, rect.y1 - oy);
      const xStart = Math.max(0, rect.x0 - ox);
      const xEnd = Math.min(pw, rect.x1 - ox);
      for (let py = yStart; py < yEnd; py += 1) {
        const row = (oy + py) * out.width + ox;
        const src = base + py * pw;
        for (let px = xStart; px < xEnd; px += 1) {
          const value = kit.pieces[src + px];
          if (value === empty) continue;
          out.indices[row + px] = value;
          out.cellOf[row + px] = cell;
          out.layerOf[row + px] = layer;
        }
      }
    }
  }
}

/** Assembles a whole board. `overrides` swaps record ids per 'r,c' (gates, walls, ladders). */
export function composeBoard(kit: BattleKit, boardId: number, overrides?: ReadonlyMap<string, number>): ComposedBoard {
  const info = kit.boardInfo[boardId];
  if (!info) throw new Error(`battle board ${boardId} is not in the kit`);
  const [width, height] = kit.layout.canvas;
  const board: ComposedBoard = {
    boardId,
    width,
    height,
    indices: new Uint8Array(width * height),
    cellOf: new Int16Array(width * height),
    layerOf: new Uint8Array(width * height),
    grid: boardGrid(kit, boardId, overrides),
  };
  paint(kit, info.tileset, board.grid, board, { x0: 0, y0: 0, x1: width, y1: height });
  return board;
}

/**
 * Applies record changes to a composed board in place and repaints only the area their pieces cover.
 * Returns the repainted rectangle (board px) so the caller can refresh just that part of its canvas.
 */
export function redrawCells(kit: BattleKit, board: ComposedBoard, changes: ReadonlyMap<string, number>): { x: number; y: number; width: number; height: number } | null {
  const { side, layers, step } = kit.layout;
  const [pw, ph] = kit.layout.piece;
  let x0 = Infinity;
  let y0 = Infinity;
  let x1 = -Infinity;
  let y1 = -Infinity;
  changes.forEach((record, key) => {
    const [r, c] = key.split(',').map(Number);
    if (!(r >= 0 && r < side && c >= 0 && c < side)) return;
    board.grid[r * side + c] = record;
    const origin = pieceOrigin(kit.layout, r, c, 0);
    x0 = Math.min(x0, origin.x);
    x1 = Math.max(x1, origin.x + pw);
    y0 = Math.min(y0, origin.y - (layers - 1) * step);
    y1 = Math.max(y1, origin.y + ph);
  });
  if (x0 === Infinity) return null;
  const rect = { x0: Math.max(0, x0), y0: Math.max(0, y0), x1: Math.min(board.width, x1), y1: Math.min(board.height, y1) };
  paint(kit, kit.boardInfo[board.boardId].tileset, board.grid, board, rect);
  return { x: rect.x0, y: rect.y0, width: rect.x1 - rect.x0, height: rect.y1 - rect.y0 };
}

/** RGBA pixels of a board (or a rectangle of it); transparent where nothing is drawn. */
export function boardRgba(kit: BattleKit, board: ComposedBoard, rect = { x: 0, y: 0, width: board.width, height: board.height }): Uint8ClampedArray {
  const out = new Uint8ClampedArray(rect.width * rect.height * 4);
  const { empty } = kit.layout;
  for (let y = 0; y < rect.height; y += 1) {
    for (let x = 0; x < rect.width; x += 1) {
      const value = board.indices[(rect.y + y) * board.width + rect.x + x];
      if (value === empty) continue;
      const at = (y * rect.width + x) * 4;
      const [red, green, blue] = kit.palette[value];
      out[at] = red;
      out[at + 1] = green;
      out[at + 2] = blue;
      out[at + 3] = 255;
    }
  }
  return out;
}

/**
 * The board's 4096 terrain letters in row-major order (r, then c) — equal to the server catalog's
 * terrainRows joined. Ticket comparison is not wired yet (C2 decides what the ticket pins).
 */
export function boardClassification(kit: BattleKit, boardId: number): string {
  if (!kit.recordClass) throw new Error('battle kit has no recordClass');
  const { side } = kit.layout;
  const classes = kit.recordClass[kit.boardInfo[boardId].tileset];
  let out = '';
  for (let i = boardId * side * side; i < (boardId + 1) * side * side; i += 1) out += classes[kit.boards[i]];
  return out;
}

/**
 * SHA-256 (lower-case hex) of the classification's ASCII bytes. Compare with the battle SNAPSHOT's
 * `terrainInputSha256` (계약판 K2-REF-08); the kit stores it per board as `boards[].terrainSha256`.
 */
export async function terrainInputSha256(kit: BattleKit, boardId: number): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(boardClassification(kit, boardId)));
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('');
}

// ── 보기 · 도우미(보는 창 기준). scale = 판 원본 px에 곱하는 배율, offset = 화면 CSS px.

export interface BoardView { scale: number; offsetX: number; offsetY: number }

export function fitView(board: { width: number; height: number }, viewport: { width: number; height: number }): BoardView {
  const scale = Math.min(viewport.width / board.width, viewport.height / board.height);
  return { scale, offsetX: (viewport.width - board.width * scale) / 2, offsetY: (viewport.height - board.height * scale) / 2 };
}

/** Default battle zoom: twice the original pixels (사용자 승인 2026-09-30, 분대 표기 B안). */
export const BATTLE_DEFAULT_SCALE = 2;

/** A view at `scale` with board point (x, y) (board px) in the viewport centre. */
export function centerView(point: { x: number; y: number }, viewport: { width: number; height: number }, scale = BATTLE_DEFAULT_SCALE): BoardView {
  return { scale, offsetX: viewport.width / 2 - point.x * scale, offsetY: viewport.height / 2 - point.y * scale };
}

/** Top layer that draws something for a record, 0 when only the ground piece exists. */
export function topLayer(kit: BattleKit, tileset: number, record: number): number {
  for (let layer = kit.layout.layers - 1; layer > 0; layer -= 1) {
    if (kit.records[(tileset * kit.layout.piecesPerTileset + record) * 8 + 1 + layer] !== 0) return layer;
  }
  return 0;
}

/**
 * Screen point of a cell. 'foot' = centre of the layer-0 diamond (the original draws units in the layer 0 · 1
 * slots whatever the height); 'top' = centre of the cell's highest piece (for marks on walls).
 */
export function cellToScreen(kit: BattleKit, board: ComposedBoard, r: number, c: number, view: BoardView, at: 'foot' | 'top' = 'foot'): { x: number; y: number } {
  const layer = at === 'top' ? topLayer(kit, kit.boardInfo[board.boardId].tileset, board.grid[r * kit.layout.side + c]) : 0;
  const origin = pieceOrigin(kit.layout, r, c, layer);
  return { x: (origin.x + 16) * view.scale + view.offsetX, y: (origin.y + 8) * view.scale + view.offsetY };
}

/** Diamond corners (top, right, bottom, left) of a cell at its foot or top layer. */
export function cellPolygon(kit: BattleKit, board: ComposedBoard, r: number, c: number, view: BoardView, at: 'foot' | 'top' = 'foot'): { x: number; y: number }[] {
  const centre = cellToScreen(kit, board, r, c, view, at);
  const hx = 16 * view.scale;
  const hy = 8 * view.scale;
  return [
    { x: centre.x, y: centre.y - hy },
    { x: centre.x + hx, y: centre.y },
    { x: centre.x, y: centre.y + hy },
    { x: centre.x - hx, y: centre.y },
  ];
}

/** Inverse of the layer-0 diamond: the ground cell under a screen point, or null outside the board. */
export function screenToCell(kit: BattleKit, x: number, y: number, view: BoardView): { r: number; c: number } | null {
  const { side, top } = kit.layout;
  const bx = (x - view.offsetX) / view.scale;
  const by = (y - view.offsetY) / view.scale;
  const p = (bx - 16) / 16; // = r + c
  const q = (by - side * 8 - top - 8) / 8; // = r − c
  const r = Math.round((p + q) / 2);
  const c = Math.round((p - q) / 2);
  return r >= 0 && r < side && c >= 0 && c < side ? { r, c } : null;
}

/** The cell whose drawn piece is under a screen point (walls in front win), or null on empty pixels. */
export function pickCell(kit: BattleKit, board: ComposedBoard, x: number, y: number, view: BoardView): { r: number; c: number } | null {
  const bx = Math.floor((x - view.offsetX) / view.scale);
  const by = Math.floor((y - view.offsetY) / view.scale);
  if (bx < 0 || by < 0 || bx >= board.width || by >= board.height) return null;
  const cell = board.cellOf[by * board.width + bx];
  if (cell < 0) return null;
  const { side } = kit.layout;
  return { r: (cell / side) | 0, c: cell % side };
}

/** The part of the board visible in a viewport, in board px (for the small-board rectangle). */
export function viewRect(view: BoardView, viewport: { width: number; height: number }): { x: number; y: number; width: number; height: number } {
  return { x: -view.offsetX / view.scale, y: -view.offsetY / view.scale, width: viewport.width / view.scale, height: viewport.height / view.scale };
}
