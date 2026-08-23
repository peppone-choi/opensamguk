# Verification — 변경 유형별 최소 검증 행렬

**공통 대원칙 (이 저장소 고유):** gradle은 exit code로 성공을 주장하지 말 것. `... 2>&1 | tail -40`에서 `BUILD SUCCESSFUL` + 테스트 카운트를 확인하거나 `**/build/test-results/test/*.xml`에서 `failures="0" errors="0"`을 확인한다. UP-TO-DATE 의심 시 `--rerun-tasks`. Docker 미가동 시 통합 테스트는 **skip이지 fail이 아니다**.

모든 gradle 명령은 repo root에서 `JAVA_HOME=$(/usr/libexec/java_home -v 21)` 프리픽스로 실행한다.

자동 훅은 provider별 진입점만 다르다. Claude는 `.claude/settings.json`, Codex는 `.codex/hooks.json`에서 보호·검증 스크립트를 호출하며 Codex의 단순 Bash 호출은 `codex-bash-guard.sh`가 best-effort로 검사한다. 공식 Codex 훅은 모든 shell 경로를 가로채지 못하므로 보안 경계가 아니라 누락을 줄이는 방어선이다. Codex의 `SessionStart`는 lock + 로컬 무결성 스탬프를 검사해 `project-skills.sh restore --soft`로 외부 스킬을 복원한다. 완료 전에는 실제 `$os-verify` 또는 `verify-changes.sh --run` 결과와 아래 행렬의 증거를 남긴다.

| 변경 유형 | 최소 검증 | 추가 검증 조건 | 완료 조건 |
|---|---|---|---|
| `common` (RNG/round/log 커널) | `./gradlew :common:test :logic:test --rerun-tasks` | 영향받는 동결 전투/AI 골든이 있으면 `tools/parity/gate.sh logic` | XML fail 0 + 영향받는 동결 게이트 green |
| `logic` 액션/전투/AI/틱 | 변경 규칙의 targeted test → `tools/parity/gate.sh logic` | 기존 동결 골든 영향 시 해당 `*GoldenTest`; 명시적 역사 parity 유지보수일 때만 새 PHP 캡처 | 현재 spec 테스트 green + 영향받는 동결 회귀 green, 테스트/골든 무약화 |
| `infra` flush/마이그레이션 | `./gradlew :infra:test` (Testcontainers) | 새 테이블/채널이면 flush IT 추가 | XML fail 0 (Docker 없으면 skip 명기) |
| `app/game-engine` | `./gradlew :app:game-engine:test` | intake/dispatch 변경 시 `:app:game-api:test` 동반 | XML fail 0 |
| `app/game-api` | `./gradlew :app:game-api:test` | precheck 변경 시 `PrecheckFullCrossCallSiteTest` 포함 확인 | XML fail 0 |
| `app/board-api` | `./gradlew :app:board-api:test` | — | XML fail 0 |
| 백엔드 광역/커밋 전 | `tools/parity/gate.sh backend` | — | 스크립트가 XML까지 검증 |
| `web/gateway` | `cd web/gateway && pnpm typecheck` | UI 흐름 변경 시 브라우저 검증(webapp-testing/Playwright) | tsc 0 error |
| `web/game` | `cd web/game && pnpm typecheck && pnpm test` | UI 흐름 변경 시 브라우저 검증 | tsc 0 + 테스트 green |
| Docker/compose/nginx | `./tools/smoke.sh` | prod compose 변경 시 배포 게이트(아래) | 스택 기동 + health 단언 |
| 문서/에이전트 설정만 | `git diff --check` + 링크/경로 대조 + `tools/agent-system/check.py` | — | 깨진 참조 0 |
| 배포(main push 포함) | **사람 승인 필수** → `docs/agent/lifecycle-ops.md` 절차 | — | health green **그리고** `world_state.current_year/month` 전진 |

## 검증 루프 3종 — 무엇이 무엇을 차단하는가 (AC-13/14/15)

