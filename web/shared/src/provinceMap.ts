import { mapCityToTile, type IsoCityOverlay, type IsoSourceSize } from './HanMapCanvas';
import type { GridSize } from './isoMap';
import { isOwnedNationVisual, parseNationColor } from './nationVisual';

const PROVINCE_MASK = 0x0fff;
const PNG_SIGNATURE = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
const FORBIDDEN_IDENTITY_CHUNKS = new Set([
  'PLTE', 'tRNS', 'gAMA', 'cHRM', 'sRGB', 'iCCP', 'cICP', 'mDCV', 'cLLI', 'sBIT',
  'acTL', 'fcTL', 'fdAT',
]);

export interface ProvinceEdge {
  x1: number;
  y1: number;
  x2: number;
  y2: number;
}

export interface ProvinceIdentityMap {
  width: number;
  height: number;
  provinces: Int16Array;
  commanderies: Int16Array;
  provinceEdges: ProvinceEdge[];
  commanderyEdges: ProvinceEdge[];
}

export interface ProvinceColor {
  nationId: number;
  rgb: [number, number, number];
}

export interface ProvinceOwnershipBinding {
  colors: Map<number, ProvinceColor>;
  conflicts: number[];
}

interface ProvincePngShape {
  width: number;
  height: number;
}

function readUint32(bytes: Uint8Array, offset: number): number {
  return new DataView(bytes.buffer, bytes.byteOffset + offset, 4).getUint32(0);
}

function pngChunkName(bytes: Uint8Array, offset: number): string {
  return String.fromCharCode(bytes[offset], bytes[offset + 1], bytes[offset + 2], bytes[offset + 3]);
}

function validateProvincePng(bytes: Uint8Array): ProvincePngShape {
  if (bytes.length < PNG_SIGNATURE.length || PNG_SIGNATURE.some((value, index) => bytes[index] !== value)) {
    throw new Error('Province identity PNG signature is invalid');
  }

  let offset = PNG_SIGNATURE.length;
  let width = 0;
  let height = 0;
  let seenHeader = false;
  let seenImageData = false;
  let imageDataEnded = false;
  let seenEnd = false;
  while (offset < bytes.length) {
    if (bytes.length - offset < 12) throw new Error('Province identity PNG chunk is truncated');
    const length = readUint32(bytes, offset);
    const kind = pngChunkName(bytes, offset + 4);
    if (!/^[A-Za-z]{4}$/.test(kind)) throw new Error('Province identity PNG chunk name is invalid');
    if (length > bytes.length - offset - 12) throw new Error(`Province identity PNG ${kind} chunk is truncated`);
    const payloadOffset = offset + 8;
    const nextOffset = offset + 12 + length;

    if (!seenHeader && kind !== 'IHDR') throw new Error('Province identity PNG must begin with IHDR');
    if (FORBIDDEN_IDENTITY_CHUNKS.has(kind)) {
      throw new Error(`Province identity PNG must not contain ${kind}`);
    }
    if (kind === 'IHDR') {
      if (seenHeader || length !== 13) throw new Error('Province identity PNG has an invalid IHDR');
      width = readUint32(bytes, payloadOffset);
      height = readUint32(bytes, payloadOffset + 4);
      const bitDepth = bytes[payloadOffset + 8];
      const colorType = bytes[payloadOffset + 9];
      const compression = bytes[payloadOffset + 10];
      const filter = bytes[payloadOffset + 11];
      const interlace = bytes[payloadOffset + 12];
      if (width < 1 || height < 1) throw new Error('Province identity PNG dimensions must be positive');
      if (bitDepth !== 8) throw new Error('Province identity PNG must use 8-bit samples');
      if (colorType !== 2) throw new Error('Province identity PNG must use truecolor RGB without alpha or palette');
      if (compression !== 0 || filter !== 0) throw new Error('Province identity PNG uses an unsupported codec');
      if (interlace !== 0) throw new Error('Province identity PNG must not be interlaced');
      seenHeader = true;
    } else if (kind === 'IDAT') {
      if (imageDataEnded) throw new Error('Province identity PNG IDAT chunks must be consecutive');
      seenImageData = true;
    } else {
      if (seenImageData) imageDataEnded = true;
      if (kind === 'IEND') {
        if (length !== 0 || nextOffset !== bytes.length) throw new Error('Province identity PNG has an invalid IEND');
        seenEnd = true;
      } else if ((bytes[offset + 4] & 0x20) === 0) {
        throw new Error(`Province identity PNG has unsupported critical chunk ${kind}`);
      }
    }
    offset = nextOffset;
    if (seenEnd) break;
  }
  if (!seenHeader || !seenImageData || !seenEnd) throw new Error('Province identity PNG is incomplete');
  return { width, height };
}

