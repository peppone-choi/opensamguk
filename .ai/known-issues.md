# Known Issues

확인된 미해결 이슈의 **포인터** 목록. 상세 정본은 각 원장/백로그 문서. 여기 없는 이슈를 "이미 알려진 것"으로 취급하지 말 것.

## 동결 회귀·테스트 disposition 백로그 (역사 포인터: `docs/loops/live-gap-closure-2026-07-10/LEDGER.md` · Jira `OPENSAM` Epic B = OPENSAM-2)

**ADR-LITE-042: 현재 결함은 승인 ADR/spec·구현·테스트로 disposition하고, PHP 캡처는 opt-in 역사 증거로만 사용한다. 발견한 제품 버그는 Jira OPENSAM 티켓으로 등재한다.**

- **대회 전투 심 동결 회귀 차이** → **OPENSAM-10**: `ProcessTournament.kt` `resolveMatch()` 결정론과 역사 PHP `fight()` 에너지 기반 RNG 심의 차이. 현재 승인된 대회 spec과 결정론 테스트로 disposition하며, PHP 캡처는 명시적 역사 비교에서만 선택한다. (바퀴 8에서 접수)
- **G12 nation reserved-fail deny-log 미배출** → **OPENSAM-11** (P5 백로그)
- **P5 long-sim multi-turn (gate dim c)** → **OPENSAM-12** (`LongSimReplayGateTest` skip 1건)
- **외교 수락 production resolver의 PHP 다중 로그/event 미포팅**(2026-07-30,
  OPENSAM-32 독립 리뷰): `ProcessNationCommand`는 등록 resolver를 logic
  command보다 우선하고, 종전·불가침 파기 수락의 production resolver는
  Jira D4-09/13의 양방향 상태/term만 기록한다. PHP 수락 command의
  general/nation/global history/action 로그와 StaticEvent는 역사 비교에서 차이가
  남아 있다. OPENSAM-32의 승인된 상태 전이 범위에는 포함되지 않으며 현재 opt-in 역사 byte-log 동결 회귀
  의무는 아니다. 제품 로그 요구가 생기면 현재 spec과 회귀 테스트로 별도 disposition한다.
  현재 작업 계약이 Jira mutation을 금지하므로 외부 티켓 생성은 수행하지
  않았고, 완료 문구는 상태 전이로만 제한해야 한다.
- **RTK14 `scenario_3200` 군주 공석 격리** (2026-07-19, Batch 4): 손책은 `200.1` 원자료에서 `君主`이지만 `death=200`이라 PHP/Kotlin 시작-수명 필터에서 제외된다. 역사 PHP `Scenario/Nation.php::postBuild`(강한 장수 자동 승격)는 후계를 만들지만 Kotlin `ScenarioImporter`에는 같은 동작이 없다. Batch 4 보고서는 국가 6을 `seed_ready=false` / `pending v2 ruler-promotion disposition`으로 기계적 격리하며, 생몰년·관직·importer를 임의 수정하지 않는다. 관직 체계 변경은 사용자 결정에 따라 v2 범위. Jira 등록은 이 세션에서 외부 연결 403 + 변경 미승인으로 미수행.
- **RTK14 전체 정제 스키마 ↔ Batch 4 v1 시드 투영 정합** (2026-07-19): 정식 시나리오 스펙 §2.1/§4의 전체 레코드(정책·특성·전법·초상 및 portrait 기반 registry)와 달리 OPENSAM-143 파일럿 도구는 기존 importer에 필요한 7스탯·소속·소재·v1 관직만 담은 ignored projection을 생성한다. 캐시된 전체 정제본과 projection의 1,000 ID/중복 이름 그룹은 일치해 현재 churn은 없지만, T6 잔여 시나리오·라이브 컷오버 전에 두 스키마와 registry header를 병합하거나 명시 승인해야 한다. Batch 4는 전체 정제본을 대체하지 않으며 이 v2 정합 작업을 조용히 완료로 간주하지 않는다.

## 문서화된 격리(quarantine — 증거 보유, 날조 아님; 정본: `CLAUDE.md` 로드맵 절)

- genfound-방랑군 (거병→건국 mini-sim 필요)
- `chooseInstantNationTurn` (PHP 호출자 0)
- Q1 `ORDER BY RAND` (do선양/오랑캐임관 — scenario 1010에서 unreachable, 결정론 대체)

## CQRS runtime safety (정본: `docs/superpowers/plans/2026-07-18-cqrs-memory-consistency-hardening-plan.md`)

