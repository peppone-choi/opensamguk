# Current Task

## 2026-08-21 — OPENSAM-206~220 통합·검증·배포 (활성 계약)

- Status: 사용자의 `전체 승인`에 따라 구현, commit, push, PR, merge, deploy, Jira 갱신까지 연속 수행한다.
- Goal: OPENSAM-206~220의 현재 할 일을 하나의 clean integration tree로 fan-in하고, 독립 리뷰·통합 게이트·실사용 QA·운영 cutover를 실제 증거로 닫는다.
- Active branch/worktree: `codex/integrate-206-220-wave` / `.omo/integration-wave`.
- Scope: main에 이미 포함된 OPENSAM-220 consumer stage는 중복 적용하지 않는다. OPENSAM-211 브랜드 단일화와 OPENSAM-219 닉네임 변경도 Jira 현재 본문대로 포함한다.
- Rollout invariants: `game_server` seed 완전성 검사 후 gateway migration, board readiness, web, nginx 순서로 기동한다. 모든 game-api consumer가 승격되기 전 gateway issuer claim 제거는 금지한다.
- Acceptance: JDK 21 backend XML, frontend full tests/typecheck/build, Compose/Docker, 인증 포함 browser/API QA, exact-SHA 5면 review와 PR review, external compose cutover, production health, Jira evidence.
- Safety: 기존 root checkout과 사용자 컨테이너/데이터를 보존한다. 비밀값을 읽거나 기록하지 않는다. destructive operation은 대상 확인 후 승인 범위에서만 수행한다.

## 2026-08-22 — W1-B 외부 세계 5개 pack 사료 계약 (진행 중)

- Goal: W1-A의 legacy 외부 65행을 전부 명시적으로 disposition하고, 동해·동북·서역·
  북방·남방 5개 pack의 선택된 외부 대상을 `AdministrativePlace | AnchoredPlace |
  PolityPresence | RemoteGate`로 분리한다. 활성 대상은 원문·권차·시기·위치 판정을
  갖고, `-9999..9999` 기본 기간과 현대 좌표 기반 역사 확정을 제거한다.
- Source boundary: 로컬 공개 corpus의 repo-relative path, SHA-256, 1-based line range,
  exact verbatim을 claim마다 고정한다. 제품 활성 15개와 lifecycle 검증 대상 Han
  리소스 31개를 분리하며, 사료 밖 위치·시기·고정점을 만들지 않는다.
- Required adjudication: 倭 여정은 `對馬→一大→末盧→伊都→奴→不彌→投馬→邪馬壹`
  순서를 보존한다. `帶方郡`은 설치 근거 이전 활성 0, `流求`는 활성 0/후대 alias-only,
  `夷洲`는 단일 확정점 금지, 후대 가야 명칭은 alias-only다. 이동·분산 세력은
  고정 도시가 아닌 `PolityPresence`다.
- Allowed files: `.ai/{task,current-state,ownership}.md`,
  `tools/map/{external_world_contract,build_external_world_pack_index,external_world_validation,external_world_source_validation}.py`,
  `tools/map/tests/{test_external_world_contract_validation,test_external_world_source_validation}.py`,
  `data/curated/han/external-world-{pack-index,legacy-adjudications}-v1.json`,
  `data/curated/han/external-world-packs/{east-sea-wa,northeast,western-regions,northern-steppe,southern-maritime}-v1.json`,
  W1-B 독립 review artifact.
- Acceptance: legacy 65 disposition 100%, 다섯 pack coverage 100%, 활성 외부 대상의
  source/date/location resolution 100%, source SHA·line·verbatim exact, lifecycle 무기한
  기본값 0, forbidden type/date/location 승격 0, 결정적 재생성·mutation tests·독립 검토.
- Non-goals: W1-C 승인 corridor/access relation·mode·geometry·최종 graph snapshot,
  `han.json`/시나리오/Kotlin/runtime 활성화, live infrastructure 값, W2+.

## 2026-08-22 — W1-A 교통·외부 세계 후보 계약 (완료)

- Goal: W0의 승인 RouteNode 780개에 현재 `han.json`의 무방향 연결 1,783개를
  route-key 기반 후보로만 옮기고, `external-places.json` 65행도 전부 PENDING 후보로
  보존한다. 후보 생성은 도로·항로의 승인, mode, geometry, 사료 근거를 추론하지 않는다.
- Scenario boundary: Gateway가 노출하는 `ACTIVE_PRODUCT_SCENARIO_CODES`는 15개이고,
  Han 지도 데이터 계약이 누락 없이 검사할 시나리오 리소스는 31개다. 두 집합을
  별도 필드로 고정하며 수명주기 검증 범위는 31개 전체다.
- Completed files: `.ai/{task,current-state,ownership}.md`,
  `tools/map/{build_route_corridor_candidates,route_network_contract,route_network_validation,route_network_source_validation}.py`,
  `tools/map/tests/{test_route_corridor_candidates,test_route_network_contract_validation,test_route_network_source_validation}.py`,
  `data/curated/han/{route-network-contract,route-corridor-key-registry,route-corridor-candidates,external-world-candidates}-v1.json`,
  `docs/superpowers/reviews/2026-08-22-w1a-route-corridor-candidate-contract-review.md`.
- Acceptance: 후보 연결 1,783 exact/unique/PENDING, endpoint RouteNode 780 집합 안,
  self/dangling/duplicate/asymmetry 0, UUIDv4 corridor key 1,783 unique, 외부 65 exact/PENDING,
  허용 mode와 4개 외부 타입 계약, infrastructure state 분리, runtime activation
  `NOT_CLAIMED_BY_W1_DATA_CONTRACT`, 결정적 재생성·drift 검사·타깃 테스트·독립 검토.
- Non-goals: W1-B 행별 APPROVE/REJECT/SPLIT/SUPERSEDE, 사료 claim과 외부 5팩,
  geometry, 최종 연결 그래프, live infrastructure 값, 시나리오/runtime 활성화, W2+.
- Evidence: 타깃 25/25, 실제 `--check` drift 0, Ruff·py_compile·basedpyright·
  no-excuse green, 산출물 4개 SHA 고정, 독립 remediation 재검토
  `BLOCKER 0 / MAJOR 0 / MINOR 0`, 최종 `cleared`.

## 2026-08-22 — W0 전체 정본 게이트 (완료)

- Status: W0-A/B/C 산출물과 독립 비평은 모두 `cleared`다. map 25/25,
  RouteNode 110/110, 정본/오버레이/후보/선정 드리프트 0, Ruff·py_compile,
  Agent System strict 오류/경고 0으로 W0 게이트를 닫았다.
- Boundary: W0-C는 data-contract approval이다. 시나리오 typed route-node binding과
  fresh-world activation은 후속 prerequisite로 남겼고, live-save cutover는 계속
  `NEW_WORLD_ONLY`로 차단한다. 이 경계를 유지한 채 W1로 넘어간다.

## 2026-08-22 — W0-C reviewed 780 RouteNode 선정·저장 이관 (완료)

- Status: 1,960 후보를 전부 `PENDING`으로 생성하고, 722 unique overlay + 50
  행별 모호성 심사 + 8 외부 위치 claim으로 780개를 명시 승인했다.
  materializer/validator 110/110과 최종 독립 재검토가 `cleared`다.
- Goal: W0-A 1,180 identity와 W0-B 220년 물리 위치 오버레이를 서로 다른
  `AdministrativeUnit`, `PhysicalPlace`, `RouteNode` 계층으로 유지하면서 정확히 780개
  플레이 노드를 승인한다. 각 노드는 독립적으로 발급한 `routeNodeKey`, 하나의
  `physicalPlaceRef`, 그리고 `HHS_ADMINISTRATIVE_UNIT` 또는
  `EXTERNAL_SOURCE_CLAIM` 중 정확히 하나의 역사 결합을 가진다.
- Review boundary: 후보 생성기는 모든 행을 `PENDING`으로만 출력한다. 승인 파일은
  행별 선정 사유·위치 판정·역사 충돌 disposition·활성 기간·시나리오 집합을 명시하며,
  거리/최근접/첫 후보 자동 선택은 허용하지 않는다. `PolityPresence`, 181~225년에
  성립하지 않는 후대 명칭, 고정점으로 비정할 수 없는 해역은 RouteNode가 아니다.
- Save boundary: 숫자 ID는 `oldCityId -> oldNodeFingerprint -> routeNodeKey -> newCityId`
  원장으로만 이관한다. 실제 live save 존재와 전 참조 리허설이 확인되기 전에는
  `NEW_WORLD_ONLY`로 fail-closed하며, 불변 history/replay JSON을 몰래 재작성하지 않는다.
- Completed files: `.ai/{task,current-state,ownership}.md`, 신규
  `tools/scenario/{build_han_route_node_candidates,validate_han_route_node_selection}.py`,
  신규 `tools/scenario/tests/test_han_route_node_selection.py`, 신규
  `data/curated/han/route-node-*-v1.json`, W0-C review artifact.
- Acceptance: 후보 1,180/현재 780 전수 분류, APPROVED 780, old/new ID 각각
  `1..780`, route key 780 unique, binding XOR/dangling/duplicate 0, 모호 위치 행별
  adjudication 100%, `-9999..9999` 0, `流求` 활성 0, 204년 전 `帶方郡` 활성 0,
  `PolityPresence` RouteNode 0, 가짜 `龜茲屬國` 0, ID 704 역사 결합 교정 수치,
  결정적 생성·참조 무결성·독립 검토 `cleared`.