async function createProvinceBitmap(blob: Blob): Promise<ImageBitmap> {
  try {
    return await createImageBitmap(blob, {
      colorSpaceConversion: 'none',
      premultiplyAlpha: 'none',
    });
  } catch (error) {
    const unsupported = error instanceof TypeError
      || (typeof DOMException !== 'undefined' && error instanceof DOMException && error.name === 'NotSupportedError');
    if (!unsupported) throw error;
    return createImageBitmap(blob);
  }
}

export async function loadProvinceIdentityMap(url: string): Promise<ProvinceIdentityMap> {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`province map fetch failed: ${response.status}`);
  const contentType = response.headers.get('content-type');
  if (contentType == null || !/^image\/png(?:\s*;.*)?$/i.test(contentType)) {
    throw new Error(`Province identity Content-Type must be image/png, received ${contentType ?? 'missing'}`);
  }
  const pngBytes = new Uint8Array(await response.arrayBuffer());
  const shape = validateProvincePng(pngBytes);
  const bitmap = await createProvinceBitmap(new Blob([pngBytes], { type: 'image/png' }));
  try {
    if (bitmap.width !== shape.width || bitmap.height !== shape.height) {
      throw new Error('Province identity decoded dimensions do not match IHDR');
    }
    const canvas = document.createElement('canvas');
    canvas.width = bitmap.width;
    canvas.height = bitmap.height;
    const context = canvas.getContext('2d', { willReadFrequently: true });
    if (!context) throw new Error('province decode context unavailable');
    context.drawImage(bitmap, 0, 0);
    const rgba = context.getImageData(0, 0, bitmap.width, bitmap.height).data;
    for (let offset = 3; offset < rgba.length; offset += 4) {
      if (rgba[offset] !== 255) throw new Error(`Province identity pixels must be opaque at pixel ${(offset - 3) / 4}`);
    }
    return decodeProvincePixels(
      rgba,
      bitmap.width,
      bitmap.height,
    );
  } finally {
    bitmap.close();
  }
}

function assertImageShape(rgba: Uint8ClampedArray, width: number, height: number) {
  if (!Number.isInteger(width) || !Number.isInteger(height) || width < 1 || height < 1) {
    throw new Error('Province identity dimensions must be positive integers');
  }
  if (rgba.length !== width * height * 4) {
    throw new Error('Province identity RGBA byte length does not match its dimensions');
  }
}

function addTransitionEdges(
  provinceEdges: ProvinceEdge[],
  commanderyEdges: ProvinceEdge[],
  fromProvince: number,
  fromCommandery: number,
  toProvince: number,
  toCommandery: number,
  edge: ProvinceEdge,
) {
  if (fromProvince < 0 || toProvince < 0) return;
  if (fromCommandery !== toCommandery) {
    provinceEdges.push(edge);
    commanderyEdges.push(edge);
  } else if (fromProvince !== toProvince) {
    provinceEdges.push(edge);
  }
}

