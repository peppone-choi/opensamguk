# 휘하 컷오버 표기 감사 (2026-09-24)

## 방법과 범위

`git ls-files`의 Markdown·JSON·Kotlin·TypeScript·YAML 전체에서 `PROVISIONAL|초안|임시|\blab\b`를 대소문자 무시로 찾고, 행에 `휘하|HWIHA|ruleProfile|S3`가 있거나 파일 경로에 `hwiha`가 있는 것을 확인했다. 원본 검색은 40파일·117행이었다. `lab`은 `label`의 부분 문자열을 세지 않도록 단어 경계로 찾았다. 이 숫자는 변경 전 기준이며 새 S3 종료 스펙은 당시 `hwiha-s3-exit-spec` worktree의 미추적 문서라 별도로 확인했다.

| 표기·위치 | 조치 또는 남기는 사유 |
|---|---|
| ADR-LITE-057의 `direction-approved`·「게임 수치는 전부 proposed」, 재설계 상단의 방향 승인 | ADR-LITE-065와 #872에 맞춰 accepted·확정 원장 3개로 고침 |
| 입력 registry·위치 권위·개인 턴 스펙의 `초안` | 정식 상태와 실제 구현 차이 표로 교체. 차이는 #779·#787·#249가 추적 |
| S3 종료 스펙의 초안·PROVISIONAL·별도 승인 | 문서 상단에 최종 통과 근거와 확정 결정, 현재 pep 실행 조건을 넣음. 아래 W0–W5 본문은 2026-09-23 계획의 역사적 증거이므로 보존 |
| `HwihaNpcDeploySelector`의 「임시」, 운영 문서의 「HWIHA 반응 기록 임시 규칙」 | #872·#874의 확정·구현에 맞춰 고침 |
| `docs/admin/hwiha-pep-transition.md`의 `scenario_990002` 병력·재고·장수 `PROVISIONAL`, `tools/e2e/fixtures/hwiha-yuzhou/README.md` | **유지.** #872는 공성·군량·내정·시야의 규칙 수치를 확정했다. QA 시나리오의 합성 시작값은 별도 선택이며 12–24순 목표 대비 실제 3순 함락이므로 pep 전환 기록에서 검토해야 한다 |
| `hwiha-s3-provisional-v1.json` 파일명, `HwihaS3Provisional` 클래스·테스트·참조 | **유지.** 최초 원장의 안정 식별자/코드 경로다. JSON 내부 `status`와 수치는 `CONFIRMED`이며 이름만으로 수치가 미승인이라는 뜻이 아니다. 일괄 개명은 런타임·테스트·문서 참조를 함께 바꾸는 별도 작업이다 |
| `HwihaDomesticDto.provisional`와 `HwihaDomesticReader`의 응답 필드 | **B단계로 이관.** 실제 값은 `CONFIRMED`를 실어 보내지만 제품 API 필드명이 낡았다. 제품 API 정리 때 의미가 드러나는 이름으로 바꾸고 클라이언트를 같이 고친다 |
| `docs/superpowers/specs/2026-09-17-stratagem-card-catalog-draft.md`, 재설계의 계책·전술 조건/행동 어휘 `초안`, `web/game/app/game/hwiha/hand/page.tsx` | **유지.** `stratagem.play`는 원장상 PLANNED이고 카드 효과·어휘는 이 승인에서 확정하지 않았다. 제품 화면은 구현 상태에 맞춰 표시해야 한다 |
| `logic/.../HwihaCountyIncome.kt`의 「초안 규모」와 관련 연구·테스트 | **유지.** #872의 세 수치 원장에 포함되지 않은 2026-09-21 월 세입 규모 출처 설명이다. 현재 정본 숫자 자체를 이 컷오버에서 새로 확정하지 않는다 |
| `docs/admin/operations-and-recovery.md`의 「임시 포로 표식」 | **유지.** 현재 `hwihaCaptive`는 효과 없는 표식이고 포로 처리 규칙은 아직 미완성이다. 반응 기록의 낡은 임시 규칙만 위와 같이 수정했다 |
| `web/game/app/game/v2-lab/**`와 2026-08의 v2-lab 계획·실측·회귀 테스트 | **B단계로 이관.** 제품 라우트는 제거 대상이고 과거 404·누출 분석 문서와 회귀 근거는 보존한다 |
| 과거 리뷰·계획·`.ai/current-state.md`의 `초안`·`lab`·`임시` | **유지.** 당시 판정/작업 상태를 증명하는 날짜 붙은 기록이며 현재 제품 상태로 인용하지 않는다 |

`PROVISIONAL`이라는 철자가 남은 것을 곧바로 미확정 규칙으로 취급하지 않는다. 현재 제품 수치의 상태는 원장 JSON의 `status`와 #872 결정으로 판정한다. B단계에서는 제품 라우트·API 표면의 낡은 명칭을 제거하고 이 표를 다시 확인한다.
