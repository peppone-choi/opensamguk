import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  FRONTIER_COUNTIES,
  FRONTIER_COUNTY_LEVEL,
  frontierCountyDisplayName,
  placeFrontierCounties,
} from '../iso/frontierCounties';
import { isExternalPlace } from '../iso/placeGameCities';
import { PASS_LEVEL, STRATEGIC_PASSES, placeStrategicPasses } from '../iso/strategicPasses';
import { RASTER_GROUP, TERRAIN, downsampleTerrain } from '../isoTileGrid';
import type { BattlefieldMapProjection } from '../HanMapCanvas';

const ROOT = resolve(__dirname, '../../../..');
const read = (p: string) => JSON.parse(readFileSync(resolve(ROOT, p), 'utf-8'));

interface LedgerCounty {
  id: string;
  nameKo: string;
  nameHan: string;
  nameSimplified: string;
  commanderyHan: string;
  level: number;
  levelName: string;
  positionStatus: string;
  sourceFlags: string[];
  modernLocation: string | null;
  coordinates: { latitude: number; longitude: number };
  primaryEvidence: { sourcePath: string; sha256: string; line: number; quote: string }[];
  positionEvidence: {
    ledger: string;
    recordId: string;
    binding: string;
    bindingQuote: string;
    crossCheck: { deltaKm: number } | null;
  };
}
interface DroppedCounty {
  id: string;
  nameHan: string;
  commanderyHan: string;
  excludedBecause: string;
  reason: string;
}
const ledger = read('data/curated/han/frontier-counties-v1.json') as {
  schemaVersion: number;
  counties: LedgerCounty[];
  excluded: DroppedCounty[];
  excludedCounties: DroppedCounty[];
  corrections: { id: string; deltaKm: number; wasCoordinates: { latitude: number; longitude: number } }[];
  coverage: {
    placed: number;
    junguozhiDeclaredTotal: number;
    junguozhiDeclaredCounties: Record<string, number>;
    placedByCommandery: Record<string, number>;
    alreadyStandingAsCommanderyNode: number;
    excludedCounties: number;
    crossChecked: number;
    modernLocationKnown: number;
  };
};

