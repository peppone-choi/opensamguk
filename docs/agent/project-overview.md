# Project Overview — opensamguk

## 목적

PHP 게임 **devsam/core**(삼국지 모의전투 HiDCHe / 삼모) 이식에서 출발한 제품을 **Kotlin/Spring + Next.js + PostgreSQL + Redis + nginx**의 메모리 중심 CQRS 스택으로 운영하고 발전시킨다. 기존 v1 결과는 동결 회귀 기준선이며, 신규 세계·시스템·UX는 오픈삼국의 승인된 제품 결정을 따른다(ADR-LITE-042).

## 핵심 소비자

- 라이브 게임 유저(sam.peppone.dev), 그리고 이 저장소에서 작업하는 사람+AI 에이전트 팀.

## 현재 구현 범위 (2026-08-20 기준)

이 문서는 온보딩용 **bounded status**다. 2026-07-30 v1 비운영 종결은 역사적 회귀 기준선이고, 신규 제품
기준은 ADR-LITE-041/042와 `docs/design/roadmap.md`를 따른다. v1 상세 근거는
`docs/superpowers/research/2026-07-26-v1-legacy-equivalence-audit.md`의 최종 부록,
`docs/loops/v1-nonoperational-completion-2026-07-27/LEDGER.md`와 review를 따른다.

- ✅ v1 비운영 감사 §6.1–§6.8: 명령, 월간/이벤트, 전투/점령, AI, side system,
  world scope/restart, 프런트, 저장 로그를 PHP capture·Kotlin replay·local Docker로
  재측정해 종결. `SelectPool`·`VotePoll`·`DiplomacyLetter`의 V32 복합 key/local-ID
  read 경계도 outer/nested `WorldId` query·중앙 scoped beans·same-local-ID 2-world
  regression으로 보정했고 final parity review는 `CLEARED`다. v1 날짜는 **연 36순**이다(ADR-LITE-024).
- ✅ F0–F5의 **로컬** 표면: 인증/시드/메뉴/read/mutation/turnkey compose와
  인증 브라우저 restart gate까지 관측. corrected gate는 five images sequential,
  8 health, Playwright 1 passed (`241634ms`), join `RESOLVED`/general `1230`, 14 DOM,
  restart general/result/repository `200`, auth `false|false` 복원, project containers 0을 확인했다.
- ✅ CQRS foundation S1–S5: `world_id` scope, generation/fence/recovery,
  durable inbox/result/outbox, bounded boot/read barrier. 이 foundation은 S6
  운영 활성화와 구분한다.
- ⬜ S6/activation: canary·expand/backfill·replica ADR, capacity/admission,
  production cutover는 **별도 인간 승인**과 운영 게이트 전까지 미수행이다.
- 🔄 신규 제품 트랙: 한나라 175군·780성·14지역, 공유 디자인 시스템, 서버 격리를
  승인·진행 상태에 따라 구현한다. 승인된 목표를 구현 완료로 오해하지 않는다.

이 상태는 git action 전에도 유효한 release-candidate 관측 증거다. commit, push,
merge, deploy가 실행되었거나 승인되었다는 뜻은 아니다.

## 주요 모듈 (정본: `settings.gradle.kts`, `AGENTS.md` §모듈 구조)

| 모듈 | 책임 |
|---|---|
| `common` | RNG 커널(`LiteHashDrbg`/`RandUtil`), `PhpRound`, 한글 로그 커널, `GameConst` |
| `logic` | 순수 게임 로직(Spring/DB 없음): actions, war, ai, event, tick |
| `infra` | `JdbcFlushExecutor`, Flyway, Redis, JPA read repo, 시나리오 시드 |
| `app/gateway-api` :8080 | 인증/프로필/어드민 |
| `app/game-api` :8081 | read + precheck + intake + SSE |
| `app/game-engine` :8082 | 턴 데몬(`InMemoryTurnWorld` = 진실 원천) |
| `web/gateway` :3000 · `web/game` :3001 | Next.js 프론트 |

## 외부 시스템

- 라이브 제어면: 별도 `opensamguk-docker` 저장소의 shared/server/deployer 모델. 이 앱 저장소의 GitHub Actions는 GHCR 이미지를 만들고 shared stack을 갱신하지만, 각 게임 서버의 이미지 핀 승격은 별도 운영 행위다. 이 저장소의 `docker-compose.production.yml`·`scripts/deploy.sh`는 호환 전용이다. **런타임 외부 API 의존 0, LLM-free.**
- 역사·회귀 참고: `legacy/devsam-core`(PHP, git-ignore), `hwe/ts/`, `tools/php-golden/` Docker 캡처 하니스. 신규 설계 정본은 최신 승인 ADR·spec·현재 구현이며, 레거시 비교는 명시적으로 요청된 동결 회귀 유지보수에서만 필수다.

## 핵심 품질 속성

1. **결정론적 회귀**: 같은 seed·입력·순서의 재현, 동결 골든 보존, 의도적인 수치·로그 변경 기록. PHP draw-for-draw는 신규 설계 제약이 아니다.
2. **아키텍처 불변식**: one-daemon-write rule (데몬 write는 `ChangeRecorder`→`JdbcFlushExecutor`만).
3. **CQRS 정합성**: world-scoped identity, generation/fence/recovery, durable command path, bounded reads는 build-only foundation이며 activation과 구분한다.
4. **증거 기반**: 기존 골든은 실 캡처 기반 동결 자산이며, 완료 판정은 현재 spec과 실제 게이트·사용 표면 관측으로 한다.

## 확인된 제약

- JDK 21 필수(Gradle 8.12가 Java 25 파싱 실패). gradle exit code 신뢰 불가(호스트 래퍼) — 출력 tail + XML로 판정.
- 저장소 비공개(Koei IP 검토 전). `legacy/` 커밋 금지.
- main push는 shared-stack 자동 갱신을 일으키므로 사람 승인 게이트 대상이다. 이는 실행 중 게임 서버의 버전 핀 자동 승격을 뜻하지 않는다.

## 주요 파일 경로

- 제품·규칙 정본: `.ai/decisions.md` · `CLAUDE.md` · 요약: `AGENTS.md` · 문서 포털: `docs/README.md`
- 운영 계약: `docs/superpowers/WORKING_SYSTEM.md` · 루프 원장: `docs/loops/*/LEDGER.md`
- 게이트: `tools/parity/gate.sh` · 에이전트 시스템 체크: `tools/agent-system/check.py` · 스모크: `tools/smoke.sh`
- 현재 상태: `.ai/task.md`, `.ai/current-state.md`
