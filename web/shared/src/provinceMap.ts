import { mapCityToTile, type IsoCityOverlay, type IsoSourceSize } from './HanMapCanvas';
import type { GridSize } from './isoMap';
import { isOwnedNationVisual, parseNationColor } from './nationVisual';

const PROVINCE_MASK = 0x0fff;
const PNG_SIGNATURE = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
const CANONICAL_PNG_CHUNKS = ['IHDR', 'IDAT', 'IEND'] as const;
const MAX_PROVINCE_PNG_AXIS = 4096;
const MAX_PROVINCE_PNG_CELLS = 4_194_304;
const MAX_PROVINCE_PNG_BYTES = 16 * 1024 * 1024;
const CRC32_TABLE = Uint32Array.from({ length: 256 }, (_, value) => {
  let crc = value;
  for (let bit = 0; bit < 8; bit += 1) {
    crc = (crc >>> 1) ^ ((crc & 1) === 1 ? 0xedb88320 : 0);
  }
  return crc >>> 0;
});

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
  cities?: Map<number, IsoCityOverlay>;
  directProvinces?: Set<number>;
}

export interface CountyAdministrativeIndex {
  commanderyByProvince: Int16Array;
  commanderyByName: ReadonlyMap<string, number>;
  administrativeSystemByProvince: readonly string[];
}

export interface ProvincePlacement {
  col: number;
  row: number;
  provinceId: number;
}

export interface ProvinceVisualAnchor extends ProvincePlacement {
  clearance: number;
}

export interface ProvinceRecordDto {
  id: string;
  displayName: string;
  nameCh: string;
  administrativeSystem: string;
  kind: string;
  parentRegionId: string;
  cityIndex: number | null;
  geometryBasis: string;
  confidence: string;
}

export interface ParentRegionRecordDto {
  id: string;
  displayName: string;
  nameCh: string;
  administrativeSystem: string;
  aliases?: readonly string[];
}

const SYSTEM_LABELS: Readonly<Record<string, string>> = {
  AILAO: '애뢰', BAEKJE: '백제', BUYEO: '부여', BYEONHAN: '변한', DI: '저',
  GOGURYEO: '고구려', JINHAN: '진한', JUHO: '주호', MAHAN: '마한',
  OKJEO: '옥저', QIANG: '강', SHANYUE: '산월', TSUSHIMA: '대마국',
  USAN: '우산국', WA: '왜', WUHUAN: '오환', XIANBEI: '선비',
  XIONGNU: '남흉노', YE: '예', YILOU: '읍루', YIZHOU: '이주',
};

