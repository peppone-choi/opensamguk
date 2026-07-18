# OPENSAM-90·91·102·109·113 실행 계약

- **status:** `approved/in-progress`
- **implementation authorization:** `A0 + A1 bounded lanes only`
- **root role:** `orchestrator-only`
- **external state:** Jira/GitHub 상태 변경 금지
- **계약일:** 2026-07-17

> **현재 상태: A0·A1 APPROVED / IN PROGRESS.** 5개 bounded lane의 착수와 아래 A1 권고안만 승인됐다. A2·A3·A4·A5는 승인되지 않았으며 commit/push/PR/deploy와 Jira/GitHub 변경은 계속 차단한다.

## 0. 2026-07-17 승인 기록

- **승인 증거:** 사용자 메시지 `자, 시작!`
- **승인 해석:** 사용자가 이 계약과 아래 A1 권고안을 제시받은 직후 보낸 직접 착수 지시로서 A0 전체와 A1-1~9 권고안을 승인한 것으로 기록한다.
- **사용자 제공 source URL:** `https://wikiwiki.jp/sangokushi14/`
- **승인 범위:** A0·A1만 승인. A2 산출물 검토, A3 concept 선택, A4 commit/push/PR, A5 deploy는 각각 별도 승인 전까지 차단한다.
- **외부 상태:** 승인 기록 시점에 Jira/GitHub/PR/deploy 상태는 변경하지 않았다.
- **OPENSAM-102 추가 범위 승인 (2026-07-17):** 사용자 원문 `도시의 경우 원본 이미지 내에서 좌표 뽑아야 할거고, 특히, 소거점의 경우도 좌표 뽑아야 할걸?`에 따라 원본 지도 이미지에서 도시와 소거점(小拠点) 좌표를 추출하는 research-only 범위를 승인했다. 이 승인은 OPENSAM-103/105의 runtime builder·asset 구현이나 원본 이미지의 repo 번들을 승인하지 않는다.

### 승인된 A1 권고안

| A1 | 승인 결정 | 경계와 이유 |
|---:|---|---|
| 1 | **local Docker volume** | configured storage root 아래에서만 사용한다. compose/nginx 같은 공유 파일은 사전 조정 후 single writer에게만 배정한다. |
| 2 | 전용 DB `profile_icon_changed_at` | generic `updatedAt`이나 PHP filename date suffix에 변경 제한을 결합하지 않는다. |
| 3 | **safe physical delete** | canonical root·path traversal·symlink 검증과 DB/file compensation을 통과한 파일만 unlink하며, 실패 시 부분 성공과 orphan을 남기지 않는다. |
| 4 | **AVIF/WebP 실제 decoder** | JPEG/PNG/GIF와 함께 decode 결과를 검증한다. 선택 dependency의 정확한 version/license와 DoS 경계는 A2 증거에 포함한다. |
| 5 | **PostgreSQL Testcontainers** | 하루 1회 제한과 동시성은 실 PostgreSQL로 증명한다. H2-only 또는 Docker skip은 A2 합격 증거가 아니다. |
| 6 | **provenance-cleared NPC data only** | `https://wikiwiki.jp/sangokushi14/`를 source lead로 삼되 provenance·license·redistribution이 확인된 항목만 사용 가능 풀에 넣고, 불명확한 항목은 제외한다. |
| 7 | cleared catalog의 결정적 schema/count | stable ID, 결정적 파일명, provenance, license, redistribution, fingerprint, fallback을 필수화한다. 목표 수량은 검증을 통과한 eligible set 전체이며 그 expected count를 테스트로 고정한다. 근거 전 raw 수량을 승인 수량으로 날조하지 않는다. |
| 8 | cleared data/assets만 repo bundle 허용 | 재배포 근거가 없는 항목은 research metadata로만 남기고 runtime allowlist·bundle·deploy에서 제외한다. |
| 9 | v1 parity 고정 + v2 catalog-only additive | 이번 wave는 catalog/allowlist까지만 허용한다. gameplay roster는 `96 + 103 + 98 + 105 → 104 seed/parity gate`와 별도 승인을 통과하기 전 활성화하지 않는다. |

## 1. 계약 불변식

