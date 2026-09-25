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
| `opensamguk.engine.hwiha.HwihaCourtHandler` | `opensamguk.engine.court.CourtHandler` | 예정 | 도메인 패키지 이동 |
| `opensamguk.common.wire.TurnDaemonCommand.HwihaCourtInput` | `opensamguk.common.wire.TurnDaemonCommand.ImmediateInput` | 이 PR | `@SerialName` 변경은 저장·통신 단계에서 별도 처리 |

## 정한 값의 근거

- `ImmediateInput`은 조정 결정뿐 아니라 배치·방침·공사·계책도 운반하는 즉시 입력 와이어 타입이다. 이 PR은 Kotlin 타입 이름만 바꾸고 저장된 discriminator `hwihaCourtInput`과 `command_inbox.action_code` 값 `HwihaCourtInput`은 유지한다. 저장·통신 단계에서 새 도메인별 와이어 이름을 정해 같은 변경 안에서 producer·consumer·직렬화 테스트를 갱신한다.
- `worldFormat = GENERAL_RETAINER_CAMPAIGN`은 유일한 제품 세계의 구조를 명시한다. 새 가드는 키·값이 없거나 옛 `ruleProfile`이 있으면 실패한다. 이전 데이터 자동 해석은 넣지 않는다.
- DB의 `siege`와 `person_card`는 현행 스키마에 같은 이름이 없어 충돌하지 않는다. 이름 변경은 새 Flyway 파일로만 실행한다.
- 상태 키 79종의 새 이름은 `hwiha` 접두사를 제거하되 현행 제품 의미가 남은 `Legacy`를 도메인 이름으로 풀어 썼다. 키 이름이 같은 다른 JSON 층(예: `corpsPolicies`)과 합쳐지지 않는지는 reader·writer별 픽스처에서 확인한다.

## 저장·통신 식별자

| 이전 | 확정 이름 | 처리 PR | 비고 |
|---|---|---|---|
| `command_inbox.action_code` (IMMEDIATE) 값 `HwihaCourtInput` | 도메인별 즉시 입력 값 | 예정 | #919에서는 기존 값 고정; 새 값은 저장·통신 단계에서 확정 |
| `world_state.config.ruleProfile` | `worldFormat = GENERAL_RETAINER_CAMPAIGN` | 예정 | 값 없는 세계·옛 키·삼모 세계 fail closed |
| `hwiha_siege` | `siege` | 예정 | 새 Flyway 마이그레이션, 옛 파일 유지 |
| `hwiha_person_card` | `person_card` | 예정 | 새 Flyway 마이그레이션, 옛 파일 유지 |
| `/api/hwiha/*` | 도메인별 `/api/*` | 예정 | 엔드포인트별 경로 확정 필요 |
| `/game/<server>/hwiha/<screen>` | `/game/<server>/<screen>` | 예정 | 옛 경로 308 리다이렉트 |
| `data/**/hwiha-*.json` | 도메인별 파일명 | 예정 | 해시·핀·패키징 동시 갱신 |

## 상태·시나리오 필드 대응

소스에서 인용 부호로 읽고 쓰는 `hwiha…` 키 81종을 조사했다. 아래 79종은 장수·국가·縣 상태 및 시나리오 필드다. 와이어 타입 `hwihaCourtInput`과 DB 표 `hwiha_siege`는 위 표에 따로 적었다. 이름은 제품 접두사를 제거하며, 현행 제품 기능인 옛 `Legacy` 이름도 도메인 뜻으로 바꾼다. 변경 PR에서는 reader·writer·fixture의 동일 키 교체와 옛 키 거절을 함께 검증한다.

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
| `hwihaCountyWarehouse` | `countyWarehouse` | `logic/src/main/kotlin/opensamguk/logic/economy/HwihaCountyWarehouse.kt` |
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
| `hwihaScoutPosts` | `scoutPosts` | `logic/src/main/kotlin/opensamguk/logic/input/HwihaScoutPosts.kt` |
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
