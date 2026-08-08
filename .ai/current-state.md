# Current State

## OPENSAM-35 V2-0A 격리 게이트 — 구현 완료 · final CodeRabbit 8 dispositions dirty-tree review `cleared` · remote exact-commit CI pending · release/deploy 미수행 — 2026-08-08

- 브랜치 `codex/op-35-v2-0a-final`은 `origin/main` `b847c351`에서 재구성했다. 계획 정본: `docs/superpowers/plans/2026-08-08-opensam-35-v2-0a-isolation-plan.md`. 사용자 결정은 계획 **채택 + S6까지 연속 실행**이다. 현재 PR [#370](https://github.com/peppone-choi/opensamguk/pull/370)은 열려 있고, merge/release/deploy/production 관측은 어느 것도 수행하지 않았다.
- **baseline과 현재 구현을 구분한다.** 구현 착수 전 리포 전역 grep에는 v2 런타임 코드가 0건이었다. 현재 브랜치에는 의도한 0A 격리 게이트만 있다: `V2SandboxGate`, 두 `V2SandboxConfiguration`, `V2ContentCatalog`, 그리고 `v2-lab` route/middleware 차단. v2 product leaf·schema·persistence는 여전히 없으며 OPENSAM-150 범위다. 그러므로 “0A-e에서 뺄 대상이 없다”는 것은 **착수 전 baseline**의 사실이지 현재 트리의 설명이 아니다.
- **티켓 본문 path:line 인용 11건 중 5건이 부정확**(행번호 오류뿐, 주장 자체는 유효). 계획서 §0.2에 대조표. 티켓이 경고한 "조용한 실패"는 실재한다 — v2가 `SCENARIO_CODE`/`SCENARIO_DIR`을 상속하면 `ignoreDefaultEvents=false` → `ScenarioImporter.kt:888` defaults 분기 → v1 기본 12행이 적재되고 v2 leaf는 0인데 부팅·시드·헬스체크는 전부 성공한다.
- **사용자 결정 C1** — `SCENARIO_SEED_ENABLED` 충돌(`coding-rules.md:12`의 production `false` CI 강제 vs proposal §7.1 `true`)은 **별도 스택 파일 분리**로 해소. `docker-compose.production.yml`·`application.yml`·`tools/agent-system/check.py` **전부 무수정**, 신규 `docker-compose.v2-sandbox.yml`을 만든다.
- **사용자 결정 C2** — 0A-e의 "s1 profile"은 이 리포에 정의체가 없다(compose 전체에 `profiles:` 키 0건; `s1`은 sibling `opensamguk-docker`의 `servers/<id>.env` 배포 인스턴스). 범위를 **이 리포로 한정**했다. [OPENSAM-177](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-177)은 shared account/JWT/profile live integration을 맡는 연결된 consumer 티켓이며, 그 실행·release·deploy는 OPENSAM-35에서 수행하지 않았고 완료로 주장하지 않는다.
- **S0(U12) 완료 — PASS, 단 치환 semantics.** `SPRING_FLYWAY_LOCATIONS` env 오버라이드는 동작하나 리스트를 **병합하지 않고 통째로 교체**한다. v1 location 포함 시 V1~V38+프로브 39행 전부 적용(V26·V38 `.kt` JDBC 포함), v1 location 누락 시 1행만 남고 JPA `ddl-auto: validate`가 `missing table [banned_member]`로 **fail-closed 부팅 실패**(조용히 통과하지 않는다). 증거: `docs/loops/opensam-35-v2-0a-2026-08-08/u12-flyway-locations-measurement.md`. → **0A-c는 env 오버라이드 경로로 확정, `application.yml` 무수정, 게이트 ⑤ 유지.**
- **T2 사전선언 확정 = 공집합**(계획서 §4). S1~S6 산출물이 전부 신규 파일이므로 게이트 ③의 기대값은 빈 출력이다. T2 기존 파일 수정이 불가피해지면 우회하지 말고 멈추고 계획 개정 + 사람 승인.
- **Round 3 P2 scope는 T2와 별개로 명시 승인됐다.** 두 P2는 세 source item으로 구현됐다: existing `docker-compose.v2-sandbox.yml`의 `web-game` build arg `ASSET_PREFIX=/game`, 신규 `tools/ops/v2_sandbox_compose_contract_test.sh`의 rendered-Compose fail-closed assertion, 그리고 existing `.github/workflows/ci.yml` `agent-system` job의 `Verify v2 sandbox compose contract` step(동일 script 실행)이다. GitHub PR head `70492bcc`의 green CI에는 original contract step이 포함됐다. source lane의 job-level `permissions: { contents: read }`, commented/missing invocation mutation rejection, 및 final-8 docs changes는 그 뒤 local validation으로 보고됐고 terminal independent final-8 dirty-tree re-review는 no findings로 `cleared`했다. Remote PR CI for an exact final-remediation commit is still unobserved. 세 항목 모두 T2 paths 밖이므로 plan §4.0a의 canonical existing/approved-file list와 전체 diff가 범위를 판정한다.
- **S1 완료 — Flyway는 location을 재귀 스캔한다(실측).** v1이 `classpath:db/migration`만 잡아도 `db/migration/v2/V901`이 `success=t`로 적용됐다 → v2는 **형제 경로** `classpath:db/migration_v2`로 확정. 운영 pair는 `classpath:db/migration,classpath:db/migration_v2`이며 U12의 `filesystem:` probe는 historical/abandoned다. U4-d 침묵 경로 발견: v2 마이그레이션을 이미 실은 DB 위에서 v1 스택은 WARN만 내고 정상 부팅한다 → `GAME_DATABASE_URL` 분리가 권고가 아니라 **제1 방어선**. 버전 정책은 P1(DB 분리 + V900 대역) 잠정 확정. 증거: `s1-flyway-location-measurement.md`.
- **S2 완료** — `V2SandboxGate`(상수 1곳) + `@Profile("v2-sandbox")` AND `@ConditionalOnProperty("v2.enabled"=true)` 이중 조건. 5행 조합표 + 변이 프로브. 증거: `s2-conditional-bean-gate.md`.
- **S3 완료** — (a) `V2ContentCatalog` read-only 로더(`@Component` 없음, 러너 없음, `*.json` 비재귀). (b) `web/game/app/game/v2-lab/**` + **`middleware.ts` 수정**(이 티켓의 유일한 승인된 기존 파일 수정). `notFound()`만으로는 `/game/**` 레이아웃이 `AuthGate` 클라이언트 컴포넌트라 HTML 셸이 먼저 flush돼 **soft 404(HTTP 200)** + RSC 페이로드에 v2 내용 유출 → 렌더 선행 계층인 미들웨어로 닫음. serverId rewrite 분기(`/game/pep/v2-lab`) 우회도 실효 경로 기준 판정으로 봉쇄. 증거: `s3a-content-v2-loader.md`, `s3b-v2lab-route.md`.
- **S4 완료** — game-engine/game-api 각각 production 컨텍스트 v2 빈 0 IT. 하드코딩 타입 대신 **패키지 기반 스캔**(`opensamguk.*.v2.*`) 8칸 매트릭스. 프로브 P-3(게이트 밖 `@Component`)이 3칸을 실패시켜 비공허성 입증. 증거: `s4-production-context-bean-gate.md`.
- **S5 완료** — 신규 `docker-compose.v2-sandbox.yml`(C1 결정대로 분리). v2 game-api/engine은 전용 DB·Redis·world·scenario를 쓰고, ADR-LITE-023에 따라 계정/JWT issuer/profile writer는 external shared v1 `gateway-api` 하나만 사용한다. 고유 `shared-gateway-relay`만 shared network에 붙고 required `SHARED_GATEWAY_UPSTREAM`으로 기존 gateway를 프록시하며, v2 web/nginx/game 서비스는 v2 network에만 남는다. shared network/upstream/profile volume/JWT와 v2 world 입력은 `:?` fail-closed. 증거: `s5-v2-stack-env-separation.md`.
- **S5 leaf 계약 정정 승인(ADR-LITE-029)** — OPENSAM-35는 v2 전용 probe 이벤트 2행 존재 + v1 기본 12행 미적재(0)를 판정한다. 실제 v2 leaf는 이 티켓의 비범위인 OPENSAM-150 필수 수용 기준으로 이관했다. 존재하지 않는 leaf를 0A에 날조하지 않는다.
- **S6 baseline과 current backend evidence를 구분한다.** baseline artifact 5종(`a1-v1-flyway-migration-sha256.txt`·`a1-v1-schema-dump.sql`·`a2-scenario-seed-sha256.txt`·`a3-php-golden-inventory-sha256.txt`·`a4-backend-gate-xml-summary.txt`+로그)과 `baseline/MANIFEST.md`가 존재한다. historical 599 suites / 5,023 tests와 web 54 files / 288 tests는 과거 record다. A4 backend log는 Java 21 `--rerun-tasks` full gate **one run / no retry** 결과(`BUILD SUCCESSFUL in 12m 35s`, 35 tasks, 601 suites / 5,050 tests / failures·errors 0 / skipped 1)이고 SHA256은 `a35ea5cf8352e2fe518daa32dbe95343f92bf62c95dc41a3673e924aa9fcaad1`다. A4 summary header는 historical Round 1 evidence로 정정됐고 SHA256은 `7d497d7423bc861e41e1bbb8a6418d66585d3e69d0e53b908653449a1f845e82`로 MANIFEST에 재기록됐다. historical dirty-tree review(`3c1b357c…`)와 local immutable-SHA review(`54ead4e7…`)는 별도 완료 evidence이며, 후속 Round 3 independent dirty-tree re-review가 두 Codex P2를 `cleared`했고 terminal final-8 dirty-tree re-review는 8 dispositions를 no findings로 `cleared`했다. 어느 local review도 remote exact-commit CI를 대체하지 않는다.
- **과거 GATE-f/f2/f3와 현재 PR review를 구분한다.** GATE-f와 GATE-f2는 pre-PR 적대적 snapshot(`fix-required`)이고, GATE-f3는 그 false-green 문서/코드 remediation 단계다. 이들은 PR #370의 review disposition 또는 release verdict가 아니다. 당시 canonical merge-base glob 재측정·빈 T1/T2/config subgate 결과는 역사적 evidence로 보존한다.
- **현재 controlling review stage: terminal final-8 dirty-tree review = `cleared`.** CodeRabbit Round 1의 23 threads는 resolved/dispositioned이고 historical dirty-tree review(`3c1b357c…`), local immutable-SHA review(`54ead4e7…`), Round 3 P2 clearance는 모두 historical evidence다. final CodeRabbit 8-item ledger는 A4/MANIFEST/S1/S3b/U12 docs remediation, source lane CI permissions/active-invocation remediation, 그리고 inapplicable PHP replay disposition을 기록하며 terminal independent review에서 all resolved/no findings로 `cleared`됐다. Canonical merge-base T1 diff는 empty이고 PHP replay pass/waiver를 주장하지 않으므로 OPENSAM-35는 blocked가 아니다. GitHub PR head `70492bcc`의 `agent-system`·`jvm`·`web (gateway)`·`web (game)` jobs는 green이었고 `agent-system`에는 original contract step이 포함됐다. 이는 final-8 dirty permission/active-matcher/docs remediation 전 evidence이며 exact final-remediation commit의 remote run은 미관측이다. CodeRabbit Round 2는 rate-limited request라 result가 없고, PR review mentions=3은 세 submitted results—CodeRabbit Round 1(23), Codex Round 3(2), final CodeRabbit incremental(8)—을 뜻한다; local reviews는 별도다.
- **historical verifier 범위:** `scripts/agent/verify-changes.sh --run`의 2026-08-08 실행은 forced backend subset 4 root에서 286 suites / 1,652 tests / failures 0 / errors 0 / skipped 1을 관측했다. 이는 뒤의 six-root A4 run보다 앞선 historical subset이다. 같은 실행에서 frontend는 의존성 부재로 `tsc: command not found`라 typecheck 실패·tests 미실행이었고, Compose는 `JWT_SECRET` required-variable 오류로 fail-closed했다. 그 오류는 syntax/config pass가 아니다.
- **후속 frontend 관측:** 위 verifier의 historical dependency failure 뒤 current frontend typecheck는 green이고, Vitest JSON은 132 suites / 288 tests / failures 0을 보고했다. 이것은 R1-22/R1-23을 닫는 focused frontend evidence이며, current backend evidence와 함께 remote exact-SHA CI 또는 release authorization을 대체하지 않는다.
- **Round 1 문서 snapshot:** canonical merge-base `:(glob)…/**` remeasurement에서 T1·T2·config·C1 네 subgate는 모두 빈 출력이고 `git diff --check`도 통과했다. baseline artifact 7개 sha256은 MANIFEST와 일치하며 owned Markdown fence는 tagged다. Final CodeRabbit terminal review가 cleared됐으므로 `tools/agent-system/check.py --strict --base origin/main --format json`은 unresolved-Verdict finding 없이 통과해야 한다.
- **잔여:** separately authorized commit/push 뒤 final-8 permission/active-matcher/docs remediation을 포함한 exact-commit remote PR CI 관측, 그리고 별도 승인된 merge·release/deploy·Jira action이다. OPENSAM-177 consumer 실행도 별도다.

---

## OPENSAM-33 완료 · OPENSAM-34 GCP 대체 — 2026-08-06

- **OPENSAM-33 = `완료`.** Jira 전이 실행됨(`할 일` → `완료`, 증거 코멘트 첨부). D4-14~17 로컬 증거는 2026-07-31자 아래 절이 정본이며, 이번 세션은 전이와 증거 기록만 수행했다.
- **OPENSAM-34 blocker 해소 — 단, Go 아님.** "`ec2-prod` 러너 offline이라 관측 불가"라는 기존 판단은 오진이었다. GitHub runner API 실측 결과 프로덕션 러너는 `gcp-prod-opensamguk` 1대이며 **online / busy=false**, 라벨 `[self-hosted, Linux, X64, gcp-prod]`, `ec2-prod` 라벨 없음. 실제 원인은 `predeploy-go-check.yml`만 없어진 `ec2-prod` 라벨을 계속 선택한 것이고 `promote-game-server.yml`·`reset-game-server.yml`·`deploy.yml`은 이미 `gcp-prod`로 이관돼 있었다.
- **별건 grader 결함 동시 수정.** `tools/ops/predeploy_go_check.sh`의 `highest_checkout_migration()`이 `infra/src/main/resources/db/migration/V*__*.sql`만 스캔해 Kotlin 마이그레이션 `infra/src/main/kotlin/db/migration/V38__rtk14_npc_lifecycle_repair.kt`를 보지 못했다. 실측 `.sql`만 **37**, Kotlin 포함 **38**. V38은 `BaseJavaMigration` 서브클래스이고 `getVersion` 오버라이드가 없어 Flyway가 클래스명에서 버전 `38`을 유도해 `flyway_schema_history`에 기록한다. 양방향으로 틀린다 — V38이 적용된 DB에서는 기대 37 vs 실제 38로 **false NO-GO**, V38이 아직 미적용인 DB에서는 기대 37 == 실제 37이라 미적용 마이그레이션을 못 보고 **false GO**. glob을 `.sql`+`.kt`로 확장해 둘 다 닫았다.
- 부수 수정 2건: 버전 파싱이 `(( ))` 좌항에 있어 zero-padded `V08`이 8진수 오류로 **조용히 skip**되던 것을 `10#` 강제로 수정, 계약 테스트의 하드코딩 `36`/`35` 스텁을 checkout 파생값으로 교체(마이그레이션 추가 때마다 계약이 깨지던 원인). **정정:** `10#`는 활성 버그 수정이 아니다 — 현 저장소에 zero-padded(`V0*`) 마이그레이션은 없고 잠재 결함만 방어한다. 이전 판의 "실측 확인" 서술은 과장이라 삭제했다.
- **철회했던 가드를 되돌렸다 (정정).** 이전 판은 `[[ -d "$KOTLIN_MIGRATIONS_DIR" ]]` fail-closed 가드를 "디렉터리 부재 = Kotlin 마이그레이션 부재이므로 37 기대가 맞고, 이 가드는 정상 스택에 영구 false NO-GO를 만든다"는 이유로 제거했다고 기록했다. **그 근거는 사실과 다르다.** 해당 디렉터리는 git 추적 대상이다(`git ls-files infra/src/main/kotlin/db/migration` → `V26__npc_lifecycle_phase_units.kt`, `V38__rtk14_npc_lifecycle_repair.kt`). 정상 체크아웃에는 항상 존재하므로 영구 false NO-GO는 발생할 수 없고, 반대로 `shopt -s nullglob`(`:80`) 때문에 경로가 없거나 stale하면 스캔이 에러 없이 `.sql`-only 최댓값으로 퇴화한다. 격리 재현: 정상 경로 `38` vs stale 경로 `37`(에러 없음, NO-GO 없음) → 미적용 V38을 안고 **false GO**. 가드를 `:155-159`에 사유 주석과 함께 복원했다. 기존 `(( highest > 0 ))`는 "숫자 마이그레이션 0개"만 덮을 뿐 이 케이스를 덮지 못한다. 이 가드를 덮는 실행 테스트는 없다(계약 테스트가 실제 `REPO_ROOT`를 쓰고, 경로를 env로 열면 프로덕션 grader에 주입면이 생긴다) — 회귀 방어는 리뷰뿐이다.
- 브랜치 `ops-predeploy-gcp-retarget`(커밋 `9da40167`·`cd46da0f`·`f8f078ad`, 3파일 + `.ai` 문서) → **PR #364로 `main`에 squash 머지됨(`d950435b`, 2026-08-08)**. 교차 프로바이더 독립 재검토는 머지 전 수행되지 않았다. 증거: `bash -n` 양쪽 PASS · 워크플로 YAML 파싱 PASS(`runs-on` = `self-hosted/Linux/X64/gcp-prod`) · hermetic 계약 테스트 `PASS: predeploy-go-check hermetic contract` EXIT=0 · 변경 전 baseline(`git stash`)에서 동일 테스트가 `NO-GO: D4-35 latest successful Flyway version does not match the checkout`로 실패함 확인.
- **미실행/미승인:** D4-31~35 실제 production 관측, 워크플로 dispatch, 배포. 머지된 것은 grader/워크플로 수정뿐이므로 Jira OPENSAM-34는 `할 일` 유지이며, 종결은 dispatch 후 5항목 GO 판정으로만 가능하다. 아래 2026-07-31 OPENSAM-34 절의 "ec2-prod offline이라 blocked" 서술은 이 절이 대체한다.

---

## RTK14 전체 장수·5능력치 — **머지 완료** (PR #356, 2026-08-04)

- 아래 2026-08-04 절은 머지 직전 후보 브랜치 기준 기록이다. **PR #356은 2026-08-04T06:39:59Z에 머지됐고**, `origin/main`이 `V37__general_owner_claim_request.sql`과 `V38__rtk14_npc_lifecycle_repair.kt`를 모두 싣고 있다. 후속 수정 #362(리셋 턴 주기 시드 반영)·#363(리셋 시나리오 옵션 5개 시드 반영)까지 머지됨. "미커밋" / "release step 미완" 서술은 이력으로만 읽을 것.

## RTK14 전체 장수·5능력치 배포 준비 — 2026-08-04 (이력)

- 엑셀 1,000행의 15개 열을 비공개 source JSON으로 round-trip하고, 15개 populated 런타임 시나리오마다 장수 번호 1–1000을 정확히 한 번씩 표현한다. 생성물과 원본은 gitignored이며 private GitHub Actions secret만 등록됐다.
- 기존 장수는 소속·도시·관직·대사를 유지하면서 통솔·무력·지력·정치·매력과 생년·등장년·몰년을 갱신한다. 엑셀에만 있는 343명은 빙의 가능한 기본 장수로 추가한다. 시나리오 전용 legacy-only 351행은 모두 근거가 있는 정치·매력 override를 사용하며, 동명이인 source 후보 소진 충돌 38건은 exact runtime identity override로 처리하고 미검토 fallback은 fail-closed다.
- exact prior source HEAD `725195fea29b3434cc358e3d262c6c440830dab7`의 리뷰는 released-V26 forward-repair gap을 P1으로 판정했다. 현재 working tree는 **V26을 전혀 건드리지 않는다**: `V26__npc_lifecycle_phase_units.kt`와 `V26NpcLifecycleMigrationTest.kt`는 origin/main으로 byte-for-byte 되돌렸고, RTK14 lifecycle repair는 전부 새 world-scoped migration `V38__rtk14_npc_lifecycle_repair.kt`(test: `V38Rtk14NpcLifecycleRepairMigrationTest.kt`) 하나로 모았다. 따라서 이미 V26을 지난 월드에 별도 future repair가 필요하다는 이전 제한은 더 이상 유효하지 않다. 단, V38 자체의 실행·배포·live 결과는 아직 없다.
- 마이그레이션 번호 정리: claim-request migration은 `V36__general_owner_claim_request.sql` → **`V37__general_owner_claim_request.sql`**로 renumber했다. origin/main이 이미 `V36__diplomacy_casualties.sql`을 싣고 있어 V36이 둘이면 Flyway가 duplicate version으로 실패하기 때문이다.
- V26 확장이 아니라 V38인 이유: 이미 `flyway_schema_history`에 V26을 기록한 DB는 V26을 절대 재실행하지 않으므로 V26을 확장해도 업그레이드된 월드에는 닿지 않는다. 별개로 fresh DB에서는 Flyway가 `ScenarioSeedRunner`(`ApplicationRunner`)보다 먼저 돌아 `world_state`가 비어 있고 V26은 즉시 반환하므로 신규 월드에서도 그 확장은 도달 불가였다. 아직 어떤 월드도 기록하지 않은 V38에 repair 전체를 두는 것이 이미 마이그레이션된 월드와 새로 시드된 월드를 같은 최종 상태로 수렴시킨다.
- V38은 world-scoped이며 모든 월드에서 실행된다. external-over-classpath effective scenario를 사용하고, `name[2]`/`nation[4]`의 실제 action identity로만 매칭하며, `rtk14Added`를 제외한다. universal strict-shape checks가 불완전한 legacy event를 보존하고, 검증된 grouped event는 appearance year별로 분할하며, ambiguous identity는 fail-closed한다.
- CodeRabbit remediation: ambiguous future row 선택 문제는 V26 확장이 아니라 V38의 duplicate future-appearance fail-closed로 닫았고, importer는 `appearanceYear > deathYear`를 import 전에 거부하며, possession의 conditional reservation delete는 `takeIf` 부수효과 대신 명시적 branch로 수행한다. V37 request-id reconciliation, `general_ex` RNG isolation, typed tuple-24 marker, and shared effective-scenario resolution remain in scope.
- Docker PR #25는 weak indentation scan을 rendered Compose JSON contract로 대체하고 daemon-host relative mount 문제를 `COMPOSE_HOST_DIR` default로 닫는다. 이는 candidate-branch validation이며, this remediation의 merge/deploy/live completion 주장이 아니다.
- Focused evidence: importer 21, possession 21, and the Docker focused contract test are green; the deep repair-migration re-review is CLEARED. V26 evidence no longer applies to this branch because V26 and its test are reverted to origin/main. The repair coverage now lives in `V38Rtk14NpcLifecycleRepairMigrationTest` (9 cases: external-only scenario resolution, external-over-classpath precedence, per-nation deferred identity, duplicate future-appearance fail-closed, missing-scenario fail-closed, plus a new malformed-external-override rollback case); it has not been re-run in this documentation pass. Earlier backend-wide evidence predates these working-tree fixes and is not a final full-gate result for them.
- Remaining: source fix commit/push → source PR #356 and Docker PR #25 each receive three new sequential exact-SHA mention reviews and any required fixes → merge → deploy → `pep` reseed → live DB/API/UI/clock verification. None of those release steps is complete.

---

## OPENSAM-34 predeploy Go conditions — local grader ready; external observation blocked (2026-07-31)

- D4-31~35 local grader, manual-only workflow, runbook, and final independent
  review are complete. The re-review is `cleared`; this is a local contract
  conclusion, not a production Go decision.
- Jira remains `할 일`. The actual `ec2-prod` runner was observed offline in
  both this repository and sibling `opensamguk-docker`, so D4-31~35 actual
  observations remain blocked/incomplete and are not promoted from simulation.
- Local evidence: both scripts `bash -n` PASS; hermetic contract PASS; YAML
  parse PASS; scoped untracked-file whitespace PASS; fresh Gradle V29
  `2/0/0/0` and V32 `9/0/0/0`, `BUILD SUCCESSFUL in 2m 2s`.
- The first independent review's ref-ordering, integer/health, read-only SQL,
  canonical `serverId`, `df`, and exact V29-index findings were remediated;
  final re-review confirmed them intact.
- `scripts/agent/verify-changes.sh` classification ran, but `--run` was not
  rerun for OPENSAM-34. Production/EC2 access, workflow dispatch, `.env*` or
  secret access, commit, push, PR, merge, deploy, Jira mutation, data deletion,
  legacy/golden writes, and test weakening did not occur.
- Tooling baseline: repeated generic Fablize tool-failure notices during
  successful read-only discovery are isolated external-tool observations, not
  grader or production evidence. Direct scoped command evidence is authoritative
  for this closeout.
- Local OPENSAM-34 file ownership is released. The next action is only an EC2
  resume followed by explicit user approval and manual workflow inputs; it is
  not authorized by this closeout. ADR-LITE-026 still requires three separate
  PR-conversation review-agent rounds plus explicit human merge approval.

## OPENSAM-33 B2 운영 스모크 — 로컬 완료/released (2026-07-31)

- Jira: `할 일`, D4-14~17. 외부 Jira 전이는 실행하지 않았으며, 다음 순서의
  OPENSAM-34 local grader closeout is recorded above; its production observation
  remains separately blocked.
- Final isolated artifact:
  `/var/folders/34/jlnbkc0j6fj0nkcp7fj0f9h00000gn/T/opensamguk-op33-remediation.A4KNsK/live-gate-marker-fixed`.
  `che_요양`은 `202` intake 뒤 동일 request ID로 reservation/execution `200`을
  받았고, durable marker·XRANGE·XINFO·XACK·XPENDING=0가 같은 Redis entry를
  증명했다.
- 60초 cadence의 세 snapshot은 successful ticks `2 → 3 → 4`, 각각
  failures/consecutive failures `0`이었다. Authoritative read는
  injury/experience/dedication `0/0/0 → 0/10/7`; `turnCompleted` 뒤
  front-info refresh와 DOM `명성=전무 (10)`, `계급=30품관 (7)`를 관측했다.
- Focused fresh evidence: `ScenarioImporterIT` 14/0/0/0,
  `RedisCommandStreamIT` 3/0/0/0, `IntakeResultChannelTest` 4/0/0/0,
  `RealtimeRelayIT` 1/0/0/0; marker lane unit 4/4 + Testcontainers IT 1/1
  skip 0; typecheck and shell contracts PASS. Fresh rerun: shell syntax+timeout
  contract PASS, web typecheck PASS, `ScenarioMapSeedIT` 8/0/0/0 (`BUILD
  SUCCESSFUL` in 2m), and `CommandReserveServiceTest` 4/0/0/0 plus IT 1/0/0/0
  (`BUILD SUCCESSFUL` in 1m 22s).
- Initial `fix-required` findings (polling-only wake proof, leftover isolated
  resources, missing phase correlation, insufficient timeout) were remediated.
  Final independent review is cleared.
- QUESTION (non-blocking for stale-refresh): 9 EventSource opens / 8
  `turnCompleted`; reconnect/remount versus duplicate subscription is UNKNOWN.
- One `scripts/agent/verify-changes.sh --run` result: five-module Gradle
  `BUILD SUCCESSFUL in 12m 55s`, 552 suites / 4,763 tests / failures 0 /
  errors 0 / skipped 1; `web/game` typecheck PASS and Vitest 46 files / 232
  tests PASS; `git diff --check` PASS. Wrapper exit 1 comes only from strict
  checker baselines: user-owned `.codex/config.toml` personal model pin and the
  historical 2026-07-27 review lacking one anchored Scope/Verdict under the
  current rule.
- Explicitly unexecuted: `tools/parity/gate.sh backend`, production deploy or
  EC2, commit/push/PR/merge, and Jira transition. Generic Fablize read-command
  notices are the documented external tooling baseline, not product evidence.

## OPENSAM-32 외교 상태 전이 6종 — 로컬 완료 (2026-07-30)

- Jira: Highest / `할 일`, D4-08~13.
- PHP oracle과 fresh baseline 뒤 D4-10 shortcut form 누락, proposal
  destination color 불일치, 분리 seam의 lifecycle 과장을 RED로 확인해
  최소 수정했다.
- `che_불가침제의` pinned modal은 server catalog form을 사용하고 lookup,
  matching row, matching form 누락 시 fail-closed한다.
- 세 proposal resolver는 preload된 상대국 color를 message payload에
  사용한다.
- Testcontainers IT가 실제 proposal → DB message → accept → 양방향 flush
  → 동일 월드 declaration을 연결한다.
- Fresh focused evidence: logic 72/72, engine 34/34, infra 2/2, frontend
  16/16, typecheck PASS; backend failure/error/skip 0.
- live browser는 compose 필수 runtime 설정 부재로 `채점대기`; accept
  다중 로그/event 전체 PHP 패러티는 Jira 상태 전이 밖 후속 항목이다.
- 독립 코드 재검토 finding은 모두 해소됐고 문서 정합화 후 최종 판정은
  `cleared`다. Jira 전이와 git/배포/production 작업은 수행하지 않았다.
- OPENSAM-32 소유권은 released. OPENSAM-33 write scope는 새
  `$os-start-task` 계약 전까지 닫혀 있다.

## OPENSAM-31 v1 안정화 체크리스트 — 로컬 완료 (2026-07-30)

- 사용자 승인 순서: `31 → 32 → 33 → 34 → 149 → 35`.
- active-plan에 D4-01~07의 정확한 repo-root 명령, 객관적 PASS/FAIL,
  Docker/Testcontainers skip 처리, production 승인 경계를 기록했다.
- 독립 검토의 D4-03/05/06/07 과장 주장을 모두 제거했고 최종 판정은
  `cleared`다. browser-facing SSE 전달과 열거한 7개 명령의 fresh 개별
  실행은 증거로 승격하지 않았다.
- whole-worktree `verify-changes --run` 1회 결과: fresh backend XML
  552 suites / 4,758 tests / failure·error 0 / skip 1; `web/game`
  typecheck + 46 files / 227 tests; Agent OS contract와 diff check PASS.
  임시 Gradle 로그가 스크립트 종료 때 삭제돼 `BUILD SUCCESSFUL` 문구는
  별도 보존되지 않았다.
- strict checker의 두 error는 현 작업 밖 baseline이다:
  user-owned `.codex/config.toml` model pin과 historical 2026-07-27 review의
  uppercase `Verdict: CLEARED`/현재 lowercase anchor 규칙 불일치.
- EC2/prod, commit/push/merge/deploy, Jira 상태 변경은 수행하지 않는다.
- Jira OPENSAM-31은 외부 전이 권한이 없어 계속 `할 일`이다.
- OPENSAM-31 소유권은 released. 다음 write scope는 새 `$os-start-task`
  계약 뒤 OPENSAM-32로만 연다.

## v1 비운영 미완성 폐쇄 — 완료 (2026-07-29)

사용자 지시 `"미완성 중 운영전환 제외하고 나머지를 완성시켜. 검증은 로컬 도커로 해."`의
비운영 범위가 완료됐다. 감사 §6.1–§6.8과 §8의 비운영 차단은 구현·재측정했고,
v1은 **상순·중순·하순, 연 36순**을 유지한다(ADR-LITE-024).

- 감사/종결: `docs/superpowers/research/2026-07-26-v1-legacy-equivalence-audit.md`
  2026-07-29 최종 부록, `docs/superpowers/reviews/2026-07-27-v1-nonoperational-completion-review.md`
- PHP: schema 4 fresh A/B 12개월·36순 capture byte-identical; Kotlin authoritative
  replay 1/0/0/0. 경로: `.omo/evidence/v1-ai-production/`.
- gates: backend 550 suites / 4,753 tests / failure·error 0; 영향 backend 185 suites /
  1,172 tests / failure·error 0; `web/game` 46 files / 227 tests + typecheck.
- local Docker: runtime9이 가입/로그인, join `202 → RESOLVED`, 후속 예약/거절,
  14 DOM route, engine restart 뒤 command/general/repository persistence를 관측했다.
- 독립 review: CLEAR / APPROVE / blockers none.
- **제외·미수행:** CQRS S6, production canary/expand/backfill/capacity, live EC2
  cutover, commit/push/merge/deploy/data delete/secret access.
- checker의 cleared/quarantined disjoint Scope union 수정 뒤 최종 Agent OS
  `scripts/agent/verify-changes.sh --run`을 정확히 한 번 실행했다. Gradle 5개
  모듈은 `BUILD SUCCESSFUL in 13m 27s` / 29 tasks, `web/game` typecheck + 46
  files / 227 tests, Agent OS contract와 diff/whitespace는 PASS다.
- strict checker는 error 1 / warning 0이며 exit 1의 유일한 원인은 수정하지 않은
  사용자 소유 `.codex/config.toml` 최상위 personal model pin이다. cross-agent
  finding은 scope-union 독립 review의 `cleared`로 제거됐다. 이는 비운영 v1
  완료와 별개의 whole-worktree strict baseline이며 strict green·ship/merge
  ready를 뜻하지 않는다. 증거:
  `.omo/evidence/v1-final/verify-changes-final2/{verify-changes.log,exit-code.txt}`.

## 버전 1 레거시 동등성 감사 — 2026-07-26 (historical snapshot)

판정은 **미완성 / release-blocked**다. `docs/` 동결 입력 388개를 전수
참조하고 PHP `devsam/core` 및 `hwe/ts`와 현 Kotlin/Next 실경로를 대조했다.
정본 보고서는
`docs/superpowers/research/2026-07-26-v1-legacy-equivalence-audit.md`,
문서 원장은
`docs/loops/v1-legacy-equivalence-audit-2026-07-26/DOCS_MANIFEST.md`다.

이번 diff에서 확정 버그 6개를 bounded 수정했다.

- cold boot world scope와 troop 재적재
- `ProfileIconSync` durable inbox terminal result
- `che_천도` 거리/비용/턴/trial/유산/로그/static-event 순서
- AI 요양 기본 임계값 30→10
- event cold-load world scope
- board secret / unique auction deep link

최종 증거: backend 521 suites / 4,585 tests / 실패 0 / skip 205,
`web/game` 42 files / 216 tests, 독립 리뷰 `cleared`. Docker 2-world IT,
PHP 재캡처, live browser는 환경 부재로 `채점대기`다. 명령·월 틱·전투·AI
정책·부가 시스템·JPA read·프런트·S6 운영의 잔여 차단 항목은 감사 보고서
§6에 남아 있다. commit/push/merge/deploy는 수행하지 않았다.

---

## 현재 상태 요약 — 2026-07-25

CQRS 정합성 하드닝 트랙과 F4 프론트 액션 배선이 함께 main에 반영됐다. 아래는 정본 최신 상태이며, 그 뒤 히스토리 절은 압축된 기록(증거는 PR/리뷰 아티팩트가 정본)이다.

### CQRS 하드닝 (ARCH-S1–S6) — S5까지 build-only 완료, main 머지

전부 **build-only**(프로덕션 cutover/activation 미수행), 라이브 게임 동작·패러티 골든 불변.

| 그룹 | 티켓 | PR / 커밋 | 상태 |
|------|------|-----------|------|
| 월드 스코프 (B1) | OPENSAM-127~129 | #302~#305 | main 머지 — 로더/쿼리/예약/Redis 키/flush를 `world_id`로 스코프, 동일 local-ID 2월드 격리 게이트 통과 |
| flush 무결성 (B2) | OPENSAM-130~132 | #307~#309, #311 | main 머지 — `DeltaGenerationSession`, `world_version` CAS + `writer_epoch`, `FlushRecoveryGate` |
| S4 durable 명령 경로 | OPENSAM-133~136 | **#312** | main 머지 · GH #279/#280/#281/#282 **CLOSED** · 독립 리뷰 `Verdict: cleared` — command_inbox 선기록, durable result/outbox, consumer-group wake + post-commit ACK, 크래시/리플레이 매트릭스 |
| S5-T1 hot/cold 카탈로그 | OPENSAM-137 | 커밋 `4e7095df` | main 머지 (build-only) |
| S5-T2 bounded boot reads | OPENSAM-138 | **#314** | main 머지 — 부팅 아카이브 읽기 bounded/on-demand화 |
| S5-T3 minVersion read barrier | OPENSAM-139 | **#315** | main 머지 — game-api `minVersion` read barrier, stale read → 409 `VERSION_NOT_VISIBLE` |
| S6 롤아웃 | OPENSAM-122 (#268) | — | **잔여** — canary/expand-backfill/replica ADR, S2–S5 완료 후 착수 |

- 에픽 #266(ARCH-S4)은 자식 T1–T4가 닫혔지만 **activation/operational 잔여** 때문에 OPEN 유지.
- ARCH-S1-T3(OPENSAM-125 / #271) 용량 임계값·admission policy는 OPEN(병렬 capacity work).

### F4 프론트 액션 배선 — main 머지

| 티켓 | PR | 내용 |
|------|-----|------|
| OPENSAM-13 | #316 | 엔진 deny 결과를 web/game에 표면화 |
| OPENSAM-6 | #317 | 외교 서신 승인/거부 응답 배선 |
| OPENSAM-8 | #318 | 내정보(my-page) 즉시 액션 |
| OPENSAM-7 | #319 | 인사부 roster read model + 장수 임면 배선 |

메일함 서신 삭제(`web/game/app/game/mailbox/page.tsx` → `deleteMessage` intake)도 배선 완료.

### 오늘(2026-07-25) Jira Done 처리

OPENSAM-6 / 7 / 8 / 13 / 97 / 123 / 124 를 완료 처리함. (97 = 초상 수집 page 모드 승격, 123 = CQRS 로컬 집계 기준선 재현, 124 = 국가 벌크 증거 + 데몬 라이프사이클 고정 — 커밋 `11bb0322`/`e013e47c`/`b6cb77f0`.)

### 다음 착수 후보

OPENSAM-137은 완료됐으므로 S5 잔여(hot/cold 활성화 follow-up) 또는 S6 착수 판단은 활성화 정책(#271, #268) 게이트를 따른다. 프로덕션 deploy/cutover·골든 위조·force-merge 금지.

---

## 히스토리 (압축) — 증거 정본은 PR/리뷰 아티팩트

### CQRS B1 — OPENSAM-127~129
process-world reads + flush scope + two-world isolation. main 머지 (#302~#305).

### CQRS B2 — OPENSAM-130~132 (build-only, 2026-07-21)
- OPENSAM-130 (#307): DeltaGenerationSession prepare/commit/abort.
- OPENSAM-131 (#308): world_version CAS + writer_epoch fence on flush.
- OPENSAM-132 (#309/#311): FlushRecoveryGate + intake/tick stop; FLUSH_RETRY resume.
- 리뷰: `docs/superpowers/reviews/2026-07-21-opensam-13{0,1,2}-*.md` — Verdict cleared.

### S4 durable 명령 경로 — OPENSAM-133~136 (build-only, PR #312 머지 2026-07-22)
- **OPENSAM-133 / #279 (ARCH-S4-T1)**: `command_inbox` 선기록(202 이전), DB-before-Redis intake, reserved ring + inbox 트랜잭션, 안정 intent fingerprint, 중복 request-id 처리. GH CLOSED 2026-07-23 (build-only).
- **OPENSAM-134 / #280 (ARCH-S4-T2)**: durable inbox claim/reclaim(lease), Redis consumer-group wake + PEL 인계 + post-commit ACK. GH CLOSED 2026-07-23 (build-only).
- **OPENSAM-135 / #281 (ARCH-S4-T3)**: durable `command_result`/`command_outbox`(V35), 같은 flush TX 커밋, `CommandOutboxRelay` 재시도, 예약/큐 terminal 상관. GH CLOSED. 리뷰 최종 `Verdict: cleared` — `docs/superpowers/reviews/2026-07-22-opensam-135-durable-result-outbox-review.md`.
- **OPENSAM-136 / #282 (ARCH-S4-T4)**: 크래시/리플레이 매트릭스. GH CLOSED.
- 잔여(비-차단): 프로덕션 deploy/cutover 미수행, 에픽 #266 activation 잔여, `reservationRevision` 계약 설계 잔여, 광역 `verify-changes.sh --run` Gradle stall(툴링 baseline). 상세 트리아지: `docs/superpowers/research/2026-07-23-ticket-triage-next.md`.

### S5-T1 hot/cold 카탈로그 — OPENSAM-137 (build-only, 커밋 `4e7095df`)
`logic/.../memory/HotColdCatalog.kt`(ALWAYS_HOT/PHASE_HOT/QUERY_ONLY_COLD) + `HotColdWorldCatalogGuardTest`(스냅샷 로더/런타임 read-seam/직접 SQL 스캔). 독립 리뷰 반복 후 method-agnostic reader 탐지로 수렴. S5 런타임 prefetch 활성화는 미수행.

### S5-T2 bounded boot reads — OPENSAM-138 (build-only, PR #314)
`WorldSnapshotLoader`가 statistic/history/global-log full-scan 제거, `ArchiveHistoryReader`/`StatisticSnapshotReader` on-demand 시seam. 아카이브 flush 복구는 `DatabaseHooks`가 pending 마커만 싣고 `JdbcFlushExecutor`가 재시도 TX 내부에서 읽도록 교정. 리뷰 5회차 cleared — `docs/superpowers/reviews/2026-07-23-opensam-138-bounded-boot-review.md`. 리터럴 JFR/heap 비교(#284)와 광역 `verify-changes.sh --run`은 미실행.

### S5-T3 minVersion read barrier — OPENSAM-139 (build-only, PR #315 머지 2026-07-24)
- `TurnDaemonEventEnvelope`가 nullable `committedWorldVersion` 운반(레거시 envelope 디코드 보존), 데몬/API terminal result 행이 커밋 버전 인코드.
- `GET /api/command/result/{requestId}`가 `committedWorldVersion` 최상위 노출.
- game-api `minVersion` 인터셉터: command-result read = read-your-writes, ranks/history/world-log/admin = eventual, 그 외 = authoritative.
- `ReadConsistencyBarrier`가 전용 `game-api-read-barrier` Hikari 풀로 `world_state.world_version` 폴링, stale read → 409 `VERSION_NOT_VISIBLE`(worldId/currentVersion/requiredVersion/retryAfterMs).
- 독립 리뷰 cleared — `docs/superpowers/reviews/2026-07-24-opensam-139-minversion-read-barrier-review.md`.
- **상태: PR #315로 main 머지 완료** (이전 "commit/push/PR pending"은 해소됨).

### 미해결 툴링 baseline (제품 결함 아님)
광역 `scripts/agent/verify-changes.sh --run`가 `--rerun-tasks` Gradle 매트릭스에서 반복 stall(exit 143)하는 현상은 여러 워커에서 관측됨. 집중 모듈 테스트·strict `check.py`·독립 리뷰는 green. 개인 `.codex/config.toml` overlay(`max_threads`/`max_depth`)는 이 트랙에서 편집/스테이징하지 않음.
