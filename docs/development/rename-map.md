# 코드·저장 식별자 개명 대응표

기준: 2026-09-25 `origin/main` 88ee3f61f. 이 표는 1·2·3층 요구사항과 후속 PR이 옛 이름을 추적할 때 사용한다. 현재는 첫 코드 개명과 저장 키 조사값을 기록한 진행 중 표이며, 나머지 파일·클래스 행은 각 순수 개명 PR에서 채운다. 순수 코드 개명과 저장 계약 개명은 별도 커밋으로 관리한다. 지도 확장 PR #905가 닿는 경로는 PR 병합 전에 다시 대조한다.

## 결정 규칙

- 제품 클래스·파일의 `Hwiha` 접두사는 도메인 패키지와 짧은 타입 이름으로 바꾼다. 예: `logic/input/HwihaDomesticRules.kt` → `logic/domestic/DomesticRules.kt`.
- 코드의 `v2` 패키지·타입 이름은 해당 도메인 이름으로 바꾼다. `schemaVersion`과 저장 세계가 가리키는 지도 번들·릴리스 ID는 유지한다.
- 저장 키·DB 객체·API·웹 경로 변경은 pep C단계 리셋 전에 처리한다. 옛 값이 들어온 세계는 형식 가드에서 명시적으로 거절한다.
- 삼모 이름과 새 제품 이름이 겹치면 제품 이름을 사용한다. 삼모 코드는 의존 감사를 마친 뒤 삭제한다.

## 코드 식별자

| 이전 | 확정 이름 | 처리 PR | 비고 |
|---|---|---|---|
| `web/game/lib/hwiha-reads.ts` | `web/game/lib/campaign-reads.ts` | 저장·통신 draft | 조회 타입과 훅의 제품 접두사 제거 |
| `web/game/lib/hwiha-screens.ts` | `web/game/lib/campaign-screens.ts` | 저장·통신 draft | 화면 등록부와 URL 생성 함수 개명 |
| `web/game/lib/hwiha-fog.ts` | `web/game/lib/campaign-fog.ts` | 저장·통신 draft | 郡 시야 함수와 방향 상수 개명 |
| `web/game/lib/hwiha-scout.ts` | `web/game/lib/campaign-scout.ts` | 저장·통신 draft | 정찰 예약 함수 개명 |
| `web/game/lib/hwiha-session.tsx` | `web/game/lib/campaign-session.tsx` | 저장·통신 draft | 세션 훅·판정 필드 개명 |
| `web/game/lib/hwiha-map.ts` | `web/game/lib/campaign-map.ts` | 저장·통신 draft | 지도 훅·상수 개명; `han` 지도 번들 ID는 유지 |
| `HanMapCanvasType` 테스트 별칭 | `WorldMapCanvasType` | 저장·통신 draft | 범용 캔버스 이름과 일치 |
| `opensamguk.logic.input.HwihaDomesticRules` | `opensamguk.logic.domestic.DomesticRules` | 예정 | 도메인 패키지 이동 |
| `opensamguk.logic.input.HwihaDomesticDesign` (`logic/input/HwihaDomesticDesign.kt`) | `opensamguk.logic.domestic.DomesticDesign` (`logic/domestic/DomesticDesign.kt`) | 이 PR | Kotlin 타입·파일·패키지 개명; 데이터 파일 `hwiha-domestic-v1.json`은 저장 식별자 단계 |
| `opensamguk.logic.input.HwihaDomesticRules` (`logic/input/HwihaDomesticRules.kt`) | `opensamguk.logic.domestic.DomesticRules` (`logic/domestic/DomesticRules.kt`) | 이 PR | 순수 타입·파일·패키지 개명 |
| `opensamguk.logic.input.HwihaDomesticProjection` | `opensamguk.logic.domestic.DomesticProjection` | 이 PR | 규칙 파일의 투영 타입 |
| `opensamguk.logic.input.HwihaSeatedMagistrate` | `opensamguk.logic.domestic.SeatedMagistrate` | 이 PR | 규칙 파일의 현령 자리 타입 |
| `opensamguk.logic.input.HwihaEffectivePolicy` | `opensamguk.logic.domestic.EffectivePolicy` | 이 PR | 규칙 파일의 유효 방침 타입 |
| `opensamguk.logic.input.DomesticPerson` | `opensamguk.logic.domestic.DomesticPerson` | 이 PR | 이름 유지, 패키지만 이동 |
| `opensamguk.logic.input.DomesticCard` | `opensamguk.logic.domestic.DomesticCard` | 이 PR | 이름 유지, 패키지만 이동 |
| `opensamguk.logic.input.DomesticCounty` | `opensamguk.logic.domestic.DomesticCounty` | 이 PR | 이름 유지, 패키지만 이동 |
| `opensamguk.logic.input.DomesticNation` | `opensamguk.logic.domestic.DomesticNation` | 이 PR | 이름 유지, 패키지만 이동 |
| `opensamguk.logic.input.DomesticBugok` | `opensamguk.logic.domestic.DomesticBugok` | 이 PR | 이름 유지, 패키지만 이동 |
| `opensamguk.logic.input.DomesticDiplomacy` | `opensamguk.logic.domestic.DomesticDiplomacy` | 이 PR | 이름 유지, 패키지만 이동 |
| `opensamguk.logic.input.DomesticFailure` | `opensamguk.logic.domestic.DomesticFailure` | 이 PR | 이름 유지, 패키지만 이동 |
| `opensamguk.logic.input.DomesticAssessment` | `opensamguk.logic.domestic.DomesticAssessment` | 이 PR | 이름 유지, 패키지만 이동 |
| `opensamguk.logic.input.PolicySource` | `opensamguk.logic.domestic.PolicySource` | 이 PR | 이름 유지, 패키지만 이동 |
| `logic/input/HwihaDomesticRulesTest.kt` | `logic/domestic/DomesticRulesTest.kt` | 이 PR | 테스트 타입·파일·패키지 개명 |
| `opensamguk.logic.input.HwihaDomesticInput` (`logic/input/HwihaDomesticInput.kt`) | `opensamguk.logic.domestic.DomesticInput` (`logic/domestic/DomesticInput.kt`) | 이 PR | 입력 파서 타입·파일·패키지 개명 |
| `opensamguk.logic.input.HwihaDomesticIds` | `opensamguk.logic.domestic.DomesticIds` | 이 PR | 내정 입력의 내부 식별자 검사 |
| `opensamguk.logic.input.PlacementPost` | `opensamguk.logic.domestic.PlacementPost` | 이 PR | 이름 유지, 패키지만 이동 |
| `opensamguk.logic.input.CountyPolicy` | `opensamguk.logic.domestic.CountyPolicy` | 이 PR | 이름 유지, 패키지만 이동 |
| `opensamguk.logic.input.CorpsPolicy` | `opensamguk.logic.domestic.CorpsPolicy` | 이 PR | 이름 유지, 패키지만 이동 |
| `opensamguk.logic.input.DomesticWork` | `opensamguk.logic.domestic.DomesticWork` | 이 PR | 이름 유지, 패키지만 이동 |
| `opensamguk.logic.input.PlacementTarget` | `opensamguk.logic.domestic.PlacementTarget` | 이 PR | 이름 유지, 패키지만 이동 |
| `opensamguk.logic.input.PolicyTarget` | `opensamguk.logic.domestic.PolicyTarget` | 이 PR | 이름 유지, 패키지만 이동 |
| `opensamguk.logic.input.PlacementRequest` | `opensamguk.logic.domestic.PlacementRequest` | 이 PR | 이름 유지, 패키지만 이동 |
| `opensamguk.logic.input.PolicyRequest` | `opensamguk.logic.domestic.PolicyRequest` | 이 PR | 이름 유지, 패키지만 이동 |
| `opensamguk.logic.input.WorkRequest` | `opensamguk.logic.domestic.WorkRequest` | 이 PR | 이름 유지, 패키지만 이동 |
| `opensamguk.logic.input.HwihaDomesticEffects` (`logic/input/HwihaDomesticEffects.kt`) | `opensamguk.logic.domestic.DomesticEffects` (`logic/domestic/DomesticEffects.kt`) | 이 PR | 효과 타입·파일·패키지 개명 |
| `opensamguk.logic.input.HwihaCountyLevels` | `opensamguk.logic.domestic.CountyLevels` | 이 PR | 縣 지표 단계 |
| `opensamguk.logic.input.HwihaSeatStats` | `opensamguk.logic.domestic.SeatStats` | 이 PR | 자리 능력치 |
| `opensamguk.logic.input.HwihaPolicyOutcome` | `opensamguk.logic.domestic.PolicyOutcome` | 이 PR | 방침 결과 |
| `opensamguk.logic.input.HwihaWorkStep` | `opensamguk.logic.domestic.WorkStep` | 이 PR | 공사 단계 |
| `logic/input/HwihaDomesticInputTest.kt` | `logic/domestic/DomesticInputTest.kt` | 이 PR | 입력 테스트 파일·타입·패키지 개명 |
| `logic/input/HwihaDomesticEffectsTest.kt` | `logic/domestic/DomesticEffectsTest.kt` | 이 PR | 효과 테스트 파일·타입·패키지 개명 |
| `logic/input/HwihaDomesticState.kt` | `logic/domestic/DomesticState.kt` | 이 PR | 상태 codec 파일·패키지 이동, meta 키는 저장 단계에서 별도 개명 |
| `opensamguk.logic.input.HwihaPlacementOrder` | `opensamguk.logic.domestic.PlacementOrder` | 이 PR | 배치 접수 |
| `opensamguk.logic.input.HwihaActivePlacement` | `opensamguk.logic.domestic.ActivePlacement` | 이 PR | 현행 배치 |
| `opensamguk.logic.input.HwihaPlacementState` | `opensamguk.logic.domestic.PlacementState` | 이 PR | 배치 상태 |
| `opensamguk.logic.input.HwihaPlacementMarch` | `opensamguk.logic.domestic.PlacementMarch` | 이 PR | 부임 행군 |
| `opensamguk.logic.input.HwihaPolicySetting` | `opensamguk.logic.domestic.PolicySetting` | 이 PR | 현행 방침 |
| `opensamguk.logic.input.HwihaPolicyOrder` | `opensamguk.logic.domestic.PolicyOrder` | 이 PR | 방침 접수 |
| `opensamguk.logic.input.HwihaPolicySlot` | `opensamguk.logic.domestic.PolicySlot` | 이 PR | 현행·대기 방침 |
| `opensamguk.logic.input.HwihaPolicyApplication` | `opensamguk.logic.domestic.PolicyApplication` | 이 PR | 적용 기록 |
| `opensamguk.logic.input.HwihaCountyPolicyState` | `opensamguk.logic.domestic.CountyPolicyState` | 이 PR | 縣 방침 상태 |
| `opensamguk.logic.input.HwihaCommanderyPolicy` | `opensamguk.logic.domestic.CommanderyPolicy` | 이 PR | 郡 방침 항목 |
| `opensamguk.logic.input.HwihaCommanderyPolicies` | `opensamguk.logic.domestic.CommanderyPolicies` | 이 PR | 郡 방침 모음 |
| `opensamguk.logic.input.HwihaCorpsPolicy` | `opensamguk.logic.domestic.CorpsPolicyAssignment` | 이 PR | 정책 enum `CorpsPolicy`와 구분되는 군단 방침 항목 |
| `opensamguk.logic.input.HwihaCorpsPolicies` | `opensamguk.logic.domestic.CorpsPolicyAssignments` | 이 PR | 군단 방침 항목 모음 |
| `opensamguk.logic.input.HwihaActiveWork` | `opensamguk.logic.domestic.ActiveWork` | 이 PR | 진행 중 공사 |
| `opensamguk.logic.input.HwihaCompletedWork` | `opensamguk.logic.domestic.CompletedWork` | 이 PR | 완료 공사 |
| `opensamguk.logic.input.HwihaCountyWorks` | `opensamguk.logic.domestic.CountyWorks` | 이 PR | 縣 공사 상태 |
| `opensamguk.logic.input.HwihaCountyIndicators` | `opensamguk.logic.domestic.CountyIndicators` | 이 PR | 縣 지표 기록 |
| `opensamguk.logic.input.HwihaCountyMonthly` | `opensamguk.logic.domestic.CountyMonthly` | 이 PR | 지난달 지표 |
| `logic/input/HwihaDomesticStateTest.kt` | `logic/domestic/DomesticStateTest.kt` | 이 PR | 상태 codec 테스트 파일·타입·패키지 개명 |
| `opensamguk.logic.input.HwihaFieldInput` (`logic/input/HwihaFieldInput.kt`) | `opensamguk.logic.domestic.FieldInput` (`logic/domestic/FieldInput.kt`) | 이 PR | 현장 직접 행동 입력·파일·패키지 개명 |
| `opensamguk.logic.input.HwihaFieldRequest` | `opensamguk.logic.domestic.FieldRequest` | 이 PR | 현장 행동 요청 |
| `opensamguk.logic.input.HwihaFieldFailure` | `opensamguk.logic.domestic.FieldFailure` | 이 PR | 현장 행동 거절 |
| `opensamguk.logic.input.HwihaFieldAssessment` | `opensamguk.logic.domestic.FieldAssessment` | 이 PR | 현장 행동 판정 |
| `opensamguk.logic.input.HwihaFieldRules` | `opensamguk.logic.domestic.FieldRules` | 이 PR | 현장 행동 규칙 |
| `opensamguk.logic.input.HwihaFieldEconomyAssessment` | `opensamguk.logic.domestic.FieldEconomyAssessment` | 이 PR | 현장 행동 자원 판정 |
| `logic/input/HwihaFieldInputTest.kt` | `logic/domestic/FieldInputTest.kt` | 이 PR | 현장 행동 테스트 파일·타입·패키지 개명 |
| `opensamguk.logic.input.HwihaScoutPosts` (`logic/input/HwihaScoutPosts.kt`) | `opensamguk.logic.vision.ScoutPosts` (`logic/vision/ScoutPosts.kt`) | 이 PR | 정찰 배치의 시야 공개 투영, 저장 키는 별도 단계 |
| `opensamguk.logic.input.HwihaScoutPostEntry` | `opensamguk.logic.vision.ScoutPostEntry` | 이 PR | 정찰 배치 항목 |
| `logic/input/HwihaScoutPostsTest.kt` | `logic/vision/ScoutPostsTest.kt` | 이 PR | 시야 계약 테스트 파일·타입·패키지 개명 |
| `opensamguk.logic.input.HwihaVisionRules` (`logic/input/HwihaVisionRules.kt`) | `opensamguk.logic.vision.VisionRules` (`logic/vision/VisionRules.kt`) | 이 PR | 시야 규칙 로더; 리소스 식별자는 저장·데이터 단계에서 개명 |
| `opensamguk.logic.input.VisionSourceKind` | `opensamguk.logic.vision.VisionSourceKind` | 이 PR | 시야 출처 enum 패키지 이동 |
| `opensamguk.logic.input.TroopBand` | `opensamguk.logic.vision.TroopBand` | 이 PR | 병력 구간 타입 패키지 이동 |
| `opensamguk.logic.economy.HwihaResources` (`logic/economy/HwihaCountyWarehouse.kt`) | `opensamguk.logic.economy.Resources` (`logic/economy/CountyWarehouse.kt`) | 이 PR | 전·곡·철·목재·말의 정수 자원 값; meta 필드 유지 |
| `opensamguk.logic.economy.HwihaCountyWarehouse` | `opensamguk.logic.economy.CountyWarehouse` | 이 PR | 縣 창고 타입·파일 개명, 저장 키는 별도 단계 |
| `opensamguk.logic.economy.HwihaCountyIncome` (`logic/economy/HwihaCountyIncome.kt`) | `opensamguk.logic.economy.CountyIncome` (`logic/economy/CountyIncome.kt`) | 이 PR | 縣 월 생산 타입·파일 개명 |
| `logic/economy/HwihaCountyWarehouseTest.kt` | `logic/economy/CountyWarehouseTest.kt` | 이 PR | 창고 테스트 파일·타입 개명 |
| `logic/economy/HwihaCountyIncomeTest.kt` | `logic/economy/CountyIncomeTest.kt` | 이 PR | 월 생산 테스트 파일·타입 개명 |
| `opensamguk.logic.input.HwihaVision` (`logic/input/HwihaVision.kt`) | `opensamguk.logic.vision.Vision` (`logic/vision/Vision.kt`) | 이 PR | 시야 투영 본체의 도메인 패키지 이동 |
| `opensamguk.logic.input.HwihaVisionView` | `opensamguk.logic.vision.VisionView` | 이 PR | 시야 투영 결과 타입 |
| `opensamguk.logic.input.HwihaCorpsVisibility` | `opensamguk.logic.vision.CorpsVisibility` | 이 PR | 군단 시야 판정 |
| `opensamguk.logic.input.VisionTier` | `opensamguk.logic.vision.VisionTier` | 이 PR | 시야 등급 enum 패키지 이동 |
| `opensamguk.logic.input.VisionSource` | `opensamguk.logic.vision.VisionSource` | 이 PR | 시야 출처 타입 패키지 이동 |
| `opensamguk.logic.input.VisionEntry` | `opensamguk.logic.vision.VisionEntry` | 이 PR | 시야 항목 타입 패키지 이동 |
| `opensamguk.logic.input.VisionViewer` | `opensamguk.logic.vision.VisionViewer` | 이 PR | 시야 관찰자 타입 패키지 이동 |
| `opensamguk.logic.input.CorpsSighting` | `opensamguk.logic.vision.CorpsSighting` | 이 PR | 군단 목격 정보 타입 패키지 이동 |
| `logic/input/HwihaVisionTest.kt` | `logic/vision/VisionTest.kt` | 이 PR | 시야 투영 테스트 파일·타입·패키지 개명 |
| `opensamguk.logic.input.HwihaScoutInput` (`logic/input/HwihaScout.kt`) | `opensamguk.logic.vision.ScoutInputCodec` (`logic/vision/Scout.kt`) | 이 PR | 정찰 입력 파서; 데이터 타입 `ScoutInput`과 이름 충돌 회피 |
| `opensamguk.logic.input.HwihaScoutRules` | `opensamguk.logic.vision.ScoutRules` | 이 PR | 정찰 판정 규칙 |
| `opensamguk.logic.input.HwihaScoutReport` | `opensamguk.logic.vision.ScoutReport` | 이 PR | 정찰 보고서 |
| `opensamguk.logic.input.HwihaScoutReports` | `opensamguk.logic.vision.ScoutReports` | 이 PR | 정찰 보고서 묶음 |
| `opensamguk.logic.input.HwihaScoutCapture` | `opensamguk.logic.vision.ScoutCapture` | 이 PR | 정찰 관측 캡처 |
| `opensamguk.logic.input.HwihaCorpsTroops` | `opensamguk.logic.vision.CorpsTroops` | 이 PR | 시야 공개용 군단 병력 계산 |
| `opensamguk.logic.input.ScoutInput` | `opensamguk.logic.vision.ScoutInput` | 이 PR | 정찰 입력 데이터 타입 패키지 이동 |
| `opensamguk.logic.input.ScoutFailure` | `opensamguk.logic.vision.ScoutFailure` | 이 PR | 정찰 거절 사유 패키지 이동 |
| `opensamguk.logic.input.ScoutAssessment` | `opensamguk.logic.vision.ScoutAssessment` | 이 PR | 정찰 사전 판정 패키지 이동 |
| `opensamguk.logic.input.ScoutedCity` | `opensamguk.logic.vision.ScoutedCity` | 이 PR | 정찰 도시 관측값 패키지 이동 |
| `opensamguk.logic.input.ScoutedCorps` | `opensamguk.logic.vision.ScoutedCorps` | 이 PR | 정찰 군단 관측값 패키지 이동 |
| `opensamguk.logic.input.ScoutCityFact` | `opensamguk.logic.vision.ScoutCityFact` | 이 PR | 정찰 도시 사실값 패키지 이동 |
| `logic/input/HwihaScoutTest.kt` | `logic/vision/ScoutTest.kt` | 이 PR | 정찰 테스트 파일·타입·패키지 개명 |
| `logic/input/HwihaVisionSources.kt` | `logic/vision/VisionSources.kt` | 이 PR | 시야 출처 meta reader의 도메인 패키지 이동; 저장 키는 유지 |
| `opensamguk.logic.input.HwihaScoutPost` | `opensamguk.logic.vision.ScoutPost` | 이 PR | 도착한 정찰 배치 타입 |
| `opensamguk.logic.input.HwihaVisionSourceReader` | `opensamguk.logic.vision.VisionSourceReader` | 이 PR | 시야 출처 reader 인터페이스 |
| `opensamguk.logic.input.HwihaMetaVisionSourceReader` | `opensamguk.logic.vision.MetaVisionSourceReader` | 이 PR | 장수·도시 meta reader |
| `opensamguk.logic.input.SourceRead` | `opensamguk.logic.vision.SourceRead` | 이 PR | 시야 출처 읽기 결과 타입 패키지 이동 |
| `logic/input/HwihaDomesticVisionContractTest.kt` | `logic/vision/VisionSourceContractTest.kt` | 이 PR | 내정·시야 계약 테스트 파일·타입·패키지 개명 |
| `opensamguk.engine.hwiha.HwihaCourtHandler` | `opensamguk.engine.court.CourtHandler` | 예정 | 도메인 패키지 이동 |
| `opensamguk.common.wire.TurnDaemonCommand.HwihaCourtInput` | `opensamguk.common.wire.TurnDaemonCommand.ImmediateInput` | 이 PR | `@SerialName`과 inbox 저장값은 저장·통신 식별자 PR에서 변경 |

