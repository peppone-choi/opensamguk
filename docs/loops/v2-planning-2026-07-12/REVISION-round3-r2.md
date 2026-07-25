# round-3 설계안 개정 2차 — 처리 기록

> 일시: 2026-07-25 · 입력: `REVIEW-round3-r1.md`(5/10 `fix-required`) · 대상: `round3-proposal-city-guanxi.md` 제자리 수정 (812 → 1058줄, 새 파일 0, 미커밋)
> 상태: **재채점 대기** (동일 시험지 `GOLDENSET-round3-city-guanxi.md` + 독립 reviewer)

## CRITICAL 처리

### C1 `nation.gold` 미러 — **폐기하고 네 번째 길**

전수 census: 직접 산술 **35파일** / 배관 포함 **42파일**, 복붙 10곳, **choke point 0**. reviewer가 제시한 (a)(b)(c) 세 갈래가 전부 불가함을 census로 재확인.

**채택**: `nation.gold`/`nation.rice`를 **국고 — 도시 원장과 병존하는 별개 실계정**으로 재정의하고 **총합 불변식 자체를 폐기**한다. 치환 leaf는 `ProcessIncome` **하나**.

두 하드 제약 동시 만족:
- 42개 쓰기 지점 **무접촉** → v1 프로덕션 0줄 (ADR-LITE-018 준수)
- 읽는 값과 차감되는 값이 **동일 계정** → precheck 거짓 통과가 구조적으로 불가

`Presets.kt:315`의 거절 문구가 이미 "**국고**가 부족합니다"이므로 개명조차 아니다.

**대가 3건 명시**: 묘섭 충실도 하락 · §1.2 "도시 중심" 정의 축소 · 국력 통계 하락.

### C2 주입 지점 — 실측 후 seam 귀속

- `EngineGeneralActionPipelineBuilder.kt:14-17` — final, non-bean, `DaemonLoopConfig.kt:229`에서 `new`
- `FrontInfoController.kt:367-403` — 인라인 생성

**seam 부재 확인.** seam 개설을 `OPENSAM-35`에 귀속하되 **그 티켓의 범위 포함 여부는 UNKNOWN → 조건부 R0 +1**로 표기. 게이트는 T1/T2 2계층으로 재작성.

## MAJOR 처리

| # | 처리 |
|---|---|
| M0 | `IncomeTick.kt:29,47,65` 호출로 중복 삭제. **R2·R3 병합까지 유도됨** |
| M1 | 월 게이트 `{1,4,7,10}` + `BAD_STATE_CODES={3..9}` 확정 — **UNKNOWN 해소** |
| M2 | DB 유일성 인덱스 폐기 → 메모리 `bondIndex`. 방향 kind는 역할이 뒤집히면 **새 결속** |
| M3 | 우호·적대 **2축 분리** (애증 = 0) |
| M4 | 실효 상한 = 통솔 ±6 · **무력/지력 ±8**. 비교 야드스틱을 `+14`(통솔 전용)에서 **부상 10%**(`GetStatValue.kt:53`)로 교체 |
| M5 | RTK 인물관계 필드 출처 **UNKNOWN**으로 정정 + 그 위에 세웠던 설계 제거 |
| M6 | 티켓별 삽입 단계 분리 |
| M7 | R7 → **6티켓 분해** |
| M8 | 규칙 4 판정 뒤집어 **오픈 후** |

## MINOR 처리

m1 `beginner__battlebasic.md:85`(intermediate 계열엔 없음) / m2 `DomesticHelpers.kt:74-78`이 문자 그대로 `"…품관"` 출력 + `GameConst.kt:48` / m3 헤더 `:133` vs 금 행 `:135` / m4 "9소스" 표현 폐기(`MODULE_ORDER` 12, 테스트 핀 10, inherit 쌍 포함 최대 12) / m5 결합은 **곱**(`IncomeTick.kt:41` `1.05.pow`) / m6 임원진 **두 번째 효과표** 추가(`intermediatedomestic.md:219-243`, 자국 전역·자격 요건) / m7 `ModuleFactoryOrderTest.kt:78-92` 인용

## 설계가 바뀐 지점 9곳

