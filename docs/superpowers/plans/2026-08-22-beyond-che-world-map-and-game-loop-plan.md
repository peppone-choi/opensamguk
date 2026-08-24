# CHE 이후의 오픈삼국 세계·지도·게임 루프 실행 계획

- 작성일: 2026-08-22
- 상태: **EXECUTION-READY MASTER PLAN**
- 제품 정본: ADR-LITE-042·044·045, `2026-07-12-opensamguk-v2-product-spec.md`
- 도메인 정본: `2026-08-22-han-route-network-and-command-design.md`
- 데이터 감사: `2026-08-22-han-administrative-unit-detection-audit.md`,
  `2026-08-22-han-external-world-data-audit.md`

## 1. 목표와 비목표

오픈삼국의 목표는 CHE에 도시와 명령을 더 붙이는 것이 아니다. 플레이어가 역사 세계의 변화를 읽고,
사람과 권한을 배치해 작전을 설계하고, 이동·보급·전투·정치의 결과를 다음 판단으로 연결하는
**비동기 작전실 + 살아 있는 편년체**를 만든다.

```text
귀환 인과 요약 → 동료와 판단 → 명령·위임 → 서버 실행
              → 이동·보급·전투·정치 결과 → replay·편년체 → 후속 협의
```

비목표는 CHE draw-for-draw 재현, 30칸 명령표의 무한 확장, 자동 직선 도로, 아이소 격자 유지,
중국 밖의 임의 도시 채우기, 접속 여부가 승패를 좌우하는 강제 실시간 전투다.

## 2. 참고 자료의 판정 순위

1. 승인된 ADR·제품 spec·현재 one-daemon-write 및 결정론 불변식
2. 원문 사료와 provenance가 있는 역사·지리 데이터
3. 현재 Kotlin/Next.js 구현과 실제 검증 결과
4. `core2026`·`core2026_docker`의 명령 계약·테스트·운영 패턴
5. CHE·samnet·묘삼·칠랑섭의 비교 가능한 사용자 경험

비교 기준은 `core2026@957445b0e05773d4b452aa7aaa38e5f120204eb6`,
`core2026_docker@917e516c093893b06592515c6d2d4d25c9e4c16b`다. 가져올 것은 command schema,
durable request/result, 논리 시계, exact-SHA release manifest, DB 보존/초기화 분리, rollback·resource
validation이다. 즉시 `cityId`를 바꾸는 이동, 94성 평면 connection, API 직접 gameplay write,
컨테이너 내부 Git clone·PM2 build는 가져오지 않는다.

samnet에서 참고할 것은 세계가 로그인 전부터 움직이는 첫인상, 전쟁 ticker와 replay, 지도·명령·기록을
한 작업대에 두는 흐름이다. 묘삼에서 확인 가능한 것은 도시 자원·주둔군·인물 배치다. 칠랑섭의 내부
cadence·명령 공식과 묘삼 현행 운영은 `UNKNOWN`이며 추정으로 요구사항을 만들지 않는다.

## 3. 정본 세계 모델

### 3.1 서로 다른 네 계층

| 계층 | 개수·역할 | 완료 판정 |
| --- | --- | --- |
| 역사 행정 카탈로그 | 《후한서》 105군국·현/읍/도/후국 1,180 | 원문 구조 전수 검출, 유형·소속·순번·인용 |
| 물리 장소 | 점·후보 영역·상대 여정 | 좌표 출처·불확실성·시기 |
| 플레이 노드 | reviewed manifest 780개 | stable id·source identity·시나리오 활성·save migration |
| 수송망 | 승인 corridor + mutable infrastructure | mode·geometry·provenance·revision·상태 |

현재 source extractor는 105/1,180을 검출한다. 기존 `junguozhi.json`은 1,076개만 좌표 결합하며,
104개는 source gap이 아니라 join gap이다. `龜茲屬國`은 독립 군국이 아니라 上郡의 항목으로 고친다.
현재 780개는 결손 `zhi` 산술에 의존하므로 총수는 제품 목표로 유지하되 개별 선정은 manifest로 재심사한다.

### 3.2 도로망

현재 1,783개 connection은 승인된 도로가 아니라 후보 topology다. 추가된 도서·외부 edge도 일반
육로로 간주하지 않는다. 각 연결을 `ROAD | PASS | BRIDGE | FORD | WATERWAY | SEA_ROUTE |
STEPPE_CORRIDOR`로 분류하고 source claim과 geometry를 붙인다. 도로는 화면에 선을 긋는 renderer
결과가 아니라 건설·수리·파손·용량·통제·통행권·계절을 가진 세계 상태다.

