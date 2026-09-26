# 황제 소재지 읽기 계약

## 용도와 범위

S6-2b의 지도 배지와 황실 화면에 같은 황제 소재지 정보를 제공한다. `ImperialPresenceProjection.badges`의 결과를 서버 읽기 API가 전달한다. 이 문서는 프론트 인계용 계약이며 엔드포인트는 아직 구현되지 않았다. 실제 황제의 城은 장수 위치 행에서 읽고, `ImperialHouse.courtCityId`는 조정 소재지로 따로 보존한다. 두 城이 달라도 황제를 조정 城에 놓지 않는다.

## 요청과 응답

- `GET /api/imperial/presence`: 현재 활성 월드의 공개 황제 소재지 요약. 기존 `/api/map` compact tuple 또는 `/api/map/preview` 응답을 변경하지 않는다.
- 월드에 `world_state.meta.imperialWorld`가 없으면 HTTP 200 `{"status":"NOT_SEEDED","badges":[]}`. 시드 부재를 황실 멸망이나 공위로 해석하지 않는다.
- 시드가 있고 활성 황제 위치를 모두 확인했으면 HTTP 200 `{"status":"READY","badges":[…]}`. `badges`는 `lineCode` 오름차순이다. 공위·종결 계통은 배지가 없다.
- 각 배지는 `lineCode`, `lineName`, `emperorGeneralId`, `emperorCityId`, `courtCityId`를 가진다. 조정 城이 미정이면 `courtCityId:null`을 명시한다. 이름·ID와 양의 城 ID는 저장 상태에서 가져오며 임의 기본값을 보충하지 않는다.
- 손상된 황실 meta 또는 활성 황제의 물리 위치 누락은 HTTP 409 `{"status":"STATE_UNAVAILABLE","badges":[]}`로 드러낸다. 프론트는 배지를 숨기며 재시도·오류 상태를 표시한다. 인증·시야 정책이 나중에 황제 위치를 제한하면 이 공개 범위의 변경은 별도 계약 검토가 필요하다.
- 조서·밀지·사자·인장 보관자·경비·군량·세력별 호의는 이 응답에 포함하지 않는다. 궁정 상세·비공개 조서 화면은 별도 투영 API가 담당한다.

## 응답 fixture

- `app/game-api/src/test/resources/imperial/presence-ready.json`: 합성 계통의 황제 위치 12, 조정 소재지 11로 분리된 정상 응답.
- `app/game-api/src/test/resources/imperial/presence-not-seeded.json`: 황실 시드가 없는 월드의 명시적 빈 응답.
- `app/game-api/src/test/resources/imperial/presence-unavailable.json`: 손상된 상태의 오류 응답 본문(HTTP 409).

두 fixture는 역사 사건을 주장하지 않는 API 형식 자료다. 서버 구현 시 DTO 직렬화 결과를 이 파일과 대조하고, 위치 행을 삭제한 적색 프로브가 `READY`를 만들지 못함을 확인한다. 프론트 담당은 이 fixture로 화면을 먼저 구성할 수 있다.