export function decodeProvincePixels(
  rgba: Uint8ClampedArray,
  width: number,
  height: number,
): ProvinceIdentityMap {
  assertImageShape(rgba, width, height);

  const provinces = new Int16Array(width * height);
  const commanderies = new Int16Array(width * height);
  provinces.fill(-1);
  commanderies.fill(-1);

  for (let index = 0; index < provinces.length; index += 1) {
    const offset = index * 4;
    const code = (rgba[offset] << 16) | (rgba[offset + 1] << 8) | rgba[offset + 2];
    if (code === 0) continue;

    const province = (code & PROVINCE_MASK) - 1;
    const commandery = (code >>> 12) - 1;
    if (province < 0 || commandery < 0) {
      throw new Error(`Province identity hierarchy is incomplete at pixel ${index}`);
    }
    if (commandery > 254) throw new Error(`Province commandery identity is out of range at pixel ${index}`);
    provinces[index] = province;
    commanderies[index] = commandery;
  }

  const provinceEdges: ProvinceEdge[] = [];
  const commanderyEdges: ProvinceEdge[] = [];
  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      const index = y * width + x;
      if (x + 1 < width) {
        const next = index + 1;
        addTransitionEdges(
          provinceEdges,
          commanderyEdges,
          provinces[index],
          commanderies[index],
          provinces[next],
          commanderies[next],
          { x1: x + 0.5, y1: y - 0.5, x2: x + 0.5, y2: y + 0.5 },
        );
      }
      if (y + 1 < height) {
        const next = index + width;
        addTransitionEdges(
          provinceEdges,
          commanderyEdges,
          provinces[index],
          commanderies[index],
          provinces[next],
          commanderies[next],
          { x1: x - 0.5, y1: y + 0.5, x2: x + 0.5, y2: y + 0.5 },
        );
      }
    }
  }

  return { width, height, provinces, commanderies, provinceEdges, commanderyEdges };
}

export function bindProvinceOwnership(
  map: ProvinceIdentityMap,
  cities: readonly IsoCityOverlay[],
  grid: GridSize,
  source: IsoSourceSize,
): ProvinceOwnershipBinding {
  const colors = new Map<number, ProvinceColor>();
  const conflicts = new Set<number>();

  for (const city of cities) {
    if (!isOwnedNationVisual(city.nationId, city.nationColor)) continue;
    const rgb = parseNationColor(city.nationColor);

    const mapped = mapCityToTile(city, grid, source);
    const col = Math.round(mapped.col);
    const row = Math.round(mapped.row);
    if (!Number.isFinite(col) || !Number.isFinite(row) || col < 0 || row < 0 || col >= grid.cols || row >= grid.rows) continue;
    if (col >= map.width || row >= map.height) continue;

    const province = map.provinces[row * map.width + col];
    if (province < 0 || conflicts.has(province)) continue;

    const prior = colors.get(province);
    if (prior && prior.nationId !== city.nationId) {
      colors.delete(province);
      conflicts.add(province);
      continue;
    }
    if (!prior) colors.set(province, { nationId: city.nationId, rgb });
  }

  return { colors, conflicts: [...conflicts] };
}

export function composeProvincePixels(
  map: ProvinceIdentityMap,
  binding: ProvinceOwnershipBinding,
  alpha = 96,
): Uint8ClampedArray {
  const pixels = new Uint8ClampedArray(map.width * map.height * 4);
  const conflicts = new Set(binding.conflicts);
  for (let index = 0; index < map.provinces.length; index += 1) {
    const province = map.provinces[index];
    if (province < 0 || conflicts.has(province)) continue;
    const color = binding.colors.get(province);
    if (!color) continue;
    const offset = index * 4;
    pixels[offset] = color.rgb[0];
    pixels[offset + 1] = color.rgb[1];
    pixels[offset + 2] = color.rgb[2];
    pixels[offset + 3] = alpha;
  }
  return pixels;
}
