# GOLDENSET — page-parity 루프 (패러티 모드)

> **패러티 모드**: 새 골든셋을 만들지 않는다. **기존 골든 게이트가 곧 시험지**이며
> 절대 완화하지 않는다 (CLAUDE.md 패러티 규율 5 = fix the impl, not the golden).
> 본 문서는 시험지 *포인터*다 — 게이트 스크립트/테스트 자체가 동결 대상.
>
> 상태: **동결** (유저 승인 2026-06-10). 이후 변경은 점수 무관 유저 승인 필수.

## 시험지 (고정)

1. **백엔드 게이트**: `tools/parity/gate.sh backend`
   - 채점 = 각 모듈 `build/test-results/test/*.xml`의 `failures="0" errors="0"` 전수
     + 스위트 수 비감소. exit code 비신뢰(CLAUDE.md).
   - 점수 표기: `green-suites/total-suites` (모듈별 분해 기록).
2. **프론트 게이트**: `web/game`: `npx pnpm typecheck` + `npx pnpm test` (42+),
   `web/gateway`: `npx pnpm typecheck`.
3. **갭 단위 골든**: 바퀴가 특정 명령/리드API를 닫을 때는 해당 `*GoldenTest`/컨트롤러
   테스트의 red→green 관찰이 추가 채점 항목(신규 테스트는 PHP 소스 인용 필수, 날조 금지).

## 채점 규칙

- 채점자 = 제안 컨텍스트 없는 fresh 서브에이전트. 결정적(테스트 XML) 채점이므로
  블라인드 A/B 불요 — 단 채점자가 XML을 직접 읽고 PHP 오라클 대조를 수행한다.
- 점수 상승 정의: (a) 시험지 전체 green 유지 + (b) 갭 1개 닫힘(신규 테스트 red→green
  + PHP 소스 인용 검증 통과). 동점 = green 유지지만 갭 미닫힘. 하락 = 시험지 red.

## 변경 금지

골든/게이트/이 문서의 변경 = 점수 무관 **유저 승인 필수**.
