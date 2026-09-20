import type { SurfaceBounds } from './mapSurfaceCache';

export interface ChunkView { width: number; height: number; panX: number; panY: number; scale: number; dpr: number }
export interface MapChunk {
  canvas: HTMLCanvasElement;
  /** Interior bounds; the bitmap has a one-pixel gutter on every side. */
  bounds: SurfaceBounds;
  rasterBounds: SurfaceBounds;
  ratio: number;
  scale: number;
  tiles: number;
  composite?: { revision: object; canvas: HTMLCanvasElement };
}
export interface ChunkFrame { surfaces: MapChunk[]; pending: boolean }
export type ChunkRenderer = (context: CanvasRenderingContext2D, bounds: SurfaceBounds, scale: number) => number;
const SIDE = 512;
const INTERIOR = SIDE - 2;
const LIMIT = 16; // Terrain16 MiB + optional composites16 MiB, released together on eviction.
const VISIBLE_LIMIT = 12; // Reserve four allocations for neighbouring chunks.
const intersects = (a: SurfaceBounds, b: SurfaceBounds) =>
  a.x < b.x + b.width && a.x + a.width > b.x && a.y < b.y + b.height && a.y + a.height > b.y;

/** A bounded LRU. Each call builds at most one chunk, always visible-first. */
export class MapChunkCache {
  private chunks = new Map<string, MapChunk>();
  constructor(private readonly createCanvas = () => document.createElement('canvas')) {}

  get(view: ChunkView, render: ChunkRenderer, settled = true): ChunkFrame | null {
    if (![view.width, view.height, view.scale, view.dpr].every(n => Number.isFinite(n) && n > 0)
      || !Number.isFinite(view.panX) || !Number.isFinite(view.panY)) return null;
    let density = Math.min(view.dpr, Math.sqrt(VISIBLE_LIMIT * INTERIOR ** 2 / view.width / view.height));
    // A translated viewport can straddle one additional chunk on each axis.
    while ((Math.ceil(view.width * density / INTERIOR) + 1)
      * (Math.ceil(view.height * density / INTERIOR) + 1) > VISIBLE_LIMIT) density *= 0.9;
    const ratio = view.scale * density;
    const size = INTERIOR / ratio;
    const visible = { x: -view.panX / view.scale, y: -view.panY / view.scale,
      width: view.width / view.scale, height: view.height / view.scale };
    const c0 = Math.floor(visible.x / size), r0 = Math.floor(visible.y / size);
    const c1 = Math.ceil((visible.x + visible.width) / size) - 1;
    const r1 = Math.ceil((visible.y + visible.height) / size) - 1;
    const generation = `${ratio}:${view.scale}`;
    const required: { key: string; col: number; row: number }[] = [];
    const neighbours: typeof required = [];
    for (let row = r0 - 1; row <= r1 + 1; row++) for (let col = c0 - 1; col <= c1 + 1; col++) {
      const item = { key: `${generation}:${col}:${row}`, col, row };
      (col >= c0 && col <= c1 && row >= r0 && row <= r1 ? required : neighbours).push(item);
    }
    const distance = ({ col, row }: typeof required[number]) => (col - (c0 + c1) / 2) ** 2 + (row - (r0 + r1) / 2) ** 2;
    required.sort((a,b) => distance(a) - distance(b));
    neighbours.sort((a,b) => distance(a) - distance(b));
    const targets = [...required, ...neighbours].slice(0, LIMIT);
    const protectedKeys = new Set(targets.map(t => t.key));
    const missing = targets.find(t => !this.chunks.has(t.key));
    if (missing && (settled || this.chunks.size === 0)) {
      if (this.chunks.size >= LIMIT) {
        const candidates = [...this.chunks].filter(([key]) => !protectedKeys.has(key));
        // Preserve visible fallback pixels until current-generation chunks cover them.
        // Include the chunk being built: it replaces the evicted pixels in this frame.
        const covered = ({ bounds: b }: MapChunk) => {
          const left = Math.max(b.x, visible.x), top = Math.max(b.y, visible.y);
          const right = Math.min(b.x + b.width, visible.x + visible.width);
          const bottom = Math.min(b.y + b.height, visible.y + visible.height);
          for (let row = Math.floor(top / size); row < Math.ceil(bottom / size); row++) {
            for (let col = Math.floor(left / size); col < Math.ceil(right / size); col++) {
              const key = `${generation}:${col}:${row}`;
              if (key !== missing.key && !this.chunks.has(key)) return false;
            }
          }
          return true;
        };
        const evicted = (candidates.find(([, chunk]) => !intersects(chunk.bounds, visible))
          ?? candidates.find(([, chunk]) => covered(chunk)) ?? candidates[0])?.[0];
        if (evicted !== undefined) this.remove(evicted);
      }
      const canvas = this.createCanvas();
      const context = canvas.getContext('2d');
      if (!context) return null;
      canvas.width = SIDE; canvas.height = SIDE;
      const bounds = { x: missing.col * size, y: missing.row * size, width: size, height: size };
      const rasterBounds = { x: bounds.x - 1 / ratio, y: bounds.y - 1 / ratio, width: SIDE / ratio, height: SIDE / ratio };
      context.fillStyle = '#0c0f0e';
      context.fillRect(0, 0, SIDE, SIDE);
      context.setTransform(ratio, 0, 0, ratio, -rasterBounds.x * ratio, -rasterBounds.y * ratio);
      context.imageSmoothingEnabled = view.scale < 1;
      const tiles = render(context, rasterBounds, view.scale);
      this.chunks.set(missing.key, { canvas, bounds, rasterBounds, ratio, scale: view.scale, tiles });
    }
    for (const {key} of targets) {
      const chunk = this.chunks.get(key);
      if (chunk) { this.chunks.delete(key); this.chunks.set(key, chunk); }
    }
    const complete = required.every(t => this.chunks.has(t.key));
    const old: MapChunk[] = [], current: MapChunk[] = [];
    for (const [key, chunk] of this.chunks) {
      if (!intersects(chunk.bounds, visible)) continue;
      if (key.startsWith(`${generation}:`)) current.push(chunk);
      else if (!complete) old.push(chunk);
    }
    return { surfaces: [...old, ...current], pending: settled && targets.some(t => !this.chunks.has(t.key)) };
  }

