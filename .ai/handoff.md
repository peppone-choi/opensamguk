# Agent Handoff


## Current handoff (2026-09-08) — 공융 보급 절단 수정 완료·프로덕션 반영 끝, 지도 트랙 절반 남음

### 한 줄

사용자 제보 「공융이 보급이 끊겨서 증발한다」를 끝까지 추적해 고쳤고, **머지 → 배포 → 승격 →
리셋 → 라이브 실측까지 마쳤다. 새 월드의 개시 보급 절단은 19국 전부 0城이다.**

### 무엇이 끝났나

| PR | 내용 | 상태 |
|---|---|---|
| #664 | 개인 서신 수신자 선택 · 정보 스트립 줄바꿈 · 약한 ETag 지문 | 머지 |
| #665 | 전콘 카드 변환 · 군주 강등 가드 · 개시 보급 게이트 | 머지 |
| #666 | 정본 1,180縣 커버리지 감사 · 좌표 원장 · 격자 빌더 · 원인 규명 | 머지 |
| #667 | **ADR-LITE-051 郡 내부 보급선 — 공융 수정** | 머지 (main `d9f3b18a`) |

프로덕션 `pep`: 배포 success → 승격 success(엔진 포함) → 리셋 success(백업 켬, scenario_1020).
리셋 후 실측 **187년 1월 상순 · 19국 · 개시 절단 0城**(공융 16/16 보급).

### 결정 — ADR-LITE-051

**보급은 행정선(郡)을 따라서도 흐른다.** 郡은 후한의 행정·병참 단위이므로, 소유 격자에서 같은
郡의 프로빈스가 조각으로 끊긴 곳을 최소 간선으로 잇는다(`data/map/han-commandery-supply-links-v1.json`,
생성기 `tools/map/build_commandery_supply_links.py`, 70개 · 길이 중앙값 48km).

**이동은 바뀌지 않는다** — 전략 위상 LAND 간선과 `_land_owners_are_adjacent` 는 그대로다.
새 간선은 `SpatialSupplyNetwork.provinceAdjacency`(보급 전용)에만 들어간다.

되돌리기: 그 JSON 을 지우면 보급이 예전과 같아진다(`CommanderySupplyLinkLoader` 가 파일 부재를
빈 목록으로 읽는다).

### 다음 사람이 반드시 알아야 할 함정 (전부 실측으로 물렸다)

1. **보급 모델이 둘이다.** `han-world-v3` 런타임은 **spatial 프로빈스 망만** 쓴다. CityConst
   그래프(`connections`)를 고치면 게임은 하나도 안 바뀐다 — 40城이 `BOTH_UNSUPPLIED` →
   `CITY_ONLY` 로 분류만 옮겨가고 게이트만 깨진다. 실제로 구현했다가 되돌렸다.
2. **`data/map/*` 는 통째로 gitignored 다.** 새 산출물은 `!` 예외 없이는 `git add -A` 로도 안
   들어간다. 로컬은 초록, CI 만 빨갛다(감사 24건 + 엔진 테스트 4건). 커밋 뒤
   `git show --stat HEAD` 로 눈으로 확인해라.
3. **Gradle UP-TO-DATE 거짓 초록.** 「전체 회귀 녹색」이라 보고했다가 틀렸다. `--rerun-tasks`
   없이는 테스트가 안 돈다. 진짜 수치는 4,521건 / 실패 0이다.
4. **심사된 판정 행을 코드가 조용히 무효화하면 안 된다.** 郡 보급선이 보호받던 城 305·548 을
   이어 버려 보호 행이 낡았고, 나는 그 행을 은퇴시켰다 — 틀렸다. 보호 행을 되살리고 보호된
   城을 규칙에서 **제외**하는 쪽이 맞다.
5. **정본 대조는 반드시 (郡, 縣) 쌍으로.** 이름만으로 맞추면 동명이지가 겹쳐 788/1180 이 나온다
   (정직한 값은 400 결손). 표기는 繁/簡/혼재 세 갈래라 `han-name-simplification-v1.json` 으로
   눕힌다. 접미사는 `县/縣` 만 떼고 `國·道` 는 이름의 일부다(安國·夷道 — 같이 떼면 20건 어긋난다).
6. **긴 보정 간선 = 좌표 결함 신호.** 郡 보급선이 수백 km 로 길어지는 곳은 예외 없이 동명이지
   오배정이었다(上郡 定陽 1,190km 등 5건, `county-misbinding-adjudications-v1.json`).

### 남은 일 — 계획 문서: `docs/superpowers/plans/2026-09-07-han-map-rebuild.md`

1. **프로빈스 이설**(「둘 다」 지시의 나머지 절반). 소유 격자에서 잘못 놓인 프로빈스를 옳은
   자리로 옮겨 **지도 정확도**를 올린다. 보급은 이미 고쳐졌으므로 이건 정확도 작업이다.
   실측: 극현 프로빈스 25칸이 (459,178)에 있는데 있어야 할 곳에서 30열 서쪽이다. 크게 어긋난
   것은 755개 중 29개(3.8%)이고, 31개를 옮겨도 절단은 102 → 90 에서 멎었다(그래서 보급선을
   먼저 했다). 대가: 수역·전략 위상·경로 노드·15개 시나리오 소유권 재생성 + `han-tiles.json`
   통파일 해시를 핀한 8개 파일 재핀.
