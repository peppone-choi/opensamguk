# OPENSAM-214 런타임 시나리오 은퇴 독립 리뷰

Verdict: cleared

- Date: 2026-08-20
- Reviewer: 구현 컨텍스트와 분리된 `fable-deep-reasoner` read-only lane
Scope: `app/gateway-api/` scenario catalog/test, ADR-LITE-043, `CLAUDE.md`, OPENSAM-204 stale-premise 분류, OPENSAM-214 감사 보고서

## 터미널 판정

수정 후 코드·테스트·문서 정확성 지적은 없다.

- 제품 카탈로그는 han 코드 15개만 명시적으로 노출한다.
- 공백지 15개 + `9200` 테스트 시나리오는 모두 클래스패스에 남고 카탈로그에서는
  빠진다.
- `scenario_1010_che.json` 두 사본, `CheScenarioBootIT`, `ScenarioImporterIT`, 골든,
  CHE/miniche 테스트의 diff는 0이다. 명시적 `SCENARIO_CODE` 부트는 카탈로그를
  경유하지 않아 회귀 경로가 남는다.
- ADR-LITE-043/`CLAUDE.md`는 런타임 소스와 일치한다:
  `FOUND_ASSAULT_RATIO=2.0`, han만 `ceil(defence * 2.0)`, 다른 맵은 0,
  건국 가능 `5..6 || >=10`, 도적·황건 `1/13/28郡治 -> 2/3/4`.
- ADR-LITE-041과 실물 데이터가 175郡治 / 780城을 증명한다. 1,144는 타일·소유격자
  입력이므로 OPENSAM-204의 161郡 택일 요구는 stale-premise가 맞다.

## 리뷰 지적과 조치

| 심각도 | 지적 | 조치 | 재검토 |
| --- | --- | --- | --- |
| MINOR | 감사 보고서가 `0`/`908`의 map 블록을 직접 읽었다고 써서 기본값 `che` 해석을 누락 | `ScenarioSeedRunner`·`ScenarioImporter` 기본값으로 실효값을 판정했다고 정정 | cleared |
| MINOR | `seats / juns = 175/175`가 `juns`를 메타 필드처럼 읽힐 수 있음 | `_meta.counts.seats / (.juns \| length)`로 정확히 표기 | cleared |

## 독립 재검증 근거

- `ScenarioCatalogServiceTest`: `BUILD SUCCESSFUL in 37s`, XML tests=1, failures=0,
  errors=0, skipped=0.
- `CheScenarioBootIT`: `BUILD SUCCESSFUL in 4m 32s`, XML tests=1, failures=0,
  errors=0, skipped=0.
- `ScenarioImporterIT`: `BUILD SUCCESSFUL in 11m 41s`, XML tests=22, failures=0,
  errors=0, skipped=0.
- `git diff --check`: clean.
- 보존 검사: 지정 CHE 픽스처·테스트·골든 경로 diff=0, 은퇴 리소스 16개 존재,
  han seats=175, playable cities=780.

## 잔여 게이트 기록

전체 `:app:gateway-api:test --rerun-tasks`는 206건 중 프로필 아이콘 Spring context 4건과
다중 XML 쓰기에서 실패했다. 실패 중 `ProfileIconMultipartLimitIT`는 단독 격리 실행에서
`BUILD SUCCESSFUL in 1m 6s`로 통과했다. 따라서 전체 모듈 게이트는 green으로
주장하지 않고 suite-level context/XML-output 실패로 별도 보고한다. 이 실패은 시나리오
카탈로그 타겟 테스트와 CHE/importer 보존 게이트의 green 판정을 대체하지 않는다.