  /** Political changes rebuild only the composite; the terrain bitmap stays intact. */
  decorate(chunk: MapChunk, revision: object, render: ChunkRenderer): HTMLCanvasElement | null {
    if (chunk.composite?.revision === revision) return chunk.composite.canvas;
    const canvas = chunk.composite?.canvas ?? this.createCanvas();
    const context = canvas.getContext('2d');
    if (!context) return null;
    canvas.width = SIDE; canvas.height = SIDE;
    context.drawImage(chunk.canvas, 0, 0);
    const b = chunk.rasterBounds;
    context.setTransform(chunk.ratio, 0, 0, chunk.ratio, -b.x * chunk.ratio, -b.y * chunk.ratio);
    render(context, b, chunk.scale);
    chunk.composite = { revision, canvas };
    return canvas;
  }

  private remove(key: string): void {
    const chunk = this.chunks.get(key)!;
    chunk.canvas.width = 0; chunk.canvas.height = 0;
    if (chunk.composite) { chunk.composite.canvas.width = 0; chunk.composite.canvas.height = 0; }
    this.chunks.delete(key);
  }
  dispose(): void { for (const key of this.chunks.keys()) this.remove(key); }
}

/** Clip the interior but sample the gutter, avoiding filtering seams at chunk edges. */
export function drawMapChunk(context: CanvasRenderingContext2D, chunk: MapChunk, displayRatio: number, image = chunk.canvas, overlay?: (context: CanvasRenderingContext2D) => void): void {
  const b = chunk.bounds, r = chunk.rasterBounds;
  context.save();
  // Integer device-pixel clip edges avoid alpha seams between adjacent clips.
  // Both neighbours round their shared edge identically; the gutter supplies samples.
  const transform = context.getTransform();
  const x0 = (Math.round(b.x * transform.a + transform.e) - transform.e) / transform.a;
  const y0 = (Math.round(b.y * transform.d + transform.f) - transform.f) / transform.d;
  const x1 = (Math.round((b.x + b.width) * transform.a + transform.e) - transform.e) / transform.a;
  const y1 = (Math.round((b.y + b.height) * transform.d + transform.f) - transform.f) / transform.d;
  context.beginPath(); context.rect(x0, y0, x1 - x0, y1 - y0); context.clip();
  context.imageSmoothingEnabled = chunk.ratio !== displayRatio;
  context.drawImage(image, r.x, r.y, r.width, r.height);
  overlay?.(context);
  context.restore();
}
