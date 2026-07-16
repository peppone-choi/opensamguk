# Known Issues

확인된 미해결 이슈의 **포인터** 목록. 상세 정본은 각 원장/백로그 문서. 여기 없는 이슈를 "이미 알려진 것"으로 취급하지 말 것.

## 패러티 백로그 (정본: `docs/loops/live-gap-closure-2026-07-10/LEDGER.md`)

- **대회 전투 심 파리티 갭**: `logic/tournament/ProcessTournament.kt`의 `resolveMatch()`가 결정론 점수비교인 반면 PHP 정본 `hwe/func_tournament.php` `fight()`는 에너지 기반 RNG 전투 심. `fight()` 풀 포트 + PHP 골든 캡처 필요. (바퀴 8에서 접수, 백로그)

## 문서화된 격리(quarantine — 증거 보유, 날조 아님; 정본: `CLAUDE.md` 로드맵 절)

- genfound-방랑군 (거병→건국 mini-sim 필요)
- `chooseInstantNationTurn` (PHP 호출자 0)
- Q1 `ORDER BY RAND` (do선양/오랑캐임관 — scenario 1010에서 unreachable, 결정론 대체)
- P5 long-sim multi-turn (gate dim c), G12 nation reserved-fail deny-log

## 운영 잔흔 (정본: `docs/superpowers/SESSION_HANDOFF.md` 2026-06-12 절)

- s1 181|1~7 이중 적용 잔흔(국가 74→92 증식) — 깨끗하게 하려면 s1 재시드. **사용자 결정 대기**였음; 이후 처리 여부는 prod DB로 재확인 필요 (UNKNOWN).

## Agent OS 백로그 (정본: `.omc/plans/2026-07-16-agent-os-activation-plan.md` Follow-ups)

- **Sentry prod 클라이언트 DSN 배선**: `NEXT_PUBLIC_SENTRY_DSN`은 빌드타임 인라인 — prod 이미지 빌드(deploy.yml→Dockerfile)에 빌드 아그 추가 필요. 현재는 서버 사이드(`SENTRY_DSN` 런타임 env)만 배선 없이 동작. DSN 발급 후 처리.
- **Sentry 소스맵 업로드**: `SENTRY_AUTH_TOKEN` 미설정 시 업로드 생략(의도) — 토큰 발급 후 CI에 주입.
- ~~**Spring 백엔드 Sentry SDK**~~ **해소**(2026-07-16): PR #154 6번째 커밋으로 3앱 배선 — `sentry-spring-boot-starter-jakarta`, 에러 캡처 전용(트레이싱 0), ADR-LITE-008. DSN 발급 후 대시보드 실증만 잔여(해제 조건은 w1 게이트 원장 Sentry 항목과 동일).
- **Agent OS 자체 평가 하네스**(갭⑤) · **라우터 준수 행동 테스트**(갭⑥): 이연.
- **omx `notify-fallback-watcher` 레이스**: deep-interview 상태 파일을 재덮어써 모드 전환 불가 — OMC 버그 리포트 대상.

## 도구/환경 주의

- gradle 호스트 래퍼: `task-notification` exit 0 부정확 → 출력 tail + 테스트 XML로만 판정 (정본: `AGENTS.md` §gradle context-mode).
- Testcontainers flake: `BettingUpsertFlushIT` init 1건은 접속 flake로 판정된 이력 있음(단독 재실행 green) — 실패 시 단독 재실행으로 먼저 분별. `GameApiApplicationTests`도 postgres 컨테이너 기동 flake 1회(2026-07-16, 3스위트 동시 실행 중 발생 — 단독 재실행 green).
