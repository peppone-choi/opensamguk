// OPENSAM-41 [G0-C] 순수 로직 게이트 — G0C-b~e(왕복), g(4-class count), h(catalog tier),
// i(runtime LOD 독립축). 브라우저가 필요 없는 항목은 전부 여기서 결정적으로 닫는다.

import { readdirSync, readFileSync } from 'node:fs';
import { join, sep } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  CATALOG_TIER_BUDGET,
  PLACE_BUDGET,
  PLACE_BUDGET_TOTAL,
  PROJECTION_VERSION,
  RUNTIME_LODS,
} from '@/lib/v2/terrain/contracts';
import type { CommandCamera, RuntimeLod, Viewport } from '@/lib/v2/terrain/contracts';
import {
  ProjectionVersionMismatchError,
  deserializeCamera,
  pickCorridor,
  pickPlace,
  project,
  roundTripBattlefieldAnchor,
  roundTripOperationPath,
  roundTripPlace,
  roundTripReplayCamera,
  serializeCamera,
  unproject,
} from '@/lib/v2/terrain/projection';
import {
  auditBudget,
  auditCatalogTiers,
  auditLodIndependence,
  budgetViolations,
  planRender,
  resolveIdentity,
  selectRuntimeLod,
} from '@/lib/v2/terrain/lod';
import {
  demoCamera,
  demoScene,
  syntheticCamera,
  syntheticCatalog,
  syntheticScene,
} from '@/lib/v2/terrain/fixtures';
import { shouldStubV2Client } from '@/lib/v2/buildIsolation.mjs';
import { FrameCollector, summarizeFrames } from '@/lib/v2/terrain/telemetry';

const VIEWPORT: Viewport = { width: 1024, height: 768 };

describe('G0C-a 데모 read model — 도시 3 · route 2 · terrain 1', () => {
  const model = demoScene();

  it('구성이 티켓 요구와 정확히 일치한다', () => {
    expect(model.places).toHaveLength(3);
    expect(model.corridors).toHaveLength(2);
    expect(model.regions).toHaveLength(1);
    expect(model.versions.projectionVersion).toBe(PROJECTION_VERSION);
  });

  it('T1-G02: 강 수송로 속성은 RIVER corridor에만 붙는다', () => {
    const river = model.corridors.find((c) => c.type === 'RIVER');
    const land = model.corridors.find((c) => c.type !== 'RIVER');
    expect(river?.river).toBeDefined();
    expect(river?.river?.upstreamCostFactor).toBeGreaterThan(river!.river!.downstreamCostFactor);
    expect(land?.river).toBeUndefined();
  });

  it('T1-G12: 개연 위치만 uncertaintyRadius를 갖는다', () => {
    for (const p of model.places) {
      if (p.reconstructionStatus === 'OBSERVED') expect(p.uncertaintyRadius).toBe(0);
      else expect(p.uncertaintyRadius).toBeGreaterThan(0);
    }
  });
});

describe('T1-G09/G10 정사영 — 투영과 역투영', () => {
  it('평면 위 점은 project → unproject 왕복에서 되돌아온다', () => {
    const cam = demoCamera();
    for (const p of [
      { x: 0, y: 0, z: 0 },
      { x: -123.5, y: 0, z: 88.25 },
      { x: 400, y: 0, z: -400 },
    ]) {
      const back = unproject(project(p, cam, VIEWPORT), cam, VIEWPORT, 0);
      expect(back.x).toBeCloseTo(p.x, 9);
      expect(back.z).toBeCloseTo(p.z, 9);
    }
  });

  it('정사영이므로 화면 거리 = 월드 거리 × zoom (원근 나눗셈 없음)', () => {
    const cam: CommandCamera = { ...demoCamera(), azimuth: 0, pitch: 0, zoom: 3 };
    const a = project({ x: 0, y: 0, z: 0 }, cam, VIEWPORT);
    const b = project({ x: 10, y: 0, z: 0 }, cam, VIEWPORT);
    expect(b.sx - a.sx).toBeCloseTo(30, 9);
  });
});