- **OPENSAM-123 live capacity acceptance 대기**: sanitized manifest 경계는 validation-only로 fail-closed하며, checked-in local deterministic materializer는 `op123-local-20260719b` 3×2를 완료했지만 production/live shape를 대신하지 않는다. 최신 live 증거는 `world_state=1, city=94, nation=19, general=598`만 남았고, 필수 `log/history/rank/diplomacy/game_kv/nation_env/statistic/auction` cardinality·payload·provenance는 정지된 EC2/EBS에만 있다. 정지가 풀리고 complete approved live aggregate manifest/restore가 마련되기 전에는 live 3×2·임계값·티켓 완료를 주장하지 않는다.
- **OPENSAM-124 durable activation blocker**: PHP GA-079 2회 캡처, daemon-owned per-child lifecycle, `OPENSAM-148` canonical identity land는 완료됐다. 남은 activation chain은 진행 중인 S2 scoped schema/runtime/two-world gate → S3 generation/fence/CAS/recovery → W3 durable world-scoped CAS/fenced flush binding이다. API general-row write·ring-only 활성화는 계속 금지한다.

## 운영 잔흔 (정본: `.ai/handoff.md` 최신 상태 + `docs/superpowers/SESSION_HANDOFF.md` 장기 이력 — s1 이중 적용 상세는 2026-06-12 절)

- **EC2 prod 요금 미납 정지**(2026-07-16 사용자 확인): prod 관련 작업 전부 **보류** — 배포, EC2 `.env` DSN 반영, prod DB 재확인. 납부·정지 해제 후 재개. 정지 기간 main push의 `deploy.yml` 런은 성공 불가 — 2026-07-16 미완료 런 2건(6h queued 포함) 취소 처리; 해제 후 최신 main으로 `gh run rerun` 또는 새 push로 배포.
- s1 181|1~7 이중 적용 잔흔(국가 74→92 증식) — 깨끗하게 하려면 s1 재시드. **사용자 결정 대기**였음; 이후 처리 여부는 prod DB로 재확인 필요 (UNKNOWN, EC2 정지로 보류).
- **Redis wake marker의 outer-transaction 경계 미검증**(2026-07-31,
  OPENSAM-33): 직접 command HTTP 경로의 raw `Instant` JDBC bind 결함은
  `Timestamp.from`과 live Redis marker/ACK gate로 해소됐다. 다만
  `GeneralPossessionService.claim`처럼 외부 `@Transactional` 안에서
  `publishImmediate`를 호출하는 경로는 Spring `afterCommit` 시 기존 JDBC
  자원이 계속 bound된 상태라 marker UPDATE가 별도 commit 없이 끝날 위험이
  있다. 이 경로는 이번 live fixture 대상이 아니며 outer-TX IT +
  marker-only `REQUIRES_NEW` 검토가 후속이다. `REQUIRES_NEW`는 XADD 전체가
  아니라 marker UPDATE에만 적용해야 DB-before-Redis/best-effort 순서를
  보존한다.
- **operational smoke의 중복 SSE 구독 관측**(2026-07-31, OPENSAM-33):
  한 live run에서 고유 tick 수보다 많은 EventSource open/`turnCompleted`가
  기록됐다. 실제 재연결인지 중복 subscription인지 원인은 UNKNOWN이다.
  stale-UI 수용 기준(SSE 뒤 front-info fetch와 DOM 갱신)은 통과했지만,
  운영 부하 관점의 중복 구독 원인 분석은 별도 후속이다.

## Agent OS 백로그 (정본: `.omc/plans/2026-07-16-agent-os-activation-plan.md` Follow-ups)

- **Sentry prod 클라이언트 DSN 배선**: `NEXT_PUBLIC_SENTRY_DSN`은 빌드타임 인라인 — prod 이미지 빌드(deploy.yml→Dockerfile)에 빌드 아그 추가 필요. 현재는 서버 사이드(`SENTRY_DSN` 런타임 env)만 배선 없이 동작. DSN은 발급 완료(2026-07-16, 로컬 `.env`/`.env.local` 주입) — 빌드 아그 작업만 잔여. prod EC2 `.env`에도 DSN 반영 필요.
- **Sentry 소스맵 업로드**: `SENTRY_AUTH_TOKEN` 발급 완료(2026-07-16, 로컬 `.env` 보관 — 채팅 노출 이력 있어 회전 권장) — CI(GitHub Actions secret) 주입 잔여.
- ~~**Spring 백엔드 Sentry SDK**~~ **해소**(2026-07-16): PR #154 6번째 커밋으로 3앱 배선 — `sentry-spring-boot-starter-jakarta`, 에러 캡처 전용(트레이싱 0), ADR-LITE-008. DSN 발급 후 대시보드 실증만 잔여(해제 조건은 w1 게이트 원장 Sentry 항목과 동일).
- **Agent OS 자체 평가 하네스**(갭⑤) · **라우터 준수 행동 테스트**(갭⑥): 이연.
- **omx `notify-fallback-watcher` 레이스**: deep-interview 상태 파일을 재덮어써 모드 전환 불가 — OMC 버그 리포트 대상.

## 도구/환경 주의

