import { NO_TILE, type BakeChunkEntry, type BakeManifest, type CellRect, type ChunkData, type MapShape } from './types';

export function chunkKey(cx: number, cy: number): string {
  return `${cx}_${cy}`;
}

export interface ChunkCoord { cx: number; cy: number }

/**
 * Chunks overlapping `rect` expanded by `ring` chunks, clipped to the chunk grid,
 * nearest first (squared distance of chunk center to rect center, then cy, then cx).
 */
export function chunksForRect(
  rect: CellRect,
  chunkSize: number,
  shape: MapShape,
  ring = 1,
): ChunkCoord[] {
  if (rect.col1 <= rect.col0 || rect.row1 <= rect.row0) return [];
  const gridCols = Math.ceil(shape.cols / chunkSize);
  const gridRows = Math.ceil(shape.rows / chunkSize);
  const cx0 = Math.max(0, Math.floor(rect.col0 / chunkSize) - ring);
  const cy0 = Math.max(0, Math.floor(rect.row0 / chunkSize) - ring);
  const cx1 = Math.min(gridCols - 1, Math.floor((rect.col1 - 1) / chunkSize) + ring);
  const cy1 = Math.min(gridRows - 1, Math.floor((rect.row1 - 1) / chunkSize) + ring);
  const centerCol = (rect.col0 + rect.col1) / 2;
  const centerRow = (rect.row0 + rect.row1) / 2;
  const out: { cx: number; cy: number; d: number }[] = [];
  for (let cy = cy0; cy <= cy1; cy += 1) {
    for (let cx = cx0; cx <= cx1; cx += 1) {
      const dx = (cx + 0.5) * chunkSize - centerCol;
      const dy = (cy + 0.5) * chunkSize - centerRow;
      out.push({ cx, cy, d: dx * dx + dy * dy });
    }
  }
  out.sort((a, b) => a.d - b.d || a.cy - b.cy || a.cx - b.cx);
  return out.map(({ cx, cy }) => ({ cx, cy }));
}

const LITTLE_ENDIAN_HOST = new Uint8Array(new Uint16Array([1]).buffer)[0] === 1;

/** Read `count` little-endian u16 values starting at `byteOffset`, independent of host byte order. */
export function readUint16LE(buffer: ArrayBuffer, byteOffset: number, count: number): Uint16Array {
  const view = new DataView(buffer, byteOffset, count * 2);
  const out = new Uint16Array(count);
  for (let i = 0; i < count; i += 1) out[i] = view.getUint16(i * 2, true);
  return out;
}

function readPlane(buffer: ArrayBuffer, byteOffset: number, count: number): Uint16Array {
  // 리틀엔디언 기기는 복사 한 번으로 끝낸다(원본 버퍼와 별칭을 만들지 않는다).
  if (LITTLE_ENDIAN_HOST) return new Uint16Array(buffer.slice(byteOffset, byteOffset + count * 2));
  return readUint16LE(buffer, byteOffset, count);
}

/** Decode a raw chunk: tiles plane (u16 LE, chunkSize²) followed by provinces plane (u16 LE). */
export function decodeChunkBuffer(buffer: ArrayBuffer, chunkSize: number): ChunkData {
  if (!Number.isInteger(chunkSize) || chunkSize <= 0) {
    throw new Error(`chunk size must be a positive integer, got ${chunkSize}`);
  }
  const cells = chunkSize * chunkSize;
  const expected = cells * 4;
  if (buffer.byteLength !== expected) {
    throw new Error(
      `chunk buffer is ${buffer.byteLength} bytes; expected ${expected} `
      + `(tiles + provinces planes, ${chunkSize}x${chunkSize} u16 each)`,
    );
  }
  return {
    tiles: readPlane(buffer, 0, cells),
    provinces: readPlane(buffer, cells * 2, cells),
  };
}

/** Least-recently-used map. `keys()` lists least recent first, most recent last. */
export class LruCache<K, V> {
  private readonly map = new Map<K, V>();

  constructor(
    private readonly capacity: number,
    private readonly onEvict?: (key: K, value: V) => void,
  ) {
    if (!Number.isInteger(capacity) || capacity < 1) {
      throw new Error(`LRU capacity must be a positive integer, got ${capacity}`);
    }
  }

  get size(): number {
    return this.map.size;
  }