describe('G0C-b 공유 picking 왕복', () => {
  it('데모 3거점 전부 identity가 그대로 돌아온다', () => {
    const model = demoScene();
    const cam = demoCamera();
    for (const place of model.places) {
      expect(roundTripPlace(place, model, cam, VIEWPORT)).toEqual({
        ok: true,
        pick: expect.objectContaining({ placeId: place.placeId, placeIdentityKey: place.placeIdentityKey }),
      });
    }
  });

  it('synthetic 2,000거점 전부 왕복 오류 0', () => {
    const model = syntheticScene();
    const cam = syntheticCamera();
    const failures = model.places
      .map((p) => roundTripPlace(p, model, cam, VIEWPORT))
      .filter((r) => !r.ok);
    expect(failures).toEqual([]);
  });

  it('projectionVersion이 다르면 picking을 시도하지 않고 거부한다', () => {
    const model = demoScene();
    const cam: CommandCamera = {
      ...demoCamera(),
      versions: { ...demoCamera().versions, projectionVersion: 'ortho-999' },
    };
    expect(() => roundTripPlace(model.places[0], model, cam, VIEWPORT)).toThrow(
      ProjectionVersionMismatchError,
    );
  });

  it('hit radius 밖은 아무것도 집지 않는다 (허위 identity 금지)', () => {
    const model = demoScene();
    const cam = demoCamera();
    expect(pickPlace({ sx: -5000, sy: -5000 }, model.places, cam, VIEWPORT)).toBeNull();
  });
});

describe('G0C-c 작전 경로 왕복', () => {
  it('작전의 corridor 전부가 집혀 같은 작전으로 되돌아온다', () => {
    const model = demoScene();
    const cam = demoCamera();
    const result = roundTripOperationPath(model.operations[0], model, cam, VIEWPORT);
    expect(result.ok).toBe(true);
    if (result.ok) expect(result.picks.map((p) => p.corridorId)).toEqual(model.operations[0].corridorIds);
  });

  it('경로에서 멀리 떨어진 점은 corridor를 집지 않는다', () => {
    const model = demoScene();
    expect(pickCorridor({ sx: 5, sy: 5 }, model.corridors, demoCamera(), VIEWPORT)).toBeNull();
  });

  it('미존재 corridor를 가진 작전은 실패 사유를 돌려준다', () => {
    const model = demoScene();
    const broken = { ...model.operations[0], corridorIds: ['route:ghost'] };
    const r = roundTripOperationPath(broken, model, demoCamera(), VIEWPORT);
    expect(r).toEqual({ ok: false, reason: expect.stringContaining('route:ghost') });
  });
});

describe('G0C-d 전장 anchor 왕복', () => {
  it('BattlefieldSeed anchor가 같은 좌표계에서 되돌아온다', () => {
    const model = demoScene();
    expect(roundTripBattlefieldAnchor(model.battlefields[0], model, demoCamera(), VIEWPORT)).toEqual({
      ok: true,
    });
  });

  it('전술 seed의 projectionVersion이 전략과 다르면 거부한다 (T1-G09)', () => {
    const model = demoScene();
    const seed = {
      ...model.battlefields[0],
      versions: { ...model.battlefields[0].versions, projectionVersion: 'ortho-0' },
    };
    expect(() => roundTripBattlefieldAnchor(seed, model, demoCamera(), VIEWPORT)).toThrow(
      ProjectionVersionMismatchError,
    );
  });

  it('전략 read model에 없는 작전의 anchor는 통과하지 못한다', () => {
    const model = demoScene();
    const seed = { ...model.battlefields[0], operationId: 'op:ghost' };
    const r = roundTripBattlefieldAnchor(seed, model, demoCamera(), VIEWPORT);
    expect(r).toEqual({ ok: false, reason: expect.stringContaining('op:ghost') });
  });
});

describe('G0C-e replay camera 왕복', () => {
  it('직렬화 → 복원 후 화면 배치 지문이 동일하다', () => {
    const model = demoScene();
    const r = roundTripReplayCamera(demoCamera(), model, VIEWPORT);
    expect(r.ok).toBe(true);
  });

  it('직렬화 문자열이 결정적이다 (replay 비교 대상)', () => {
    expect(serializeCamera(demoCamera())).toBe(serializeCamera(demoCamera()));
  });

  it('저장 당시 projectionVersion이 지원 범위 밖이면 복원을 거부한다', () => {
    const raw = serializeCamera({
      ...demoCamera(),
      versions: { ...demoCamera().versions, projectionVersion: 'ortho-legacy' },
    });
    expect(() => deserializeCamera(raw)).toThrow(ProjectionVersionMismatchError);
  });
});

