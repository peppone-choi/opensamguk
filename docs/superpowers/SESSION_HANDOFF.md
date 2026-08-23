# SESSION HANDOFF — 2026-08-23 (OPENSAM-206~220 integration wave; OPENSAM-218 policy gate)

## Current integration-wave / OPENSAM-218 handoff

- Current task authority is `.ai/task.md` section `OPENSAM-206~220 통합·검증·배포 (활성 계약)` and the active integration worktree named there.
- PR #499 is the bounded OPENSAM-218 policy lane. ADR-LITE-042 retires PHP grand-truth, PHP-wins, draw-for-draw, byte-log, and golden-first product gates while retaining truthful evidence, frozen existing tests, deterministic replay, one-daemon-write, flush-delta, and insertion-order rules.
- The integration wave, not this lane, owns merge, deployment, production observation, Jira transition, and cleanup. No such result is claimed in this current section.
- Detailed OPENSAM-218 implementation, verification, and risk evidence is in the metarepo task report `reports/opensamguk/tasks/2026-08-23-op218-parity-adr.md`.

---

# SESSION HANDOFF — 2026-08-09 (OPENSAM-43 terminal structural dirty-tree review; historical)

## Historical OP43 handoff

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
- Historical only: the prior cleared fingerprint
  `5c93a23653012a0e557b720f701374ea2fe2c86ea5cebf718856d51933e17360` and
  4,914-test verifier applied to an earlier Round 1 tree; neither replaces the
  current exact structural-tree clearance or 4,931 full verifier.
- The PR-conversation review counter is 0/3. After commit/push, observe
  exact-SHA CI, then start the three sequential
  review/remediation/reverification rounds. Commit, push, exact-SHA CI, reviews,
  merge, and deployment remain separate gates.
- No deployment, cutover, production observation, secret access, data deletion,
  legacy/golden write, or test weakening is claimed or authorized by this handoff.

---

# SESSION HANDOFF — 2026-08-09 (OPENSAM-43 V2-0B approved contract reconciliation; historical)

## User-approved contract and durable records

- The 2026-08-09 approval opens OPENSAM-43 on the existing tracked payload
  `infra/src/main/resources/scenario/cities_1010.json`, with SHA-256
  `6759a68255cae1a6b9c05cbbaf5736ed8fc9fcb50c6623be44d7e3dfe0b4d393`, 94 total
  cities, 24 with `nation_id != 0`, and an empty repeated typed-snapshot diff.
  `infra/src/main/resources/content/v2/cities_1010.json` is seven-field
  metadata that references that payload; it is not a second city-row source.
- ADR-LITE-030 in `.ai/decisions.md` formally supersedes the old OP43
  G0/counter-1,180/`CountyParticipationFixture` prerequisite. It preserves
  V2-G0 and the 1,180-content/fixture work as post-open work rather than
  deleting it.
- Durable docs ledger: `docs/loops/opensam-43-v2-0b-2026-08-09/LEDGER.md`.
  Its `0B-a-v1-completion-reference.md` links, rather than copies, the
  canonical `docs/loops/v1-nonoperational-completion-2026-07-27/LEDGER.md`.
  Its `0B-j-query-read-write-impact-ledger.md` names the observed
  world/profile/catalog/wire/Flyway seams and explicitly distinguishes them
  from unimplemented persistence, handler, cache, realtime, and UI work.
- `docs/superpowers/plans/2026-07-17-v2-ticket-backlog/01-backbone-micro.md`
  reconciles the stale OP43 wording to that approval. The OP43 runtime-contract
  plan remains authoritative for runtime behavior; its scope paragraph now also
  points to the later OP44 decomposition and OP150 `V901` ownership boundary.

## Historical implemented fan-in and verification

- The separately owned source lanes have now implemented 0B-b~i/k: the existing
  engine/API process-world identities, the profile/property AND gate, typed
  ACTIVE-only city metadata/adapter, independent required v2 wire versions,
  and a test-only V900 sibling-location probe. There is no production v2 SQL
  or product leaf.
- The exact dirty-tree backend gate was green in one run: 605 suites / 5,063
  tests / failures 0 / errors 0 / skipped 1. Independent review then found four
  MAJOR false-green gaps. The remediations now reject metadata extra keys and
  missing wire versions, boot actual v1/v2 Spring Flyway configurations, and
  reject comment-spoofed migration rules while introspecting applied tables.
  Focused current XML is common wire 7/0/0/0, infra catalog 9/0/0/0, and engine
  v2 21/0/0/0. Exact dirty-tree re-review is `cleared` at fingerprint `3e05d2cf…cbb`.
- The final current-tree `scripts/agent/verify-changes.sh --run` invocation exited 0:
  `BUILD SUCCESSFUL in 16m 19s`, 29 tasks, 4,911 tests across common/logic/infra/game-api/game-engine,
  failures 0 / errors 0. Strict reported 41 changed, Errors 0, Warnings 0, findings 0. The captured log
  `/tmp/op43-final-os-verify.log` has SHA-256
  `00b137ac81dca1757bb920ccc54f1b0eda1dac18343b5028587ffaab5286242a`.
- OPENSAM-44 (persistence contract decomposition), OPENSAM-150 (`V901` first product persistence/leaf), OPENSAM-104/105 (RTK builders),
  V2-G0/1,180, and production rollout remain outside this approval. Do not
  infer a v2 schema, city ledger, JPA repository, cache, handler, Redis
  producer, controller, SSE subscriber, or frontend invalidation from 0B-j.
- Next: commit, perform immutable-SHA review, push/open the PR, observe remote CI, and complete the required
  three sequential PR-conversation reviews before merge. Compose/smoke/deploy were not run because they are
  outside this non-operational contract. The generic Fablize tool-failure notices seen on
  otherwise successful read-only discovery remain the already-recorded
  external tooling baseline, not runtime evidence.

---

# SESSION HANDOFF — 2026-08-03 (RTK14 전체 로스터·5능력치·생몰/등장 수명주기)

## 사용자 지시 원문

1. “그리고 통솔, 무력, 지력, 정치, 매력이 다 반영되어야지? 모든 선택 단추나 NPC 스탯이나 전부?”
2. “지금 정치랑 매력이 50이잖아 모두 그거 수정해야지.”
3. “만약 여기에 없는 사람의 정치와 매력은 나름대로 판단해.”
4. “정치, 매력 붙여서 새로운 시나리오로 만들어서 덮어 써.”
5. “기존 통무지도 엑셀 값으로 수정해. 그리고 등장하지 않는 장수들도 파악해서.... 확인하고.”
6. “모든 json의 값을 수정 해야 할거야.”
7. “엑셀에 있는 모든 데이터를 다 사용해. 만약 엑셀에 있는데 빠졌던 사람이라면 추가하고. 생몰년, 등장년 확인해서 적절하게 넣어.”

## 구현·검증 상태

- private workbook 1,000행·15열 전체를 source contract로 검증하고 populated 런타임 시나리오 15개에 각 officer number 1–1000을 정확히 한 번씩 표현했다. settings-only 15개는 유지한다.
- 기존 장수의 통솔·무력·지력·정치·매력과 생년·등장년·몰년을 갱신하고 workbook-only 343행을 base `general`에 추가했다. runtime-only 351행의 정치/매력은 reviewed override이며 미검토 fallback은 fail-closed다.
- source PR #356의 실제 리뷰 결함(중립 tuple offset, lifecycle repair의 birth identity·explicit appearance·stored-adult/future-appearance·effective source — 초기 초안은 V26을 확장했으나 지금은 전부 `V38__rtk14_npc_lifecycle_repair.kt`에 있고 V26은 origin/main으로 되돌렸다, source `general_ex` filtering/RNG, 빙의 terminal/request correlation/reload cleanup, typed RNG marker/placement, source-test-before-materialization, archive filtering, secret tracing)을 수정했다.
- claim-request migration이 임시 `general_owner`에 `claim_request_id`를 저장한다. 이 migration은 `V36__general_owner_claim_request.sql` → **`V37__general_owner_claim_request.sql`**로 renumber됐다. origin/main이 이미 `V36__diplomacy_casualties.sql`을 싣고 있어 V36이 둘이면 Flyway가 duplicate version으로 실패하기 때문이다. 재시도는 동일 요청만 조회하고 claimable GET도 exact terminal deny만 예약 해제해 새 후보를 돌려준다. 기존 null-id 확정 행은 `npc_state != 2`에서만 호환 유지한다.
- `EffectiveScenarioResolver`가 external-over-classpath 우선순위를 시드/lifecycle repair에 공유하고 세 Flyway 앱에 `scenario_dir` placeholder를 배선한다. source Compose와 Docker PR #25가 Flyway를 먼저 잡을 수 있는 game-api/engine에 동일 read-only mount를 제공한다.
- 증거: Python 18/18, real-workbook 30/30 two-pass byte-identical, backend `BUILD SUCCESSFUL in 15m 59s`와 XML 4,404건(실패/오류 0, 스킵 1), importer 20/20·ScenarioJson 15/15·resolver 4/4·V26 5/5(V26 확장 초안 시점의 값 — 그 확장은 되돌렸고 커버리지는 `V38Rtk14NpcLifecycleRepairMigrationTest`로 이동)·SeedBootstrap 3/3·빙의 21/21·claim-request(현 V37) IT 3/3·game 251/251·양쪽 typecheck·Agent OS/strict 0/0. 로컬 stack start는 미제공 `JWT_SECRET`로 중단했고, 비밀을 읽지 않은 Compose config 검증은 통과했다.
- Docker PR #24는 3회 mention review 후 merge·배포 성공. Source PR #356과 Docker PR #25는 새 exact SHA 기준 3회 mention review가 필요하다. V26 확장은 폐기했다 — 이미 `flyway_schema_history`에 V26을 기록한 DB는 V26을 재실행하지 않아 업그레이드된 월드에 닿지 못하고, fresh DB에서도 Flyway가 `ScenarioSeedRunner`(`ApplicationRunner`)보다 먼저 돌아 `world_state`가 비어 있어 V26이 즉시 반환하므로 신규 월드에서도 도달 불가였다. repair 전체를 아직 어떤 월드도 기록하지 않은 world-scoped `V38__rtk14_npc_lifecycle_repair.kt` 하나에 두어 기존 월드와 새로 시드된 월드가 같은 최종 상태로 수렴한다. 실행·배포·live 결과는 아직 없다.