- Non-goals: W1 corridor 승인·외부 세계 전수 저작, W2+ 런타임 명령/이동, live save
  cutover, 배포, 골든/legacy 변경, 시크릿 접근.

## 2026-08-22 — W0-B 1,180 행정단위·CHGIS 220년 좌표 오버레이 (완료)

- Status: tools/map 25/25, real-source drift 0, 1,180 identity exact, independent
  terminal review `cleared`로 완료했다.
- Goal: 1,180개 안정 identity를 누락 없이 CHGIS V6 county point 220년
  snapshot과 결합하되, 동명 후보를 거리로 임의 선택하지 않고 해결·모호·미해결·
  손상 원문을 명시적 상태로 보존한다.
- Join contract: key는 `(sourceVolume, canonicalGroup, ordinal)`로만 만든다.
  일치는 `sourceName`과 독립 인용이 있는 `nameCorrection.correctedName`만 사용하고,
  CHGIS `NAME_CH|NAME_FT`의 현/도/후국/읍 접미를 규칙으로 제거한 정확 일치만 후보로 삼는다.
  `BEG_YR <= 220 <= END_YR`만 포함하고, 후보 1건은 `RESOLVED_POINT`, 2건 이상은
  `AMBIGUOUS_POINT`, 0건은 `NO_COORDINATE_CANDIDATE`, 원문 손상 3건은
  `SOURCE_PLACEHOLDER`로 fail-closed한다. 단, 유일 후보 record를 다른 identity도
  사용하면 양쪽 모두 `AMBIGUOUS_POINT`이며 선택 좌표를 두지 않는다.
- Output policy: CHGIS 좌표·record provenance가 든 생성물
  `data/map/administrative-place-overlay.json`은 ADR-LITE-039에 따라 gitignored/uncommitted로
  유지하고, 추적 코드·테스트·리뷰 증거만 commit surface에 둔다.
- Allowed files: `.ai/{task,current-state,ownership}.md`,
  `tools/map/build_administrative_place_overlay.py`,
  `tools/map/tests/test_administrative_place_overlay.py`,
  `docs/superpowers/reviews/2026-08-22-w0b-administrative-place-overlay-review.md`.
- Acceptance: 오버레이 identity 1,180/1,180 exact+unique, input catalog 105/1,180
  fail-closed, CHGIS DBF hash·year·record provenance 보존, 후보 2건 이상의 좌표 선택 0,
  legacy 1,076에서 빠진 104 identity를 모두 명시적 상태로 표현, 결정적 재생성,
  targeted test/real-source `--check` green, 별도 agent 비평 `cleared`.
- Non-goals: 모호 후보의 인수 승인, 780 playable 선정·save migration(W0-C),
  외부 세력·도로(W1), 런타임/DB/UI 변경, 추적 좌표 데이터 추가.

## 2026-08-22 — W0-A 후한 군국지 정본 카탈로그 (완료)

- Status: 9/9 targeted test, 105/1,180 exact audit, byte-exact artifact,
  whole-worktree verifier와 별도 agent 검토 `cleared`로 완료했다.
- Goal: 《後漢書》 卷109–113 郡國志의 구조를 경계 정본으로 삼아 105개 군국과
  1,180개 현급 단위를 중복 없는 안정 식별자 `(sourceVolume, canonicalGroup, ordinal)`로
  공개 정본화한다.
- Source contract: 로컬 공개 도메인 corpus는 블록 경계·열거 순서·유형 판정의 근거다.
  ctext 사본/CHGIS는 W0-A 정본 좌표에 포함하지 않는다. 원문 불일치는 숨기지 않고
  `安平國 城十三 / 열거 12` 한 건으로 명시한다.
- Allowed files: `.ai/{task,current-state,ownership}.md`,
  `tools/map/audit_junguozhi_source.py`, `tools/map/junguozhi_contract.py`,
  `tools/map/tests/test_junguozhi_contract.py`,
  `data/curated/han/administrative-units.json`,
  `docs/superpowers/reviews/2026-08-22-w0a-administrative-catalog-review.md`.
- Acceptance: canonical group 105/105 exact+unique, unit identity 1,180/1,180 exact+unique,
  `龜茲`는 `上郡` 제9 단위이고 가짜 군국 `龜茲屬國`·오염 헤더 `巴陵秦置`는 0건,
  mismatch는 `安平國 13/12` 단 한 건, 생성기를 두 번 실행해 byte drift 0,
  타깃 테스트 green, Agent OS 검증, 별도 agent 비평 `cleared`.
- Non-goals: 좌표/도로/외부 세계 결합(W0-B), 780 playable 선택·save ID 이관(W0-C),
  런타임·DB·프론트 변경, golden/legacy 수정, 배포·운영 cutover.
- Safety: 기존 dirty worktree와 사용자 변경을 보존한다. `.env*`·secret은 읽거나 쓰지
  않는다. 이 계약은 W0-A 파일에만 쓰기 권한을 행사한다.

## 2026-08-14 — OPENSAM-149 종결 문서 정합화 (활성 계약)

- Status: 사용자 요청 "계획을 짜서 하나하나 처리해"에 따라 OPENSAM-32/#174,
  OPENSAM-88/#230 종결 뒤 순차 실행 중.
- Goal: 이미 `main`에 병합된 OPENSAM-149의 bounded restart gate와 명시적
  quarantine을 정본 문서에 반영해 #324 Done 조건의 문서 불일치를 해소한다.
- Allowed files: `.ai/{task,current-state,ownership}.md`,
  `docs/superpowers/gap/LOGIC_GAP.md`, `docs/superpowers/SESSION_HANDOFF.md`,
  `docs/superpowers/plans/2026-08-14-opensam-149-closeout-plan.md`,
  `docs/superpowers/reviews/2026-08-14-opensam-149-closeout-review.md`.
- Acceptance evidence: PR #399 merge/CI 확인, 기존 focused/architecture XML 증거와
  quarantine matrix 대조, 문서 링크·strict·diff 검증, 독립 리뷰 `cleared`.
- Human approval checkpoints: commit/push/PR/merge와 데이터 삭제는 별도 승인 없이 수행하지 않는다.
- Non-goals: product source/test 변경, quarantine 구현, engine promotion/deploy,
  production/secret 접근, golden/legacy 수정.

## 2026-08-09 — OPENSAM-43 V2-0B 런타임 계약·격리 가드 (활성 계약)

- Status: 사용자 승인 완료(`"승인."`, 2026-08-09), committed PR head
  `5363abe9be95c304b30288df493c5b1f006ab8bb`의 exact-SHA remote CI는 CodeRabbit을 포함한 모든 job이
  SUCCESS로 완료됐다. prior/pre-infra-sync terminal independent code-remediation review fingerprint
  `84dc8a5ee43586b00f36101b7a840f9eba3241fdb7c705460242790d2263b1ca`는 no findings로 `cleared`였으며 current exact-tree
  fingerprint가 아니다. focused
  focused exact-source `app:game-engine` mutation 4 / V2Both2는 green이다. exact dirty-tree full verifier는 `2h 16m 30s` 뒤
  `app:game-api` `V2BothConditionsBeanGateIT` `initializationError`의 Testcontainers PostgreSQL readiness timeout(assertion 전)만으로 실패했다
  (log SHA-256 `8d6ff449e7fa672249768d486416f8d2ad17101d4e0ff59421b4ed13f76cec94`). 따라서 local full verifier는
  green이 아닌 `infra-blocked`다. authorized final isolated rerun은 Docker preflight가 usable version/status를
  반환하지 않고 health가 `UNKNOWN/not confirmed healthy`여서 시작하지 않았다. strict/diff는 green이고 independent code-content
  review는 `cleared`다. commit/push 뒤 resulting exact-SHA remote CI가 required deciding gate이며 reviews 0/3은
  pending이다; merge-ready를 주장하지 않는다. `8abb47a1` CI도 historical green evidence다. 계획 정본:
  `docs/superpowers/plans/2026-08-09-opensam-43-v2-0b-runtime-contract-plan.md`.
- Goal: OPENSAM-35의 isolation-only foundation 위에서 0B-a~k를 실제 런타임·wire·Flyway·catalog
  계약으로 닫고, v1 production에는 v2 bean/table/content가 0건임을 유지한다.
- Approved contract correction:
  - 기존 G0 선행·counter 1,180·`CountyParticipationFixture` gameplay 전제를 v2 오픈 경로에서 제거한다.
  - 추적 입력 `infra/src/main/resources/scenario/cities_1010.json`을 복제 없이 참조한다.
  - 전체 94도시, `nation_id != 0` 소유 도시 24, SHA-256
    `6759a68255cae1a6b9c05cbbaf5736ed8fc9fcb50c6623be44d7e3dfe0b4d393`을 fail-closed로 검증한다.
  - 같은 입력의 반복 적재 snapshot은 동일하고 in-memory diff는 0이어야 한다.
  - G0·1,180 콘텐츠는 폐기하지 않고 오픈 후로 유지한다(ADR-LITE-019/021).
- Scope:
  - 0B-a v1-completion ledger 정본 참조.
  - 0B-b/c v2 profile/world identity와 feature flag runtime integration.
  - 0B-d/e migration naming/rollback/location 계약 + test-only Flyway sandbox probe.
  - 0B-f/g ACTIVE-only catalog와 94도시 typed adapter.
  - 0B-h/i v1 wire를 바꾸지 않는 v2 전용 command-result/turn-event payload version 계약.
  - 0B-j 영향 query 목록, 0B-k v1 production 미적용 가드.
