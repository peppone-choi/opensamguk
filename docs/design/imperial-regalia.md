# 황실 인장·관인·부절 정본

## 역할

황실 옥새와 지방 관인은 물리적 물품이지만, 소유나 보관만으로 제위·관직 권한이 생기지 않는다. 황실 인장 상태는 황통·조서 상태와 별도 정본으로 저장한다. 조서의 인장 단계와 관직 credential 검사는 후속 통합 슬라이스가 이 정본을 참조한다.

## 상태

- `RegaliaArtifact.id`는 세계 안의 물리적 개체 식별자이며 중복할 수 없다. 같은 정체성을 주장하는 위조품은 다른 물리적 id를 가질 수 있다.
- `claimedIdentity`와 `authenticityClaims`는 그 물품에 관한 주장이다. `CORROBORATED`도 특정 증거에 근거한 평가이며, 소유자와 동일하지 않다. 사료를 확인하지 못한 진위는 확정하지 않는다.
- `ownerGeneralId` 또는 `ownerNationId`는 명목 소유자, `custodianGeneralId`와 `cityId`는 실제 보관 상태다. 보관 이전·탈취는 append-only `custodyHistory`를 남기되 소유·진위 주장을 자동 변경하지 않는다.
- `OfficeInstrumentScope`는 L1 관직 재임의 credential id, tenure id, 관할 id, 유효 순을 기록한다. L1 `OfficeCredential.kind=SEAL|TALLY`와는 id adapter로 연결한다. 재임 실효 권한은 L1 resolver가 인장·부절·부임·관할을 함께 판정한다.
- `STATE_REGALIA`에는 관직 scope를 붙이지 않는다. 물품을 가진 세력이 황제를 보유하거나 조서를 발급할 권한을 자동으로 얻지 않는다.

## 저장

`world_state.meta.imperialRegalia` 스키마 1에 전체 물품·주장·보관 이력을 넣는다. `world_state`는 기존 HotColdCatalog에서 `ALWAYS_HOT`이며 신규 표나 Flyway 번호가 필요 없다. 키가 없으면 시나리오가 인장을 선언하지 않은 상태다. 키가 있으나 손상됐다면 예외로 처리하고 기본 물품을 보충하지 않는다. 시나리오 seed 적재와 ChangeRecorder→flush→콜드 재로드 연결은 후속 통합 PR에서 한다.

## 사료와 범위

`data/curated/han/imperial-regalia-sources.json`은 『後漢書』 卷075 傳國璽의 보유 전언·탈취, 卷086 太守印綬 수여, 卷116 璽·虎符·竹符 관리의 원문을 분리한다. 卷075의 「又聞」은 보유 소문을 전하므로 특정 개체의 진위를 단정하지 않는다. 『三國志』 卷46 裴松之注에는 손견의 발견설과 그에 대한 반론이 함께 있으므로, 후속 사료 원장에서는 주석 속 주장을 정사 본문·연의와 섞지 않는다.

현재 PR은 사료·순수 모델·codec만 구현한다. 함락·포로·위조·조서 인장 단계·관직 실효 판정의 운영 연결은 S6-4b 및 S6-3 통합 슬라이스에서 한다.