모든 이동, 호송, 출병, 원군, 퇴각, 보급은 동일한 `RouteNetworkSnapshot`을 소비한다. 진행 중 명령은
revision을 pin하고, 변경 시 `RouteInvalidated` 뒤 명시적으로 우회한다. edge count를 하드코딩하지 않고
승인 manifest의 count와 hash를 함께 검증한다.

### 3.3 중국 밖

`AdministrativePlace | AnchoredPlace | PolityPresence | RemoteGate`를 분리한다. 외부 행의 무기한
`-9999..9999`, 현대 좌표 조회 성공을 역사 확정으로 보는 규칙, 수대 `流求`의 2세기 활성,
왜 여정 압축, 서역 0건, 유목 세력의 고정 도시화를 폐기한다. 동해 여정, 동북, 서역 남북도,
북방 초원, 남방·해상 pack을 독립 검수한다. 원거리 국가는 본 지도 축척을 무너뜨리지 않도록
remote gate와 지역 inset을 사용한다.

## 4. 커맨드와 게임성

### 4.1 command 계약

모든 v2 canonical command는 canonical id, legacy alias, source ring, actor/authority, typed args/result,
`AVAILABLE | NEEDS_INPUT | BLOCKED | UNKNOWN`, idempotency, expiry, replay event, route revision을 가진다.
신규 id가 registry에 없으면 휴식으로 떨어지지 않고 `UNKNOWN_COMMAND`로 fail closed한다.

- `personal.travel.plan|cancel`: 개인·부곡의 다턴 이동
- `logistics.convoy.create|reroute|cancel`: 재고 예약·호위·용량·도착·차단·약탈
- `operation.route.revise`: invalidation 뒤 revision 검증과 우회
- `operation.create/support/reinforce/setRetreat`: waypoint·보급선·arrival window·퇴각 corridor
- infrastructure project: `CITY | ROUTE_SEGMENT | CROSSING`
- typed objective: `INTERCEPT | BLOCK_ROUTE | SABOTAGE | ESCORT`

v1 `che_*` facade와 동결 회귀는 유지하지만 v2에서 즉시 위치 변경·원격 재고 증가·원군 순간 합류로
번역하지 않는다. intake ack와 daemon terminal result를 UI에서 구분한다.

### 4.2 접속 루프와 분위기

- 2~5분: 지난 접속 이후 중요도·내 역할 기반 인과 요약, 최대 3개 결정, 예턴 일부 수정
- 10~15분: 경로·보급·외교·위임 설계와 동료 의견
- 1시간 cadence: deterministic 비동기 실행
- 일간: 전선·회의·예외 점검
- 시즌: 봉토·정통성·통일 서사

raw 로그를 `proposalId/commandId/operationId/battleId/worldVersion`으로 엮은 causal thread로 투영한다.
공개 편년체는 fog allowlist, 국가 작전실은 권한별 상세, 개인 기록은 “내 판단이 무엇을 바꿨는가”를
설명한다. 도시·작전·전투 replay에 durable 회의 thread와 결정문을 붙이고 채팅은 즉시 조율에 쓴다.

1천여 행정단위는 클릭 수가 아니다. 태수·도독·가신 위임, 정책 template, 대량 명령 preview,
예외 알림으로 압축한다. 실시간 전술은 opt-in이며 부재 시 doctrine·가신 AI가 이어받는다.

## 5. 실행 wave와 수용 조건

### W0 — 원문 카탈로그와 780 선정 계약

- corpus 구조 identity `(sourceVolume, canonicalGroup, ordinal)` 1,180개를 ctext·CHGIS에 결합한다.
- 안평국 13/12 불일치는 citation과 함께 허용하고 항목을 생성하지 않는다.
- reviewed 780 selection manifest와 save migration policy를 만든다.
- **AC:** 105/1,180 exact, fake header 0, 각 780 node exactly-one source identity 또는 외부 claim,
  ambiguous 좌표의 silent nearest-neighbor 0, 총 780·stable id unique.

### W1 — corridor provenance와 외부 권역 pack