- Non-goals: OPENSAM-44 persistence, OPENSAM-150 실제 migration/첫 v2 leaf·도시 원장,
  OPENSAM-104/105 RTK builder, G0 1,180 콘텐츠, production deploy/cutover.
- Acceptance evidence: 계획서의 lane별 RED→GREEN 명령, JDK 21 focused XML,
  `tools/parity/gate.sh backend`, `tools/agent-system/check.py --strict --base origin/main --format json`,
  `scripts/agent/verify-changes.sh --run`, 독립 review artifact와 PR review disposition.
- External tracking: Jira OPENSAM-43과 GitHub issue #185는 승인 계약으로 갱신됐고 Jira는 `진행 중`이다.
  PR #371은 별도의 pull-request record다.
- Human approval: 이 계약과 v2 완성 목표는 구현·PR·머지를 승인한다. deploy/cutover, secret 접근,
  데이터 삭제, legacy/golden 쓰기, 테스트 약화는 승인되지 않았다.
- Current review/evidence: committed PR head `5363abe9be95c304b30288df493c5b1f006ab8bb`의 exact-SHA remote
  CI는 CodeRabbit을 포함한 모든 job이 SUCCESS로 완료됐다. prior/pre-infra-sync terminal independent code-remediation
  review fingerprint `84dc8a5ee43586b00f36101b7a840f9eba3241fdb7c705460242790d2263b1ca`는 no findings로 `cleared`였으며
  current exact-tree fingerprint가 아니다. focused
  focused exact-source `app:game-engine` mutation 4 / V2Both2는 green이고 sole code-content verdict는 `cleared`다. exact dirty-tree full
  verifier는 `2h 16m 30s` 후 `app:game-api` `V2BothConditionsBeanGateIT` `initializationError`의 Testcontainers PostgreSQL readiness timeout이 assertion
  전에 발생해 유일하게 실패했다 (log SHA-256
  `8d6ff449e7fa672249768d486416f8d2ad17101d4e0ff59421b4ed13f76cec94`). local full verifier는 `infra-blocked`이며
  green/merge-ready가 아니다. authorized final isolated rerun은 Docker preflight가 usable version/status를 주지 않고
  health가 `UNKNOWN/not confirmed healthy`여서 시작하지 않았다. strict/diff는 green이다. commit/push 뒤 resulting exact-SHA
  remote CI가 required deciding gate이고 PR-conversation reviews 0/3은 pending이다. historical PR head `8abb47a1`의
  CI도 green evidence다. 아래 authorized healthy-Docker isolated verifier는 committed-head historical evidence다:
  `scripts/agent/verify-changes.sh --run` rerun은 exit 0, `BUILD SUCCESSFUL in 23m 46s` / 29 tasks,
  common 232 + logic 3,173 + infra 236 + game-api 468 + game-engine 822 = 4,931 tests / failures 0 /
  errors 0 / game-engine skipped 1을 기록했다. verifier strict는 46 changed / Errors 0 / Warnings 0 /
  findings 0이고 로그 `/tmp/op43-catalog-diff-final-os-verify-rerun.log` SHA-256은
  `a95386e902908c199f12d86cb776e06d97ead25eef71e9dc3647b0e3e671e31e`다. 직전 첫 run은 game-api app
  assertion 전 transient Docker HTTP 500으로 실패한 historical infrastructure-only attempt이며 로그
  `/tmp/op43-catalog-diff-final-os-verify.log` SHA-256은
  `706131db0b7c24a2e57d4c7875031b195240b2e565ca2b798f54098bada6aadc`다. Historical only: the first submitted `@codex` review's
  duplicate metadata-key, FK same-name identity, and `SELECT INTO` P2 findings, the prior immutable
  clearance at base HEAD `ac1d199644f61685ca3fee25f19c36e07782f960` / dirty diff
  `0657e23e82f37f2c8ee0a7080edb3cdfbf048bb78966590f3c310cd839a4bb8b`, focused 6 + 1/16 + 2, and fresh
  `scripts/agent/verify-changes.sh --run` 4,916/0/0/skip1 evidence are pre-current-structural-tree
  history only; they do not substitute for the prior/pre-infra-sync code-remediation clearance or current exact-tree
  infra-blocked full-verifier gate. 그 전의 PR #371 initial SHA
  `983598928f4375b902d1e49c72551056ce5c9a1f`에는
  `agent-system`, `jvm`, `web (gateway)`, `web (game)` 네 job이 green이지만 Round 1 전의
  historical CI다. Round 1의 CodeRabbit 6 threads와 Codex P2 1건은 구현됐고, focused infra rerun은
  `BUILD SUCCESSFUL in 42s` / 10 tasks, catalog 10/0/0/0, adapter 6/0/0/0이다. Engine evidence log
  SHA-256 `d6ea51c9a8ee5fb9991443eb7313cee666f86092c56e1fd7daf2e461b3e36ba4`는
  `app:game-engine` `V2FlywayIsolationConstraintMutationIT` 1/0/0/0 및 `app:game-engine` V2Both2
  (`V2BothConditionsBeanGateIT`) 2/0/0/0을 기록한다.
  Historical terminal independent re-review fingerprint
  `5c93a23653012a0e557b720f701374ea2fe2c86ea5cebf718856d51933e17360`는 BLOCKER/MAJOR/MINOR/QUESTION/NIT
  없이 `cleared`였으나 prior/pre-infra-sync code-remediation clearance 또는 current exact-tree infra-blocked
  full-verifier gate를 대체하지 않는다. pre-final-SHA Round 1
  dirty-tree `scripts/agent/verify-changes.sh --run`은 정확히 한 번
  exit 0으로 실행되어 `BUILD SUCCESSFUL in 12m 54s` / 29 executed, common 232 + logic 3,173 + infra 235 +
  game-api 468 + game-engine 806 = 4,914 tests / failures 0 / errors 0 / game-engine skipped 1을 기록했다.
  strict는 44 changed / Errors 0 / Warnings 0 / findings 0이고 로그
  `/tmp/op43-round1-final-os-verify.log` SHA-256은
  `5dc8db9b1f94cb509a8e1c4f826aaa6621e2fcc81e6996db110adc77f8dd9454`다. compose/smoke/deploy는
  비범위로 미실행이다.

---

## 2026-08-08 — OPENSAM-35 V2-0A production 격리 게이트 (활성 계약)

- Status: 계획 채택됨(사용자 승인 2026-08-08), S0~S6 구현과 PR #370 Round 1 remediation은 완료됐다.
  historical dirty-tree review는 no findings로 `cleared`(fingerprint `3c1b357c…`)이고 local immutable-SHA review는
  `54ead4e7…`에서 완료됐다. GitHub PR head `70492bcc`의 네 CI jobs는 green이었고 `agent-system`에는 original
  contract step이 포함됐다. 이는 final-8 dirty permission/active-matcher/docs remediation 전 evidence다. Codex Round 3
  P2 clearance도 historical evidence다. Terminal independent final-8 dirty-tree re-review는 no findings로 `cleared`되어
  final CodeRabbit 8 dispositions를 모두 resolved했다. CodeRabbit Round 2는 rate-limited request라 result가 없고,
  PR review mentions=3은 세 submitted results—CodeRabbit Round 1(23), Codex Round 3(2), final CodeRabbit incremental(8)—이다.
  Current remote CI for an exact final-remediation commit와 merge/release/deploy/production 관측은 미수행이다. 계획 정본:
  `docs/superpowers/plans/2026-08-08-opensam-35-v2-0a-isolation-plan.md`.
- User request (verbatim): "OPENSAM-35" / "채택 + S6까지 연속 실행".
- Goal: v2 코드가 production으로 새지 않음을 강제하는 격리 게이트 0A-a~g 7항목 +
  ADR-LITE-021이 귀속시킨 DoD 3항목을 닫는다. 이 티켓은 **확장점 개설이 아니라 격리**다
  (`round3-proposal-city-guanxi.md:459` — "7항목 전부가 격리이고 확장점 개설은 하나도 없다").
- 착수 전 확정 사실:
  - **구현 전 baseline에서** v2 런타임 코드는 리포에 0건이었다. 현재는 이 계약이
    의도한 isolation-only gate(`V2SandboxGate`, 두 configuration, content catalog,
    `v2-lab` route/middleware)만 존재한다. v2 product leaf·schema·persistence는 이
    티켓에 없고 OPENSAM-150로 남는다. 따라서 0A-e는 baseline에 뺄 대상이 없었다.
  - 티켓 본문의 path:line 인용 5건이 부정확하다(내용은 유효). 정정표는 계획서 §0.2.
  - U12는 "선례 없음"이 확정됐다 — 양쪽 `application.yml:14`가 `classpath:db/migration`
    하드코딩이고 리포 전역에 `SPRING_FLYWAY_LOCATIONS` 오버라이드 선례가 없다.
  - 리포 최초 도입 3건: `@ConditionalOnProperty`(전역 0건), `getBeansOfType` assertion
    (테스트 전역 0건), `content/` 루트(없음).
- 사용자 결정 (2026-08-08):
  - **C1** — v2 sandbox 스택은 `docker-compose.v2-sandbox.yml` **신규 파일**로 분리한다.
    `docker-compose.production.yml`은 무수정이고 `SCENARIO_SEED_ENABLED=false` strict
    불변식(`coding-rules.md:12`)을 유지하며 `tools/agent-system/check.py`는 수정하지 않는다.
  - **C2** — 0A-e 범위는 **이 리포로 한정**한다. sibling `opensamguk-docker`의
    `servers/<id>.env` 및 shared account/JWT/profile live-integration 몫은 연결된
    OPENSAM-177 consumer 티켓으로 분리한다. OPENSAM-35는 그 티켓을 실행·release·deploy하지
    않으며, OPENSAM-177도 완료/배포로 주장하지 않는다.
