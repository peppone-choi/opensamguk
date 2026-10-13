# 국가 해산 #1457 인수 경계

PR #1608의 해산은 방랑군(level=0, 영토 없음) 군주 전용이다. 이 문서는 제품 완료 판정이 아니다.

## 생산 연결과 남은 인수

- `PoliticalRules.assess`는 군주·영토·건국 상태·전투를 검사한다.
- `PoliticalOptionsService`와 `PoliticalAdmission`은 같은 Rules와 카탈로그를 사용한다.
- `ReservedTurnHandler`는 카탈로그 hasHandler 확인 후 `PoliticalHandler`로 전달한다.
- 생산 `action.dissolve`는 `HANDLER_READY`로 연결했다. domain-rule/handler-test 소스 증거를 카탈로그에 등록했으며 Options→Admission→ReservedTurnHandler→PoliticalHandler의 생산 hasHandler 가드를 통과한다. UI_READY/VERIFIED나 실제 제품 완료 선언이 아니다.
- 초기 월 거절은 `PoliticalRules`의 `INITIAL_MONTH`로 공유한다. API/엔진 투영이 현재 월드 meta를 전달한다. `init_year`가 있으면 현재 월이 시작 월 이하일 때 Options·Admission·실행 모두 거절한다(`init_month` 누락 시 1, `init_year` 없는 legacy 월드는 기존 허용 보존). 초기 월의 미래 슬롯 예약도 거절하며 다음 월에 다시 예약한다. 접수 시점은 현재 월, 실행 시점은 실제 실행 월을 재검사하며 예약 인자에 월을 고정하지 않는다. 중복 성공 영수증 재조회는 기존 idempotency 규칙을 보존한다.
- `TurnDaemonLifecycle`은 해산 취소 슬롯 쓰기를 감지해 동결된 같은 배치의 부하 행동을 취소한다. `TurnRunService`의 취소 envelope와 `JdbcFlushExecutor`는 남은 예약에 terminal executionRejected/RESERVATION_CANCELLED를 저장한다.

## 확보된 코드 시험 근거

base head `9c58d32edc484cbd3ba716b49a6705bffee0ef9c`, GitHub run [38396454025](https://github.com/peppone-choi/opensamguk/actions/runs/38396454025): game-engine 성공.
`wu-game-engine-executed-attempt-1`는 해당 head의 JUNIT_XML 기반 결과이며 DissolvePersistenceIT의 실제 PostgreSQL flush/cold reload, PoliticalHandlerTest의 cleanup/거절/중복, TurnDaemonLifecycleIsolationTest의 실제 해산→동결 예약 취소가 passed다. 전체 CI 성공이나 실제 제품 QA를 대신하지 않는다.

PoliticalDeliveryTest는 생산 카탈로그의 Options→Admission 일치(군주, 방랑군, 영토, 건국, 전투, 인자), 초기 월 전체 슬롯 거절 및 연도 경계의 다음 월 접수를 검사한다. PoliticalHandlerTest는 생산 카탈로그의 다음 월 실행과 초기 월 무쓰기 거절을 검사한다. 새 head의 GitHub CI와 독립 리뷰가 필요하다.

## 실제 인수 기록 필수 항목

| 범위 | 요구 증거 | 현재 |
|---|---|---|
| UI/접수 | 환경·SHA·시각·actor/역할, args, 거절, 예약 readback | NOT_RUN |
| 정본 턴 | 군주/부하 동일 배치, 국가·군정·소속·권한·예약 전후, 결과 영수증 | 제품 NOT_RUN; 코드 시험만 PASS |
| 영속성 | 실제 제품 DB→cold reload→새 API/UI 일치 | 제품 NOT_RUN; fixture PG 시험 PASS |
| 경계 | 중복·취소·권한/대상 변경·재시작·이중 실행 없음 | 제품 NOT_RUN |
| 플랫폼/전투 | Windows·Android·실시간 전투 및 dangling 참조 확인 | NOT_RUN |

카탈로그 HANDLER_READY 증거는 소스 연결이다. 첫걸음 설명은 UNMAPPED로 유지하며 UI_READY/VERIFIED 승격과 draft 해제는 필수 CI·독립 리뷰·제품 인수 뒤 별도 판단한다. #1457 체크박스는 실제 인수 전 미체크로 유지한다. main 앱 자동배포는 승인 범위이나 이번 구현은 병합·게임 서버 조작·초기화·GCP 전원을 수행하지 않는다.