① `nation.gold` 미러 → 국고 병존 ② R2+R3 병합, 공식은 v1 함수 호출 ③ 공백지화 월 게이트 ④ 2축 관계 보정 + 실효 상한 ⑤ `bondIndex` 강제 ⑥ T1/T2 방어선 ⑦ `--diff-filter=MD` + `logic/src/main/kotlin/` **전체** 잠금(원안은 24패키지 중 6개만) ⑧ R6 원장 열람 티켓 신설 ⑨ 관계망 오픈 후

## 오픈 경로 최종 = **20 (조건부 21)**

| 티켓 | 산출물 | 삽입 |
|---|---|---|
| R1 | 원장 기반 | 3단계(0B) 직후 |
| R2 | 수입·봉록 (M0로 R2+R3 병합) | 3단계 직후 |
| R3 | 공백지화 | 3단계 직후 |
| R4 | 병사보충 | 4단계(V2-1) 이후 |
| R5 | 수송 | 4단계 이후 |
| R6 | 원장 열람 (신설) | 4단계 동시 |
| (R0) | 파이프라인 seam — `OPENSAM-35` 범위 UNKNOWN | 조건부 +1 |

reviewer의 20과 **같은 값, 다른 구성** — 병합 −1, 열람 +1, 관계망 −2. 관계망은 오픈 후 **6티켓**.

## 권고: 20, 관계망 오픈 후

**결정적 근거는 reviewer도 몰랐던 것 — 원안의 "기록은 소급할 수 없다"가 거짓이다.**

- `operation_participants`가 V2-3 티켓 3-b(`01-backbone-micro.md:190`)라 **오픈 시점부터 참여 기록이 쌓이고 소급 생성이 가능**하다
- 커맨드 이력도 insert-only · purge 잡 0건(`JdbcFlushExecutor.kt:2182`, `@Scheduled` 0)이라 무기한 보존 (보존 **정책**만 `cqrs-consistency-failure-contract.md:502`에서 UNKNOWN)
- 정산 기록만 UNKNOWN
- 게다가 emergent 소스 3/4가 `OPENSAM-56`·`61` = **오픈 경로 마지막 두 티켓**이라 넣을 자리 자체가 없다

## reviewer 반박 4건 (코드 근거)

1. **C2의 leaf 치환 근거 오류.** reviewer는 `EngineEventConfig.kt:89`를 지목했으나 그건 빈 `generalActionPipeline()` 빈이다. 실제 leaf 레지스트리는 `logic/event/WorldActions.kt:30-56` + `EventStore.kt:157`이고, DB `event` 행이 기본값을 대체하므로(`EngineEventConfig.kt:46-57`) **leaf 치환은 `app/**` 편집 0으로 끝난다** — reviewer 추정보다 싸다. 단, 게이트가 `app/**`를 덮어야 한다는 결론은 **파이프라인 빌더 때문에 유지**.
2. **M8 "(a) v2 로그 한 줄이 가장 쌈"** — 파이프라인은 장수당 최소 턴당 1회 조립되고 상한 UNKNOWN. 변화분만 남기려면 이전 보정 상태를 영속해야 해 새 컬럼이 붙는다. **가장 싸지 않다.**
3. **M4 "(b) 불가"** — 결론 동의, 이유가 더 정확하다. 채널(`ActionPipeline.kt:24` `aux`)은 **존재**하지만 유일 호출부 `GetStatValue.kt:64`가 안 채우고 그 파일이 T1이다.
4. **M0** — 지적은 옳으나 불완전. `ProcessIncome`이 3분기·`ratio`·장수별 지급까지 한 leaf에 갖고 있어 **R2+R3이 통째로 합쳐진다**(reviewer는 R2 축소만 봄).

## 잔여 UNKNOWN (설계를 얹지 않음)

- `OPENSAM-35` 범위에 파이프라인 seam이 포함되는가 → 조건부 +1의 근거
- RTK 원본 인물관계 필드 존재 여부
- Operation 정산 기록 영속 여부
- 커맨드 이력 보존 **정책**
- 묘섭 품관 눈금 (v1은 `GameConst.kt:48` 30등급 역순 확정)
- 도시병사 수송 상한

`docs/wiki/raw/**` 무수정, 미커밋.