describe('변경 縣 표는 원장에서만 온다', () => {
  it('런타임 표가 frontier-counties-v1.json 과 한 글자도 안 어긋난다', () => {
    expect(FRONTIER_COUNTIES.map((c) => c.id)).toEqual(ledger.counties.map((c) => c.id));
    for (const county of FRONTIER_COUNTIES) {
      const row = ledger.counties.find((c) => c.id === county.id);
      expect(row, county.id).toBeDefined();
      expect(row!.nameKo).toBe(county.nameKo);
      expect(row!.nameHan).toBe(county.nameHan);
      expect(row!.commanderyHan).toBe(county.commanderyHan);
      expect(row!.coordinates.latitude).toBe(county.latitude);
      expect(row!.coordinates.longitude).toBe(county.longitude);
      // 자리는 전부 추정이다. 확정으로 올리려면 근거를 새로 대야 한다.
      expect(row!.positionStatus).toBe('APPROXIMATE');
      // 등급은 생성기 규칙(戶÷城 < 10,000 → 장현)의 결과다.
      expect(row!.level).toBe(FRONTIER_COUNTY_LEVEL);
      expect(row!.levelName).toBe('장현');
      // 근거 없는 줄은 없다 — 郡國志 본문 인용이 縣마다 붙어 있다.
      expect(row!.primaryEvidence.length).toBeGreaterThan(0);
      for (const e of row!.primaryEvidence) {
        expect(e.sourcePath).toBe('corpus/hhs-113.txt');
        expect(e.sha256).toMatch(/^[0-9a-f]{64}$/);
        expect(e.line).toBeGreaterThan(0);
        expect(e.quote.length).toBeGreaterThan(0);
      }
      // 코퍼스는 簡體다 — 인용문 대조는 원장의 簡體 표기로 한다.
      expect(row!.primaryEvidence[0].quote).toContain(row!.nameSimplified);
      // 좌표가 어느 레코드에서 왔고 어느 축으로 그 郡에 결속됐는지 줄마다 적혀 있다.
      expect(row!.positionEvidence.ledger).toBe('namu-source-records-v1');
      expect(row!.positionEvidence.recordId.length).toBeGreaterThan(0);
      expect(['AFFILIATION', 'SECTION', 'AFFILIATION_AND_SECTION'])
        .toContain(row!.positionEvidence.binding);
      expect(row!.positionEvidence.bindingQuote.length).toBeGreaterThan(0);
      // 「추정」 꼬리표는 수확본이 단 것을 그대로 옮긴 것만 인정한다.
      for (const flag of row!.sourceFlags) expect(['UNCERTAIN', 'DISPUTED']).toContain(flag);
      // 현대 지명은 두 번째 원장에 행이 있을 때만 적는다 — 없으면 null 이다.
      expect(typeof row!.modernLocation === 'string' || row!.modernLocation === null).toBe(true);
    }
    expect(ledger.coverage.placed).toBe(FRONTIER_COUNTIES.length);
    expect(ledger.schemaVersion).toBe(2);
  });

  it('郡國志 63 縣이 51 + 郡治 7 + 뺀 縣 5 로 남김없이 갈린다', () => {
    const declared = ledger.coverage.junguozhiDeclaredCounties;
    const sum = Object.values(declared).reduce((a, b) => a + b, 0);
    expect(sum).toBe(ledger.coverage.junguozhiDeclaredTotal);
    expect(sum).toBe(63);
    expect(ledger.coverage.placed
      + ledger.coverage.alreadyStandingAsCommanderyNode
      + ledger.coverage.excludedCounties).toBe(sum);
    expect(ledger.excluded).toHaveLength(ledger.coverage.alreadyStandingAsCommanderyNode);
    expect(ledger.excludedCounties).toHaveLength(ledger.coverage.excludedCounties);
    // 郡마다 세운 수도 원장이 세어 둔 것과 같다.
    for (const [jun, n] of Object.entries(ledger.coverage.placedByCommandery)) {
      expect(FRONTIER_COUNTIES.filter((c) => c.commanderyHan === jun).length, jun).toBe(n);
    }
    // id 는 郡國志의 <郡>:<순번> 이라, 63 줄이 전부 서로 다른 자리를 가리킨다.
    const every = [...ledger.counties, ...ledger.excluded, ...ledger.excludedCounties];
    expect(new Set(every.map((c) => c.id)).size).toBe(63);
  });

  it('원장이 뺀 것은 런타임에도 없다 — 郡治 일곱·중복 둘·郡 노드 하나·미확정 하나·바다 하나', () => {
    for (const dropped of [...ledger.excluded, ...ledger.excludedCounties]) {
      expect(dropped.reason.length).toBeGreaterThan(20);
      expect(dropped.excludedBecause.length).toBeGreaterThan(0);
      const stillThere = FRONTIER_COUNTIES.some(
        (c) => c.nameHan === dropped.nameHan && c.commanderyHan === dropped.commanderyHan,
      );
      expect(stillThere, `${dropped.commanderyHan} ${dropped.nameHan}`).toBe(false);
    }
    // 郡治 일곱은 郡 노드가 이미 그 자리에 선다(jurisdiction-seat-recoveries-v1 의 seatPlaceId).
    for (const seat of ['襄平', '高句驪', '朝鮮', '昌遼', '龍編', '胥浦', '西卷']) {
      expect(ledger.excluded.some((e) => e.nameHan === seat), seat).toBe(true);
    }
    expect(ledger.excluded.every((e) => e.excludedBecause === 'ALREADY_STANDING_AS_COMMANDERY_NODE'))
      .toBe(true);
    // 郡國志가 두 郡에 적은 縣은 한 곳만 선다 — 校勘記 판정대로다.
    expect(FRONTIER_COUNTIES.filter((c) => c.nameHan === '候城')).toHaveLength(1);
    expect(FRONTIER_COUNTIES.find((c) => c.nameHan === '候城')!.commanderyHan).toBe('玄菟郡');
    expect(FRONTIER_COUNTIES.filter((c) => c.nameHan === '無慮')).toHaveLength(1);
    expect(FRONTIER_COUNTIES.find((c) => c.nameHan === '無慮')!.commanderyHan).toBe('遼東郡');
    // 자리를 못 정한 것은 UNKNOWN 으로 남는다 — 후보 하나를 골라 세우지 않는다.
    const why = new Map(ledger.excludedCounties.map((c) => [c.nameHan, c.excludedBecause]));
    expect(why.get('樂都')).toBe('POSITION_UNRESOLVED');
    expect(why.get('𧦦邯')).toBe('TILE_IS_WATER');
    expect(why.get('帶方')).toBe('ALREADY_STANDING_AS_COMMANDERY_NODE');
  });

  it('id 는 유일하고 郡國志 城數를 넘지 않는다', () => {
    expect(new Set(FRONTIER_COUNTIES.map((c) => c.id)).size).toBe(FRONTIER_COUNTIES.length);
    const declared = ledger.coverage.junguozhiDeclaredCounties;
    for (const county of FRONTIER_COUNTIES) {
      const seq = Number(county.id.split(':').pop());
      expect(county.id.startsWith(`hhs:113:${county.commanderyHan}:`), county.id).toBe(true);
      expect(seq).toBeGreaterThan(0);
      expect(seq, county.id).toBeLessThanOrEqual(declared[county.commanderyHan]);
    }
  });
});

