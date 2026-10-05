# 봉신 저장 조건 읽기 — 소비자 조율용 초안

기존 `court-office-vassal-api-contract.md`의 공개 계약을 아직 변경하지 않는다. 이 초안과 로컬 `VassalStoredTermsReader`는 저장된 조건을 먼저 읽는 슬라이스의 검토 자료다. HTTP controller와 프론트 배선은 없다.

## 현재 저장 조회

인증 사용자로 `GeneralResolver`가 해석한 살아 있는 본인 장수만 actor가 된다. 처리 월드의 현재 세력과 `game_kv(game_env, game_env, vassalContracts)`를 같은 REPEATABLE_READ 트랜잭션에서 읽는다. 월드 meta나 다른 namespace의 값으로 부재를 덮지 않는다.

내부 저장 상태는 `READY | NOT_SEEDED | UNAVAILABLE`다. 정상 codec1 빈 상태만 READY 빈 목록이다. 미생산·손상·현재 월드/세력 불명은 READY 빈 목록이 아니다. READY는 저장 조건을 읽었다는 뜻이며 활성 계약·현재 주공 지위·명령권이나 설립 가능 여부를 보증하지 않는다.

목록에는 같은 세력으로 저장된 계약의 종료 기록도 있다. `signedTurn`, `expiresTurn`, `endedTurn`의 실제 Long 값을 보존하고 달력이나 현재 시각으로 해석하지 않는다. 이름은 같은 월드·현재 같은 세력의 장수 행에서만 읽으며 미확인 이름은 명시 null이다. 상납 이력은 해당 계약 영수증만 연월순으로 반환한다. 새 값·원군 기본량·새 계약·새 선택지를 생산하지 않는다.

## K8 회신이 필요한 공개 응답 후보

경로 후보는 기존 `GET /api/court/vassals?generalId=<actor>`를 유지한다. 인증되지 않으면 401, 본인 장수가 아니면 403, Cache-Control은 no-store다.

| 칸 | 후보 값과 의미 |
| --- | --- |
| `status` | `PARTIAL`은 저장 조건만 읽음, `UNAVAILABLE`은 조건도 미확인. 기존 READY/UNAVAILABLE과 다른 부분이므로 회신 전 사용하지 않음 |
| `now` | 실제 world_state 연월순. 계약 Long 시간 기준으로 사용하지 않음 |
| `contractsStatus` | 내부 READY/NOT_SEEDED/UNAVAILABLE을 유지. NOT_SEEDED는 계약 0개 확정이 아님 |
| `contracts[]` | 기존 contractId/sovereignLordId/vassalLordId/vassalName/fiefCountyIds/tributePercent/reinforcementTroops/autonomy/diplomacyRight/loyalty/signedTurn/tributeHistory를 유지; 실제 expiresTurn/endedTurn을 명시 null 또는 원본 Long으로 추가 |
| `activityStatus` | `UNAVAILABLE`. legacy Long의 현재 활성 판정 근거 없음 |
| `foundingOptionsStatus` | `UNAVAILABLE`. 첫 슬라이스는 설립 선택지의 producer를 공급하지 않음 |
| `foundingOptions[]` | 빈 목록은 위 상태와 함께만 반환하며 선택지 없음 확정으로 그리지 않음 |

K8이 저장 조건을 먼저 표시할 수 있는지와 위 상태 필드/문구/종료 기록 표시를 확인한 뒤 HTTP adapter·정확 fixture를 고정한다. 전체 활성·설립 목록을 처음부터 요구한다면 시간 기준과 실제 writer가 선행한다. 이 슬라이스는 입력 등록·handler·운영 설정을 바꾸지 않는다.

## 준비한 시험과 남은 검증

조회 시험은 원본 조건·80명 원군 의무 보존, 종료/미래 서명 원본 보존, 같은 세력 범위, 정상 빈 상태와 부재의 차이, 손상, 본인 actor 확인, 현재 월드/소속, 이름 null, null 직렬화를 다룬다. 로컬 자원 제한으로 아직 실행하지 않았다. HTTP 합의 뒤 실제 실패를 관측하는 정상 PR CI, 구현 뒤 GREEN, 독립 리뷰를 순서대로 기록한다. 저장 flush·재기동·실제 DB snapshot 검증은 아직 없으며 단위 대역 시험을 그 증거로 세지 않는다.