## 다음 순서

1. source fix commit/push 및 PR #356 CI.
2. 최신 exact SHA에 `@codex review`를 3회 순차 요청하고 각 완료/지적을 확인한다. 새 commit이 생기면 카운트를 다시 시작한다.
3. source merge·GCP 배포 완료 후 `pep`만 승인된 control-plane 경로로 재시드한다.
4. `sam.peppone.dev` health/login/game, 엔진 clock, DB의 5능력치 분포와 RTK metadata, 연도별 활성 roster, 빙의 terminal-result UI를 실서비스에서 확인한다.

---

# SESSION HANDOFF — 2026-07-31 (OPENSAM-34 local grader closeout)

## 사용자 지시 원문

1. `권장 처리 순서대로 차례대로.`
2. `계속.`

## 처리 결과

- OPENSAM-34 D4-31~35 local predeploy grader, manual-only workflow contract,
  runbook, and final independent review are complete. The final re-review is
  `cleared`; initial `fix-required` findings were remediated rather than waived.
- Local evidence: both predeploy `bash -n` checks, hermetic contract, workflow
  YAML parse, and scoped untracked-file whitespace checks passed. Fresh Gradle
  migration evidence is V29 `2/0/0/0` and V32 `9/0/0/0`, `BUILD SUCCESSFUL in
  2m 2s`.
- This is not a production Go decision. Jira remains `할 일`; the actual
  `ec2-prod` runner was observed offline in both this repository and sibling
  `opensamguk-docker`, so D4-31~35 actual observations are blocked/incomplete.
- `scripts/agent/verify-changes.sh` classification ran, but `--run` was not
  rerun for OPENSAM-34. No EC2/prod access, workflow dispatch, `.env*`/secret
  access, commit, push, PR, merge, deploy, Jira mutation, data deletion,
  legacy/golden write, or test weakening occurred.
- Repeated generic Fablize tool-failure notices during successful read-only
  discovery are an isolated external tooling baseline; direct scoped evidence,
  not those notices, determines this closeout.

## 다음 순서

1. Do not resume EC2 or dispatch the workflow without authorization. After EC2
   is resumed and the user explicitly approves, dispatch manually with `server`,
   immutable `expected_tag`, `expected_scenario_code`, positive `world_id`, and
   operator-approved `min_free_gib`/`min_free_percent`.
2. A future PR must follow ADR-LITE-026: three separate review-agent mentions
   in the PR conversation, fix and reverify every finding, then obtain explicit
   human merge approval.

---

# SESSION HANDOFF — 2026-07-31 (OPENSAM-33 로컬 종결)

## 사용자 지시 원문

1. `Jira 확인해서 현재 처리 해야 하는 티켓을 확인해.`
2. `권장 처리 순서대로 차례대로.`
3. `그리고 앞으론 PR 올린 후에 자체 리뷰를 3번 받고 수정 한 다음에 머지하도록 해.`
4. `PR에서 멘션하면 리뷰 가능하잖아. 그걸 이야기 하는거야.`
5. `계속.`

## 처리 결과

- 승인된 직렬 순서 `OPENSAM-31 → 32 → 33 → 34 → 149 → 35`에서
  OPENSAM-33 D4-14~17을 locally complete/released로 종결했다. Jira는 외부
  전이 권한이 없어 계속 `할 일`이다.
- Final isolated artifact:
  `/var/folders/34/jlnbkc0j6fj0nkcp7fj0f9h00000gn/T/opensamguk-op33-remediation.A4KNsK/live-gate-marker-fixed`.
  `che_요양`의 intake `202`, same-ID reservation/execution `200`, durable marker,
  exactly matching XRANGE/XINFO, same-entry XACK/XPENDING=0, 60-second three
  ticks, authoritative `0/0/0 → 0/10/7`, and SSE → refresh → DOM were retained.
- Initial `fix-required` findings were remediated: raw `Instant` JDBC marker
  binding changed to `Timestamp.from`, exact run volumes and aliases are
  removed, reservation/execution each assert HTTP status and original request
  ID, and the operational timeout is `600000ms` with caller override preserved.
- Final focused evidence: `ScenarioImporterIT` 14/0/0/0,
  `RedisCommandStreamIT` 3/0/0/0, `IntakeResultChannelTest` 4/0/0/0,
  `RealtimeRelayIT` 1/0/0/0; marker unit 4/4 + Testcontainers IT 1/1 skip 0;
  web typecheck and shell contracts PASS. Fresh rerun: shell syntax+timeout
  contract PASS, web typecheck PASS, `ScenarioMapSeedIT` 8/0/0/0 (`BUILD
  SUCCESSFUL` in 2m), and `CommandReserveServiceTest` 4/0/0/0 plus IT 1/0/0/0
  (`BUILD SUCCESSFUL` in 1m 22s). Final independent review is cleared.
- Exact cleanup for `v1-e2e-20260731063246-68779` removed its `pgdata`,
  `redisdata`, and `profile-icons` volumes and its five `:latest` run aliases;
  prebuilt source image tags were preserved.
- Residual QUESTION: artifact evidence has 9 EventSource opens and 8
  `turnCompleted` events. Reconnect/remount versus duplicate subscription is
  UNKNOWN; it is non-blocking for stale-refresh evidence only.
- `scripts/agent/verify-changes.sh --run` executed once: five-module Gradle
  `BUILD SUCCESSFUL in 12m 55s`, 552 suites / 4,763 tests / failures 0 / errors
  0 / skipped 1; `web/game` typecheck PASS and Vitest 46 files / 232 tests PASS;
  `git diff --check` PASS. Wrapper exit 1 is only the strict checker’s existing
  whole-worktree baselines: user-owned `.codex/config.toml` personal model pin
  and the historical 2026-07-27 review missing one anchored Scope/Verdict under
  the current rule.
- Explicitly unexecuted: `tools/parity/gate.sh backend`, production deploy or
  EC2, commit/push/PR/merge, and Jira transition. Generic Fablize tool-failure
  notices on successful read commands are an isolated external tooling baseline,
  not runtime evidence.

## 다음 순서

1. OPENSAM-34 local grader closeout is recorded above; its actual production
   observation remains blocked until EC2 resume and explicit user approval.
2. ADR-LITE-026: after a PR is created, mention the review agent in the PR
   conversation for three separate rounds; fix and reverify each round; merge
   only with explicit human approval.

---

# SESSION HANDOFF — 2026-07-30 (OPENSAM-32 로컬 종결)

## 사용자 지시 원문

1. "Jira 확인해서 현재 처리 해야 하는 티켓을 확인해."
2. "권장 처리 순서대로 차례대로."

## 처리 결과

- 승인 직렬 순서 `OPENSAM-31 → 32 → 33 → 34 → 149 → 35`에서
  OPENSAM-32 D4-08~13을 완료했다.
- 불가침 제의 shortcut의 구조화 `destNationID/year/month` form을 server
  catalog에서 해석하고 lookup/row/form 누락을 fail-closed로 만들었다.
- 종전·불가침·불가침파기 proposal의 destination target color를 preload된
  상대국 실제 color와 일치시켰다.
- 실제 lifecycle proposal → JDBC message → accept → 양방향 diplomacy
  flush → 동일 월드 declaration을 Testcontainers IT로 연결했다.
- 최종 focused gates: logic 72/72, game-engine 34/34, infra 2/2,
  frontend 16/16, typecheck PASS; backend failure/error/skip 0.
- 독립 리뷰의 3 MAJOR, 1 MINOR, 추가 form-missing edge가 모두 해소돼
  `Verdict: cleared`다.
- live browser는 필수 compose runtime 설정 부재로 `채점대기`다. accept
  다중 로그/event 전체 PHP 패러티는 Jira 상태 전이 밖 후속 항목으로
  명시했다.
- Jira 상태는 외부 변경 권한이 없어 계속 `할 일`; commit/push/merge/
  deploy/production 접근/data delete/secret/legacy/golden write는 없었다.

## 다음 순서

1. OPENSAM-33을 새 `$os-start-task` 계약으로 연다.
2. 완료·검증·독립 검토 전 OPENSAM-34 write scope를 열지 않는다.