// 두 번째 축. 위 검사는 전부 같은 원장을 본다 — 원장이 틀리면 같이 틀린다.
// 여기서는 **다른 출처**를 본다: administrative-units.json 의 郡國志 인용문은 Wikisource
// 계열이고 위 원장의 인용은 shiliao 코퍼스(中華書局 표점본) 계열이라 판이 다르다.
describe('郡國志 다른 판본으로 대조한다', () => {
  const adm = read('data/curated/han/administrative-units.json') as {
    groups: { canonicalGroup: string; declaredCities?: number; evidence?: { quote?: string }[] }[];
  };
  const quoteOf = new Map(
    adm.groups.map((g) => [g.canonicalGroup, (g.evidence ?? []).map((e) => e.quote ?? '').join(' ')]),
  );
  const declaredOf = new Map(adm.groups.map((g) => [g.canonicalGroup, g.declaredCities]));

  it('城數가 두 판본에서 같다', () => {
    for (const [jun, count] of Object.entries(ledger.coverage.junguozhiDeclaredCounties)) {
      expect(declaredOf.get(jun), jun).toBe(count);
    }
  });

  it('51 중 40 이 다른 판본 본문에도 그대로 있고, 어긋나는 열하나는 표기·줄바꿈 차이뿐이다', () => {
    const missing = FRONTIER_COUNTIES.filter(
      (c) => !(quoteOf.get(c.commanderyHan) ?? '').includes(c.nameHan),
    ).map((c) => `${c.commanderyHan} ${c.nameHan}`);
    // 실측 기준선이다. 임계값이 아니라 「지금 이 열하나」다 — 늘어나면 빨개진다.
    // 줄바꿈으로 갈린 것 넷: 遼東 無慮(無\n慮) · 樂浪 浿水(浿\n水) · 樂浪 昭明(昭\n明) ·
    //   九真 無編(無\n編) · 交趾 苟漏(苟\n漏).
    // 표기가 다른 것: 遼東 安市→安巿(異體字) · 交趾 羸𨻻→羸啮 · 交趾 安定→定安(뒤집힘) ·
    //   交趾 麋泠→麊泠 · 交趾 朱䳒→朱觏 · 玄菟 西蓋馬→西蓋鳥(誤刻).
    expect(missing.sort()).toEqual([
      '九真郡 無編', '交趾郡 安定', '交趾郡 朱䳒', '交趾郡 羸𨻻', '交趾郡 苟漏', '交趾郡 麋泠',
      '樂浪郡 昭明', '樂浪郡 浿水', '玄菟郡 西蓋馬', '遼東郡 安市', '遼東郡 無慮',
    ].sort());
    expect(FRONTIER_COUNTIES.length - missing.length).toBe(40);
    // 대조가 살아 있는지 확인한다 — 반드시 걸려야 하는 것을 같이 건다.
    expect(quoteOf.get('樂浪郡')).toContain('駟望');
    expect(quoteOf.get('玄菟郡')).toContain('高顯');
    expect(quoteOf.get('遼東屬國')).toContain('賓徒');
    expect(quoteOf.get('九真郡')).toContain('咸懽');
    expect(quoteOf.get('日南郡')).toContain('盧容');
  });
});

