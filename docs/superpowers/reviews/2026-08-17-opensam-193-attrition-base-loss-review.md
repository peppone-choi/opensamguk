# OPENSAM-193 — 도시병사 감소량을 묘섭 원문 값으로 교정: 자기 검토

- 대상: `app/game-engine/.../v2/V2CityGarrisonAttrition.kt` + 그 테스트 + R3 리뷰 §2 + R3 설계안 인용
- 브랜치: `fix-opensam-193-attrition-base-loss` (base = `main`)
- 일자: 2026-08-17

Scope: OPENSAM-193 v2 도시병사 감소량 상수를 묘섭 원문 3000/500으로 교정(app/) + R3 리뷰·설계안 인용 정정(docs/)
Verdict: cleared

## 1. 무엇이 틀렸었나

R3(OPENSAM-152)는 감소 **수량**을 "묘섭 미명시"로 적고 v1 재난 비율에서 12.5%를 유도한 뒤
하한 100명을 붙였다. 실제로는 원문에 있었다:

> "300명 기준 3000명이며, 장수수가 적은 경우, 500명까지로 줄어듭니다."
> — `docs/wiki/raw/myosam-help/help__start__other__etcetera.md:67`

바로 앞 `:64`("감소량이 최저 6분의 1까지 감소")를 정량화한 줄이고, **500은 3000의 정확히 1/6**이라
두 줄이 서로를 확증한다. 즉 감소량은 정률이 아니라 **절대 수량**이며, 스케일 곡선의 두 끝값이
원문에서 이미 정해져 있었다. 없는 값을 지어낸 것이므로 "faithful port, never fabricate" 위반이다.

발견 경위: R4 착수 전 R3의 근거를 되짚다가 원문 `:67`을 직접 읽어 확인했다(서브에이전트 보고가
아니라 `sed`로 원문 확인). 리뷰 문서의 "묘섭 미명시" 주장과 원문이 어긋났다.

## 2. 교정 내용

- `ATTRITION_BASE_RATE(0.125)` + `ATTRITION_MIN_LOSS(100)` **삭제** → `ATTRITION_BASE_LOSS = 3000`
- `loss = floor(3000 × scale)`, 마지막에 `garrison`으로 절단. `scale`(1/6 ~ 1, 선형)은 불변 —
  그 곡선만 여전히 "묘섭 미명시, 오픈삼국 결정"이다.
- 절단이 0 도달(=공백지화)의 종결성을 만든다. 예전 하한 100이 하던 역할을 원문 수량이 대신한다.
- 정률 잔재는 남기지 않았다(상수·주석·테스트 모두).

## 3. 테스트 — 약화가 아니라 재조준

- `at the 300-general reference the loss is the myosam base of 3000` — 3000 고정 + **garrison 2배여도
  감소량 동일**(비례하지 않음을 새로 고정)
- `with no generals the loss floors at the myosam 500` — 500 = `ATTRITION_BASE_LOSS / 6`
- `garrison is reduced and never goes below zero` — 10000 → 7000
- 선형 보간·상한 절단·공백지화 테스트는 그대로(값만 새 기준). skip/TODO 없음.

## 4. 영향 범위

v2 샌드박스 전용이다. v1 패러티(RNG·로그·골든·DB)는 **건드린 파일 0**이고, `attritionLoss`의
호출자는 v2 leaf 하나뿐이다. draw 0도 그대로(함수에 `RandUtil` 인자 없음).

## 5. 검증 증거

- `:app:game-engine:test --tests 'opensamguk.engine.v2.*' --rerun-tasks` → v2 17개 클래스 전부
  failures 0 / errors 0 (`V2CityGarrisonAttritionTest` tests 15)
- 문서 정정: R3 리뷰 §2 의 해당 divergence 항목을 취소선 + 철회 사유로 교체(지우지 않고 남긴다),
  R3 설계안에 `:67` 인용 추가