- 실행 단계: S0 U12 실측 → S1 0A-c Flyway location 분리 → S2 0A-b 조건부 빈 게이트 →
  S3 0A-d catalog + 0A-a route → S4 0A-f 실측 아키텍처 테스트 → S5 v2 스택 env 분리 →
  S6 0A-g artifact + 게이트 + 독립 리뷰. 상세 판정 기준은 계획서 §3.
- Allowed files: 계획서 §3·§4가 단계별로 확정한다. **T1(`logic/src/main/kotlin/**`,
  `common/src/main/kotlin/**`, `logic/src/test/resources/golden/**`, 기존 v1 테스트)은
  수정·삭제 0건, 신규 파일 추가만 허용, 예외 없음.** T2 사전 선언 목록은 S0 결과 뒤
  계획서 §4에 기입하며, 그 뒤 초과 편집은 게이트 ③ 위반이다.
- Acceptance evidence:
  - 게이트 ① `tools/parity/gate.sh backend` (출력 tail + XML, exit code 금지)
  - 게이트 ② T1 diff 0 · 게이트 ③ T2 diff = 사전 선언과 정확히 일치 · 게이트 ⑤ 설정 리소스 diff 0.
    모든 diff evidence는 `MB=$(git merge-base HEAD origin/main)`와 `:(glob)…/**` pathspec으로
    다시 측정한다. 이전 `git diff origin/main -- 'dir/'` transcript는 evidence가 아니다.
  - 0A-f가 v1 프로세스 컨텍스트를 **실제로 띄워** v2 빈 0개를 실측(정적 스캔 대체 불가)
  - v2 스택 부팅 후 v2 전용 probe 이벤트 행 존재 + v1 기본 이벤트 12행 **미적재**를 DB로 실측.
    실제 v2 leaf 행은 OPENSAM-150의 필수 수용 기준으로 이관(ADR-LITE-029)
  - `web/game` `pnpm typecheck && pnpm test`
  - **A3의 의미 한정:** PHP golden inventory와 T1/parity diff 0은 scope/inventory proof일 뿐
    PHP capture 또는 draw-for-draw replay가 실행·통과했다는 주장이 아니다. 이 isolation/build-only
    티켓은 T1/parity code를 바꾸지 않으므로 PHP replay를 acceptance로 요구하지 않는다. 이후
    T1/parity 변경 티켓은 별도 capture/replay를 수행해야 하며, A3는 A4 backend gate를 대체하지 않는다.
    final CodeRabbit의 반복 replay 요청은 **blocked가 아닌 inapplicable** disposition이다: ADR-LITE-021의
    isolation DoD와 canonical merge-base T1 empty diff는 replay가 이 티켓의 acceptance event가 아님을 보이고,
    어떤 replay pass/waiver도 주장하지 않는다.
  - PR #370의 23개 Round 1 disposition은 source remediation/full backend evidence와 함께 all
    resolved/dispositioned이며, **current dirty-tree** independent re-review는 `cleared`다
    (no findings; fingerprint `3c1b357c…`). local immutable-SHA review(`54ead4e7…`)와 last observed GitHub CI
    green은 historical completion evidence다. Codex Round 3 P2 source remediation은 Compose `ASSET_PREFIX=/game`, new
    `tools/ops/v2_sandbox_compose_contract_test.sh`, and `.github/workflows/ci.yml` `agent-system` invocation으로
    implemented됐고 prior independent dirty-tree re-review는 no blockers/fixes/questions/nits로 `cleared`다. GitHub
    head `70492bcc`는 original contract step을 포함해 green이었지만 final-8 dirty permission/active-matcher/docs
    remediation을 원격에서 검증하지 않는다. Terminal independent final-8 dirty-tree re-review는 all eight findings를
    no findings로 `cleared`했고, exact final-remediation commit의 remote CI는 별도로 pending이다.
  - current backend evidence는 Java 21 `tools/parity/gate.sh backend`의 `--rerun-tasks` six-root
    **one run / no retry** 결과다: `BUILD SUCCESSFUL in 12m 35s`, 35 actionable tasks, XML 601 suites /
    5,050 tests / failures 0 / errors 0 / skipped 1; full-log SHA256
    `a35ea5cf8352e2fe518daa32dbe95343f92bf62c95dc41a3673e924aa9fcaad1`. historical A4 599/5,023과
    2026-08-08 forced four-root subset 286/1,652는 더 이른 기록이며 full current backend evidence가 아니다.
    같은 verifier의 frontend dependency absence(`tsc: command not found`)는 historical failure이고,
    later direct-pnpm typecheck는 green이며 Vitest JSON은 132 suites / 288 tests / failures 0이다.
    Compose required `JWT_SECRET` 오류는 fail-closed failure이고 syntax pass가 아니다. 현재 backend evidence는
    remote exact-SHA CI 또는 release authorization을 대체하지 않는다.
- Human approval checkpoints: 커밋·푸시·PR·머지·배포는 각각 별도 승인. 골든/테스트 약화,
  legacy 쓰기, `.env*`·secret 접근, 데이터 삭제, production 접근은 금지.
- Non-goals: v2 파이프라인 seam 개설(오픈 후 P0), OPENSAM-150 `v2_city_ledger` 스키마,
  sibling 리포 변경, production 배포·cutover·서버 승격.

---

## 2026-08-06 — OPENSAM-33 완료 전이 + OPENSAM-34 EC2→GCP 대체

- OPENSAM-33 (D4-14~17 B2 운영 스모크): Jira `할 일` → **`완료`** 전이 완료. 2026-07-31 로컬 완료/released 상태에서 전이만 미실행이었고, D4-14~17 실측 증거와 5모듈 게이트(552 suites / 4,763 tests / 0 failures)를 증거 코멘트로 남겼다. 미해결 non-blocking 1건은 EventSource open 9 vs `turnCompleted` 8 (reconnect/remount 대 중복 구독 UNKNOWN) — D4-16 판정에는 영향 없으나 중복 구독이면 별건이다.
- OPENSAM-34 (D4-31~35 배포 전 Go 조건): 종전 blocker였던 "`ec2-prod` 러너 offline"은 **오진**이었다. 레포 등록 프로덕션 러너 `gcp-prod-opensamguk`은 online이며 라벨은 `[self-hosted, Linux, X64, gcp-prod]`, `ec2-prod` 라벨은 존재하지 않는다. 실제 원인은 predeploy 워크플로만 없어진 라벨을 계속 선택한 drift였다(promote/reset/deploy는 이미 이관 완료).
- 함께 발견한 별건 결함: grader의 `highest_checkout_migration()`이 `*.sql`만 스캔해 Kotlin 마이그레이션 `V38__rtk14_npc_lifecycle_repair.kt`를 놓친다. `.sql`만 37 / Kotlin 포함 38. **양방향으로 틀린다** — V38 적용된 DB에서는 기대 37 vs 실제 38로 false NO-GO(라벨만 고쳤으면 첫 실행이 그대로 실패), V38 미적용 DB에서는 37==37이라 미적용 마이그레이션을 못 보고 false GO.
- 부수 수정: zero-padded `V08`이 `(( ))` 좌항 8진수 오류로 조용히 skip되던 것 `10#` 강제로 수정. 계약 테스트 하드코딩 스텁 → checkout 파생. **정정:** 현 저장소에 `V0*` 마이그레이션은 없으므로 이는 활성 버그가 아니라 잠재 결함 방어다.
- **철회했던 가드를 되돌렸다 (정정).** 이전 판의 철회 근거 — "디렉터리 부재 = Kotlin 마이그레이션 부재이므로 37 기대가 옳고, 이 가드는 영구 false NO-GO만 만든다" — 는 사실과 다르다. 해당 디렉터리는 git 추적 대상이라(`V26__npc_lifecycle_phase_units.kt`, `V38__rtk14_npc_lifecycle_repair.kt`) 정상 체크아웃에 항상 있고, `shopt -s nullglob` 때문에 경로가 stale하면 스캔이 조용히 `.sql`-only(37)로 퇴화해 미적용 V38을 안고 **false GO**가 난다. 격리 재현 38 vs 37 확인 후 `predeploy_go_check.sh:155-159`에 사유 주석과 함께 복원했다.
- 브랜치 `ops-predeploy-gcp-retarget` → **PR #364로 `main`에 squash 머지 완료(`d950435b`, 2026-08-08).** 검증: 양쪽 스크립트 `bash -n` PASS, 워크플로 YAML 파싱 PASS, hermetic 계약 테스트 `PASS` EXIT=0, 변경 전 baseline에서는 동일 테스트가 `NO-GO: D4-35 ...`로 실패함을 `git stash`로 확인.
- **리뷰 상태 정정.** 이전 판의 "독립 적대적 리뷰 1회 — blocker/major 0"은 유효하지 않다. 후속 검토에서 위 major(가드 부재로 인한 false GO)를 찾았고, 그 리뷰는 **독립 에이전트가 수행하지 못했다** — `oh-my-claudecode:critic`·`deep-reasoner`가 각각 두 번씩 보고 없이 idle 처리됐다. 사용자 승인 아래 이 세션 자체 검증으로 아티팩트를 작성했다: `docs/superpowers/reviews/2026-08-06-opensam-34-predeploy-gcp-retarget-review.md` (`Verdict: quarantined-with-proof`). **이전 판이 "머지 전 필수 잔여"로 적었던 다른 프로바이더 독립 재검토는 수행되지 않은 채 사용자가 PR #364를 머지했다.** 즉 이 변경의 교차 프로바이더 검토는 미실행으로 남아 있으며, 회귀 방어는 hermetic 계약 테스트와 이 세션 자체 리뷰 아티팩트뿐이다.
- Jira OPENSAM-34는 **`할 일` 유지**. 머지된 것은 grader/워크플로 수정뿐이고 실제 D4-31~35 production 관측은 미실행이다. 워크플로 dispatch에는 여전히 별도 명시 승인과 6개 입력값(`server`/`expected_tag`/`expected_scenario_code`/`world_id`/`min_free_gib`/`min_free_percent`)이 필요하다.
- 다음: 티켓 종결은 `predeploy-go-check.yml` dispatch → D4-31~35 5항목 GO 판정으로만 가능하다. 그 전까지 OPENSAM-34 write scope는 닫는다.