// 세 번째 축. 좌표가 원장을 거치며 뒤바뀌지 않았나를 **수확본 원본**에 대고 본다.
// 앞판(schemaVersion 1)의 沓氏가 정확히 이 자리에서 遼隧縣의 좌표를 물고 있었다.
describe('좌표를 수확본 원본에 대고 본다', () => {
  interface Rec {
    recordId: string;
    locations?: { coordinatePairs?: { lat: number; lon: number }[] }[];
  }
  const nsr = read('data/curated/han/namu-source-records-v1.json') as { records: Rec[] };
  const byId = new Map(nsr.records.map((r) => [r.recordId, r]));

  it('51 縣의 좌표가 positionEvidence 가 가리키는 레코드의 좌표와 같다', () => {
    for (const row of ledger.counties) {
      const rec = byId.get(row.positionEvidence.recordId);
      expect(rec, row.id).toBeDefined();
      const pairs = (rec!.locations ?? []).flatMap((l) => l.coordinatePairs ?? []);
      // 자리를 하나로 못 좁히는 레코드는 애초에 안 쓴다.
      expect(pairs, row.id).toHaveLength(1);
      expect(pairs[0].lat, row.id).toBe(row.coordinates.latitude);
      expect(pairs[0].lon, row.id).toBe(row.coordinates.longitude);
    }
  });

  it('沓氏는 遼隧縣의 좌표를 다시 물지 않는다', () => {
    const correction = ledger.corrections.find((c) => c.id === 'hhs:113:遼東郡:011');
    expect(correction, '沓氏 교정 기록').toBeDefined();
    expect(correction!.deltaKm).toBeGreaterThan(200);
    const dapssi = FRONTIER_COUNTIES.find((c) => c.nameHan === '沓氏')!;
    expect(dapssi.latitude).not.toBe(correction!.wasCoordinates.latitude);
    expect(dapssi.longitude).not.toBe(correction!.wasCoordinates.longitude);
    // 遼隧縣이 그 자리를 갖고 있다는 것 자체를 수확본에서 확인한다 — 대조가 살아 있어야 한다.
    const liaosui = nsr.records.find(
      (r) => (r.locations ?? []).some(
        (l) => (l.coordinatePairs ?? []).some(
          (p) => p.lat === correction!.wasCoordinates.latitude
            && p.lon === correction!.wasCoordinates.longitude,
        ),
      ),
    );
    expect(liaosui, '41.25893/122.78741 을 가진 레코드').toBeDefined();
    expect(liaosui!.recordId).not.toBe(
      ledger.counties.find((c) => c.id === 'hhs:113:遼東郡:011')!.positionEvidence.recordId,
    );
  });

  it('두 번째 원장과 겹치는 25 곳이 沓氏 말고는 소수점까지 같다', () => {
    const cross = ledger.counties.filter((c) => c.positionEvidence.crossCheck);
    expect(cross).toHaveLength(ledger.coverage.crossChecked);
    expect(cross).toHaveLength(25);
    const apart = cross.filter((c) => c.positionEvidence.crossCheck!.deltaKm >= 1);
    expect(apart.map((c) => c.nameHan)).toEqual(['沓氏']);
  });
});

