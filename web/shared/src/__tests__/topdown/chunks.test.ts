import { describe, expect, it } from 'vitest';
import {
  ChunkLoader,
  LruCache,
  chunkKey,
  chunksForRect,
  decodeChunkBuffer,
  readUint16LE,
} from '../../map/topdown/chunks';
import { NO_TILE, type BakeChunkEntry, type BakeManifest } from '../../map/topdown/types';

const SIZE = 4;

function manifest(chunks: BakeChunkEntry[], chunkSize = SIZE): BakeManifest {
  return {
    schemaVersion: 1,
    artifactId: 'topdown-bake',
    bakeId: 'test',
    shape: { cols: 10, rows: 9 },
    chunkSize,
    kitId: 'kit',
    inputs: {},
    chunks,
    overview: { file: 'L2.bin.gz', sha256: '0', cols: 3, rows: 3, block: 4 },
    places: { file: 'places.json.gz', sha256: '0' },
  };
}

/** Byte-exact little-endian buffer: tiles then provinces. */
function chunkBytes(tiles: number[], provinces: number[]): ArrayBuffer {
  const bytes = new Uint8Array((tiles.length + provinces.length) * 2);
  [...tiles, ...provinces].forEach((value, i) => {
    bytes[i * 2] = value & 0xff;
    bytes[i * 2 + 1] = value >> 8;
  });
  return bytes.buffer;
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

const ramp = (n: number, base: number) => Array.from({ length: n }, (_, i) => base + i);

describe('조각 좌표', () => {
  it('열쇠는 cx_cy', () => {
    expect(chunkKey(3, 11)).toBe('3_11');
  });

  it('겹치는 조각 + 둘레, 격자 안으로 자르고 가까운 순', () => {
    const shape = { cols: 3072, rows: 2676 };
    // 조각 256, 격자 12×11. 조각 (1,1) 과 꼭 맞는 사각형이라 네 변 · 네 모서리가 각각 같은 거리 → cy, cx 순.
    const rect = { col0: 256, row0: 256, col1: 512, row1: 512 };
    expect(chunksForRect(rect, 256, shape, 1)).toEqual([
      { cx: 1, cy: 1 },
      { cx: 1, cy: 0 }, { cx: 0, cy: 1 }, { cx: 2, cy: 1 }, { cx: 1, cy: 2 },
      { cx: 0, cy: 0 }, { cx: 2, cy: 0 }, { cx: 0, cy: 2 }, { cx: 2, cy: 2 },
    ]);
    expect(chunksForRect(rect, 256, shape, 0)).toEqual([{ cx: 1, cy: 1 }]);
    // 가운데가 오른쪽 아래로 치우치면 그쪽 조각이 먼저다.
    const skewed = chunksForRect({ col0: 450, row0: 450, col1: 500, row1: 500 }, 256, shape, 1);
    // (2,1) 과 (1,2) 는 같은 거리 → cy 가 작은 (2,1) 먼저.
    expect(skewed.slice(0, 3)).toEqual([{ cx: 1, cy: 1 }, { cx: 2, cy: 1 }, { cx: 1, cy: 2 }]);
    expect(skewed[skewed.length - 1]).toEqual({ cx: 0, cy: 0 });
  });

  it('지도 가장자리에서는 격자 밖을 버린다', () => {
    const shape = { cols: 3072, rows: 2676 };
    const got = chunksForRect({ col0: 3000, row0: 2600, col1: 3072, row1: 2676 }, 256, shape, 1);
    const xs = new Set(got.map((c) => c.cx));
    const ys = new Set(got.map((c) => c.cy));
    expect([...xs].sort((a, b) => a - b)).toEqual([10, 11]);
    expect([...ys].sort((a, b) => a - b)).toEqual([9, 10]);
    expect(got[0]).toEqual({ cx: 11, cy: 10 });
  });

  it('빈 사각형은 조각이 없다', () => {
    expect(chunksForRect({ col0: 5, row0: 5, col1: 5, row1: 9 }, 256, { cols: 3072, rows: 2676 })).toEqual([]);
  });
});

describe('조각 풀기', () => {
  it('리틀엔디언 u16 두 평면을 순서대로 읽는다', () => {
    const tiles = [0x0102, 0xff00, 0x00ff, 0xabcd, ...ramp(12, 0x1000)];
    const provinces = [0, 1, 0x0200, 65535, ...ramp(12, 7)];
    const data = decodeChunkBuffer(chunkBytes(tiles, provinces), SIZE);
    expect(Array.from(data.tiles)).toEqual(tiles);
    expect(Array.from(data.provinces)).toEqual(provinces);
  });

  it('DataView 경로(빅엔디언 기기용)도 같은 값을 낸다', () => {
    const buffer = chunkBytes([0x0102, 0xbeef], [0x0304, 0x0001]);
    expect(Array.from(readUint16LE(buffer, 0, 2))).toEqual([0x0102, 0xbeef]);
    expect(Array.from(readUint16LE(buffer, 4, 2))).toEqual([0x0304, 0x0001]);
  });

  it('풀린 평면은 원본 버퍼와 별칭이 아니다', () => {
    const buffer = chunkBytes(ramp(16, 1), ramp(16, 100));
    const data = decodeChunkBuffer(buffer, SIZE);
    new Uint8Array(buffer).fill(0);
    expect(data.tiles[0]).toBe(1);
    expect(data.provinces[15]).toBe(115);
  });

  it('길이가 4·size² 가 아니면 알아볼 수 있는 오류로 멈춘다', () => {
    expect(() => decodeChunkBuffer(new ArrayBuffer(63), SIZE)).toThrow(/63 bytes; expected 64/);
    expect(() => decodeChunkBuffer(new ArrayBuffer(128), SIZE)).toThrow(/expected 64/);
  });
});

describe('LruCache', () => {
  it('가장 오래 안 쓴 것부터 내보내고, get 은 최근으로 올린다', () => {
    const evicted: string[] = [];
    const cache = new LruCache<string, number>(2, (key) => evicted.push(key));
    cache.set('a', 1);
    cache.set('b', 2);
    expect(cache.get('a')).toBe(1);
    cache.set('c', 3);
    expect(evicted).toEqual(['b']);
    expect(cache.keys()).toEqual(['a', 'c']);
    expect(cache.has('b')).toBe(false);
    expect(cache.size).toBe(2);
  });

  it('has 는 최근으로 올리지 않고, delete 는 onEvict 를 부르지 않는다', () => {
    const evicted: string[] = [];
    const cache = new LruCache<string, number>(2, (key) => evicted.push(key));
    cache.set('a', 1);
    cache.set('b', 2);
    expect(cache.has('a')).toBe(true);
    cache.set('c', 3);
    expect(evicted).toEqual(['a']);
    expect(cache.delete('b')).toBe(true);
    expect(evicted).toEqual(['a']);
    expect(cache.keys()).toEqual(['c']);
  });
});

describe('ChunkLoader', () => {
  const file = (cx: number, cy: number): BakeChunkEntry => ({ cx, cy, file: `grid/L0/${cx}_${cy}.bin.gz`, sha256: 'x' });

  it('같은 조각을 동시에 두 번 달라 해도 한 번만 받는다', async () => {
    const gate = deferred<ArrayBuffer>();
    const calls: BakeChunkEntry[] = [];
    const loader = new ChunkLoader({
      manifest: manifest([file(0, 0)]),
      fetchChunk: (entry) => { calls.push(entry); return gate.promise; },
    });
    const first = loader.request(0, 0);
    const second = loader.request(0, 0);
    expect(loader.stats()).toEqual({ fetches: 1, cacheHits: 0, inFlight: 1 });
    gate.resolve(chunkBytes(ramp(16, 1), ramp(16, 0)));
    const [a, b] = await Promise.all([first, second]);
    expect(a).toBe(b);
    expect(calls).toHaveLength(1);
    expect(loader.stats()).toEqual({ fetches: 1, cacheHits: 0, inFlight: 0 });
    expect(await loader.request(0, 0)).toBe(a);
    expect(loader.stats()).toEqual({ fetches: 1, cacheHits: 1, inFlight: 0 });
    expect(loader.peek(0, 0)).toBe(a);
  });

  it('한 가지 값 조각과 매니페스트에 없는 조각은 받지 않는다', async () => {
    let calls = 0;
    const loader = new ChunkLoader({
      manifest: manifest([{ cx: 1, cy: 0, uniform: { tile: 42, province: 7 } }]),
      fetchChunk: async () => { calls += 1; return new ArrayBuffer(0); },
    });
    const uniform = await loader.request(1, 0);
    expect(Array.from(new Set(uniform.tiles))).toEqual([42]);
    expect(Array.from(new Set(uniform.provinces))).toEqual([7]);
    expect(uniform.tiles).toHaveLength(SIZE * SIZE);
    const empty = await loader.request(2, 2);
    expect(Array.from(new Set(empty.tiles))).toEqual([NO_TILE]);
    expect(Array.from(new Set(empty.provinces))).toEqual([0]);
    expect(calls).toBe(0);
    expect(loader.stats().fetches).toBe(0);
    expect(loader.peek(1, 0)).toBe(uniform);
  });

  it('용량을 넘으면 가장 오래 안 쓴 조각을 내보낸다', async () => {
    const evicted: string[] = [];
    const loader = new ChunkLoader({
      manifest: manifest([file(0, 0), file(1, 0), file(2, 0)]),
      fetchChunk: async () => chunkBytes(ramp(16, 0), ramp(16, 0)),
      capacity: 2,
      onEvict: (key) => evicted.push(key),
    });
    await loader.request(0, 0);
    await loader.request(1, 0);
    await loader.request(0, 0);
    await loader.request(2, 0);
    expect(evicted).toEqual(['1_0']);
    expect(loader.peek(1, 0)).toBeUndefined();
    expect(loader.peek(0, 0)).toBeDefined();
    expect(loader.stats().fetches).toBe(3);
  });

  it('실패는 캐시하지 않는다: 같이 기다린 쪽은 같은 오류, 다음 요청은 다시 받는다', async () => {
    const gate = deferred<ArrayBuffer>();
    let calls = 0;
    const loader = new ChunkLoader({
      manifest: manifest([file(0, 0)]),
      fetchChunk: () => {
        calls += 1;
        return calls === 1 ? gate.promise : Promise.resolve(chunkBytes(ramp(16, 5), ramp(16, 0)));
      },
    });
    const first = loader.request(0, 0);
    const second = loader.request(0, 0);
    const boom = new Error('network down');
    gate.reject(boom);
    await expect(first).rejects.toBe(boom);
    await expect(second).rejects.toBe(boom);
    expect(loader.peek(0, 0)).toBeUndefined();
    expect(loader.stats().inFlight).toBe(0);
    const retry = await loader.request(0, 0);
    expect(retry.tiles[0]).toBe(5);
    expect(calls).toBe(2);
  });

  it('풀기 실패(잘린 바이트)도 실패로 돌려주고 캐시하지 않는다', async () => {
    const loader = new ChunkLoader({
      manifest: manifest([file(0, 0)]),
      fetchChunk: async () => new ArrayBuffer(10),
    });
    await expect(loader.request(0, 0)).rejects.toThrow(/expected 64/);
    expect(loader.peek(0, 0)).toBeUndefined();
  });

  it('파일과 한 가지 값을 둘 다 갖거나 둘 다 없는 항목, 겹친 항목은 거절한다', () => {
    const fetchChunk = async () => new ArrayBuffer(0);
    expect(() => new ChunkLoader({
      manifest: manifest([{ cx: 0, cy: 0, file: 'a', uniform: { tile: 1, province: 1 } }]), fetchChunk,
    })).toThrow(/exactly one/);
    expect(() => new ChunkLoader({ manifest: manifest([{ cx: 0, cy: 0 }]), fetchChunk })).toThrow(/exactly one/);
    expect(() => new ChunkLoader({ manifest: manifest([file(0, 0), file(0, 0)]), fetchChunk })).toThrow(/twice/);
  });
});