1. **A0 전 무실행:** 전체 계약 승인 전에는 코드·연구·디자인·테스트·골든·외부 상태 산출물을 만들지 않는다.
2. **권한 분리:** A0는 작업 착수만 허용한다. commit/push/PR은 A4, deploy는 A5의 별도 명시 승인이 필요하다.
3. **패러티 보호:** 테스트·골든·검증 기준을 약화하거나, 캡처되지 않은 값을 골든으로 만들거나, 실패를 skip 처리하지 않는다.
4. **정보 보호:** secret, token, PII, `.env*`를 읽거나 출력하지 않는다. 경로·스택·토큰·PII가 오류 응답이나 로그에 노출되지 않게 한다.
5. **외부 상태 보호:** Jira/GitHub의 상태·본문·라벨·댓글·링크·PR을 변경하지 않는다. 별도 외부-write 승인 없이는 Jira 티켓 생성도 금지한다.
6. **인식론 표기:** 모든 산출물은 다음을 분리한다.
   - `[사실]`: 직접 확인한 코드, 캡처, 원문, URL, 테스트 결과.
   - `[추론]`: 사실에서 도출한 해석. 근거를 함께 적는다.
   - `[UNKNOWN]`: 확인되지 않은 키, 경로, 라이선스, 동작 또는 수치. 추측으로 채우지 않는다.
7. **범위 고정:** 아래 비범위를 조용히 끌어들이지 않는다. 새 범위는 오케스트레이터가 영향과 승인 필요성을 보고한 뒤 사용자 승인을 받아야 한다.

## 2. 입력 상태와 소스 매핑

아래는 이 계약에 제공된 입력 사실이며, A2에서 각 담당자가 원문 링크와 현재 상태를 다시 증빙한다.

| 작업 | Jira | GitHub | 우선순위 | 현재 상태 | 미머지 PR |
|---|---:|---:|---|---|---:|
| 초상 경로 통일 | OPENSAM-90 | #232 | HIGH | Jira `To Do`, GH `open` | 0 |
| 프로필 아이콘 API + NPC 풀 | OPENSAM-91 | #233 | HIGH | Jira `To Do`, GH `open` | 0 |
| RTK 지도 소스 조사 | OPENSAM-102 | #245 | MED | Jira `To Do`, GH `open` | 0 |
| RTK 시스템 후보 조사 | OPENSAM-109 | #252 | MED | Jira `To Do`, GH `open` | 0 |
| UI 진단·시안 | OPENSAM-113 | #256 | MED | Jira `To Do`, GH `open` | 0 |

`91b`와 직접 중복되는 "NPC 풀" 티켓은 `0`건이다. 사용자의 명시 결정에 따라 91b를 OPENSAM-91의 umbrella 하위 범위로 둔다. 연계 사실은 다음과 같으며 새 키를 날조하거나 외부 티켓을 만들지 않는다.

| 연계 | 확인된 역할 | 91b 경계 |
|---|---|---|
| OPENSAM-96 / #239 | RTK14 약 1,039명·RTK8R 약 900명의 portrait/stat sourcing | source/provenance/IP 입력; 완료를 가정하지 않음 |
| OPENSAM-104 / #247 | 1차 152명 scenario roster builder | gameplay roster 활성화의 후속 wave |
| OPENSAM-103 / #246 | roster/spec 선행 계약 | 후속 wave prerequisite |
| OPENSAM-98 / #241 | stable keying 선행 계약 | 후속 wave prerequisite |
| OPENSAM-105 | city contract 선행 계약 | GitHub 키는 `[UNKNOWN]`; 날조 금지 |
| OPENSAM-95 / #237 | 기존 1,678 초상 관련 1건 | 신규 NPC 풀 티켓이 아님 |
| OPENSAM-100 / #243 | CDN 범위 | NPC catalog/roster 범위가 아님 |

## 3. 승인 게이트와 의존 그래프

| 게이트 | 승인 내용 | 통과 증거 |
|---|---|---|
| A0 | 이 계약 전체와 5개 bounded lane 착수 | 사용자의 명시 문구 |
| A1 | 특히 OPENSAM-91/91b의 저장·시간·삭제·디코더·동시성·소스/IP·수량·번들·패러티 결정 | 결정표의 모든 항목에 선택과 이유 기록 |
| A2 | 각 산출물의 근거, 테스트, 보안/IP 결과와 독립 검토 | lane별 증거 묶음 + verifier 승인 + `fix-required=0` |
| A3 | OPENSAM-113의 시안 1개를 사용자가 선택 | 선택한 concept ID와 허용 변경 기록 |
| A4 | commit/push/PR | 정확한 대상·브랜치·base·행위별 사용자 승인 |
| A5 | deploy | 환경·이미지/커밋·롤백 계획에 대한 사용자 승인 |

```text
A0
├─ OPENSAM-90 ────────────────┐
├─ A1 → OPENSAM-91/91b catalog ┤
│       └─ 96 + 103 + 98 + 105 → 104 gameplay roster
├─ OPENSAM-102 → 103 → 105 ───┤
├─ OPENSAM-109 ────────────────┤→ A2 → A4 → A5
└─ OPENSAM-113 → A3 → 114/115 ┘
```