| `LegacyCourt*` (API·engine 코드 타입/파일) | `CourtAction*` | #937 draft | 저장 키 `hwihaLegacyCourt*`는 저장 계약 단계에서 처리 |
| `LegacyDirect*` (API·engine 코드 타입/파일) | `DirectAction*` | #937 draft | 저장 키 `hwihaLegacyDirect*`는 저장 계약 단계에서 처리 |
| `LegacyStratagem*` (API·engine 코드 타입/파일) | `StratagemAction*` | #937 draft | 저장 키 `hwihaLegacyStratagem*`는 저장 계약 단계에서 처리 |
| `QueuedLegacyCourt`, `QueuedLegacyStratagem` | `QueuedCourtAction`, `QueuedStratagemAction` | #937 draft | 직렬화 meta 키 값은 별도 처리 |
| `logic/world/HanMapConnectivityTest.kt` | `logic/world/WorldMapConnectivityTest.kt` | #937 draft | 특정 `han` 지도 픽스처는 유지 |

## 정한 값의 근거

- `ImmediateInput`은 조정 결정뿐 아니라 배치·방침·공사·계책도 운반하는 즉시 입력 와이어 타입이다. 첫 코드 개명 PR은 Kotlin 타입만 바꾸고 저장 값을 유지했다. 저장·통신 식별자 PR에서 discriminator `hwihaCourtInput`은 `immediateInput`, `command_inbox.action_code` 값 `HwihaCourtInput`은 `ImmediateInput`으로 바꾸고 producer·consumer·직렬화 테스트를 함께 갱신한다.
- `worldFormat = GENERAL_RETAINER_CAMPAIGN`은 유일한 제품 세계의 구조를 명시한다. 새 가드는 키·값이 없거나 옛 `ruleProfile`이 있으면 실패한다. 이전 데이터 자동 해석은 넣지 않는다.
- DB의 `siege`와 `person_card`는 현행 스키마에 같은 이름이 없어 충돌하지 않는다. 이름 변경은 새 Flyway 파일로만 실행한다.
- 상태 키 79종의 새 이름은 `hwiha` 접두사를 제거하되 현행 제품 의미가 남은 `Legacy`를 도메인 이름으로 풀어 썼다. 키 이름이 같은 다른 JSON 층(예: `corpsPolicies`)과 합쳐지지 않는지는 reader·writer별 픽스처에서 확인한다.

## 결정론 해시 도메인 구분자

| 이전 | 확정 이름 | 이유 |
|---|---|---|
| `hwihaBattlePlayback:v1` | `battlePlayback:v1` | 제품 접두사 제거; 재생 해시가 달라짐 |
| `hwihaEncounterResolution:v${RULE_VERSION}` | `encounterResolution:v${RULE_VERSION}` | 제품 접두사 제거; 조우 스냅샷 해시가 달라짐 |
| `hwihaSiegeAssault:v${RULE_VERSION}` | `siegeAssault:v${RULE_VERSION}` | 제품 접두사 제거; 공성 결과 해시가 달라짐 |
| `hwiha-corps:<orderId>` | `corps:<orderId>` | 정찰 관측에 쓰는 불투명 군단 ID의 해시 도메인 변경; 기존 관측 ID는 재사용하지 않음 |

## 저장·통신 식별자

| 이전 | 확정 이름 | 처리 PR | 비고 |
|---|---|---|---|
| `command_inbox.action_code` (IMMEDIATE) 값 `HwihaCourtInput` | `ImmediateInput` | 저장·통신 draft | #919에서는 기존 값 고정; reset 전 새 값으로 확정 |
| 와이어 discriminator `hwihaCourtInput` | `immediateInput` | 저장·통신 draft | `@SerialName`과 `type` 갱신 |
| `world_state.config.ruleProfile` | `worldFormat = GENERAL_RETAINER_CAMPAIGN` | 예정 | 값 없는 세계·옛 키·삼모 세계 fail closed |
| `hwiha_siege` | `siege` | DB 식별자 draft | V64에서 표·제약·인덱스 개명, V61 원본 유지 |
| `hwiha_person_card` | `person_card` | DB 식별자 draft | V64에서 뷰 개명과 새 meta 키 투영, V63 원본 유지 |
| `/api/hwiha/*` | 같은 도메인명 `/api/*` | 저장·통신 draft | 13개 조회 경로와 웹 클라이언트 호출 동시 갱신; `/api/game` 프록시는 그대로 전달 |
| `/game/<server>/hwiha/<screen>` | `/game/<server>/<screen>` | 저장·통신 draft | Next 경로 그룹 `(campaign)`으로 화면 이동; 옛 서버 경로와 서버 없는 경로 308 리다이렉트 |
| `data/**/hwiha-*.json` | 도메인별 파일명 | 저장·통신 draft | 18개 파일·내부 ID·빌드 패키징·로더·생성기 경로 동시 갱신 |
| `hwiha-stratagem-fortify`, `hwiha-stratagem-insight` | `stratagem-fortify`, `stratagem-insight` | 카드 ID 후속 | 카드 원장·기여 상태의 저장 ID 변경; pep 리셋 전 적용 |
| `hwiha_*` 엔진 경고 이벤트 이름 | `campaign_*` | 카드 ID 후속 | 캠페인 경고 로그의 제품 접두사 제거 |

## 데이터 파일·리소스 대응

| 이전 | 확정 이름 |
|---|---|
| `data/battle/hwiha-unit-profiles-v1.json` | `data/battle/unit-profiles-v1.json` |
| `data/commands/hwiha-input-catalog.json` | `data/commands/input-catalog.json` |
| `data/curated/han/hwiha-aptitude-weights-v1.json` | `data/curated/han/aptitude-weights-v1.json` |
| `data/curated/han/hwiha-s3-provisional-v1.json` | `data/curated/han/campaign-balance-v1.json` |
| `data/curated/han/hwiha-legacy-direct-v1.json` | `data/curated/han/direct-actions-v1.json` |
| `data/curated/han/hwiha-domestic-v1.json` | `data/curated/han/domestic-v1.json` |
| `data/curated/han/hwiha-equipment-v1.json` | `data/curated/han/equipment-v1.json` |
| `data/curated/han/hwiha-items-excluded-v1.json` | `data/curated/han/items-excluded-v1.json` |
| `data/curated/han/hwiha-military-v1.json` | `data/curated/han/military-v1.json` |
| `data/curated/han/hwiha-people-v1.json` | `data/curated/han/people-v1.json` |
| `data/curated/han/hwiha-personal-encounter-v1.json` | `data/curated/han/personal-encounter-v1.json` |
| `data/curated/han/hwiha-personal-v1.json` | `data/curated/han/personal-v1.json` |
| `data/curated/han/hwiha-political-v1.json` | `data/curated/han/political-v1.json` |
| `data/curated/han/hwiha-renown-assessment-v1.json` | `data/curated/han/renown-assessment-v1.json` |
| `data/curated/han/hwiha-renown-events-v1.json` | `data/curated/han/renown-events-v1.json` |
| `data/curated/han/hwiha-resource-production-v1.json` | `data/curated/han/resource-production-v1.json` |
| `data/curated/han/hwiha-treasure-cards-v1.json` | `data/curated/han/treasure-cards-v1.json` |
| `data/curated/han/hwiha-vision-rules-v1.json` | `data/curated/han/vision-rules-v1.json` |

classpath `hwiha/`는 `campaign/`으로 옮겼다. `tools/map/build_hwiha_resource_production.py`는 `build_county_resource_production.py`, `tools/content/build_hwiha_item_ledgers.py`는 `build_item_ledgers.py`가 되었으며, 생성 원장과 런타임 파일의 내부 ID·generator·sourceLedger도 새 이름을 쓴다. 지도 번들 판 ID(`han-world-v3-1447` 등)는 세계 핀 계약이므로 유지한다.

E2E 시나리오 픽스처 `tools/e2e/fixtures/hwiha-court`, `hwiha-yuzhou`는 각각 `court`, `yuzhou`로 옮겼다. 라이브 스펙은 `court-live.spec.ts`, `yuzhou-live.spec.ts`이며 실행 환경 변수는 `E2E_COURT_LIVE`, `E2E_YUZHOU_LIVE`다. 예약 서버 ID 목록 7곳은 드리프트 검사 `tools/ci/check_reserved_server_ids.py`로 묶었다.

## 상태·시나리오 필드 대응

소스에서 인용 부호로 읽고 쓰는 `hwiha…` 키 81종을 조사했다. 아래 79종은 장수·국가·縣 상태 및 시나리오 필드다. 와이어 타입 `hwihaCourtInput`과 DB 표 `hwiha_siege`는 위 표에 따로 적었다. 이름은 제품 접두사를 제거하며, 현행 제품 기능인 옛 `Legacy` 이름도 도메인 뜻으로 바꾼다. 저장 식별자 draft에서 reader·writer·fixture 130파일의 347참조를 같은 이름으로 교체했다. 옛 키 거절은 세계 형식 가드에서 검증한다.