---

# SESSION HANDOFF — 2026-07-30 (OPENSAM-31 로컬 종결)

## 사용자 지시 원문

1. "Jira 확인해서 현재 처리 해야 하는 티켓을 확인해."
2. "권장 처리 순서대로 차례대로."

## 처리 결과

- 승인된 직렬 순서 `OPENSAM-31 → 32 → 33 → 34 → 149 → 35` 중
  OPENSAM-31만 write scope를 열어 완료했다.
- active v1 안정화 계획에 seed/load/intake/flush/read/SSE relay
  ingress/deploy의 정확한 repo-root 명령과 객관적 판정 기준을 추가했다.
- 독립 검토에서 D4-03/05/06/07 assertion overclaim을 제거한 뒤
  `Verdict: cleared`를 받았다.
- `scripts/agent/verify-changes.sh --run` 1회: fresh backend XML
  552 suites / 4,758 tests / failure·error 0 / skip 1. 임시 Gradle 로그가
  삭제돼 literal `BUILD SUCCESSFUL` line은 보존되지 않았다.
- `web/game` typecheck + 46 files / 227 tests, Agent OS contract,
  `git diff --check`는 PASS다.
- strict checker의 두 error는 현 티켓 밖 baseline이다: user-owned
  `.codex/config.toml` model pin, historical review의 uppercase
  `Verdict: CLEARED`와 현재 lowercase anchor 규칙의 불일치.
- 열거한 7개 런타임 명령은 개별 재실행하지 않았고 browser-facing SSE는
  `채점대기`로 남겼다. 문서화 티켓의 실행 증거로 과장하지 않는다.
- Jira 전이, commit, push, merge, deploy, EC2/production 접근, 데이터 삭제,
  secret 접근, legacy/golden write, test weakening은 수행하지 않았다.

## 다음 순서

1. OPENSAM-32를 새 `$os-start-task` 계약으로 연다.
2. PHP oracle → browser observation → root-cause → measured loop 순서를 지킨다.
3. OPENSAM-32 완료·검증·독립 검토 전 OPENSAM-33 write scope는 열지 않는다.

---

# SESSION HANDOFF — 2026-07-30 (V2 실시간 전투 설계 승인)

## 0. 사용자 지시 원문

1. "오픈삼국의 전투 스프라이트와 2D 혹은 2.5D 전투 시스템을 만들어서 구현하고 싶어. 지금은 그냥 꽝 vs 꽝인데, 전략과 전술을 넣고 싶단 말이지. ... 타일이나 2.5D 느낌 혹은 토탈워 비슷한 느낌을 내고 싶거든."
2. "일단 설계만 해둬."
3. "스프라이트는 만들어 둬."
4. "병종 전체와 에셋 전체, 그리고 지형도 필요하지."
5. "나는 병종을 추가 및 수정해달라는 뜻이었어. 함진영이나 단양병 같은거."
6. "좋아, 전부 펼쳐."
7. "커밋 하고 병종에 맞게 스프라이트도 만들어 줘. 도트로."
8. "전투 시스템을 위해선 지형 스프라이트도 필요할거 같은데."
9. "그리고 이펙트도 필요할거고."
10. "커밋 및 푸시. 그리고 다음 단계들을 [$superpowers:brainstorming] 이용해서 물어봐서 설계후 지라 타켓과 깃허브 이슈로 넣어줘."
11. 설계 보드 승인 원문: "승인" → "승인." → "전체 승인." → "좋아. 승인." → 작성 스펙 최종 "승인."

## 1. 승인된 정본

- 스펙:
  `docs/superpowers/specs/2026-07-30-v2-realtime-battle-session-command-replay-design.md`
- 스펙 커밋/푸시: `b2af749b`
- 독립 아키텍처 검토: 1차·2차 `FIX-REQUIRED`의 campaign lock closure,
  durable ticket/ACK, R3 event 보존, 증원/result 소유권, recoverable timer를
  모두 반영한 뒤 최종 `CLEAR — blockers none`.
- 결정: V2 출시 필수 야전·공성·수전, 전용 single-world battle-engine,
  200ms authoritative actor, WebSocket, commander+delegated officers,
  편제당 사용자 1명, 지휘망 지연, 제한 정지, disconnect→AI→부관 승계,
  진영당 16편제, 12–15분, headless fallback.
- 시각 계약: Three.js 정사영 2.5D와 formation 판정은 유지한다. 추적 에셋은
  unit source/runtime 105/105, terrain 32, effects 16/94 frames이며 아직
  candidate이고 simulation authority가 아니다.

## 2. 뒤집힌 정본

- ADR-LITE-019/021의 `V2-4A`/`V2-4B` 오픈 후 분류와 오픈 경로 20
  단일값은 이번 승인으로 해당 부분이 supersede됐다. 기존 20은 전투 프로그램
  추가 전 부분합이다.
- `2026-07-28-v2-2_5d-tactical-battle-and-sprite-design.md`의 game-engine
  scheduler, HTTP/SSE 우선, 약 8편제, 오픈 후 rollout, 1인 지휘 기본값은
  역사 초안으로 강등됐다.
- 유지되는 부분: v1 격리, Three.js 2.5D, formation 단위 판정, 연속
  fixed-point 좌표, 대형·측후면·사기·피로·보급·지휘망·목표, 에셋 계약.

## 3. 아직 정하지 않은 것

- 야전·공성·수전 각각의 구체 전술 수식과 수용 시나리오: 별도 brainstorming
  스펙 필요.
- 전투 HUD/2.5D renderer의 실제 화면 구성: 별도 UI 스펙 필요.
- 동시 battle session의 운영 admission 상한: target node 부하 측정 뒤
  별도 용량 ADR 필요. 단일 전투 출시 게이트는 승인 스펙 §18에 확정됨.
- 진영당 32편제 stretch는 출시 차단이 아니며 별도 재측정 전 활성화 금지.

## 4. 다음 순서

1. `superpowers:writing-plans`로 공통 전투 기반 implementation plan 작성
2. placeholder/spec coverage/type consistency 자체 검토
3. 독립 adversarial plan review와 `fix-required` 종결
4. 사용자 계획 승인
5. Jira OPENSAM 목표/Epic/Story/Sub-task와 GitHub issues 생성·상호 링크
6. 구현은 별도 사용자 승인 전 시작하지 않음

---

# SESSION HANDOFF — 2026-07-29 (v1 비운영 감사 종결)

## 결론

v1의 2026-07-26 감사 §6.1–§6.8 중 **비운영** 차단은 PHP 정본·Kotlin
replay·local Docker로 종결됐다. v1은 **연 36순**(상순/중순/하순)이며,
production/S6 rollout은 여전히 미수행이다.

- 감사/원래 source map:
  `docs/superpowers/research/2026-07-26-v1-legacy-equivalence-audit.md`
- 동결 docs manifest: `docs/loops/v1-legacy-equivalence-audit-2026-07-26/DOCS_MANIFEST.md`
  (388 files; 동결 뒤 문서는 소급 산입 금지)
- final ledger/review:
  `docs/loops/v1-nonoperational-completion-2026-07-27/LEDGER.md`,
  `docs/superpowers/reviews/2026-07-27-v1-nonoperational-completion-review.md`
- evidence: schema 4 12개월·36순 A/B byte-identical + Kotlin replay 1/0/0/0;
  backend 550/4,753 failure·error 0; web/game 46/227 + typecheck; local Docker
  runtime9 Playwright 1 passed/0 unexpected; independent review CLEAR/APPROVE.

## 2026-07-29 검증과 금지선 (historical snapshot)

1. **최종 Agent OS 검증:** checker의 cleared/quarantined disjoint Scope union 수정
   뒤 `scripts/agent/verify-changes.sh --run`을 정확히 한 번 실행했다. Gradle 5개
   모듈은 `BUILD SUCCESSFUL in 13m 27s` / 29 tasks, `web/game` typecheck + 46/227,
   Agent OS contract와 diff/whitespace는 PASS다. cross-agent finding은
   [독립 scope-union review](reviews/2026-07-29-cross-agent-scope-union-review.md)의
   `cleared`로 제거됐다.
2. **strict 기준선 분리:** strict checker는 error 1 / warning 0이며 exit 1의
   유일한 원인은 untouched user-owned `.codex/config.toml` 최상위 personal model
   pin이다. 이는 당시 비운영 v1 기능 종결과 다른 whole-worktree baseline이며,
   current strict 상태나 git action readiness를 뜻하지 않는다. 증거:
   [verify-changes.log](../../.omo/evidence/v1-final/verify-changes-final2/verify-changes.log),
   [exit-code.txt](../../.omo/evidence/v1-final/verify-changes-final2/exit-code.txt).
3. **운영 제외:** S6/canary/expand-backfill/capacity/live EC2 cutover는 이 종결의
   일부가 아니다. 인간 승인 없이 deploy하지 않는다.
4. **수행하지 않음:** commit, push, merge, production deploy, data delete,
   secret access, legacy/golden write 또는 test weakening.

## 2026-07-30 사후 parity 검토 정정