---

## 2026-08-03 — RTK14 full-roster scenario and five-stat surfaces (완료 — 2026-08-04 머지)

- Status (2026-08-06 갱신): **PR #356이 2026-08-04T06:39:59Z에 머지됐다.** 아래 "working tree에만 존재" / "release step 미완" 서술은 당시 기록이며 더 이상 유효하지 않다. `origin/main`에 `V37__general_owner_claim_request.sql`과 `V38__rtk14_npc_lifecycle_repair.kt`가 모두 올라와 있고, 후속 수정 #362·#363까지 머지됐다. 이 절은 이력으로 보존한다.
- 당시 기록: the exact prior source HEAD `725195fea29b3434cc358e3d262c6c440830dab7` review found a P1 released-V26 forward-repair gap. The remediation no longer touches V26: `V26__npc_lifecycle_phase_units.kt` and `V26NpcLifecycleMigrationTest.kt` are reverted byte-for-byte to `origin/main`, and all RTK14 lifecycle repair now lives in one new world-scoped migration, `V38__rtk14_npc_lifecycle_repair.kt` (test: `V38Rtk14NpcLifecycleRepairMigrationTest.kt`), which runs on every world. It is not yet a final full gate, merged change, deployment, reseed, or live verification.
- Migration numbering: the claim-request migration was renumbered `V36__general_owner_claim_request.sql` → `V37__general_owner_claim_request.sql`, because `origin/main` already ships `V36__diplomacy_casualties.sql` and two V36s make Flyway fail with a duplicate version.
- Why V38 instead of extending V26: a database that already recorded V26 in `flyway_schema_history` never re-runs it, so the extension could not reach upgraded worlds; and on a fresh database Flyway runs before `ScenarioSeedRunner` (an `ApplicationRunner`), so `world_state` is empty and V26 returns immediately, making the extension unreachable on new worlds too. Putting the whole repair in V38, which no world has recorded yet, is what makes already-migrated and freshly seeded worlds converge on the same final state.
- Current remediation: V38 resolves the effective scenario external-over-classpath, matches actual deferred action identity at `name[2]`/`nation[4]`, excludes `rtk14Added`, applies universal strict-shape checks, splits valid grouped events while preserving unrelated ones, and fails closed on ambiguity — including the ambiguous future-row case that an earlier draft had tried to close inside V26. The importer rejects `appearanceYear > deathYear`; possession uses an explicit conditional-delete branch rather than a `takeIf` side effect.
- Docker PR #25 remediation replaces the indentation-only Compose scan with a rendered Compose JSON contract and gives the daemon-visible scenario mount a `COMPOSE_HOST_DIR` default. This is candidate-branch validation only.
- Focused evidence: importer 21, possession 21, and the Docker focused contract test are green; the deep repair-migration re-review is CLEARED. The former V26 focused count no longer applies, since V26 and its test are reverted to `origin/main`; that coverage moved into `V38Rtk14NpcLifecycleRepairMigrationTest` (9 cases — external-only scenario resolution, external-over-classpath precedence, per-nation deferred identity, duplicate future-appearance fail-closed, missing-scenario fail-closed, plus a new malformed-external-override rollback case), which was not re-run in this documentation pass. These focused results do not replace a final full gate for the current working tree.
- Remaining release gates: both source PR #356 and Docker PR #25 need three new sequential mention reviews on their new exact SHA, with any findings remediated; only then may they be merged, deployed, followed by `pep` reseed and live DB/API/UI/clock verification.
- Goal: use every officer row from `/Users/apple/Desktop/삼국지14 무장정보.xlsx` in every populated runtime scenario, preserving one-to-one duplicate identities, replacing the five stats, and applying birth/appearance/death lifecycle dates. Existing runtime-only officers remain with reviewed politics/charm overrides.
- In scope:
  - the local RTK14 source-data builder, validation/reporting, and private generated scenario artifacts;
  - runtime scenario tuple decoding and active/deferred appearance scheduling;
  - deferred NPC creation carrying politics/charm;
  - possession routing plus all affected API/UI five-stat surfaces;
  - source and orchestration deployment wiring for private generated scenarios;
  - tests, three sequential mention-triggered PR reviews with fixes, merge, deployment, and live verification.
- Non-goals: tracked generated Koei data, `legacy/**` or golden writes, unrelated archive universes under `data/extracted/scenario`, and secret disclosure.
- Allowed files: `tools/rtk14/**`, relevant scenario seed/import and deferred-NPC logic/tests in `infra/**`, `logic/**`, `app/game-engine/**`, affected `app/game-api/**` DTO/controller/tests, affected `web/game/**` and `web/gateway/**`, `.github/workflows/deploy.yml`, `tools/agent-system/check.py`, `.ai/{task,current-state,ownership,handoff}.md`, and one review artifact. The sibling Docker worktree may change only scenario mount Compose/deployer contract files.
- Acceptance evidence:
  - exactly 1,000 workbook rows are represented once per populated runtime scenario, including all duplicate-name rows; settings-only scenarios remain byte-identical;
  - five stats and birth/appearance/death lifecycle values round-trip through source JSON and generated tuples; no unresolved workbook row remains;
  - future appearances retain politics/charm and occur on the validated effective appearance year;
  - focused Python, logic, infra, engine, API, frontend, typecheck, static workflow, and diff gates pass with XML/tail evidence;
  - three fresh PR review rounds per PR have no unresolved `fix-required`; deployment and live DB/UI observations confirm the result.
- Approved external actions: create/update the private RTK14 GitHub Actions secret without printing it, commit, push, PR, three mention reviews, merge, deploy, and overwrite/reseed the target scenario as requested. Any broader data deletion remains prohibited.

---

## 2026-07-31 OPENSAM-34 — 배포 전 Go 조건 5종

- Status: the local grader and closeout documentation are complete, and the
  final independent re-review is `cleared`. Jira remains `할 일`. D4-31~35
  actual production observation is blocked/incomplete because the `ec2-prod`
  runner was observed offline in both repositories; it requires an EC2 resume
  signal and separate explicit user approval.
- User request (verbatim):
  - "권장 처리 순서대로 차례대로."
  - "계속."
- Jira acceptance:
  - D4-31 immutable image tag.
  - D4-32 authoritative seed code.
  - D4-33 self-hosted runner health.
  - D4-34 disk headroom.
  - D4-35 DB/Flyway migration state, including V29's concurrent index.
- Repository truth:
  - The current production control plane is the sibling
    `opensamguk-docker` repository; this repository's production compose and
    deploy script are compatibility-only.
  - Scenario seed truth is persisted in `ng_games.env.scenario_code`.
  - V29 is non-transactional, uses `DROP/CREATE INDEX CONCURRENTLY`, and
    requires the configured PostgreSQL session lock.
  - The local manual-only executable grader now covers all five Jira checks;
    no disk Go threshold is approved until an operator supplies the two
    explicit values for an authorized run.
- Approved local implementation:
  - Add a manual-only self-hosted workflow and a read-only grader that accept
    the expected immutable tag, scenario code, and operator-approved free-space
    thresholds as explicit inputs.
  - Compare safe server pins and running image references without printing
    unrelated environment values; prove runner execution, disk thresholds,
    `ng_games.env.scenario_code`, Flyway success/current version, and V29 index
    validity/readiness.
  - Add a hermetic shell contract test and a runbook with exact PASS/FAIL and
    redaction rules.
- Allowed files:
  - `.github/workflows/predeploy-go-check.yml`
  - `tools/ops/predeploy_go_check.sh`
  - `tools/ops/predeploy_go_check_contract_test.sh`
  - `docs/superpowers/runbooks/2026-07-31-opensam-34-predeploy-go-checklist.md`
  - `docs/superpowers/plans/2026-07-13-v1-stabilization-and-v2-open-plan.md`
  - `docs/superpowers/plans/2026-07-17-v2-ticket-backlog/02-plans-micro.md`
  - `docs/superpowers/reviews/2026-07-31-opensam-34-predeploy-go-review.md`
  - `.ai/task.md`, `.ai/current-state.md`, `.ai/known-issues.md`,
    `.ai/ownership.md`, `.ai/handoff.md`, `docs/superpowers/SESSION_HANDOFF.md`
- Acceptance evidence:
  - Shell syntax and hermetic contract tests cover all five Go/No-Go branches,
    redaction, invalid inputs, and fail-closed behavior.
  - The V29 migration IT is fresh with zero failures/errors/skips.
  - Independent critique ends `cleared` for the local grader.
  - The actual production observation remains `blocked`, never promoted to
    PASS from local simulation.
