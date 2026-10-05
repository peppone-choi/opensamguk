import { readdirSync, readFileSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

// M2-9: 옛 지도(아이소)를 지울 때 함께 사라질 모듈. 남는 지도 코드는 이것들을 가져오지 않는다 —
// 그래야 지우기 PR 이 파일 삭제만으로 끝난다(1단계 옮기기, 2026-10-05).
const OLD_IMPORT = /from '(?:\.\.?\/)+(?:WorldMapCanvas|isoMap|provinceMap|iso\/(?:cityFootprint|regionalArchitecture|tint|marker|juLod|corpsOverlay|cityBadgeLayer))'/;

const SRC = resolve(__dirname, '..');
const RETAINED_FILES = [
  'worldCityBadges.ts',
  'nationVisual.ts',
  'provinceNames.ts',
  'strategicMap.ts',
  'iso/cityName.ts',
  'iso/countyNameGloss.tsx',
  'iso/waterwaySiteRoles.ts',
];

function sourcesUnder(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return sourcesUnder(path);
    return /\.tsx?$/.test(entry.name) ? [relative(SRC, path)] : [];
  });
}

const retained = [...sourcesUnder(join(SRC, 'map')), ...RETAINED_FILES];

describe('남는 지도 코드는 옛 지도 모듈을 가져오지 않는다', () => {
  it('검사가 살아 있다(옛 import 를 잡고, 새 지도 파일을 훑는다)', () => {
    expect(OLD_IMPORT.test("import { drawCityFlag } from '../iso/marker';")).toBe(true);
    expect(OLD_IMPORT.test("import type { IsoView } from '../../isoMap';")).toBe(true);
    expect(OLD_IMPORT.test("import { WorldMapCanvas } from './WorldMapCanvas';")).toBe(true);
    expect(OLD_IMPORT.test("import { cityDisplayName } from '../iso/cityName';")).toBe(false);
    expect(retained).toContain(join('map', 'topdown', 'renderer.ts'));
    expect(retained).toContain(join('map', 'mapData.ts'));
  });

  it.each(retained)('%s', (file) => {
    const offending = readFileSync(join(SRC, file), 'utf8').split('\n').filter((line) => OLD_IMPORT.test(line));
    expect(offending).toEqual([]);
  });
});