7월 29일 종결 뒤 final reviewer가 `SelectPool`·`VotePoll`·
`DiplomacyLetter`의 unscoped side read를 발견했다. V32 복합 world key는 row
identity만 보장하므로 local-ID-only outer/nested read는 다른 world의 같은 ID를
잡을 수 있었다. 모든 outer/nested query에 `WorldId`/`world_id`를 강제하고
중앙 scoped beans로만 process world를 제공하며, same-local-ID 두 world
regression으로 보정했다. 최종 parity review는 **`CLEARED`**다.

- `origin/main`의 `OPENSAM-149 (OP149) Rehydrate` 표기는 superseded·not-wired
  historical quarantine이다. 현재 종료/rehydrate 증거나 활성 blocker로 재연결하지
  않는다.
- 최신 canonical backend gate: 552 suites / 4,758 tests / failure·error 0,
  known `LongSim` skip 1. fresh 영향 범위는 237 suites / 1,366 tests green이다.
  Golden은 current green이지만 logic은 `UP-TO-DATE`라 fresh logic rerun 주장이
  아니다. frontend typecheck + 46/227 + diff-check도 green이다.
- corrected local Docker: five images sequential green, 8 health green,
  Playwright 1 passed (`241634ms`); join `RESOLVED`/`ok=true`/general `1230`,
  정확히 14 DOM, engine restart 뒤 general/result/repository `200`, auth
  `false|false` 복원, final project containers 0.
- 더 이른 parallel image-build OOM 및 port 3000 collision/120초 timeout은
  하니스/환경 실패다. root timeout 기본값은 `420000`으로 수정됐고 override
  test는 green이다. 무관한 `eager_cray`는 OOMKilled였지만 volume을 보존한 채
  restart하지 않았고 final project 결과에 포함하지 않았다.
- 7월 29일 `.codex/config.toml` strict error는 historical whole-worktree
  hygiene baseline일 뿐, 현재 v1 parity evidence의 blocker도 current strict
  green 주장도 아니다. v1은 계속 연 36순이며 S6/cutover는 제외다.

이 사후 검토는 git action 전 release-candidate 증거다. commit, push, merge,
deploy를 실행하거나 승인하지 않았다.

---

# SESSION HANDOFF — 2026-07-26 (v1 레거시 동등성 감사, historical snapshot)

## 결론

버전 1은 **레거시 동등 완료가 아니며 release-blocked**다. `docs/` 동결 입력
388개를 전수 참조하고 PHP `legacy/devsam-core` commit
`4de7ebec17a722d516608dbb987467f1a451dada` 및 `hwe/ts`와 현 실경로를
대조했다.

- 감사 보고서:
  `docs/superpowers/research/2026-07-26-v1-legacy-equivalence-audit.md`
- 문서 manifest:
  `docs/loops/v1-legacy-equivalence-audit-2026-07-26/DOCS_MANIFEST.md`
- 루프 원장:
  `docs/loops/v1-legacy-equivalence-audit-2026-07-26/LEDGER.md`
- 독립 리뷰:
  `docs/superpowers/reviews/2026-07-26-v1-legacy-equivalence-fixes-review.md`
  (`Verdict: cleared`)

## 이번에 수정한 것

1. world-scoped cold boot와 troop 재적재
2. `ProfileIconSync` durable inbox terminal result
3. `che_천도` 거리·비용·준비 턴·trial·유산·5개 로그·static-event 순서
4. AI 요양 기본 임계값 30→10
5. event cold-load world scope
6. 기밀실/유니크 경매 deep link

최종 backend gate는 521 suites / 4,585 tests / 실패 0 / skip 205,
`web/game`은 typecheck + 42 files / 216 tests다. Docker daemon, PHP CLI,
로컬 게임 서버 부재로 2-world PostgreSQL IT, PHP 재캡처, live browser는
`채점대기`다. 다음 작업은 감사 보고서 §6 차단 항목을 foundation-first
슬라이스로 닫아야 하며, v1 완료 표기·ship/merge/deploy는 금지한다.

---

# SESSION HANDOFF — 2026-07-25 (v2 버전분리 + 오픈 경로 확정 + 도시·인맥 루프 round-3)

> 이 세션은 코드 변경 0. 산출물은 전부 **결정과 문서**다. 정본 원장: `docs/loops/v2-planning-2026-07-12/LEDGER.md`.
> **대화 기록 의무**: ADR-LITE-020에 따라 이 절은 대화에서 오간 지시·판단·미결을 그대로 남긴다. 요약하면서 기각 사유·미검증 항목을 지우지 않는다.

## 0. 사용자 지시 (발화 순서대로, 원문)

1. "버전 1을 따로 저장하되 버전 2를 구현해서 오리지널, 뉴버전으로 나눌거야. 운영은 뉴버전으로 하고. 오리지널은 필요할때 여는걸로." → "둘다 지금 해."
2. "좋아, 이제 티켓 기준으로 앞으로 해야 할 일이 뭐가 있는지 확인해줘. **내가 원하는건 버전 2를 빨리 여는거긴 해.**"
3. "Jira에 오픈 경로 14개 우선순위 걸어줘. 깃허브도 마찬가지고."
4. "단, 버전 2를 열면서 **UI를 현대화 하고 유저 맞춤으로** 갈거야. 지금은 너무 복잡하고 정보도 많고... 촌스럽고..."
5. "그리고 **도시/인맥(꽌시) 중심의 플레이**를 유도할 수 있을까?"
6. "돌려. 내가 말한 도시 중심은 요섭 이야기 한거야." → 정정: "요섭이 아니라 **묘섭** 오타났어."
7. "돌려. 그럼 우리가 더 채울건 여기서 **장수장수 관계**겠군."
8. "**관계는 능력치 버프에도 영향을 줘야지.** 예를 들면 유비 관우 장비 의형제제라던지."
9. "그리고 **국가 임원진과 중앙관직**은 어떻게 할거지 https://namu.wiki/w/삼국지/관직"
10. "무조건 문서화를 할 것." → "대화도 문서화를 해야지."

`AskUserQuestion` 선택 2건: **G0 → "오픈 뒤로 미룬다"**, **OPENSAM-149 → "v2 착수 전에 먼저"**.

## 1. 확정된 것 (ADR로 감)

- **ADR-LITE-018** — v1을 오리지널로 동결, v2 뉴버전이 상시 운영. 별도 DB·별도 route/bean/migration, 플래그 공존 금지. **M-config 마일스톤 전제가 뒤집혀** v1은 상수 외부화 대상에서 제외(`MILESTONES.md`에 전제 변경 blockquote 삽입 완료). 오리지널 on-demand의 선결 조건 = restart-rehydrate lossless gate.
- **ADR-LITE-019** — 오픈 경로 재배열. `OPENSAM-149` 선행 승격, `V2-G0`(36~42)·`C-track`(51~55) 오픈 후로 연기. 확정 경로 `[31·32·33·34] → 149 → 0A(35) → 0B(43·44) → V2-1(45·46·47) → V2-2(48) → V2-3(56) → V2-5(61)` = **14 티켓**. GOLDENSET(round-2) 4·8번은 폐기가 아니라 **적용 시점 유예**. 리스크 자인: 오픈 시점 차별점이 조작 대상 패널·부곡·작전·가신 **4개뿐**.
- **ADR-LITE-020** — 무조건 문서화. 대화·에이전트 보고에만 있는 결과는 산출물 아님. 리뷰·채점은 `cleared`든 `fix-required`든 동일하게 파일로. 이 절이 그 규칙의 대화 부분 이행이다.
- **ADR-LITE-021** — round-3 채택. 도시 중심 = **자원 소유 주체의 위치**(3D·거점 수 아님), 인맥은 별개 시스템이 아니라 같은 시스템의 다른 면(4축이 전부 기존 `officer_level` 위에). **오픈 경로 14 → 20 단일값**(R1 원장기반·R2 수입봉록·R3 공백지화·R4 병사보충·R5 수송·R6 원장열람 추가, 조건부 항목 0). **관계망 7티켓은 오픈 후.** 관계 → 능력치 보정 확정(`product-spec.md:388` 뒤집기). 배포 토폴로지 = 한 프로세스 = 한 월드 = 한 DB(코드 불변식). **ADR-019의 오픈 경로 표(14)를 이 ADR의 20이 대체하고, 019의 나머지는 유효.**
- **OPENSAM-149 / #324** 신규 발행 — restart-rehydrate lossless gate.
- Jira·GitHub 양쪽에 오픈 경로 14티켓 우선순위 반영 완료. ⚠ **ADR-021로 20이 됐으므로 Jira·GitHub 우선순위를 6티켓분 갱신해야 한다 — 아직 안 했다.**

## 2. 대화에서 정해졌으나 아직 티켓·정본이 없는 것 (⚠ 인수인계 핵심)