- Local completion record:
  - Both predeploy scripts passed `bash -n`; the hermetic contract, workflow
    YAML parse, and scoped untracked-file whitespace check passed.
  - Fresh migration Gradle evidence is V29 `2/0/0/0` and V32 `9/0/0/0`, with
    `BUILD SUCCESSFUL in 2m 2s`.
  - `scripts/agent/verify-changes.sh` classification ran, but `--run` was not
    rerun for OPENSAM-34. This ticket therefore makes no fresh whole-worktree
    execution claim.
  - The runbook and final independent review record the exact manual inputs,
    redaction/fail-closed contract, initial `fix-required` remediations, and
    final `cleared` review result.
- Human approval checkpoints:
  - EC2/prod access or workflow dispatch, `.env*` content access, production
    DB reads, commit, push, PR, merge, deploy, Jira mutation, data deletion,
    secrets, legacy/golden writes, and test weakening remain prohibited.
- Non-goals:
  - Resuming EC2, choosing disk capacity policy for the operator, changing a
    server pin/seed, applying a migration, pruning disk, or starting
    OPENSAM-149 before OPENSAM-34's production observation is authorized and
    complete.

---

## 2026-07-30 OPENSAM-33 — B2 운영 스모크

- Status: completed/released locally. Jira remains `할 일` because no separately
  authorized external transition was performed. OPENSAM-34 now has a local
  grader closeout; its actual production observation remains separately blocked.
- User request (verbatim):
  - "권장 처리 순서대로 차례대로."
- Jira acceptance:
  - D4-14 s1/QA 60초 cadence 축소 루프.
  - D4-15 Redis intake → daemon → authoritative read → browser-facing SSE
    관측 배선.
  - D4-16 no-op / false-deny / stale frontend 탐지 기준을 구체화하고
    executable smoke assertion으로 만든다.
  - D4-17 seed 중간 실패 뒤 부분 `world_state`가 남지 않고 재시도가
    skip되지 않는지 검증한다.
- Initial repository truth (before execution):
  - D4-17은 `ScenarioSeedCoordinator`의 단일 transaction과
    `ScenarioImporterIT`의 mid-import rollback→successful retry 회귀로 이미
    구현돼 있다. fresh rerun 전에는 완료로 승격하지 않는다.
  - Redis intake/daemon/flush/SSE 각 seam은 존재하지만 60초 local stack에서
    request→terminal→read→browser refresh를 한 실행으로 잇는 grader는 없다.
  - 기존 live E2E는 generic available command의 terminal success를 확인하나
    authoritative state delta나 browser SSE refresh를 요구하지 않아 silent
    no-op/stale UI를 놓칠 수 있다.
- Execution contract:
  - 0바퀴에서 기존 focused backend와 live-E2E contract를 먼저 측정한다.
  - 60초 cadence는 isolated local Compose에서만 활성화하며 production
    compose/default cadence를 바꾸지 않는다.
  - D4-16 기준은 최소한 (a) valid intake가 deterministic reject되지 않음,
    (b) terminal success 뒤 기대한 authoritative read delta 존재,
    (c) 실제 `turnCompleted` 뒤 브라우저 document refresh와 갱신된 read가
    함께 관측됨으로 고정한다.
  - TDD/contract RED를 먼저 관측한 뒤 가장 작은 smoke-harness 변경만 한다.
- Provisional allowed files:
  - `tools/e2e/local_v1_gate.sh`
  - `tools/e2e/local_v1_gate_timeout_contract_test.sh`
  - `web/game/e2e/v1-core-live.spec.ts`
  - `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/AdminWriteController.kt`
  - `app/game-api/src/test/kotlin/opensamguk/gameapi/controller/AdminWriteControllerTest.kt`
  - `docs/superpowers/plans/2026-07-13-v1-stabilization-and-v2-open-plan.md`
  - `docs/superpowers/plans/2026-07-17-v2-ticket-backlog/02-plans-micro.md`
  - `docs/loops/opensam-33-operational-smoke-2026-07-30/**`
  - `docs/superpowers/reviews/2026-07-30-opensam-33-operational-smoke-review.md`
  - `.ai/task.md`, `.ai/current-state.md`, `.ai/known-issues.md`,
    `.ai/ownership.md`, `.ai/handoff.md`, `docs/superpowers/SESSION_HANDOFF.md`
- Acceptance evidence:
  - Focused backend integration gates are fresh and Testcontainers skips are 0.
  - Isolated local Compose smoke proves 60-second cadence without reading local
    secrets or touching an existing project.
  - One correlated artifact chain shows accepted request ID, terminal success,
    authoritative read delta, and browser refresh after `turnCompleted`.
  - Seed mid-import rollback/retry is fresh green with zero skipped tests.
  - Independent critique ends `cleared` or gaps are explicitly quarantined with
    proof; no `fix-required` remains.
- Human approval checkpoints:
  - Commit, push, merge, deploy, Jira mutation, production/EC2 access, data
    deletion, secret access, legacy/golden writes, and test weakening remain
    prohibited.
- Non-goals:
  - Production cadence changes, production smoke/cutover, generalized
    observability platform work, or opening OPENSAM-34 before OPENSAM-33 is
    verified and released.
- Completion record:
  - Fresh focused XML evidence: `ScenarioImporterIT` 14/0/0/0,
    `RedisCommandStreamIT` 3/0/0/0, `IntakeResultChannelTest` 4/0/0/0, and
    `RealtimeRelayIT` 1/0/0/0. The marker remediation additionally reported
    focused unit 4/4 and Testcontainers IT 1/1 with skip 0; web typecheck and
    the local-gate shell contracts passed. A fresh focused rerun also passed
    shell syntax and timeout contract, web typecheck, `ScenarioMapSeedIT`
    8/0/0/0 (`BUILD SUCCESSFUL` in 2m), and `CommandReserveServiceTest`
    4/0/0/0 plus IT 1/0/0/0 (`BUILD SUCCESSFUL` in 1m 22s).
  - The final isolated Compose artifact is
    `/var/folders/34/jlnbkc0j6fj0nkcp7fj0f9h00000gn/T/opensamguk-op33-remediation.A4KNsK/live-gate-marker-fixed`.
    It records `che_요양` intake `202`, same-request reservation/execution
    `200`, durable Redis marker/XRANGE/XINFO/XACK/XPENDING evidence, three
    60-second ticks, authoritative `0/10/7`, and SSE → refresh → rendered DOM.
  - The initial independent `fix-required` findings were remediated: the raw
    `Instant` marker bind now uses `Timestamp.from`, the gate proves one
    matching wake/ACK chain, cleanup removes the exact project resources, and
    phase correlation plus the operational timeout are explicitly asserted.
  - The final independent review is cleared. The remaining QUESTION is nine
    EventSource opens versus eight `turnCompleted` events: reconnect/remount
    versus duplicate subscription is UNKNOWN and non-blocking for the
    stale-refresh acceptance criterion.
  - `scripts/agent/verify-changes.sh --run` executed once: five-module Gradle
    `BUILD SUCCESSFUL in 12m 55s`, 552 suites / 4,763 tests / failures 0 /
    errors 0 / skipped 1; `web/game` typecheck PASS and Vitest 46 files / 232
    tests PASS; `git diff --check` PASS. The wrapper exited 1 only because its
    strict checker found the two existing whole-worktree baselines: the
    user-owned `.codex/config.toml` personal model pin and the historical
    2026-07-27 review missing one anchored Scope/Verdict under the current rule.
  - Explicitly unexecuted: `tools/parity/gate.sh backend`, production deploy or
    EC2 access, commit/push/PR/merge, and Jira transition.
  - ADR-LITE-026 applies to a future PR: after creation, mention the review
    agent in the PR conversation for three separate rounds, fix and reverify
    each round, and merge only with explicit human approval.

---

## 2026-07-30 OPENSAM-32 — 외교 상태 전이 6종

- Status: completed/released locally. Jira remains `할 일` because no
  separately authorized external transition was performed.
- User request (verbatim):
  - "권장 처리 순서대로 차례대로."
- Jira acceptance:
  - D4-08 `che_종전제의`
  - D4-09 `che_종전수락` (`war → trade`)
  - D4-10 `che_불가침제의`
  - D4-11 `che_불가침수락` (non-aggression state + term)
  - D4-12 `che_불가침파기제의`
  - D4-13 `che_불가침파기수락` (`trade` 복귀 후 선전포고 가능)
- Execution contract:
  - Process each command as a separate parity-close slice; never batch an
    unverified production edit across all six.
  - Freeze PHP path+line, RNG/log/order/state evidence before any behavior edit.
  - Reproduce the current Kotlin reserved-command/message-accept/flush/UI path
    and record a zero-round baseline before forming one root-cause hypothesis.
  - Use TDD red→green for any missing behavior; if the Jira criteria are already
    met, make no speculative source change and close with fresh evidence.
