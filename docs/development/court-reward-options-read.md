# 상사 옵션 읽기 계약

관련 작업은 [#789](https://github.com/peppone-choi/opensamguk/issues/789),
[#892](https://github.com/peppone-choi/opensamguk/issues/892)이다.

`GET /api/court/reward-options?generalId=<actor>[&retainerId=<card>][&money=<amount>]`는 인증된
현재 장수가 직접 거느린 인물 카드의 상사 금 상한과 저장된 창고 자금 추정치를 읽는다. `retainerId`는
인물 장수 ID가 아닌 카드 ID다. Controller → Query → Reader와 Query → Projection → 순수 경제 규칙을
거치며, 소유 확인부터 DB 조회까지 하나의 read-only REPEATABLE_READ 트랜잭션에서 수행한다.
소유 조회는 수리를 수행하지 않는 `GeneralOwnershipReadSource`를 사용한다.

모든 응답은 `Cache-Control: no-store`다. 인증 실패는 401 `AUTH_REQUIRED`, 양의 Int가 아닌 actor/card ID는
400 `INVALID_GENERAL_ID`/`INVALID_RETAINER_ID`, 카드 없이 money를 보낸 요청은 400
`PREVIEW_TARGET_REQUIRED`다. 현재 소유하지 않은 몸과 다른 처리 월드의 몸은 모두 403 `FORBIDDEN`이다.
URL의 userId나 nationId는 권한을 부여하지 않는다.
HTTP 오류는 `error: { code, message }`를 갖는다. message는 사람이 읽을 설명이며 특정 문구를 계약으로
고정하지 않는다.

## 응답과 불완전한 상태

루트 키는 `status`, `reason`, `generalId`, `snapshot`, `rule`, `queued`, `cards`, `preview`이며 null도
명시한다. READY의 카드 없음은 `cards: []`, 미가용은 `cards: null`이다. snapshot은 저장된 year/month/phase이며
전역 최신성 보장이 아니다. 루트가 READY가 아니면 snapshot/rule/queued/cards/preview는 모두 null이다.
월드 없음은 UNAVAILABLE/WORLD_UNAVAILABLE, 날짜 오류는 UNAVAILABLE/WORLD_DATE_INVALID이다.
저장소 예외는 다른 상태로 변환하지 않고 전파한다. 규칙과 형식 불일치는 각각 WRONG_RULE_PROFILE과
UNSUPPORTED_WORLD_FORMAT이다. 번들 없음은 UNAVAILABLE/ARTIFACTS_UNAVAILABLE이다. roster에 다른 월드의
행이 끼면 전체 투영을 UNAVAILABLE/ROSTER_INVALID로 닫고 해당 ID와 이름을 내보내지 않는다.
artifact resolver 예외는 성공 응답으로 바꾸지 않고 트랜잭션 밖으로 전파한다.

queued의 NONE/QUEUED/UNAVAILABLE와 retainerId/money는 현재 actor의 대기 metadata 진단이며 예약 승인이나
효력 보장이 아니다. 직접 소유·인물 연결 카드만 목록에 들어간다. 간접·타인·비인물·없는 카드의 preview는
동일한 CARD_UNAVAILABLE이다. 수신 인물 행이 없으면 소유 카드는 유지하지만 name/locationCityId는 null,
funding은 RECIPIENT_MISSING, preview는 CARD_UNAVAILABLE이다. 대체 이름이나 0 위치를 만들지 않는다.
수신 인물의 cityId가 0 이하이면 locationCityId는 null이다.

## 기존 금·충성 규칙

기존 CampaignBalance, RewardInput, RewardMoneyLimit와 실행 규칙을 그대로 사용한다. 금 100마다 충성 +1,
한 번에 최대 +10, 최소 금 100, 입력 상한 1,000,000,000, 충성 상한 100이다. loyaltyRoom은 한 번의
충성 여유이며 0~10으로 제한한다. 충성 0/95/99/100의 한 번 금
상한은 각각 1000/500/100/100이다. 충성 100의 금 100은 기존 기록용 지출이며 충성 증가는 0이다.
금 150은 충성 0/95에서 +1과 나머지 50, 충성 99/100에서는 REWARD_OVER_CAP이다.

money는 RewardInput과 같은 양의 정수 문법을 따른다. 문법 실패는 HTTP 400이 아니라 INVALID_AMOUNT이며
표현할 수 없는 preview.money는 null이다. 금 생략은 INVALID_AMOUNT가 아니며, 카드가 없으면
CARD_UNAVAILABLE, 소유 카드이면 NO_AMOUNT다. 판정 우선순위는 아래 표의 순서다. 요청 카드 ID와
유효하게 해석된 money를 되돌려주며, 그 밖의 효과·잔액·계획은 verdict에 따라 다음과 같다.

| verdict | loyaltyGain/loyaltyAfter/moneyWithoutGain | usableMoney | debitPlan |
| --- | --- | --- | --- |
| INVALID_AMOUNT | null | null | null |
| CARD_UNAVAILABLE | null | null | null |
| NO_AMOUNT | null | null | null |
| TOO_SMALL | null | null | null |
| REWARD_OVER_CAP | null | null | null |
| FUNDING_UNAVAILABLE | 충성 효과 추정 | null | null |
| INSUFFICIENT_STOCK | 충성 효과 추정 | 십진 문자열 | null |
| COVERED_AT_SNAPSHOT | 충성 효과 추정 | 십진 문자열 | 전액 계획 |

## 자금 범위와 안전한 정수

수신 인물의 cityId를 위치, actor의 nationId를 지불자로 사용한다. 자기 세력의 보급된 행정 縣이면 그 세력의
보급된 행정 縣 창고 전체를 수도 먼저, 나머지 ID 순으로 선택한다(NETWORK). 고립된 자기 縣이면 그 縣만
선택한다(ISOLATED). 보급되지 않은 수도는 네트워크에 들어가지 않는다. 재야·타국·무주 위치는 KNOWN,
NONE, 금 0이며 각각 PAYER_LANDLESS/LOCATION_FOREIGN/LOCATION_NEUTRAL로 구분한다.
행정 縣이 아닌 자기 도시도 기존 선택 규칙을 따른다. 보급된 위치는 NETWORK로 자기 세력의 보급된
행정 縣 창고를 선택하며, 고립된 비행정 위치는 ISOLATED로 금 0이다. LOCATION_UNKNOWN은 도시 행이
없을 때만 사용한다.

funding은 status, scope, noneReason, unavailableReason, usableMoney, warehouseCount를 모두 갖는다.
위치 불명은 LOCATION_UNKNOWN, 지불 세력 행 없음은 PAYER_NATION_MISSING이다. 후보의 창고 키 없음과
존재하되 훼손된 metadata는 구분한다. 훼손을 조용히 제외하지 않고 WAREHOUSE_MALFORMED로 닫는다.
합계 Long overflow는 TOTAL_OVERFLOW와 usableMoney=null이다. UNAVAILABLE의 scope는 null이다.
API의 MetaJsonConverter → MetaJson.decode → CountyWarehouse 정수 codec 경로를 사용하며 Double로
창고 값을 변환하지 않는다. 잔액·합계·revision·차감량은 큰 값도 정확한 십진 문자열로 응답한다.

## 추정치의 한계

preview의 verdict는 INVALID_AMOUNT/CARD_UNAVAILABLE/NO_AMOUNT/TOO_SMALL/REWARD_OVER_CAP/
FUNDING_UNAVAILABLE/INSUFFICIENT_STOCK/COVERED_AT_SNAPSHOT이다. debitPlan은 전액을 낼 수 있을 때만
있으며 각 항목은 cityId/isCapital/take/balance/revision이다. 부분 차감 계획은 없다.
COVERED_AT_SNAPSHOT은 저장된 스냅샷 추정치이며 예약·차감·실행 승인이 아니다.
`notChecked`는 항상 QUEUE_ADMISSION, REWARD_HISTORY, CONCURRENT_DEBITS, STATE_AFTER_SNAPSHOT이다.
접수와 실행자는 이후의 현재 소유·보급·충성·잔액·이력 등을 기존 경로에서 다시 검사한다. 이 조회는 pay,
settlement, executor, intake, NPC, 녹봉·보급 또는 잔액을 변경하지 않는다.

## 검증

순수 범위 시험은 lazy callback 및 창고 읽기 순서/횟수를 검사한다. 기존 상사·녹봉·부곡 보급·군단 군량·NPC
상사 소비 회귀와 EconomyBoundaryTest/DonationFlowTest 및 동결 backend parity gate를 유지한다.
전용 시험은 충성/금 표, zero와 unavailable, 훼손/overflow/큰 정수, JWT 401/400/403, 카드 비공개성,
no-store/null, 실제 PostgreSQL jsonb와 읽기 전용 트랜잭션 및 행 불변성을 검사한다.
Docker로 건너뛴 PG 시험은 PASS로 세지 않는다.
