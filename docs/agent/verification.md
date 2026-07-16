# Verification — 변경 유형별 최소 검증 행렬

**공통 대원칙 (이 저장소 고유):** gradle은 exit code로 성공을 주장하지 말 것. `... 2>&1 | tail -40`에서 `BUILD SUCCESSFUL` + 테스트 카운트를 확인하거나 `**/build/test-results/test/*.xml`에서 `failures="0" errors="0"`을 확인한다. UP-TO-DATE 의심 시 `--rerun-tasks`. Docker 미가동 시 통합 테스트는 **skip이지 fail이 아니다**.

모든 gradle 명령은 repo root에서 `JAVA_HOME=$(/usr/libexec/java_home -v 21)` 프리픽스로 실행한다.

자동 훅은 provider별 진입점만 다르다. Claude는 `.claude/settings.json`, Codex는 `.codex/hooks.json`에서 보호·검증 스크립트를 호출하며 Codex의 단순 Bash 호출은 `codex-bash-guard.sh`가 best-effort로 검사한다. 공식 Codex 훅은 모든 shell 경로를 가로채지 못하므로 보안 경계가 아니라 누락을 줄이는 방어선이다. Codex의 `SessionStart`는 lock + 로컬 무결성 스탬프를 검사해 `project-skills.sh restore --soft`로 외부 스킬을 복원한다. 완료 전에는 실제 `$os-verify` 또는 `verify-changes.sh --run` 결과와 아래 행렬의 증거를 남긴다.

| 변경 유형 | 최소 검증 | 추가 검증 조건 | 완료 조건 |
|---|---|---|---|
| `common` (RNG/round/log 커널) | `./gradlew :common:test :logic:test --rerun-tasks` | 전투/AI 골든 영향 시 `tools/parity/gate.sh logic` | XML fail 0 + 관련 골든 게이트 green |
| `logic` 액션/전투/AI/틱 | 해당 `*GoldenTest` 타깃 실행 → `tools/parity/gate.sh logic` | 골든 없으면 먼저 캡처(`tools/php-golden/`) 또는 격리+증거 | 골든 draw-for-draw green, 골든/테스트 무약화 |
| `infra` flush/마이그레이션 | `./gradlew :infra:test` (Testcontainers) | 새 테이블/채널이면 flush IT 추가 | XML fail 0 (Docker 없으면 skip 명기) |
| `app/game-engine` | `./gradlew :app:game-engine:test` | intake/dispatch 변경 시 `:app:game-api:test` 동반 | XML fail 0 |
| `app/game-api` | `./gradlew :app:game-api:test` | precheck 변경 시 `PrecheckFullCrossCallSiteTest` 포함 확인 | XML fail 0 |
| 백엔드 광역/커밋 전 | `tools/parity/gate.sh backend` | — | 스크립트가 XML까지 검증 |
| `web/gateway` | `cd web/gateway && pnpm typecheck` | UI 흐름 변경 시 브라우저 검증(webapp-testing/Playwright) | tsc 0 error |
| `web/game` | `cd web/game && pnpm typecheck && pnpm test` | UI 흐름 변경 시 브라우저 검증 | tsc 0 + 테스트 green |
| Docker/compose/nginx | `./tools/smoke.sh` | prod compose 변경 시 배포 게이트(아래) | 스택 기동 + health 단언 |
| 문서/에이전트 설정만 | `git diff --check` + 링크/경로 대조 + `tools/agent-system/check.py` | — | 깨진 참조 0 |
| 배포(main push 포함) | **사람 승인 필수** → `docs/agent/lifecycle-ops.md` 절차 | — | health green **그리고** `world_state.current_year/month` 전진 |

## 검증 루프 3종 — 무엇이 무엇을 차단하는가 (AC-13/14/15)

| 루프 | 차단 대상 | 기제 (전부 실배선) | 증거 형태 |
|---|---|---|---|
| **① 코드 결함 차단** | 버그·패러티 드리프트가 조용히 남는 것 | provider 공통 PostToolUse 훅(Claude `.claude/settings.json`, Codex `.codex/hooks.json` → `verify-changes.sh`의 diff→최소 검증 행렬 안내) + 위 행렬 실행 + `tools/parity/gate.sh backend`(XML 판정) | `BUILD SUCCESSFUL` tail + 테스트 XML `failures="0"` |
| **② 자기 승인 차단** | 작성자가 자기 작업을 스스로 승인·머지 | PR 이중 리뷰(`claude_review.yml` 파리티 한국어 프롬프트 + CodeRabbit `.coderabbit.yaml`; fallback 시 Claude GHA 단독) + `check.py --strict`의 cross-agent critique Verdict 검사(`docs/superpowers/reviews/*.md` 요구) | PR 리뷰 코멘트 + reviews 아티팩트의 `Verdict:` 라인 |
| **③ 규범 위반 차단** | 시크릿 접근·골든/legacy 수정·검증 없는 완료 선언 | provider 공통 PreToolUse 보호 훅(Claude `.claude/settings.json`, Codex `.codex/hooks.json` → `protect-sensitive-files.sh` exit 2) + Claude 첨부 경계 `.claudeignore`(@멘션 구멍) + CI `check.py --strict --base origin/main`(ci.yml agent-system 잡) | BLOCKED stderr 캡처 + CI 그린 로그 |

세 루프는 서로 대체재가 아니다: ①이 초록이어도 ②없이 머지하면 자기 승인이고, ②가 있어도 ③없이는 골든 수정으로 게이트를 "통과"시킬 수 있다. 완료 선언은 셋 다 해당 증거를 인용할 수 있을 때만.

## 판정 규칙

- **실행한 검증과 실행하지 않은 검증을 구분해 보고한다.** 실행 안 한 것을 통과로 기록 금지.
- 실패 테스트를 삭제·skip·assertion 약화로 우회 금지. 골든 불일치는 Kotlin 구현을 고친다(골든 수정 금지).
- UI 변경은 시각/브라우저 검증 없이 "정상"이라 주장하지 않는다. 도구가 없으면 `채점대기`로 보고.
- Testcontainers 단건 실패는 단독 재실행으로 flake 분별 후 판정(이력: `BettingUpsertFlushIT`).
- 비자명 변경은 cross-agent critique(`docs/superpowers/WORKING_SYSTEM.md` §Cross-agent critique)가 `cleared`여야 완료. `fix-required`가 남으면 ship/merge 금지.