describe('G0C-g PlaceBudgetClass 4-class count·합계', () => {
  const places = syntheticCatalog();

  it('1,200 / 200 / 500 / 100, 합계 2,000', () => {
    const audit = auditBudget(places);
    expect(audit.counts).toEqual(PLACE_BUDGET);
    expect(audit.total).toBe(PLACE_BUDGET_TOTAL);
    expect(budgetViolations(audit)).toEqual([]);
  });

  it('다중 class·무분류·비장소·identityKey 복제 집계가 0', () => {
    const audit = auditBudget(places);
    expect(audit.multiClassKeys).toEqual([]);
    expect(audit.unclassifiedIds).toEqual([]);
    expect(audit.nonPlaceIds).toEqual([]);
    expect(audit.duplicateKeys).toEqual([]);
  });

  it('감사기가 실제로 위반을 잡는다 (공허한 통과 방지)', () => {
    const broken = [
      ...places.slice(0, 1999),
      { ...places[1999], budgetClass: 'ADMINISTRATIVE_SETTLEMENT' as const },
      { ...places[0], placeId: 'dup', position: { x: NaN, y: 0, z: 0 } },
    ];
    const violations = budgetViolations(auditBudget(broken));
    expect(violations.length).toBeGreaterThan(0);
    expect(violations.join(' ')).toMatch(/비장소|identityKey 복제|total/);
  });
});

describe('G0C-h catalog LOD Tier A/B/C', () => {
  it('120 / 380 / 1,500', () => {
    const { counts, violations } = auditCatalogTiers(syntheticCatalog());
    expect(counts).toEqual(CATALOG_TIER_BUDGET);
    expect(violations).toEqual([]);
  });

  it('tier 배분은 budget class 배분과 독립이다 (두 축이 서로를 결정하지 않는다)', () => {
    const places = syntheticCatalog();
    // 각 budget class 안에 A·B·C가 모두 등장하면 두 축은 결합되어 있지 않다.
    for (const cls of Object.keys(PLACE_BUDGET) as (keyof typeof PLACE_BUDGET)[]) {
      const tiers = new Set(places.filter((p) => p.budgetClass === cls).map((p) => p.catalogTier));
      expect(tiers, cls).toEqual(new Set(['A', 'B', 'C']));
    }
  });
});

describe('G0C-i runtime LOD 4종 독립축 왕복', () => {
  it('선택은 카메라 거리·기기 성능만 본다 — catalog 데이터가 입력에 없다', () => {
    expect(selectRuntimeLod(0, 'HIGH')).toBe('FULL_SCENE');
    expect(selectRuntimeLod(100, 'HIGH')).toBe('KIT');
    expect(selectRuntimeLod(300, 'HIGH')).toBe('SYMBOL');
    expect(selectRuntimeLod(1000, 'HIGH')).toBe('CLUSTER');
    // 저사양은 한 단계가 아니라 두 단계 물러난다.
    expect(selectRuntimeLod(0, 'MID')).toBe('KIT');
    expect(selectRuntimeLod(0, 'LOW')).toBe('SYMBOL');
    expect(selectRuntimeLod(1000, 'LOW')).toBe('CLUSTER');
  });

  it('2,000거점 × 4 LOD × 3 tier 전부에서 identity 유실·중복 0', () => {
    expect(auditLodIndependence(syntheticCatalog())).toEqual([]);
  });

  it('CLUSTER에서도 개별 거점을 stable identity로 되찾는다 (Tier C 포함)', () => {
    const places = syntheticCatalog();
    const plan = planRender(places, 'CLUSTER');
    expect(plan.clusters.length).toBeGreaterThan(0);
    const tierC = places.filter((p) => p.catalogTier === 'C');
    for (const p of [tierC[0], tierC[tierC.length - 1], places[0], places[1999]]) {
      expect(resolveIdentity(plan, p.placeId), p.placeId).toMatchObject({ found: true, via: 'CLUSTER' });
    }
    // 클러스터 대표도 결정적이어야 replay가 같은 그림을 낸다.
    expect(planRender(places, 'CLUSTER').clusters.map((c) => c.representativeId)).toEqual(
      plan.clusters.map((c) => c.representativeId),
    );
  });

  it('모든 LOD에서 read model 전량이 정확히 한 번 표현된다', () => {
    const places = syntheticCatalog();
    for (const lod of RUNTIME_LODS as readonly RuntimeLod[]) {
      const plan = planRender(places, lod);
      const slots = plan.markers.length + plan.clusters.reduce((s, c) => s + c.memberIds.length, 0);
      expect(slots, lod).toBe(places.length);
    }
  });
});

