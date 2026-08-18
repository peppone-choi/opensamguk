# OPENSAM-178 [H2] 수전 phases[] 처리 — 자체 리뷰

Scope: docs product-spec §6 BattleReplay 한 문장 추가와 .ai/decisions.md ADR-LITE-037. 코드 변경 없음.
Verdict: cleared

## 1. 무엇을 정했나

사용자가 **(3) 축 분리**를 선택했다. `phases[]`는 작전 안의 **순차 단계 축**으로 한정하고,
야전·공성·수전은 **전투 종류 축(battle type)**으로 분리한다. 관계는 한 문장:

> 한 phase 안에서 0..N개의 전투가 열리고, 각 전투가 battle type 하나를 갖는다.

`phases[]` 7값은 **하나도 바꾸지 않았다** — 값 추가·삭제 없이 의미만 한정했다.

## 2. 티켓 수용 기준 대조

- AC1 "사람 승인을 받으면 결정이 ADR-LITE로 기록된다" — **충족**. 사용자 선택(2026-08-18) 후
  ADR-LITE-037을 `Status: approved` / `Approved by: 사용자`로 기록했다. 이번 세션의 다른 ADR
  (033~036)이 전부 `proposed`/`NONE`인 것과 대비된다 — 여기만 실제 승인이 있었다.
- AC2 "product-spec §6 갱신 시 `phases[]`와 어댑터 3종의 축 관계가 한 문장으로 명시되고 수전의
  소속이 모호하지 않다" — **충족**. 추가 문장이 축 관계 + "수전은 phase 값이 아니다"를 함께 적었다.

## 3. H6 규칙(ADR-LITE-036) 준수

이번이 그 규칙의 첫 적용이다.

1. 개정 시점 — `phases[]`의 **의미**가 바뀌므로 개정이 맞다.
2. 승인·기록 — 사람 승인 확보 → ADR-LITE-037 기록 → 본문 수정 커밋이 ADR 번호를 인용(문장 자체에도
   `OPENSAM-178 / ADR-LITE-037`을 박았다).
3. supersede 열거 — 이번은 대체가 아니라 **한정**이라 supersede 대상이 없다. 다른 절은 무변경.
4. traceability — 이 개정을 소비할 티켓은 OPENSAM-158(F2)이며 ADR Consequences에 명시했다.

## 4. 스스로 공격해 본 것

- **(1) NAVAL 추가가 더 싸지 않나?** 싸지만 틀린다. `NAVAL`은 "순서상 어디"가 아니라 "무엇으로
  싸우나"다. 수전 뒤에 상륙해 URBAN으로 가는 작전을 7+1 열거로는 표현할 수 없고, FIELD/SIEGE의
  이중 의미도 남는다.
- **이름 충돌은?** FIELD/SIEGE가 두 축에 같은 이름으로 존재하게 된다. 해소하지 않고 **F2에
  권고**로 남겼다(`FIELD_BATTLE` 등) — battle type 열거의 정식 값은 F2 소유라 여기서 동결하면
  H7에서 정한 소유 경계를 침범한다.
- **범위를 넘었나?** product-spec에서 고친 것은 **한 문장 추가**뿐이다. `phases[]` 값, 다른 절,
  07-28 2.5D 문서 `:70`은 무변경. `git diff --stat`으로 확인.
- **승인 없이 정본을 고쳤나?** 아니다. 세 선택지를 제시하고 사용자가 (3)을 골랐다. 그 선택이
  §6 수정을 포함한다는 것을 질문 본문에 적었다.

## 5. 증거

- 근거 문서: 연구문서 §2.5(축 불일치)·R2·H2, `.ai/decisions.md` ADR-LITE-025(수전 출시 필수)·032.
- 대상: `docs/superpowers/specs/2026-07-12-opensamguk-v2-product-spec.md` §6 `BattleReplay`.
- 코드·테스트 무변경이므로 Gradle/vitest 실행 없음. v1 패러티(logic/war·PHP golden·RNG·로그) 무관.

## 6. 남긴 것

- battle type 열거의 정식 값·이름 동결(OPENSAM-158/F2), FIELD/SIEGE 이름 충돌 표기 정리.
