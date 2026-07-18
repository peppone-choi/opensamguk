# Known Issues

확인된 미해결 이슈의 **포인터** 목록. 상세 정본은 각 원장/백로그 문서. 여기 없는 이슈를 "이미 알려진 것"으로 취급하지 말 것.

## 패러티 백로그 (정본: `docs/loops/live-gap-closure-2026-07-10/LEDGER.md` · Jira `OPENSAM` Epic B = OPENSAM-2)

**표준 절차(사용자 지시 2026-07-16): 패러티 갭·버그는 발견 즉시 Jira OPENSAM 티켓으로 등재한다.**

- **대회 전투 심 파리티 갭** → **OPENSAM-10**: `ProcessTournament.kt` `resolveMatch()` 결정론 vs PHP `fight()` 에너지 기반 RNG 심. `fight()` 풀 포트 + PHP 골든 캡처 필요. (바퀴 8에서 접수)
- **G12 nation reserved-fail deny-log 미배출** → **OPENSAM-11** (P5 백로그)
- **P5 long-sim multi-turn (gate dim c)** → **OPENSAM-12** (`LongSimReplayGateTest` skip 1건)

## 문서화된 격리(quarantine — 증거 보유, 날조 아님; 정본: `CLAUDE.md` 로드맵 절)

- genfound-방랑군 (거병→건국 mini-sim 필요)
- `chooseInstantNationTurn` (PHP 호출자 0)
- Q1 `ORDER BY RAND` (do선양/오랑캐임관 — scenario 1010에서 unreachable, 결정론 대체)

## CQRS runtime safety (정본: `docs/superpowers/plans/2026-07-18-cqrs-memory-consistency-hardening-plan.md`)

- **OPENSAM-123 capacity acceptance 대기**: 2 GiB/JDK 21 seed-proxy 3×/3× 기준선은 확보했지만, sanitized production-shape manifest와 명시적 W0 임계값이 없어 티켓 완료·용량 정책 확정은 금지한다.
- **OPENSAM-124 GA-079 approval blocker**: PHP nation bulk는 예약 ring 기록 뒤 user general의 `killturn=max(env.killturn,current)`를 저장한다. Kotlin API는 이를 누락하며, API general-row write는 one-daemon-write를 위반한다. PHP 캡처와 승인된 daemon-owned lifecycle 없이는 ring-only 또는 비계약 비동기 분리를 구현하지 않는다.

## 운영 잔흔 (정본: `.ai/handoff.md` 최신 상태 + `docs/superpowers/SESSION_HANDOFF.md` 장기 이력 — s1 이중 적용 상세는 2026-06-12 절)

- **EC2 prod 요금 미납 정지**(2026-07-16 사용자 확인): prod 관련 작업 전부 **보류** — 배포, EC2 `.env` DSN 반영, prod DB 재확인. 납부·정지 해제 후 재개. 정지 기간 main push의 `deploy.yml` 런은 성공 불가 — 2026-07-16 미완료 런 2건(6h queued 포함) 취소 처리; 해제 후 최신 main으로 `gh run rerun` 또는 새 push로 배포.
- s1 181|1~7 이중 적용 잔흔(국가 74→92 증식) — 깨끗하게 하려면 s1 재시드. **사용자 결정 대기**였음; 이후 처리 여부는 prod DB로 재확인 필요 (UNKNOWN, EC2 정지로 보류).

## Agent OS 백로그 (정본: `.omc/plans/2026-07-16-agent-os-activation-plan.md` Follow-ups)

- **Sentry prod 클라이언트 DSN 배선**: `NEXT_PUBLIC_SENTRY_DSN`은 빌드타임 인라인 — prod 이미지 빌드(deploy.yml→Dockerfile)에 빌드 아그 추가 필요. 현재는 서버 사이드(`SENTRY_DSN` 런타임 env)만 배선 없이 동작. DSN은 발급 완료(2026-07-16, 로컬 `.env`/`.env.local` 주입) — 빌드 아그 작업만 잔여. prod EC2 `.env`에도 DSN 반영 필요.
- **Sentry 소스맵 업로드**: `SENTRY_AUTH_TOKEN` 발급 완료(2026-07-16, 로컬 `.env` 보관 — 채팅 노출 이력 있어 회전 권장) — CI(GitHub Actions secret) 주입 잔여.
- ~~**Spring 백엔드 Sentry SDK**~~ **해소**(2026-07-16): PR #154 6번째 커밋으로 3앱 배선 — `sentry-spring-boot-starter-jakarta`, 에러 캡처 전용(트레이싱 0), ADR-LITE-008. DSN 발급 후 대시보드 실증만 잔여(해제 조건은 w1 게이트 원장 Sentry 항목과 동일).
- **Agent OS 자체 평가 하네스**(갭⑤) · **라우터 준수 행동 테스트**(갭⑥): 이연.
- **omx `notify-fallback-watcher` 레이스**: deep-interview 상태 파일을 재덮어써 모드 전환 불가 — OMC 버그 리포트 대상.

## 도구/환경 주의

- gradle 호스트 래퍼: `task-notification` exit 0 부정확 → 출력 tail + 테스트 XML로만 판정 (정본: `AGENTS.md` §gradle context-mode).
- Testcontainers flake: `BettingUpsertFlushIT` init 1건은 접속 flake로 판정된 이력 있음(단독 재실행 green) — 실패 시 단독 재실행으로 먼저 분별. `GameApiApplicationTests`도 postgres 컨테이너 기동 flake 1회(2026-07-16, 3스위트 동시 실행 중 발생 — 단독 재실행 green).
- **web/game vitest 부하 민감 플레이크**(2026-07-17): 호스트 CPU 포화(외부 프로세스·Docker 빌드 병행, load avg 800+) 시 jsdom 파일들이 광범위 타임아웃 실패(18파일 21건까지 관측). 판정 절차 = 실패 파일 **단독 재실행**으로 분별(전부 green이면 부하 플레이크), 필요 시 `--fileParallelism=false` 직렬 실행. 코드 회귀로 오판하지 말 것. 2026-07-18 재관측: `PartialReservedCommand.test.tsx:45` 1건 — 근인은 테스트 자체 레이스(waitFor가 API mock 호출까지만 대기, 렌더 반영 전 동기 단정; 45-53행 단정을 waitFor 안으로 옮기면 근치). 별도 정리 대상.
- **Docker Desktop 인-컨테이너 gradle 빌드 크래시**(2026-07-17): compose 앱 이미지 빌드(gradle bootJar in-container)가 VM 리소스(8GB) 한계로 데몬 크래시 유발 — 병렬 빌드 금지, 순차로도 불안정. 로컬 E2E 스택은 **백엔드 호스트 gradle 네이티브 기동 + Docker는 postgres/redis만** 전략 사용.
- **AGENTS.md §들여쓰기 문서 드리프트**(2026-07-17, PR #155 CodeRabbit 계기로 실측): `AGENTS.md:108`은 `.ts`/`.tsx` 2칸이라 하나 실제 `web/game`·`web/gateway` 코드베이스는 레벨당 4칸이 지배 관례(`api.ts`, `page.tsx` 등). 신규 코드는 주변 코드(4칸)를 따른다 — AGENTS.md 문구 정정 필요.