- OPENSAM-90의 `imgsvr=1`은 serving 작업 OPENSAM-93 전까지 default 처리한다.
- OPENSAM-91은 UI 92, serving 93, engine 94보다 앞선 API/저장 계약이다. 91b의 catalog/allowlist 확대는 포함하지만 gameplay roster 활성화는 96/103/98/105와 seed/parity gate를 통과한 OPENSAM-104 하위 wave다.
- OPENSAM-102는 `102 → 103 → 105`의 조사 선행 조건이다. 102가 103/105의 구현을 승인하지 않는다.
- OPENSAM-113은 A3 사용자 선택 전 114/115로 진행할 수 없다.

## 4. OPENSAM-90 / GitHub #232 — gateway 초상 경로 통일

### 목표

gateway account와 lobby가 동일한 helper로 공유 초상을 해석하고, 누락·오류에도 유한한 default fallback을 렌더한다.

### 범위

- 기본 공유 경로를 `icons/<code>.jpg`로 통일한다.
- 업로드로 기록된 canonical 확장자는 강제로 `.jpg`로 바꾸지 않고 유지한다.
- picture 누락, 알 수 없는 code, 로드 실패에 동일한 default를 사용한다.
- OPENSAM-93 전까지 `imgsvr=1`은 외부 serving을 시도하지 않고 default로 처리한다.
- `<img>`의 `onError`는 이미 fallback인지 확인하는 guard를 두어 재귀 오류 루프와 반복 상태 갱신을 막는다.
- account와 lobby의 정상·누락·`imgsvr=1`·오류 fallback 테스트를 추가한다.

### 비범위

- backend API 또는 DB 변경
- asset 업로드, 저장소, nginx serving
- game frontend helper 변경
- OPENSAM-93 serving 및 OPENSAM-95 후속 범위

### 예상 파일·산출물

- `web/gateway/lib/portrait.ts`
- account 화면의 초상 렌더링 파일
- lobby 화면의 초상 렌더링 파일
- `web/gateway`의 portrait/account/lobby 테스트
- 테스트 결과와 브라우저 account/lobby 증거

정확한 account/lobby 파일명은 구현 lane이 A0 후 현재 라우트를 확인해 `[사실]`로 기록한다. 확인 전 경로를 발명하지 않는다.

### 완료 기준

- 두 화면이 같은 helper와 같은 fallback 규칙을 사용한다.
- 기존 canonical 확장자를 보존하고, 기본 code만 `.jpg` 규칙을 사용한다.
- `imgsvr=1`, missing, 404에서 깨진 이미지나 무한 `onError`가 없다.
- 타입검사·테스트·production build가 모두 성공한다.
- 인증된 account와 lobby에서 DOM `src`, 네트워크 요청, fallback 화면을 캡처한다.

### 정확한 검증 명령

```bash
cd web/gateway && corepack pnpm typecheck
cd web/gateway && corepack pnpm test
cd web/gateway && corepack pnpm build
```

브라우저 검증은 account/lobby 각각에서 정상 초상, picture 없음, `imgsvr=1`, 404를 재현하고 DOM `src`·요청 URL/상태·스크린샷을 같은 run ID로 남긴다. 이후 초상 버그는 반드시 `opensamguk-php-oracle → webapp-testing → systematic-debugging → loop-engineering` 순서로 처리한다.

### 승인 지점·의존성

- A0 후 구현 가능, A2 전 완료 주장 금지, A4 전 commit/push/PR 금지.
- OPENSAM-93 전 `imgsvr=1` default 규칙을 바꾸려면 별도 승인 필요.

## 5. OPENSAM-91 / GitHub #233 — ProfileIcon API와 91b NPC 공용 풀

### 목표

인증된 사용자가 안전한 multipart API로 자신의 프로필 아이콘을 하루 한 번 원자적으로 업로드/삭제하고, 같은 shared-icon 계약이 승인된 NPC 공용 초상/데이터 풀을 안정적으로 참조하게 한다.

### 91a 범위 — 업로드·삭제

- gateway-api의 authenticated multipart upload/delete endpoint.
- 요청 principal 본인의 프로필만 변경하며 대상 user ID를 신뢰하지 않는다.
- AVIF/WebP/JPEG/PNG/GIF를 실제 decoder로 해석한 뒤 검증한다.
- decoded 파일 크기 `<= 51200B`, 정사각형, 가로·세로 `64..128`을 모두 만족해야 한다.
- 서버가 CSPRNG 기반 `8`자리 lowercase hex 파일명과 decoder가 판정한 canonical 확장자를 생성한다. 클라이언트 파일명·경로·확장자를 저장 키로 사용하지 않는다.
- configured storage만 사용하고 `users.picture`·`users.imgsvr`를 갱신한다.
- upload와 delete를 모두 1 calendar day당 1회 변경으로 원자적으로 제한한다.
- JSON profile setter는 공유 allowlist에 명시된 필드만 받으며 임의 필드명/반사형 setter를 금지한다.
- DB 실패, 파일 move 실패, delete 실패의 compensation과 orphan cleanup 정책을 테스트한다.
- 동시 요청에서 정확히 하나만 성공하고 제한이 우회되지 않음을 PostgreSQL 동시성 테스트로 증명한다.