- Initial allowed files (narrow further after reproduction):
  - `logic/src/main/kotlin/opensamguk/logic/actions/nation/CheJongjeonjeui.kt`
  - `logic/src/main/kotlin/opensamguk/logic/actions/nation/CheJongjeonSuak.kt`
  - `logic/src/main/kotlin/opensamguk/logic/actions/nation/CheBulgachimJeui.kt`
  - `logic/src/main/kotlin/opensamguk/logic/actions/nation/CheBulgachimSuak.kt`
  - `logic/src/main/kotlin/opensamguk/logic/actions/nation/CheBulgachimPagiJeui.kt`
  - `logic/src/main/kotlin/opensamguk/logic/actions/nation/CheBulgachimPagiSuak.kt`
  - directly corresponding logic tests/goldens (existing fixtures read-only)
  - `app/game-engine/**/DiplomaticMessageHandler*`
  - `app/game-engine/**/ReservedTurnHandler*`
  - `app/game-engine/src/test/kotlin/opensamguk/engine/intake/DiplomaticMessageWorldScopeIT.kt`
  - `infra/**/Diplomacy*` and the diplomacy-only `JdbcFlushExecutor` seam
  - `web/game/app/game/diplomacy/page.tsx`
  - `web/game/components/CommandModal.tsx`
  - `web/game/__tests__/CommandModal.form-spec.test.tsx`
  - `web/game/__tests__/DiplomacyPage.command.test.tsx`
  - `docs/loops/opensam-32-diplomacy-transitions-2026-07-30/**`
  - `docs/superpowers/reviews/2026-07-30-opensam-32-diplomacy-review.md`
  - `.ai/task.md`, `.ai/current-state.md`, `.ai/ownership.md`, `.ai/handoff.md`,
    `docs/superpowers/SESSION_HANDOFF.md`
- Acceptance evidence:
  - Six PHP command sources have exact path+line evidence and parity dimensions.
  - Proposal commands create the PHP-shaped diplomatic message action/payload.
  - Accept commands mutate both diplomacy directions with the exact target
    state/term and preserve the next-command constraint (`선전포고` after
    break-accept).
  - The actual reserved-ring → message response → daemon delta → JDBC flush
    path is covered by fresh tests, and the existing diplomacy UI submit path is
    observed or explicitly marked `채점대기`.
  - Independent critique ends `cleared` or a gap is
    `quarantined-with-proof`; no `fix-required` remains.
- Human approval checkpoints:
  - Commit, push, merge, deploy, Jira mutation, production/EC2 access, data
    deletion, secret access, legacy/golden writes, and test weakening remain
    prohibited.
- Non-goals:
  - General diplomacy redesign, invented status semantics, production rollout,
    or opening OPENSAM-33 before this ticket is verified and released.
- Completion record:
  - D4-10의 `che_불가침제의` shortcut이 backend catalog의 authoritative
    `destNationID/year/month` form을 소비한다. 조회 실패, row 누락, form
    누락은 모두 fail-closed하며 기존 scalar shortcut은 유지한다.
  - D4-08/10/12 proposal destination target color는 preload된 상대국
    color를 직렬화한다. destination 없는 격리 테스트는 기존 fallback을
    유지한다.
  - 실제 lifecycle proposal → message JDBC → accept → 양방향 diplomacy
    flush → 같은 월드 declaration을 Testcontainers 회귀로 연결했다.
  - Fresh focused gates: logic 72/72, game-engine 34/34, infra 2/2,
    frontend 16/16, frontend typecheck PASS; backend failure/error/skip 0.
  - Live browser는 필수 compose runtime 설정 부재로 `채점대기`이며,
    accept의 PHP 다중 로그/event 전체 패러티는 Jira 상태 전이 밖의 명시적
    후속 항목이다.
  - 독립 검토의 코드 finding은 모두 해소됐다. 최종 문서 정합화와 Agent OS
    verification 뒤 `Verdict: cleared`로 종결한다.
  - No commit, push, merge, deploy, Jira mutation, production access, data
    deletion, secret access, legacy/golden write, or test weakening occurred.

---

## 2026-07-30 Jira 권장 순서 직렬 실행 — OPENSAM-31 완료

- Status: completed/released locally; Jira remains `할 일`
  because no external status mutation was authorized or performed.
- User request (verbatim):
  - "권장 처리 순서대로 차례대로."
- Goal:
  - Process the agreed Jira order strictly sequentially:
    `OPENSAM-31 → 32 → 33 → 34 → 149 → 35`.
  - Finish and verify one ticket before opening the next ticket's write scope.
  - For OPENSAM-31, document one existing executable command and an objective
    PASS/FAIL criterion for each of seed, load/restart, intake, flush, read,
    SSE, and deploy.
- Approved plan:
  - Reconcile each Jira ticket with repository truth before changing files.
  - Prefer existing tests and scripts; do not invent evidence or weaken gates.
  - Record implementation, verification, and independent review evidence before
    moving to the next ticket.
- Allowed files for OPENSAM-31:
  - `docs/superpowers/plans/2026-07-13-v1-stabilization-and-v2-open-plan.md`
  - `docs/superpowers/reviews/2026-07-30-opensam-31-stabilization-checklist-review.md`
  - `.ai/task.md`, `.ai/current-state.md`, `.ai/ownership.md`, `.ai/handoff.md`
  - `docs/superpowers/SESSION_HANDOFF.md`
- Acceptance evidence:
  - D4-01 through D4-07 each name an exact repo-root command.
  - Each item defines observable success and failure, including Gradle XML
    requirements and Docker/Testcontainers skip handling.
  - Every referenced test class/script exists in the repository.
  - Documentation checks and independent review are recorded.
- Human approval checkpoints:
  - Commit, push, merge, deploy, production/EC2 access, data deletion, secret
    access, legacy/golden writes, and test weakening remain prohibited.
  - OPENSAM-34 production checks remain paused until the existing EC2 resume
    condition and separate operational approval are satisfied.
- Non-goals for OPENSAM-31:
  - Runtime behavior changes, executing a production deployment, Jira status
    mutation, or opening the write scope of OPENSAM-32 and later tickets.
- Completion record:
  - Active plan now contains exact D4-01~07 repo-root commands, objective
    PASS/FAIL rules, Testcontainers skip handling, and the production-approval
    boundary.
  - Independent review moved from `fix-required` to `cleared` after removing
    four assertion overclaims.
  - `scripts/agent/verify-changes.sh --run` was executed once against the whole
    dirty worktree. Fresh backend XML: 552 suites / 4,758 tests /
    failures 0 / errors 0 / skipped 1. The wrapper deleted its temporary
    Gradle log, so the literal `BUILD SUCCESSFUL` line was not retained.
  - `web/game` typecheck and 46 files / 227 tests passed; Codex Agent OS
    contract and `git diff --check` passed.
  - Strict checker remains non-green because of two pre-existing branch/worktree
    baselines: user-owned `.codex/config.toml` personal model pin and the
    historical 2026-07-27 review's uppercase `Verdict: CLEARED`, which no longer
    matches the current lowercase anchored-verdict rule.
  - No commit, push, merge, deploy, Jira mutation, production access, data
    deletion, secret access, legacy/golden write, or test weakening occurred.

---

## 2026-07-30 V2 실시간 전투 공통 기반 계획·티켓화

- Status: approved design; implementation planning in progress.
- User request (verbatim):
  - "커밋 및 푸시. 그리고 다음 단계들을 [$superpowers:brainstorming] 이용해서 물어봐서 설계후 지라 타켓과 깃허브 이슈로 넣어줘."
  - "전체 승인."
  - "좋아. 승인."
  - "승인."
- Goal:
  - Convert the approved realtime battle session/authority/order/replay spec into one executable common-foundation implementation plan.
  - Keep land, siege, naval, and 2.5D client as separately specified consumers of that foundation while retaining all three as V2 launch requirements.
  - After human plan approval, create the Jira OPENSAM target/Epic hierarchy and linked GitHub issues with Given-When-Then acceptance criteria.
- Approved design:
  - `docs/superpowers/specs/2026-07-30-v2-realtime-battle-session-command-replay-design.md`
  - Dedicated single-world `battle-engine`; authoritative 200ms session actor; WebSocket; durable events/snapshots; campaign handoff/result boundaries.
  - Commander + delegated officers, one human per formation, delayed authority/orders, limited pause, reconnect/AI/deputy succession.
  - Launch baseline 16 formations per side, 12–15 minutes; land/siege/naval required; deterministic headless fallback only for failed realtime gates.
- Allowed files:
  - `docs/superpowers/specs/**`, `docs/superpowers/plans/**`, `docs/superpowers/SESSION_HANDOFF.md`
  - `.ai/task.md`, `.ai/decisions.md`, `.ai/current-state.md`, `.ai/handoff.md`
  - Jira OPENSAM and the connected GitHub repository after plan approval
- Acceptance evidence:
  - Plan maps every common-foundation spec requirement to an exact file/interface/test task with no placeholders.
  - Independent adversarial plan review has no remaining `fix-required`.
  - Jira and GitHub items cross-link to the approved spec/plan and preserve foundation-first dependencies.
- Human approval checkpoints:
  - Implementation plan adoption before external tracker writes.
  - Any implementation, merge, deploy, production change, data deletion, secret access, legacy/golden write, or test weakening needs separate authority.
- Non-goals:
  - Runtime implementation, PR creation, merge/deploy, production/S6 activation, or changing v1 parity behavior.

---

## Previous tracked task contract

- Status: completed — 2026-07-26 감사의 모든 비운영 v1 blocker를 구현·관측했다. checker 수정 뒤 최종 `scripts/agent/verify-changes.sh --run`도 정확히 한 번 기록됐으며, whole-worktree strict 기준선의 유일한 error는 수정하지 않은 사용자 소유 `.codex/config.toml` personal model pin이다.
- Updated: 2026-07-29
- User request (verbatim):
  - "미완성 중 운영전환 제외하고 나머지를 완성시켜. 검증은 로컬 도커로 해."