| 루프 | 차단 대상 | 기제 (전부 실배선) | 증거 형태 |
|---|---|---|---|
| **① 코드 결함 차단** | 버그·동결 회귀 드리프트가 조용히 남는 것 | provider 공통 PostToolUse 훅(Claude `.claude/settings.json`, Codex `.codex/hooks.json` → `verify-changes.sh`의 diff→최소 검증 행렬 안내) + 위 행렬 실행 + 역사적 이름의 `tools/parity/gate.sh backend`(XML 판정) | `BUILD SUCCESSFUL` tail + 테스트 XML `failures="0"` |
| **② 자기 승인 차단** | 작성자가 자기 작업을 스스로 승인·머지 | PR 리뷰봇 CodeRabbit(`.coderabbit.yaml`) + `check.py --strict`의 cross-agent critique Verdict 검사(`docs/superpowers/reviews/*.md` 요구) + 사람/타 프로바이더 에이전트의 명시 비평 | PR 리뷰 코멘트 + reviews 아티팩트의 `Verdict:` 라인 |
| **③ 규범 위반 차단** | 시크릿 접근·legacy 수정·근거 없는 골든 갱신·검증 없는 완료 선언 | provider 공통 PreToolUse 보호 훅이 시크릿/legacy 위반은 `protect-sensitive-files.sh` exit 2로 차단하고, golden 기대값 갱신은 훅 NOTICE 후 `check.py --strict` 리뷰 게이트가 변경 경로마다 `Golden path:`·`Golden change reason:`·실행한 `Regression command:`·`Regression evidence: PASS — ...`·`Critique: CLEARED — ...`를 강제한다. 기존 추적 테스트의 삭제와 테스트 영역 밖 이동은 apply-patch/Bash 훅과 Git name-status strict 검사에서 차단한다. Claude 첨부 경계는 `.claudeignore`, CI는 agent-system 잡이다. | BLOCKED/NOTICE stderr 캡처 + 리뷰 근거 + CI 그린 로그 |

세 루프는 서로 대체재가 아니다: ①이 초록이어도 ②없이 머지하면 자기 승인이고, ②가 있어도 ③의 명시적 변경 이유·회귀 증거 게이트 없이는 기대값을 바꿔 통과시킬 수 있다. 완료 선언은 셋 다 해당 증거를 인용할 수 있을 때만.

## 판정 규칙

- **실행한 검증과 실행하지 않은 검증을 구분해 보고한다.** 실행 안 한 것을 통과로 기록 금지.
- 실패 테스트를 삭제·skip·assertion 약화로 우회 금지. 동결 골든 불일치는 먼저 의도치 않은 회귀로 취급해 구현을 고친다. 승인된 제품 규칙 변경이라면 기대값 변경은 별도 명시 근거와 회귀 영향 기록 없이는 허용하지 않는다.
- UI 변경은 시각/브라우저 검증 없이 "정상"이라 주장하지 않는다. 도구가 없으면 `채점대기`로 보고.
- Testcontainers 단건 실패는 단독 재실행으로 flake 분별 후 판정(이력: `BettingUpsertFlushIT`).
- Docker 없이 IT가 `assumeTrue(dockerAvailable)`로 skip되면 `BUILD SUCCESSFUL`이 찍혀도 그 IT는 검증된 게 아니다. `tools/agent-system/check_test_xml.py`가 test-results XML의 `skipped` 속성으로 기본 실패 판정하며, 로컬 무Docker 반복만 `OPENSAM_ALLOW_SKIPPED_IT=1`로 허용한다(스킵 목록은 항상 stderr에 찍힌다). CI는 이 opt-out을 절대 인정하지 않는다.
- 비자명 변경은 cross-agent critique(`docs/superpowers/WORKING_SYSTEM.md` §Cross-agent critique)가 `cleared`여야 완료. `fix-required`가 남으면 ship/merge 금지.