### 91b 범위 — NPC 공용 초상/데이터 풀 확장

- 업로드 API와 같은 shared-icon allowlist/catalog 계약을 사용한다.
- 이 wave는 사진/데이터 catalog·allowlist의 확대와 검증까지만 포함한다. gameplay roster 활성화는 하지 않는다.
- NPC마다 안정적 ID와 결정적 파일명을 부여하고 rename/reorder로 의미가 바뀌지 않게 한다.
- catalog의 ID 중복, 파일 누락, 지원하지 않는 확장자, 고아 파일, fallback 누락을 validation에서 실패시킨다.
- 각 항목에 source provenance, license, redistribution 허용 여부를 기록한다. 근거가 없거나 IP가 불명확한 자산은 번들·배포하지 않는다.
- 승인된 catalog expected count를 테스트로 고정하고, 동일 입력 seed의 validation fixture가 동일한 ID/초상 선택 결과를 만드는지 검증한다. 실제 scenario roster에는 주입하지 않는다.
- 기존 시나리오와 legacy v1 parity의 ID·RNG draw·AI·한글 로그·flush 결과를 변경하지 않도록 격리한다. v2 additive 여부는 A1 결정 전 구현하지 않는다.
- 임의 외부 URL, runtime 외부 API/CDN 의존, 무허가·재배포 불명 자산을 금지한다.
- 후속 gameplay roster 활성화는 OPENSAM-96 source/IP, OPENSAM-103/#246 spec, OPENSAM-98/#241 keying, OPENSAM-105 city contract가 준비되고 OPENSAM-104/#247의 seed/parity gate를 통과한 뒤에만 가능하다.

### 비범위

- Next.js 업로드 UI(OPENSAM-92)
- 파일 serving(OPENSAM-93)
- game-engine 소비 변경(OPENSAM-94)
- 일반 media 관리 시스템 또는 S3 도입(단, A1에서 명시 선택한 저장 방식 제외)
- 기존 시나리오/legacy 결과 재작성
- NPC의 실제 scenario/gameplay roster 활성화
- IP 불명 자산 수집·생성·번들

### 예상 파일·산출물

- gateway-api controller, service, request/response DTO
- user/profile entity·repository 변경과 Flyway migration
- storage/decoder/profile-icon configuration
- shared JSON setter allowlist
- 인증·파일검증·compensation·동시성·401 보안 테스트
- shared-icon allowlist/catalog와 catalog-only seed validator 및 결정성 테스트
- 로컬 저장을 선택한 경우에만 compose volume/nginx 설정

정확한 class/package, catalog 소유 모듈, migration 번호, NPC seed 파일 경로는 현재 근거가 없는 `[UNKNOWN]`이다. A0 후 기존 책임 경계를 확인해 소유권을 등록하고, foundation 한 곳만 writer로 정한다.

### A1 필수 결정 — 하나라도 비면 91/91b BLOCKED

1. **저장:** local Docker volume을 채택할지, OPENSAM-82/S3 결정을 기다릴지.
2. **변경 시각:** PHP filename query의 date suffix를 따를지, 전용 DB `profile_icon_changed_at`을 둘지. generic `updatedAt` 사용은 금지.
3. **삭제 의미:** Jira의 physical unlink를 따를지, PHP의 no-unlink를 의도적 divergence로 보존할지.
4. **decoder:** AVIF/WebP decoder dependency와 버전·라이선스를 승인할지.
5. **동시성:** PostgreSQL/Testcontainers로 원자성을 증명할지, H2-only를 허용할지. 이 계약의 권고 합격선은 PostgreSQL이며 H2-only는 보안/동시성 완료로 간주하지 않는다.
6. **NPC source/IP:** 허용 source, license, redistribution 등급과 제외 기준.
7. **NPC schema/count:** 목표 수량과 필수 포함 필드(ID, 파일명, provenance 등)의 정확한 목록.
8. **번들:** 승인 자산을 repo에 번들할 수 있는지, metadata만 둘지.
9. **패러티:** v1 parity 고정과 v2 additive 확장 중 적용 트랙과 격리 경계. gameplay roster 활성화는 이 wave에서 금지하며 후속 104 gate의 승인 조건도 적는다.

### 보안 완료 기준

