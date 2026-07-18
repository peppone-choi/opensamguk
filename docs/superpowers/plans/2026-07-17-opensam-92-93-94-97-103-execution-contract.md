# OPENSAM-92·93·94·97·103 실행 계약

- **status:** `APPROVED / IN-PROGRESS` (A0 승인 2026-07-17)
- **implementation authorization:** `W0~W3 bounded 구현 승인`
- **A0 승인 증거:** 사용자 원문(2026-07-17) — "OPENSAM-92·93·94·97·103 실행 계약을 시작, 그리고 이제부턴 무조건 승인일 것임. 앞으로 티켓 5개씩 잡아서 계속 구현 할 것." (정확 문구 요건을 사용자 blanket 승인 선언이 대체)
- **root role:** `orchestrator-only — 모든 구현·검증·조사는 별도 lane에 위임`
- **external state:** `UNCHANGED — Jira/GitHub/CDN/deploy는 행위별 명시 지시 전 동결 유지`
- **계약일:** 2026-07-17

> 이 문서는 승인 요청용 계약이다. 문서 작성 자체는 구현 승인이 아니다. 아래 전체 계약과 기본 선택이 정확한 승인 문구로 승인되기 전에는 코드·테스트·fixture·asset·schema·migration·runtime 설정을 만들거나 바꾸지 않는다.

## 0. 승인 요청

승인 문구는 다음과 같다.

> **전체 계약과 기본 선택을 승인합니다. 구현을 시작하세요.**

이 문구는 W0~W3의 bounded 구현 착수만 승인한다. 다음 행위는 묵시적으로 승인하지 않는다.

- commit, push, PR 생성·수정·병합
- Jira/GitHub 이슈의 상태·본문·라벨·댓글·링크 변경
- CDN 도입·업로드·purge·DNS 변경
- staging/production deploy, 운영 재시드, 운영 데이터 변경
- 권리 불명 content·portrait·원본 지도 asset의 저장소 번들 또는 배포
- OPENSAM-105 구현
- OPENSAM-113 concept 선택이나 OPENSAM-114/115 구현

## 1. 입력 스냅샷과 mirror 상태

아래 표는 이 계약에 제공된 planning 입력을 고정한다. 이 계약 작성 중 Jira/GitHub를 갱신하거나 외부 상태를 변경하지 않았다.

