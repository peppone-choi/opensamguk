// OPENSAM-41 [G0-C] G0C-j — 실제 2,000 source catalog 전체 동일 검사.
//
// 상태: **BLOCKED.** 2,000 거점 catalog는 G0A-h/i(군·국 105 + 현·읍·도·후국 1,180)와
// G0B-n/o(주변 정치 240 / 주변 물리 장소 500)의 산출물이고 아직 리포에 없다.
// 리포에 있는 유일한 v2 카탈로그는 v1 지도의 94 도시(content/v2/cities_1010.json)다.
//
// 그래서 이 테스트는 (1) 실제 catalog가 나타나면 synthetic과 **같은 함수**로 검사하고,
// (2) 아직 없으면 "없음"을 사실로 고정한다. 수치를 지어내 통과시키지 않는다.
// 카탈로그가 착지하는 순간 아래 BLOCKED 단언이 빨개지고, 검사 경로가 자동으로 켜진다.

import { readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import type { PhysicalPlace } from '@/lib/v2/terrain/contracts';
import { PLACE_BUDGET_TOTAL } from '@/lib/v2/terrain/contracts';
import { auditCatalog } from '@/lib/v2/terrain/lod';
import { syntheticCatalog } from '@/lib/v2/terrain/fixtures';

const REPO_ROOT = join(__dirname, '..', '..', '..');

/** G0A/G0B 산출물이 놓일 후보 경로. 하나라도 있으면 실검사를 돌린다. */
const CANDIDATES = [
  'content/v2/places.json',
  'content/v2/place-catalog.json',
  'infra/src/main/resources/content/v2/places.json',
  'infra/src/main/resources/content/v2/place-catalog.json',
];

function loadSourceCatalog(): { path: string; places: PhysicalPlace[] } | null {
  for (const rel of CANDIDATES) {
    const abs = join(REPO_ROOT, rel);
    if (!existsSync(abs)) continue;
    const raw = JSON.parse(readFileSync(abs, 'utf8')) as { places?: PhysicalPlace[] } | PhysicalPlace[];
    const places = Array.isArray(raw) ? raw : (raw.places ?? []);
    return { path: rel, places };
  }
  return null;
}

describe('G0C-j 실제 source catalog 동일 검사', () => {
  const source = loadSourceCatalog();

  it('synthetic 2,000은 공용 검사 함수를 통과한다 (검사기 자체의 기준선)', () => {
    expect(auditCatalog(syntheticCatalog())).toEqual([]);
  });

  it(
    source
      ? `실제 catalog(${source.path}) ${PLACE_BUDGET_TOTAL} 거점이 synthetic과 동일 검사를 통과한다`
      : '실제 2,000 catalog는 아직 리포에 없다 — BLOCKED on G0A-h/i, G0B-n/o',
    () => {
      if (!source) {
        // 존재하는 v2 카탈로그는 v1 지도의 94 도시뿐임을 사실로 고정한다.
        const v1Catalog = join(REPO_ROOT, 'infra/src/main/resources/content/v2/cities_1010.json');
        expect(existsSync(v1Catalog)).toBe(true);
        const meta = JSON.parse(readFileSync(v1Catalog, 'utf8')) as { cityCount: number };
        expect(meta.cityCount).toBe(94);
        expect(meta.cityCount).toBeLessThan(PLACE_BUDGET_TOTAL);
        return;
      }
      expect(source.places).toHaveLength(PLACE_BUDGET_TOTAL);
      expect(auditCatalog(source.places)).toEqual([]);
    },
  );

  it('연대기 모드: 시나리오 날짜 밖 항목도 같은 검사 대상이다', () => {
    if (!source) {
      // 실제 catalog가 없으면 연대기 항목도 없다 — 판정할 것이 없음을 명시한다.
      expect(source).toBeNull();
      return;
    }
    const chronicle = source.places.filter((p) => p.chronicleOnly);
    expect(auditCatalog(source.places)).toEqual([]);
    // 연대기 항목을 제외해도 남는 거점 수가 예산 총합보다 작아야 분리 집계가 성립한다.
    expect(source.places.length - chronicle.length).toBeLessThanOrEqual(PLACE_BUDGET_TOTAL);
  });
});