- principal-only, unauthenticated `401`, 타 사용자 변경 불가.
- traversal, symlink, MIME spoof, polyglot, decompression/decoder DoS, oversized body, TOCTOU를 거부한다.
- 응답·로그에 filesystem path, stack trace, token, PII가 없다.
- 파일과 DB의 부분 성공을 보상하며 임시 파일·고아 파일이 남지 않는다.
- 두 동시 변경 중 정확히 하나만 성공하고 calendar-day 제한 상태가 보존된다.
- NPC catalog는 중복·누락·fallback·expected count·deterministic seed를 검증한다.
- provenance/license/redistribution 미승인 항목은 catalog의 사용 가능 풀에 들어가지 않는다.
- 기존 scenario의 RNG draw 순서·횟수·인자, AI 선택, byte-패러티 로그, ChangeRecorder/JDBC flush 결과가 기준선과 동일하다.

### 정확한 검증 명령

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:gateway-api:test --tests '*ProfileIcon*' --rerun-tasks
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:gateway-api:test --rerun-tasks
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test :infra:test :app:game-engine:test --rerun-tasks
tools/parity/gate.sh backend
tools/agent-system/check.py --strict --base origin/main
scripts/agent/verify-changes.sh
```

compose/nginx/local volume이 바뀐 경우에만 추가 실행한다.

```bash
./tools/smoke.sh
```

각 Gradle 실행은 출력의 `BUILD SUCCESSFUL`을 확인하고 다음 명령이 결과를 찾지 않아야 한다.

```bash
! rg -n 'failures="[1-9]|errors="[1-9]' app/gateway-api/build/test-results/test -g '*.xml'
```

NPC 검증 테스트의 XML에도 expected count, duplicate/missing/fallback, 동일 seed 동일 결과 case가 존재해야 한다. Docker 미사용으로 PostgreSQL 동시성 테스트가 skip되면 A2는 통과하지 않는다.

### 승인 지점·의존성

- A0와 A1 전 구현 금지. A2에서 backend/security verifier와 독립 reviewer가 모두 승인해야 한다.
- 92/93/94의 구현을 이 작업에 섞지 않는다.
- 91b 직접 중복 티켓은 0건이며 OPENSAM-91 umbrella로 유지한다. 외부-write 승인 없이 별도 티켓을 생성하지 않는다.
- catalog/allowlist만 현재 wave다. gameplay roster 활성화는 `96 + 103/#246 + 98/#241 + 105 → 104/#247 → seed/parity gate` 순서의 별도 하위 wave다.

## 6. OPENSAM-102 / GitHub #245 — RTK14·RTK8R 지도 소스 커버리지 연구

### 목표

RTK14 기대 46도시와 RTK8R의 도시·소거점(小拠点)·인접·좌표·지도·region 정보를 재현 가능한 근거로 분류한다. 원본 지도 이미지에서 46개 도시와 관찰되는 모든 소거점/strongpoint의 픽셀 좌표를 추출해 재검토 가능한 원장으로 남기고, v1/v2 데이터 모델에 연결 가능한지 판정한다.

### 범위

- 각 필드를 `DIRECT / DERIVED / MANUAL / UNAVAILABLE` 중 하나로 분류.
- source URL/section, provenance, fingerprint, license, extractability, 접근일을 기록.
- RTK14 46도시 기대치와 실제 source coverage를 분리.
- RTK8R 도시, adjacency, coordinate, map, region을 각각 평가.
- 원본 지도 이미지는 repo 밖 임시 위치에만 취득하고, 각 source의 URL/section, provenance, license, 접근일, SHA-256, 원본 pixel dimensions를 기록한다. 원본 이미지는 repo에 복사·번들하지 않는다.
- 좌표계는 원본 이미지의 top-left를 `(0, 0)`으로 하고 `x`는 오른쪽, `y`는 아래쪽으로 증가하는 native pixel 좌표로 고정한다. crop/resize/보정본 좌표를 원본 좌표로 가장하지 않는다.
- RTK14 46개 도시 전부와 원본에서 관찰되는 모든 소거점(小拠点)/strongpoint를 좌표 원장에 기록한다. 각 행은 entity kind/name, `x`, `y`, source fingerprint, extraction method, confidence, reviewer 결과를 포함한다.
- 식별·좌표·분류가 불명확하거나 두 독립 검토가 합의하지 못한 행은 값을 추측하지 않고 `[UNKNOWN]`과 conflict 근거를 기록한다.
- 좌표 원장은 서로 독립된 2인이 원본 fingerprint와 dimensions가 같은 이미지를 기준으로 재검토한다. 두 검토의 방법과 판정, 불일치를 보고서에 남긴다.
- v1 `MapJson`과 v2 `PhysicalPlace`/`RouteCorridor` 대응표 작성.
- 근거 없는 값과 상충 자료는 `[UNKNOWN]`으로 유지.

### 비범위