describe('placeFrontierCounties', () => {
  // han-tiles.json _meta.projection 실측값. 전장·關 이 쓰는 그 투영이다.
  const tiles = read('data/map/han-tiles.json') as {
    _meta: { projection: BattlefieldMapProjection; cols: number; rows: number };
    terrain: string[];
  };
  const projection = tiles._meta.projection;
  const source = { cols: tiles._meta.cols, rows: tiles._meta.rows };
  const grid = { cols: Math.ceil(source.cols / 4), rows: Math.ceil(source.rows / 4) };

  it('縣 51 곳이 모두 격자 안에 선다', () => {
    const placed = placeFrontierCounties(projection, source, grid);
    expect(placed).toHaveLength(FRONTIER_COUNTIES.length);
    expect(placed).toHaveLength(51);
    for (const county of placed) {
      expect(county.col).toBeGreaterThanOrEqual(0);
      expect(county.col).toBeLessThan(grid.cols);
      expect(county.row).toBeGreaterThanOrEqual(0);
      expect(county.row).toBeLessThan(grid.rows);
      expect(county.tileCol).toBe(Math.floor(county.col));
      expect(county.tileRow).toBe(Math.floor(county.row));
    }
  });

  it('한 곳도 바다·범위밖 타일에 서지 않는다', () => {
    const terrain = downsampleTerrain(tiles.terrain, RASTER_GROUP);
    const placed = placeFrontierCounties(projection, source, grid);
    const bad: string[] = [];
    for (const county of placed) {
      const code = terrain.code[county.tileRow * terrain.cols + county.tileCol];
      if (code === TERRAIN.SEA || code === TERRAIN.OUT_OF_SCOPE) bad.push(county.name);
    }
    expect(bad).toEqual([]);
    // 대조가 살아 있는지 확인한다 — 뺀 𧦦邯 자리는 실제로 바다 타일이다.
    const dropped = ledger.excludedCounties.find((c) => c.nameHan === '𧦦邯') as
      DroppedCounty & { harvestedCoordinates: { latitude: number; longitude: number } };
    const p = projection;
    const col = Math.floor(((dropped.harvestedCoordinates.longitude * p.k) - p.x0 + p.pad) / p.cell
      / RASTER_GROUP);
    const row = Math.floor((p.y1 + p.pad - dropped.harvestedCoordinates.latitude) / p.cell
      / RASTER_GROUP);
    expect(terrain.code[row * terrain.cols + col]).toBe(TERRAIN.SEA);
  });

  it('縣 은 등급 11(작은 縣 그림)로 서고 게임 城 이 아니다', () => {
    const placed = placeFrontierCounties(projection, source, grid);
    for (const county of placed) {
      expect(county.level).toBe(FRONTIER_COUNTY_LEVEL);
      // 음수 id — 눌러도 들어갈 데가 없다.
      expect(isExternalPlace(county)).toBe(true);
      expect(county.seat).toBe(false);
      expect(county.isCapital).toBe(false);
      expect(county.nationId).toBe(0);
    }
    expect(new Set(placed.map((c) => c.id)).size).toBe(placed.length);
  });

  it('id 가 關·郡國 밖 세력과 절대 안 겹친다', () => {
    const counties = placeFrontierCounties(projection, source, grid);
    const passes = placeStrategicPasses(projection, source, grid);
    expect(passes).toHaveLength(STRATEGIC_PASSES.length);
    const overlap = new Set(counties.map((c) => c.id));
    for (const pass of passes) expect(overlap.has(pass.id), String(pass.id)).toBe(false);
    // 지형 cities[] 는 실측 1,524 항목이고 關 은 -1,000,000 부터다.
    for (const county of counties) expect(county.id).toBeLessThan(-1_000_008);
    expect(FRONTIER_COUNTY_LEVEL).not.toBe(PASS_LEVEL);
  });

  it('화면 이름은 「뭐뭐현」이다 — 음수 id 라 cityDisplayName 이 안 붙여 준다', () => {
    const placed = placeFrontierCounties(projection, source, grid);
    for (const county of placed) expect(county.name.endsWith('현')).toBe(true);
    expect(frontierCountyDisplayName(FRONTIER_COUNTIES[0])).toBe('신창현');
    expect(placed.map((c) => c.name)).toContain('점제현');
    // 이름이 겹치면 화면에서 두 縣을 못 가른다.
    expect(new Set(placed.map((c) => c.name)).size).toBe(placed.length);
  });

  it('투영이 없으면 한 곳도 안 선다 — 자리를 지어내지 않는다', () => {
    expect(placeFrontierCounties(undefined, source, grid)).toHaveLength(0);
  });

  it('일곱 郡이 모두 선다 — 九真·日南·遼東屬國도 이제 縣을 갖는다', () => {
    for (const jun of ['遼東郡', '玄菟郡', '樂浪郡', '遼東屬國', '交趾郡', '九真郡', '日南郡']) {
      expect(FRONTIER_COUNTIES.some((c) => c.commanderyHan === jun), jun).toBe(true);
    }
  });
});