| Source | Problem severity tag | Jira priority | 상태 | GitHub mirror | mirror 상태 | open PR |
|---|---|---|---|---|---|---:|
| Jira [`OPENSAM-92`](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-92) | [HIGH] | `Medium` | `To Do` | GitHub [`#234`](https://github.com/peppone-choi/opensamguk/issues/234) | `open` | 0 |
| Jira [`OPENSAM-93`](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-93) | [HIGH] | `Medium` | `To Do` | GitHub [`#235`](https://github.com/peppone-choi/opensamguk/issues/235) | `open` | 0 |
| Jira [`OPENSAM-94`](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-94) | [MED] | `Medium` | `To Do` | GitHub [`#236`](https://github.com/peppone-choi/opensamguk/issues/236) | `open` | 0 |
| Jira [`OPENSAM-97`](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-97) | [MED] | `Medium` | `To Do` | GitHub [`#240`](https://github.com/peppone-choi/opensamguk/issues/240) | `open` | 0 |
| Jira [`OPENSAM-103`](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-103) | [MED] | `Medium` | `To Do` | GitHub [`#246`](https://github.com/peppone-choi/opensamguk/issues/246) | `open` | 0 |

> `[HIGH]`/`[MED]`는 이슈 본문의 problem severity tag이며 Jira priority가 아니다. 다섯 Jira 이슈의 priority는 모두 `Medium`이다.

- Jira가 planning source이고 GitHub issue는 mirror다. 둘 사이 divergence가 발견되면 값을 추측하지 않고 계약을 중단해 사람에게 올린다.
- open PR은 다섯 티켓 모두 `0`이다. 기존 PR이 있다고 가정하거나 새 PR을 만들지 않는다.
- 상태 변경은 이 계약의 범위가 아니다. 구현 lane도 별도 외부-write 승인 없이는 Jira/GitHub를 수정하지 않는다.

## 2. 에픽별 비교와 batch 경계

| 에픽 | 포함 티켓 | 사용자 가치 | 선행 조건 | 이번 batch의 기본 선택 | 이번 batch가 닫지 않는 것 |
|---|---|---|---|---|---|
| Profile icon delivery | 92·93·94 | account multipart upload/delete가 same-origin proxy와 nginx serving을 거쳐 기존 일반 장수까지 실제 flush 동기화 | OPENSAM-90 helper PASS, OPENSAM-91 code `SPEC/SECURITY/TESTS PASS`; OPENSAM-91 LICENSE gate는 release 전 유지 | **multipart-first UI + 보조 verified chooser → nginx `/d_pic/` → typed sync + best-effort fanout** | storage/decoder 재구현, CDN, exact-once 분산 트랜잭션, OPENSAM-91 legal release, redesign |
| RTK content foundation | 97·103 | RTK14 얼굴을 local-only로 결정적 crop하고, content 교체의 sanctioned-divergence/cutover 계약을 먼저 승인 가능하게 준비 | OPENSAM-91b rights/activation blocked, OPENSAM-102 좌표 101개 cleared + `RIGHTS WARN` | **RTK14 local-only face pipeline + content-layer-only sanctioned divergence** | image commit/redistribution, runtime builder, default scenario 변경, gameplay roster 활성화, OPENSAM-105 city runtime |

### OPENSAM-105 제외 결정

OPENSAM-105는 이 batch에서 제외한다.

1. OPENSAM-102는 46 city와 55 small-base의 native-pixel presentation 좌표, 합계 101개를 검토했지만 **full adjacency**를 확정하지 않았다.
2. runtime `MapJson`이 요구하는 **12개 값**은 현재 근거가 없어 `[UNKNOWN]`이다. 이름·값·기본값을 이 계약에서 발명하지 않는다.
3. 이미지 픽셀 좌표는 world coordinate, projection, 이동 그래프 또는 역사 위치의 증거가 아니다.
4. 따라서 105를 포함하면 미확인 adjacency나 12개 값을 placeholder/default로 날조하게 된다. 이는 패러티·데이터 계약과 `UNKNOWN` 보존 규칙을 위반한다.

OPENSAM-103은 sanctioned-divergence와 cutover/rollback을 문서로 정리하지만 builder, `MapJson` 생성, 도시 runtime wiring, route 활성화는 하지 않는다. 105는 full adjacency와 12개 값의 정본, stable identity, rights-cleared presentation asset을 갖춘 별도 계약과 별도 승인이 필요하다.

## 3. 전체 불변식과 기본 선택

### 3.1 실행 불변식

1. **전체 승인 전 구현 금지:** 위 승인 문구 전에는 구현·테스트 작성·asset 취득·schema/migration 변경·브라우저 검증을 시작하지 않는다.
2. **root orchestrator-only:** root는 ownership 배정, 순서 조정, 증거 수집, reviewer 판정 조정만 한다. 모든 구현·테스트·조사·브라우저 검증은 disjoint single-writer lane에 위임한다.
3. **one-daemon-write:** game-engine write가 필요해도 `EntityManager`를 사용하지 않는다. daemon mutation은 기존 `ChangeRecorder → JdbcFlushExecutor` 경계만 따른다.
4. **v1 parity 고정:** RNG draw 순서·횟수·인자, `PhpRound`, 한글 로그 byte, 부수효과 순서, insertion order, flush 결과를 바꾸지 않는다.
5. **권리 보수성:** provenance/license/redistribution이 확인되지 않은 content와 asset은 metadata의 `UNKNOWN`/blocked 상태만 남기고 runtime allowlist·bundle·deploy로 승격하지 않는다.
6. **정보 보호:** `.env*`, secret, token, PII를 읽거나 출력하지 않는다. 오류·로그에 사용자명, 파일 경로, 원본 파일명, token, stack trace를 노출하지 않는다.
7. **외부 상태 동결:** commit/push/PR/Jira/GitHub/CDN/deploy는 별도 승인 전 실행하지 않는다.
8. **독립 검토:** 각 wave는 구현자와 독립된 verifier/reviewer가 검토하고 최신 판정이 `fix-required=0`이어야 다음 wave의 소비자가 결과를 사용한다.

### 3.2 제안 기본 선택

#### D1. Multipart-first account UI + verified chooser 보조 default

- OPENSAM-92의 본체는 account의 multipart upload/delete UI와 httpOnly cookie를 Bearer로 교환하는 same-origin Next route proxy다.
- 브라우저 JavaScript에 access/refresh token을 노출하지 않고, 브라우저가 임의 user ID·경로·확장자·`imgsvr`·외부 URL을 구성하지 않는다.
- 50 KiB 이하, 64..128 정사각형 조건을 선택 전 안내하고 가능한 범위에서 사전검증하되, OPENSAM-91 decoder·principal·calendar-day 제한을 최종 보안 경계로 둔다.
- upload/delete 응답의 canonical `picture`·`imgsvr`를 preview와 reload에 사용한다. verified shared-code chooser는 upload 불가/미선택 시의 보조 default이지 주 흐름이 아니다.

#### D2. nginx `/d_pic/`

- OPENSAM-93은 same-origin nginx `/d_pic/`를 채택한다.
- gateway-api가 쓰는 configured local Docker volume을 nginx에 read-only로 mount하고, canonical managed filename만 정적 제공한다.
- `web/gateway/lib/portrait.ts`와 `web/game/lib/portrait.ts`에서 `imgsvr=1`을 강제로 default 처리하던 경계를 해제하고 canonical managed name을 same-origin `/d_pic/<managed-name>`으로 해석한다.
- account/lobby와 game generals/my-generals/battle-center 등 모든 helper consumer는 같은 canonical 해석을 사용하며, missing/invalid/load failure에는 guarded `onError` default를 유지한다.
- directory listing, temporary file, operation marker, dot path, traversal, symlink escape를 노출하지 않는다.
- missing/invalid asset은 정적 경로에서 `404`를 반환하고, UI의 이미 검증된 default fallback이 처리한다. nginx가 임의 portrait를 합성하지 않는다.
- CDN은 이 batch의 구현·검증·승인 범위가 아니다.

#### D3. Typed general sync + best-effort fanout

- OPENSAM-94는 OPENSAM-91 commit 후 typed wire로 `userId`, canonical `picture`, `imgsvr`를 전달한다.
- handler는 engine/server의 authoritative current config/account data로 existing Join eligibility `show_img_level >= 1 && grade >= 1`를 판정하며 wire/client boolean을 신뢰하지 않는다.
- eligibility를 통과한 뒤에만 game-api intake → dispatcher → handler가 `owner=userId && npc=0`인 기존 general을 고르고, `ChangeRecorder` dirty → `JdbcFlushExecutor`로 실제 flush한다.
- gate off, `show_img_level=0`, `grade=0`, NPC, 다른 owner에서는 memory/dirty/DB가 모두 불변이다. target 0건은 성공이고, 동일 payload retry는 idempotent하다.
- 여러 target fanout은 bounded best-effort다. target 하나의 실패가 canonical profile mutation이나 다른 target 시도를 rollback하지 않는다.
- exactly-once, distributed transaction, 무제한 retry를 주장하지 않는다. 실패는 PII 없이 관측 가능하게 남긴다.

#### D4. RTK14 local-only face pipeline

- OPENSAM-97은 `tools/rtk-faces` 아래 RTK14 전용 name enumeration → URL `?rev` 제거 → polite fetch/cache → 얼굴 검출 → square crop → report pipeline을 만든다.
- raw/cache/out은 repo 밖 또는 gitignored 경로만 사용하고, success/fail/no-detect를 결정적으로 보고한다.
- RTK14만 선택한다. 실패하거나 얼굴을 검출하지 못한 인물에 다른 얼굴을 발명·대체하지 않는다.
- image commit, CDN, redistribution, production activation을 하지 않는다.

#### D5. Content-layer-only sanctioned divergence

- OPENSAM-103은 `docs/superpowers/specs/2026-07-17-v2-content-replacement-cutover-spec.md` 한 문서로 ADR-LITE-010 sanctioned divergence를 제안한다.
- divergence는 content layer에만 허용하고 engine semantics, devsam fixtures/goldens, v1 parity를 동결한다.
- `ScenarioJson`/`MapJson`과 v2 `PhysicalPlace` projection, `C001`/`B001` stable ID, versioned v1 Int map, cutover/rollback을 명시한다.
- 101 coordinates는 evidence로 고정하고 5개 이민족 거점의 `parent_city`는 `[UNKNOWN]`, source/asset은 `RIGHTS WARN`으로 유지한다.
- 문서는 `PROPOSED`로 시작하며 사용자만 `APPROVED`로 전환할 수 있다. builder/runtime/default scenario/Jira 변경은 하지 않는다.

## 4. OPENSAM-92 — account multipart upload/delete UI와 Next proxy

### 목표

인증된 사용자가 account 화면에서 프로필 아이콘을 multipart로 upload/delete하고, 브라우저에 token을 노출하지 않는 same-origin Next route proxy가 httpOnly cookie를 gateway-api Bearer 요청으로 변환하며, canonical 결과와 하루 1회 제한을 정확히 보여준다.

### 범위

- account settings의 current/canonical preview, file picker, upload, delete, pending/success/error 상태
- 50 KiB(`51200B`) 이하, 가로·세로 `64..128`, 정사각형 조건 안내와 브라우저 사전검증
- multipart body를 보존하는 Next route handler와 httpOnly `sam_access`/refresh 경계의 서버측 Bearer 변환
- gateway-api가 반환한 canonical `picture`·`imgsvr`로 preview 갱신 및 reload persistence
- `401`, `409` 하루 1회 제한, decoder/shape/size 거부, network failure의 구분 가능한 안전한 메시지
- OPENSAM-90 fallback 재사용과 verified shared-code chooser의 보조 default

### 비범위

- gateway-api storage/decoder/DB 제한 변경
- `/d_pic/` serving(OPENSAM-93)
- 기존 general 동기화(OPENSAM-94)
- 임의 URL·filename·path·user ID 입력
- NPC roster 활성화, OPENSAM-113 concept 채택, 전면 redesign

### Given/When/Then acceptance criteria

1. **Given** 인증된 account와 50 KiB 이하·64..128 정사각형 image, **When** upload하면, **Then** route proxy가 multipart를 보존하고 httpOnly cookie에서 얻은 Bearer를 서버측에서만 붙이며 canonical `picture`·`imgsvr` preview를 렌더한다.
2. **Given** 브라우저 JavaScript, **When** upload/delete를 실행하거나 page source/state를 검사하면, **Then** access/refresh token은 노출되지 않고 임의 user ID를 요청 body/path로 보낼 수 없다.
3. **Given** 초과 크기, 64 미만/128 초과, 비정사각형 또는 브라우저가 판독 가능한 미지원 파일, **When** 선택하면, **Then** 조건을 안내하고 가능한 경우 요청 전에 차단한다. 사전검증을 우회해도 서버 거부를 성공으로 위장하지 않는다.
4. **Given** upload/delete 성공, **When** 응답과 reload를 확인하면, **Then** client filename이 아니라 서버 canonical 값이 preview·account/lobby에 동일하게 보인다.
5. **Given** 같은 calendar day의 두 번째 변경, **When** gateway-api가 `409`를 반환하면, **Then** 하루 1회 제한을 명시하고 기존 canonical preview를 유지한다.
6. **Given** delete, **When** 성공하면, **Then** verified shared-code/default portrait로 수렴하고 stale uploaded URL을 유지하지 않는다.
7. **Given** 미인증/만료 cookie, **When** mutation하면, **Then** proxy가 안전한 `401`/refresh/login 경계를 반환하고 token·stack·filesystem path를 노출하지 않는다.
8. **Given** image `404`, **When** preview가 실패하면, **Then** OPENSAM-90 canonical default로 한 번 수렴하고 무한 `onError`가 없다.

### 검증

```bash
corepack pnpm --dir web/gateway test -- account-settings.interaction.test.tsx profile-icon-route.test.ts
corepack pnpm --dir web/gateway typecheck
corepack pnpm --dir web/gateway build
```

브라우저 verifier는 authenticated account에서 valid upload, delete, 50 KiB/64..128/square 사전검증, 서버 validation reject, `409`, expired cookie, canonical reload, `404`를 DOM·multipart network status·screenshot 한 row로 묶는다. DevTools/page state에 Bearer가 나타나지 않음을 확인하고 mocked evidence와 live evidence를 구분한다.

### 승인점

- 전체 계약 승인 후 구현 가능.
- OPENSAM-91 LICENSE gate 해소 전 release 불가.
- A4 commit/push/PR과 A5 deploy는 별도 승인.
- OPENSAM-113 A3 선택은 별개이며, 미선택 상태에서는 현재 account 디자인 언어 안에서 기능만 배선한다.

## 5. OPENSAM-93 — same-origin `/d_pic/` serving

### 목표

gateway-api가 안전하게 확정한 managed profile icon을 nginx가 동일 출처 `/d_pic/`에서 읽기 전용으로 제공한다. 동시에 `web/gateway/lib/portrait.ts`와 `web/game/lib/portrait.ts`가 `imgsvr=1`을 강제 default로 버리지 않고 canonical `/d_pic/<managed-name>`으로 해석해 account/lobby와 game portrait 소비 화면에 일관되게 렌더한다.

### 범위

- local Docker named volume의 writer/read-only consumer 분리
- nginx `/d_pic/` 정적 location, canonical MIME, cache 정책, `nosniff`
- managed filename만 접근 가능하게 하는 path/extension 경계
- missing `404`, directory listing off, internal marker/temp 차단
- local compose와 production compose의 일관된 mount 계약 문서화
- `web/gateway/lib/portrait.ts`와 `web/game/lib/portrait.ts`의 `imgsvr=1` canonical same-origin URL 해석
- 기존 guarded `onError` default 유지 및 recursive/repeated fallback 방지
- gateway account/lobby와 game generals, my-generals, battle-center 등 helper 소비 화면의 정상·missing·`imgsvr=1`·404 렌더 검증

### 비범위

- CDN·S3·외부 origin
- nginx upload 또는 resize
- raw NPC/RTK asset 제공
- gateway-api storage 의미 변경
- OPENSAM-92 account multipart upload/delete UI와 Next route proxy. 단, 공유 gateway helper는 OPENSAM-93 creator가 먼저 완료하고 OPENSAM-92가 이후 소비한다.
- deploy/DNS/TLS 변경

### Given/When/Then acceptance criteria

1. **Given** commit 완료된 canonical managed file, **When** `/d_pic/<canonical-name>`을 요청하면, **Then** `200`, decoder 결과와 맞는 `Content-Type`, `X-Content-Type-Options: nosniff`로 응답한다.
2. **Given** 존재하지 않는 canonical name, **When** 요청하면, **Then** `404`이며 directory path나 upstream filesystem path를 노출하지 않는다.
3. **Given** traversal, dot path, `.ops`, temporary/marker filename, symlink escape 시도, **When** 요청하면, **Then** 파일 내용이 절대 반환되지 않는다.
4. **Given** nginx container, **When** volume을 검사하면, **Then** profile icon volume은 read-only이고 nginx가 파일을 생성·수정·삭제할 수 없다.
5. **Given** 새 upload commit, **When** 같은 origin에서 canonical URL을 요청하면, **Then** 별도 CDN purge 없이 새 내용이 보이고 UI fallback 계약과 충돌하지 않는다.
6. **Given** `imgsvr=1`과 canonical managed name, **When** gateway/game helper가 URL을 계산하면, **Then** 강제 default가 아니라 정확히 same-origin `/d_pic/<managed-name>`을 반환한다.
7. **Given** `imgsvr=1` portrait, **When** gateway account/lobby와 game generals/my-generals/battle-center 등 소비 화면을 렌더하면, **Then** 두 앱이 동일 canonical URL을 요청하고 성공 응답을 표시한다.
8. **Given** missing/invalid name 또는 `/d_pic/` `404`, **When** image load가 실패하면, **Then** 기존 guarded `onError`가 canonical default로 한 번 수렴하고 무한 fallback·반복 state update가 없다.
9. **Given** `imgsvr!=1` 또는 기존 default case, **When** helper를 실행하면, **Then** OPENSAM-90에서 cleared된 기존 해석과 fallback을 회귀시키지 않는다.

### 검증

```bash
corepack pnpm --dir web/gateway test -- portrait
corepack pnpm --dir web/gateway typecheck
corepack pnpm --dir web/gateway build
corepack pnpm --dir web/game test -- portrait
corepack pnpm --dir web/game typecheck
corepack pnpm --dir web/game build
docker compose config
docker compose -f docker-compose.production.yml config
./tools/smoke.sh
```

별도 verifier는 정상 MIME별 `200`, missing `404`, traversal/dot/marker 차단, read-only mount, directory listing off, response header를 curl/컨테이너 관측으로 확인한다. 브라우저/DOM 검증은 gateway account/lobby와 game generals/my-generals/battle-center의 정상·missing·`imgsvr=1`·404를 포함하고, 요청 URL·상태·guarded fallback·screenshot을 한 row로 묶는다. 이는 local verification이며 deploy 승인이 아니다.

### 승인점

- 전체 계약 승인 후 구현 가능.
- compose/nginx shared file은 W0에서 지정한 single writer만 수정한다.
- `web/gateway/lib/portrait.ts`는 OPENSAM-93 helper creator가 먼저 수정·검증하고, OPENSAM-92는 cleared helper를 순차 소비한다. 두 lane의 동시 write를 금지한다.
- `web/game/lib/portrait.ts`와 game consumer 검증도 OPENSAM-93 owner 범위로 고정한다.
- CDN은 이 batch에서 계속 금지.
- A4/A5 별도 승인 전 commit/push/PR/deploy 금지.

## 6. OPENSAM-94 — existing player-general picture/imgsvr dirty/flush sync

### 목표

canonical profile icon mutation이 commit된 뒤 typed wire를 통해 각 game server로 전달한다. engine/server의 authoritative current config/account data가 existing Join eligibility `show_img_level >= 1 && grade >= 1`를 만족할 때만, 그 다음 `owner=userId && npc=0`인 기존 general에 `picture`·`imgsvr`를 반영하고 `ChangeRecorder` dirty와 `JdbcFlushExecutor` flush까지 one-daemon-write 경계로 완료한다.

### 범위

- OPENSAM-91 DB commit 이후 발행되는 typed profile-icon-sync wire
- gateway의 target enumeration과 target별 bounded best-effort fanout
- game-api intake의 인증·shape validation과 dispatcher 전달
- engine/server authoritative current config/account data 조회와 exact Join eligibility `show_img_level >= 1 && grade >= 1` 판정
- wire/client가 보낸 eligibility boolean 무시 및 authoritative 재평가
- eligibility 통과 뒤 game-engine handler의 `owner=userId && npc=0` exact target 선택
- canonical `picture`·`imgsvr` mutation → `ChangeRecorder` dirty → `JdbcFlushExecutor` batch flush
- duplicate/retry idempotency, target 0건 success, partial failure 관측
- gate off·`show_img_level=0`·`grade=0`, NPC·다른 owner 및 OPENSAM-91 principal/calendar-day 의미의 memory/dirty/DB 불변 보존

### 비범위

- DB와 모든 game server 사이 distributed transaction
- exactly-once 보장
- 무제한 retry·영구 queue 신설
- game-engine JPA/`EntityManager` write 또는 handler inline JDBC
- 새 general 생성, Join gate 우회, NPC/다른 사용자 general 변경
- gameplay state, RNG, 로그 byte 의미 변경

### Given/When/Then acceptance criteria

1. **Given** profile mutation rollback, **When** transaction이 끝나면, **Then** typed sync/fanout은 0회이고 general은 변하지 않는다.
2. **Given** commit된 `userId/picture/imgsvr`와 game target, **When** wire가 intake→dispatcher→handler를 통과하면, **Then** handler는 wire/client eligibility boolean을 신뢰하지 않고 authoritative current config/account의 `show_img_level`과 `grade`를 읽는다.
3. **Given** `show_img_level >= 1 && grade >= 1`, **When** eligibility를 판정하면, **Then** 그 뒤에만 `owner=userId && npc=0` exact target predicate를 적용한다.
4. **Given** gate off, `show_img_level=0`, 또는 `grade=0`, **When** sync가 실행되면, **Then** 대상 general의 memory state, recorder dirty, DB `picture`·`imgsvr`가 모두 불변이다.
5. **Given** eligibility 통과와 matching existing general, **When** handler가 실행되면, **Then** canonical `picture`·`imgsvr`를 mutation하고 recorder dirty에 등록한다.
6. **Given** handler mutation, **When** turn flush가 완료되면, **Then** `JdbcFlushExecutor`가 canonical `picture`·`imgsvr`를 DB에 반영하며 inline write/JPA write는 0회다.
7. **Given** 같은 user가 소유한 NPC 또는 `npc!=0` general과 다른 owner general, **When** sync가 실행되면, **Then** 해당 rows는 memory/dirty/DB 모두 불변이다.
8. **Given** Join eligibility 미통과 또는 matching general 부재, **When** sync가 도착하면, **Then** general을 새로 만들거나 Join gate를 우회하지 않는다.
9. **Given** matching general 0명, **When** handler가 실행되면, **Then** 성공으로 종료하고 dirty/flush row는 0개다.
10. **Given** 동일 canonical payload retry, **When** 두 번 처리하면, **Then** 최종 state는 한 번 처리와 같고 중복 로그·부수효과·추가 row가 없다.
11. **Given** target 하나가 timeout/5xx, **When** 나머지 target이 정상이라면, **Then** canonical API 성공은 rollback되지 않고 나머지 target 시도와 성공 target flush는 계속된다.
12. **Given** 오류 관측, **When** 로그를 확인하면, **Then** 비-PII operation/target key와 오류 class만 있고 token·username·path·stack trace는 없다.

### 검증

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
  :app:gateway-api:test :app:game-api:test :app:game-engine:test \
  --tests '*ProfileIconSync*' --rerun-tasks
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
  :common:test :infra:test :app:gateway-api:test :app:game-api:test :app:game-engine:test \
  --rerun-tasks
tools/parity/gate.sh backend
```

focused `*ProfileIconSync*` tests는 typed wire round-trip, intake/dispatcher, authoritative config/account lookup, wire/client boolean 무시, gate on, gate off, `show_img_level=0`, `grade=0`, eligibility 통과 뒤 exact owner+npc predicate, NPC/other-owner 불변, target0 success, retry idempotency, dirty/flush persistence, fanout partial failure를 포함한다. gate 경계마다 memory/dirty/DB를 모두 단언한다. Gradle 출력의 `BUILD SUCCESSFUL`과 XML `failures="0" errors="0"`를 함께 확인한다. Docker pre-assert failure는 code pass로 바꾸지 않고 environment-failed로 분리한다.

### 승인점

- 전체 계약 승인 후 구현 가능.
- typed wire, intake, dispatcher, handler, recorder/flush 연결은 foundation-first single writer 순서로 구현한다.
- reviewer가 authoritative Join eligibility, eligibility 이후 exact predicate, one-daemon-write, actual flush, v1 parity를 clear하기 전 완료 주장 금지.
- A4/A5 별도 승인.

## 7. OPENSAM-97 — RTK14 face scraper와 square-crop pipeline

### 목표

RTK14를 선택해 인물명을 결정적으로 열거하고, 원본 URL의 `?rev`를 제거한 polite local scraper/cache와 얼굴 검출 기반 square crop pipeline을 `tools/rtk-faces`에 구축한다. 결과는 성공·실패·미검출을 재현 가능한 report로 남기되 image를 commit·재배포·production 활성화하지 않는다.

### 범위

- `tools/rtk-faces` 아래 Python name enumeration, URL normalization, fetch/cache, face detection, square crop, report 단계
- RTK14 name source의 deterministic enumeration과 중복·빈 이름 검증
- source URL에서 query `?rev` 제거 후 canonical URL과 fingerprint 기록
- 명시적 User-Agent, 요청 간 polite rate limit, bounded retry/timeout, cache 재사용
- raw/cache/out을 repo 밖 또는 gitignored path로 강제
- 성공, fetch/decode/crop 실패, `NO_DETECT`를 동일 입력에서 결정적으로 분류한 report
- 얼굴 box를 포함하는 정사각형 crop, bounds clamp, output dimension/format 기록
- 유명 장수와 비유명/minor 장수를 함께 포함한 20건 manual QA manifest

### 비범위

- image/raw/cache/crop output의 git commit 또는 repository bundle
- CDN upload, hotlink, redistribution, production 사용
- general/NPC roster, scenario seed, default scenario, runtime asset wiring
- fetch/decode/face-detect 실패 시 임의 인물 얼굴 생성·대체·추측
- RTK8R 또는 다른 게임으로 범위 확대
- 권리 판단을 기술 reviewer가 대신 승인하는 것

### Given/When/Then acceptance criteria

1. **Given** 고정된 RTK14 name source, **When** enumerate를 두 번 실행하면, **Then** 동일 name order/count와 동일 normalized source URL을 만든다.
2. **Given** source URL에 `?rev` query가 있으면, **When** normalize하면, **Then** fetch/cache/report key에는 query가 제거된 canonical URL을 사용한다.
3. **Given** cold fetch, **When** pipeline이 실행되면, **Then** 명시적 polite delay·bounded timeout/retry·User-Agent를 지키고 요청 폭주를 만들지 않는다.
4. **Given** 동일 raw/cache input, **When** crop을 반복하면, **Then** 동일 success/fail/no-detect 분류, crop geometry, output fingerprint, report ordering을 만든다.
5. **Given** 얼굴 검출 성공, **When** crop하면, **Then** 검출 box를 포함하고 원본 bounds 안에 clamp된 square crop을 만든다.
6. **Given** fetch/decode 실패 또는 얼굴 미검출, **When** pipeline이 끝나면, **Then** `FAIL` 또는 `NO_DETECT`를 이유와 함께 기록하고 다른 얼굴을 발명하지 않는다.
7. **Given** raw/cache/out 경로, **When** repo tracked path를 지정하면, **Then** fail closed한다. repo 밖 또는 gitignored path만 허용한다.
8. **Given** manual QA 20건, **When** reviewer가 원본과 crop을 비교하면, **Then** 유명 장수와 minor 장수가 모두 포함되고 identity, face inclusion, square bounds, 오검출 여부가 각 row에 판정된다.
9. **Given** 작업 완료 diff, **When** tracked files를 감사하면, **Then** image/raw/cache/crop binary, CDN 설정, runtime wiring은 0개다.

### 검증

```bash
python3 -m unittest discover -s tools/rtk-faces/tests -p 'test_*.py'
python3 tools/rtk-faces/build_rtk14_faces.py \
  --source-dir <outside-repo> \
  --out-dir <ignored> \
  --report <ignored>/rtk14-face-report.json
```

unit tests는 name ordering, duplicate/empty name, `?rev` 제거, rate limiter, cache hit, timeout/failure, deterministic crop, bounds clamp, no-detect, report ordering, repo-path rejection을 포함한다. manual QA는 report에서 유명/비유명 20건을 층화 추출해 원본과 crop을 실제로 열어 판정한다. report와 image는 repo 밖 또는 gitignored 위치에 남기며 합격이 redistribution 허가를 뜻하지 않는다.

### 승인점

- 전체 계약 승인 후 `tools/rtk-faces`와 local-only 실행만 가능.
- RTK14 선택은 RTK8R 확대 승인이 아니다.
- legal/rights owner가 명시적으로 허가하기 전 image commit·redistribution·CDN·production 사용 금지.
- crop 결과의 runtime/gameplay activation은 별도 계약 대상.
- A4/A5 별도 승인.

## 8. OPENSAM-103 — ADR-LITE-010 sanctioned-divergence cutover spec

### 목표

`docs/superpowers/specs/2026-07-17-v2-content-replacement-cutover-spec.md`에 ADR-LITE-010 sanctioned divergence를 `PROPOSED`로 작성한다. v1 engine semantics와 devsam fixtures/goldens를 동결한 채 content layer 교체의 identity, projection, cutover, rollback만 승인 가능한 수준으로 규정한다.

### 범위

- 지정 spec 한 파일과 그 안의 `ADR-LITE-010` 상태·결정·근거·대안·결과
- **content layer only** sanctioned divergence; engine command/RNG/rounding/log/order/flush semantics immutable
- devsam-derived fixtures와 goldens의 byte baseline freeze 및 변경 승인 경계
- v1 `ScenarioJson`/`MapJson`과 v2 `PhysicalPlace` projection 계약
- city `C001...`, small-base `B001...` stable ID와 versioned v1 Int ID map
- OPENSAM-102의 46 city + 55 small-base = 101 native-pixel coordinate evidence
- 5개 이민족 거점의 `parent_city`는 `[UNKNOWN]`, source/asset redistribution은 `RIGHTS WARN`
- 1차 시나리오는 `황건의 난 184-02`로 고정하고, 그 mapping·compare·rollback 검증과 사용자 승인 뒤에만 다른 시나리오를 한 번에 하나씩 같은 계약으로 확장하는 순서
- raw/intermediate source artifact는 repo 밖 또는 gitignored 경로에만 두고, spec에는 provenance·fingerprint·경로 정책만 기록하는 원칙
- staged cutover preconditions, dual-read/compare 또는 equivalent observation, abort criteria, rollback source/version
- OPENSAM-105 defer 근거: full adjacency와 `MapJson` 12개 값 `[UNKNOWN]`

### 비범위

- builder, scraper, validator executable 또는 runtime code
- `ScenarioJson`/`MapJson`/`PhysicalPlace` 실제 생성·변경·wiring
- default scenario/seed 변경 또는 content 활성화
- devsam fixtures/goldens 변경
- full adjacency 생성·reverse edge 자동 보충
- `MapJson` 12개 값, 5개 이민족 거점의 `parent_city`, pixel-to-world projection의 placeholder/default 발명
- raw/intermediate source artifact의 tracked 저장 또는 1차·후속 시나리오 실제 구현
- raw image/source table bundle, CDN, Jira/GitHub 변경
- OPENSAM-105 구현 또는 역사 위치 증거 채택

### Given/When/Then acceptance criteria

1. **Given** 새 spec, **When** 작성되면, **Then** status는 `PROPOSED`이고 ADR-LITE-010의 context/decision/alternatives/consequences/cutover/rollback이 모두 존재한다.
2. **Given** content replacement, **When** 허용 경계를 읽으면, **Then** divergence는 content layer로 한정되고 engine RNG·rounding·Korean log·side-effect order·ChangeRecorder/JDBC flush semantics는 immutable이다.
3. **Given** devsam fixtures/goldens, **When** cutover를 계획하면, **Then** baseline freeze와 변경 금지/별도 승인 규칙이 명시되고 새 content가 기존 golden을 다시 쓰지 않는다.
4. **Given** v1/v2 모델, **When** projection을 정의하면, **Then** `ScenarioJson`/`MapJson` ↔ `PhysicalPlace`, `C001`/`B001`, versioned v1 Int map의 방향·version·unknown 처리 규칙이 명시된다.
5. **Given** OPENSAM-102 evidence, **When** spec에 인용하면, **Then** 정확히 101 coordinates, 5개 이민족 거점의 `parent_city` `[UNKNOWN]`, `RIGHTS WARN`, native-pixel은 world coordinate가 아님을 보존한다.
6. **Given** full adjacency와 `MapJson` 12개 값이 미확인, **When** OPENSAM-105 의존성을 판정하면, **Then** 105는 deferred이고 placeholder/default/reverse edge를 허용하지 않는다.
7. **Given** cutover 실패 또는 parity mismatch, **When** abort criteria가 충족되면, **Then** 이전 content version/source로 되돌리는 rollback 절차와 관측 지점이 명시된다.
8. **Given** scenario cutover 순서, **When** spec을 작성하면, **Then** 1차는 `황건의 난 184-02`이고 그 mapping·compare·rollback 검증과 사용자 승인 뒤에만 다른 시나리오를 한 번에 하나씩 확장하며 default scenario를 바꾸지 않는다.
9. **Given** raw/intermediate source artifact, **When** 보존 경계를 정의하면, **Then** repo 밖 또는 gitignored 경로만 허용하고 tracked artifact나 구현 결과를 만들지 않는다.
10. **Given** 문서가 `PROPOSED`, **When** agent/reviewer가 검토를 마쳐도, **Then** status를 `APPROVED`로 바꾸지 않는다. 오직 사용자의 명시 승인만 `APPROVED` 전환 근거다.
11. **Given** 이 티켓 diff, **When** 파일 목록을 확인하면, **Then** 지정 spec 외 builder/runtime/default scenario/Jira 변경은 0개다.

### 검증

```bash
test -f docs/superpowers/specs/2026-07-17-v2-content-replacement-cutover-spec.md
rg -n 'ADR-LITE-010|PROPOSED|content layer|engine semantics|ScenarioJson|MapJson|PhysicalPlace|C001|B001|versioned|101|parent_city|황건의 난 184-02|raw|intermediate|UNKNOWN|RIGHTS WARN|cutover|rollback|OPENSAM-105' \
  docs/superpowers/specs/2026-07-17-v2-content-replacement-cutover-spec.md
git diff --check -- docs/superpowers/specs/2026-07-17-v2-content-replacement-cutover-spec.md
```

독립 reviewer는 spec이 content layer와 engine semantics를 분리하고, stable ID/versioned map, 101/5 UNKNOWN/RIGHTS WARN, cutover/rollback, 105 defer를 빠짐없이 유지하는지 검토한다. 이 티켓에는 Gradle catalog validator를 요구하지 않는다.

### 승인점

- 전체 계약 승인 후 지정 `PROPOSED` spec 작성만 가능.
- reviewer clearance는 문서 품질 판정일 뿐 `APPROVED` 전환이 아니다. 사용자가 별도로 승인해야 한다.
- original source quarantine, 5개 이민족 거점의 `parent_city` `[UNKNOWN]`, `RIGHTS WARN`은 해제되지 않는다.
- builder/runtime/default scenario/Jira 및 OPENSAM-105는 별도 계약·승인 전 금지.
- A4/A5 별도 승인.

## 9. 실행 wave W0~W3

### W0 — foundation freeze와 lane 배정

- root orchestrator가 ticket별 disjoint ownership을 `.ai/ownership.md`에 등록한다.
- 구현자는 정확한 현재 API/event/path/schema를 읽고 `[사실]`로 고정한다. 확인되지 않은 class/file/key는 발명하지 않는다.
- shared writer를 먼저 지정한다: nginx/compose owner 1명, Next account/proxy owner 1명, event/wire owner 1명. `tools/rtk-faces`와 OP103 spec은 각각 별도 single writer다. OPENSAM-93 helper creator가 `web/gateway/lib/portrait.ts`와 `web/game/lib/portrait.ts`를 먼저 완료·검증할 때까지 OPENSAM-92는 gateway helper를 수정하지 않는다.
- 각 ticket의 RED/fixture 또는 문서 verification sheet와 rollback 기준을 작성하되 전체 계약 승인 전에는 실행하지 않는다.
- 외부 상태는 unchanged로 유지한다.

**W0 exit:** overlap 0, shared writer 1명씩, exact files와 verifier 명령 확정, 구현 시작 가능 범위가 계약 안에 머문다.

### W1 — OPENSAM-93·97·103 disjoint foundations

세 lane은 파일과 runtime 책임이 겹치지 않으므로 병렬 가능하다.

1. OPENSAM-93 owner가 nginx `/d_pic/` read-only serving과 보안 경계, 양 앱 portrait helper의 `imgsvr=1` canonical 해석을 구현한다. nginx 정상/404/traversal/marker/read-only와 gateway account/lobby, game generals/my-generals/battle-center 등 소비 화면의 정상·missing·`imgsvr=1`·404 렌더를 검증한다.
2. OPENSAM-97 owner가 RTK14 `tools/rtk-faces` local-only pipeline을 구현하고 Python unit tests와 famous+minor 20건 manual crop QA를 수행한다.
3. OPENSAM-103 owner가 지정 sanctioned-divergence cutover spec을 `PROPOSED`로 작성하고 독립 reviewer가 required anchors와 105 defer를 검토한다.
4. raw/cache/out image, CDN, runtime activation, Jira/GitHub 외부 write는 세 lane 모두 금지한다.

**W1 exit:** OP93 local nginx proof, 양 앱 portrait tests/typecheck/build, account/lobby + game 소비 화면 렌더 matrix green; OP97 unit tests + manual QA 20건 판정 완료; OP103 spec completeness `fix-required=0`; tracked image 0, CDN/deploy 0. OP103 status는 여전히 `PROPOSED`다.

### W2 — OPENSAM-92 account UI가 OPENSAM-93을 소비

- W1에서 cleared된 same-origin `/d_pic/`와 OPENSAM-93 gateway portrait helper만 preview URL 계약으로 순차 소비한다. OPENSAM-92는 helper creator가 아니며 W1 clearance 전 동시 write하지 않는다.
- account multipart upload/delete와 httpOnly cookie→Bearer Next route proxy를 구현한다.
- 50 KiB·64..128 square 사전검증, canonical preview/reload, 서버 거부/하루 1회 메시지를 검증한다.
- focused tests, typecheck/build 뒤 authenticated account browser manual QA를 수행한다.

**W2 exit:** focused 2파일 tests, typecheck, build green; valid upload/delete/409/reject/reload/token 비노출 browser matrix pass; independent review `fix-required=0`.

### W3 — OPENSAM-94 typed sync와 actual dirty/flush

- OPENSAM-91 commit semantics와 W1/W2 canonical `picture`·`imgsvr` 계약을 소비한다.
- typed wire → game-api intake → dispatcher → handler → `ChangeRecorder` → `JdbcFlushExecutor`를 foundation-first로 연결한다.
- authoritative current config/account로 `show_img_level >= 1 && grade >= 1`를 판정하고 wire/client boolean을 무시한다. eligibility 통과 뒤 exact `owner=userId && npc=0` predicate를 적용한다.
- gate off, `show_img_level=0`, `grade=0`, NPC/other owner에서 memory/dirty/DB 불변과 target0 success, retry idempotency, fanout partial failure를 검증한다.
- 실제 DB flush 관측, backend module tests, parity gate, one-daemon-write 독립 리뷰를 수행한다.

**W3 exit:** `*ProfileIconSync*`와 module/parity evidence green 또는 환경 실패가 명시적으로 격리되고, gate on/off·show0·grade0의 memory/dirty/DB 경계와 actual dirty/flush가 관측되며 review `fix-required=0`.

## 10. 전체 검증과 증거 규칙

각 명령은 해당 wave가 실제로 바꾼 범위에 맞춰 implementer와 분리된 verifier가 실행한다. 아래는 승인 후 실행할 계약 명령이며 현재 실행 결과가 아니다.

```bash
corepack pnpm --dir web/gateway test -- account-settings.interaction.test.tsx profile-icon-route.test.ts
corepack pnpm --dir web/gateway test -- portrait
corepack pnpm --dir web/gateway typecheck
corepack pnpm --dir web/gateway build
corepack pnpm --dir web/game test -- portrait
corepack pnpm --dir web/game typecheck
corepack pnpm --dir web/game build

JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
  :app:gateway-api:test :app:game-api:test :app:game-engine:test \
  --tests '*ProfileIconSync*' --rerun-tasks
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
  :common:test :infra:test :app:gateway-api:test :app:game-api:test :app:game-engine:test \
  --rerun-tasks

tools/parity/gate.sh backend
docker compose config
docker compose -f docker-compose.production.yml config
./tools/smoke.sh

python3 -m unittest discover -s tools/rtk-faces/tests -p 'test_*.py'
python3 tools/rtk-faces/build_rtk14_faces.py \
  --source-dir <outside-repo> \
  --out-dir <ignored> \
  --report <ignored>/rtk14-face-report.json

test -f docs/superpowers/specs/2026-07-17-v2-content-replacement-cutover-spec.md
rg -n 'ADR-LITE-010|PROPOSED|content layer|engine semantics|ScenarioJson|MapJson|PhysicalPlace|C001|B001|versioned|101|parent_city|황건의 난 184-02|raw|intermediate|UNKNOWN|RIGHTS WARN|cutover|rollback|OPENSAM-105' \
  docs/superpowers/specs/2026-07-17-v2-content-replacement-cutover-spec.md
git diff --check -- docs/superpowers/specs/2026-07-17-v2-content-replacement-cutover-spec.md

tools/agent-system/check.py --strict --base origin/main
scripts/agent/verify-changes.sh
```

검증 판정 규칙:

- Gradle은 출력의 `BUILD SUCCESSFUL`과 test XML `failures="0" errors="0"`를 함께 확인한다.
- Gradle module/parity gate는 OPENSAM-94 sync와 기존 backend 회귀 검증용이다. OPENSAM-97/103에 Gradle catalog validator를 요구하지 않는다.
- Docker/Testcontainers가 assertion 전에 실패하면 `environment-failed/inconclusive`이며 pass나 code-fail로 바꾸지 않는다.
- OPENSAM-92 browser는 mocked와 live를 분리하고 viewport, URL, DOM, multipart network, canonical preview, token 비노출, screenshot ID를 같은 evidence row에 기록한다.
- OPENSAM-93 manual QA는 nginx response/MIME/header/read-only/traversal 관측을 남긴다.
- OPENSAM-97 manual QA는 famous+minor 20건의 원본/crop identity, face inclusion, square bounds, 오검출 판정을 남긴다.
- OPENSAM-103은 spec anchor/diff/reviewer 검증만 하며 executable builder/runtime evidence를 요구하지 않는다.
- OPENSAM-94는 authoritative `show_img_level >= 1 && grade >= 1` gate와 gate off/show0/grade0 불변을 memory/dirty/DB에서 각각 단언한다. memory mutation만으로 pass하지 않고 recorder dirty와 JDBC flush 후 DB `picture`·`imgsvr`를 관측한다.
- smoke는 local stack 검증일 뿐 deploy 승인이 아니다.
- strict check의 pre-existing finding과 이번 diff finding을 분리한다.
- reviewer 문서는 exact scope와 artifact SHA-256을 기록하고 마지막 verdict를 `cleared` 또는 `fix-required`로 남긴다.
- `fix-required`가 하나라도 남으면 다음 wave, commit, release, deploy로 진행하지 않는다.

## 11. 승인 게이트

| Gate | 내용 | 현재 상태 | 통과 증거 |
|---|---|---|---|
| A0 | 전체 계약 + D1~D5 기본 선택 + W0~W3 bounded 착수 | `PENDING` | 사용자의 정확한 승인 문구 |
| A1 | W0 foundation/ownership과 shared-writer 경계 | `PENDING` | overlap 0, exact file/owner/verifier matrix |
| A2 | wave별 구현·테스트·보안·패러티·rights 증거 | `PENDING` | 독립 verifier + reviewer `fix-required=0` |
| A3 | OPENSAM-113 concept 선택 | `SEPARATE/PENDING` | 사용자가 concept ID를 별도로 선택 |
| A4 | commit/push/PR | `BLOCKED` | 정확한 branch/base/행위별 사용자 승인 |
| A5 | deploy | `BLOCKED` | 환경·artifact SHA·rollback 계획에 대한 사용자 승인 |
| LEGAL | OPENSAM-91/97/103 dependency·content·asset 권리 release | `BLOCKED` | 사람 legal/release owner의 명시 판정 |

### OPENSAM-113과의 분리

- OPENSAM-113의 live A2와 A3 concept 선택은 이 계약의 A0가 대신하지 않는다.
- A3 전 OPENSAM-92는 현재 UI 언어 안에서 기능적 배선만 한다. concept를 선점하거나 114/115 디자인 시스템을 구현하지 않는다.
- 나중에 A3가 선택되더라도 이번 batch의 security, serving, fanout, content/right 경계를 약화할 수 없다.

## 12. 최종 체크리스트

- [ ] 사용자가 **`전체 계약과 기본 선택을 승인합니다. 구현을 시작하세요.`**라고 승인했다.
- [ ] W0 single-writer ownership과 disjoint lane이 등록됐다.
- [ ] OPENSAM-92 account multipart upload/delete, 50 KiB·64..128 square, httpOnly cookie→Bearer proxy와 canonical preview 경계가 승인됐다.
- [ ] OPENSAM-93 nginx `/d_pic/`, 양 앱 `imgsvr=1` helper 해석, guarded fallback, gateway/game 소비 화면과 helper creator 경계가 승인됐다.
- [ ] OPENSAM-94 authoritative `show_img_level >= 1 && grade >= 1` gate, eligibility 이후 typed wire→intake→dispatcher→handler→dirty/flush sync, exact owner/npc predicate와 best-effort fanout 경계가 승인됐다.
- [ ] OPENSAM-97 RTK14 local-only scraper/square-crop, manual QA 20건과 no-image-commit/legal gate가 승인됐다.
- [ ] OPENSAM-103 ADR-LITE-010 content-only sanctioned divergence, stable ID/versioned map, cutover/rollback과 user-only approval 경계가 승인됐다.
- [ ] OPENSAM-105 제외(full adjacency + `MapJson` 12개 값 `[UNKNOWN]`)가 승인됐다.
- [ ] OPENSAM-113 선택이 별도 gate임을 확인했다.
- [ ] commit/push/Jira/GitHub/CDN/deploy가 계속 blocked임을 확인했다.

승인 전 현재 판정은 **PROPOSED / NO IMPLEMENTATION AUTHORIZED / EXTERNAL STATE UNCHANGED**다.

## 13. 범위 수정 (2026-07-17, A0 이후 사용자 지시)

사용자 원문:

> "이참에 지도도 RTK14로 맞춰. 지금 계속 실제 지도가 아니라고 난감해하는데 안그래도 돼. 정확히는 모든 RTK 시리즈의 지도를 가지고와서 비교하고 보충해."

이에 따른 수정:

1. **지도 콘텐츠 기준 = RTK14.** "실제(역사) 지도가 아니다"는 유보를 해제한다. RTK14 지도가 v2 content-layer 지도의 기준이다 (OPENSAM-102 좌표 101개가 이미 RTK14 원본 기준).
2. **전 RTK 시리즈 비교·보충 리서치 승인.** 새 lane `lane-map-rtk-series`가 접근 가능한 모든 RTK 시리즈의 지도(도시 목록·인접/이동 경로·구조)를 수집·비교하고, OPENSAM-105가 `[UNKNOWN]`으로 묶어둔 full adjacency와 `MapJson` 값들을 **시리즈별 provenance가 붙은 근거**로 보충하는 문서를 작성한다.
3. **날조 금지 원칙 불변.** 보충은 발명이 아니라 근거 수집이다. 어느 시리즈에서도 근거가 없는 값은 여전히 `[UNKNOWN]`으로 남긴다. 다만 근거가 확보된 항목은 105 defer를 해제하는 입력이 된다.
4. **권리 보수성 불변.** 원본 지도 이미지·raw 캡처는 repo 밖 또는 gitignored 유지, 재배포/번들 미승인(`RIGHTS WARN`). 리서치 문서에는 provenance·fingerprint·경로 정책만 기록한다.

추가 지시 (2026-07-17, 사용자 원문): "일단 RTK14 지도 원본 이미지는 데이터화 시켜. 헥스맵이니까. 지형등을 데이터화 시키기 쉬울거야."

5. **RTK14 지도 이미지 데이터화 승인.** 새 lane `lane-map-datafy`가 `tools/rtk14/build_rtk14_hexmap.py`(+테스트)를 만들어 원본 지도 이미지를 헥스 격자 단위 지형 데이터로 결정적으로 추출한다. 기존 stats 빌더 패턴을 따른다: 빌더·테스트·방법 문서만 버전 관리, 원본 이미지와 산출 데이터는 repo 밖 또는 gitignored. 지형 라벨 집합은 출처(게임 내 지형 분류) 근거로 고정하고, 분류 불확실 헥스는 `UNKNOWN`으로 남긴다. 셀 색상-지형 매핑은 위키 범례 문서를 근거로 한다(사용자 지시: "각 지도 이미지의 셀 색상은 위키에서 확인하고"). 산출 데이터의 runtime 소비는 OPENSAM-103/105 계약의 별도 승인 사항이다.

추가 지시 (2026-07-17, 사용자 원문): "그리고 황건의난으로 고정하면 어떡하냐."

6. **1차 시나리오 단일 고정 해제.** §8과 D5의 "1차 시나리오 `황건의 난 184-02` 고정 + 시나리오별 사용자 승인 후 한 번에 하나씩 확장" 조항을 사용자 지시로 대체한다: content 교체 cutover는 **전 시나리오 집합**을 대상으로 하며, 시나리오별 mapping·compare·rollback 검증 절차는 유지하되 시나리오 사이의 사용자 승인 게이트와 단일 파일럿 고정은 제거한다(blanket 승인 선언과 본 지시에 근거). 검증 순서·병렬화는 구현 lane의 재량이다. 이 항목은 §8 acceptance criterion 8과 §10의 해당 rg anchor 요건보다 우선한다.
