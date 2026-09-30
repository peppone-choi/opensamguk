# 현 월 수입 읽기 계약 (K4-11 보강)

`GET /api/counties?scope=NATION|COMMANDERY&commanderyId=&generalId=`의 수입 읽기 첫 범위다. JWT가 소유한 현행 장수와 일반 소유 검사를 모두 통과해야 하며 일반 계정은 자신의 세력 소유 행정 縣만 목록에 받는다. COMMANDERY는 이 목록을 활성 세계 CommanderyIndex의 id로 더 거른다. 재야는 NO_NATION, 다른 세력의 수입은 내지 않는다. 전체 K4-11의 지표·공사·관직·경고·군 방침은 후속이며 이 작은 수입 표가 전체 화면 완성을 뜻하지 않는다.

응답: status, scope, commandery{id,name}|null, period=GAME_MONTH, basis=CURRENT_STATE_FORECAST, stamp{year,month,phase}|null, counties[{cityId,name,commanderyId,visibility,income{money,grain}|null}]. visibility는 기존 VisionReader의 활성 세계 시야 투영을 그대로 사용한다. FULL 밖에서는 수입을 계산하거나 내지 않는다. 시야 정본 자체가 미상이면 UNAVAILABLE, 지도/세계가 다르면 409다. 소유 縣이 없는 실제 목록은 빈 목록이다.

월 전망 정본은 CountyIncome.monthly이며 MonthlyCountyIncome.credit가 이를 월 경계에 한 번 적용한다. 현재 호구·상업·농업·상한·보급을 넣는 **월 총생산 전망**이고, 이미 적립된 확정 세입이나 순당 수입이 아니다. 창고가 없으면 엔진과 같이 0, 창고/입력 손상 또는 계산 넘침은 null/PARTIAL. 창고 재고 상한, 다음 경계까지의 개발·소유·보급 변화, 월 적립 도장, 철·목재·말의 합계 넘침으로 실제 적립은 달라질 수 있다. 월단평/녹봉 등 지출을 뺀 순수익도 아니다. 전·곡 계산에 산지 표의 상수를 복사하지 않는다.

읽기만 하며 운영 DB·migration·web 변경은 없다. 로컬 Gradle은 자원 조율에 따라 실행하지 않고 원격 CI에 집중 테스트 및 실제 JWT chain 검증을 맡긴다.
