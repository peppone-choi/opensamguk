# 황제 소재지 읽기 계약

## 용도와 범위

S6-2b는 황제의 **실제 공간 노드**를 지도 배지와 황실 화면에 같은 값으로 제공한다. `ImperialPresenceProjection.badges`의 결과를 서버 읽기 API가 전달한다. 위치 정본은 `general_spatial_position`이고, 월드에 고정된 지형 판본으로 검증한다. `general.city_id`는 그 장수가 기준 城에 실제로 서 있는지 판별하는 데만 쓴다. `ImperialHouse.courtCityId`는 별도 조정 소재지이며 황제 위치를 대체하지 않는다.

城 없는 省으로 이동하면 `emperorNodeKind=LAND_PROVINCE`, `emperorNodeId=그 省 ID`, `emperorCityId=null`이다. 수역이면 `WATER_ZONE`과 수역 ID를 준다. 배지는 노드 ID로 그리며 `emperorCityId`를 위치 대용으로 쓰지 않는다. 전장에 있을 때도 공간 노드는 유지하되 城에 있다고 표시하지 않는다.

## 요청과 응답

- `GET /api/imperial/presence`: 현재 활성 월드의 공개 황제 소재지 요약. 기존 `/api/map` compact tuple 또는 `/api/map/preview` 응답을 변경하지 않는다.
- 월드에 `world_state.meta.imperialWorld`가 없으면 HTTP 200 `{"status":"NOT_SEEDED","badges":[]}`. 시드 부재를 황실 멸망이나 공위로 해석하지 않는다.
- 시드가 있고 활성 황제의 장수 행과 공간 위치를 모두 확인했으면 HTTP 200 `{"status":"READY","badges":[…]}`. `badges`는 `lineCode` 오름차순이다. 공위·종결 계통은 배지가 없다.
- 각 배지는 `lineCode`, `lineName`, `emperorGeneralId`, `emperorName`, `emperorNodeKind`, `emperorNodeId`, `emperorCityId`, `courtCityId`를 가진다. `emperorName`은 같은 읽기 트랜잭션에서 확인한 해당 월드 장수 행의 현재 공개 이름(`string|null`)이다. 빈 이름은 명시적 `null`이며 계통명이나 다른 월드의 장수 이름으로 채우지 않는다. 이름이 미정인 것만으로 `READY`를 다른 상태로 바꾸지 않는다. 복수 활성 황통은 각 배지의 황제 이름을 따로 제공한다. 노드 종류는 `LAND_PROVINCE` 또는 `WATER_ZONE`이다. `emperorCityId`는 기준 城의 省과 위치 省이 같고 전장 밖일 때만 그 城 ID, 아니면 명시적 `null`이다. 조정 城이 미정이면 `courtCityId:null`을 명시한다. 임의 기본 위치를 보충하지 않는다.
- 손상된 황실 meta·지형 판본 또는 활성 황제의 장수·공간 위치 행 누락은 HTTP 409 `{"status":"STATE_UNAVAILABLE","badges":[]}`로 드러낸다. 프론트는 배지를 숨기며 재시도·오류 상태를 표시한다. 인증·시야 정책이 나중에 황제 위치를 제한하면 이 공개 범위의 변경은 별도 계약 검토가 필요하다.
- 조서·밀지·사자·인장 보관자·경비·군량·세력별 호의는 이 응답에 포함하지 않는다. 궁정 상세·비공개 조서 화면은 별도 투영 API가 담당한다.

## 응답 fixture

- `app/game-api/src/test/resources/imperial/presence-ready.json`: 합성 계통의 황제 위치 省 `70930`·현 城 12, 조정 소재지 11로 분리된 정상 응답.
- `app/game-api/src/test/resources/imperial/presence-not-seeded.json`: 황실 시드가 없는 월드의 명시적 빈 응답.
- `app/game-api/src/test/resources/imperial/presence-unavailable.json`: 손상된 상태의 오류 응답 본문(HTTP 409).

세 fixture는 역사 사건을 주장하지 않는 API 형식 자료다. `ImperialPresenceReaderTest`가 DTO 직렬화 결과를 이 파일과 대조하고, 황제 장수·위치 행이 없을 때 `READY`를 만들지 못함을 확인한다. 城 없는 省의 응답은 별도 집중 테스트가 검증한다.
