// 굽기 산출 · 키트를 받는 쪽. 같은 주소는 한 번만 받는다(모듈 단위 캐시).
import type { BakeManifest, ChunkData } from './types';

const inflight = new Map<string, Promise<ArrayBuffer>>();
const kept = new Map<string, Promise<ArrayBuffer>>();

export async function gunzip(buffer: ArrayBuffer): Promise<ArrayBuffer> {
  const stream = new Response(buffer).body!.pipeThrough(new DecompressionStream('gzip'));
  return new Response(stream).arrayBuffer();
}

/**
 * Concurrent requests for one URL share a fetch. `keep` keeps the bytes for the page lifetime
 * (small shared assets: manifest, kit, places); chunks are cached decoded by ChunkLoader instead.
 * Failures are never cached. `.gz` files are served as-is and inflated here.
 */
export function fetchBytes(url: string, options: { keep?: boolean } = {}): Promise<ArrayBuffer> {
  const known = kept.get(url) ?? inflight.get(url);
  if (known) return known;
  const load = fetch(url).then(async (response) => {
    if (!response.ok) throw new Error(`${url}: HTTP ${response.status}`);
    const bytes = await response.arrayBuffer();
    return url.endsWith('.gz') ? gunzip(bytes) : bytes;
  });
  (options.keep ? kept : inflight).set(url, load);
  load.then(
    () => { if (!options.keep) inflight.delete(url); },
    () => { kept.delete(url); inflight.delete(url); },
  );
  return load;
}

export async function fetchJson<T>(url: string): Promise<T> {
  return JSON.parse(new TextDecoder().decode(await fetchBytes(url, { keep: true }))) as T;
}

export function joinUrl(base: string, path: string): string {
  return `${base.replace(/\/+$/, '')}/${path.replace(/^\/+/, '')}`;
}

/** L2 overview: same two-plane layout as a chunk, cols × rows. */
export async function fetchOverview(baseUrl: string, manifest: BakeManifest): Promise<ChunkData> {
  const { file, cols, rows } = manifest.overview;
  const buffer = await fetchBytes(joinUrl(baseUrl, file), { keep: true });
  if (buffer.byteLength !== cols * rows * 4) throw new Error(`overview: ${buffer.byteLength} bytes, expected ${cols * rows * 4}`);
  const tiles = new Uint16Array(cols * rows);
  const provinces = new Uint16Array(cols * rows);
  const view = new DataView(buffer);
  for (let i = 0; i < cols * rows; i += 1) {
    tiles[i] = view.getUint16(i * 2, true);
    provinces[i] = view.getUint16((cols * rows + i) * 2, true);
  }
  return { tiles, provinces };
}

/**
 * Decodes an 8-bit grey PNG to its raw values (the kit index atlas). Colour conversion and
 * premultiplication are switched off so value v comes back as v.
 */
export async function decodeGreyPng(url: string): Promise<{ width: number; height: number; data: Uint8Array }> {
  const bitmap = await loadBitmap(url);
  const { width, height } = bitmap;
  const canvas = new OffscreenCanvas(width, height);
  const context = canvas.getContext('2d', { willReadFrequently: true });
  if (!context) throw new Error('2d context unavailable');
  context.drawImage(bitmap, 0, 0);
  const rgba = context.getImageData(0, 0, width, height).data;
  const data = new Uint8Array(width * height);
  for (let i = 0; i < data.length; i += 1) data[i] = rgba[i * 4];
  bitmap.close();
  return { width, height, data };
}

export async function loadBitmap(url: string): Promise<ImageBitmap> {
  const bytes = await fetchBytes(url, { keep: true });
  return createImageBitmap(new Blob([bytes], { type: 'image/png' }), {
    colorSpaceConversion: 'none',
    premultiplyAlpha: 'none',
  });
}