| 이전 키 | 확정 키 | 현행 사용 위치 예시 |
|---|---|---|
| `hwihaBattleJournal` | `battleJournal` | `logic/src/main/kotlin/opensamguk/logic/war/hwiha/HwihaBattleJournal.kt` |
| `hwihaBattlePlans` | `battlePlans` | `logic/src/main/kotlin/opensamguk/logic/war/hwiha/HwihaBattlePlans.kt` |
| `hwihaCaptive` | `captive` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaPeopleRules.kt` |
| `hwihaCityMilitary` | `cityMilitary` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaCityMilitaryState.kt` |
| `hwihaCityMilitaryLastTurn` | `cityMilitaryLastTurn` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaCityMilitaryHandler.kt` |
| `hwihaCommanderyPolicies` | `commanderyPolicies` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaDomesticState.kt` |
| `hwihaCorpsEncounter` | `corpsEncounter` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaCorpsEncounter.kt` |
| `hwihaCorpsMarch` | `corpsMarch` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaCorpsMarchState.kt` |
| `hwihaCorpsOrder` | `corpsOrder` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaCorpsOrder.kt` |
| `hwihaCorpsPolicies` | `corpsPolicies` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaDomesticState.kt` |
| `hwihaCountyAssignment` | `countyAssignment` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaDispatchState.kt` |
| `hwihaCountyIncomeMonth` | `countyIncomeMonth` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaMonthlyCountyIncome.kt` |
| `hwihaCountyMeritWindow` | `countyMeritWindow` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaCountyMeritWindow.kt` |
| `hwihaCountyMonthly` | `countyMonthly` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaDomesticState.kt` |
| `hwihaCountyPolicy` | `countyPolicy` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaDomesticState.kt` |
| `hwihaCountyWarehouse` | `countyWarehouse` | `logic/src/main/kotlin/opensamguk/logic/economy/CountyWarehouse.kt` |
| `hwihaCountyWorks` | `countyWorks` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaDomesticState.kt` |
| `hwihaDeparture` | `departure` | `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/HwihaRenownRecordsTest.kt` |
| `hwihaDeployment` | `deployment` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaDeploymentState.kt` |
| `hwihaDirectTravel` | `directTravel` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaTravelState.kt` |
| `hwihaDispatch` | `dispatch` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaDispatchState.kt` |
| `hwihaDomesticPhase` | `domesticPhase` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaDomesticBoundary.kt` |
| `hwihaEncounterCombatProfiles` | `encounterCombatProfiles` | `logic/src/main/kotlin/opensamguk/logic/war/hwiha/HwihaEncounterCombatProfiles.kt` |
| `hwihaEncounterDeployment` | `encounterDeployment` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaEncounterDeployment.kt` |
| `hwihaEncounterForces` | `encounterForces` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaEncounterForces.kt` |
| `hwihaEncounterRelations` | `encounterRelations` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaEncounterRelations.kt` |
| `hwihaFieldLastTurn` | `fieldLastTurn` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaFieldHandler.kt` |
| `hwihaFoundedBy` | `foundedBy` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaPoliticalHandler.kt` |
| `hwihaLandPassage` | `landPassage` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaLandPassageState.kt` |
| `hwihaLastBattle` | `lastBattle` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaEncounterResolver.kt` |
| `hwihaLastEncounterDisbanded` | `lastEncounterDisbanded` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaEncounterResolver.kt` |
| `hwihaLastPersonalEncounter` | `lastPersonalEncounter` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaPersonalEncounter.kt` |
| `hwihaLastPersonalTurn` | `lastPersonalTurn` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaPersonalTurn.kt` |
| `hwihaLegacyCourtLastExecution` | `courtLastExecution` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaCourtHandler.kt` |
| `hwihaLegacyDirectLastTurn` | `directActionLastTurn` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaLegacyDirectHandler.kt` |
| `hwihaLegacyReleaseCorpsLast` | `releaseCorpsLast` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaLegacyCourtExecutor.kt` |
| `hwihaLegacyStratagemLastExecution` | `stratagemLastExecution` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaCourtHandler.kt` |
| `hwihaLegacyStratagemStock` | `stratagemStock` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaLegacyStratagemRules.kt` |
| `hwihaLord` | `lord` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaLordStatus.kt` |
| `hwihaLords` | `lords` | `infra/src/main/kotlin/opensamguk/infra/seed/ScenarioJson.kt` |
| `hwihaMarch` | `march` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaMarchState.kt` |
| `hwihaMarchReactions` | `marchReactions` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaMarchReactions.kt` |
| `hwihaMusterLastTurn` | `musterLastTurn` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaMusterHandler.kt` |
| `hwihaPeopleLastTurn` | `peopleLastTurn` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaPeopleHandler.kt` |
| `hwihaPersonBonds` | `personBonds` | `logic/src/main/kotlin/opensamguk/logic/content/HwihaPersonCard.kt` |
| `hwihaPersonContribution` | `personContribution` | `logic/src/main/kotlin/opensamguk/logic/content/HwihaPersonCard.kt` |
| `hwihaPersonPolicies` | `personPolicies` | `infra/src/main/kotlin/opensamguk/infra/seed/HwihaScenarioPersonPolicies.kt` |
| `hwihaPersonPolicy` | `personPolicy` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaPersonPolicyState.kt` |
| `hwihaPersonalLastTurn` | `personalLastTurn` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaPersonalHandler.kt` |
| `hwihaPersonalTravelCondition` | `personalTravelCondition` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaPersonalTravelCondition.kt` |
| `hwihaPersonalTravelRecoveryAt` | `personalTravelRecoveryAt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaTravelTurn.kt` |
| `hwihaPlacement` | `placement` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaDomesticState.kt` |
| `hwihaPlacementMarch` | `placementMarch` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaDomesticState.kt` |
| `hwihaPoliticalConsent` | `politicalConsent` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaPoliticalConsent.kt` |
| `hwihaPoliticalLastTurn` | `politicalLastTurn` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaPoliticalHandler.kt` |
| `hwihaPortableStock` | `portableStock` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaPortableStock.kt` |
| `hwihaQueuedDispatch` | `queuedDispatch` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaCourtQueue.kt` |
| `hwihaQueuedLegacyCourt` | `queuedCourt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaLegacyCourtExecutor.kt` |
| `hwihaQueuedLegacyStratagem` | `queuedStratagem` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaLegacyStratagemExecutor.kt` |
| `hwihaQueuedReward` | `queuedReward` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaRewardInput.kt` |
| `hwihaRenownAssessmentStamp` | `renownAssessmentStamp` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaRenownAssessment.kt` |
| `hwihaRenownRanking` | `renownRanking` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaRenownAssessment.kt` |
| `hwihaRenownReasons` | `renownReasons` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaRenownAssessment.kt` |
| `hwihaRenownTally` | `renownTally` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaRenownEvents.kt` |
| `hwihaRetireLastTurn` | `retireLastTurn` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaRetireHandler.kt` |
| `hwihaRetired` | `retired` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaRetireRules.kt` |
| `hwihaSalaryMonth` | `salaryMonth` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaMonthlySalary.kt` |
| `hwihaScoutPosts` | `scoutPosts` | `logic/src/main/kotlin/opensamguk/logic/vision/ScoutPosts.kt` |
| `hwihaScoutReports` | `scoutReports` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaScout.kt` |
| `hwihaStratagemHand` | `stratagemHand` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaStratagemHand.kt` |
| `hwihaSupplyConvoyMonth` | `supplyConvoyMonth` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaCorpsRations.kt` |
| `hwihaSupplyConvoys` | `supplyConvoys` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaCorpsRations.kt` |
| `hwihaTalentDiscovery` | `talentDiscovery` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaTalentDiscovery.kt` |
| `hwihaTransferLastTurn` | `transferLastTurn` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaTransferHandler.kt` |
| `hwihaTreasureInventory` | `treasureInventory` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaTreasureInventory.kt` |
| `hwihaUnitResupplyMonth` | `unitResupplyMonth` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaUnitResupply.kt` |
| `hwihaUnits` | `units` | `infra/src/main/kotlin/opensamguk/infra/seed/HwihaScenarioUnits.kt` |
| `hwihaWarehouseSeed` | `warehouseSeed` | `infra/src/main/kotlin/opensamguk/infra/seed/ScenarioImporter.kt` |
| `hwihaWarehouses` | `warehouses` | `infra/src/main/kotlin/opensamguk/infra/seed/HwihaScenarioWarehouseSeeds.kt` |

## 명망 로직 개명

| 이전 | 새 이름 | 비고 |
|---|---|---|
| `opensamguk.logic.input.HwihaRenownAssessment` | `opensamguk.logic.renown.RenownAssessment` | 저장 키·정본 데이터 파일 이름은 저장 식별자 단계에서 변경 |
| `opensamguk.logic.input.HwihaRenownEvents` | `opensamguk.logic.renown.RenownEvents` | 사건 집계와 저장 키는 별도 변경 |
| `opensamguk.logic.input.HwihaRenownRules` | `opensamguk.logic.renown.RenownRules` | 인물 코스트 규칙 |
| `opensamguk.logic.input.HwihaRenownEventKind` | `opensamguk.logic.renown.RenownEventKind` | 사건 종류 |
| `opensamguk.logic.input.HwihaRenownEventSource` | `opensamguk.logic.renown.RenownEventSource` | 사건 원인 |
| `opensamguk.logic.input.HwihaRenownEntry` | `opensamguk.logic.renown.RenownEntry` | 집계 항목 |
| `opensamguk.logic.input.HwihaRenownHooks` | `opensamguk.logic.renown.RenownHooks` | 사건 기록 훅 |
| `opensamguk.logic.input.HwihaDomesticMerit` | `opensamguk.logic.renown.DomesticMerit` | 월단평용 내정 치적 판정 |
| `logic/input/HwihaRenown*Test.kt` | `logic/renown/Renown*Test.kt` | 테스트 파일·타입·패키지 개명 |

## 전쟁 로직 개명

조우·전투·공성 파일은 `opensamguk.logic.war.hwiha`에서 `opensamguk.logic.war`로 옮긴다. 저장 키·데이터 파일명은 저장 식별자 단계에서 바꾼다.

| 이전 파일·타입 | 새 파일·타입 |
|---|---|
| `logic/src/main/kotlin/opensamguk/logic/war/hwiha/HwihaBattleAutopilot.kt` | `logic/src/main/kotlin/opensamguk/logic/war/BattleAutopilot.kt` |
| `logic/src/main/kotlin/opensamguk/logic/war/hwiha/HwihaBattleConditions.kt` | `logic/src/main/kotlin/opensamguk/logic/war/BattleConditions.kt` |
| `logic/src/main/kotlin/opensamguk/logic/war/hwiha/HwihaBattleJournal.kt` | `logic/src/main/kotlin/opensamguk/logic/war/BattleJournal.kt` |
| `logic/src/main/kotlin/opensamguk/logic/war/hwiha/HwihaBattlePlans.kt` | `logic/src/main/kotlin/opensamguk/logic/war/BattlePlans.kt` |
| `logic/src/main/kotlin/opensamguk/logic/war/hwiha/HwihaBattlePlayback.kt` | `logic/src/main/kotlin/opensamguk/logic/war/BattlePlayback.kt` |
| `logic/src/main/kotlin/opensamguk/logic/war/hwiha/HwihaS3Provisional.kt` | `logic/src/main/kotlin/opensamguk/logic/war/CampaignBalance.kt` |
| `logic/src/main/kotlin/opensamguk/logic/war/hwiha/HwihaCountyCapture.kt` | `logic/src/main/kotlin/opensamguk/logic/war/CountyCapture.kt` |
| `logic/src/main/kotlin/opensamguk/logic/war/hwiha/HwihaEncounterCombatProfiles.kt` | `logic/src/main/kotlin/opensamguk/logic/war/EncounterCombatProfiles.kt` |
| `logic/src/main/kotlin/opensamguk/logic/war/hwiha/HwihaEncounterResolution.kt` | `logic/src/main/kotlin/opensamguk/logic/war/EncounterResolution.kt` |
| `logic/src/main/kotlin/opensamguk/logic/war/hwiha/HwihaGridExchange.kt` | `logic/src/main/kotlin/opensamguk/logic/war/GridExchange.kt` |
| `logic/src/main/kotlin/opensamguk/logic/war/hwiha/HwihaGridMovement.kt` | `logic/src/main/kotlin/opensamguk/logic/war/GridMovement.kt` |
| `logic/src/main/kotlin/opensamguk/logic/war/hwiha/HwihaGridReach.kt` | `logic/src/main/kotlin/opensamguk/logic/war/GridReach.kt` |
| `logic/src/main/kotlin/opensamguk/logic/war/hwiha/HwihaSiegeAssault.kt` | `logic/src/main/kotlin/opensamguk/logic/war/SiegeAssault.kt` |
| `logic/src/main/kotlin/opensamguk/logic/war/hwiha/HwihaSiegeMorale.kt` | `logic/src/main/kotlin/opensamguk/logic/war/SiegeMorale.kt` |
| `logic/src/main/kotlin/opensamguk/logic/war/hwiha/HwihaSiegeRules.kt` | `logic/src/main/kotlin/opensamguk/logic/war/SiegeRules.kt` |
| `logic/src/main/kotlin/opensamguk/logic/war/hwiha/HwihaUnitProfiles.kt` | `logic/src/main/kotlin/opensamguk/logic/war/UnitProfiles.kt` |
| `logic/src/test/kotlin/opensamguk/logic/war/hwiha/HwihaBattleAutopilotTest.kt` | `logic/src/test/kotlin/opensamguk/logic/war/BattleAutopilotTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/war/hwiha/HwihaBattleConditionsTest.kt` | `logic/src/test/kotlin/opensamguk/logic/war/BattleConditionsTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/war/hwiha/HwihaBattleJournalTest.kt` | `logic/src/test/kotlin/opensamguk/logic/war/BattleJournalTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/war/hwiha/HwihaBattlePlansTest.kt` | `logic/src/test/kotlin/opensamguk/logic/war/BattlePlansTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/war/hwiha/HwihaBattlePlaybackTest.kt` | `logic/src/test/kotlin/opensamguk/logic/war/BattlePlaybackTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/war/hwiha/HwihaS3ProvisionalTest.kt` | `logic/src/test/kotlin/opensamguk/logic/war/CampaignBalanceTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/war/hwiha/HwihaCountyCaptureTest.kt` | `logic/src/test/kotlin/opensamguk/logic/war/CountyCaptureTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/war/hwiha/HwihaEncounterCombatProfilesTest.kt` | `logic/src/test/kotlin/opensamguk/logic/war/EncounterCombatProfilesTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/war/hwiha/HwihaEncounterResolutionTest.kt` | `logic/src/test/kotlin/opensamguk/logic/war/EncounterResolutionTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/war/hwiha/HwihaGridExchangeTest.kt` | `logic/src/test/kotlin/opensamguk/logic/war/GridExchangeTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/war/hwiha/HwihaGridMovementTest.kt` | `logic/src/test/kotlin/opensamguk/logic/war/GridMovementTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/war/hwiha/HwihaGridReachTest.kt` | `logic/src/test/kotlin/opensamguk/logic/war/GridReachTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/war/hwiha/HwihaSiegeAssaultTest.kt` | `logic/src/test/kotlin/opensamguk/logic/war/SiegeAssaultTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/war/hwiha/HwihaSiegeMoraleTest.kt` | `logic/src/test/kotlin/opensamguk/logic/war/SiegeMoraleTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/war/hwiha/HwihaSiegeRulesTest.kt` | `logic/src/test/kotlin/opensamguk/logic/war/SiegeRulesTest.kt` |
| `HwihaRoundInput` | `RoundInput` |
| `HwihaUnitProfile` | `UnitProfile` |
| `HwihaS3Provisional` | `CampaignBalance` |
## 입력 로직 개명

다음은 제품 접두사와 옛 ‘Legacy’ 설계 표기를 제거한 순수 코드 이름 변경이다. 저장 키·와이어 값은 아직 유지한다. 이름이 겹치는 `HwihaDeployInput`·`HwihaDeploymentProjection`·`HwihaRetinueLedger`는 도메인 패키지 이동 때 처리한다.

