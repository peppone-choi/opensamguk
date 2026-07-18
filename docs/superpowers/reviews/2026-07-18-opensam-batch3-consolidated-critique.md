# Batch-3 (OPENSAM-92·93·94·97·103 + 91 후속) 통합 크로스-에이전트 크리틱

Scope: batch-3 전체 변경 — app/gateway-api·app/game-api·app/game-engine, common/src/main/kotlin/opensamguk/common/wire(TurnDaemonCommand), infra/src(UserEntity·JdbcFlushExecutor·UserRepository), web/gateway·web/game, tools/rtk14(hexmap), .codex/config.toml(개인 모델 핀 제거), nginx/docker-compose/gradle 부속 변경 포함
Verdict: cleared

작성일: 2026-07-18 · 작성 주체: batch3-closeout 오케스트레이터(집계) · 판정 근거: 아래 7건의 **독립 리뷰어 에이전트** 원본 리뷰

## 1. 이 문서의 성격

이 문서는 자체 재리뷰가 아니라 **집계 아티팩트**다. batch-3의 각 티켓은 구현 레인과 분리된 독립 리뷰어 에이전트가 READ-ONLY로 검토했고, 원본 리뷰 7건이 `docs/loops/opensam-batch3-2026-07-17/reviews/`에 보존되어 있다. 최종 판정은 전부 원본 리뷰의 것이며, 이 문서는 PR 단위 게이트(`tools/agent-system/check.py --strict`)가 요구하는 전 영역 커버 크리틱 앵커를 제공한다.

## 2. 원본 독립 리뷰 7건 (전건 cleared)

| 리뷰 파일 | 대상 | 최종 판정 |
|---|---|---|
| `opensam-92-account-review.md` | web/gateway 계정 UI + 프로필 아이콘 업로드 프록시 + 테스트 | `cleared` (fix-required 0, note 3) |
| `opensam-93-dpic-review.md` | nginx/compose 초상 서빙 + 양 앱 portrait 헬퍼 | `cleared` (익스플로잇 가능 취약점 0, disable_symlinks 하드닝 note) |
| `opensam-94-sync-review.md` | common/src wire(TurnDaemonCommand) + engine dispatcher/ChangeRecorder/flush + game-api sync 컨트롤러 + IT | CLEARED — fix-required 0건 (mandatory check #1: 테스트 직접 재실행, XML 판정) |
| `opensam-97-faces-review.md` | 전체 프레임 초상 축소 도구/에셋 경로 | `cleared` (fix-required 0, note 6 — 권리 보수성/LEGAL 게이트 준수 확인) |
| `opensam-103-spec-review.md` | 스펙 문서 | R2 재리뷰 `cleared` (R1 fix-required 2건 → 전부 해소) |
| `rtk14-hexmap-review.md` | tools/rtk14 hexmap 빌더 | `cleared` (fix-required 0, note 2) |
| `rtk-series-map-research-review.md` | 맵 리서치 원장/CSV | `cleared` (fix-required 0, note 3) |

OPENSAM-91 후속(프로필 아이콘 최종 재리뷰)은 별도 독립 리뷰가 `docs/loops/opensam-91-profile-icon/final-review.md`(구 `docs/superpowers/reviews/2026-07-17-opensam-91-profile-icon-final-review.md`에서 이동)에 있으며 판정 `cleared`다.

## 3. 리뷰 대상 외 부속 변경의 근거

- **`.codex/config.toml` 개인 모델 핀 제거** — `check.py` codex-surface 규칙("Project Codex config must not pin a personal model")이 기계적으로 강제하는 정리. main에도 존재하던 pre-existing 위반의 해소이며 행동 변경 없음(개인 핀은 `~/.codex/config.toml` 전역 설정으로 이전 가능).
- **gradle/libs.versions.toml·compose·nginx** — 각 티켓 리뷰(92/93/94)의 검토 범위에 포함되어 함께 판정됨.

## 4. 검증 증거 (판정 규칙: OUTPUT TAIL + XML, exit code 불신)

- **백엔드 게이트** (`tools/parity/gate.sh backend` 상당, 2026-07-18 재실행): `BUILD SUCCESSFUL in 9m 58s` + `XML gate green: 486 suites, 4423 tests` — failures/errors 0.
- **web/gateway** (`pnpm typecheck && pnpm test`, 2026-07-18 13:04): typecheck 무오류 + `Test Files 4 passed (4)` / `Tests 53 passed (53)`.
- **web/game** (`pnpm typecheck && pnpm test`, 2026-07-18 13:04): typecheck 무오류 + `Test Files 39 passed (39)` / `Tests 186 passed (186)`. 직전 12:56 전체 실행에서 `PartialReservedCommand.test.tsx` 1건 실패가 관측됐으나 단독 재실행 2/2·전체 재실행 186/186 green — `.ai/known-issues.md`의 "web/game vitest 부하 민감 플레이크" 문서화 패턴과 일치(테스트가 waitFor로 API 호출까지만 대기 후 렌더를 동기 단정하는 pre-existing 레이스; 해당 테스트/컴포넌트는 본 diff 미포함).
- **agent-system 게이트** (`tools/agent-system/check.py --strict --base origin/main`, 2026-07-18): No findings.
