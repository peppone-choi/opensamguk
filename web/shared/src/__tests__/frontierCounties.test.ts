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
  coordinates: { latitude: number; longitude: number };
  primaryEvidence: { sourcePath: string; sha256: string; line: number; quote: string }[];
}
const ledger = read('data/curated/han/frontier-counties-v1.json') as {
  counties: LedgerCounty[];
  excluded: { nameHan: string; commanderyHan: string; reason: string }[];
  coverage: { placed: number; junguozhiDeclaredCounties: Record<string, number> };
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
    }
    expect(ledger.coverage.placed).toBe(FRONTIER_COUNTIES.length);
  });

  it('원장이 뺀 것은 런타임에도 없다 — 郡治 넷·衍文 하나·다른 郡 소속 하나', () => {
    expect(ledger.excluded).toHaveLength(6);
    for (const dropped of ledger.excluded) {
      expect(dropped.reason.length).toBeGreaterThan(20);
      const stillThere = FRONTIER_COUNTIES.some(
        (c) => c.nameHan === dropped.nameHan && c.commanderyHan === dropped.commanderyHan,
      );
      expect(stillThere, `${dropped.commanderyHan} ${dropped.nameHan}`).toBe(false);
    }
    // 郡治 넷은 郡 노드가 이미 그 자리에 선다.
    for (const seat of ['朝鮮', '高句驪', '襄平', '龍編']) {
      expect(ledger.excluded.some((e) => e.nameHan === seat), seat).toBe(true);
    }
    // 候城은 郡國志가 遼東·玄菟 양쪽에 적었다. 한 곳만 선다.
    expect(FRONTIER_COUNTIES.filter((c) => c.nameHan === '候城')).toHaveLength(1);
    expect(FRONTIER_COUNTIES.find((c) => c.nameHan === '候城')!.commanderyHan).toBe('玄菟郡');
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

  it('25 중 21 이 다른 판본 본문에도 그대로 있고, 어긋나는 넷은 표기 차이뿐이다', () => {
    const missing = FRONTIER_COUNTIES.filter(
      (c) => !(quoteOf.get(c.commanderyHan) ?? '').includes(c.nameHan),
    ).map((c) => `${c.commanderyHan} ${c.nameHan}`);
    // 실측 기준선이다. 임계값이 아니라 「지금 이 넷」이다 — 늘어나면 빨개진다.
    //   遼東 安市  — Wikisource 판이 異體字 「安巿」로 적는다(市/巿).
    //   交趾 苟漏  — Wikisource 판에서 「苟」와 「漏」가 줄바꿈으로 갈려 있다.
    //   交趾 羸𨻻  — 벽자라 판마다 다르게 옮긴다(코퍼스 羸𨻻 · Wikisource 羸啮).
    //   交趾 安定  — Wikisource 판이 「定安」으로 뒤집어 적는다.
    expect(missing.sort()).toEqual(
      ['交趾郡 安定', '交趾郡 羸𨻻', '交趾郡 苟漏', '遼東郡 安市'].sort(),
    );
    expect(FRONTIER_COUNTIES.length - missing.length).toBe(21);
    // 대조가 살아 있는지 확인한다 — 반드시 걸려야 하는 것을 같이 건다.
    expect(quoteOf.get('樂浪郡')).toContain('駟望');
    expect(quoteOf.get('玄菟郡')).toContain('高顯');
  });
});

describe('placeFrontierCounties', () => {
  // han-tiles.json _meta.projection 실측값. 전장·關 이 쓰는 그 투영이다.
  const tiles = read('data/map/han-tiles.json') as {
    _meta: { projection: BattlefieldMapProjection; cols: number; rows: number };
  };
  const projection = tiles._meta.projection;
  const source = { cols: tiles._meta.cols, rows: tiles._meta.rows };
  const grid = { cols: Math.ceil(source.cols / 4), rows: Math.ceil(source.rows / 4) };

  it('縣 25 곳이 모두 격자 안에 선다', () => {
    const placed = placeFrontierCounties(projection, source, grid);
    expect(placed).toHaveLength(FRONTIER_COUNTIES.length);
    for (const county of placed) {
      expect(county.col).toBeGreaterThanOrEqual(0);
      expect(county.col).toBeLessThan(grid.cols);
      expect(county.row).toBeGreaterThanOrEqual(0);
      expect(county.row).toBeLessThan(grid.rows);
      expect(county.tileCol).toBe(Math.floor(county.col));
      expect(county.tileRow).toBe(Math.floor(county.row));
    }
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
  });

  it('투영이 없으면 한 곳도 안 선다 — 자리를 지어내지 않는다', () => {
    expect(placeFrontierCounties(undefined, source, grid)).toHaveLength(0);
  });

  it('九真郡·日南郡은 한 곳도 안 선다 — 좌표가 없어서다', () => {
    for (const jun of ['九真郡', '日南郡']) {
      expect(FRONTIER_COUNTIES.some((c) => c.commanderyHan === jun), jun).toBe(false);
    }
  });
});
