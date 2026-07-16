# Project Overview — opensamguk

## 목적

PHP 게임 **devsam/core**(삼국지 모의전투 HiDCHe / 삼모)를 **Kotlin/Spring + Next.js + PostgreSQL + Redis + nginx**의 메모리 중심 CQRS 스택으로 **byte 단위 충실 이식**하고, EC2에서 라이브 서비스(sam.peppone.dev)로 운영한다. v1(패러티)이 기반이고, v2(실시간 대형 전장 등 신규 콘텐츠)가 그 위에 준비 중이다.

## 핵심 소비자

- 라이브 게임 유저(sam.peppone.dev), 그리고 이 저장소에서 작업하는 사람+AI 에이전트 팀.

## 현재 구현 범위 (2026-07-16 기준)

- ✅ P0–P6(+P7 read API): 패러티 커널·명령·월간 틱·전투 엔진·NPC AI·베팅/경매/외교/메시지.
- ✅ F0–F3: 게이트웨이 인증, 시나리오 시드, 게임 메인/메뉴, 랭킹·내정보.
- 🔄 F4–F5: 액션 페이지 mutation, turnkey compose/docs. live-gap-closure 루프로 잔여 폐쇄 중.
- ⬜ P8 잔여: 패러티 하니스 통합, 일부 P6 골든 캡처.
- v2: 기획 수렴 완료(round-2 adopted), 구현 전. 상세: `CLAUDE.md` 로드맵 절.

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

- 라이브: EC2 3.37.232.176(ubuntu), GHCR 이미지, GitHub Actions 배포. **런타임 외부 API 의존 0, LLM-free.**
- 오라클: `legacy/devsam-core`(PHP, git-ignore) — 모든 동작의 grand truth. `tools/php-golden/` Docker 캡처 하니스.

## 핵심 품질 속성

1. **패러티**: RNG draw-for-draw, half-away 반올림, 한글 로그 byte-일치, 삽입 순서. (`CLAUDE.md` §Parity가 정본)
2. **아키텍처 불변식**: one-daemon-write rule (데몬 write는 `ChangeRecorder`→`JdbcFlushExecutor`만).
3. **증거 기반**: 골든은 실 PHP 캡처만, 완료 판정은 게이트 XML.

## 확인된 제약

- JDK 21 필수(Gradle 8.12가 Java 25 파싱 실패). gradle exit code 신뢰 불가(호스트 래퍼) — 출력 tail + XML로 판정.
- 저장소 비공개(Koei IP 검토 전). `legacy/` 커밋 금지.
- main push = 자동 배포(라이브 서버 직결).

## 주요 파일 경로

- 규칙 정본: `CLAUDE.md` · 요약: `AGENTS.md` · 하니스 지도: `.claude/HARNESS.md`
- 운영 계약: `docs/superpowers/WORKING_SYSTEM.md` · 루프 원장: `docs/loops/*/LEDGER.md`
- 게이트: `tools/parity/gate.sh` · 에이전트 시스템 체크: `tools/agent-system/check.py` · 스모크: `tools/smoke.sh`
- 현재 상태: `.ai/task.md`, `.ai/current-state.md`