describe('OPENSAM-35 격리 전제 복원 — v2 클라이언트 번들 배제', () => {
  it('V2_ENABLED가 아니면 v2 클라이언트 진입점을 스텁으로 치환한다', () => {
    expect(shouldStubV2Client('@/components/v2/SpaceProof3D', false)).toBe(true);
    expect(shouldStubV2Client('./components/v2/SpaceFallbackTable', false)).toBe(true);
  });

  it('V2_ENABLED 빌드에서는 치환하지 않는다', () => {
    expect(shouldStubV2Client('@/components/v2/SpaceProof3D', true)).toBe(false);
  });

  it('v2가 아닌 모듈은 건드리지 않는다', () => {
    for (const request of ['react', 'three', '@/components/GameChrome', '@/lib/v2/terrain/lod']) {
      expect(shouldStubV2Client(request, false), request).toBe(false);
    }
  });

  // 리뷰 발견(2026-08-16): shouldStubV2Client 단위 테스트만 있으면 next.config.mjs에서
  // 훅을 떼어내도 전 테스트가 초록이고 three.js가 프로덕션 번들로 나간다. 배선 자체를 건다.
  it('next.config.mjs가 스텁 플러그인을 V2_ENABLED 조건과 함께 실제로 배선한다', () => {
    const config = readFileSync(join(__dirname, '..', 'next.config.mjs'), 'utf8');
    expect(config).toMatch(/createV2ClientStubPlugin\(/);
    expect(config).toMatch(/process\.env\.V2_ENABLED === 'true'/);
    expect(config).toMatch(/webpack\(config\)/);
  });

  // buildIsolation.mjs가 스스로 신고한 한계: 스텁은 webpack 훅이다. build가 turbopack으로
  // 바뀌면 훅이 조용히 죽는다 → 주석이 아니라 테스트로 잠근다.
  it('build 스크립트가 turbopack으로 바뀌면 실패한다 (스텁은 webpack 전용)', () => {
    const pkg = JSON.parse(readFileSync(join(__dirname, '..', 'package.json'), 'utf8')) as {
      scripts: Record<string, string>;
    };
    expect(pkg.scripts.build).not.toMatch(/turbo/);
  });

  // 스텁은 `components/v2/**` import만 가로챈다. v2 로직(lib/v2/terrain/*)을
  // components/v2 밖의 클라이언트 컴포넌트가 직접 import하면 그 경로로 새어 나간다.
  it('components/v2 밖의 클라이언트 컴포넌트는 lib/v2를 import하지 않는다', () => {
    const root = join(__dirname, '..');
    const skip = new Set(['node_modules', '.next', 'test-results', '__tests__', 'e2e', '.git']);
    const walk = (dir: string): string[] =>
      readdirSync(dir, { withFileTypes: true }).flatMap((e) =>
        e.isDirectory()
          ? skip.has(e.name)
            ? []
            : walk(join(dir, e.name))
          : /\.tsx?$/.test(e.name)
            ? [join(dir, e.name)]
            : [],
      );

    const files = walk(root);
    expect(files.length).toBeGreaterThan(0); // 공허한 통과 방지
    const leaks = files.filter((f) => {
      if (f.includes(`${sep}components${sep}v2${sep}`)) return false;
      const src = readFileSync(f, 'utf8');
      // 서버 컴포넌트(page.tsx 등)는 클라이언트 번들에 들어가지 않으므로 대상이 아니다.
      if (!/^\s*['"]use client['"]/m.test(src)) return false;
      return /from '@\/lib\/v2\//.test(src);
    });
    expect(leaks).toEqual([]);
  });
});

describe('G0C-k 프레임 통계 — 지어내지 않는다', () => {
  it('16.67ms 간격 60프레임이면 60 FPS로 요약된다', () => {
    const t = summarizeFrames(Array.from({ length: 60 }, () => 1000 / 60));
    expect(t.fps).toBeCloseTo(60, 6);
    expect(t.frames).toBe(60);
  });

  it('워밍업 프레임만 버리고 이후 스파이크는 남긴다', () => {
    const c = new FrameCollector(240, 3);
    // 기동 히치 3개(버려짐) → 정상 40프레임 → 스파이크 1개(남아야 함)
    let t = 0;
    for (const d of [0, 120, 130, 140, ...Array.from({ length: 40 }, () => 16.7), 90]) {
      t += d;
      c.tick(t);
    }
    const s = c.summary()!;
    expect(s.frames).toBe(41);
    expect(s.worstFrameMs).toBe(90);
    expect(s.p50FrameMs).toBeCloseTo(16.7, 6);
  });

  it('표본이 부족하면 FPS를 만들어내지 않고 null을 낸다', () => {
    const c = new FrameCollector();
    for (let i = 1; i <= 20; i += 1) c.tick(i * 16.7);
    expect(c.summary()).toBeNull();
  });

  it('p95·최악 프레임이 스파이크를 감춘다면 게이트가 무의미하다', () => {
    const t = summarizeFrames([...Array.from({ length: 99 }, () => 10), 200]);
    expect(t.worstFrameMs).toBe(200);
    expect(t.p95FrameMs).toBe(10);
    expect(t.p50FrameMs).toBe(10);
  });
});