  get(key: K): V | undefined {
    if (!this.map.has(key)) return undefined;
    const value = this.map.get(key) as V;
    this.map.delete(key);
    this.map.set(key, value);
    return value;
  }

  /** Does not refresh recency. */
  has(key: K): boolean {
    return this.map.has(key);
  }

  set(key: K, value: V): void {
    this.map.delete(key);
    this.map.set(key, value);
    while (this.map.size > this.capacity) {
      const oldest = this.map.keys().next().value as K;
      const evicted = this.map.get(oldest) as V;
      this.map.delete(oldest);
      this.onEvict?.(oldest, evicted);
    }
  }

  /** Explicit removal does not call `onEvict`. */
  delete(key: K): boolean {
    return this.map.delete(key);
  }

  keys(): K[] {
    return [...this.map.keys()];
  }
}

export interface ChunkLoaderOptions {
  manifest: BakeManifest;
  fetchChunk: (entry: BakeChunkEntry) => Promise<ArrayBuffer>;
  capacity?: number;
  onEvict?: (key: string) => void;
}

export interface ChunkLoaderStats { fetches: number; cacheHits: number; inFlight: number }

export const DEFAULT_CHUNK_CAPACITY = 64;

/** Fetches, decodes and caches chunks. Uniform and missing chunks never fetch. */
export class ChunkLoader {
  private readonly chunkSize: number;
  private readonly fetchChunk: (entry: BakeChunkEntry) => Promise<ArrayBuffer>;
  private readonly entries = new Map<string, BakeChunkEntry>();
  private readonly cache: LruCache<string, ChunkData>;
  private readonly pending = new Map<string, Promise<ChunkData>>();
  private fetches = 0;
  private cacheHits = 0;

  constructor(options: ChunkLoaderOptions) {
    const { manifest, fetchChunk, capacity = DEFAULT_CHUNK_CAPACITY, onEvict } = options;
    if (!Number.isInteger(manifest.chunkSize) || manifest.chunkSize <= 0) {
      throw new Error(`manifest chunkSize must be a positive integer, got ${manifest.chunkSize}`);
    }
    this.chunkSize = manifest.chunkSize;
    this.fetchChunk = fetchChunk;
    for (const entry of manifest.chunks) {
      const key = chunkKey(entry.cx, entry.cy);
      if ((entry.file === undefined) === (entry.uniform === undefined)) {
        throw new Error(`chunk ${key} must have exactly one of file or uniform`);
      }
      if (this.entries.has(key)) throw new Error(`chunk ${key} is listed twice in the manifest`);
      this.entries.set(key, entry);
    }
    this.cache = new LruCache(capacity, onEvict ? (key) => onEvict(key) : undefined);
  }

  request(cx: number, cy: number): Promise<ChunkData> {
    const key = chunkKey(cx, cy);
    const cached = this.cache.get(key);
    if (cached) {
      this.cacheHits += 1;
      return Promise.resolve(cached);
    }
    const running = this.pending.get(key);
    if (running) return running;

    const entry = this.entries.get(key);
    if (!entry || entry.uniform) {
      // 매니페스트에 없는 조각은 그릴 것이 없다(NO_TILE). 한 가지 값 조각은 받지 않고 채운다.
      const data = this.filled(entry?.uniform?.tile ?? NO_TILE, entry?.uniform?.province ?? 0);
      this.cache.set(key, data);
      return Promise.resolve(data);
    }

    this.fetches += 1;
    const load = (async () => {
      const buffer = await this.fetchChunk(entry);
      const data = decodeChunkBuffer(buffer, this.chunkSize);
      this.cache.set(key, data);
      return data;
    })();
    // 실패한 시도는 캐시하지 않는다. 다음 요청이 다시 받는다.
    const shared = load.finally(() => {
      this.pending.delete(key);
    });
    this.pending.set(key, shared);
    return shared;
  }

  /** Cached chunk only; never fetches and does not count as a cache hit. */
  peek(cx: number, cy: number): ChunkData | undefined {
    return this.cache.get(chunkKey(cx, cy));
  }

  stats(): ChunkLoaderStats {
    return { fetches: this.fetches, cacheHits: this.cacheHits, inFlight: this.pending.size };
  }

  private filled(tile: number, province: number): ChunkData {
    const cells = this.chunkSize * this.chunkSize;
    return {
      tiles: new Uint16Array(cells).fill(tile),
      provinces: new Uint16Array(cells).fill(province),
    };
  }
}