2. **나무위키 좌표 수확 267縣.** 원장 `data/curated/han/namu-place-locations-v1.json`(227행).
   요령: 문서가 길어 요약기가 표를 뭉개므로 **한 郡씩** 요청해야 좌표가 나온다.
   진행: 정본 1,180縣 중 지도 배치 781 · 원장 채움 75 · 타일만 26 · **좌표 없음 298**.
3. **깃허브 이슈·지라 티켓 정리** — 사용자가 지시했으나 아직 착수 못 했다.
4. UI: 작전실 도시·국가 카드는 `Panel`·`SectionHeader` 로 결선했다. 나머지 화면의 일회용
   `war-card__*` 계열이 남아 있는지 점검이 필요하다.

### 상태

- 워크트리 `worktrees/opensamguk/ui-redesign-2026-09` 는 `origin/main`(`d9f3b18a`)에 detach.
  작업 트리 깨끗, 미커밋 없음.
- 게이트 전부 초록: `build_han_world` · `apply_han_world` · `audit_han_supply_disagreements` ·
  `audit_scenario_seed_supply` · `build_commandery_supply_links` · `build_han_water_topology` ·
  `build_han_route_node_candidates` · `audit_territory_disconnections` · `audit_county_coverage`.
- 테스트: JVM 4,521 / 계약 371 / 지도 656 — 전부 통과.

## Current handoff (2026-08-23) — OPENSAM-226 W1-B remediation interrupted; resume from RED

- Reason: 사용자가 진행 중인 구현을 중단하고 핸드오프 후 대기를 요청했다. 실행 중이던
  `w1b-pack-implementer`를 interrupt했고, commit/push/merge/deploy/delete는 수행하지 않았다.
- Completed prerequisite: W0 전체와 W1-A는 독립 검토 `cleared`. W1-A artifact SHA는
  contract `fcc6a110…`, registry `fd2e7a82…`, corridor candidates `6b086297…`,
  external candidates `dd7359de…`다.
- Current W1-B files: `tools/map/external_world_*.py`, 두 external-world 테스트,
  `data/curated/han/external-world-{pack-index,legacy-adjudications}-v1.json`, 다섯 pack JSON.
  모두 uncommitted/untracked 작업이며 다른 dirty 변경과 함께 보존해야 한다.
- Initial snapshot only: 5 packs, entities 53/APPROVED 42, claims 45, scenario states
  1,643/ACTIVE 0, ledger 65 (`SUPERSEDE 27`, `REJECT 1`, pack rows 37), approved corridor 0.
  최초 구현에서 14/14와 `--check`가 green이었지만 독립 검토 `fix-required` 뒤 현재
  합격 증거로 폐기했다.
- Blocking review findings:
  1. pack SHA 재계산과 함께 claim value/sourceRef/subject/period를 위조해도 통과한다.
  2. entity `sourceClaimRefs`와 claim `subjectEntityKey`의 양방향 exact 결합이 없다.
  3. APPROVED 42개인데 ACTIVE 0을 생성기/validator가 강제한다.
  4. legacy APPROVE 37 중 11은 비승인 결과이며 summary 37이 하드코딩됐다.
  5. 모든 사료를 `OFFICIAL_HISTORY`로 일괄 분류해 후대 지리지까지 승격한다.
- Interrupted edit state: `test_external_world_source_validation.py`에 claim/source swap,
  완전 위조, 교차주체/orphan, period sentinel/source grade mutation 테스트가 추가됐고,
  `test_external_world_contract_validation.py`에 비승인 결과 APPROVE와 derived summary
  mutation 테스트가 추가됐다. 구현 파일과 JSON은 기존 의미를 유지한다. 새 테스트는
  interrupt 뒤 실행하지 않았으므로 RED 여부도 현재 exact tree에서는 미관측이다.
- Historical corrections already decided from local corpus:
  - 扶南: 기존 245 제거. `sgz-47.txt` 107행 赤烏元年(238) + 119행 六年 사건을
    결합한 243년 exact chronology.
  - 夷洲: 77행 黃龍元年(229) + 85행 二年 사건을 두 span으로 결합한 230년.
  - 帶方: `sgz-30.txt` 74행 建安中, 196~220 RANGE; 204 하드코딩 금지.
  - 林邑: `sui-082.txt` 9행 漢末의 원문 정밀도 보존.
  - 流求: 후대 alias-only, 모든 현재 시나리오 INACTIVE.
