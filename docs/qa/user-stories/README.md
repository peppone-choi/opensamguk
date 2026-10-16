# 역할별 유저 스토리 — 브라우저 QA 공통 실행 인덱스

> 상태: QA 실행용 문서. 이 문서와 역할별 5개 파일의 모든 스토리는 **NOT_RUN**이다.
> 기준: `peppone-choi/opensamguk` main `9293ac465ad89436f30620329e337cd07e52a945` (W1 PR #1633·A1 PR #1635 병합 뒤)
> 마지막 검토: 2026-10-16

이 폴더는 브라우저 QA 담당이 그대로 실행할 역할별 유저 스토리를 둔다. 기존 정본(`docs/admin/local-qa.md`, 휘하 재설계 spec, ADR)을 대체하지 않고 인용만 한다. 정본과 이 문서가 다르면 정본이 맞고, 이 문서를 고친다.

## 1. 파일과 역할

| 파일 | 역할 | 스토리 ID 접두 | 스토리 수 | 준비 계정 |
|---|---|---|---|---|
| [군주.md](군주.md) | 세력의 정점에 선 주공 | `US-LORD-` | 12 | `qa160alord`·`qa160blord`·`qa160clord` |
| [1차부하.md](1차부하.md) | 군주 휘하에 직접 든 사람 장수 | `US-SUB1-` | 9 | `qa160{a,b,c}manager`, `qa160{a,b,c}general1`·`general2` |
| [2차부하.md](2차부하.md) | 1차부하가 거느린 인물 | `US-SUB2-` | 8 | 사람 계정 없음. `manager`의 NPC 부장 2명을 관측 |
| [재야.md](재야.md) | 세력에 속하지 않은 장수 | `US-WAND-` | 12 | `qa160freewanderer1`~`4` |
| [운영자.md](운영자.md) | 게임 운영 권한(ADMIN) | `US-OPS-` | 9 | `qa160admin` |

모두 50개다. 이 가운데 실행해도 결과가 BLOCKED로 정해진 스토리(기획만·정본 충돌)는 `US-LORD-12`, `US-SUB1-09`의 기능 부분, `US-SUB2-07`, `US-SUB2-08`, `US-WAND-11`, `US-WAND-12`의 기능 부분이다. `US-LORD-10`의 실시간 조작 부분도 BLOCKED다.

계정 이름 규칙과 생성 절차의 정본은 `tools/qa/role-fixtures.py`(`ROLES`, `credentials`)와 [격리된 로컬 QA 서버](../../admin/local-qa.md) "계정·인물과 신분 준비"다.

### 1.1 위계 용어 (정본 대조)

- 위계는 **군주 → 봉신 주공 → 장수** 세 단계다. 군주·봉신 사이는 봉신 계약, 주공·장수 사이는 휘하다([휘하 재설계 spec](../../superpowers/specs/2026-09-17-general-and-retinue-campaign-redesign.md) §2.3).
- **장수가 직접 거느리는 인물은 NPC 부장만 둔다.** 사람 장수가 사람 장수를 거느리려면 주공이 되어야 한다(같은 §2.3).
- 2026-09-26 용어 교체로 상위 개념 「휘하」는 화면에서 **부(府)**, 쉬운 말로 「소속」이다(`docs/design/roadmap.md` 52행). 이 폴더는 화면 문구를 그대로 쓰고, 설명에는 「휘하」를 함께 쓴다.
- 이 폴더의 **1차부하**는 군주의 직속 휘하 사람 장수, **2차부하**는 1차부하가 거느린 인물이다. 현재 QA fixture에는 사람 2차부하가 없다(local-qa.md "비군주가 사람 부하를 가지지 않는 게임 규칙", "존재하지 않는 2차 사람 계층을 만들지 않는다").
- **정본 충돌 1건:** main의 D164(PR #1552)는 재야가 비군주 사람 장수의 직속으로 출사하는 길을 열었고 `docs/user/features.md`도 이를 안내한다. 이는 spec §2.3과 local-qa.md의 위 문장과 맞지 않는다. 이 폴더는 판정하지 않고 해당 스토리를 BLOCKED로 둔다. 자세한 대조는 [2차부하.md](2차부하.md) 「역할 정의와 정본 대조」에 있다.

## 2. 구현 상태 표기

각 스토리 머리에 다음 중 하나를 적는다. 근거 파일 없이 상태를 올리지 않는다.

| 표기 | 뜻 | QA 처리 |
|---|---|---|
| **구현됨** | 서버 접수·엔진 처리와 화면 입력이 main에 있다 | 실행 대상 |
| **서버만** | 서버·엔진은 있으나 화면 입력이 없다 | 브라우저 스토리는 BLOCKED. API 관측만 별도 기록 |
| **기획만** | 승인 spec·ADR에만 있다 | BLOCKED, 결함 아님 |
| **미결·미구현** | 사용자 결정이 없거나 수치가 미승인 | BLOCKED, 수치·규칙을 지어 넣지 않는다 |
| **draft PR** | 열린 draft PR에만 있다 | main QA 대상 아님. 후보 QA는 local-qa.md "PR 후보 채널"로 따로 |

미승인 결정은 수치를 쓰지 않는다. 특히 **Q5-b**(여러 세력이 섞인 수비에서 「공격 대상 세력」·「동맹」 정의)와 **Q7 계열**(전투 군량 소비량·지속 수치)은 사용자 미답이다. 두 질문은 저장소 밖 기획 인계(C-1′ 재개 인계)에서 열려 있고 저장소에는 확정 문서가 없다. 해당 동작은 「미결」로만 적는다. 선전포고의 24개월·무비용·불가침 중 허용은 2026-10-16 사용자 **잠정** 결정이다(`logic/src/main/kotlin/opensamguk/logic/input/DeclareWarPolicy.kt`). 이 폴더는 그 값을 관측 기준으로만 쓰고 확정 정책으로 쓰지 않는다.

## 3. 실행 환경과 금지 사항

- **허용 환경:** 격리 QA 서버 `qa160`(UI `http://127.0.0.1:18300`, world `160`, Docker 프로젝트 `opensamguk-qa160`) 또는 trusted-main 단일 job 후보 QA(`.github/workflows/run-pr-candidate-qa.yml`, GitHub-hosted `ubuntu-24.04`). 절차는 local-qa.md를 따른다.
- **seed:** `scenario_3190`, `RESET_TURNTERM=2`(120초), `RESET_MAXGENERAL=50`, `RESET_BLOCK_GENERAL_CREATE=0`. 실제 `world_state.tick_seconds`와 턴 진행을 확인하기 전에는 턴 관련 스토리를 실행하지 않는다.
- **신분 fixture:** 국가 A·B·C 각각 군주·중간관리(`manager`)·일반 2명, 재야 4명. A–B는 교전(코드 0), A–C는 불가침(코드 7). 별도 동맹 코드가 없어 불가침을 동맹 PASS로 쓰지 않는다.
- **금지:** 운영 `sam.peppone.dev`·실사용자 계정 사용, 운영 서버 변경, 게임 서버 초기화·승격, GCP 전원 조작, 새 계정에 ADMIN 부여(일반 USER를 SQL로 승격 포함), 이 VPS에서의 이미지 빌드. 운영자 스토리의 인프라 항목은 실행하지 않고 범위 밖으로 기록한다.

## 4. 공통 절차

### 4.1 로그인

1. 새 브라우저 컨텍스트(서비스 워커 차단)를 연다.
2. `http://127.0.0.1:18300/login?next=/game/qa160`으로 간다.
3. `input[name="username"]`·`input[name="password"]`에 custody 계정을 넣고 「로그인」 버튼을 누른다.
4. 기대: `POST /api/auth/login` 200, `sam_access` 쿠키가 httpOnly, `GET /api/auth/me`의 `role`이 USER(운영자는 ADMIN), 주소가 `/game/qa160`.

이 절차는 `web/game/e2e/qa160-live.spec.ts`의 `login()`(81–110행)과 같다. 비밀번호·토큰은 화면 캡처·로그·결과에 남기지 않는다.

게임 주소는 `/game/<서버 ID>/<경로>`다(`web/game/middleware.ts` 185–193행). 이 폴더는 `/game/qa160/...`으로 적는다. 명령 흐름을 주소로 열 때 `slot`은 **1–12**(01순 = `slot=1`)이고, `slot=0`은 「순 지정 없음」으로 읽혀 다음 빈 순이 골라진다(`web/game/lib/command-flow/url.ts` 28–29행). 서버 쪽 `turnIdx`는 0–11이다.

### 4.2 예약 입력 관측 (모든 역할 공통)

장수 행동은 명령 목록 12순(`turnIdx` 0–11)에 예약하고 자기 턴 시각에 실행된다(spec §3·§4). 하나의 예약은 다음 순서로 관측한다. 앞 단계 성공을 뒤 단계 성공으로 쓰지 않는다.

| 단계 | 관측 | 근거 |
|---|---|---|
| 접수 | 예약 POST 202와 `requestId` | qa160-live.spec.ts 274–279행 |
| 저장 | `reserved-commands`의 해당 슬롯 `revision`이 접수 결과와 같다 | 같은 파일 289–291행 |
| 실행 | `command/result/{requestId}`가 `RESOLVED`, `ok: true` | 같은 파일 294–307행 |
| 반영 | 장수 상태 변화(예: 견문의 경험·헌신 증가), 턴 시각 변화, 해당 슬롯 비움, 장수 기록 1건 이상 | 같은 파일 316–335행 |
| 재접속 | 새로고침 뒤 `front-info`와 `reserved-commands`가 같은 결과 | 같은 파일 336–342행 |

`reservationAccepted`·`PENDING`·HTTP 200/202는 접수일 뿐 실행 완료가 아니다(local-qa.md 243–244행). 실행 시점에 조건이 맞지 않는 입력은 비용 없이 무효가 되고 사유를 남긴다(spec §4 마지막 항, 휘하 입력은 장수 기록 `INPUT_REJECTED`).

### 4.3 공통 검증 축

모든 스토리는 아래 여섯 축 중 해당하는 것을 「검증」 표에 채운다. 해당 없음은 이유와 함께 `N/A`로 쓴다.

| 축 | 확인할 것 |
|---|---|
| 권한 | 본인 장수만 조작된다. 남의 장수·다른 세력·권한 없는 입력은 화면에서 막히거나 서버가 403/거절 code를 돌려준다 |
| 비용 | 입력이 쓰는 자원·명망·충성 변화가 실행 뒤에만 반영되고, 거절이면 변화가 없다 |
| 턴 | 접수 순이 아니라 자기 턴 시각·순 경계·월 경계 중 정본이 정한 때에 실행된다 |
| 영속성 | 실행 결과가 API readback과 새로고침 뒤 화면에 같다 |
| 재접속 | 로그아웃·재로그인 또는 다른 기기(모바일 프로필)에서 같은 상태가 보인다 |
| 중복실행 | 같은 버튼 연타·같은 슬롯 재예약·뒤로 가기 재제출이 두 번 실행되지 않는다. 보이는 결과(거절 code, 덮어쓰기, 무시)를 그대로 적는다 |

## 5. 증거 규약

- **위치:** 증거는 실행 담당의 private custody 아래 `qa-evidence/<storyId>/`에 둔다. 공개 업로드는 local-qa.md 268–272행의 허용 목록(정제 JSON 등)만이며, 화면 캡처·DOM·trace·HAR·영상은 공개 artifact에 올리지 않는다.
- **이름:** `<storyId>-<단계번호>-<desktop|mobile>.png`, API 관측은 `<storyId>-<단계번호>.json`.
- **화면 캡처:** 로그인 입력 화면, 비밀번호가 보이는 화면, 쿠키·토큰이 보이는 개발자 도구는 찍지 않는다. 계정 이름은 역할명(`A-lord` 등)으로 바꿔 적는다.
- **관측 증거:** HTTP method·경로·status, 응답의 `status`·`code`·`requestId`, `front-info`의 `year`·`month`·`turnPhase`, 실행 시각(UTC). 응답 원문 전체나 토큰은 남기지 않는다.
- **DB readback:** 브라우저로 볼 수 없는 값(예: `general_retainers`, `general.meta`)은 QA 운영자가 같은 world의 읽기 전용 SQL로 확인하고 질의와 결과 행만 남긴다. 쓰기 SQL로 상태를 만들지 않는다(fixture 단계 제외).

## 6. 결과 양식

각 스토리 실행마다 아래 블록을 한 번 채운다. 데스크톱·모바일은 따로 적는다.

```text
storyId:        US-XXXX-NN
환경:           qa160 | hosted-candidate (receipt run id)
앱 커밋:        <40자 source sha>
시험기 커밋:    <40자 main sha>
기기:           desktop | mobile
계정 역할:      A-lord 등 (계정 이름·비밀번호 금지)
결과:           PASS | FAIL | BLOCKED | NOT_RUN
판정 근거:      단계별 증거 파일 경로 목록
BLOCKED 사유:   준비 불가 조건 또는 미구현 근거 파일:줄
실행 시각(UTC): 시작 ~ 끝
```

- **PASS:** 「기대 결과」의 모든 Then과 해당 검증 축이 실제 화면·readback으로 확인됐다.
- **FAIL:** 하나라도 다르다. 아래 결함 재현 양식이 필수다.
- **BLOCKED:** 준비 조건을 만들 수 없거나 기능이 「서버만·기획만·미결·draft PR」이다. 결함으로 올리지 않는다.
- **NOT_RUN:** 실행하지 않았다. 정적 검사·CI·smoke(mock 응답) 통과는 NOT_RUN을 바꾸지 않는다.

## 7. 결함 재현 양식 (FAIL 필수)

```text
결함 제목:      [storyId] 한 줄 요약
재현 단계:      1. … 2. … (계정 역할, 화면 경로, 누른 버튼 문구)
기대:           스토리의 Then 문장
실제:           화면 문구·HTTP status·code·requestId
재현 횟수:      n/m
첫 발견 커밋:   <sha>
증거:           파일 경로
관련 정본:      spec·코드 파일:줄
기존 이슈 대조: 같은 증상의 열린 이슈/PR 번호 또는 "없음"
```

결함은 기존 담당·티켓과 대조한 뒤 QA-FIX로 넘긴다. 수정 PR 병합 뒤 해당 main 이미지에서 같은 스토리를 다시 실행한다(local-qa.md "실제 기능 판정").

## 8. 기준 시점의 PR 상태

| 묶음 | PR | 상태(2026-10-16 확인) | 이 폴더에서의 처리 |
|---|---|---|---|
| W1 선전포고 | #1633 | main 병합 | 군주 스토리 실행 대상 |
| A1 전환 ADR | #1635 | main 병합(문서) | 판정 근거로만 인용 |
| C-1′ 조우 origin | #1638 | draft | main QA 대상 아님 |
| C-3a 합류 배치 이동 | #1634 | draft | main QA 대상 아님 |
| A2 전술 아트 팩·판 렌더러 v2 | #1637 | draft(기능 플래그 기본 끔) | main QA 대상 아님 |
| A5 전술 artpack 도구 | #1639 | draft | main QA 대상 아님 |
| A3 전략 지도 Projection | #1636 | draft | main QA 대상 아님 |
| 작전실 읽기 범위 전환 제출 검증 | #1622 | 열림(draft 아님) | 병합 전 main QA 대상 아님 |
| QA S16 진단 분리 | #1626 | 열림(draft 아님) | 시험 도구 변경. 스토리 기대값에 영향 없음 |
| QA S24 세계 시각 계약 | #1632 | draft | 같음 |
| 방랑 세력 해산 정리 | #1608 | draft | `US-WAND-12` BLOCKED 근거 |

draft PR의 CI 통과는 main 동작이 아니다. 병합 뒤 해당 스토리를 실행 대상으로 바꾼다.

## 9. 실행 순서

일부 스토리는 fixture 상태를 바꾼다. 아래 순서를 지키고, 상태를 바꾸는 스토리는 그 계정의 다른 스토리를 마친 뒤 실행한다.

1. 읽기·권한: `US-OPS-01`, `US-LORD-01`, `US-SUB1-01`, `US-SUB1-07`, `US-SUB2-01`, `US-SUB2-02`, `US-WAND-01`, `US-WAND-07`
2. 개인 행동: `US-SUB1-02`·`03`·`05`·`06`, `US-WAND-02`·`03`·`06`, `US-SUB2-03`·`05`
3. 군주 조정과 교차 역할: `US-LORD-02` → `US-LORD-03`·`04`·`05`, `US-LORD-06` ↔ `US-SUB1-04`, `US-LORD-07`·`08`, `US-SUB2-04`
4. 서신·게시판·운영: `US-WAND-08`·`09`, `US-OPS-02`·`04`·`05`·`07`·`08`
5. 상태를 바꾸는 스토리: `US-WAND-04`(재야1 출사), `US-SUB2-06`(재야3 출사), `US-WAND-05`(재야2 거병), `US-LORD-10`(출병), `US-SUB1-08`(A-general2 하야), `US-OPS-03`(재야4 차단·해제), `US-LORD-09`(C 세력 천도·현 포기)
6. 전체 영향: `US-OPS-06`(턴 멈춤·재개), `US-OPS-09`(서버 상태, 마지막)
7. BLOCKED 확인: `US-LORD-11`·`12`, `US-SUB1-09`, `US-SUB2-07`·`08`, `US-WAND-10`(추가 계정 허용이 없을 때)·`11`·`12`

QA 정리 실패(`CLEANUP_FAILED`)가 나면 전체 QA는 PASS가 될 수 없다(local-qa.md 266행).
