# 버전 1 레거시 동등성 감사·수정 독립 리뷰

Scope: .codex/, app/, common/, logic/, web/

- 기준 커밋: `0cbcf44626074f7e481d58b6e42defab164b6ea7`
- 대상: 2026-07-26 감사 보고서, docs 전수 원장, 확정 버그 6개 수정 diff
- 1차·최종 리뷰어: `fable-deep-reasoner` (`review_v1_audit_fixes`)
- 교차 코드 리뷰어: `lazycodex-code-reviewer` (`second_code_review`)
- 최종 판정: **cleared**
- 제품 판정: **v1 전체는 release-blocked** — 이 리뷰는 이번 bounded 수정 diff만 승인한다.

`.codex/`는 이번 수정 대상이 아니라 기존 개인 model pin을 baseline으로
격리하기 위한 검토 범위다. 제품 diff는 `app/`, `common/`, `logic/`,
`web/`에서 독립 검토했다.

## 1차 판정과 보정

1차 리뷰는 다음을 `fix-required`로 판정했다.

1. 천도 준비 poll마다 필요한 `nation_env.last천도Trial` 쓰기 누락
2. 성공 시 `active_action`, general/nation/global history와 정적 이벤트 누락
3. `isunited != 0`일 때 유산점수 no-op gate 누락
4. 정적 이벤트가 활동점수·5개 로그보다 먼저 실행되는 순서 오류
5. 보고서의 AI 표현 과장과 §6 차단 항목의 PHP/hwe↔Kotlin/Next 줄 근거 부족

보정 후에는 FULL 허용 뒤 매 poll의 trial 기록, `active_action → ordered log 5건
→ che_천도 static hook`, 통일 상태 no-write, power/tech 보존과 포화 비용을
회귀 테스트로 고정했다. 보고서는 AI가 병합 정책이 아니라 기본 policy
object를 소비한다고 정정했고, §6.9에 39개 차단 키와 97개 경로 인용을
추가했다.

## 최종 검토 근거

- `CheondoTest`: 5/5 green
- `ProcessNationCommandCheondoTest`: 3/3 green
- `tools/parity/gate.sh backend`: `BUILD SUCCESSFUL`
- XML: 521 suites / 4,585 tests / 0 failures / 0 errors / 205 skipped
- `web/game`: typecheck + 42 files / 216 tests green
- docs manifest: 388/388, 누락 0
- `git diff --check`: green
- 두 독립 리뷰어 모두 최신 on-disk diff에서 blocker 없음

## 잔여 QA

- args 없는 PRECHECK/catalog의 천도 거리 50 fallback
- Docker 미기동으로 2-world PostgreSQL IT 1건 skip
- PHP CLI·Docker 부재로 새 골든 캡처 미실행
- 게임 서버 미기동으로 실제 Next hydration/browser 관측 미실행
- 보고서 §6의 명령·월 틱·전투·AI·부가 시스템·JPA read·프런트·운영 차단 항목
- strict Agent System check는 기존 사용자 변경인 `.codex/config.toml`의
  personal model pin과 이전 untracked
  `2026-07-25-docs-harness-refresh-review.md`의 현 diff scope 불일치 2건으로
  exit 1이다. 이번 감사 파일·리뷰 누락이 아니며 해당 사용자 변경은
  수정하지 않았다.

위 항목은 이번 diff의 허위 green으로 덮지 않았고 감사 보고서에
`채점대기` 또는 release blocker로 남겼다. 반복된 fablize `tool failure`
알림은 과거 RED·출력 절단을 재표시한 wrapper baseline이며, 최종 exit code,
XML 집계와 diff check는 모두 green으로 독립 확인했다.

Verdict: cleared