- Resume sequence:
  1. `.ai/task.md`, `.ai/decisions.md`, `docs/agent/project-overview.md`, 이 문서를 읽는다.
  2. 현재 새 mutation tests를 먼저 실행해 RED를 관측한다. 실패하지 않는 mutation은
     테스트/validator 경계를 다시 확인하되 테스트를 약화하지 않는다.
  3. claim별 reviewed binding에 exact citation spans/attested form, semantic value,
     subject, period precision을 분리하고 source raw text/SHA/line과 결합한다.
  4. entity↔claim 양방향 exact 검증, reviewed lifecycle→31 scenario state 파생,
     실제 APPROVED 결과만 legacy APPROVE, 자료별 evidence/source proximity를 구현한다.
  5. 다섯 pack과 index/ledger를 결정적으로 재생성하고 focused tests, actual `--check`,
     Ruff, basedpyright, py_compile, no-excuse를 실행한다.
  6. 같은 독립 reviewer로 최초 mutation 묶음을 재검증하고 `cleared` 후에만 W1-B
     Agent OS review/strict를 기록한다. 그 전 W1-C 금지.
- Do not repeat: all-INACTIVE를 runtime 비활성 경계로 정당화하지 않는다. broad source
  range 존재만으로 claim 의미를 승인하지 않는다. claim 이름별 하드코딩이나 count-only
  guard로 위조를 가리지 않는다. 扶南 245, 帶方 204, 林邑 192를 근거 없이 재사용하지 않는다.
- Verification at pause: 새 remediation tests와 현재 exact tree의 모든 검증은 미실행.
  체크포인트의 read-only `sed`/`rg`는 exit 0이었으나 비특정 fablize wrapper 경고가
  반복됐고 제품 실패와 분리해 기록했다.

## Current handoff (2026-08-23) — OPENSAM-206~220 integration wave; OPENSAM-218 policy gate

- Current task authority is `.ai/task.md` section `OPENSAM-206~220 통합·검증·배포 (활성 계약)` and the active integration worktree named there.
- OPENSAM-218 is the bounded policy lane in PR #499. ADR-LITE-042 retires PHP grand-truth, PHP-wins, draw-for-draw, byte-log, and golden-first product gates while retaining truthful evidence, frozen existing tests, deterministic replay, one-daemon-write, flush-delta, and insertion-order rules.
- This handoff records policy/guard verification only. The integration wave owns merge, deploy, production observation, Jira transition, and cleanup; none is claimed here.
- Durable OPENSAM-218 result and risk evidence is recorded in `reports/opensamguk/tasks/2026-08-23-op218-parity-adr.md` in the metarepo.

---

## Historical handoff (2026-08-09) — OPENSAM-43 V2-0B terminal structural review cleared; full verifier green

- HEAD `8abb47a1` structural dirty tree is terminally `cleared` with no findings:
  combined fingerprint `0734d9d5625b70fb6a92ea12c6e5717302b1b689aadcc46a4f17fcbf06f28ac3`
  (tracked `023225…06f4`; untracked fixture `898063…dd0`).
- Runtime now compares a PostgreSQL `pg_class` OID baseline with post-v2 catalog
  state and includes duplicate-key/FK fixes. Focused convention 17, mutation 4,
  V2Both 2, and infra catalog 11 are all green.
- The authorized healthy-Docker isolated `scripts/agent/verify-changes.sh --run`
  rerun passed: exit 0, `BUILD SUCCESSFUL in 23m 46s` / 29 tasks; common 232 +
  logic 3,173 + infra 236 + game-api 468 + game-engine 822 = 4,931 tests /
  failures 0 / errors 0 / engine skipped 1. Verifier strict was 46 changed /
  Errors 0 / Warnings 0 / findings 0; log
  `/tmp/op43-catalog-diff-final-os-verify-rerun.log` SHA-256 is
  `a95386e902908c199f12d86cb776e06d97ead25eef71e9dc3647b0e3e671e31e`.
- The preceding first run is historical infrastructure-only: transient Docker
  HTTP 500 before game-api app assertions; log
  `/tmp/op43-catalog-diff-final-os-verify.log` SHA-256 is
  `706131db0b7c24a2e57d4c7875031b195240b2e565ca2b798f54098bada6aadc`.
- The prior P2 reports, immutable clearance, focused 6 + 1/16 + 2, and 4,916
  verifier are historical pre-current-structural-tree evidence only.
- Next: commit/push and observe remote CI for that exact SHA. The
  PR-conversation review counter remains 0/3; no merge or deployment is implied.
- The earlier `5c93a23653012a0e557b720f701374ea2fe2c86ea5cebf718856d51933e17360`
  clearance and 4,914-test verifier are historical evidence only; they do not
  replace the current exact structural-tree clearance or 4,931 full verifier.
- No commit, push, merge, deployment, cutover, production observation, secret
  access, data deletion, legacy/golden write, or test weakening occurred in
  this documentation handoff.

## Durable references

- Current task/state: `.ai/task.md`, `.ai/current-state.md`
- Controlling review: `docs/superpowers/reviews/2026-08-09-opensam-43-v2-0b-runtime-review.md`
- Approved scope: `docs/superpowers/plans/2026-08-09-opensam-43-v2-0b-runtime-contract-plan.md`
- Long-form history: `docs/superpowers/SESSION_HANDOFF.md`
