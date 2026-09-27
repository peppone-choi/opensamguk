# 황제 기준 城 읽기 계약

## 용도와 범위

S6-2b는 황제의 **기준 城**을 지도 배지와 황실 화면에 같은 값으로 제공한다. `ImperialPresenceProjection.badges`의 결과를 서버 읽기 API가 전달한다. `emperorCityId`는 `general.city_id`에서 읽는 기준 城이고, `ImperialHouse.courtCityId`는 조정 소재지로 따로 보존한다. 두 城이 달라도 황제의 기준 城을 조정 城으로 대체하지 않는다.

이 응답은 `general_spatial_position`의 실제 위치를 읽지 않는다. HWIHA에서 황제가 城 없는 省으로 이동하면 `general.city_id`는 마지막으로 섰던 城을 유지하므로, 이 배지를 실제 소재지로 그리면 **떠나온 城에 찍힌다**. 실제 소재지를 표시하는 화면은 위치 행과 省 좌표를 읽는 후속 계약이 연결되기 전까지 이 배지를 현재 위치로 취급하면 안 된다.

## 요청과 응답

- `GET /api/imperial/presence`: 현재 활성 월드의 공개 황제 기준 城 요약. 기존 `/api/map` compact tuple 또는 `/api/map/preview` 응답을 변경하지 않는다.
- 월드에 `world_state.meta.imperialWorld`가 없으면 HTTP 200 `{"status":"NOT_SEEDED","badges":[]}`. 시드 부재를 황실 멸망이나 공위로 해석하지 않는다.
- 시드가 있고 활성 황제의 장수 행을 모두 확인했으면 HTTP 200 `{"status":"READY","badges":[…]}`. `badges`는 `lineCode` 오름차순이다. 공위·종결 계통은 배지가 없다. `READY`는 실제 위치 행을 확인했다는 뜻이 아니다.
- 각 배지는 `lineCode`, `lineName`, `emperorGeneralId`, `emperorCityId`(기준 城), `courtCityId`(조정 城)를 가진다. 조정 城이 미정이면 `courtCityId:null`을 명시한다. 이름·ID와 양의 城 ID는 저장 상태에서 가져오며 임의 기본값을 보충하지 않는다.
- 손상된 황실 meta 또는 활성 황제의 장수 행 누락은 HTTP 409 `{"status":"STATE_UNAVAILABLE","badges":[]}`로 드러낸다. 프론트는 배지를 숨기며 재시도·오류 상태를 표시한다. 인증·시야 정책이 나중에 황제 위치를 제한하면 이 공개 범위의 변경은 별도 계약 검토가 필요하다.
- 조서·밀지·사자·인장 보관자·경비·군량·세력별 호의는 이 응답에 포함하지 않는다. 궁정 상세·비공개 조서 화면은 별도 투영 API가 담당한다.

## 응답 fixture

- `app/game-api/src/test/resources/imperial/presence-ready.json`: 합성 계통의 황제 기준 城 12, 조정 소재지 11로 분리된 정상 응답.
- `app/game-api/src/test/resources/imperial/presence-not-seeded.json`: 황실 시드가 없는 월드의 명시적 빈 응답.
- `app/game-api/src/test/resources/imperial/presence-unavailable.json`: 손상된 상태의 오류 응답 본문(HTTP 409).

세 fixture는 역사 사건을 주장하지 않는 API 형식 자료다. `ImperialPresenceReaderTest`가 DTO 직렬화 결과를 이 파일과 대조하고, 황제 장수 행이 없을 때 `READY`를 만들지 못함을 확인한다. 프론트 담당은 기준 城 정보로만 이 fixture를 사용할 수 있다.