- Goal:
  - Close every release blocker in audit §6 and every gate in §8 except production/S6 cutover.
  - Preserve PHP `legacy/devsam-core` as grand truth and use `hwe/ts/` only for frontend shape.
  - Prove command, monthly/event, battle/conquest, AI, side-system, world-scope/restart, frontend, and stored-log behavior through real PHP evidence and local Docker.
  - Update the Korean audit and loop ledger from `채점대기` to observed pass/fail evidence.
- Approved plan:
  - Freeze the new loop contract and measure Docker/PHP/browser baselines before implementation.
  - Build shared foundations sequentially, then implement disjoint behavior families with one hypothesis per loop.
  - Require RED reproduction or an explicit missing-path contract before each fix; never weaken goldens/tests.
  - Run targeted gates, full backend/frontend gates, local Docker smoke/restart/two-world checks, PHP capture, and authenticated browser E2E.
  - Obtain an independent adversarial review and remediate every `fix-required` finding.
- Allowed files:
  - `.ai/task.md`, `.ai/current-state.md`, `.ai/ownership.md`, `.ai/handoff.md`
  - `common/**`, `logic/**`, `infra/**`
  - `app/game-api/**`, `app/game-engine/**`
  - `web/game/**`
  - `tools/php-golden/**` capture scripts only; legacy and existing golden fixtures remain read-only
  - `tools/smoke.sh` and local test tooling only if a reproduced verification defect requires it
  - `docs/loops/v1-nonoperational-completion-2026-07-27/**`
  - `docs/superpowers/research/2026-07-26-v1-legacy-equivalence-audit.md`
  - `docs/superpowers/reviews/2026-07-27-v1-nonoperational-completion-review.md`
  - `docs/superpowers/SESSION_HANDOFF.md`
- Acceptance evidence:
  - Audit §6.1–§6.8 has no unresolved non-operational `정적대조`, `추론`, or `채점대기` blocker.
  - PHP 92-command cross-layer matrix reaches form/intake/daemon/flush/terminal/UI or records a PHP-proven non-user-facing exception.
  - Battle/conquest, monthly/event, AI 12-month, side-system, and stored-log parity use real PHP captures and deterministic replay gates.
  - Every world-owned read is process-world scoped; same-local-ID two-world and restart-rehydrate Docker tests pass.
  - All PARTIAL/DEAD core-live routes are exercised through authenticated local browser flows and terminal results.
  - `tools/parity/gate.sh backend`, both frontend gates, `./tools/smoke.sh`, PHP golden capture, and `scripts/agent/verify-changes.sh --run` have fresh evidence.
  - Independent review verdict is `cleared`.
- Human approval checkpoints:
  - Commit, push, merge, production deploy/cutover, data deletion, secret access, golden/test weakening, and legacy writes remain prohibited.
- Non-goals:
  - CQRS S6/production rollout, production data or infrastructure changes, v2 implementation, external tracker writes, and speculative divergence from PHP.

## 2026-07-29 완료 기록

- §6.1–§6.8의 비운영 항목은 PHP schema 4 12개월·36순 exact replay,
  92-command matrix, battle/event/AI/side-system capture, scoped read/restart,
  authenticated local Docker E2E로 종결했다.
- 표준 backend gate는 550 suites / 4,753 tests / failure·error 0,
  영향 backend 재검증은 185 suites / 1,172 tests / failure·error 0이다.
  `web/game`은 46 files / 227 tests + typecheck, runtime9 Playwright는
  1 passed / 0 unexpected다.
- independent review는 CLEAR / APPROVE / blockers none이다.
- production/S6, commit, push, merge, deploy, data delete, secret access는
  수행하지 않았다.
- checker의 cleared/quarantined disjoint Scope union 수정 뒤 최종 Agent OS
  verify를 정확히 한 번 실행했다. Gradle 5개 모듈은 `BUILD SUCCESSFUL in
  13m 27s` / 29 tasks, `web/game` typecheck + 46 files / 227 tests, Agent OS
  contract와 diff/whitespace는 PASS다.
- strict checker는 error 1 / warning 0이며 exit 1은 사용자 소유·untouched
  `.codex/config.toml` 최상위 personal model pin 하나뿐이다. 이는 비운영
  기능 완료와 다른 whole-worktree strict baseline이므로 strict green 또는
  ship/merge ready를 뜻하지 않는다. 증거:
  `.omo/evidence/v1-final/verify-changes-final2/{verify-changes.log,exit-code.txt}`.

---

## 2026-08-02 — canonical alphanumeric game-server ID release closeout

- PR: #354 — `https://github.com/peppone-choi/opensamguk/pull/354`.
- Status: the first Codex review found two valid issues against
  `8d1a64fee0651b2977f13af27eb6b91b43577342`. They were remediated in exact
  code SHA `1683100447be91abc6eb2629969c9ee3c16bac5e`; an independent re-review
  of that exact fixed code SHA is **CLEARED**. This source verdict does not
  satisfy the separate fresh three-round PR review requirement.
- Canonical public contract: accept raw `[A-Za-z0-9]+`, canonicalize to
  lowercase `[a-z0-9]+`, and derive the internal Compose/container identity as
  `s` + public ID. Examples: `pep` → `pep` / `spep`; `s1` → `s1` / `ss1`;
  `A1` → `a1` / `sa1`.
- `all`, `main`, and current top-level game route names are reserved to avoid
  control and URL collisions. `current` remains a valid public ID.
- First-review remediation:
  - compatibility Nginx now accepts canonical public paths and derives the
    internal `s<public>` upstream/container name only at that boundary;
  - deploy, promote, and reset workflows now apply matching reserved-public-ID
    guards instead of a one-off `all` check.
- Independent re-review evidence for the fixed SHA: gateway 64 tests; game 220
  tests; FrontInfo XML `27/0/0`; Admin XML `26/0/0`; and the fixed-SHA
  workflow/Nginx/Compose contract review.
- Approved external actions remain those of the governing OPENSAM-34 task:
  GCP changes/configuration, commit, push, PR creation, three mention-triggered
  reviews, merge, deployment, and live verification. They are authorized scope,
  not completed actions; secrets stay redacted and data deletion is out of
  scope.
- Remaining release gates: Docker repository review/fix; a new three-round
  `@codex` PR review sequence after the next documentation commit/current HEAD;
  then merge, deploy, and live-production verification. No final PR review,
  merge, deployment, or live result is claimed.

## 2026-08-02 OPENSAM-34 — GCP production migration and launch

- Status: in progress; GCP VM/runtime/network/DNS are provisioned, and PR
  review findings are being remediated before the approved merge and deploy.
- User-approved scope:
  - migrate active production GitHub Actions runner selectors and operator
    guidance from the retired EC2 target to GCP Compute Engine
    `e2-standard-2` / `gcp-prod`;
  - configure the GCP production control repository and generated runtime
    secrets without printing their values;
  - request three PR mention-triggered reviews, fix valid findings, then merge
    and deploy;
  - verify `sam.peppone.dev` through Cloudflare and live health routes.
- Approved external actions: GCP resource changes, production configuration,
  commit, push, PR creation, merge, and deployment. Secret values remain
  redacted and data deletion remains out of scope.
- Allowed repository files: production workflows, active deployment/operator
  documentation, the compatibility deploy script/compose header, and this task
  contract. No game logic, golden fixture, legacy source, image pin, or runtime
  secret may enter the diff.
- Completion evidence: matching online `gcp-prod` runners, three successful PR
  review rounds with no unresolved `fix-required`, green targeted syntax/diff
  checks, an observed deployment run, and live domain health.

---

## Previous completed task — documentation and agent harness refresh

- Status: completed — independent review cleared; docs-only verification passed except for the pre-existing `.codex/config.toml` personal-model baseline.
- Updated: 2026-07-25
- Goal: refresh the current documentation and agent harness, with `README.md` as the primary onboarding surface.
- Approved plan:
  - Update `README.md` to match the current July 2026 implementation, local quick start, CQRS consistency/runtime state, and shared/server production model.
  - Update `.claude/HARNESS.md` from its obsolete parity-only/deploy narrative to the current Agent OS, parity, verification, and deployment entry points.
  - Correct the comment-language and deployment wording in the tracked parity skills.
  - Refresh `docs/agent/project-overview.md` so README can link to a current bounded status source.
- Allowed files:
  - `README.md`
  - `.claude/HARNESS.md`
  - `.claude/skills/parity-close/SKILL.md`
  - `.claude/skills/parity-ship/SKILL.md`
  - `docs/agent/project-overview.md`
  - `docs/agent/lifecycle-ops.md`
  - `.ai/task.md`
  - `.ai/current-state.md`
  - `.ai/ownership.md`
  - `docs/superpowers/reviews/2026-07-25-docs-harness-refresh-review.md`
- Acceptance evidence:
  - No obsolete `V1..V10`, `appleboy/ssh-action`, unresolved `commandBlockMs` wiring-gap, or Korean-code-comment claim remains in the refreshed surfaces.
  - README quick start names required environment variables without exposing values or secrets.
  - Production guidance points to `opensamguk-docker` shared/server orchestration and clearly labels repository production compose/manual deploy as compatibility-only.
  - Parity guidance distinguishes immediate intake from turn-reserved ring admission and never authorizes commit/push/merge/deploy without separate human approval.
  - `git diff --check`, path/link checks, and `tools/agent-system/check.py` results are recorded.
- Human approval checkpoints:
  - Commit, push, merge, deploy, data deletion, or secret access remain prohibited without separate approval.
- Non-goals:
  - Runtime/code/schema changes, legacy or golden edits, production operations, and external tracker mutations.