- scraper, runtime coordinate builder, asset pipeline 구현
- 원본 지도/게임 image asset의 repo 저장·번들·배포(조사용 임시 취득만 허용)
- OPENSAM-103/105 구현
- RTK 역사증거 채택 승인

### 예상 파일·산출물

- `docs/superpowers/research/2026-07-17-opensam-102-rtk14-rtk8r-map-source-coverage.md`
- `docs/superpowers/research/2026-07-17-opensam-102-map-coordinate-ledger.csv`
- 보고서 안의 coverage matrix, provenance/fingerprint/license/SHA-256/dimensions 표, 좌표계·추출 방법·독립 2인 검토 기록, v1/v2 mapping, UNKNOWN/conflict 목록

### 완료 기준

- RTK14 46개 기대 행과 RTK8R 5개 데이터 차원이 누락 없이 판정된다.
- coordinate ledger에 46개 도시가 각각 한 번씩 존재하고, 원본 이미지에서 관찰된 모든 소거점/strongpoint가 누락 없이 행으로 기록된다.
- 모든 좌표 행이 동일한 top-left native-pixel 좌표계, source SHA-256/dimensions/provenance, method, confidence를 갖는다.
- 두 독립 reviewer가 같은 source fingerprint를 기준으로 전 행을 검토하며, 불일치는 해소 근거 또는 `[UNKNOWN]`으로 남는다.
- 모든 DIRECT/DERIVED 주장에 정확한 source section/URL과 fingerprint가 있다.
- MANUAL에는 추출 절차와 2인 검토 지점, UNAVAILABLE에는 조사 범위와 중단 이유가 있다.
- 라이선스 불명은 research-only이며 번들 가능으로 승격하지 않는다.
- game-reference 트랙과 history/evidence 트랙을 분리한다.

### 정확한 검증 명령

```bash
test -s docs/superpowers/research/2026-07-17-opensam-102-rtk14-rtk8r-map-source-coverage.md
test -s docs/superpowers/research/2026-07-17-opensam-102-map-coordinate-ledger.csv
git diff --check -- docs/superpowers/research/2026-07-17-opensam-102-rtk14-rtk8r-map-source-coverage.md docs/superpowers/research/2026-07-17-opensam-102-map-coordinate-ledger.csv
rg -n 'DIRECT|DERIVED|MANUAL|UNAVAILABLE|provenance|fingerprint|SHA-256|dimensions|top-left|confidence|reviewer|MapJson|PhysicalPlace|RouteCorridor|UNKNOWN' docs/superpowers/research/2026-07-17-opensam-102-rtk14-rtk8r-map-source-coverage.md
rg -n 'city|small_base|strongpoint|x|y|source_sha256|source_width|source_height|method|confidence|review' docs/superpowers/research/2026-07-17-opensam-102-map-coordinate-ledger.csv
tools/agent-system/check.py
```

URL 감사는 병렬 요청 없이 문서 URL을 하나씩 요청하고 rate limit을 지킨다.

```bash
rg -o 'https?://[^ )>]+' docs/superpowers/research/2026-07-17-opensam-102-rtk14-rtk8r-map-source-coverage.md | sort -u | while IFS= read -r url; do curl --fail --location --retry 2 --retry-delay 2 --max-time 30 --user-agent 'opensamguk-research/1.0' "$url" | shasum -a 256; sleep 2; done
```

### 승인 지점·의존성

- A0 후 research-only 착수.
- A2에서 주 source, license/bundling, 46개 도시와 전체 관찰 소거점/strongpoint coverage, 좌표계·SHA-256·dimensions, manual extraction 독립 2인 검토, UNKNOWN 처리, game-reference/history 트랙 분리를 승인한다.
- 불명확한 라이선스는 research-only. `102 → 103 → 105`; 후속 착수는 별도 승인이다.
- 이 승인으로 만들어지는 tracked artifact는 위 report와 coordinate ledger뿐이다. runtime builder·asset·schema 반영은 OPENSAM-103/105의 별도 범위다.

## 7. OPENSAM-109 / GitHub #252 — RTK 시스템 후보 카탈로그 연구

### 목표

8개 축의 후보를 동일 schema와 정확한 근거로 비교하고, multiplayer 적합성을 분석해 `ADOPT / ADAPT / HOLD / REJECT`로 판정한다.

### 범위

문서는 정확히 다음 8개 `##` section을 갖는다.

1. 외교
2. 계략
3. 명품·보물
4. 관직·작위
5. 전투
6. 내정
7. 인사
8. 이벤트

각 candidate schema는 최소 `candidate_id`, 축, 게임/버전, exact evidence section, URL, license/IP grade, 관찰 사실, 추론, UNKNOWN/conflict, multiplayer authority, determinism, cadence, abuse surface, v1 영향, v2 additive 경계, 판정, 이유를 포함한다.

