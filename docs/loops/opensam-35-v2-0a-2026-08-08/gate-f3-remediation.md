# GATE-f3 false-green remediation — pre-PR historical stage (2026-08-08)

정본 리뷰: `docs/superpowers/reviews/2026-08-08-opensam-35-v2-0a-isolation-review.md`.
이 문서는 PR #370 Round 1 review나 release verdict가 아니다. 현재 controlling disposition은 review
artifact의 23-thread `fix-required`; source remediation and backend evidence are now observed resolved, but the
independent dirty-tree reviewer has not yet supplied a terminal disposition.
평가 계약은 리뷰가 지적한 세 false-green을 각각 독립 가설로 고정했다. 테스트 기대값이나 기존
golden은 완화하지 않았다.

## 0바퀴 기준선

- backend fresh pre-remediation: 599 suites / 5,023 tests / failures 0 / errors 0 / skipped 1.
- 문제: green 결과와 별개로 (a) 선택 root 하나의 XML 0개, (b) 다른 모듈 raw source 변화,
  (c) imported test config만 뜬 gateway context가 각각 통과할 수 있었다.
- 합치기 기준: 각 결함을 강제로 주입했을 때 red, 복원한 final tree에서 focused green.
- 승인 대기: v2 leaf acceptance 충돌은 별도 ADR-LITE-029로 사용자 승인받아 해소.

## 1바퀴 — module별 XML 부재 fail-closed

- 베이스라인: XML 5개 root만 있고 gateway root가 없는 hermetic fixture에서 기존 parser가 exit 0,
  `XML gate green: 5 suites, 30 tests`.
- 가설: aggregate `files.isEmpty()` 대신 선택 root별 XML 최소 1개를 강제하면 부분 누락이 red가 된다.
- 변경: `tools/parity/gate.sh`가 각 `module_root`의 XML을 따로 수집하고 빈 root 목록을 출력한 뒤 exit 1.
- 재측정: 같은 5-root fixture는 exit 1 + `app/gateway-api` 명시. 여섯 번째 XML 추가 시 exit 0,
  `6 suites, 36 tests`.
- 판정: **채택**. `bash -n tools/parity/gate.sh` PASS.

## 2바퀴 — cross-module naming source를 Gradle input으로 선언

- 베이스라인: `V2NamingConventionGuardTest`가 여섯 source root를 직접 읽지만 game-engine `test` task는
  gateway/game-api/infra/common/logic raw source를 input으로 선언하지 않았다.
- 가설: 여섯 Kotlin file tree를 named `RELATIVE` input으로 등록하면 다른 모듈 위반도 UP-TO-DATE를
  무효화한다.
- 변경: `app/game-engine/build.gradle.kts`의 `v2NamingConventionSources`를 `tasks.test.inputs.files`에 등록.
- 재측정: gateway `AuthController.kt`의 임시 whitespace 변화 후 Gradle `--info`가
  `Input property 'v2NamingConventionSources' ... AuthController.kt has changed`를 출력하고 test task를
  실행했다. 원본 byte 복원 후 focused guard `BUILD SUCCESSFUL in 4s`, XML 1/0/0/0.
- 판정: **채택**.

## 3바퀴 — gateway context 양성 대조를 production bean으로 고정

- 베이스라인: 기존 타입-prefix 대조를 imported `ProfileIconSecureStorageTestConfiguration`에만 맞추는
  변이에서도 네 context가 전부 통과했다. production scan이 죽어도 green일 수 있었다.
- 가설: 특정 production `AuthController` bean 정확히 1개를 요구하면 test import만으로는 통과하지 못한다.
- 변경: prefix non-empty 단언을 `context.getBeansOfType(AuthController::class.java).size == 1`로 교체.
- 재측정: `AuthController`의 `@RestController`를 임시 제거하면 production-shape test가 red. 복원 후
  네 context `BUILD SUCCESSFUL in 1m 13s`, XML 합계 4 tests / failures 0 / errors 0 / skipped 0.
- 판정: **채택**.

## 현재 판정

세 가설 모두 결함 주입 red와 final focused green을 관측했다. 이는 pre-PR focused remediation evidence다.
full backend gate와 fresh independent re-review는 이 문서 작성 시점에 아직 미실행이었고, 이후 historical
A4/cleared note도 current PR Round 1 exact-SHA acceptance로 승격되지 않는다.
