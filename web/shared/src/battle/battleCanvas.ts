// 전장 판을 브라우저 캔버스로: 키트 받기(한 번만), 판 그림 한 장, 바뀐 칸만 다시 칠하기, 작은 판.
import { boardRgba, composeBoard, parseBattleKit, redrawCells, type BattleKit, type ComposedBoard, type KitJson } from './battleBoard';

const kits = new Map<string, Promise<BattleKit>>();

async function fetchBytes(url: string): Promise<ArrayBuffer> {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`${url}: HTTP ${response.status}`);
  const bytes = await response.arrayBuffer();
  if (!url.endsWith('.gz')) return bytes;
  return new Response(new Response(bytes).body!.pipeThrough(new DecompressionStream('gzip'))).arrayBuffer();
}

const join = (base: string, file: string) => `${base.replace(/\/+$/, '')}/${file}`;

/** Fetches the kit once per base URL (about 0.3 MB). */
export function loadBattleKit(baseUrl: string): Promise<BattleKit> {
  const known = kits.get(baseUrl);
  if (known) return known;
  const load = (async () => {
    const [json, pieces, records, boards] = await Promise.all([
      fetchBytes(join(baseUrl, 'kit.json')).then((bytes) => JSON.parse(new TextDecoder().decode(bytes)) as KitJson),
      fetchBytes(join(baseUrl, 'pieces.bin.gz')),
      fetchBytes(join(baseUrl, 'records.bin')),
      fetchBytes(join(baseUrl, 'boards.bin.gz')),
    ]);
    return parseBattleKit(json, { pieces, records, boards });
  })();
  kits.set(baseUrl, load);
  load.catch(() => kits.delete(baseUrl));
  return load;
}

export interface BoardPicture {
  board: ComposedBoard;
  /** Original-size picture (board px); draw it scaled with drawImage while pinching — no re-assembly. */
  canvas: OffscreenCanvas;
}

export function boardPicture(kit: BattleKit, boardId: number, overrides?: ReadonlyMap<string, number>): BoardPicture {
  const board = composeBoard(kit, boardId, overrides);
  const canvas = new OffscreenCanvas(board.width, board.height);
  canvas.getContext('2d')!.putImageData(new ImageData(boardRgba(kit, board), board.width, board.height), 0, 0);
  return { board, canvas };
}

/** Structure changes (gate, wall, ladder record swaps): repaint only the covered rectangle. */
export function updateBoardPicture(kit: BattleKit, picture: BoardPicture, changes: ReadonlyMap<string, number>): void {
  const rect = redrawCells(kit, picture.board, changes);
  if (!rect || rect.width <= 0 || rect.height <= 0) return;
  picture.canvas.getContext('2d')!.putImageData(new ImageData(boardRgba(kit, picture.board, rect), rect.width, rect.height), rect.x, rect.y);
}

/** Small whole-board picture (smoothed) for the mini board. */
export function boardThumbnail(picture: BoardPicture, maxSize: number): OffscreenCanvas {
  const scale = Math.min(maxSize / picture.board.width, maxSize / picture.board.height);
  const thumb = new OffscreenCanvas(Math.max(1, Math.round(picture.board.width * scale)), Math.max(1, Math.round(picture.board.height * scale)));
  const ctx = thumb.getContext('2d')!;
  ctx.imageSmoothingEnabled = true;
  ctx.drawImage(picture.canvas, 0, 0, thumb.width, thumb.height);
  return thumb;
}
