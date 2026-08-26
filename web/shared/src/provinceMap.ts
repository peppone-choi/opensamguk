import { mapCityToTile, type IsoCityOverlay, type IsoSourceSize } from './HanMapCanvas';
import type { GridSize } from './isoMap';

const PROVINCE_MASK = 0x0fff;

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
    const commandery = ((code >>> 12) & 0xff) - 1;
    if (province < 0 || commandery < 0) {
      throw new Error(`Province identity hierarchy is incomplete at pixel ${index}`);
    }
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

function parseNationColor(color: string | undefined): [number, number, number] | undefined {
  if (color == null || !/^#[0-9a-fA-F]{6}$/.test(color)) return undefined;
  return [
    Number.parseInt(color.slice(1, 3), 16),
    Number.parseInt(color.slice(3, 5), 16),
    Number.parseInt(color.slice(5, 7), 16),
  ];
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
    if (city.nationId <= 0) continue;
    const rgb = parseNationColor(city.nationColor);
    if (!rgb) continue;

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
  for (let index = 0; index < map.provinces.length; index += 1) {
    const color = binding.colors.get(map.provinces[index]);
    if (!color) continue;
    const offset = index * 4;
    pixels[offset] = color.rgb[0];
    pixels[offset + 1] = color.rgb[1];
    pixels[offset + 2] = color.rgb[2];
    pixels[offset + 3] = alpha;
  }
  return pixels;
}