- OPENSAM-96 자료는 출발점일 뿐이며 그대로 정답으로 승격하지 않는다.
- OPENSAM-97~100의 완성을 가정하지 않는다.
- 상충 근거를 삭제하지 않고 conflict로 병기한다.

### 비범위

- 코드·schema·migration 구현
- Jira/GitHub 티켓 생성 또는 상태 변경
- asset 수집·번들
- v1 parity 변경
- 역사 근거의 공식 채택

### 예상 파일·산출물

- `docs/superpowers/research/2026-07-17-rtk-system-candidate-catalog.md` 한 개
- 8축 catalog, evidence/IP grade, multiplayer 분석, 판정표, UNKNOWN/conflicts, foundation order 제안

### 완료 기준

- 8개 section이 정확히 한 번씩 존재하고 모든 후보가 공통 schema를 채운다.
- evidence는 section/URL/license까지 추적 가능하다.
- authority/determinism/cadence/abuse를 분석하지 않은 후보는 ADOPT/ADAPT가 될 수 없다.
- 우선순위와 foundation order는 `[추론]`으로 표시한다.
- v2 additive 제안은 v1 parity 변경을 전제하지 않는다.

### 정확한 검증 명령

```bash
test -s docs/superpowers/research/2026-07-17-rtk-system-candidate-catalog.md
test "$(rg -c '^## (외교|계략|명품·보물|관직·작위|전투|내정|인사|이벤트)$' docs/superpowers/research/2026-07-17-rtk-system-candidate-catalog.md)" -eq 8
rg -n 'candidate_id|exact evidence|URL|license|authority|determinism|cadence|abuse|ADOPT|ADAPT|HOLD|REJECT|UNKNOWN|conflict' docs/superpowers/research/2026-07-17-rtk-system-candidate-catalog.md
git diff --check -- docs/superpowers/research/2026-07-17-rtk-system-candidate-catalog.md
tools/agent-system/check.py
```

URL 감사는 직렬로 수행한다.

```bash
rg -o 'https?://[^ )>]+' docs/superpowers/research/2026-07-17-rtk-system-candidate-catalog.md | sort -u | while IFS= read -r url; do curl --fail --location --retry 2 --retry-delay 2 --max-time 30 --user-agent 'opensamguk-research/1.0' "$url" | shasum -a 256; sleep 2; done
```

### 승인 지점·의존성

- A0 후 research-only 착수.
- A2에서 evidence/IP grade, 후보/우선순위, multiplayer adaptation/balance, v2 additive, foundation order를 각각 승인한다.
- Jira 생성은 별도 외부-write 승인 없이는 금지한다.

## 8. OPENSAM-113 / GitHub #256 — UI 진단과 concept 선택

### 목표

실제 gateway/game 내용과 상태를 보존한 채 현재 UI를 진단하고, 서로 실질적으로 다른 2~3개 concept를 비교해 사용자가 하나를 선택할 수 있게 한다.

### 범위

- desktop과 critical mobile에서 다음 baseline을 수집한다.
  - gateway: login, lobby
  - game: `GameChrome`, auction
- color, density, typography, spacing, hierarchy, component의 6차원 진단.
- 같은 실제 내용·데이터·loading/empty/error/disabled 상태를 사용한 materially different concept 2~3개.
- concept마다 대표 mockup 2개, palette, type scale, spacing scale, density, component treatment를 제시.
- concept 비교표와 accessibility(contrast, focus, keyboard, target size, reduced motion) 평가.

### 비범위

- CSS/TSX 코드 변경
- asset pipeline 변경
- OPENSAM-114 구현
- design system OPENSAM-115 구현
- backend, gating, deploy 변경

### 예상 파일·산출물

- tracked 문서: `docs/superpowers/research/2026-07-17-opensam-113-ui-diagnosis-and-concepts.md`
- mockup과 baseline screenshot: repo 밖 user-data 위치만 사용하고 문서에는 식별자/설명만 기록
- DOM/network/screenshot evidence manifest, 6차원 진단, 2~3 concept 비교표

### 완료 기준

- 네 baseline surface의 desktop/critical-mobile 상태가 실제 서버 응답과 일치한다.
- concept마다 같은 내용/상태를 써서 시각 언어만 비교 가능하다.
- concept마다 정확히 2개 mockup과 palette/type/spacing/density/components가 있다.
- accessibility 차이와 trade-off가 명시된다.
- 사용자가 A3에서 concept를 선택하기 전 114/115 구현이 시작되지 않는다.

### 정확한 검증 명령

```bash
cd web/gateway && corepack pnpm typecheck
cd web/game && corepack pnpm typecheck
cd web/game && corepack pnpm test
git diff --check -- docs/superpowers/research/2026-07-17-opensam-113-ui-diagnosis-and-concepts.md
tools/agent-system/check.py
./tools/smoke.sh
```

