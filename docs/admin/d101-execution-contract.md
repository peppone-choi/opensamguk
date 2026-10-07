# D101 초기화 접수 이력과 비공개 관문

D101 전용 내부 접수 경로는 승인 의도 원문, 별도 목적 서명 및 실제 원천 검증을 요구한다. 일반 관리자 권한이나 내부 서비스 bearer 하나로 초기화를 실행할 수 없다.

운영에 설치되지 않았던 native trust installer, host/selected reader, Purpose 공급자와 signed Root proof reader/adapter는 제거했다. 내부 D101 접수·복구 경로는 기존 unavailable 권한으로 계속 503을 반환한다. 일반 관리자 권한·내부 bearer·별도 bean으로 이 경로를 활성화할 수 없으며 Docker 또는 Root reset을 호출하지 않는다. 일반 reset/maintenance·서버 공개 권한 검사·공통 operation 예약과 DB 충돌 방어는 그대로 유지한다.

## 내부 경로

기준 경로: `/internal/d101/servers/pep/operations/{operationId}`.

| 요청 | 저장/응답 의미 |
| --- | --- |
| POST /prepare | 공통 operation ID 예약, PUBLIC R → VERIFYING V, D101 PREPARED 이력과 RESET 대기 메타데이터를 같은 gateway DB transaction으로 기록한다. 신규201, 정확한 원문 재생200. |
| GET 기준 경로 | 새 목적 권한 검증 뒤 같은 operation의 durable 이력을 읽는다. 확인된 미존재404, DB/source 불가503. |
| POST /dispatch-intent | 실제 plan/preflight 원천 검증 뒤 고정 참조와 registry dispatched를 함께 기록한다. Root 접수·물리 성공을 뜻하지 않는다. |
| POST /terminal | 검증된 Root 성공 원문을 REMOTE_SUCCEEDED로 보존한 뒤 전용 canonical transaction으로 REGISTRY_SETTLED에 정산한다. production consumer adapter는 아직 unavailable이므로 현재 운영 배선은 503이다. |

모든 정상/typed 오류 응답은 no-store다. 기존 내부 ingress의 bearer 실패401 응답 형식은 유지한다. 다른 server 경로는 지원하지 않는다. 원문은 prepare64KiB/intent32KiB/나머지16KiB로 제한하고 unknown field·중복 JSON key·잘못된 UTF8·null·대상/서명 불일치를 거절한다. 별도 내부 header는 `X-D101-Grant`이며 서명 원문은 public/JWT key로 대신하지 않는다.

## 멱등성과 실패 처리

같은 operation은 intent 원문·최초 prepare 원문·대상·R 및 처음 저장한 dispatch 참조가 모두 같아야 한다. 의미가 같은 JSON이라도 원문이 다르면409다. 과거 publication ID와 다른 종류/서버의 공통 ID는 새 초기화로 사용할 수 없다.

잠금은 registry parent → publication → execution/history → pending registry 순서다. prepare의 일부 저장이 실패하면 VERIFYING CAS와 공통 ID·history·execution도 함께 rollback한다. HTTP 또는 DB 응답이 UNKNOWN이면 같은 operation의 QUERY로 실제 저장 여부를 확인한다. 새 operation/UUID로 재시도하거나 성공·미실행을 추정하지 않는다.

PREPARED/DISPATCH_INTENT의 일반 registry claim·dispatch·정산·cancel 및 generic 최종 PUBLIC은 전용 실행을 대신하지 못한다. generic verifying의 같은 V history 응답은 새 물리 실행 권한이 아니다. 창 밖은 fresh QUERY만 허용하며 새 쓰기·창 연장·재실행을 허용하지 않는다.

Root 성공 원문 보존과 canonical 정산은 두 transaction으로 나눈다. 두 번째 transaction이 실패해도 같은 operation의 REMOTE_SUCCEEDED 및 원본 결과 SHA/bytes는 유지한다. 재개는 동일 결과 정산만 수행한다. display name·generation·scenario를 정산하면서 canonical URLs/project는 보존하고 RESET pending 삭제와 REGISTRY_SETTLED를 원자적으로 기록한다. 이 단계에서 publication은 VERIFYING V를 유지하며 PUBLIC은 별도 최종 관문이다.

## 남은 연결과 운영 경계

이력·RECOVERY_REQUIRED/RECOVERED 모델은 기존 operation 충돌과 최종 공개 거절에 사용하므로 유지한다. 복구 endpoint도 unavailable 권한으로 닫혀 있다. 삭제된 native 공급자를 새 운영 선행조건으로 사용하지 않는다.

마이그레이션은 운영 DB에 적용하지 않은 소스다. ready 직전 main의 최고 버전과 중복을 확인하고, 정상 PR CI의 actual PostgreSQL IT에서 원자성·stage 제약을 검증한다. 테스트 fixture 서명이나 local H2 결과를 운영 승인·실제 실행 증거로 사용하지 않는다.

이 경로는 G07 운영 장수 생성 특권을 제공하지 않는다. 생성 정책과 공개 후 일반 입장 설정은 해당 승인 계약을 따른다.