- **UI 현대화(지시 4)는 v1 폐기가 아니라 v1→v2 재타깃이다.** `OPENSAM-112`~`115`(비주얼 현대화)는 우선순위가 내려간 게 아니라 **대상이 v2로 바뀌었다**. 113 = `priority-now`, 114·115 = `priority-next`로 조정함.
- ~~**"유저 맞춤"에는 티켓이 없다.**~~ **→ 해소(2026-07-25). ADR-LITE-022** — *"20버튼 전부 노출" → "이 주체가 지금 할 수 있는 것만"*을 `OPENSAM-113` 요구사항으로 편입(사용자 승인). 신설 게이팅이 아니라 **기존 F2 게이팅 위의 표시 규칙**이고, 판정 정본은 서버(precheck `Presets.kt`) — 프론트가 조건을 복제 구현하면 이중 진실이 된다.
- ~~**관계 → 능력치 보정(지시 8)은 product-spec `:388` 뒤집기다.**~~ **→ 완료(2026-07-25).** 설계안이 `cleared`된 뒤 `docs/superpowers/specs/2026-07-12-opensamguk-v2-product-spec.md:388`을 개정했다 — "능력치 버프가 **아니라**" → "명령 신뢰·협상 태도에 영향을 주고 **능력치에도 보정을 준다**". 뒤집힌 사유·원문·상한(선언 ±6 / 실효 통솔 ±6·무력지력 ±8 / `GetStatValue.kt:65` 재클램프로 255 불초과)을 그 줄에 함께 박아 뒀다. ADR-LITE-021.
- ~~**게이트웨이 계정을 오리지널/뉴버전이 공유하는지 미결**~~ **→ 해소(2026-07-25). ADR-LITE-023 — 공유한다**(사용자 승인). ADR-018의 "별도 DB"는 **게임 월드 DB에만** 적용되고 게이트웨이는 공용 자산이다. 로비가 "어느 월드에 들어갈지" 고르는 화면이 된다. **새로 생긴 미결**: 닉네임·프로필·**유산 포인트** 같은 계정 부속 데이터를 어디까지 공유하는가(유산 포인트는 밸런스 직결이라 월드별 분리가 기본값으로 보이나 확정 안 함) + 공유 계정이므로 어드민 권한이 자동으로 두 버전 모두에 걸리는데 그게 의도인지.

## 3. round-3 루프 (도시 중심 · 인맥) — 진행 중

- 기준선: 묘섭 도움말 37페이지 전수 + opensamguk 코드 실측. **핵심 발견 3개** — (a) 운영자가 묘섭을 "City-oriented 삼모전"으로 자기규정하고 최대 차이를 "금·병량이 국가→도시, 도시병사 상주"로 명시(`peq.md:46,61`) → **"도시 중심" = 자원 소유권의 위치이지 지도·3D·거점 수가 아니다** → G0 없이 오픈 경로에서 가능. (b) **묘섭에도 장수↔장수 관계망이 없다** — 인맥은 인사권·배치효과·감시·자원분배 4축으로 만들어진다. (c) 묘섭 임원진 6종 이름이 이미 v1 `formatOfficerLevelText.ts`(국가레벨 7)에 칭호로 존재하고 효과만 없다.
- 채점기 `GOLDENSET-round3-city-guanxi.md` 10문항 작성. 채택 규칙 = **10/10 + 독립 reviewer `cleared`** 둘 다.
- 설계안 `round3-proposal-city-guanxi.md` 작성 → 지시 8·9 반영해 개정 1차.
- **독립 채점 결과: 5/10 `fix-required`** (`REVIEW-round3-r1.md` 전문). 자기채점 10/10 기각. N = 문항 2·4·7·8·9.
  - **C1** `nation.gold` 미러 불변식 구현 불가 — 읽기/쓰기 120지점·20+파일, 세 갈래 전부 막힘. precheck도 구조적 거짓 통과.
  - **C2** 파이프라인 조립처 2곳이 `app/game-engine`·`app/game-api`에 있는데 증명 게이트 경로에 `app/**`·`infra/**`가 빠져 **게이트가 위반을 못 본다**.
  - M4: 관계 보정 상한 ±6이 `GetStatValue.kt:54-64` 교차증강 재귀 때문에 **실효 +8** — "상한은 계약"이 계약이 아님.
  - M2: 유일성 인덱스가 방향성을 지워 **상호 원한이 조용히 삼켜짐**.
  - M0(반대 방향): 도시별 수입은 `IncomeTick.kt:29,47,65`에 **이미 공개 함수로 존재** — 중복 재작성 불필요, 저자가 걱정한 "v1 리팩터링 압력"은 애초에 없었다.
  - 티켓 수량: R7이 산출물 6종 단일 티켓이라 분해 필요 → **22는 방어되지 않는 숫자**. reviewer 권고는 "긴축 20이 기본값, 22가 권고안이 되어선 안 된다".
  - 정직성 평가는 높음 — 묘섭 인용 표본 **28건 중 27건 축자 일치**, UNKNOWN 10곳+.
- **최종: 6바퀴 만에 10/10 `cleared` — 채택(ADR-LITE-021).** 매 바퀴 *새* 독립 채점자를 붙였고 부정 판정도 전부 파일로 남겼다. 5/10 → 6/10 → 9/10 → 9/10 → 9/10 → **10/10**. 원장: `LEDGER.md`, 채점 6건 `REVIEW-round3-r1~r6.md`, 개정 6건 `REVISION-round3-r2~r7.md`.
- **실패형이 바퀴마다 달라졌고 그게 이 루프의 실질 산출물이다.**
  - 1·3·4차 = **"빠뜨렸다"** — 자기 산출물을 소비자·구현자까지 밟아 내려가지 않음(누가 파이프라인 조립부를 부르는가 → 누가 `WorldActions.register`를 부르는가 → 누가 leaf의 컨텍스트를 구현하는가). 목록을 넓혀 고친다.
  - 2차 = **날조** — UNKNOWN을 없애려고 `RaiseDisaster` 재해 라벨 7종을 지어냄("가뭄"·"전염병"은 소스에 없다). 이후 바퀴에 "`path:line`이 있는 것으로는 부족하고 그 줄이 실제로 그 내용인지가 근거다"를 규율로 박았고 재발 0.
  - 5차 = **"불가능한 줄 몰랐다"** — `HotColdWorldCatalogGuardTest`가 `WorldSnapshotLoader`를 T1 카탈로그와 `assertEquals`로 봉인해 4차 설계 2행이 **물리적으로 불가능**했다. 확장점 추적으로는 안 나오고 "이 파일을 고치면 무엇이 깨지나"를 반대 방향에서 물어야 나온다.
  - 6차 = **"물리적 제약을 지어냈다"** — 5차가 "`JdbcFlushExecutor`는 v1 템플릿에 묶여 있다"고 썼는데 그 `NamedParameterJdbcTemplate`에는 `@Qualifier`가 없다(오토컨피그 단일 DataSource). **넓힐수록 나빠지는 유일한 실패형** — 없는 제약을 피하려 설계를 우회시켜 v2 쓰기를 `DaemonWriteGuard` 사각지대에 놓을 뻔했다. 규율: *"이 코드는 X에 묶여 있다"고 쓸 때 X가 소스에 있는 이름인지 내가 붙인 해석인지 매번 구분한다.*
- **채점자가 틀린 적도 있고 그때는 저자가 이겼다** — r2의 병종연구 총액 650,000(실제 **700,000**), r3의 `UpdateNationLevel.kt:145-146` 오프바이원 지적(`:147`은 `meta`, 원문이 옳다), r5의 CRITICAL-1 근거 문장. 셋 다 후속 채점자가 "저자가 옳다"고 인정했다.
- **토폴로지는 고르는 문제가 아니라 찾는 문제였다** — 한 DB = 한 월드는 DoD로 얻어낼 약속이 아니라 `ScenarioSeedCoordinator.kt:37-49`가 `error(...)`로 이미 강제하는 코드 불변식이다(단, `ScenarioSeedRunner.kt:70-73`의 `if (!seedEnabled) return false` 뒤에 있어 **시드 활성 부팅 한정**).

## 4. 확인 불가로 남은 것 (추측으로 메우지 말 것)

- **V2-3 Operation 정산 기록의 영속 여부** — OPENSAM-56 미구현. R7·R8을 오픈 경로에 넣자는 근거("기록은 소급할 수 없다")가 이 전제 위에 있다. **참이 아니면 긴축 20안이 자동 우세.** 채택 논의 전에 이것부터 확인할 것.
- **RTK 원본에 인물관계 필드(`被親愛`/`被嫌悪`)가 존재하는지** — 근거로 든 문서는 "정제층 미포함"만 확립하며 오히려 "원천에 없는 것" 항목이다. 사전 관계 전면 데이터 계획 전체가 이 미검증 단정 위에 있었다.
- **namu.wiki 403** — 봇 차단으로 미접근. 지시 9의 링크는 읽지 못했다. 다만 리포지토리 spec이 『후한서』 백관지에 직접 근거하므로 판정에 영향 없음.

## 5. 다음 세션이 먼저 할 것