브라우저 verifier는 각 baseline에서 viewport, URL, DOM 핵심 텍스트/상태, network 요청/응답 상태, screenshot ID를 한 evidence row로 묶는다. 콘솔 오류, 인증 redirect, API 실패를 숨기지 않는다.

### 승인 지점·의존성

- A0 후 진단/시안만 가능.
- A2에서 baseline과 비교 가능성, 접근성 근거를 검토한다.
- A3는 사용자 concept 선택의 hard gate다. 선택 전 114/115 코드 작업 금지.

## 9. 승인 후 실행 모델

A0 이후에도 root는 `orchestrator-only`다. 직접 코딩, 문서 작성, 조사, 테스트, 브라우저 검증을 하지 않고 dispatch/reconcile/status만 수행한다.

### 5개 bounded producer lane

1. OPENSAM-90 frontend 구현자
2. OPENSAM-91/91b backend·catalog 구현자
3. OPENSAM-102 source 연구자
4. OPENSAM-109 system 연구자
5. OPENSAM-113 design 진단자

각 lane은 single-writer이며 disjoint worktree를 사용하거나 `.ai/ownership.md`에 비중첩 소유권을 등록한다. 공유 파일이 필요하면 foundation owner 한 명에게만 쓰기를 배정하고 나머지는 소비한다. 토큰과 조사 범위는 티켓 계약에 고정한다.

### 3개 verifier lane

1. **frontend/browser:** 90과 113의 typecheck/test/build, DOM/network/screenshot 증거 검증
2. **backend/security:** 91/91b의 원자성, PostgreSQL 동시성, 파일 보안, compensation, parity gate 검증
3. **research/source+design:** 102/109의 provenance/license/fingerprint와 113의 비교 공정성·접근성 검증

그 뒤 producer와 verifier에서 독립된 reviewer가 범위, 근거, 패러티, 보안, 운영 불변식을 검토한다. `fix-required`가 있으면 해당 owner에게 재현 조건과 허용 파일을 명시한 bounded follow-up 한 번을 보낸다. 같은 질문의 무제한 재조사나 무한 반복은 금지하고, 해소되지 않으면 `[UNKNOWN]` 또는 `BLOCKED`로 사용자에게 올린다.

## 10. 초기 계약 작성 단계 exit proof (historical)

이 절은 승인 전 최초 계약 작성 단계의 합격 조건이다. 2026-07-17 활성화 기록은 이 문서와 `.ai/current-state.md`, `.ai/ownership.md`를 의도적으로 갱신한다.

- agent-owned 변경은 이 문서 한 파일뿐이다.
- code와 `.ai/task.md`를 포함한 다른 tracked 파일을 수정하지 않았다.
- Jira/GitHub/PR/deploy 등 외부 상태를 변경하지 않았다.
- 기존 사용자 변경은 건드리지 않았으며, pre-dispatch snapshot과 비교해 이 lane의 delta만 판정한다.
- `git diff --check`와 `tools/agent-system/check.py`가 성공한다.
- 독립 reviewer가 `fix-required=0`으로 clear한다.

검증 명령:

```bash
git diff --name-only
git diff --check
tools/agent-system/check.py
```

위 명령은 오케스트레이터가 별도 verifier에게 맡긴다. 이 문서의 생성 자체가 A0, A2 또는 구현 승인을 뜻하지 않는다.

## 11. 사용자 승인 체크리스트

- [x] **A0 전체 승인** — 사용자 메시지 `자, 시작!`으로 5개 lane의 bounded 착수를 승인했다.
- [x] **A1-1~5** — local volume, 전용 변경 시각, safe physical delete, AVIF/WebP decoder, PostgreSQL Testcontainers를 승인했다.
- [x] **A1-6~9** — 제공된 wikiwiki URL을 출발점으로 provenance-cleared NPC data만 사용하고, cleared catalog count/schema·번들 경계·v1 고정/v2 catalog-only 경계를 승인했다.
- [ ] **A2** — 각 산출물의 근거와 독립 검토를 확인했다. *(실행 후 별도 승인)*
- [ ] **A3** — OPENSAM-113 concept ID를 선택했다. *(시안 검토 후 별도 승인)*
- [ ] **A4** — 정확한 commit/push/PR 행위를 승인했다. *(별도 승인)*
- [ ] **A5** — 정확한 배포 환경과 롤백 계획을 승인했다. *(별도 승인)*

2026-07-17의 `자, 시작!`은 직전에 제시된 전체 계약과 A1 권고안에 대한 직접 착수 지시로 기록됐다. 이 기록은 A2/A3/A4/A5의 묵시적 승인이 아니며, 각 후속 게이트는 위 체크리스트의 명시 승인이 있을 때만 열린다.
