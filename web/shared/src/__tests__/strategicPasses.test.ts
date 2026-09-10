import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { PASS_LEVEL, STRATEGIC_PASSES, placeStrategicPasses } from '../iso/strategicPasses';
import { isExternalPlace } from '../iso/placeGameCities';
import type { BattlefieldMapProjection } from '../HanMapCanvas';

const ROOT = resolve(__dirname, '../../../..');
const read = (p: string) => JSON.parse(readFileSync(resolve(ROOT, p), 'utf-8'));

interface LedgerPass {
  id: string;
  nameKo: string;
  nameHan: string;
  role: string;
  positionStatus: string;
  coordinates: { latitude: number; longitude: number };
  primaryEvidence: { sourcePath: string; sha256: string; line: number; quote: string }[];
}
const ledger = read('data/curated/han/strategic-passes-v1.json') as {
  passes: LedgerPass[];
  excluded: { nameHan: string }[];
};

describe('關 표는 원장에서만 온다', () => {
  it('런타임 표가 strategic-passes-v1.json 과 한 글자도 안 어긋난다', () => {
    expect(STRATEGIC_PASSES.map((p) => p.id)).toEqual(ledger.passes.map((p) => p.id));
    for (const pass of STRATEGIC_PASSES) {
      const row = ledger.passes.find((p) => p.id === pass.id);
      expect(row, pass.id).toBeDefined();
      expect(row!.nameKo).toBe(pass.nameKo);
      expect(row!.nameHan).toBe(pass.nameHan);
      expect(row!.coordinates.latitude).toBe(pass.latitude);
      expect(row!.coordinates.longitude).toBe(pass.longitude);
    }
  });

  it('원장의 關 은 모두 正史 인용을 달고 있다 — 근거 없는 자리는 없다', () => {
    expect(ledger.passes).toHaveLength(8);
    for (const row of ledger.passes) {
      expect(row.role).toBe('PASS');
      // 좌표는 전부 근사다. EXACT 로 승격하려면 새 근거가 있어야 한다.
      expect(row.positionStatus).toBe('APPROXIMATE');
      expect(row.primaryEvidence.length).toBeGreaterThan(0);
      for (const cite of row.primaryEvidence) {
        expect(cite.sourcePath).toMatch(/^corpus\/(hhs|sgz)-\d+\.txt$/);
        expect(cite.sha256).toMatch(/^[0-9a-f]{64}$/);
        expect(cite.line).toBeGreaterThan(0);
        expect(cite.quote.length).toBeGreaterThan(0);
      }
    }
  });

  it('演義에만 나오는 셋은 표에 없다', () => {
    expect(ledger.excluded.map((e) => e.nameHan).sort()).toEqual(['汜水關', '綿竹關', '葭萌關']);
    for (const dropped of ['汜水關', '綿竹關', '葭萌關']) {
      expect(STRATEGIC_PASSES.some((p) => p.nameHan === dropped)).toBe(false);
    }
  });
});

describe('placeStrategicPasses', () => {
  // han-tiles.json _meta.projection 실측값. 전장(관도·장판)이 쓰는 그 투영이다.
  const tiles = read('data/map/han-tiles.json') as {
    _meta: { projection: BattlefieldMapProjection; cols: number; rows: number };
  };
  const projection = tiles._meta.projection;
  const source = { cols: tiles._meta.cols, rows: tiles._meta.rows };
  const grid = { cols: Math.ceil(source.cols / 4), rows: Math.ceil(source.rows / 4) };

  it('關 8 곳이 모두 격자 안에 선다', () => {
    const placed = placeStrategicPasses(projection, source, grid);
    expect(placed).toHaveLength(STRATEGIC_PASSES.length);
    for (const pass of placed) {
      expect(pass.col).toBeGreaterThanOrEqual(0);
      expect(pass.col).toBeLessThan(grid.cols);
      expect(pass.row).toBeGreaterThanOrEqual(0);
      expect(pass.row).toBeLessThan(grid.rows);
      expect(pass.tileCol).toBe(Math.floor(pass.col));
      expect(pass.tileRow).toBe(Math.floor(pass.row));
    }
  });

  it('關 은 등급 3(關 그림)으로 서고 게임 城 이 아니다', () => {
    const placed = placeStrategicPasses(projection, source, grid);
    for (const pass of placed) {
      expect(pass.level).toBe(PASS_LEVEL);
      // 음수 id — 눌러도 들어갈 데가 없다.
      expect(isExternalPlace(pass)).toBe(true);
      expect(pass.seat).toBe(false);
      expect(pass.isCapital).toBe(false);
      expect(pass.nationId).toBe(0);
    }
    // 서로 다른 id 여야 한다 — 겹치면 렌더러가 한 곳만 세운다.
    expect(new Set(placed.map((p) => p.id)).size).toBe(placed.length);
  });

  it('關 id 는 郡國 밖 세력 id(-1 … -지형항목수)와 절대 안 겹친다', () => {
    const placed = placeStrategicPasses(projection, source, grid);
    // 지형 cities[] 는 실측 1,524 항목이다. 여유를 크게 두고 확인한다.
    for (const pass of placed) expect(pass.id).toBeLessThan(-100_000);
  });

  it('투영이 없으면 한 곳도 안 선다 — 자리를 지어내지 않는다', () => {
    expect(placeStrategicPasses(undefined, source, grid)).toHaveLength(0);
  });
});
