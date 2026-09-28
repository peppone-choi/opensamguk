# 실시간 전투 JoinTicket 발급 경계

game-api의 `POST /api/battles/{worldId}/{battleId}/join-ticket`은 검증된 gateway access JWT를 요구한다. 경로의 `worldId`는 game-api의 프로세스 세계와 같아야 한다. 발급 시 현재 계정이 소유한 장수와 저장된 전투 참가자·진영·권한 revision·세션 epoch를 대조한다. 응답은 `joinTicket`만 포함하며 `Cache-Control: no-store`를 보낸다. 권한이 없거나 전투를 찾을 수 없으면 전투 정보를 드러내지 않는 404를 반환한다.

기본값 `BATTLE_JOIN_TICKET_ENABLED=false`에서는 발급 컨트롤러와 서명 서비스가 생성되지 않는다. 활성화할 때 `BATTLE_JOIN_TICKET_KEY_BASE64`에 별도로 생성한 32바이트 이상의 원시 키를 표준 Base64로 넣어야 한다. 값이 없거나 형식이 틀리거나 짧으면 game-api가 시작에 실패한다. gateway JWT 키나 다른 서비스 비밀키를 재사용하지 않는다. 실제 키는 저장소나 로그에 남기지 않는다.

발급·검증 시각은 PostgreSQL `clock_timestamp()`를 사용한다. 티켓은 최대 60초이며 전투 deadline을 넘지 않는다. 현재 세션 lease가 끝났거나 세션 단계가 JOINING/RUNNING이 아니면 발급하지 않는다. WebSocket 접속과 라이브 배포 경로는 별도 구현 단계에 속한다.