| 이전 파일 | 새 파일 |
|---|---|
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaAptitude.kt` | `logic/src/main/kotlin/opensamguk/logic/input/Aptitude.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaCatalogDuplicateKeys.kt` | `logic/src/main/kotlin/opensamguk/logic/input/CatalogDuplicateKeys.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaCityMilitaryState.kt` | `logic/src/main/kotlin/opensamguk/logic/input/CityMilitaryState.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaCorpsEncounter.kt` | `logic/src/main/kotlin/opensamguk/logic/input/CorpsEncounter.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaCorpsMarchState.kt` | `logic/src/main/kotlin/opensamguk/logic/input/CorpsMarchState.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaCorpsOrder.kt` | `logic/src/main/kotlin/opensamguk/logic/input/CorpsOrder.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaCountyGeography.kt` | `logic/src/main/kotlin/opensamguk/logic/input/CountyGeography.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaCourtExpansionInput.kt` | `logic/src/main/kotlin/opensamguk/logic/input/CourtExpansionInput.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaCourtQueue.kt` | `logic/src/main/kotlin/opensamguk/logic/input/CourtQueue.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaCourtResourceInput.kt` | `logic/src/main/kotlin/opensamguk/logic/input/CourtResourceInput.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaLegacyCourtRules.kt` | `logic/src/main/kotlin/opensamguk/logic/input/CourtRules.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaDeployRules.kt` | `logic/src/main/kotlin/opensamguk/logic/input/DeployRules.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaDeploymentRules.kt` | `logic/src/main/kotlin/opensamguk/logic/input/DeploymentRules.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaDeploymentState.kt` | `logic/src/main/kotlin/opensamguk/logic/input/DeploymentState.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaDiplomacyInput.kt` | `logic/src/main/kotlin/opensamguk/logic/input/DiplomacyInput.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaLegacyDirectDesign.kt` | `logic/src/main/kotlin/opensamguk/logic/input/DirectDesign.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaLegacyDirectInput.kt` | `logic/src/main/kotlin/opensamguk/logic/input/DirectInput.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaLegacyDirectRules.kt` | `logic/src/main/kotlin/opensamguk/logic/input/DirectRules.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaDispatchInput.kt` | `logic/src/main/kotlin/opensamguk/logic/input/DispatchInput.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaDispatchRules.kt` | `logic/src/main/kotlin/opensamguk/logic/input/DispatchRules.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaDispatchState.kt` | `logic/src/main/kotlin/opensamguk/logic/input/DispatchState.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaEncounterDeployment.kt` | `logic/src/main/kotlin/opensamguk/logic/input/EncounterDeployment.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaEncounterForces.kt` | `logic/src/main/kotlin/opensamguk/logic/input/EncounterForces.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaEncounterRelations.kt` | `logic/src/main/kotlin/opensamguk/logic/input/EncounterRelations.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaEnlistmentBudget.kt` | `logic/src/main/kotlin/opensamguk/logic/input/EnlistmentBudget.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaEnlistmentInput.kt` | `logic/src/main/kotlin/opensamguk/logic/input/EnlistmentInput.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaEnlistmentPrecheck.kt` | `logic/src/main/kotlin/opensamguk/logic/input/EnlistmentPrecheck.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaEnlistmentRules.kt` | `logic/src/main/kotlin/opensamguk/logic/input/EnlistmentRules.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaFlatArguments.kt` | `logic/src/main/kotlin/opensamguk/logic/input/FlatArguments.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaForcedMarchTempo.kt` | `logic/src/main/kotlin/opensamguk/logic/input/ForcedMarchTempo.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaGovernanceMerit.kt` | `logic/src/main/kotlin/opensamguk/logic/input/GovernanceMerit.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaInputRegistry.kt` | `logic/src/main/kotlin/opensamguk/logic/input/InputRegistry.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaLandPassageState.kt` | `logic/src/main/kotlin/opensamguk/logic/input/LandPassageState.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaLordStatus.kt` | `logic/src/main/kotlin/opensamguk/logic/input/LordStatus.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaMarchCheckpoint.kt` | `logic/src/main/kotlin/opensamguk/logic/input/MarchCheckpoint.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaMarchReactions.kt` | `logic/src/main/kotlin/opensamguk/logic/input/MarchReactions.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaMarchState.kt` | `logic/src/main/kotlin/opensamguk/logic/input/MarchState.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaMilitaryDesign.kt` | `logic/src/main/kotlin/opensamguk/logic/input/MilitaryDesign.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaMilitaryInput.kt` | `logic/src/main/kotlin/opensamguk/logic/input/MilitaryInput.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaMilitaryPresence.kt` | `logic/src/main/kotlin/opensamguk/logic/input/MilitaryPresence.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaMilitaryRules.kt` | `logic/src/main/kotlin/opensamguk/logic/input/MilitaryRules.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaMusterRules.kt` | `logic/src/main/kotlin/opensamguk/logic/input/MusterRules.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaOathBonds.kt` | `logic/src/main/kotlin/opensamguk/logic/input/OathBonds.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaPeopleDesign.kt` | `logic/src/main/kotlin/opensamguk/logic/input/PeopleDesign.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaPeopleInput.kt` | `logic/src/main/kotlin/opensamguk/logic/input/PeopleInput.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaPeopleRules.kt` | `logic/src/main/kotlin/opensamguk/logic/input/PeopleRules.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaPersonPolicyState.kt` | `logic/src/main/kotlin/opensamguk/logic/input/PersonPolicyState.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaPersonalDesign.kt` | `logic/src/main/kotlin/opensamguk/logic/input/PersonalDesign.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaPersonalEncounterBattle.kt` | `logic/src/main/kotlin/opensamguk/logic/input/PersonalEncounterBattle.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaPersonalEncounterDesign.kt` | `logic/src/main/kotlin/opensamguk/logic/input/PersonalEncounterDesign.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaPersonalInput.kt` | `logic/src/main/kotlin/opensamguk/logic/input/PersonalInput.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaPersonalRules.kt` | `logic/src/main/kotlin/opensamguk/logic/input/PersonalRules.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaPersonalTravelCondition.kt` | `logic/src/main/kotlin/opensamguk/logic/input/PersonalTravelCondition.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaPoliticalConsent.kt` | `logic/src/main/kotlin/opensamguk/logic/input/PoliticalConsent.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaPoliticalDesign.kt` | `logic/src/main/kotlin/opensamguk/logic/input/PoliticalDesign.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaPoliticalInput.kt` | `logic/src/main/kotlin/opensamguk/logic/input/PoliticalInput.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaPoliticalRules.kt` | `logic/src/main/kotlin/opensamguk/logic/input/PoliticalRules.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaPortableStock.kt` | `logic/src/main/kotlin/opensamguk/logic/input/PortableStock.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaRecordKind.kt` | `logic/src/main/kotlin/opensamguk/logic/input/RecordKind.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaRetireInput.kt` | `logic/src/main/kotlin/opensamguk/logic/input/RetireInput.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaRetireRules.kt` | `logic/src/main/kotlin/opensamguk/logic/input/RetireRules.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaRewardInput.kt` | `logic/src/main/kotlin/opensamguk/logic/input/RewardInput.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaStratagemHand.kt` | `logic/src/main/kotlin/opensamguk/logic/input/StratagemHand.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaLegacyStratagemRules.kt` | `logic/src/main/kotlin/opensamguk/logic/input/StratagemRules.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaTalentDiscovery.kt` | `logic/src/main/kotlin/opensamguk/logic/input/TalentDiscovery.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaTransferInput.kt` | `logic/src/main/kotlin/opensamguk/logic/input/TransferInput.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaTransferRules.kt` | `logic/src/main/kotlin/opensamguk/logic/input/TransferRules.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaTravelInput.kt` | `logic/src/main/kotlin/opensamguk/logic/input/TravelInput.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaTravelReturn.kt` | `logic/src/main/kotlin/opensamguk/logic/input/TravelReturn.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaTravelRules.kt` | `logic/src/main/kotlin/opensamguk/logic/input/TravelRules.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaTravelState.kt` | `logic/src/main/kotlin/opensamguk/logic/input/TravelState.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaTreasureInventory.kt` | `logic/src/main/kotlin/opensamguk/logic/input/TreasureInventory.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaAptitudeTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/AptitudeTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaCorpsEncounterProjectionTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/CorpsEncounterProjectionTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaCorpsEncounterTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/CorpsEncounterTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaCorpsMarchStateTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/CorpsMarchStateTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaCorpsOrderTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/CorpsOrderTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaCourtExpansionInputTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/CourtExpansionInputTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaCourtQueueTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/CourtQueueTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaCourtResourceInputTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/CourtResourceInputTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaDeployInputTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/DeployInputTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaDeployRulesTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/DeployRulesTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaDeploymentProjectionTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/DeploymentProjectionTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaDeploymentRulesTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/DeploymentRulesTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaDiplomacyInputTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/DiplomacyInputTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaLegacyDirectInputTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/DirectInputTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaDispatchInputTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/DispatchInputTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaDispatchRulesTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/DispatchRulesTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaEncounterDeploymentTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/EncounterDeploymentTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaEncounterForcesTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/EncounterForcesTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaEncounterRelationsTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/EncounterRelationsTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaEnlistmentBudgetTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/EnlistmentBudgetTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaEnlistmentInputTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/EnlistmentInputTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaEnlistmentPrecheckTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/EnlistmentPrecheckTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaEnlistmentRulesTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/EnlistmentRulesTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaInputRegistryTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/InputRegistryTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaLandPassageStateTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/LandPassageStateTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaLordStatusTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/LordStatusTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaMarchReactionsPresenceTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/MarchReactionsPresenceTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaMarchReactionsTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/MarchReactionsTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaMarchStateTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/MarchStateTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaMilitaryPresenceTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/MilitaryPresenceTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaMilitaryRulesTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/MilitaryRulesTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaPeopleInputTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/PeopleInputTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaPeopleRulesTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/PeopleRulesTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaPersonPolicyStateTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/PersonPolicyStateTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaPersonalEncounterBattleTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/PersonalEncounterBattleTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaPersonalInputTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/PersonalInputTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaPersonalRulesTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/PersonalRulesTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaPersonalTravelConditionTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/PersonalTravelConditionTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaPoliticalInputTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/PoliticalInputTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaPoliticalRulesTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/PoliticalRulesTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaRetinueLedgerTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/RetinueLedgerTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaRetireInputTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/RetireInputTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaRetireRulesTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/RetireRulesTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaStratagemHandTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/StratagemHandTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaTransferInputTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/TransferInputTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaTravelInputTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/TravelInputTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaTravelReturnTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/TravelReturnTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaTravelRulesTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/TravelRulesTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/input/HwihaTravelStateTest.kt` | `logic/src/test/kotlin/opensamguk/logic/input/TravelStateTest.kt` |

| 이전 타입 | 새 타입 |
|---|---|
| `HwihaAptitude` | `Aptitude` |
| `HwihaAptitudeTest` | `AptitudeTest` |
| `HwihaCargo` | `Cargo` |
| `HwihaCatalogDuplicateKeys` | `CatalogDuplicateKeys` |
| `HwihaCityMilitaryAssessment` | `CityMilitaryAssessment` |
| `HwihaCityMilitaryPlan` | `CityMilitaryPlan` |
| `HwihaCityMilitaryState` | `CityMilitaryState` |
| `HwihaCorpsEncounter` | `CorpsEncounter` |
| `HwihaCorpsEncounterProjectionTest` | `CorpsEncounterProjectionTest` |
| `HwihaCorpsEncounterTest` | `CorpsEncounterTest` |
| `HwihaCorpsMarchState` | `CorpsMarchState` |
| `HwihaCorpsMarchStateTest` | `CorpsMarchStateTest` |
| `HwihaCorpsOrder` | `CorpsOrder` |
| `HwihaCorpsOrderTest` | `CorpsOrderTest` |
| `HwihaCountyAssignment` | `CountyAssignment` |
| `HwihaCountyGeography` | `CountyGeography` |
| `HwihaCountyPlace` | `CountyPlace` |
| `HwihaCourtExpansionInput` | `CourtExpansionInput` |
| `HwihaCourtExpansionInputTest` | `CourtExpansionInputTest` |
| `HwihaCourtExpansionRequest` | `CourtExpansionRequest` |
| `HwihaCourtQueueTest` | `CourtQueueTest` |
| `HwihaCourtResourceInput` | `CourtResourceInput` |
| `HwihaCourtResourceInputTest` | `CourtResourceInputTest` |
| `HwihaCourtResourceRequest` | `CourtResourceRequest` |
| `HwihaDeployInputTest` | `DeployInputTest` |
| `HwihaDeployRules` | `DeployRules` |
| `HwihaDeployRulesTest` | `DeployRulesTest` |
| `HwihaDeployedCorps` | `DeployedCorps` |
| `HwihaDeploymentProjectionTest` | `DeploymentProjectionTest` |
| `HwihaDeploymentRules` | `DeploymentRules` |
| `HwihaDeploymentRulesTest` | `DeploymentRulesTest` |
| `HwihaDeploymentState` | `DeploymentState` |
| `HwihaDiplomacyInput` | `DiplomacyInput` |
| `HwihaDiplomacyInputTest` | `DiplomacyInputTest` |
| `HwihaDiplomacyRequest` | `DiplomacyRequest` |
| `HwihaDispatchInput` | `DispatchInput` |
| `HwihaDispatchInputTest` | `DispatchInputTest` |
| `HwihaDispatchPolicy` | `DispatchPolicy` |
| `HwihaDispatchProjection` | `DispatchProjection` |
| `HwihaDispatchReplyInput` | `DispatchReplyInput` |
| `HwihaDispatchRules` | `DispatchRules` |
| `HwihaDispatchRulesTest` | `DispatchRulesTest` |
| `HwihaDispatchState` | `DispatchState` |
| `HwihaEncounterDeployment` | `EncounterDeployment` |
| `HwihaEncounterDeploymentTest` | `EncounterDeploymentTest` |
| `HwihaEncounterForces` | `EncounterForces` |
| `HwihaEncounterForcesTest` | `EncounterForcesTest` |
| `HwihaEncounterParticipant` | `EncounterParticipant` |
| `HwihaEncounterRelations` | `EncounterRelations` |
| `HwihaEncounterRelationsTest` | `EncounterRelationsTest` |
| `HwihaEnlistmentBudget` | `EnlistmentBudget` |
| `HwihaEnlistmentBudgetTest` | `EnlistmentBudgetTest` |
| `HwihaEnlistmentInput` | `EnlistmentInput` |
| `HwihaEnlistmentInputTest` | `EnlistmentInputTest` |
| `HwihaEnlistmentPrecheck` | `EnlistmentPrecheck` |
| `HwihaEnlistmentPrecheckTest` | `EnlistmentPrecheckTest` |
| `HwihaEnlistmentProjection` | `EnlistmentProjection` |
| `HwihaEnlistmentRules` | `EnlistmentRules` |
| `HwihaEnlistmentRulesTest` | `EnlistmentRulesTest` |
| `HwihaFlatArguments` | `FlatArguments` |
| `HwihaForcedMarchTempo` | `ForcedMarchTempo` |
| `HwihaGovernanceMeritEvent` | `GovernanceMeritEvent` |
| `HwihaGovernanceMeritSink` | `GovernanceMeritSink` |
| `HwihaInputCatalog` | `InputCatalog` |
| `HwihaInputEntry` | `InputEntry` |
| `HwihaInputRegistry` | `InputRegistry` |
| `HwihaInputRegistryTest` | `InputRegistryTest` |
| `HwihaInstalledScheme` | `InstalledScheme` |
| `HwihaLandPassageState` | `LandPassageState` |
| `HwihaLandPassageStateTest` | `LandPassageStateTest` |
| `HwihaLegacyCourtAssessment` | `CourtAssessment` |
| `HwihaLegacyCourtFailure` | `CourtFailure` |
| `HwihaLegacyCourtInput` | `CourtInput` |
| `HwihaLegacyCourtReady` | `CourtReady` |
| `HwihaLegacyCourtRules` | `CourtRules` |
| `HwihaLegacyDirectAssessment` | `DirectAssessment` |
| `HwihaLegacyDirectDesign` | `DirectDesign` |
| `HwihaLegacyDirectFailure` | `DirectFailure` |
| `HwihaLegacyDirectInput` | `DirectInput` |
| `HwihaLegacyDirectInputTest` | `DirectInputTest` |
| `HwihaLegacyDirectRequest` | `DirectRequest` |
| `HwihaLegacyDirectRules` | `DirectRules` |
| `HwihaLegacyStratagemAssessment` | `StratagemAssessment` |
| `HwihaLegacyStratagemFailure` | `StratagemFailure` |
| `HwihaLegacyStratagemInput` | `StratagemInput` |
| `HwihaLegacyStratagemReady` | `StratagemReady` |
| `HwihaLegacyStratagemRules` | `StratagemRules` |
| `HwihaLegacyStratagemStock` | `StratagemStock` |
| `HwihaLordStatus` | `LordStatus` |
| `HwihaLordStatusTest` | `LordStatusTest` |
| `HwihaMarchCheckpoint` | `MarchCheckpoint` |
| `HwihaMarchReactions` | `MarchReactions` |
| `HwihaMarchReactionsPresenceTest` | `MarchReactionsPresenceTest` |
| `HwihaMarchReactionsTest` | `MarchReactionsTest` |
| `HwihaMarchState` | `MarchState` |
| `HwihaMarchStateTest` | `MarchStateTest` |
| `HwihaMilitaryDesign` | `MilitaryDesign` |
| `HwihaMilitaryFailure` | `MilitaryFailure` |
| `HwihaMilitaryInput` | `MilitaryInput` |
| `HwihaMilitaryPresence` | `MilitaryPresence` |
| `HwihaMilitaryPresenceTest` | `MilitaryPresenceTest` |
| `HwihaMilitaryRequest` | `MilitaryRequest` |
| `HwihaMilitaryRules` | `MilitaryRules` |
| `HwihaMilitaryRulesTest` | `MilitaryRulesTest` |
| `HwihaMusterAssessment` | `MusterAssessment` |
| `HwihaMusterRules` | `MusterRules` |
| `HwihaNativeCountyLedger` | `NativeCountyLedger` |
| `HwihaOathBonds` | `OathBonds` |
| `HwihaPeopleAssessment` | `PeopleAssessment` |
| `HwihaPeopleDesign` | `PeopleDesign` |
| `HwihaPeopleFailure` | `PeopleFailure` |
| `HwihaPeopleInput` | `PeopleInput` |
| `HwihaPeopleInputTest` | `PeopleInputTest` |
| `HwihaPeopleRequest` | `PeopleRequest` |
| `HwihaPeopleRules` | `PeopleRules` |
| `HwihaPeopleRulesTest` | `PeopleRulesTest` |
| `HwihaPersonPolicyState` | `PersonPolicyState` |
| `HwihaPersonPolicyStateTest` | `PersonPolicyStateTest` |
| `HwihaPersonalAssessment` | `PersonalAssessment` |
| `HwihaPersonalDesign` | `PersonalDesign` |
| `HwihaPersonalEncounterBattle` | `PersonalEncounterBattle` |
| `HwihaPersonalEncounterBattleTest` | `PersonalEncounterBattleTest` |
| `HwihaPersonalEncounterDesign` | `PersonalEncounterDesign` |
| `HwihaPersonalFailure` | `PersonalFailure` |
| `HwihaPersonalInput` | `PersonalInput` |
| `HwihaPersonalInputTest` | `PersonalInputTest` |
| `HwihaPersonalRequest` | `PersonalRequest` |
| `HwihaPersonalRules` | `PersonalRules` |
| `HwihaPersonalRulesTest` | `PersonalRulesTest` |
| `HwihaPersonalTravelCondition` | `PersonalTravelCondition` |
| `HwihaPersonalTravelConditionTest` | `PersonalTravelConditionTest` |
| `HwihaPersonalTravelDistance` | `PersonalTravelDistance` |
| `HwihaPhase` | `Phase` |
| `HwihaPoliticalAssessment` | `PoliticalAssessment` |
| `HwihaPoliticalConsent` | `PoliticalConsent` |
| `HwihaPoliticalDesign` | `PoliticalDesign` |
| `HwihaPoliticalFailure` | `PoliticalFailure` |
| `HwihaPoliticalInput` | `PoliticalInput` |
| `HwihaPoliticalInputTest` | `PoliticalInputTest` |
| `HwihaPoliticalRequest` | `PoliticalRequest` |
| `HwihaPoliticalRules` | `PoliticalRules` |
| `HwihaPoliticalRulesTest` | `PoliticalRulesTest` |
| `HwihaPortableStock` | `PortableStock` |
| `HwihaQueuedDispatch` | `QueuedDispatch` |
| `HwihaQueuedReward` | `QueuedReward` |
| `HwihaReactionOrder` | `ReactionOrder` |
| `HwihaRecordKind` | `RecordKind` |
| `HwihaRetinueLedgerTest` | `RetinueLedgerTest` |
| `HwihaRetireAssessment` | `RetireAssessment` |
| `HwihaRetireFailure` | `RetireFailure` |
| `HwihaRetireInput` | `RetireInput` |
| `HwihaRetireInputTest` | `RetireInputTest` |
| `HwihaRetireRequest` | `RetireRequest` |
| `HwihaRetireRules` | `RetireRules` |
| `HwihaRetireRulesTest` | `RetireRulesTest` |
| `HwihaReturnDestination` | `ReturnDestination` |
| `HwihaRewardInput` | `RewardInput` |
| `HwihaStratagemCardType` | `StratagemCardType` |
| `HwihaStratagemHand` | `StratagemHand` |
| `HwihaStratagemHandTest` | `StratagemHandTest` |
| `HwihaTalentDiscovery` | `TalentDiscovery` |
| `HwihaTradeSide` | `TradeSide` |
| `HwihaTrainingStat` | `TrainingStat` |
| `HwihaTransferAssessment` | `TransferAssessment` |
| `HwihaTransferFailure` | `TransferFailure` |
| `HwihaTransferInput` | `TransferInput` |
| `HwihaTransferInputTest` | `TransferInputTest` |
| `HwihaTransferRequest` | `TransferRequest` |
| `HwihaTransferResource` | `TransferResource` |
| `HwihaTransferRules` | `TransferRules` |
| `HwihaTravelAssessment` | `TravelAssessment` |
| `HwihaTravelFailure` | `TravelFailure` |
| `HwihaTravelInput` | `TravelInput` |
| `HwihaTravelInputTest` | `TravelInputTest` |
| `HwihaTravelRequest` | `TravelRequest` |
| `HwihaTravelReturn` | `TravelReturn` |
| `HwihaTravelReturnTest` | `TravelReturnTest` |
| `HwihaTravelRules` | `TravelRules` |
| `HwihaTravelRulesTest` | `TravelRulesTest` |
| `HwihaTravelSnapshot` | `TravelSnapshot` |
| `HwihaTravelState` | `TravelState` |
| `HwihaTravelStateTest` | `TravelStateTest` |
| `HwihaTreasureInventory` | `TreasureInventory` |

## 나머지 로직 파일 개명

동명 데이터 타입이 있는 3곳은 유틸리티 객체 이름을 복수형·역할형으로 구분한다. 저장 식별자·데이터 파일명은 이 단계에서 유지한다.

| 이전 파일 | 새 파일 |
|---|---|
| `logic/src/main/kotlin/opensamguk/logic/content/HwihaCommonStratagemCards.kt` | `logic/src/main/kotlin/opensamguk/logic/content/CommonStratagemCards.kt` |
| `logic/src/main/kotlin/opensamguk/logic/content/HwihaItemCatalogJson.kt` | `logic/src/main/kotlin/opensamguk/logic/content/ItemCatalogJson.kt` |
| `logic/src/main/kotlin/opensamguk/logic/content/HwihaPersonCard.kt` | `logic/src/main/kotlin/opensamguk/logic/content/PersonCard.kt` |
| `logic/src/main/kotlin/opensamguk/logic/content/HwihaTreasureCards.kt` | `logic/src/main/kotlin/opensamguk/logic/content/TreasureCards.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaDeployInput.kt` | `logic/src/main/kotlin/opensamguk/logic/input/DeployInputs.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaDeploymentProjection.kt` | `logic/src/main/kotlin/opensamguk/logic/input/DeploymentProjector.kt` |
| `logic/src/main/kotlin/opensamguk/logic/input/HwihaRetinueLedger.kt` | `logic/src/main/kotlin/opensamguk/logic/input/RetinueLedgers.kt` |
| `logic/src/main/kotlin/opensamguk/logic/world/HwihaBattlefieldGeometry.kt` | `logic/src/main/kotlin/opensamguk/logic/world/BattlefieldGeometry.kt` |
| `logic/src/main/kotlin/opensamguk/logic/world/HwihaBattlefieldLayout.kt` | `logic/src/main/kotlin/opensamguk/logic/world/BattlefieldLayout.kt` |
| `logic/src/test/kotlin/opensamguk/logic/content/HwihaPersonCardTest.kt` | `logic/src/test/kotlin/opensamguk/logic/content/PersonCardTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/content/HwihaTreasureCardsTest.kt` | `logic/src/test/kotlin/opensamguk/logic/content/TreasureCardsTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/world/HwihaBattlefieldGeometryTest.kt` | `logic/src/test/kotlin/opensamguk/logic/world/BattlefieldGeometryTest.kt` |
| `logic/src/test/kotlin/opensamguk/logic/world/HwihaBattlefieldLayoutTest.kt` | `logic/src/test/kotlin/opensamguk/logic/world/BattlefieldLayoutTest.kt` |

| 이전 타입 | 새 타입 |
|---|---|
| `HwihaBattlefieldGeometry` | `BattlefieldGeometry` |
| `HwihaBattlefieldGeometryTest` | `BattlefieldGeometryTest` |
| `HwihaBattlefieldLayout` | `BattlefieldLayout` |
| `HwihaBattlefieldLayoutTest` | `BattlefieldLayoutTest` |
| `HwihaCommonStratagemCards` | `CommonStratagemCards` |
| `HwihaDeployInput` | `DeployInputs` |
| `HwihaDeploymentProjection` | `DeploymentProjector` |
| `HwihaEquipmentDefinition` | `EquipmentDefinition` |
| `HwihaItemCatalog` | `ItemCatalog` |
| `HwihaItemCatalogJson` | `ItemCatalogJson` |
| `HwihaPersonBondState` | `PersonBondState` |
| `HwihaPersonCard` | `PersonCard` |
| `HwihaPersonCardTest` | `PersonCardTest` |
| `HwihaPersonContributionState` | `PersonContributionState` |
| `HwihaRetinueLedger` | `RetinueLedgers` |
| `HwihaTreasureCardsTest` | `TreasureCardsTest` |
| `HwihaTreasureDefinition` | `TreasureDefinition` |
| `HwihaTreasureState` | `TreasureState` |

## 인프라·엔진·API 코드 개명

제품 접두사를 제거한 순수 코드 이름 변경이다. 저장·통신 키와 값은 별도 단계에서 바꾼다. 아래 타입 이름은 해당 파일의 원래 패키지에서 사용한다.

| 이전 파일 | 새 파일 |
|---|---|
| `app/game-api/src/main/kotlin/opensamguk/gameapi/dto/HwihaCampDto.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/dto/CampDto.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/dto/HwihaDeployDtos.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/dto/DeployDtos.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/dto/HwihaDispatchDto.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/dto/DispatchDto.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/dto/HwihaDomesticDto.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/dto/DomesticDto.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/dto/HwihaSiegeDto.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/dto/SiegeDto.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/dto/HwihaVisionDtos.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/dto/VisionDtos.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/precheck/HwihaDeployPrecheckService.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/precheck/DeployPrecheckService.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/precheck/HwihaDispatchPrecheckService.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/precheck/DispatchPrecheckService.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/precheck/HwihaEnlistmentPrecheckService.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/precheck/EnlistmentPrecheckService.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/precheck/HwihaFieldOptionsService.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/precheck/FieldOptionsService.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/precheck/HwihaLegacyCourtOptionsService.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/precheck/LegacyCourtOptionsService.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/precheck/HwihaLegacyDirectOptionsService.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/precheck/LegacyDirectOptionsService.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/precheck/HwihaLegacyStratagemOptionsService.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/precheck/LegacyStratagemOptionsService.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/precheck/HwihaMilitaryOptionsService.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/precheck/MilitaryOptionsService.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/precheck/HwihaPeopleOptionsService.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/precheck/PeopleOptionsService.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/precheck/HwihaPersonalOptionsService.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/precheck/PersonalOptionsService.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/precheck/HwihaPoliticalOptionsService.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/precheck/PoliticalOptionsService.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/precheck/HwihaRetireOptionsService.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/precheck/RetireOptionsService.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/precheck/HwihaTransferOptionsService.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/precheck/TransferOptionsService.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/precheck/HwihaTravelPrecheckService.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/precheck/TravelPrecheckService.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/read/HwihaCampLedgers.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/read/CampLedgers.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/read/HwihaCampReader.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/read/CampReader.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/read/HwihaDomesticReader.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/read/DomesticReader.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/read/HwihaLastTurnsReader.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/read/LastTurnsReader.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/read/HwihaRecordReadRepository.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/read/RecordReadRepository.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/read/HwihaSiegeReadRepository.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/read/SiegeReadRepository.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/read/HwihaSiegeReader.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/read/SiegeReader.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/read/HwihaStratagemHandReader.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/read/StratagemHandReader.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/read/HwihaVisionReader.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/read/VisionReader.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/HwihaCourtAdmission.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/CourtAdmission.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/HwihaDeployAdmission.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/DeployAdmission.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/HwihaDomesticAdmission.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/DomesticAdmission.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/HwihaEnlistmentAdmission.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/EnlistmentAdmission.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/HwihaFieldAdmission.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/FieldAdmission.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/HwihaLegacyDirectAdmission.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/LegacyDirectAdmission.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/HwihaMilitaryAdmission.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/MilitaryAdmission.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/HwihaPeopleAdmission.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/PeopleAdmission.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/HwihaPersonalAdmission.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/PersonalAdmission.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/HwihaPoliticalAdmission.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/PoliticalAdmission.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/HwihaRetireAdmission.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/RetireAdmission.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/HwihaScoutAdmission.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/ScoutAdmission.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/HwihaTransferAdmission.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/TransferAdmission.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/HwihaTravelAdmission.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/TravelAdmission.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/web/HwihaCampController.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/web/CampController.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/web/HwihaCourtController.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/web/CourtController.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/web/HwihaDeployController.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/web/DeployController.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/web/HwihaDispatchReadController.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/web/DispatchReadController.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/web/HwihaDomesticController.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/web/DomesticController.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/web/HwihaEnlistmentOptionsController.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/web/EnlistmentOptionsController.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/web/HwihaFieldOptionsController.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/web/FieldOptionsController.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/web/HwihaLastTurnsController.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/web/LastTurnsController.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/web/HwihaLegacyCourtOptionsController.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/web/LegacyCourtOptionsController.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/web/HwihaLegacyDirectOptionsController.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/web/LegacyDirectOptionsController.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/web/HwihaLegacyStratagemController.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/web/LegacyStratagemController.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/web/HwihaMilitaryOptionsController.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/web/MilitaryOptionsController.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/web/HwihaPeopleOptionsController.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/web/PeopleOptionsController.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/web/HwihaPersonalOptionsController.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/web/PersonalOptionsController.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/web/HwihaPoliticalOptionsController.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/web/PoliticalOptionsController.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/web/HwihaRetireOptionsController.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/web/RetireOptionsController.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/web/HwihaSiegeController.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/web/SiegeController.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/web/HwihaStratagemHandController.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/web/StratagemHandController.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/web/HwihaTransferOptionsController.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/web/TransferOptionsController.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/web/HwihaTravelOptionsController.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/web/TravelOptionsController.kt` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/web/HwihaVisionController.kt` | `app/game-api/src/main/kotlin/opensamguk/gameapi/web/VisionController.kt` |
| `app/game-api/src/test/kotlin/opensamguk/gameapi/precheck/HwihaDeployPrecheckServiceTest.kt` | `app/game-api/src/test/kotlin/opensamguk/gameapi/precheck/DeployPrecheckServiceTest.kt` |
| `app/game-api/src/test/kotlin/opensamguk/gameapi/precheck/HwihaDispatchPrecheckServiceTest.kt` | `app/game-api/src/test/kotlin/opensamguk/gameapi/precheck/DispatchPrecheckServiceTest.kt` |
| `app/game-api/src/test/kotlin/opensamguk/gameapi/precheck/HwihaEnlistmentPrecheckServiceTest.kt` | `app/game-api/src/test/kotlin/opensamguk/gameapi/precheck/EnlistmentPrecheckServiceTest.kt` |
| `app/game-api/src/test/kotlin/opensamguk/gameapi/precheck/HwihaTravelPrecheckServiceTest.kt` | `app/game-api/src/test/kotlin/opensamguk/gameapi/precheck/TravelPrecheckServiceTest.kt` |
| `app/game-api/src/test/kotlin/opensamguk/gameapi/read/HwihaCampReaderTest.kt` | `app/game-api/src/test/kotlin/opensamguk/gameapi/read/CampReaderTest.kt` |
| `app/game-api/src/test/kotlin/opensamguk/gameapi/read/HwihaDomesticViewsTest.kt` | `app/game-api/src/test/kotlin/opensamguk/gameapi/read/DomesticViewsTest.kt` |
| `app/game-api/src/test/kotlin/opensamguk/gameapi/read/HwihaLastTurnsReaderTest.kt` | `app/game-api/src/test/kotlin/opensamguk/gameapi/read/LastTurnsReaderTest.kt` |
| `app/game-api/src/test/kotlin/opensamguk/gameapi/read/HwihaSiegeReaderTest.kt` | `app/game-api/src/test/kotlin/opensamguk/gameapi/read/SiegeReaderTest.kt` |
| `app/game-api/src/test/kotlin/opensamguk/gameapi/read/HwihaVisionReaderTest.kt` | `app/game-api/src/test/kotlin/opensamguk/gameapi/read/VisionReaderTest.kt` |
| `app/game-api/src/test/kotlin/opensamguk/gameapi/reserve/HwihaReservableActionsTest.kt` | `app/game-api/src/test/kotlin/opensamguk/gameapi/reserve/ReservableActionsTest.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaAssignmentMarchExecutor.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/AssignmentMarchExecutor.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaAssignmentMarchTurn.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/AssignmentMarchTurn.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaCapitalAfterCapture.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/CapitalAfterCapture.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaCityMilitaryHandler.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/CityMilitaryHandler.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaCorpsEncounterRecorder.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/CorpsEncounterRecorder.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaCorpsMarchExecutor.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/CorpsMarchExecutor.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaCorpsMarchTurn.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/CorpsMarchTurn.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaCorpsRations.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/CorpsRations.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaCountyMeritWindow.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/CountyMeritWindow.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaCourtHandler.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/CourtHandler.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaDeployHandler.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/DeployHandler.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaDeploymentExecutor.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/DeploymentExecutor.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaDispatchExecutor.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/DispatchExecutor.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaDomesticBoundary.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/DomesticBoundary.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaDomesticContext.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/DomesticContext.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaDomesticHandler.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/DomesticHandler.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaDomesticTurn.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/DomesticTurn.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaEncounterResolver.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/EncounterResolver.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaEnlistmentExecutor.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/EnlistmentExecutor.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaEnlistmentHandler.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/EnlistmentHandler.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaEnlistmentPolicy.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/EnlistmentPolicyReader.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaFieldHandler.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/FieldHandler.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaGovernanceMeritRenownSink.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/GovernanceMeritRenownSink.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaLegacyCourtExecutor.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/LegacyCourtExecutor.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaLegacyDirectHandler.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/LegacyDirectHandler.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaLegacyStratagemExecutor.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/LegacyStratagemExecutor.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaMarchReactionInterpreter.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/MarchReactionInterpreter.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaMarchReactionPolicy.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/MarchReactionPolicy.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaMilitaryPresenceProvider.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/MilitaryPresenceProvider.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaMonthlyAssessment.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/MonthlyAssessment.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaMonthlyCountyIncome.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/MonthlyCountyIncome.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaMonthlySalary.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/MonthlySalary.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaMusterHandler.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/MusterHandler.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaNpcCityMilitarySelector.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/NpcCityMilitarySelector.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaNpcDeploySelector.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/NpcDeploySelector.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaNpcDispatchSelector.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/NpcDispatchSelector.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaNpcEnlistmentSelector.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/NpcEnlistmentSelector.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaNpcFieldSelector.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/NpcFieldSelector.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaNpcMusterSelector.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/NpcMusterSelector.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaNpcPeopleSelector.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/NpcPeopleSelector.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaNpcPersonalSelector.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/NpcPersonalSelector.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaNpcRetireSelector.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/NpcRetireSelector.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaPeopleHandler.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/PeopleHandler.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaPersonDeckProjection.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/PersonDeckProjection.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaPersonalEncounter.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/PersonalEncounter.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaPersonalHandler.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/PersonalHandler.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaPersonalTurn.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/PersonalTurn.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaPhaseBoundary.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/PhaseBoundary.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaPlacementMarch.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/PlacementMarch.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaPoliticalHandler.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/PoliticalHandler.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaReactionInventory.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/ReactionInventory.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaRecords.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/Records.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaRenownEventRecorder.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/RenownEventRecorder.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaRetireHandler.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/RetireHandler.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaRewardExecutor.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/RewardExecutor.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaScoutHandler.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/ScoutHandler.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaSiegeHandler.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/SiegeHandler.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaSiegeService.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/SiegeService.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaStratagemDraw.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/StratagemDraw.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaTransferHandler.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/TransferHandler.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaTravelExecutor.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/TravelExecutor.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaTravelHandler.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/TravelHandler.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaTravelTurn.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/TravelTurn.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaTurnOutcome.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/TurnOutcome.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaUnitResupply.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/UnitResupply.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaWarOutcomeListener.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/WarOutcomeListener.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaWarOutcomeRenownListener.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/WarOutcomeRenownListener.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaWarehouseNetwork.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/WarehouseNetwork.kt` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaWarehouseSettlement.kt` | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/WarehouseSettlement.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/boot/HwihaCourtApiIT.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/boot/CourtApiIT.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/boot/HwihaCourtRecoveryIT.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/boot/CourtRecoveryIT.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/boot/HwihaCreatedPersonPersistenceIT.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/boot/CreatedPersonPersistenceIT.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/boot/HwihaDeploymentPersistenceIT.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/boot/DeploymentPersistenceIT.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/boot/HwihaDispatchPersistenceIT.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/boot/DispatchPersistenceIT.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/boot/HwihaDomesticPersistenceIT.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/boot/DomesticPersistenceIT.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/boot/HwihaEnlistmentApiIT.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/boot/EnlistmentApiIT.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/boot/HwihaEnlistmentFixture.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/boot/EnlistmentFixture.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/boot/HwihaEnlistmentPersistenceIT.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/boot/EnlistmentPersistenceIT.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/boot/HwihaMarchPersistenceIT.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/boot/MarchPersistenceIT.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/boot/HwihaMonthBoundaryLoopIT.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/boot/MonthBoundaryLoopIT.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/boot/HwihaMonthlyIncomePersistenceIT.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/boot/MonthlyIncomePersistenceIT.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/boot/HwihaNpcCourtFlowApiIT.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/boot/NpcCourtFlowApiIT.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/boot/HwihaNpcDispatchPersistenceIT.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/boot/NpcDispatchPersistenceIT.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/boot/HwihaS3PassChainProbeIT.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/boot/S3PassChainProbeIT.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/boot/HwihaScoutPersistenceIT.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/boot/ScoutPersistenceIT.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/boot/HwihaStratagemHandApiIT.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/boot/StratagemHandApiIT.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/boot/HwihaWarehousePersistenceIT.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/boot/WarehousePersistenceIT.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/HwihaCampaignWorldFixture.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/CampaignWorldFixture.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/HwihaCapitalAfterCaptureTest.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/CapitalAfterCaptureTest.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/HwihaCityMilitaryHandlerTest.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/CityMilitaryHandlerTest.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/HwihaDispatchExecutorTest.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/DispatchExecutorTest.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/HwihaDomesticEngineTest.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/DomesticEngineTest.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/HwihaEconomyBoundaryTest.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/EconomyBoundaryTest.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/HwihaEncounterResolverTest.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/EncounterResolverTest.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/HwihaEnlistmentExecutorTest.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/EnlistmentExecutorTest.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/HwihaEnlistmentHandlerTest.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/EnlistmentHandlerTest.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/HwihaEnlistmentPolicyTest.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/EnlistmentPolicyReaderTest.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/HwihaFieldHandlerTest.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/FieldHandlerTest.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/HwihaGovernanceMeritWiringTest.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/GovernanceMeritWiringTest.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/HwihaLegacyCourtHandlerTest.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/LegacyCourtHandlerTest.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/HwihaLegacyDirectHandlerTest.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/LegacyDirectHandlerTest.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/HwihaLegacyStratagemHandlerTest.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/LegacyStratagemHandlerTest.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/HwihaMarchReactionInterpreterTest.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/MarchReactionInterpreterTest.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/HwihaMarchReactionPolicyTest.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/MarchReactionPolicyTest.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/HwihaMonthlyCountyIncomeTest.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/MonthlyCountyIncomeTest.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/HwihaMusterHandlerTest.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/MusterHandlerTest.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/HwihaNpcDeploySelectorTest.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/NpcDeploySelectorTest.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/HwihaNpcDispatchSelectorTest.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/NpcDispatchSelectorTest.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/HwihaNpcEnlistmentSelectorTest.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/NpcEnlistmentSelectorTest.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/HwihaNpcWarBranchesTest.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/NpcWarBranchesTest.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/HwihaPeopleHandlerTest.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/PeopleHandlerTest.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/HwihaPersonalHandlerTest.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/PersonalHandlerTest.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/HwihaPoliticalHandlerTest.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/PoliticalHandlerTest.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/HwihaRenownRecordsTest.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/RenownRecordsTest.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/HwihaReservedTurnRejectionTest.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/ReservedTurnRejectionTest.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/HwihaRetireHandlerTest.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/RetireHandlerTest.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/HwihaScoutHandlerTest.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/ScoutHandlerTest.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/HwihaSiegeServiceTest.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/SiegeServiceTest.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/HwihaTransferHandlerTest.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/TransferHandlerTest.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/HwihaTravelExecutorTest.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/TravelExecutorTest.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/HwihaTravelHandlerTest.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/TravelHandlerTest.kt` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/intake/HwihaDeploymentEditGuardTest.kt` | `app/game-engine/src/test/kotlin/opensamguk/engine/intake/DeploymentEditGuardTest.kt` |
| `infra/src/main/kotlin/opensamguk/infra/persistence/HwihaSiegeRow.kt` | `infra/src/main/kotlin/opensamguk/infra/persistence/SiegeRow.kt` |
| `infra/src/main/kotlin/opensamguk/infra/seed/HwihaCountyGeographyJson.kt` | `infra/src/main/kotlin/opensamguk/infra/seed/CountyGeographyJson.kt` |
| `infra/src/main/kotlin/opensamguk/infra/seed/HwihaCountyProductionJson.kt` | `infra/src/main/kotlin/opensamguk/infra/seed/CountyProductionJson.kt` |
| `infra/src/main/kotlin/opensamguk/infra/seed/HwihaScenarioPersonPolicies.kt` | `infra/src/main/kotlin/opensamguk/infra/seed/ScenarioPersonPolicies.kt` |
| `infra/src/main/kotlin/opensamguk/infra/seed/HwihaScenarioUnits.kt` | `infra/src/main/kotlin/opensamguk/infra/seed/ScenarioUnits.kt` |
| `infra/src/main/kotlin/opensamguk/infra/seed/HwihaScenarioWarehouseSeeds.kt` | `infra/src/main/kotlin/opensamguk/infra/seed/ScenarioWarehouseSeeds.kt` |
| `infra/src/main/kotlin/opensamguk/infra/seed/HwihaUnitProfilesJson.kt` | `infra/src/main/kotlin/opensamguk/infra/seed/UnitProfilesJson.kt` |
| `infra/src/test/kotlin/opensamguk/infra/seed/HwihaBattlefieldLayoutArtifactTest.kt` | `infra/src/test/kotlin/opensamguk/infra/seed/BattlefieldLayoutArtifactTest.kt` |
| `infra/src/test/kotlin/opensamguk/infra/seed/HwihaCountyGeographyJsonTest.kt` | `infra/src/test/kotlin/opensamguk/infra/seed/CountyGeographyJsonTest.kt` |
| `infra/src/test/kotlin/opensamguk/infra/seed/HwihaCountyProductionJsonTest.kt` | `infra/src/test/kotlin/opensamguk/infra/seed/CountyProductionJsonTest.kt` |
| `infra/src/test/kotlin/opensamguk/infra/seed/HwihaScenarioPersonPoliciesTest.kt` | `infra/src/test/kotlin/opensamguk/infra/seed/ScenarioPersonPoliciesTest.kt` |
| `infra/src/test/kotlin/opensamguk/infra/seed/HwihaScenarioWarehouseSeedsTest.kt` | `infra/src/test/kotlin/opensamguk/infra/seed/ScenarioWarehouseSeedsTest.kt` |
| `infra/src/test/kotlin/opensamguk/infra/seed/HwihaUnitProfilesJsonTest.kt` | `infra/src/test/kotlin/opensamguk/infra/seed/UnitProfilesJsonTest.kt` |
| `infra/src/test/kotlin/opensamguk/infra/seed/HwihaWarehouseSeedIT.kt` | `infra/src/test/kotlin/opensamguk/infra/seed/WarehouseSeedIT.kt` |
| `infra/src/test/kotlin/opensamguk/infra/seed/HwihaYuzhouSliceScenarioTest.kt` | `infra/src/test/kotlin/opensamguk/infra/seed/YuzhouSliceScenarioTest.kt` |

| 이전 타입 | 새 타입 |
|---|---|
| `HwihaActivePlacementDto` | `ActivePlacementDto` |
| `HwihaActiveWorkDto` | `ActiveWorkDto` |
| `HwihaAdmissionDenied` | `AdmissionDenied` |
| `HwihaAptitudesDto` | `AptitudesDto` |
| `HwihaAssignmentMarchExecutor` | `AssignmentMarchExecutor` |
| `HwihaAssignmentMarchTurn` | `AssignmentMarchTurn` |
| `HwihaBattlefieldLayoutArtifactTest` | `BattlefieldLayoutArtifactTest` |
| `HwihaBondDto` | `BondDto` |
| `HwihaCampController` | `CampController` |
| `HwihaCampForbidden` | `CampForbidden` |
| `HwihaCampLedgers` | `CampLedgers` |
| `HwihaCampReader` | `CampReader` |
| `HwihaCampReaderTest` | `CampReaderTest` |
| `HwihaCampaignWorldFixture` | `CampaignWorldFixture` |
| `HwihaCapitalAfterCapture` | `CapitalAfterCapture` |
| `HwihaCapitalAfterCaptureTest` | `CapitalAfterCaptureTest` |
| `HwihaCityGeography` | `CityGeography` |
| `HwihaCityMilitaryHandler` | `CityMilitaryHandler` |
| `HwihaCityMilitaryHandlerTest` | `CityMilitaryHandlerTest` |
| `HwihaCodeLabel` | `CodeLabel` |
| `HwihaCommanderyPolicyDto` | `CommanderyPolicyDto` |
| `HwihaCompletedWorkDto` | `CompletedWorkDto` |
| `HwihaCorpsDto` | `CorpsDto` |
| `HwihaCorpsEncounterRecorder` | `CorpsEncounterRecorder` |
| `HwihaCorpsMarchExecutor` | `CorpsMarchExecutor` |
| `HwihaCorpsMarchTurn` | `CorpsMarchTurn` |
| `HwihaCorpsPolicyDto` | `CorpsPolicyDto` |
| `HwihaCorpsRations` | `CorpsRations` |
| `HwihaCorpsResponse` | `CorpsResponse` |
| `HwihaCountyGeographyJson` | `CountyGeographyJson` |
| `HwihaCountyGeographyJsonTest` | `CountyGeographyJsonTest` |
| `HwihaCountyMeritWindow` | `CountyMeritWindow` |
| `HwihaCountyPolicyDto` | `CountyPolicyDto` |
| `HwihaCountyProductionJson` | `CountyProductionJson` |
| `HwihaCountyProductionJsonTest` | `CountyProductionJsonTest` |
| `HwihaCountyResponse` | `CountyResponse` |
| `HwihaCountyWorksDto` | `CountyWorksDto` |
| `HwihaCourtAdmission` | `CourtAdmission` |
| `HwihaCourtApiIT` | `CourtApiIT` |
| `HwihaCourtController` | `CourtController` |
| `HwihaCourtExecution` | `CourtExecution` |
| `HwihaCourtHandler` | `CourtHandler` |
| `HwihaCourtRecoveryIT` | `CourtRecoveryIT` |
| `HwihaCreatedPersonPersistenceIT` | `CreatedPersonPersistenceIT` |
| `HwihaDeployAdmission` | `DeployAdmission` |
| `HwihaDeployBugok` | `DeployBugok` |
| `HwihaDeployController` | `DeployController` |
| `HwihaDeployDestination` | `DeployDestination` |
| `HwihaDeployHandler` | `DeployHandler` |
| `HwihaDeployOptions` | `DeployOptions` |
| `HwihaDeployOrder` | `DeployOrder` |
| `HwihaDeployPrecheckService` | `DeployPrecheckService` |
| `HwihaDeployPrecheckServiceTest` | `DeployPrecheckServiceTest` |
| `HwihaDeploymentEditGuardTest` | `DeploymentEditGuardTest` |
| `HwihaDeploymentExecutor` | `DeploymentExecutor` |
| `HwihaDeploymentPersistenceIT` | `DeploymentPersistenceIT` |
| `HwihaDispatchExecutor` | `DispatchExecutor` |
| `HwihaDispatchExecutorTest` | `DispatchExecutorTest` |
| `HwihaDispatchPersistenceIT` | `DispatchPersistenceIT` |
| `HwihaDispatchPrecheckService` | `DispatchPrecheckService` |
| `HwihaDispatchPrecheckServiceTest` | `DispatchPrecheckServiceTest` |
| `HwihaDispatchReadController` | `DispatchReadController` |
| `HwihaDomesticAdmission` | `DomesticAdmission` |
| `HwihaDomesticBoundary` | `DomesticBoundary` |
| `HwihaDomesticContext` | `DomesticContext` |
| `HwihaDomesticController` | `DomesticController` |
| `HwihaDomesticCountyEffects` | `DomesticCountyEffects` |
| `HwihaDomesticEngineTest` | `DomesticEngineTest` |
| `HwihaDomesticForbidden` | `DomesticForbidden` |
| `HwihaDomesticHandler` | `DomesticHandler` |
| `HwihaDomesticPersistenceIT` | `DomesticPersistenceIT` |
| `HwihaDomesticReader` | `DomesticReader` |
| `HwihaDomesticSnapshot` | `DomesticSnapshot` |
| `HwihaDomesticTurn` | `DomesticTurn` |
| `HwihaDomesticViews` | `DomesticViews` |
| `HwihaDomesticViewsTest` | `DomesticViewsTest` |
| `HwihaEconomyBoundaryTest` | `EconomyBoundaryTest` |
| `HwihaEffectivePolicyDto` | `EffectivePolicyDto` |
| `HwihaEncounterResolver` | `EncounterResolver` |
| `HwihaEncounterResolverTest` | `EncounterResolverTest` |
| `HwihaEnlistmentAdmission` | `EnlistmentAdmission` |
| `HwihaEnlistmentApiIT` | `EnlistmentApiIT` |
| `HwihaEnlistmentExecutor` | `EnlistmentExecutor` |
| `HwihaEnlistmentExecutorTest` | `EnlistmentExecutorTest` |
| `HwihaEnlistmentFixture` | `EnlistmentFixture` |
| `HwihaEnlistmentHandler` | `EnlistmentHandler` |
| `HwihaEnlistmentHandlerTest` | `EnlistmentHandlerTest` |
| `HwihaEnlistmentOptionsController` | `EnlistmentOptionsController` |
| `HwihaEnlistmentPersistenceIT` | `EnlistmentPersistenceIT` |
| `HwihaEnlistmentPolicy` | `EnlistmentPolicyReader` |
| `HwihaEnlistmentPolicyResult` | `EnlistmentPolicyResult` |
| `HwihaEnlistmentPolicyTest` | `EnlistmentPolicyReaderTest` |
| `HwihaEnlistmentPrecheckService` | `EnlistmentPrecheckService` |
| `HwihaEnlistmentPrecheckServiceTest` | `EnlistmentPrecheckServiceTest` |
| `HwihaFieldAdmission` | `FieldAdmission` |
| `HwihaFieldHandler` | `FieldHandler` |
| `HwihaFieldHandlerTest` | `FieldHandlerTest` |
| `HwihaFieldOptions` | `FieldOptions` |
| `HwihaFieldOptionsController` | `FieldOptionsController` |
| `HwihaFieldOptionsService` | `FieldOptionsService` |
| `HwihaFiveStatsDto` | `FiveStatsDto` |
| `HwihaGovernanceMeritRenownSink` | `GovernanceMeritRenownSink` |
| `HwihaGovernanceMeritWiringTest` | `GovernanceMeritWiringTest` |
| `HwihaLastTurnDto` | `LastTurnDto` |
| `HwihaLastTurnsController` | `LastTurnsController` |
| `HwihaLastTurnsReader` | `LastTurnsReader` |
| `HwihaLastTurnsReaderTest` | `LastTurnsReaderTest` |
| `HwihaLastTurnsResponse` | `LastTurnsResponse` |
| `HwihaLegacyCourtChoice` | `LegacyCourtChoice` |
| `HwihaLegacyCourtExecutor` | `LegacyCourtExecutor` |
| `HwihaLegacyCourtHandlerTest` | `LegacyCourtHandlerTest` |
| `HwihaLegacyCourtOptions` | `LegacyCourtOptions` |
| `HwihaLegacyCourtOptionsController` | `LegacyCourtOptionsController` |
| `HwihaLegacyCourtOptionsService` | `LegacyCourtOptionsService` |
| `HwihaLegacyDirectAdmission` | `LegacyDirectAdmission` |
| `HwihaLegacyDirectChoice` | `LegacyDirectChoice` |
| `HwihaLegacyDirectHandler` | `LegacyDirectHandler` |
| `HwihaLegacyDirectHandlerTest` | `LegacyDirectHandlerTest` |
| `HwihaLegacyDirectOptions` | `LegacyDirectOptions` |
| `HwihaLegacyDirectOptionsController` | `LegacyDirectOptionsController` |
| `HwihaLegacyDirectOptionsService` | `LegacyDirectOptionsService` |
| `HwihaLegacyStratagemChoice` | `LegacyStratagemChoice` |
| `HwihaLegacyStratagemController` | `LegacyStratagemController` |
| `HwihaLegacyStratagemExecutor` | `LegacyStratagemExecutor` |
| `HwihaLegacyStratagemHandlerTest` | `LegacyStratagemHandlerTest` |
| `HwihaLegacyStratagemOptions` | `LegacyStratagemOptions` |
| `HwihaLegacyStratagemOptionsService` | `LegacyStratagemOptionsService` |
| `HwihaMarchPersistenceIT` | `MarchPersistenceIT` |
| `HwihaMarchReactionInterpreter` | `MarchReactionInterpreter` |
| `HwihaMarchReactionInterpreterTest` | `MarchReactionInterpreterTest` |
| `HwihaMarchReactionPolicy` | `MarchReactionPolicy` |
| `HwihaMarchReactionPolicyTest` | `MarchReactionPolicyTest` |
| `HwihaMilitaryAdmission` | `MilitaryAdmission` |
| `HwihaMilitaryOptions` | `MilitaryOptions` |
| `HwihaMilitaryOptionsController` | `MilitaryOptionsController` |
| `HwihaMilitaryOptionsService` | `MilitaryOptionsService` |
| `HwihaMilitaryPresenceProvider` | `MilitaryPresenceProvider` |
| `HwihaMonthBoundaryLoopIT` | `MonthBoundaryLoopIT` |
| `HwihaMonthlyAssessment` | `MonthlyAssessment` |
| `HwihaMonthlyCountyIncome` | `MonthlyCountyIncome` |
| `HwihaMonthlyCountyIncomeTest` | `MonthlyCountyIncomeTest` |
| `HwihaMonthlyIncomePersistenceIT` | `MonthlyIncomePersistenceIT` |
| `HwihaMonthlySalary` | `MonthlySalary` |
| `HwihaMusterHandler` | `MusterHandler` |
| `HwihaMusterHandlerTest` | `MusterHandlerTest` |
| `HwihaNationSummaryEntryDto` | `NationSummaryEntryDto` |
| `HwihaNpcCityMilitarySelector` | `NpcCityMilitarySelector` |
| `HwihaNpcCourtFlowApiIT` | `NpcCourtFlowApiIT` |
| `HwihaNpcDeploySelector` | `NpcDeploySelector` |
| `HwihaNpcDeploySelectorTest` | `NpcDeploySelectorTest` |
| `HwihaNpcDispatchPersistenceIT` | `NpcDispatchPersistenceIT` |
| `HwihaNpcDispatchSelector` | `NpcDispatchSelector` |
| `HwihaNpcDispatchSelectorTest` | `NpcDispatchSelectorTest` |
| `HwihaNpcEnlistmentSelector` | `NpcEnlistmentSelector` |
| `HwihaNpcEnlistmentSelectorTest` | `NpcEnlistmentSelectorTest` |
| `HwihaNpcFieldSelector` | `NpcFieldSelector` |
| `HwihaNpcMusterSelector` | `NpcMusterSelector` |
| `HwihaNpcPeopleSelector` | `NpcPeopleSelector` |
| `HwihaNpcPersonalSelector` | `NpcPersonalSelector` |
| `HwihaNpcRetireSelector` | `NpcRetireSelector` |
| `HwihaNpcWarBranchesTest` | `NpcWarBranchesTest` |
| `HwihaPeopleAdmission` | `PeopleAdmission` |
| `HwihaPeopleHandler` | `PeopleHandler` |
| `HwihaPeopleHandlerTest` | `PeopleHandlerTest` |
| `HwihaPeopleOptions` | `PeopleOptions` |
| `HwihaPeopleOptionsController` | `PeopleOptionsController` |
| `HwihaPeopleOptionsService` | `PeopleOptionsService` |
| `HwihaPeopleTargetOption` | `PeopleTargetOption` |
| `HwihaPersonCardDto` | `PersonCardDto` |
| `HwihaPersonDeckProjection` | `PersonDeckProjection` |
| `HwihaPersonalAdmission` | `PersonalAdmission` |
| `HwihaPersonalEncounter` | `PersonalEncounter` |
| `HwihaPersonalHandler` | `PersonalHandler` |
| `HwihaPersonalHandlerTest` | `PersonalHandlerTest` |
| `HwihaPersonalOptions` | `PersonalOptions` |
| `HwihaPersonalOptionsController` | `PersonalOptionsController` |
| `HwihaPersonalOptionsService` | `PersonalOptionsService` |
| `HwihaPersonalStatOption` | `PersonalStatOption` |
| `HwihaPersonalTurn` | `PersonalTurn` |
| `HwihaPhaseBoundary` | `PhaseBoundary` |
| `HwihaPlacementCardDto` | `PlacementCardDto` |
| `HwihaPlacementMarchTurn` | `PlacementMarchTurn` |
| `HwihaPlacementOrderDto` | `PlacementOrderDto` |
| `HwihaPlacementTargetDto` | `PlacementTargetDto` |
| `HwihaPoliciesResponse` | `PoliciesResponse` |
| `HwihaPolicyApplicationDto` | `PolicyApplicationDto` |
| `HwihaPolicyOrderDto` | `PolicyOrderDto` |
| `HwihaPolicySettingDto` | `PolicySettingDto` |
| `HwihaPoliticalAdmission` | `PoliticalAdmission` |
| `HwihaPoliticalConsentOption` | `PoliticalConsentOption` |
| `HwihaPoliticalHandler` | `PoliticalHandler` |
| `HwihaPoliticalHandlerTest` | `PoliticalHandlerTest` |
| `HwihaPoliticalOption` | `PoliticalOption` |
| `HwihaPoliticalOptionsController` | `PoliticalOptionsController` |
| `HwihaPoliticalOptionsService` | `PoliticalOptionsService` |
| `HwihaPoliticalTargetOption` | `PoliticalTargetOption` |
| `HwihaPostOptionDto` | `PostOptionDto` |
| `HwihaPostTargetDto` | `PostTargetDto` |
| `HwihaPostsResponse` | `PostsResponse` |
| `HwihaQueuedLegacyCourt` | `QueuedLegacyCourt` |
| `HwihaQueuedLegacyStratagem` | `QueuedLegacyStratagem` |
| `HwihaReactionInventory` | `ReactionInventory` |
| `HwihaReasonDto` | `ReasonDto` |
| `HwihaRecordEntryDto` | `RecordEntryDto` |
| `HwihaRecordReadRepository` | `RecordReadRepository` |
| `HwihaRecordRow` | `RecordRow` |
| `HwihaRecords` | `Records` |
| `HwihaRenownEventRecorder` | `RenownEventRecorder` |
| `HwihaRenownPendingEventDto` | `RenownPendingEventDto` |
| `HwihaRenownReasonDto` | `RenownReasonDto` |
| `HwihaRenownRecordsTest` | `RenownRecordsTest` |
| `HwihaReservableActionsTest` | `ReservableActionsTest` |
| `HwihaReservedTurnRejectionTest` | `ReservedTurnRejectionTest` |
| `HwihaResourceCostDto` | `ResourceCostDto` |
| `HwihaRetinueResponse` | `CampRetinueResponse` |
| `HwihaRetireAdmission` | `RetireAdmission` |
| `HwihaRetireHandler` | `RetireHandler` |
| `HwihaRetireHandlerTest` | `RetireHandlerTest` |
| `HwihaRetireOptions` | `RetireOptions` |
| `HwihaRetireOptionsController` | `RetireOptionsController` |
| `HwihaRetireOptionsService` | `RetireOptionsService` |
| `HwihaRetireSuccessorOption` | `RetireSuccessorOption` |
| `HwihaRewardExecutor` | `RewardExecutor` |
| `HwihaS3PassChainProbeIT` | `S3PassChainProbeIT` |
| `HwihaScenarioPersonPolicies` | `ScenarioPersonPolicies` |
| `HwihaScenarioPersonPoliciesTest` | `ScenarioPersonPoliciesTest` |
| `HwihaScenarioUnit` | `ScenarioUnit` |
| `HwihaScenarioUnits` | `ScenarioUnits` |
| `HwihaScenarioWarehouseSeeds` | `ScenarioWarehouseSeeds` |
| `HwihaScenarioWarehouseSeedsTest` | `ScenarioWarehouseSeedsTest` |
| `HwihaScoutAdmission` | `ScoutAdmission` |
| `HwihaScoutHandler` | `ScoutHandler` |
| `HwihaScoutHandlerTest` | `ScoutHandlerTest` |
| `HwihaScoutOptionDto` | `ScoutOptionDto` |
| `HwihaScoutOptionsResponse` | `ScoutOptionsResponse` |
| `HwihaScoutOriginDto` | `ScoutOriginDto` |
| `HwihaScoutPersistenceIT` | `ScoutPersistenceIT` |
| `HwihaSeatDto` | `SeatDto` |
| `HwihaSiegeController` | `SiegeController` |
| `HwihaSiegeDto` | `SiegeDto` |
| `HwihaSiegeHandler` | `SiegeHandler` |
| `HwihaSiegePartyDto` | `SiegePartyDto` |
| `HwihaSiegePhaseDto` | `SiegePhaseDto` |
| `HwihaSiegeReadRepository` | `SiegeReadRepository` |
| `HwihaSiegeReadRow` | `SiegeReadRow` |
| `HwihaSiegeReader` | `SiegeReader` |
| `HwihaSiegeReaderTest` | `SiegeReaderTest` |
| `HwihaSiegeRow` | `SiegeRow` |
| `HwihaSiegeService` | `SiegeService` |
| `HwihaSiegeServiceTest` | `SiegeServiceTest` |
| `HwihaSiegesResponse` | `SiegesResponse` |
| `HwihaSpecialtyDto` | `SpecialtyDto` |
| `HwihaStampDto` | `StampDto` |
| `HwihaStartableWorkDto` | `StartableWorkDto` |
| `HwihaStockDto` | `StockDto` |
| `HwihaStratagemDraw` | `StratagemDraw` |
| `HwihaStratagemHandApiIT` | `StratagemHandApiIT` |
| `HwihaStratagemHandController` | `StratagemHandController` |
| `HwihaStratagemHandReader` | `StratagemHandReader` |
| `HwihaSyntheticScenario` | `SyntheticScenario` |
| `HwihaTransferAdmission` | `TransferAdmission` |
| `HwihaTransferHandler` | `TransferHandler` |
| `HwihaTransferHandlerTest` | `TransferHandlerTest` |
| `HwihaTransferOptions` | `TransferOptions` |
| `HwihaTransferOptionsController` | `TransferOptionsController` |
| `HwihaTransferOptionsService` | `TransferOptionsService` |
| `HwihaTransferResourceOption` | `TransferResourceOption` |
| `HwihaTransferTargetOption` | `TransferTargetOption` |
| `HwihaTravelAdmission` | `TravelAdmission` |
| `HwihaTravelDestinationOption` | `TravelDestinationOption` |
| `HwihaTravelExecution` | `TravelExecution` |
| `HwihaTravelExecutor` | `TravelExecutor` |
| `HwihaTravelExecutorTest` | `TravelExecutorTest` |
| `HwihaTravelHandler` | `TravelHandler` |
| `HwihaTravelHandlerTest` | `TravelHandlerTest` |
| `HwihaTravelOptions` | `TravelOptions` |
| `HwihaTravelOptionsController` | `TravelOptionsController` |
| `HwihaTravelPrecheckService` | `TravelPrecheckService` |
| `HwihaTravelPrecheckServiceTest` | `TravelPrecheckServiceTest` |
| `HwihaTravelTurn` | `TravelTurn` |
| `HwihaTroopBandDto` | `TroopBandDto` |
| `HwihaTurnOutcome` | `TurnOutcome` |
| `HwihaTurnStamp` | `TurnStamp` |
| `HwihaUnitProfilesJson` | `UnitProfilesJson` |
| `HwihaUnitProfilesJsonTest` | `UnitProfilesJsonTest` |
| `HwihaUnitResupply` | `UnitResupply` |
| `HwihaVisibilityCommanderyDto` | `VisibilityCommanderyDto` |
| `HwihaVisibilityResponse` | `VisibilityResponse` |
| `HwihaVisionContext` | `VisionContext` |
| `HwihaVisionController` | `VisionController` |
| `HwihaVisionForbidden` | `VisionForbidden` |
| `HwihaVisionReader` | `VisionReader` |
| `HwihaVisionReaderTest` | `VisionReaderTest` |
| `HwihaVisionSourceDto` | `VisionSourceDto` |
| `HwihaWarOutcomeListener` | `WarOutcomeListener` |
| `HwihaWarOutcomeRenownListener` | `WarOutcomeRenownListener` |
| `HwihaWarehouseDto` | `WarehouseDto` |
| `HwihaWarehouseNetwork` | `WarehouseNetwork` |
| `HwihaWarehousePersistenceIT` | `WarehousePersistenceIT` |
| `HwihaWarehouseSeed` | `WarehouseSeed` |
| `HwihaWarehouseSeedIT` | `WarehouseSeedIT` |
| `HwihaWarehouseSettlement` | `WarehouseSettlement` |
| `HwihaWarehousesResponse` | `WarehousesResponse` |
| `HwihaWorksResponse` | `WorksResponse` |
| `HwihaYuedanResponse` | `YuedanResponse` |
| `HwihaYuedanRow` | `YuedanRow` |
| `HwihaYuedanSelf` | `YuedanSelf` |
| `HwihaYuzhouSliceScenarioTest` | `YuzhouSliceScenarioTest` |

## 웹 코드 이름 개명

공용 지도 UI의 `HanMapCanvas`·`HanTiles`도 실제 사용 범위에 맞춰 `WorldMapCanvas`·`WorldTiles`로 바꾼다. 저장 세계가 핀으로 가리키는 `han-world-v3`는 데이터 계약 예외로 유지한다. 웹 경로와 API 경로는 저장·통신 단계에서 바꾼다.

| 이전 파일 | 새 파일 |
|---|---|
| `web/game/__tests__/HwihaCourtForm.test.tsx` | `web/game/__tests__/CourtForm.test.tsx` |
| `web/game/__tests__/HwihaDeployForm.test.tsx` | `web/game/__tests__/DeployForm.test.tsx` |
| `web/game/__tests__/HwihaLegacyDirectForm.test.tsx` | `web/game/__tests__/DirectActionForm.test.tsx` |
| `web/game/__tests__/HwihaEnlistmentForm.test.tsx` | `web/game/__tests__/EnlistmentForm.test.tsx` |
| `web/game/__tests__/HwihaFieldForm.test.tsx` | `web/game/__tests__/FieldForm.test.tsx` |
| `web/game/__tests__/HwihaMilitaryForm.test.tsx` | `web/game/__tests__/MilitaryForm.test.tsx` |
| `web/game/__tests__/HwihaPeopleForm.test.tsx` | `web/game/__tests__/PeopleForm.test.tsx` |
| `web/game/__tests__/HwihaPersonalForm.test.tsx` | `web/game/__tests__/PersonalForm.test.tsx` |
| `web/game/__tests__/HwihaPoliticalForm.test.tsx` | `web/game/__tests__/PoliticalForm.test.tsx` |
| `web/game/__tests__/HwihaTransferForm.test.tsx` | `web/game/__tests__/TransferForm.test.tsx` |
| `web/game/__tests__/HwihaTravelForm.test.tsx` | `web/game/__tests__/TravelForm.test.tsx` |
| `web/game/__tests__/HanMapCanvas.interaction.test.tsx` | `web/game/__tests__/WorldMapCanvas.interaction.test.tsx` |
| `web/game/__tests__/HanMapCanvas.test.ts` | `web/game/__tests__/WorldMapCanvas.test.ts` |
| `web/game/components/HwihaShell.module.css` | `web/game/components/GameShell.module.css` |
| `web/game/components/HwihaShell.tsx` | `web/game/components/GameShell.tsx` |
| `web/game/components/hwiha/CommanderyNavigator.tsx` | `web/game/components/campaign/CommanderyNavigator.tsx` |
| `web/game/components/hwiha/CountyPanel.tsx` | `web/game/components/campaign/CountyPanel.tsx` |
| `web/game/components/hwiha/DomesticPanels.tsx` | `web/game/components/campaign/DomesticPanels.tsx` |
| `web/game/components/hwiha/HwihaEmbed.module.css` | `web/game/components/campaign/GameEmbed.module.css` |
| `web/game/components/hwiha/GameEntry.tsx` | `web/game/components/campaign/GameEntry.tsx` |
| `web/game/components/hwiha/HwihaStates.tsx` | `web/game/components/campaign/GameStates.tsx` |
| `web/game/components/hwiha/GeneralRoster.module.css` | `web/game/components/campaign/GeneralRoster.module.css` |
| `web/game/components/hwiha/GeneralRoster.tsx` | `web/game/components/campaign/GeneralRoster.tsx` |
| `web/game/components/hwiha/LastTurnPanel.tsx` | `web/game/components/campaign/LastTurnPanel.tsx` |
| `web/game/components/hwiha/StandingBar.tsx` | `web/game/components/campaign/StandingBar.tsx` |
| `web/game/components/hwiha/TurnList.tsx` | `web/game/components/campaign/TurnList.tsx` |
| `web/game/components/hwiha/WarRoomMap.tsx` | `web/game/components/campaign/WarRoomMap.tsx` |
| `web/game/components/command/HwihaLegacyCourtForm.tsx` | `web/game/components/command/CourtActionForm.tsx` |
| `web/game/components/command/HwihaCourtForm.module.css` | `web/game/components/command/CourtForm.module.css` |
| `web/game/components/command/HwihaCourtForm.preview.css` | `web/game/components/command/CourtForm.preview.css` |
| `web/game/components/command/HwihaCourtForm.preview.tsx` | `web/game/components/command/CourtForm.preview.tsx` |
| `web/game/components/command/HwihaCourtForm.tsx` | `web/game/components/command/CourtForm.tsx` |
| `web/game/components/command/HwihaDeployForm.tsx` | `web/game/components/command/DeployForm.tsx` |
| `web/game/components/command/HwihaLegacyDirectForm.tsx` | `web/game/components/command/DirectActionForm.tsx` |
| `web/game/components/command/HwihaEnlistmentForm.tsx` | `web/game/components/command/EnlistmentForm.tsx` |
| `web/game/components/command/HwihaFieldForm.tsx` | `web/game/components/command/FieldForm.tsx` |
| `web/game/components/command/HwihaMilitaryForm.tsx` | `web/game/components/command/MilitaryForm.tsx` |
| `web/game/components/command/HwihaPeopleForm.tsx` | `web/game/components/command/PeopleForm.tsx` |
| `web/game/components/command/HwihaPersonalForm.tsx` | `web/game/components/command/PersonalForm.tsx` |
| `web/game/components/command/HwihaPoliticalForm.tsx` | `web/game/components/command/PoliticalForm.tsx` |
| `web/game/components/command/HwihaLegacyStratagemForm.tsx` | `web/game/components/command/StratagemActionForm.tsx` |
| `web/game/components/command/HwihaTransferForm.tsx` | `web/game/components/command/TransferForm.tsx` |
| `web/game/components/command/HwihaTravelForm.tsx` | `web/game/components/command/TravelForm.tsx` |
| `web/shared/src/HanMapCanvas.tsx` | `web/shared/src/WorldMapCanvas.tsx` |

| 이전 식별자 | 새 식별자 |
|---|---|
| `HanMapCanvas` | `WorldMapCanvas` |
| `HanTiles` | `WorldTiles` |
| `HwihaAction` | `Action` |
| `HwihaAptitudes` | `Aptitudes` |
| `HwihaBlocked` | `Blocked` |
| `HwihaBond` | `Bond` |
| `HwihaBuilt` | `Built` |
| `HwihaCities` | `Cities` |
| `HwihaCodeLabel` | `CodeLabel` |
| `HwihaCommanderyCell` | `CommanderyCell` |
| `HwihaCorps` | `Corps` |
| `HwihaCorpsList` | `CorpsList` |
| `HwihaCounty` | `County` |
| `HwihaCountyPolicy` | `CountyPolicy` |
| `HwihaCountyWorks` | `CountyWorks` |
| `HwihaCourtForm` | `CourtForm` |
| `HwihaDate` | `Date` |
| `HwihaDeployForm` | `DeployForm` |
| `HwihaDeployOptions` | `DeployOptions` |
| `HwihaDirection` | `Direction` |
| `HwihaEmbed` | `GameEmbed` |
| `HwihaEmpty` | `Empty` |
| `HwihaEnlistmentForm` | `EnlistmentForm` |
| `HwihaFieldActionId` | `FieldActionId` |
| `HwihaFieldForm` | `FieldForm` |
| `HwihaFieldOptions` | `FieldOptions` |
| `HwihaFiveStats` | `FiveStats` |
| `HwihaInputTab` | `InputTab` |
| `HwihaLastTurn` | `LastTurn` |
| `HwihaLastTurnEntry` | `LastTurnEntry` |
| `HwihaLastTurns` | `LastTurns` |
| `HwihaLayout` | `GameLayout` |
| `HwihaLegacyCourtChoice` | `CourtActionChoice` |
| `HwihaLegacyCourtForm` | `CourtActionForm` |
| `HwihaLegacyCourtId` | `CourtActionId` |
| `HwihaLegacyCourtOptions` | `CourtActionOptions` |
| `HwihaLegacyDirectActionId` | `DirectActionId` |
| `HwihaLegacyDirectChoice` | `DirectActionChoice` |
| `HwihaLegacyDirectForm` | `DirectActionForm` |
| `HwihaLegacyDirectOptions` | `DirectActionOptions` |
| `HwihaLegacyStratagemChoice` | `StratagemActionChoice` |
| `HwihaLegacyStratagemForm` | `StratagemActionForm` |
| `HwihaLegacyStratagemId` | `StratagemActionId` |
| `HwihaLegacyStratagemOptions` | `StratagemActionOptions` |
| `HwihaLegendEntry` | `LegendEntry` |
| `HwihaMapState` | `MapState` |
| `HwihaMilitaryActionId` | `MilitaryActionId` |
| `HwihaMilitaryForm` | `MilitaryForm` |
| `HwihaMilitaryOptions` | `MilitaryOptions` |
| `HwihaMonthlyAssessment` | `MonthlyAssessment` |
| `HwihaNationSummaryEntry` | `NationSummaryEntry` |
| `HwihaPeopleActionId` | `PeopleActionId` |
| `HwihaPeopleForm` | `PeopleForm` |
| `HwihaPeopleOptions` | `PeopleOptions` |
| `HwihaPersonCard` | `PersonCard` |
| `HwihaPersonalActionId` | `PersonalActionId` |
| `HwihaPersonalForm` | `PersonalForm` |
| `HwihaPersonalOptions` | `PersonalOptions` |
| `HwihaPhase` | `Phase` |
| `HwihaPlacementCard` | `PlacementCard` |
| `HwihaPolicies` | `Policies` |
| `HwihaPoliticalActionId` | `PoliticalActionId` |
| `HwihaPoliticalConsentOption` | `PoliticalConsentOption` |
| `HwihaPoliticalForm` | `PoliticalForm` |
| `HwihaPoliticalOption` | `PoliticalOption` |
| `HwihaPostOption` | `PostOption` |
| `HwihaPosts` | `Posts` |
| `HwihaRead` | `Read` |
| `HwihaReadStatus` | `ReadStatus` |
| `HwihaRecordKind` | `RecordKind` |
| `HwihaRenown` | `Renown` |
| `HwihaRenownPendingEvent` | `RenownPendingEvent` |
| `HwihaRenownReason` | `RenownReason` |
| `HwihaRetinue` | `Retinue` |
| `HwihaScout` | `Scout` |
| `HwihaScoutOption` | `ScoutOption` |
| `HwihaScoutOptions` | `ScoutOptions` |
| `HwihaScreen` | `GameScreen` |
| `HwihaSession` | `GameSession` |
| `HwihaSessionContext` | `GameSessionContext` |
| `HwihaSessionProvider` | `GameSessionProvider` |
| `HwihaShell` | `GameShell` |
| `HwihaShellProps` | `GameShellProps` |
| `HwihaSiege` | `Siege` |
| `HwihaSieges` | `Sieges` |
| `HwihaStamp` | `Stamp` |
| `HwihaStates` | `GameStates` |
| `HwihaStock` | `Stock` |
| `HwihaStratagemCard` | `StratagemCard` |
| `HwihaStratagemHand` | `StratagemHand` |
| `HwihaTransferActionId` | `TransferActionId` |
| `HwihaTransferForm` | `TransferForm` |
| `HwihaTransferOptions` | `TransferOptions` |
| `HwihaTravelActionId` | `TravelActionId` |
| `HwihaTravelForm` | `TravelForm` |
| `HwihaTravelOptions` | `TravelOptions` |
| `HwihaUnitCard` | `UnitCard` |
| `HwihaVisibility` | `Visibility` |
| `HwihaVisibilityCommandery` | `VisibilityCommandery` |
| `HwihaVisionTier` | `VisionTier` |
| `HwihaWarehouse` | `Warehouse` |
| `HwihaWarehouses` | `Warehouses` |
| `HwihaWorks` | `Works` |
| `HwihaWorld` | `World` |
| `HwihaWorldMap` | `WorldMap` |
| `HwihaYuedan` | `Yuedan` |
| `HwihaYuedanRow` | `YuedanRow` |

## 엔진 패키지 개명

| 이전 패키지·경로 | 새 패키지·경로 | 비고 |
|---|---|---|
| `opensamguk.engine.hwiha` (`app/game-engine/src/{main,test}/kotlin/opensamguk/engine/hwiha/`) | `opensamguk.engine.campaign` (`app/game-engine/src/{main,test}/kotlin/opensamguk/engine/campaign/`) | 103개 파일·호출부의 패키지 경로 이동. 조정·내정·전쟁 등 세부 도메인 분리는 후속 정리. |

## #905 교차 경로

PR #905 변경 파일 244개 가운데 개명 범위와 겹치는 경로가 64개다(app 31, logic 18, infra 6, web 4, tools 3, data 2). #905가 draft인 동안 해당 경로의 파일 이동과 참조 수정은 병합 상태를 확인하고 진행한다. 아래 목록은 개명 또는 리베이스가 필요한 정확한 옛 경로다.

```text
app/game-api/src/main/kotlin/opensamguk/gameapi/dto/HwihaDomesticDto.kt
app/game-api/src/main/kotlin/opensamguk/gameapi/dto/HwihaRoadFortDto.kt
app/game-api/src/main/kotlin/opensamguk/gameapi/precheck/HwihaDeployPrecheckService.kt
app/game-api/src/main/kotlin/opensamguk/gameapi/read/HwihaDomesticReader.kt
app/game-api/src/main/kotlin/opensamguk/gameapi/read/HwihaGameEnvStateMeta.kt
app/game-api/src/main/kotlin/opensamguk/gameapi/read/HwihaRoadFortReader.kt
app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/HwihaDomesticAdmission.kt
app/game-api/src/main/kotlin/opensamguk/gameapi/web/HwihaRoadFortController.kt
app/game-api/src/test/kotlin/opensamguk/gameapi/precheck/HwihaDeployPrecheckServiceTest.kt
app/game-api/src/test/kotlin/opensamguk/gameapi/read/HwihaCampReaderTest.kt
app/game-api/src/test/kotlin/opensamguk/gameapi/read/HwihaLastTurnsReaderTest.kt
app/game-api/src/test/kotlin/opensamguk/gameapi/v2/V2CommandPrecheckServiceTest.kt
app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaAssignmentMarchTurn.kt
app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaCorpsMarchTurn.kt
app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaCorpsRations.kt
app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaCourtHandler.kt
app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaDeployHandler.kt
app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaDomesticBoundary.kt
app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaDomesticContext.kt
app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaDomesticHandler.kt
app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaMarchReactionInterpreter.kt
app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaNpcDeploySelector.kt
app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaPhaseBoundary.kt
app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaPlacementMarch.kt
app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaRoadFortPassage.kt
app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaRoadFortSiegeService.kt
app/game-engine/src/test/kotlin/opensamguk/engine/boot/HwihaS3ChainSupport.kt
app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/HwihaDomesticEngineTest.kt
app/game-engine/src/test/kotlin/opensamguk/engine/hwiha/HwihaRoadFortSiegeServiceTest.kt
app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2CityTransportRulesTest.kt
app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2ContentCatalogBeanTest.kt
data/commands/hwiha-input-catalog.json
data/curated/han/hwiha-resource-production-v1.json
infra/src/main/kotlin/opensamguk/infra/seed/HwihaCountyGeographyJson.kt
infra/src/main/resources/hwiha/county-production-v1.json
infra/src/test/kotlin/opensamguk/infra/seed/HwihaCountyGeographyJsonTest.kt
infra/src/test/kotlin/opensamguk/infra/seed/HwihaWarehouseSeedIT.kt
infra/src/test/kotlin/opensamguk/infra/seed/HwihaYuzhouSliceScenarioTest.kt
infra/src/test/kotlin/opensamguk/infra/v2/V2CityCatalogAdapterTest.kt
logic/src/main/kotlin/opensamguk/logic/input/HwihaCountyGeography.kt
logic/src/main/kotlin/opensamguk/logic/input/HwihaDeployRules.kt
logic/src/main/kotlin/opensamguk/logic/input/HwihaDomesticEffects.kt
logic/src/main/kotlin/opensamguk/logic/input/HwihaDomesticInput.kt
logic/src/main/kotlin/opensamguk/logic/input/HwihaDomesticRules.kt
logic/src/main/kotlin/opensamguk/logic/input/HwihaDomesticState.kt
logic/src/main/kotlin/opensamguk/logic/input/HwihaInfrastructureSiteRules.kt
logic/src/main/kotlin/opensamguk/logic/input/HwihaLandPassageState.kt
logic/src/main/kotlin/opensamguk/logic/input/HwihaRecordKind.kt
logic/src/main/kotlin/opensamguk/logic/input/HwihaRoadFortSiegeInput.kt
logic/src/main/kotlin/opensamguk/logic/input/HwihaRoadFortState.kt
logic/src/test/kotlin/opensamguk/logic/input/HwihaDomesticEffectsTest.kt
logic/src/test/kotlin/opensamguk/logic/input/HwihaDomesticInputTest.kt
logic/src/test/kotlin/opensamguk/logic/input/HwihaDomesticRulesTest.kt
logic/src/test/kotlin/opensamguk/logic/input/HwihaInfrastructureSiteRulesTest.kt
logic/src/test/kotlin/opensamguk/logic/input/HwihaInputRegistryTest.kt
logic/src/test/kotlin/opensamguk/logic/input/HwihaLandPassageStateTest.kt
logic/src/test/kotlin/opensamguk/logic/input/HwihaRoadFortStateTest.kt
tools/e2e/fixtures/hwiha-yuzhou/scenario_990002.json
tools/map/build_hwiha_resource_production.py
tools/map/tests/test_build_hwiha_resource_production.py
web/game/__tests__/hwiha-siege-orders.test.tsx
web/game/app/game/hwiha/siege/page.tsx
web/game/components/hwiha/DomesticPanels.tsx
web/game/lib/hwiha-reads.ts
```