export function formatProvinceTooltip(
  province: ProvinceRecordDto,
  parent?: ParentRegionRecordDto,
): string {
  if (province.administrativeSystem === 'HAN_COMMANDERY') {
    return [parent?.displayName, province.displayName].filter(Boolean).join(' ');
  }
  const system = SYSTEM_LABELS[province.administrativeSystem]
    ?? (parent?.administrativeSystem === province.administrativeSystem ? parent.displayName : undefined)
    ?? parent?.displayName
    ?? '외부 지역';
  return `${system} · ${province.displayName}`;
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

function crc32(bytes: Uint8Array, offset: number, length: number): number {
  let crc = 0xffffffff;
  for (let index = offset; index < offset + length; index += 1) {
    crc = CRC32_TABLE[(crc ^ bytes[index]) & 0xff] ^ (crc >>> 8);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function validateProvincePng(bytes: Uint8Array): ProvincePngShape {
  if (bytes.length < PNG_SIGNATURE.length || PNG_SIGNATURE.some((value, index) => bytes[index] !== value)) {
    throw new Error('Province identity PNG signature is invalid');
  }

  let offset = PNG_SIGNATURE.length;
  const chunks: Array<{ kind: string; length: number; payloadOffset: number }> = [];
  while (offset < bytes.length) {
    if (bytes.length - offset < 12) throw new Error('Province identity PNG chunk is truncated');
    const length = readUint32(bytes, offset);
    const kind = pngChunkName(bytes, offset + 4);
    if (!/^[A-Za-z]{4}$/.test(kind)) throw new Error('Province identity PNG chunk name is invalid');
    if (length > bytes.length - offset - 12) throw new Error(`Province identity PNG ${kind} chunk is truncated`);
    const payloadOffset = offset + 8;
    const nextOffset = offset + 12 + length;
    const expectedCrc = readUint32(bytes, payloadOffset + length);
    const actualCrc = crc32(bytes, offset + 4, length + 4);
    if (actualCrc !== expectedCrc) throw new Error(`Province identity PNG ${kind} CRC is invalid`);

    chunks.push({ kind, length, payloadOffset });
    offset = nextOffset;
  }

  if (chunks.length !== CANONICAL_PNG_CHUNKS.length
    || chunks.some((chunk, index) => chunk.kind !== CANONICAL_PNG_CHUNKS[index])) {
    throw new Error('Province identity PNG must contain exactly IHDR, IDAT, IEND');
  }

  const [header, imageData, end] = chunks;
  if (header.length !== 13) throw new Error('Province identity PNG has an invalid IHDR');
  if (imageData.length < 1) throw new Error('Province identity PNG has an empty IDAT');
  if (end.length !== 0) throw new Error('Province identity PNG has an invalid IEND');

  const width = readUint32(bytes, header.payloadOffset);
  const height = readUint32(bytes, header.payloadOffset + 4);
  const bitDepth = bytes[header.payloadOffset + 8];
  const colorType = bytes[header.payloadOffset + 9];
  const compression = bytes[header.payloadOffset + 10];
  const filter = bytes[header.payloadOffset + 11];
  const interlace = bytes[header.payloadOffset + 12];
  if (width < 1 || height < 1) throw new Error('Province identity PNG dimensions must be positive');
  if (width > MAX_PROVINCE_PNG_AXIS || height > MAX_PROVINCE_PNG_AXIS) {
    throw new Error(`Province identity PNG axis exceeds ${MAX_PROVINCE_PNG_AXIS}`);
  }
  if (width * height > MAX_PROVINCE_PNG_CELLS) {
    throw new Error(`Province identity PNG cell count exceeds ${MAX_PROVINCE_PNG_CELLS}`);
  }
  if (bitDepth !== 8) throw new Error('Province identity PNG must use 8-bit samples');
  if (colorType !== 2) throw new Error('Province identity PNG must use truecolor RGB without alpha or palette');
  if (compression !== 0 || filter !== 0) throw new Error('Province identity PNG uses an unsupported codec');
  if (interlace !== 0) throw new Error('Province identity PNG must not be interlaced');
  return { width, height };
}

async function createProvinceBitmap(blob: Blob): Promise<ImageBitmap> {
  try {
    return await createImageBitmap(blob, {
      colorSpaceConversion: 'none',
      imageOrientation: 'none',
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
  const contentLength = response.headers.get('content-length');
  if (contentLength != null) {
    if (!/^\d+$/.test(contentLength)) throw new Error('Province identity Content-Length is invalid');
    const declaredLength = Number(contentLength);
    if (!Number.isSafeInteger(declaredLength)) throw new Error('Province identity Content-Length is invalid');
    if (declaredLength > MAX_PROVINCE_PNG_BYTES) {
      throw new Error(`Province identity Content-Length exceeds ${MAX_PROVINCE_PNG_BYTES} byte limit`);
    }
  }
  const pngBuffer = await response.arrayBuffer();
  if (pngBuffer.byteLength > MAX_PROVINCE_PNG_BYTES) {
    throw new Error(`Province identity PNG exceeds ${MAX_PROVINCE_PNG_BYTES} byte limit`);
  }
  const pngBytes = new Uint8Array(pngBuffer);
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

interface ProvinceGeometry {
  colTotal: number;
  rowTotal: number;
  cells: number;
}

interface CitySample {
  city: IsoCityOverlay;
  col: number;
  row: number;
  province: number;
  commandery: number;
}

export function buildCountyAdministrativeIndex(
  map: ProvinceIdentityMap,
  counties: readonly { col: number; row: number }[],
  commanderies: readonly { name: string }[],
): CountyAdministrativeIndex {
  const commanderyByProvince = new Int16Array(counties.length);
  commanderyByProvince.fill(-1);
  for (let province = 0; province < counties.length; province += 1) {
    const county = counties[province];
    if (!Number.isInteger(county.col) || !Number.isInteger(county.row)
      || county.col < 0 || county.row < 0 || county.col >= map.width || county.row >= map.height) continue;
    const index = county.row * map.width + county.col;
    if (map.provinces[index] === province) commanderyByProvince[province] = map.commanderies[index];
  }
  return {
    commanderyByProvince,
    commanderyByName: new Map(commanderies.map((commandery, index) => [commandery.name, index])),
    administrativeSystemByProvince: Array.from({ length: counties.length }, () => 'HAN_COMMANDERY'),
  };
}

export function buildProvinceAdministrativeIndex(
  map: ProvinceIdentityMap,
  provinces: readonly ProvinceRecordDto[],
  parentRegions: readonly ParentRegionRecordDto[],
): CountyAdministrativeIndex {
  const parentById = new Map(parentRegions.map((parent, index) => [parent.id, index]));
  const commanderyByProvince = new Int16Array(provinces.length);
  const administrativeSystemByProvince = new Array<string>(provinces.length);
  commanderyByProvince.fill(-1);
  provinces.forEach((province, index) => {
    const parent = parentById.get(province.parentRegionId);
    if (parent != null) commanderyByProvince[index] = parent;
    administrativeSystemByProvince[index] = province.administrativeSystem;
  });
  // The identity PNG remains authoritative.  A DTO hierarchy mismatch must not
  // silently redirect hover/ownership to a different parent.
  for (let cell = 0; cell < map.provinces.length; cell += 1) {
    const province = map.provinces[cell];
    if (province < 0) continue;
    if (province >= provinces.length || commanderyByProvince[province] !== map.commanderies[cell]) {
      throw new Error(`Province parent hierarchy mismatch at pixel ${cell}`);
    }
  }
  const commanderyByName = new Map<string, number>();
  parentRegions.forEach((parent, index) => {
    commanderyByName.set(parent.displayName, index);
    parent.aliases?.forEach((alias) => commanderyByName.set(alias, index));
  });
  return {
    commanderyByProvince,
    commanderyByName,
    administrativeSystemByProvince,
  };
}

function nearestSample(samples: readonly CitySample[], centerCol: number, centerRow: number): CitySample | undefined {
  let selected: CitySample | undefined;
  let selectedDistance = Number.POSITIVE_INFINITY;
  let selectedId = Number.MAX_SAFE_INTEGER;
  for (const sample of samples) {
    const distance = (sample.col - centerCol) ** 2 + (sample.row - centerRow) ** 2;
    const id = Number.isSafeInteger(sample.city.id) ? sample.city.id : Number.MAX_SAFE_INTEGER;
    if (distance < selectedDistance || (distance === selectedDistance && id < selectedId)) {
      selected = sample;
      selectedDistance = distance;
      selectedId = id;
    }
  }
  return selected;
}

const VISUAL_ANCHOR_NEIGHBORS = [
  [-1, -1], [0, -1], [1, -1],
  [-1, 0], [1, 0],
  [-1, 1], [0, 1], [1, 1],
] as const;

/**
 * Find a deterministic deep-interior display point for each province.
 * These points are presentation-only and never replace the canonical seat used
 * by ownership, adjacency, commands, or replay.
 */
export function buildProvinceVisualAnchors(
  map: ProvinceIdentityMap,
  preferredByProvince: ReadonlyMap<number, { col: number; row: number }> = new Map(),
): readonly (ProvinceVisualAnchor | undefined)[] {
  const distance = new Int16Array(map.provinces.length);
  distance.fill(-1);
  const queue = new Int32Array(map.provinces.length);
  let head = 0;
  let tail = 0;
  let maximumProvinceId = -1;

  for (let index = 0; index < map.provinces.length; index += 1) {
    const provinceId = map.provinces[index];
    if (provinceId < 0) continue;
    maximumProvinceId = Math.max(maximumProvinceId, provinceId);
    const col = index % map.width;
    const row = Math.floor(index / map.width);
    const boundary = VISUAL_ANCHOR_NEIGHBORS.some(([deltaCol, deltaRow]) => {
      const candidateCol = col + deltaCol;
      const candidateRow = row + deltaRow;
      return candidateCol < 0 || candidateRow < 0
        || candidateCol >= map.width || candidateRow >= map.height
        || map.provinces[candidateRow * map.width + candidateCol] !== provinceId;
    });
    if (boundary) {
      distance[index] = 0;
      queue[tail] = index;
      tail += 1;
    }
  }

  while (head < tail) {
    const index = queue[head];
    head += 1;
    const provinceId = map.provinces[index];
    const col = index % map.width;
    const row = Math.floor(index / map.width);
    for (const [deltaCol, deltaRow] of VISUAL_ANCHOR_NEIGHBORS) {
      const candidateCol = col + deltaCol;
      const candidateRow = row + deltaRow;
      if (candidateCol < 0 || candidateRow < 0
        || candidateCol >= map.width || candidateRow >= map.height) continue;
      const candidateIndex = candidateRow * map.width + candidateCol;
      if (map.provinces[candidateIndex] !== provinceId || distance[candidateIndex] >= 0) continue;
      distance[candidateIndex] = distance[index] + 1;
      queue[tail] = candidateIndex;
      tail += 1;
    }
  }

  const anchors: Array<ProvinceVisualAnchor | undefined> = Array(maximumProvinceId + 1);
  const preferredDistances = new Float64Array(maximumProvinceId + 1);
  preferredDistances.fill(Number.POSITIVE_INFINITY);
  for (let index = 0; index < map.provinces.length; index += 1) {
    const provinceId = map.provinces[index];
    if (provinceId < 0) continue;
    const col = index % map.width;
    const row = Math.floor(index / map.width);
    const clearance = distance[index];
    const preferred = preferredByProvince.get(provinceId);
    const preferredDistance = preferred
      ? (col - preferred.col) ** 2 + (row - preferred.row) ** 2
      : 0;
    const current = anchors[provinceId];
    if (!current || clearance > current.clearance
      || (clearance === current.clearance && preferredDistance < preferredDistances[provinceId])
      || (clearance === current.clearance && preferredDistance === preferredDistances[provinceId]
        && (row < current.row || (row === current.row && col < current.col)))) {
      anchors[provinceId] = { provinceId, col, row, clearance };
      preferredDistances[provinceId] = preferredDistance;
    }
  }
  return anchors;
}

/** Resolve a map point to one province, correcting only into its declared parent region. */
export function resolveProvincePlacement(
  map: ProvinceIdentityMap,
  countyIndex: CountyAdministrativeIndex,
  col: number,
  row: number,
  commandery: number | undefined,
): ProvincePlacement | undefined {
  const sampleCol = Math.round(col);
  const sampleRow = Math.round(row);
  if (sampleCol >= 0 && sampleRow >= 0 && sampleCol < map.width && sampleRow < map.height) {
    const provinceId = map.provinces[sampleRow * map.width + sampleCol];
    if (provinceId >= 0 && (commandery === undefined
      || countyIndex.commanderyByProvince[provinceId] === commandery)) {
      return { col, row, provinceId };
    }
  }
  if (commandery === undefined) return undefined;

  let best: (ProvincePlacement & { distance: number }) | undefined;
  for (let candidateRow = 0; candidateRow < map.height; candidateRow += 1) {
    for (let candidateCol = 0; candidateCol < map.width; candidateCol += 1) {
      const provinceId = map.provinces[candidateRow * map.width + candidateCol];
      if (provinceId < 0 || countyIndex.commanderyByProvince[provinceId] !== commandery) continue;
      const distance = (candidateCol - col) ** 2 + (candidateRow - row) ** 2;
      if (!best || distance < best.distance
        || (distance === best.distance && (candidateRow < best.row
          || (candidateRow === best.row && candidateCol < best.col)))) {
        best = { col: candidateCol, row: candidateRow, provinceId, distance };
      }
    }
  }
  return best && { col: best.col, row: best.row, provinceId: best.provinceId };
}

/** Bind political color only to county provinces with a directly placed live owner. */
export function bindCompleteProvinceOwnership(
  map: ProvinceIdentityMap,
  cities: readonly IsoCityOverlay[],
  grid: GridSize,
  source: IsoSourceSize,
  countyIndex: CountyAdministrativeIndex,
): ProvinceOwnershipBinding {
  const geometry = new Map<number, ProvinceGeometry>();
  for (let index = 0; index < map.provinces.length; index += 1) {
    const province = map.provinces[index];
    if (province < 0) continue;
    const prior = geometry.get(province) ?? { colTotal: 0, rowTotal: 0, cells: 0 };
    prior.colTotal += index % map.width;
    prior.rowTotal += Math.floor(index / map.width);
    prior.cells += 1;
    geometry.set(province, prior);
  }

  const directByProvince = new Map<number, CitySample[]>();
  for (const city of cities) {
    const mapped = mapCityToTile(city, grid, source);
    const commandery = city.commanderyName == null
      ? undefined
      : countyIndex.commanderyByName.get(city.commanderyName);
    if (city.commanderyName != null && commandery === undefined) continue;
    const placement = resolveProvincePlacement(
      map, countyIndex, mapped.col, mapped.row, commandery,
    );
    if (!placement) continue;
    const resolvedCommandery = countyIndex.commanderyByProvince[placement.provinceId];
    const sample = {
      city,
      col: placement.col,
      row: placement.row,
      province: placement.provinceId,
      commandery: resolvedCommandery,
    };
    const direct = directByProvince.get(placement.provinceId) ?? [];
    direct.push(sample);
    directByProvince.set(placement.provinceId, direct);
  }

  const colors = new Map<number, ProvinceColor>();
  const assignedCities = new Map<number, IsoCityOverlay>();
  const directProvinces = new Set<number>();
  for (const [province, shape] of geometry) {
    const centerCol = shape.colTotal / shape.cells;
    const centerRow = shape.rowTotal / shape.cells;
    const direct = directByProvince.get(province) ?? [];
    const commandery = countyIndex.commanderyByProvince[province];
    const matchingDirect = direct.filter((sample) => sample.commandery === commandery);
    const ownedDirect = matchingDirect.filter((sample) => (
      isOwnedNationVisual(sample.city.nationId, sample.city.nationColor)
    ));
    const directCity = nearestSample(matchingDirect, centerCol, centerRow);
    if (directCity) {
      assignedCities.set(province, directCity.city);
      directProvinces.add(province);
    }
    const owned = nearestSample(ownedDirect, centerCol, centerRow);
    if (owned) colors.set(province, {
      nationId: owned.city.nationId,
      rgb: parseNationColor(owned.city.nationColor!),
    });
  }

  return { colors, conflicts: [], cities: assignedCities, directProvinces };
}

export function composeProvincePixels(
  map: ProvinceIdentityMap,
  binding: ProvinceOwnershipBinding,
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
    pixels[offset + 3] = 255;
  }
  return pixels;
}