1. **`OPENSAM-35`(0A) 티켓 본문에 v2 스택 DoD 3항목이 들어갔는지 확인** — (a) v2를 **별도 compose 서비스**로 띄우고 `GAME_DATABASE_URL`·`OPENSAMGUK_WORLD_ID`·`SCENARIO_CODE`/`SCENARIO_DIR`·`V2_ENABLED`·`SPRING_PROFILES_ACTIVE`를 v1과 다른 값으로 주입 (b) v2 Flyway location은 `SPRING_FLYWAY_LOCATIONS` env 오버라이드로만(=`application.yml` 무수정, 게이트 ⑤) (c) 0A-f "프로덕션 컨텍스트 v2 빈 0" 아키텍처 테스트. **오늘 compose 파일에 v2 스택은 없다.** 특히 `SCENARIO_CODE`/`SCENARIO_DIR`을 빼면 v2가 `scenario_1010`(`ignoreDefaultEvents=false`)을 물려받아 **부팅은 성공하는데 도시 원장 수입이 안 도는 조용한 실패**가 난다.
2. **R1 착수 첫 작업으로 UNKNOWN 3건을 컴파일·실측으로 닫는다** — U9(`@Serializable` sealed 서브클래스를 원 파일 밖에 두면 wire 직렬화가 되는가. `TurnDaemonCommand.kt`는 74 variant 전부 중첩이고 리포 선례 0건) · U10(v2 시드·마이그레이션이 v2 store 첫 읽기 전에 도는가) · U12(`SPRING_FLYWAY_LOCATIONS` env 오버라이드, 표준 동작이나 리포 선례 0건). **어느 것도 티켓 수량 20의 전제가 아니다.**
3. **오픈 전 관측 3종을 계기화한다** — 국고 월간 추이 / 병종연구 최초 완료 시점 / `maxResourceActionAmount` 분포. 국고가 정기 수입원을 잃고 이전 전용 경제가 되는 것이 이 설계가 감수하는 미열거 비용이다.
4. ~~§2의 미결 2건~~ **→ 둘 다 해소(ADR-LITE-022·023).** 대신 **새 미결 2건**이 023에서 파생됐다 — 계정 부속 데이터(닉네임·프로필·**유산 포인트**) 공유 범위 / 공유 계정의 어드민 권한이 두 버전에 자동 적용되는 게 의도인지.
5. 관계망 7티켓(P0~P6)은 **오픈 후**. 설계는 `round3-proposal-city-guanxi.md` §4에 완전한 상태로 있으므로 재설계 불필요, 착수만 하면 된다.

---

# SESSION HANDOFF — 2026-06-12 (세션8: page-parity 루프 바퀴 2~26 + 재채점 10-바퀴 + W-1/W-3/W-4/W-9 수정)

다음 세션은 이 문서 + `docs/loops/page-parity/LEDGER.md`(정본 원장)부터.

> **main 직커밋·직푸시 체제 (06-11부터 PR 없이 직행). 이 핸드오프 커밋 기준 main = origin 동기화.**

## 0. 세션8 완료 (06-11 ~ 06-12, 전부 main)

- **page-parity 루프 바퀴 2~19** (06-11): P0-01 예약명령 실소비 / P0-12 city fallback / P0-27 statMin·Max / P0-18 crew 제거 / P0-17 prevNo selector / P0-26 유니크 경매 1차(후일 정정) / P0-10·P0-02 당기기·미루기·반복 버튼 / P0-28 mailbox 마스킹 1차(후일 정정) / P0-14 守 위조 '-' 마스킹. 부수: Next.js 15.1.3→15.5.19 보안 패치(`ddb0b6d`).
- **바퀴 20** (`ca419fa`): P0-07 PlaceBetHandler ← PHP `Betting::bet()` 전량 포팅 + **inheritance KV 판별자 'game_kv'→'inheritance' 근본수정**(V15 백필) — 데몬 inheritance 쓰기 전부 고아행이던 실버그.
- **재채점 워크플로** (wf_89ed4731 + `docs/superpowers/gap/regrade-2026-06-12/`, critic 10-바퀴): 바퀴 15·18 판정 뒤집힘(정정·재오픈), W-1~W-10 신규 발견.
- **바퀴 22** (`19dba54`): W-3 — 바퀴 18 over-mask 회귀 수정(diplomacy type 게이트 + 단건 마스킹).
- **바퀴 23** (`de06cff`): W-1 — 경매 위조 로그 push 6사이트 제거. **log_scope enum 외 값 1건이 flush BatchUpdateException 틱 롤백 = 턴 동결 지뢰**였음.
- **바퀴 24** (`170a960`): W-9 — P0-26 재닫음. FE 미등록 코드 `OpenUniqueAuction`→정본 `auctionOpenUnique`+`{itemId,amount}` 교체(휴식 턴 잠복 위조 소멸).
- **바퀴 25** (`a38baa8`): P0-23 — `InheritCatalog` 신설(특기 20 + 유니크 100), 실PHP Docker 2회 byte-동일 추출.
- **바퀴 26** (`db80c05` + 후속 경계수정): W-4 — AuctionBidHandler 환불 복제/미달차감/유산포인트 미차감 3결함 근절(PHP `_bid`/`bidInheritPoint`/`refundBid` 정합). **적대 리뷰(grader-w26) FAIL→PASS 2라운드**: 유니크 래퍼 부위 가드 2종 + obfuscatedName 풀 디코드 + aux.ownerName + tryExtendCloseDate 경로별 고정 + wall-clock 차단 + 유니크 finished 메시지 + 부수효과 순서 + isunited 게이트 + 환불 현재-owner 재해석 + npc>=2 검증 + wire `extendCloseDate` 키. AuctionBidHandlerTest 33종. 아티팩트 `docs/superpowers/reviews/2026-06-12-w4-auction-bid-refund-review.md`.

**게이트 수치 (바퀴 26 시점)**: logic 2123 · engine 350 · game-api 301 · infra 87 · common 192, 전부 green.

## 0a. 운영 사고 2건 + 근본수정 (06-12 새벽, 푸시 후 발견)

1. **배포 파이프라인 사망 은폐**: 06-11 박스가 멀티서버 스택(`~/opensamguk-docker`)으로 이행하며 GH 러너 유실 + 루트 디스크(8G) 100% 포화 → deploy 런 pending→cancelled 연쇄로 바퀴 22~26 미배포가 조용히 누적. 복구: EBS 100G 확장(growpart) + 러너 v2.335.1 **systemd 서비스** 재설치 + deploy.yml 신 스택 재작성(`8808c94` — CI는 공유 스택만, 게임 서버는 서버별 IMAGE_TAG 고정 설계 존중, 갱신은 어드민/deployer bounce). SSH 키 = `개인프로젝트/opens.pem`(id_ed25519 아님).
2. **"턴 되감김"의 정체 = 전 월 재생(replay)**: s1 엔진 bounce 직후 클럭이 182|6→181|1로 후퇴, 월당 ~2분으로 재생(월수입/AI 이중 적용 + 로그 중복). 근본 = `meta['lastTurnTime']`을 읽는 코드만 있고 **쓰는 코드가 없음** → 재기동마다 start_time 폴백. **근본수정 `953aa8d`**(flush가 매 틱 meta 병합 영속화 + IT 회귀 가드). 라이브 완화 = lastTurnTime 주입 + 중복 로그 5032행 삭제. **잔흔: s1 181|1~7 이중 적용(국가 74→92 증식) — 깨끗하게 하려면 s1 재시드(사용자 결정 대기)**.

## 0b. 다음 세션 우선순위

1. **재채점 잔여 4건** (LEDGER 백로그): W-6 NF income null 크래시 · W-7 NF 권한 게이트 · W-8 nation_env read 채널(setBlockWar 100% deny) · W-10 che_선전포고 위조 로그 골든.
2. LEDGER 백로그 나머지(read-api 4종, intake 6종, statistic 골든 latent 3건, OpenNationBetting 미스포트, 경매 PHP 실로그 byte-port, 유니크 1순위 가드 pending 경매 비가시, 빼섭 보급-동결 등) — 가설 1개 = 바퀴 1개.
3. 배포 후 실서버 검증(턴 전진 + 로그인 + 경매/베팅 경로) — main push = 자동 배포임을 항상 전제.

---

# SESSION HANDOFF — 2026-06-10 (세션7: 턴동결 핫픽스 + 페이지 패러티 W0/W1 + 루프 가동)

다음 세션은 이 문서부터. 핵심은 git log + `/workflows` + TaskList.

