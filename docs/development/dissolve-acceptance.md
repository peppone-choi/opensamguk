# 국가 해산 #1457 인수 경계

PR #1608의 해산은 방랑군(level=0, 영토 없음) 군주 전용이다. 이 문서는 제품 완료 판정이 아니다.

## 생산 연결과 남은 차단

- `PoliticalRules.assess`는 군주·영토·건국 상태·전투를 검사한다.
- `PoliticalOptionsService`와 `PoliticalAdmission`은 같은 Rules와 카탈로그를 사용한다.
- `ReservedTurnHandler`는 카탈로그 hasHandler 확인 후 `PoliticalHandler`로 전달한다.
- 생산 `action.dissolve`는 아직 PLANNED/evidence={}이다. Options는 NOT_DELIVERED, Admission과 Handler는 거절한다. 시험용 HANDLER_READY 카탈로그가 생산 노출의 증거는 아니다.
- `PoliticalHandler`의 초기 월 거절은 현재 Rules/Options/Admission에서 공유되지 않는다. 활성화 전 초기 월 옵션·접수·실행 정책의 일치를 보완/검증해야 한다. 초기 월에 미리 예약하고 다음 월 실행을 허용하는지 여부를 코드의 실행 시점 규칙과 함께 인수한다.
- `TurnDaemonLifecycle`은 해산 취소 슬롯 쓰기를 감지해 동결된 같은 배치의 부하 행동을 취소한다. `TurnRunService`의 취소 envelope와 `JdbcFlushExecutor`는 남은 예약에 terminal executionRejected/RESERVATION_CANCELLED를 저장한다.

## 확보된 코드 시험 근거

base head `9c58d32edc484cbd3ba716b49a6705bffee0ef9c`, GitHub run [38396454025](https://github.com/peppone-choi/opensamguk/actions/runs/38396454025): game-engine 성공.
`wu-game-engine-executed-attempt-1`는 해당 head의 JUNIT_XML 기반 결과이며 DissolvePersistenceIT의 실제 PostgreSQL flush/cold reload, PoliticalHandlerTest의 cleanup/거절/중복, TurnDaemonLifecycleIsolationTest의 실제 해산→동결 예약 취소가 passed다. 전체 CI 성공이나 실제 제품 QA를 대신하지 않는다.

PoliticalDeliveryTest에 생산 PLANNED 차단과 시험 활성화 상태의 해산 Options→Admission 일치(군주, 방랑군, 영토, 건국, 전투, 인자)를 추가한다. 새 head의 GitHub CI와 독립 리뷰가 필요하다.

## 실제 인수 기록 필수 항목

| 범위 | 요구 증거 | 현재 |
|---|---|---|
| UI/접수 | 환경·SHA·시각·actor/역할, args, 거절, 예약 readback | NOT_RUN |
| 정본 턴 | 군주/부하 동일 배치, 국가·군정·소속·권한·예약 전후, 결과 영수증 | 제품 NOT_RUN; 코드 시험만 PASS |
| 영속성 | 실제 제품 DB→cold reload→새 API/UI 일치 | 제품 NOT_RUN; fixture PG 시험 PASS |
| 경계 | 중복·취소·권한/대상 변경·재시작·이중 실행 없음 | 제품 NOT_RUN |
| 플랫폼/전투 | Windows·Android·실시간 전투 및 dangling 참조 확인 | NOT_RUN |

전체 필수 CI 및 독립 검증 후에만 카탈로그 evidence/승격과 draft 해제를 판단한다. #1457 체크박스는 실제 인수 전 미체크로 유지한다. main 앱 자동배포는 승인 범위이나 이번 구현은 병합·게임 서버 조작·초기화·GCP 전원을 수행하지 않는다.