- 후보 1,783 edge를 mode·geometry·source·lifecycle로 심사한다.
- 동해·동북·서역·북방·남방 pack과 시나리오 활성 기간을 만든다.
- **AC:** dangling/self/duplicate/asymmetric 0, 780 node degree≥1, 승인 graph connected,
  모든 활성 외부 대상에 source/date/location resolution, `-9999..9999` 0.

### W2 — 이동 한 개의 완결 수직 절편

- 출발 선택 → route/ETA/보급 preview → 예약 → 3턴 이상 진행 → 중간 조우 → 퇴각/도착 → replay.
- one-daemon-write, precheck/reserved reason 합의, restart 복구를 보존한다.
- **AC:** 같은 snapshot/input/order/seed의 path·arrival·terminal result·replay hash 동일,
  intake 성공을 도착 성공으로 표시하는 UI 0.

### W3 — 호송·기반망·작전

- convoy, capacity 경합, 건설·수리·파손, invalidation/reroute, 원군·퇴각선을 연결한다.
- **AC:** 원격 재고 순간 증가 0, 재고 보존, path pin/invalidation 규칙, 육로·도하·해로 대표 fixture.

### W4 — 지도 작업면

- 지리 기반 2D/2.5D 벡터, semantic zoom, 도시·corridor·부대·보급·통제·불확실성 layer.
- 지도에서 대상 선택, route preview, 명령 timeline, 결과 replay를 한 작업면에 둔다.
- **AC:** geometry 없는 후보 도로 렌더 0, preview와 daemon route 일치, 키보드·reduced motion·색각
  대체 표현, 모바일 핵심 결정 3단계 이내.

### W5 — 귀환 요약과 살아 있는 편년체

- causal projection, 중요도/권한별 요약, 도시·작전·replay 회의 thread를 구현한다.
- **AC:** 한 operation을 제안부터 결과·후속 결정까지 추적, fog 누출 0, 2~5분 귀환 시나리오
  수동 QA, 부재 위임과 재접속 결과 설명.

### W6 — 운영·출시

- exact-SHA image manifest, migration compatibility, active/previous release, rollback/readiness,
  resource/secret validation을 관리자·배포 표면에 추가한다.
- **AC:** DB 보존 배포와 명시적 초기화 분리, rollback rehearsal, release audit log, component version
  mismatch 차단. gameplay mutation은 계속 daemon 단일 write다.

## 6. 티켓 재편

| 기존 | 조치 | 새 소관 |
| --- | --- | --- |
| OPENSAM-213 / GH #473 | 재작성 | W0~W3, hash 승인 RouteNetworkSnapshot과 완결 이동 loop |
| OPENSAM-214 / GH #474 | 재작성 | 이동·호송·보급·원군·invalidation의 legacy adapter |
| OPENSAM-215 / GH #475 | reopen·재작성 | 아이소 폐기, 승인 geometry 소비, 지도 command/replay 작업면 |
| OPENSAM-225 / GH #491 | 생성 | 1,180 source join, 780 selection manifest, 龜茲屬國 교정 |
| OPENSAM-226 / GH #492 | 생성 | 외부 권역 provenance·시간축·해로·remote gate |
| OPENSAM-227 / GH #493 | 생성 | typed availability/result와 unknown fail-closed |
| OPENSAM-228 / GH #494 | 생성 | 귀환 인과 요약·편년체·회의 thread |
| 기존 release/rollout | 보강 | exact-SHA manifest·migration compatibility·rollback rehearsal |

로컬 Markdown이 정본이다. Jira는 작업 흐름과 acceptance criteria, GitHub issue는 구현 단위와
저장소 증거를 반영한다. 외부 이슈는 본 계획보다 좁게 쓰며 서로 다른 수치를 재정의하지 않는다.

## 7. 중단선

- 지도 renderer부터 만들지 않는다. W0/W1 데이터·geometry 계약 전에는 자동 선을 그리지 않는다.
- 780을 현재 행 identity 그대로 동결하지 않는다. 총수와 stable migration만 먼저 고정한다.
- `城數`를 맞추려고 이름을 생성하지 않는다.
- 외부 권역을 임의 슬롯 수로 채우지 않는다.
- 실시간 전투, 3D, 수집 성장, 반복 일일 퀘스트를 핵심 루프 증명 전에 확장하지 않는다.
- 동결 회귀를 삭제하거나 기대값을 신규 기획에 맞춰 고치지 않는다. 신규 world/profile로 차이를 연다.