> **main = 전부 머지·배포됨 (PR #68~#79, 오늘 10+개). parity-final 브랜치는 폐기됨(전부 main에 흡수).**

## 0. 즉시 확인할 것 (재개 절차)

1. **W1 워크플로 살아있나**: run ID `wf_3fc9274f-9fc`, 스크립트
   `~/.claude/projects/-Users-apple-Desktop--------opensamguk/ee322d2d-d0c5-4ea8-a227-175a9874a611/workflows/scripts/w1-page-parity-wave-wf_3fc9274f-9fc.js`.
   죽었으면 `Workflow({scriptPath, resumeFromRunId: "wf_3fc9274f-9fc"})` — 완료 에이전트는 캐시 재개.
   구조: W0게이트워처 → 배치1보안(D board/L mailbox/C betting/H generals/O nation-finance) →
   배치2크래시(F city/G diplomacy/E chief/B auction/M map) → 배치3위조(A main/I history/J inherit/K join/N my-*) →
   LEDGER 웨이브기록 → s1/spep 바운스+실서버검증.
2. **W0 잔여 3종** (재개 에이전트가 기존 워크트리에서 작업): 워크트리 = `.claude/worktrees/agent-{aa9f04ab*,a43b4a83*,a521e815*}`
   = W0-7 wire(`w0/7-wire-contract`) / W0-4 결과채널(`w0/4-intake-result-channel`) / W0-8 infra(`w0/8-infra-flush-migrations`).
   푸시되면 워크플로 게이트워처가 자동 PR+머지. 에이전트 사망 시: 워크트리 잔존물 회수 절차(아래 §4) 후 재발사.
3. **세션 리밋 이력**: 두 번 충돌(13:2x, 15:2x — 15:30 리셋). 죽은 에이전트 = 출력파일 145바이트 헤더 동결 + 워크트리 무활동으로 판별.

## 1. 세션7 완료 (전부 main 머지)

- **프로드 턴동결 근본수정** (#69): 신규월드 첫 연경계에서 `Json.encodeToString(aux: Map<String,Any?>)` 런타임 직렬화 예외 + `statistic` 테이블 DDL 부재(2층). → `StatisticInsertColumns`+`MetaJson` / `V13__statistic_table.sql` / `StatisticFlushIT`(실DB) / 리버트가드. **s1/spep 바운스 후 턴 전진 검증 완료**. latent 3건(aux dict-vs-array 등)은 statistic 골든 백로그.
- **장수 등록 UI 패러티** (#68): npcmode 0/1/2 3서피스 + blockGeneralCreate.
- **하드코딩 단계3** (#70): OFFICER_LEVEL_TEXT→F4StateText / mapWidth·Height→map json 로더 / INHERIT_COSTS→API 소비. 잔여 = 단계5 mutation 3건(W1 합류) + 단계6 BLOCKED 4건.
- **루프 엔지니어링 가동**: `docs/loops/page-parity/` GOLDENSET(승인·동결)+LEDGER. **1바퀴 = Nation/GetGeneralLog 포팅(#71)** — 405→406 suites, fresh 채점 PASS. General alias self-view 변형은 백로그.
- **페이지 패러티 감사** (#71에 동봉): `docs/superpowers/gap/PAGE_PARITY_AUDIT_2026-06-10.md` — 20페이지 P0 54/P1 84+/P2 56+, W0 8종+W1 A~O 웨이브 계획. ⚠️ W0-3가 감사의 BLOCKED 주장 일부 반박(penalty 컬럼/meta 키 실존) — BLOCKED 주장은 실측 후 수용.
- **W0 파운데이션 6/8 머지**: W0-1 FE와이어(#73, IntakeOutcome=성공토스트위조 근원차단) / W0-2 DTO(#75+#76) / W0-3 권한 단일소스(#74, PHP 전분기+기존버그 3종 교정) / W0-5 log read(#72, SUMMARY+ACTION 합집합 발견) / W0-6 맵뷰어(#78, 두 맵뷰어 불변식).
- **운영**: 502 원인 실측(배포 경합→gateway-frontend Created 방치+nginx stale-DNS) → 즉시 복구 + deploy.yml concurrency 직렬화(#79). repo `allow_auto_merge=true` — **PR 생성 즉시 auto-merge가 표준**(사용자 지시). 묵력→무력 오타(#77).

## 2. 사용자 지시 (이 세션에서 추가된 것)

1. PR 올리면 자동 머지 (auto-merge 표준).
2. W1 끝나면: LEDGER 웨이브별 기록 + prod 배포 + 실서버 검증까지.
3. W1 후 빼섭 보급-동결 버그를 루프 바퀴로 마감 (Task #8).
4. 페이지 패러티 = 내용+기능+배치+백엔드+게임데이터 **전부**. 컴포넌트 사용·구조 = 레거시 Vue 정본, 모더나이즈는 스타일만. 무장(장수) 생성(join) 풀 패러티 강조.
5. 워크플로 도구로 오케스트레이션.

## 3. 남은 것 (TaskList와 동기)

- W0-4/7/8 마감 → W1 15페이지(워크플로가 자동 진행) → LEDGER → 배포검증 (Task #6,#7)
- 빼섭 보급-동결 바퀴 (Task #8) — doNPC구출발령 빈 supplyCities, 상류 보급계산 발산, 로컬 1030 재현
- gateway-api JwtTokenProviderTest flaky — 오늘 deploy 2회 실패 원인, 근본수정 필요
- 로컬 스택: web 3종 최신, **백엔드 3종 구이미지**(빌드 OOM) — `docker compose up -d --build` 재시도 (게이트와 동시 실행 금지, OOM 재발)
- nation-finance 감사 truncated 재감사(W1-O가 처리 예정) · battle-center 페이지 MISSING(백로그)
- prod statistic INSERT 실증(182.1 도달 시) · 어드민 표면 QA · Tier4 잔여 명령 등 기존 백로그(세션6 §2 유효)

## 4. 죽은 에이전트 회수 절차 (확립됨)

1. 워크트리 확인: `git -C <wt> log --oneline origin/main..HEAD` + `git status --short`
2. 미커밋 RED 테스트는 패치로 분리(`git diff > /tmp/x.patch && git checkout -- <f>`)
3. 게이트 재검증(XML) → 회수 크리틱 아티팩트 작성("Verdict: cleared") → strict check → push → PR
4. 잔여 단위는 백로그/후속 바퀴로 (W0-2→W0-2b 선례)

## 5. 환경/접속 (변경분)

- prod 멀티서버: 공유 스택 + s1(통일 서버)/spep 스택. 바운스: `docker compose -p opensamguk-{srv} -f ~/opensamguk/docker-compose.server.yml --env-file .env --env-file servers/{srv}.env up -d` (선행 docker pull). 502 시: Created 컨테이너 start + `nginx -s reload`.
- 어드민 peppone / (로컬 메모리 참조). EC2 `ssh -i ~/.ssh/id_ed25519 ubuntu@3.37.232.176`.

---

# SESSION HANDOFF — 2026-06-08 (세션6: B1-B3 완결 + Wave1 계략 + constants.ts + agent-system)

다음 세션은 이 문서부터. 핵심은 git log.

> **브랜치 `parity-final` = origin 동기화, main보다 20 ahead, 미배포**
> **HEAD**: `9ba9430` · **상태**: 전부 커밋+푸시 완료, working tree clean

---

## 1. 세션6 완료 (커밋 20개, `parity-final`)

### A. B1 장수생성 — 완결 (4커밋)
- `6954552` B1 RNG 코어 — MakeGeneral.draw() draw-for-draw + choiceUsingWeight insertion-order 커널 패러티 수정
- `89205f8` B1 write-seam foundation — ChangeRecorder.createdGenerals + JdbcFlushExecutor general-create flush + GeneralCreateFlushIT
- `d92a6db` B1 end-to-end — MakeGeneral variant + handler + dispatcher + game-api intake + FE PageJoin
- `5e4f045` B1 로그 정확성 — Join.php:502-528 9개 로그 byte-match

### B. B2/B3 장수빙의/선택 — 완결 (3커밋)
- `8532064` 빙의 토큰 검증 — legacy NPC selection tokens required before possession
- `63f88c8` 빙의 데몬 write path — NPC possession persist through daemon
- `8963773` 빙의 데몬 publish — NPC possession claims published to daemon

### C. Wave 1 계략 + misc — 완결 (6커밋)
- `91002bc`~`6b5eff5` Wave 1 A1 계략 5종(화계/파괴/탈취/선동/첩보) + 단련 + 접경귀환 골든 게이트
- `7f4a085` 계략 로직 포팅 + SabotageInjury
- `6b5eff5` 계략 골든 게이트 마감 + 포팅 버그 3종 수정

### D. FE 하드코딩 제거 + constants.ts — 완결 (1커밋)
- `f8ffef1` constants.ts 중앙화 + tournament-admin 미구현 명시

### E. 기타 완결 (세션4-5 미커밋 더미)
- `166351e` nation.tech 월틱 0-덮어쓰기 실버그 수정
- `c668443` 정보 카드 raw 코드→한글명 해석 (장수/국가/도시 + 세력순위)
- `d18388c` 9 event_*연구 chief research commands (turn-reserved, deterministic)
- `434a196` 게임 메인 재디자인 (로비-정적 지도 + 클릭→도시 + PHP 풀카드)

### F. Agent-system 강화 (2커밋)
- `245c357` parity work drift 방지
- `9ba9430` cross-agent critique 필수화

---

## 2. 백로그 (남은 항목만)

### A. 패러티 명령 (Tier4 #15 잔여)
- **14종 남음** (Wave1 10종 완료):
  - 계략: che_선동 (포팅됨, 골든 미확인)
  - General: che_강행, che_숙련전환, che_전투태세, che_모반시도
  - Reset: che_전투특기초기화, che_내정특기초기화
  - CR: cr_인구이동
  - (기타 5종 — PARITY_LEDGER.md 참조)
- **방법**: parity-wave 스크립트(ring/deterministic 보정)로 배치 처리

### B. FE 액션 서피스 (SILENT-NO-OP 위험)
- **chief-center nation-command 예약 에디터** — 전체 MISSING-ACTION. ReserveCommand/ReserveBulkCommand/RepeatCommand/PushCommand/clipboard/presets 전부 미구현. 현재 100% read-only.
- **diplomacy send/destroy/rollback letter** — send-letter(자유형식) 미구현, destroy-letter(파기 요청) 미구현, rollback-letter 미구현
- **board 게시물 작성/댓글** — article_add, comment_add 미구현
- **troop 부대** — 부대 생성/해산/가입/탈퇴/장수이동 미구현
- **battle 전투 예약** — 출병/방어/철수 등 전투 명령 예약 미구현

### C. 이민족/NPC 이벤트 (12종)
- RaiseInvader, InvaderEnding, AutoDeleteInvader, RaiseNPCNation, RegNPC, RegNeutralNPC, CreateManyNPC, CreateAdminNPC, BlockScoutAction, UnblockScoutAction, ChangeCity, LostUniqueItem
- WorldActions.register 미등록, 시나리오 후반부 핵심

### D. 어드민 (10종)
- _119_b(시간조정), _admin1/2/5/7/8(게임관리/회원관리/일제정보/로그/외교), _admin_force_rehall
- 현재 admin/page.tsx는 placeholder 탭만

### E. 도메인/트리거/기타
- 장수 풀 추상화 (AbsGeneralPool 등)
- GeneralTriggerCaller + 4종 트리거 (che_도시치료/병력군량소모/부상경감/아이템치료)
- Constraint/AdhocCallback, ExistsAllowJoinNation
- ScoutMessage, RaiseInvaderMessage
- DTO: MenuItem/Line/Multi/Split, SelectItem, VoteInfo/VoteComment
- TextDecoration: DyingMessage, SightseeingMessage

### F. intake-api 미등록 (6종)
- General/DieOnPrestart, General/DropItem, General/InstantRetreat
- InheritAction/CheckOwner, InheritAction/ResetStat
- Misc/UploadImage

### G. read-api 미구현 (5종)
- Nation/GetGeneralLog (alias General/GetGeneralLog)
- Global/ExecuteEngine
- Global/GeneralListWithToken
- InheritAction/GetMoreLog

### H. 운영
- **빼섭 보급-동결 버그** — 미수정 (doNPC구출발령 빈 supplyCities→RandUtil.choice throw, 상류 1030 보급 발산). 가드=band-aid.
- **재기동 rehydrate source gate 해소, live promotion 별도** — OPENSAM-149/PR #399가
  `N → flush → discard/reload → N+1` bounded 동등성과 channel quarantine matrix를 `main`에
  병합했다. 따라서 rehydrate를 이유로 한 doc-only main push blanket 금지는 해제한다.
  일반 main push 승인·CI·배포 게이트는 그대로이며, 현재 서버 이미지가 #399 이후 engine을
  실제 승격·재기동했다는 의미도 아니다. live restart/clock 전진은 배포 런북의 별도 운영
  검증으로 남는다.
- **nginx canonical** `infra/nginx/default.conf` — server-basic-info 블록 parity-final에만, main 미반영.

### I. 검증 미완
- **chief-center ChiefCommandReserve 제출 intake 왕복 end-to-end** — UI 배포됨, 실제 엔진 도달 미검증
- **Gateway-api JwtTokenProviderTest CI flaky** — 시계성, deploy.yml 비차단

---

## 3. 사용자 핵심 원칙

1. **하드코딩 금지.** 모든 표시값 = 실제 API + 기능 결과.
2. **PHP가 grand truth.** 모든 행동/로그/RNG는 devsam 충실 포팅.
3. 자율 머지+배포 OK (CI green 선결). 주석 한글, 식별자/wire/패러티로그 영문.
4. 커밋 끝 `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

---

## 4. dev 환경 상태

- **pnpm dev :3002** — web/game 핫리로드 (도커 백엔드 game-api:8081 프록시)
- **전체 docker 스택** — web-game:3001/gateway:8080/game-api:8081/engine:8082/nginx:80/pg:5433
- **gradle** — `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew ...`, 출력 tail+XML 검증
- **EC2** — `3.37.232.176`, ssh `-i ~/.ssh/id_ed25519 ubuntu@`

---

## 5. 감사 산출물 (docs/superpowers/gap/)

- `MASTER_GAP.md` — 전수 패러티 감사 (768단위, 457비교, 202미포팅, 100부분포팅)
- `FE_OUTPUT_ACTION_GAP.md` — FE 액션 페이지 갭 (chief-center/diplomacy/board/troop/battle)
- `FE_OUTPUT_READ_GAP.md` — FE read 페이지 갭
- `FE_STRUCTURE_GAP.md` — FE 구조 갭
- `LOGIC_GAP.md` — 로직 갭
- `API_GAP.md` — API 갭
- `READ_DTO_GAP.md` — read DTO 갭
- `HARDCODE_INVENTORY.md` — 하드코딩 인벤토리
- `PARITY_RECONCILED.md` — 패러티 조정 이력
- `WAVE_COVERAGE_REVIEW.md` — 웨이브 커버리지 리뷰
- `EXECUTION_PLAN.md` — 실행 계획
- `_full_audit_2026-06-07.raw.json` — 원시 감사 데이터 (5.1MB)

---

(이하 세션5/4/3 기록 — 아카이브 목적, 참조만)

# SESSION HANDOFF — 2026-06-07 (세션5: B1 코어+FE 재디자인 배포대기)
[아카이브 — 모든 미커밋 더미는 세션6에서 커밋 완료]

# SESSION HANDOFF — 2026-06-07 (세션4: B1 장수생성 RNG 코어 골든 게이트)
[아카이브 — B1 RNG 코어는 `6954552`에 커밋됨]

# SESSION HANDOFF — 2026-06-06 (세션3: 9 event_연구 + K1/K2 + 메인 크래시 근본수정)
[아카이브 — K1/K2/event_연구/메인재디자인 모두 커밋 완료]
# SESSION HANDOFF — 2026-08-04 (RTK14 final-review remediation documentation)

## 0. User direction recorded for this handoff

- Update only durable RTK14 documentation for the final review remediation; do not change code or tests. Preserve pending status: both PRs still require new exact-SHA three-round reviews, then merge, deploy, `pep` reseed, and live verification. Do not expose secrets or workbook contents.

## 1. Current source and Docker remediation

- Exact prior source HEAD `725195fea29b3434cc358e3d262c6c440830dab7` received a P1 finding: already-released V26 lacked a forward repair for affected worlds. The current source working tree no longer modifies V26 at all — `V26__npc_lifecycle_phase_units.kt` and `V26NpcLifecycleMigrationTest.kt` are reverted byte-for-byte to `origin/main` — and puts every part of the repair in one new world-scoped migration, `V38__rtk14_npc_lifecycle_repair.kt` (test: `V38Rtk14NpcLifecycleRepairMigrationTest.kt`), which runs on every world. No final full gate, merge, deployment, reseed, or live verification is complete.
- Why V38 and not an extended V26: a database that already recorded V26 in `flyway_schema_history` never re-runs it, so extending V26 could not reach upgraded worlds. Separately, on a fresh database Flyway runs before `ScenarioSeedRunner` (an `ApplicationRunner`), so `world_state` is empty and V26 returns immediately — the extension was unreachable on new worlds too. Putting the whole repair in V38, which no world has recorded yet, is what makes already-migrated worlds and freshly seeded worlds converge on the same final state.
- The claim-request migration was renumbered `V36__general_owner_claim_request.sql` → `V37__general_owner_claim_request.sql`, because `origin/main` already ships `V36__diplomacy_casualties.sql` and two V36s make Flyway fail with a duplicate version.
- V38 resolves its effective scenario external-over-classpath; uses `name[2]`/`nation[4]` action identity rather than name alone; excludes `rtk14Added`; requires universal strict event/action shape; splits verified grouped events by appearance year while preserving unrelated entries; and fails closed on ambiguity.
- The associated CodeRabbit findings are remediated in the working tree: the ambiguous-future-row case is rejected inside V38 (duplicate future-appearance identities fail closed) rather than by extending V26, importer validation rejects `appearanceYear > deathYear`, and possession uses an explicit conditional-delete branch rather than a `takeIf` side effect.
- Docker PR #25 replaces a weak indentation scan with a rendered Compose JSON contract and adds the daemon-host-relative `COMPOSE_HOST_DIR` default. This is candidate-branch validation, not a completed deployment.

## 2. Evidence and next handoff

- Focused tests are green: importer 21, possession 21, and Docker's Compose contract test. The deep repair-migration re-review is CLEARED. The earlier V26 focused count no longer applies, because V26 and its test are reverted to `origin/main`; the coverage it held moved into `V38Rtk14NpcLifecycleRepairMigrationTest` (9 cases — external-only scenario resolution, external-over-classpath precedence, per-nation deferred identity, duplicate future-appearance fail-closed, missing-scenario fail-closed, plus a new malformed-external-override rollback case), which was not re-run in this documentation pass. These focused results do not constitute a current final full gate.
- The obsolete limitation that already-V26 worlds require another future repair is superseded by V38 coverage, subject to V38 being committed, reviewed, and executed through the normal migration/deployment path.
- Next: commit/push source fixes; collect three new sequential mention reviews for the exact SHA of source PR #356 and Docker PR #25; fix any findings; merge; deploy; reseed `pep`; then observe live DB/API/UI/clock behavior.
- The private GitHub Actions material is a secret, not an input. Do not print or decode it.
- Documentation tooling note: a broad Docker-worktree keyword search was blocked by the repository secret-protection hook because its exclusion glob named a protected secret path. No secret was accessed; direct tracked Compose/test diff inspection succeeded. Treat this as an isolated documentation-tooling limitation, not product-test evidence.

---
