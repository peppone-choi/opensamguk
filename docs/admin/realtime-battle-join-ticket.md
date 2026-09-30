# 실시간 전투 JoinTicket 발급 경계

game-api의 `POST /api/battles/{worldId}/{battleId}/join-ticket`은 검증된 gateway access JWT를 요구한다. 경로의 `worldId`는 game-api의 프로세스 세계와 같아야 한다. 발급 시 현재 계정이 소유한 장수와 저장된 전투 참가자·진영·권한 revision·세션 epoch를 대조한다. 응답은 `joinTicket`만 포함하며 `Cache-Control: no-store`를 보낸다. 권한이 없거나 전투를 찾을 수 없으면 전투 정보를 드러내지 않는 404를 반환한다.

기본값 `BATTLE_JOIN_TICKET_ENABLED=false`에서는 발급 컨트롤러·서명 서비스·WebSocket 진입점이 생성되지 않는다. 활성화할 때 `BATTLE_JOIN_TICKET_KEY_BASE64`에 별도로 생성한 32바이트 이상의 원시 키를 표준 Base64로 넣고, `SERVER_ID`에 소문자 서버 ID(1–48자), `BATTLE_WS_ALLOWED_ORIGINS`에 정확한 브라우저 Origin 목록(쉼표 구분)을 설정한다. 잘못된 값이나 빈 목록이면 game-api가 시작에 실패한다. gateway JWT 키나 다른 서비스 비밀키를 재사용하지 않는다. 실제 키는 저장소나 로그에 남기지 않는다.

발급·검증 시각은 PostgreSQL `clock_timestamp()`를 사용한다. 티켓은 최대 60초이며 전투 deadline을 넘지 않는다. 현재 세션 lease가 끝났거나 세션 단계가 JOINING/RUNNING이 아니면 발급하지 않는다. BTJ2 티켓은 서버 ID까지 서명하며 기존 BTJ1 티켓은 수용하지 않는다.

WebSocket 입장은 `/api/battle-ws/{serverId}/{worldId}/{battleId}`를 nginx에서 전용 game-api 경로로 보낸다. 브라우저는 `Sec-WebSocket-Protocol: battle.v1, <JoinTicket>`과 선택적 `?lastSeenEventSeq=<음이 아닌 정수>`를 사용한다. 서버는 응답에 `battle.v1`만 되돌리고 서명·현재 세션 epoch·참가자 revision·소유 장수를 101 전에 다시 확인한다. URL에 티켓을 넣거나 장기 JWT/쿠키를 전달하지 않는다. 현 절편은 접속 심사만 제공하며 전투 상태 전송·명령 처리는 후속 절편이다. 운영 프록시 적용은 별도 승인 후 진행한다.

연결 뒤에는 game-api가 5초마다 DB 기준 현재 세션 epoch·lease·phase·참가자 권한 revision과 계정의 소유 장수를 다시 확인한다. 상태가 바뀌거나 조회에 실패하면 연결을 닫는다. 15초마다 WebSocket ping을 보내고 마지막 pong 뒤 60초 동안 응답이 없으면 닫는다. JoinTicket의 60초 만료는 **새 입장**에 적용하며 이미 열린 연결의 수명을 직접 제한하지 않는다. 같은 계정·전투의 새 정상 입장은 이전 소켓을 닫고 슬롯을 교체해 재접속을 막지 않는다. 연결 슬롯은 현재 game-api 프로세스 안에서 관리한다. 재시작·다중 인스턴스 간 연결 수 제한은 이 절편의 보증 범위가 아니다.
