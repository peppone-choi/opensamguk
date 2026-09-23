/** Three map reading levels. Thresholds are the visible width of one tile in CSS pixels. */
export type MapLod = 'JU' | 'COMMANDERY' | 'COUNTY';

export const JU_NAMES = [
  '사예', '예주', '기주', '연주', '서주', '청주', '형주',
  '양주', '익주', '량주', '병주', '유주', '교주',
] as const;
const KNOWN_REGIONS = new Set<string>([...JU_NAMES, '동이']);

export interface JuIndexResponse { sourceSha256: string; juByParent: string[] }

export function juUrlForTerrain(terrainUrl: string): string | null {
  const url = new URL(terrainUrl, 'http://local');
  if (!url.pathname.endsWith('/terrain')) return null;
  url.pathname = `${url.pathname.slice(0, -'/terrain'.length)}/ju`;
  return `${url.pathname}${url.search}`;
}

export function verifiedJuByParent(
  response: JuIndexResponse, terrainSha256: string | null, parentCount: number,
): string[] | null {
  if (!terrainSha256 || response.sourceSha256 !== terrainSha256
    || !Array.isArray(response.juByParent) || response.juByParent.length !== parentCount
    || response.juByParent.some((name) => !KNOWN_REGIONS.has(name))) return null;
  return response.juByParent;
}

export function mapLod(tileCssPixels: number): MapLod {
  if (tileCssPixels < 3.2) return 'JU';
  if (tileCssPixels < 11) return 'COMMANDERY';
  return 'COUNTY';
}

export interface JuEdge { x1: number; y1: number; x2: number; y2: number }
export interface JuLabel { name: string; col: number; row: number; cells: number }
export interface JuLayer { edges: readonly JuEdge[]; labels: readonly JuLabel[] }

/** Build 州 boundaries from the canonical 郡 index raster, never from geographic regions[]. */
export function buildJuLayer(
  commanderies: ArrayLike<number>, width: number, height: number,
  parents: readonly { ju?: string }[],
): JuLayer {
  if (commanderies.length !== width * height) throw new Error('郡 raster dimensions do not match');
  const byParent = parents.map((parent) => parent.ju && KNOWN_REGIONS.has(parent.ju) ? parent.ju : null);
  const identity = (i: number): string | null => {
    const parent = commanderies[i];
    if (parent < 0) return null;
    if (parent >= byParent.length || !byParent[parent]) throw new Error(`郡 ${parent} has no 州 assignment`);
    return byParent[parent];
  };
  const totals = new Map<string, { sumX: number; sumY: number; cells: number }>();
  const edges: JuEdge[] = [];
  for (let row = 0; row < height; row += 1) for (let col = 0; col < width; col += 1) {
    const index = row * width + col;
    const ju = identity(index);
    if (!ju) continue;
    const total = totals.get(ju) ?? { sumX: 0, sumY: 0, cells: 0 };
    total.sumX += col; total.sumY += row; total.cells += 1;
    totals.set(ju, total);
    if (col === 0 || identity(index - 1) !== ju) edges.push({ x1: col - 0.5, y1: row - 0.5, x2: col - 0.5, y2: row + 0.5 });
    if (row === 0 || identity(index - width) !== ju) edges.push({ x1: col - 0.5, y1: row - 0.5, x2: col + 0.5, y2: row - 0.5 });
    if (col === width - 1 || identity(index + 1) !== ju) edges.push({ x1: col + 0.5, y1: row - 0.5, x2: col + 0.5, y2: row + 0.5 });
    if (row === height - 1 || identity(index + width) !== ju) edges.push({ x1: col - 0.5, y1: row + 0.5, x2: col + 0.5, y2: row + 0.5 });
  }
  // Mean coordinates can fall in water or another 州. Place labels at the
  // nearest cell of their own 州 instead of inventing a centroid on the map.
  const best = new Map<string, { distance: number; col: number; row: number }>();
  for (let row = 0; row < height; row += 1) for (let col = 0; col < width; col += 1) {
    const ju = identity(row * width + col);
    if (!ju) continue;
    const total = totals.get(ju)!;
    const dx = col - total.sumX / total.cells;
    const dy = row - total.sumY / total.cells;
    const distance = dx * dx + dy * dy;
    const prior = best.get(ju);
    if (!prior || distance < prior.distance) best.set(ju, { distance, col, row });
  }
  const labels = [...totals].map(([name, total]) => ({ name, col: best.get(name)!.col,
    row: best.get(name)!.row, cells: total.cells }));
  labels.sort((a, b) => b.cells - a.cells || a.name.localeCompare(b.name));
  return { edges, labels };
}
