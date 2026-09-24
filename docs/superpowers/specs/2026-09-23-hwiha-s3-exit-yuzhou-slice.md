# 휘하 S3 통과 — 豫州 한 州 슬라이스 완주

> 작성일: 2026-09-23
> 상태: **완료(2026-09-24)** — S3 豫州 관문 통과. 아래 원래 W0–W5 계획·위험·질문은 2026-09-23 당시 기록이며, 현재 판정은 이 절의 완료 기록을 따른다.
> 상위: [장수·휘하 캠페인 재설계](./2026-09-17-general-and-retinue-campaign-redesign.md) §5, §14, §15.2 S3 · [입력 registry 계약](./2026-09-17-input-registry-contract.md) · [휘하 시야·첩보 계약](./2026-09-23-hwiha-vision-contract.md)(PR #868) · [포트폴리오 계획](../plans/2026-09-17-general-retinue-portfolio-plan.md) 3단계
> 추적: #562(OPENSAM-231) 마스터 · #171(OPENSAM-29) · #351(OPENSAM-174)

## 2026-09-24 종료 판정

- [PR #869](https://github.com/peppone-choi/opensamguk/pull/869)가 main에 병합됐다(`2180bd8c`). 같은 SHA의 [CI](https://github.com/peppone-choi/opensamguk/actions/runs/35888552184) 8개 잡이 초록이며 `HwihaS3PassChainIT`·부곡 제거 적색 짝·豫州 모의 시험이 3회 동일 결과로 통과했다. 증거: 메타 보고서 `reports/opensamguk/tasks/2026-09-23-hwiha-s3-yuzhou-slice.md`와 `evidence/hwiha-s3-main-ci-three-attempts/comparison.json`.
- 격리 Docker 실스택의 단일 Playwright 흐름에서 가입 → 장수 생성 → 출사 → 발령 수락 → 부임 행군 도착 → NPC 공성·점령 → 징세 → 191-01 월단평을 36.1분에 완주했다. 9개 화면, API 12건, 순별 DB 사건표와 `tick failed=0` 기록은 같은 메타 보고서의 `evidence/hwiha-yuzhou-live-w4-pending-probe-20260924/`에 있다. 이는 pep 운영 전환 증거가 아니다.
- §6의 D1은 점령군 수비대 전입 뒤 군단 해산, D2는 수도 이전·멸망, D3는 일시 조우 2순 재시도, D4는 `scenario_990002` 카탈로그 등록으로 결정·구현됐다. D5 수치는 [#872](https://github.com/peppone-choi/opensamguk/issues/872)와 [PR #874](https://github.com/peppone-choi/opensamguk/pull/874)에서 확정됐다.
- 남은 관측 위험: 실스택 함락 7건이 모두 3순으로 목표 12–24순보다 빠르다. 적대 관계 13개월 상한 뒤 전쟁 지속성은 이 검증 범위 밖이다. 수치 확정은 이 템포의 품질 합격을 뜻하지 않는다.
- pep 전환은 [ADR-LITE-065](../../../.ai/decisions.md)에 따라 지도 통일과 B단계 PR들의 승인·병합, main CI 초록 뒤 런북으로 실행한다. §1·§3·§5·§6의 「별도 승인」, PROVISIONAL, 작업 전 상태는 당시 계획 기록이다.

## 0. 지금 코드 상태 (2026-09-23 15시 기준)

| 대상 | SHA | 상태 |
|---|---|---|
| `main` | `4fb0b4b3` | #861(폰트 self-host)·#862(산지 생산 런타임 배선) 머지 |
| PR #868 `work/opensamguk/hwiha-real-backend` | `2e6339cf` | 열림. main 병합(`e6e58ef3`)과 리뷰 P1 수정(`2e6339cf`, 부곡 월 군량 보충은 실제 위치 기준) 푸시. jvm 만 빨강 — `a3f75821`(run `35821661067`)과 `2e6339cf`(run `35823972446`) 모두 같은 DB IT 4건. 나머지 8개 체크 초록 |
| PR #865 `work/opensamguk/gap-county-placement-1228` | `1ca36bf4` | 열림. 1224 판. jvm 빨강 3건 — main 병합 뒤 고정 수치가 낡았다(`HwihaCountyProductionJsonTest` 2건, `HanSpatialSupplyProviderTest` 省 1558) |

PR #868 이 S3 고리의 모든 칸을 배선했다. `main` 에는 아직 없다.

| S3 고리 칸 | 구현(PR #868) | 비고 |
|---|---|---|
| 출사 | `action.enlist`(PR #854, main) | |
| 발령·응답 | `court.dispatch`·`court.dispatchReply`(PR #855, main) | 거절 명망은 월단평 −4 로 통일, 실행기 즉시 −1 제거 |
| 장수 턴 — 행군 | PR #856·#857(main) · 배치 부임 행군 `HwihaPlacementMarch` | |
| 장수 턴 — 조우 | `HwihaEncounterResolver` → `HwihaEncounterResolution` → `HwihaBattleAutopilot`(`8a21d148`) | 공격 지휘관 개인 턴에 격자 전투로 해결. 요격·회피는 **비차단 기본값**(`HwihaMarchReactionPolicy`) |
| 장수 턴 — 공성 | `HwihaSiegeService`·`HwihaSiegeHandler`, V61 `hwiha_siege`(`d1272990`·`2fce6260`) | 강공 격자전·항복 권고·함락 정산. `GET /api/hwiha/sieges` |
| 순 경계 — 보급 | 순마다 보급망 재계산·녹봉 `HwihaMonthlySalary`·상사 `HwihaRewardExecutor`(`7576a1e7`), 부곡 군량 보충 `HwihaUnitResupply`(`59d4ff8e`·`2e6339cf`) | 휘하 월드에서 옛 가신 유지비 30/30 끔 |
| 縣 점령 | `HwihaSiegeService.capture` → `HwihaCountyCapture.settle` | |
| 징세 | `HwihaMonthlyCountyIncome`(PR #860·#862) — 전·곡 + 산지 철·목재·말 | |
| 월단평 | `HwihaMonthlyAssessment`(PR #863) + 사건 기록 `HwihaRenownEventRecorder`·전쟁 결과 `HwihaWarOutcomeRenownListener`·치적 `HwihaGovernanceMeritRenownSink` | 종류당 월 1회, 이탈(0)·배신(−8) 구분 |
| NPC | 출사·발령 선택기(main), 출병 `HwihaNpcDeploySelector`(`51cfee42`), 구원 출병(`27fa8c59`) | 포상 AI·위임 AI 없음 |
| 관문 | `HwihaS3PassChainIT`(豫州 48순) + 적색 짝 `HwihaS3PassChainProbeIT`(`3f3a5ad4`) | **CI 에서 둘 다 빨갛다** |

입력 원장(`data/commands/hwiha-input-catalog.json`) 12행 중 11행이 HANDLER_READY 다. `stratagem.play` 만 PLANNED 다.

## 1. 목표와 통과 조건

S3 의 통과 조건은 설계 §15.2 그대로다. 출사 → 발령 → 장수 턴(행군·조우·공성) → 순 경계(보급) → 縣 점령 → 징세 → 월단평이 관리자 개입 없이 돌아야 하고, 월 경계를 한 번 이상 넘겨야 한다.

이 스펙이 끝나면 다음 네 가지가 모두 참이어야 한다.

1. **CI 관문 초록** — `HwihaS3PassChainIT` 가 main 의 CI(jvm)에서 초록이다. 적색 짝 `HwihaS3PassChainProbeIT` 는 부곡을 빼면 조우·공성 칸에서 빨개진다. 둘 다 결정적이다(같은 커밋을 두 번 돌리면 같은 결과).
2. **실스택 완주** — 로컬 도커 스택에 豫州 시나리오를 올린다. 사람 장수 1명이 브라우저로 가입 → 장수 생성 → 출사를 하면, 나머지는 NPC 와 루프가 맡는다. 발령 응답 → 행군 → 조우 → 공성 → 점령 → 징세 → 월단평이 화면과 DB 에 남는다. 관리자 SQL 개입은 0회다.
3. **전쟁 결과가 세계를 망가뜨리지 않는다** — 함락한 縣이 바로 되넘어가지 않는다. 수도를 잃어도 보급망 뿌리가 적의 城을 가리키지 않는다. 풀 수 없는 조우가 행군을 영원히 붙잡지 않는다(§3 W2).
4. **플레이어가 공성을 조작할 수 있다** — 공성 화면이 실데이터를 보이고, 강공·항복 권고·상사를 화면에서 입력할 수 있다(§3 W3).

통과를 확인하면 pep 전환 준비(§3 W5)로 넘어간다. 전환 자체와 월드 초기화는 **별도 승인** 대상이다.

## 2. 범위 밖

- 요격·회피 해석기(#786·#200). 지금은 반응 기록이 행군을 막지 않는다(`HwihaMarchReactionPolicy` CLEAR 기본값). S3 통과 조건이 요구하지 않으므로 다음 스펙으로 넘긴다. 단 §3 W4 에서 「효과 없음」을 플레이어에게 보이게 한다.
- 계책 카드 효과·`stratagem.play`(#782).
- NPC 포상 AI·미접속 장수 위임 AI·§14 게이트(#792·#616) — S4.
- V57 `battle_replay` 확장 저장·해시(#166·#199). 조우 journal 은 참가자 meta 에만 있다.
- 해전(#349), 수송·호송(#474), 부대 카드 모델(#162).
- 1224 판 전환. PR #865 가 머지되면 §3 W1 에서 豫州 시나리오를 다시 검사한다(§5 위험 2).

## 3. 작업 단위

순서: W0 → W1 → (W2 · W3 병렬) → W4 → W5. W2 와 W3 은 서로 다른 파일을 소유한다(W2 = engine·logic, W3 = web·game-api 조회).

### W0. PR #868 초록화와 머지

**문제.** jvm 이 game-engine DB IT 4건으로 빨갛다(`a3f75821` run `35821661067` · `2e6339cf` run `35823972446`, 둘 다 4건 실패).

| 테스트 | 증상 |
|---|---|
| `HwihaCourtApiIT` — HTTP dispatch persists queue then executes on issuer turn … | FAILED |
| `HwihaDispatchPersistenceIT` — refusal loyalty renown and terminal state survive cold reload with no second charge | FAILED |
| `HwihaS3PassChainIT` | `IllegalStateException` at `HwihaS3PassChainIT.kt:56` |
| `HwihaS3PassChainProbeIT` | `IllegalStateException` at `HwihaS3PassChainProbeIT.kt:55` |

원인 분석: §7 부록 A. 요약하면 1·2 는 거절 명망 −1 제거 결정에 맞춰 기대값이 낡은 것(29→30)이다. 3·4 는 로그에 예외 메시지가 없어 미확정이다. 두 IT 는 작성 뒤 한 번도 돌지 않았다.

**변경.** 부록 A 의 선행 작업(CI 예외 전문 출력·리포트 업로드, 적색 짝 로컬 실행)부터 하고, 그다음 수정 방향대로 고친다. 기대값을 바꿔야 하면 **먼저 어느 사용자 결정 때문에 바뀌는지 적는다.** 예: 거절 명망 −1 제거는 2026-09-23 결정이다. 근거 없이 단언을 약화하지 않는다.

**수용 기준.**
- PR #868 CI 9개 체크가 모두 초록이다.
- DB IT 18건 목록(보고서 `reports/opensamguk/tasks/2026-09-23-hwiha-real-backend.md` 「Verification」)을 로컬에서 한 번 돌린 기록이 PR 본문에 있다. gradle 전체 테스트는 겹쳐 띄우지 않는다. 같은 Postgres 락 때문에 조용히 멈춘다.
- 머지는 merge commit 으로 한다(PR 본문 지시). 머지 뒤 같은 브랜치에 푸시하지 않는다.

### W1. S3 관문을 결정적으로

**문제.** `HwihaS3ChainSupport.PHASES = 48` 안에 조우가 난다는 보장이 없다. 모의 36순에서 조우가 1건뿐이었다(보고서 「남은 위험」). 관문이 우연에 기대면 초록이 증거가 되지 못한다.

**변경.**
1. 豫州 시나리오에서 조우가 반드시 나는 조건을 **데이터로** 고정한다. 교전 중인 두 세력의 주공이 서로의 縣으로 출병하는 배치와 NPC 출병 선택기의 목표 선택 규칙을 이용한다. 순 수를 늘려 덮지 않는다.
2. 관문 단언마다 「몇 순째에 처음 참이 됐는지」를 기록하는 측정 출력을 더한다. 이 값은 게이트 임계값이 아니다. 기준선 기록일 뿐이다.
3. 같은 커밋을 두 번 돌려 순별 결과가 같은지 확인하는 결정성 단언을 더한다(개인 턴 결정론 계약 §2.2·§2.3).
4. PR #865(1224 판)가 먼저 머지되면 `scenario_990002.json` 을 1224 판에 대해 다시 검사한다. 새 豫州 縣에는 창고 행이 없으면 징세가 조용히 건너뛴다(`HwihaMonthlyCountyIncome` 은 창고 없는 縣을 건너뛴다). 전 縣 창고 불변식은 검사로 확인한다.

**수용 기준.** CI 에서 관문과 적색 짝이 3회 연속 같은 결과를 낸다. 적색 짝은 실제로 빨개져야 한다. 한 번 부곡을 빼 보고 빨간 것을 확인한 뒤 되돌린다.

### W2. 전쟁 결과 결함 3건과 경계 예외 감사

| # | 결함 | 코드 근거 | 방향 |
|---|---|---|---|
| a | **함락 직후 수비 0.** 점령하면 `defence = settlement.garrisonTroops`(= 0)이고 군단이 해산된다(`endDeployment`). 다음 순에 되찾으러 오면 수비가 없어 `UNDEFENDED` 로 즉시 넘어간다. | `HwihaSiegeService.capture`, `startIfArrived` 의 `garrisonOf(city) == 0` 분기 | §6 D1 |
| b | **수도 함락 뒤 수도가 그대로.** `nation.capitalCityId` 를 옮기는 곳이 없다. 보급망 뿌리(`HwihaPhaseBoundary` `SupplyCapital`)와 녹봉 창고망(`HwihaWarehouseNetwork`)이 적 소유 城을 뿌리로 쓴다. 縣이 0 이 된 세력의 멸망 정산도 휘하 경로에 없다. | `HwihaPhaseBoundary.kt:42`, `HwihaWarehouseNetwork.kt:23` | §6 D2 |
| c | **풀 수 없는 조우는 영구 대기.** 지원하지 않는 병종이나 전장 없음으로 전투를 준비하지 못하면 `BATTLE_NOT_READY` 로 남고, 행군이 `BATTLE_PENDING` 에서 멈춘다. | `HwihaEncounterResolver.resolvePending`(journal 없음 → pending 유지) | 아래 |

(c) 의 방향: 준비 불가 사유가 **영구적**이면 조우를 「무전투 해산」으로 끝낸다. 영구 사유는 병종 프로필 없음과 전장 기하 없음이다. 양쪽 행군은 그 省에서 정지하고, 사유를 지난 순 기록에 남긴다. **예외를 던지지 않는다.** 월·순 경계에서 던지면 턴 루프가 영구히 멈춘다. 일시 사유(참가자 meta 불일치 등)는 N순 재시도 뒤 같은 해산으로 끝낸다. N 은 PROVISIONAL 데이터 파일(`hwiha-s3-provisional-v1.json`)에 둔다.

(d) **경계 코드의 예외 지점 감사.** 순·월 경계에서 도는 휘하 서비스의 `check`·`require`·`error`·`!!` 는 후보 20곳이다. 파일별로 공성 4·내정 경계 6·명망 사건 4·월단평 2·창고망 2·보급 1·징세 1이다. 예: `HwihaSiegeService.kt:244` 「Validated garrison ration debit was rejected」. 한 곳씩 두 가지 중 하나로 정리한다. 테스트로 도달 불가를 증명하거나, 그 縣·장수만 건너뛰고 기록을 남기게 바꾼다. 경계에서 던지면 health 는 UP 인 채 시계가 멈춘다.

**수용 기준.**
- (a) 함락 → 다음 순 역포위 시나리오 단위 테스트에서 縣이 즉시 넘어가지 않는다(D1 규칙대로 수비가 있다). 적색 짝: 수정을 되돌리면 `UNDEFENDED` 로 넘어간다.
- (b) 수도 함락 단위 테스트: 수도가 D2 규칙대로 옮겨지고 보급망 뿌리가 자기 세력 城이다. 縣 0 세력은 D2 규칙대로 정산된다.
- (c) 병종 프로필이 없는 부곡이 낀 조우가 N순 안에 끝나고, 턴 루프 틱이 예외 없이 돈다.
- (d) 20곳마다 「도달 불가 테스트」 또는 「건너뛰기+기록」 중 무엇으로 정리했는지 표로 PR 에 남긴다. 건너뛰기로 바꾼 곳은 적색 프로브(잘못된 입력 → 루프 계속 + 기록 1건)가 있다.
- 豫州 모의 시험(`HwihaYuzhouCampaignSimulationTest`)의 36순 포위·함락 수를 전후로 기록한다. 승인된 목표 템포(일반 縣城 완전 봉쇄 포위 12–24순, 설계 §16.3)와 비교한 결과를 PR 에 적는다. 이 비교는 게이트가 아니다. 사용자 판단 자료다.

### W3. 공성 화면 실데이터와 공성·상사 입력

**문제.** `web/game/app/game/hwiha/siege/page.tsx` 가 `GET /api/hwiha/sieges` 에 연결돼 있지 않다. 강공(`action.assault`)·항복 권고(`action.demandSurrender`)·상사(`court.reward`)는 백엔드가 HANDLER_READY 인데 입력 UI 가 없다.

**변경.**
- 공성 화면: 포위 목록(내가 건 것·당한 것), 순 수, 성 안 사기·수비·군량, 타임라인을 `/api/hwiha/sieges?generalId=` 로 그린다.
- 강공·항복 권고: 개인 턴 예약 명령으로 넣는다. 정찰(`action.scout`)이 쓰는 `api.command(name, args, generalId, turnIdx)` 경로(`war-room/page.tsx:70`)와 같다. 버튼은 포위를 지휘하는 장수일 때만 켠다. 항복 권고는 서버가 준 문턱 정보(사기·민심)를 함께 보인다.
- 상사: 조정 화면에서 `POST /api/commands/court/reward` 로 넣는다. 대상은 내 휘하 카드, 금액은 창고망 금 잔액 이하.
- 실패 사유 코드(`HwihaSiegeService.Failure`)마다 한국어 문구를 둔다. 문구는 새 용어 결정(「계책 덱」 등)을 따른다.

**수용 기준.** web/game vitest 에 화면 단위 테스트를 더한다. 실스택 캡처는 W4 에서 한다. 서버가 거절하는 경우(지휘관 아님·포위 없음·병종 없음)를 각각 화면에서 확인한다.

### W4. 豫州 실스택 검증

**변경 없음(검증 작업).** 필요한 수정이 나오면 W2·W3 로 되돌린다.

1. 로컬 도커 스택(`docker-compose.yml`, 개발용)에 PR #868 머지 후 main 이미지를 굽는다. 백엔드 이미지는 하나씩 굽는다(동시 빌드는 메모리 부족).
2. `scenario_990002` 를 로컬 월드에 올린다. 이 시나리오는 **운영 시나리오 목록에 없다.** 로컬 초기화에는 승인이 필요 없지만, 목록 등록은 §6 D4 다.
3. 브라우저(Playwright)로 가입 → 장수 생성 → 출사한다. 실측 장수는 만들자마자 `killturn` 을 올린다. 기본 6이면 명령 없이 6턴 뒤 삭제된다.
4. 턴 시간을 짧게 두고 월 경계를 한 번 이상 넘긴다. 확인할 것: 발령 응답 → 부임 행군 → (NPC) 출병·조우 → 공성 → 점령 → 징세(창고 증가) → 녹봉 → 월단평 순위.
5. 휘하 화면 9종을 실데이터로 캡처한다. 각 화면의 API 응답을 저장한다.
6. 엔진 로그에서 `tick failed` 0건을 확인한다. health UP 만으로는 부족하다.

**수용 기준.** 보고서에 순별 사건표, 화면 캡처, DB 조회 결과(`hwiha_siege`, 창고 meta, 월단평 순위)를 남긴다. 관리자 SQL 은 시나리오 적재와 `killturn` 조정 외에 0회다.

### W5. pep 전환 준비(실행하지 않음)

설계 §15.2: pep 은 S3 통과 직후 새 규칙 시험 서버로 전환하고, 현행 월드는 초기화한다(2026-09-17 사용자 결정). 이 스펙은 **준비물만** 만든다.

- 전환 런북: 대상 시나리오, 지도 판(1168 또는 1224), 승격 SHA, `reset-game-server.yml` 입력값 전부를 적는다. 워크플로 기본값은 낡았다. 그대로 돌리면 초기화가 아니라 시나리오 교체가 된다.
- 전환 전 실측 체크리스트: 현행 월드 상태, 디스크 여유, 백엔드 Sentry 가 아니라 관리자 조회 경로(`lastTickError`)로 턴 루프 확인.
- 전환 실행·월드 초기화는 사용자 승인 뒤 별도 작업으로 한다.

## 4. 이슈 연결

| 작업 | 닫거나 진척하는 이슈 |
|---|---|
| W0 | #868 머지 → #789(OPENSAM-269)·#213(71)·#465(205)·#494(228)·#779(259)·#783(263)·#784(264)·#785(265)·#343(166) 진척. 각 이슈의 남은 항목을 확인한 뒤 닫는다 |
| W1 | #351(OPENSAM-174) S3 부분집합, #171(29) |
| W2 | #348(OPENSAM-171)·#202(60)·#201(59)·#347(170) |
| W3 | #348(171)·#783(263)·#789(269) |
| W4 | #562(OPENSAM-231) 3단계 통과 기록 |
| W5 | #249(OPENSAM-106) 컷오버 준비 |

## 5. 위험

1. **PROVISIONAL 수치가 스펙이 된다.** `hwiha-s3-provisional-v1`·`hwiha-domestic-v1`·`hwiha-vision-rules-v1` 과 치적 문턱 2% 는 사용자 결정 대기다(§6 D5). 이 스펙에서 새로 넣는 수치(W2 c 의 N)도 같은 파일에 PROVISIONAL 로 둔다. 테스트는 이 값을 복사하지 말고 파일에서 읽는다.
2. **#865 와 #868 의 결합.** 1224 판은 省 1430·관할 1224 로 바뀐다. 豫州 시나리오·S3 IT·산지 표·보급 기준선의 고정 수치가 먼저 머지되는 쪽에 맞춰 다시 핀돼야 한다. 재핀은 결합 검사 초록이 아니라 **저장소 전체 grep 0건**이 기준이다.
3. **V61 을 V60 보다 먼저 적용한 DB.** 로컬 DB 가 그런 순서로 올라갔다면 Flyway `outOfOrder` 가 필요할 수 있다. W4 는 새 DB 로 시작한다.
4. **정복 속도.** 모의 36순에 포위 26·함락 22건이다. W2 (a) 가 이 수를 줄일 가능성이 크다. 줄지 않으면 템포 표 대비 재조정이 필요하다(사용자 결정).
5. **내정 치적이 자연 성장을 잡는다.** 2% 문턱으로 완화만 했다. 월단평 순위가 내정 쪽으로 쏠리는지 W4 에서 본다.

## 6. 사용자 결정 필요

| # | 질문 | 추천 | 이유 |
|---|---|---|---|
| D1 | 함락 직후 수비를 어떻게 두는가 | **점령 군단을 해산하지 않고 그 省에 출전 상태로 남긴다.** 역포위 때 수비는 `city.defence` 에 그 省에 선 자기 세력 군단 병력을 더해 센다. 귀환은 주인이 명령할 때만 한다 | 새 수치를 짓지 않는다. 대안인 「공격 병력 일부를 수비로 전입」은 전입 비율을 새로 정해야 한다. 휘하 코드에는 주둔 모델이 따로 없다(`주둔` 0건). 그래서 출전 상태 유지가 가장 작은 변경이다 |
| D2 | 수도를 잃으면 | **남은 縣 중 인구가 가장 많은 縣으로 수도를 옮긴다. 縣이 0 이면 멸망 정산(기존 멸망 경로 재사용)** | 보급망 뿌리가 곧 재정망 뿌리라(§9.2) 수도가 없으면 세력 전체가 고립 판정된다 |
| D3 | 풀 수 없는 조우의 재시도 순 수 N | **2순**(PROVISIONAL) | 1순은 일시 사유를 구분할 여지가 없다. 길면 행군이 오래 묶인다 |
| D4 | 豫州 시나리오를 운영 목록에 등록하는가 | **등록한다(pep 전환 시 첫 시나리오 후보)** | S3 통과가 pep 전환 조건이다. 등록하지 않으면 전환 대상 시나리오가 없다 |
| D5 | PROVISIONAL 수치 3파일 확정 시점 | **W4 실측 뒤 한 번에 검토** | 실스택 순별 사건표가 판단 자료가 된다 |

## 7. 부록 A — PR #868 jvm 실패 4건 원인

CI run `35821661067`(head `a3f75821`)을 읽기 전용으로 조사했다. `e6e58ef3`·`2e6339cf` 는 네 테스트 파일과 Dispatch·Court 코드를 건드리지 않았다. 그러므로 새 head 에서도 재현될 것으로 본다.

| # | 테스트 | 실패 지점 | 원인 | 판정 | 수정 |
|---|---|---|---|---|---|
| 1 | `HwihaCourtApiIT` | `:132` `assertEquals(29, renownCapacity)` → 실제 30 | `91398eaa` 가 거절 즉시 명망 −1 을 없애고 월단평 집계 −4 로 통일했다(2026-09-23 사용자 결정). `HwihaDispatchExecutor.kt:89-98` 은 이제 `DISPATCH_REFUSAL` 사건만 기록한다. 단위 테스트(`HwihaDispatchExecutorTest`)만 29→30 으로 고치고 이 IT 는 놓쳤다 | 기대값 낡음 | 30 으로 바꾸고, 이번 달 `hwihaRenownTally` 에 `DISPATCH_REFUSAL` 1건이 쌓였는지 단언을 더한다. 133–142행은 이번에 돌지 않았으므로 고친 뒤 다시 확인한다 |
| 2 | `HwihaDispatchPersistenceIT` | `:85` 같은 단언 | 1과 같다 | 기대값 낡음 | 30 으로 바꾼다. 「no second charge」는 이제 집계 1건이 cold reload·재응답 뒤에도 1건인지로 단언한다 |
| 3 | `HwihaS3PassChainIT` | `:56` `HwihaS3ChainSupport.run` 안에서 `IllegalStateException` | 48순 틱 도중에 던져 `assertChain` 까지 가지 못했다. 예외 메시지가 로그에 없다 | **미확정** | 아래 |
| 4 | `HwihaS3PassChainProbeIT` | `:55` 같은 지점 | 부곡이 없는 적색 짝도 같은 곳에서 던진다. 그러므로 전쟁 경로(행군·조우·공성·보급)가 아니라 두 테스트가 함께 지나는 경로다 | **미확정** | 아래 |

3·4 에 대해 확실한 것:
- 두 IT 는 `3f3a5ad4` 에서 **작성만 하고 한 번도 돌리지 않았다**(커밋 메시지). 병합 커밋 `e6279541`·`a3f75821` 도 컴파일하지 않았다. 그래서 병합 탓인지 처음부터 깨져 있었는지 가릴 수 없다.
- 짧은 형식 로그는 실제 예외 클래스를 찍는다. 따라서 `MilitarySupplyUnavailableException`(ISE 하위)은 제외된다. 평범한 `check`·`checkNotNull`·`error` 다.
- 이 IT 에서 처음 함께 도는 조합이 후보다. 조합은 네 가지다. ① ScenarioImporter 가 기본 SAMMO 월 이벤트를 휘하 월드에 넣는다(`ignoreDefaultEvents` 없음). ② 사람 장수를 raw SQL 로 넣고 inbox·ring 으로 출사를 예약한다. ③ 창고가 있는 縣 여러 곳에서 내정 경계·녹봉 `HwihaWarehouseNetwork` `check`·치적 창이 함께 돈다. ④ NPC 주공이 사람에게 발령하고 기한 경과로 수락한다. DB 없는 `HwihaYuzhouCampaignSimulationTest` 는 월 파이프라인·내정·녹봉·월단평·발령을 돌리지 않는다.
- 이 조합은 W2 (d) 의 경계 예외 감사 대상과 겹친다.

**W0 에 더하는 선행 작업.**
1. game-engine `tasks.test` 에 `testLogging { exceptionFormat = FULL }` 을 넣는다. CI jvm 잡은 실패하면 `build/reports/tests` 와 결과 XML 을 artifact 로 올린다. 지금은 CI 실패에서 예외 메시지를 얻을 방법이 없다(이 run 의 artifact 0건).
2. 적색 짝 `HwihaS3PassChainProbeIT` 하나만 로컬에서 돌려 스택을 받는다. 같은 worktree 를 쓰는 다른 세션이 없을 때 돌린다. gradle 을 겹쳐 띄우지 않는다.
3. **스택을 보기 전에는 3·4 의 수정 방향을 정하지 않는다.** 원인이 경계 코드의 `check` 라면 W2 (d) 규칙대로 「도달 불가 증명 또는 건너뛰기+기록」으로 정리한다. 원인이 시드면 `HwihaS3ChainSupport.seed` 를 고친다.
