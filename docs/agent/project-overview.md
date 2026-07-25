# Project Overview — opensamguk

## 목적

PHP 게임 **devsam/core**(삼국지 모의전투 HiDCHe / 삼모)를 **Kotlin/Spring + Next.js + PostgreSQL + Redis + nginx**의 메모리 중심 CQRS 스택으로 **byte 단위 충실 이식**하고, shared/server `opensamguk-docker` 운영 모델로 라이브 서비스를 준비·운영한다. v1(패러티)이 기반이고, v2(실시간 대형 전장 등 신규 콘텐츠)가 그 위에 준비 중이다.

## 핵심 소비자

- 라이브 게임 유저(sam.peppone.dev), 그리고 이 저장소에서 작업하는 사람+AI 에이전트 팀.

## 현재 구현 범위 (2026-07-25 기준)

이 문서는 온보딩을 위한 **bounded status**다. 티켓별 증거·활성화 잔여는 `.ai/current-state.md`, PHP/UI 갭은 해당 `docs/loops/*/LEDGER.md`를 따른다.

- ✅ P0–P6(+P7 read API): 패러티 커널·명령·월간 틱·전투 엔진·NPC AI·베팅/경매/외교/메시지.
- ✅ F0–F3: 게이트웨이 인증, 시나리오 시드, 게임 메인/메뉴, 랭킹·내정보.
- ✅ CQRS foundation S1–S5 (**build-only**, main 머지): `world_id` 스코프와 2월드 격리, `DeltaGenerationSession`·`world_version` CAS·`writer_epoch`·`FlushRecoveryGate`, durable inbox/result/outbox 경로, hot/cold 카탈로그·bounded/on-demand boot read, primary `minVersion` visibility barrier.
- 🔄 F4–F5: 실제 mutation 페이지 배선과 로컬 turnkey 문서/compose를 계속 닫는 중. 메일함 삭제·외교 서신 응답·인사부 임면·내정보 즉시 액션·엔진 deny 표면화는 배선됐지만, 전체 명령/페이지 동형 주장이 아니다.
- ⬜ S6/activation: canary·expand/backfill·replica ADR, capacity/admission policy, 프로덕션 cutover는 별도 승인·운영 게이트 전까지 미수행.
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

- 라이브 제어면: 별도 `opensamguk-docker` 저장소의 shared/server/deployer 모델. 이 앱 저장소의 GitHub Actions는 GHCR 이미지를 만들고 shared stack을 갱신하지만, 각 게임 서버의 이미지 핀 승격은 별도 운영 행위다. 이 저장소의 `docker-compose.production.yml`·`scripts/deploy.sh`는 호환 전용이다. **런타임 외부 API 의존 0, LLM-free.**
- 오라클: `legacy/devsam-core`(PHP, git-ignore) — 모든 동작의 grand truth. `tools/php-golden/` Docker 캡처 하니스.

## 핵심 품질 속성

1. **패러티**: RNG draw-for-draw, half-away 반올림, 한글 로그 byte-일치, 삽입 순서. (`CLAUDE.md` §Parity가 정본)
2. **아키텍처 불변식**: one-daemon-write rule (데몬 write는 `ChangeRecorder`→`JdbcFlushExecutor`만).
3. **CQRS 정합성**: world-scoped identity, generation/fence/recovery, durable command path, bounded reads는 build-only foundation이며 activation과 구분한다.
4. **증거 기반**: 골든은 실 PHP 캡처만, 완료 판정은 게이트 XML.

## 확인된 제약

- JDK 21 필수(Gradle 8.12가 Java 25 파싱 실패). gradle exit code 신뢰 불가(호스트 래퍼) — 출력 tail + XML로 판정.
- 저장소 비공개(Koei IP 검토 전). `legacy/` 커밋 금지.
- main push는 shared-stack 자동 갱신을 일으키므로 사람 승인 게이트 대상이다. 이는 실행 중 게임 서버의 버전 핀 자동 승격을 뜻하지 않는다.

## 주요 파일 경로

- 규칙 정본: `CLAUDE.md` · 요약: `AGENTS.md` · 하니스 지도: `.claude/HARNESS.md`
- 운영 계약: `docs/superpowers/WORKING_SYSTEM.md` · 루프 원장: `docs/loops/*/LEDGER.md`
- 게이트: `tools/parity/gate.sh` · 에이전트 시스템 체크: `tools/agent-system/check.py` · 스모크: `tools/smoke.sh`
- 현재 상태: `.ai/task.md`, `.ai/current-state.md`