- **OPENSAM-33 discovery의 `.env.example` read 범위 위반**(2026-07-30):
  cadence/compose mapping을 찾는 focused `rg`가 committed template
  `.env.example`까지 포함했다. 같은 파일은 safe-local placeholder만 담지만
  Agent OS의 `.env*` read 금지보다 넓게 읽은 절차 위반이다. 실제 `.env`,
  user secret, production credential은 읽거나 출력하지 않았고 파일 변경도
  없었다. 이후 OPENSAM-33 명령은 `.env*`를 명시 제외하며, local smoke
  credential은 실행 시 임시 생성하고 값은 artifact/log에 남기지 않는다.
- **Agent OS 기준선 실패 — 사용자 소유 `.codex/config.toml`**(2026-07-18): 현재 작업 전부터 존재한 개인 모델 고정(`model = "gpt-5.6-sol"`) 때문에 `tools/agent-system/check.py --strict --base origin/main`의 `codex-surface`가 1건 실패한다. `max_threads` 누락은 `scripts/agent/test-codex-agent-os.sh`에서 `max_depth` fallback로 처리되어 더 이상 `KeyError`로는 막지 않는다. 이 문제는 사용자 변경 보존 차원에서 별도 완화 없이 운영 보류로 유지한다.
- **Agent OS historical review anchor 기준선**(2026-07-30): branch diff에 포함된 `docs/superpowers/reviews/2026-07-27-v1-nonoperational-completion-review.md`의 유일한 anchored verdict가 `Verdict: CLEARED`(대문자)다. 현재 `tools/agent-system/check.py --strict --base origin/main`은 `cleared|fix-required|quarantined-with-proof` 소문자만 허용하므로 `cross-agent-critique` 1건이 추가 실패한다. 파일은 현재 worktree에서 수정되지 않은 과거 종결 아티팩트라 OPENSAM-31 범위에서 고치지 않고 baseline으로 격리한다.
- **Fablize 보조 래퍼 경고 기준선**(2026-07-19): exit 0인 read/status/test 명령에도 generic `tool failure` notice가 반복된다. 실제 실패는 직접 종료코드, Gradle tail, 테스트 XML, artifact SHA로 분리 판정하며 동일 broad 명령은 반복하지 않는다.
- gradle 호스트 래퍼: `task-notification` exit 0 부정확 → 출력 tail + 테스트 XML로만 판정 (정본: `AGENTS.md` §gradle context-mode).
- Testcontainers flake: `BettingUpsertFlushIT` init 1건은 접속 flake로 판정된 이력 있음(단독 재실행 green) — 실패 시 단독 재실행으로 먼저 분별. `GameApiApplicationTests`도 postgres 컨테이너 기동 flake 1회(2026-07-16, 3스위트 동시 실행 중 발생 — 단독 재실행 green).
- **web/game vitest 부하 민감 플레이크**(2026-07-17): 호스트 CPU 포화(외부 프로세스·Docker 빌드 병행, load avg 800+) 시 jsdom 파일들이 광범위 타임아웃 실패(18파일 21건까지 관측). 판정 절차 = 실패 파일 **단독 재실행**으로 분별(전부 green이면 부하 플레이크), 필요 시 `--fileParallelism=false` 직렬 실행. 코드 회귀로 오판하지 말 것. 2026-07-18 재관측: `PartialReservedCommand.test.tsx:45` 1건 — 근인은 테스트 자체 레이스(waitFor가 API mock 호출까지만 대기, 렌더 반영 전 동기 단정; 45-53행 단정을 waitFor 안으로 옮기면 근치). 별도 정리 대상.
- **Docker Desktop 인-컨테이너 gradle 빌드 크래시**(2026-07-17): compose 앱 이미지 빌드(gradle bootJar in-container)가 VM 리소스(8GB) 한계로 데몬 크래시 유발 — 병렬 빌드 금지, 순차로도 불안정. 로컬 E2E 스택은 **백엔드 호스트 gradle 네이티브 기동 + Docker는 postgres/redis만** 전략 사용.
- **AGENTS.md §들여쓰기 문서 드리프트**(2026-07-17, PR #155 CodeRabbit 계기로 실측): `AGENTS.md:108`은 `.ts`/`.tsx` 2칸이라 하나 실제 `web/game`·`web/gateway` 코드베이스는 레벨당 4칸이 지배 관례(`api.ts`, `page.tsx` 등). 신규 코드는 주변 코드(4칸)를 따른다 — AGENTS.md 문구 정정 필요.
- **#507 은 카탈로그만 바꿨고 타일 산출물은 아직 stale** (2026-08-24, OPENSAM-226 조사): `data/curated/han/administrative-units.json`은 COMMANDERY 85/KINGDOM 20으로 올바르지만 실제 서빙되는 `data/map/han-tiles.json`은 재생성되지 않았다(현재 KINGDOM 0). 재생성을 시도하면 `build_han_places.py`의 TIER 분류가 侯國/屬國/郡을 KINGDOM으로 오분류하는 별개 결함까지 걸린다. 상세·재현 명령·경로망 계약(`_scenario_resources()` 31 vs 15) 회귀 경위는 #518.
