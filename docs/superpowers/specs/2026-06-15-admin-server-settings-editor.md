# 어드민 서버설정 편집기 — spec (2026-06-15)

> 출처: 라이브 QA 버그 4(어드민에서 서버 설정 수정 불가) + 버그 2(빙의 활성화) 키스톤. 짝 LEDGER:
> `docs/loops/bug-parity-2026-06-15/LEDGER.md`. 구현은 바퀴별 게이트로(아래 §4). 라이브 버그라 우선순위 높음.

## 1. 문제

opensamguk 어드민(`web/game/app/game/admin1/page.tsx` + gateway `admin/page.tsx`)은 서버 설정을
**read-only** 로만 표시한다. game-api `AdminReadController` 가 값은 반환하나 `blockedWrites` 에 7개 뮤테이션을
전부 BLOCKED("game_env/world_state config mutation intake 미포팅") 표기. **write 엔드포인트 부재** —
`AdminWriteController` 는 `POST /api/admin/server-status`(world_state.status)만 구현. 그래서 운영자가
턴텀·최대장수·**npcmode(빙의 여부)**·시작년도 등을 못 바꾼다.

**버그 2 연결:** 로비 빙의/선택 버튼은 `config["npcmode"]`(0불가/1가능/2선택) 에 게이트되는데, 라이브
s1 은 npcmode 미시드(=0)라 영구 비활성. 본 편집기로 `config["npcmode"]=1` 을 쓰면 로비(ServerBasicInfo가
DB-read)가 즉시 빙의 버튼을 노출 → **버그 2 완결**.

## 2. 아키텍처 (one-daemon-write 규칙과 무모순)

`AdminWriteController` 는 이미 `world.save(entity)`(JPA)로 world_state.status 를 쓴다(admin-role gated).
one-daemon-write 규칙(`DaemonNoEntityManagerTest`)은 **엔진 데몬**의 JPA write 만 막고 game-api **어드민**
write 는 막지 않는다 — 즉 game-api 어드민 config write 는 server-status 선례대로 **합법**. legacy 도
`_admin1_submit.php` 가 KVStorage('game_env') 직접 write(턴 파이프라인 밖). 대칭이다.

`WorldStateReadEntity.config: var Map<String,Any?>` @jsonb (mutable). write =
`entity.config = entity.config + mapOf(key to value); world.save(entity)`.

**엔진 in-memory 영향:** npcmode/maxgeneral/startyear 등은 엔진이 per-turn 으로 읽지 않는다(npcmode 는
reset killturn 산정에만). 로비/진입 게이팅은 전부 DB-read → 라이브 config write 가 엔진 재시작 없이 즉시 반영.
**예외 turnterm**: world_state.tickSeconds(=turnterm*60)는 데몬이 읽는다 → turnterm 변경은 tickSeconds
재계산 + 데몬 인지가 필요(legacy ServerTool::changeServerTerm). MVP 에선 turnterm 제외 or 재시작 워크어라운드(§4 W22c).

## 3. 설정 항목 (legacy `_admin1.php`/`_admin1_submit.php` + install)

| key | 라벨 | 검증 | 비고 |
|---|---|---|---|
| msg | 운영자 메시지 | 문자열 | config["msg"] |
| maxgeneral | 최대 장수 | 1..999 | 등록마감 게이트 |
| maxnation | 최대 국가 | 1..99 | |
| startyear | 시작 년도 | 1..9999 | |
| npcmode | NPC 빙의 | 0\|1\|2 | **버그 2** |
| block_general_create | 장수 임의생성 | 0\|1\|2 | 로비 canCreate |
| turnterm | 턴 시간(분) | enum(1/2/5/10/20/30/60/120) | tickSeconds=*60, 데몬 인지 필요(W22c) |

값은 legacy 그대로(위조 금지). fiction/extend/showImgLevel/joinMode/tournamentTrig 등은 2차.

## 4. 단계 (각 = 측정→1가설→fresh 재채점→채택/원복 1바퀴)

- **W22a (backend, Docker IT):** game-api `POST /api/admin/game-settings` — `AdminGameSettingsUpdateRequest`
  (nullable 필드별 부분 갱신) → 검증(범위/enum) → `entity.config + 갱신` → `world.save` → 응답 updatedValues.
  admin-role gated(`requireAdmin` 재사용). **jsonb config write 가 미검증 경로**라 실DB IT 필수
  (write→read 라운드트립 + 잘못된 값 4xx + 비-admin 401/403). turnterm 제외(W22c).
- **W22b (frontend):** admin1/page.tsx read-only 인풋 → 편집가능 + 저장 버튼(POST) + 성공/에러 + 제출중 disable.
  `web/game/lib/api.ts` + `types.ts` 클라 메서드. 게이트 tsc + vitest.
- **W22c (turnterm, 선택):** turnterm 변경 시 tickSeconds 재계산 + 데몬 인지(ServerTool 등가) — 엔진 시seam
  필요. MVP 후 별도. 그 전엔 turnterm 편집 비활성 + "재시작 필요" 안내.

## 5. 버그 2 마감 절차(라이브)
W22a 배포 후, 운영자가 어드민에서 s1 npcmode=1 저장 → ServerBasicInfo DB-read 즉시 반영 → 로비 빙의 버튼
노출(바퀴 20 게이팅과 결합). 재시드(게임 리셋) 불요.

## 6. 리스크
- jsonb config write 미검증 → IT 필수(converter 양방향 확인).
- turnterm 라이브 변경 = 데몬 인지(W22c 분리).
- 부분 갱신: 미전송 필드는 보존(`config + 부분맵`), 전체 덮어쓰기 금지.
- 검증 실패 시 4xx + config 불변(원자성).

## 7. 참고 (file:line)
legacy: `_admin1.php:1-69` · `_admin1_submit.php:1-81` · `ServerTool::changeServerTerm`.
opensamguk: `AdminWriteController.kt`(server-status 선례) · `AdminReadController.kt`(blockedWrites) ·
`WorldStateReadRepository.kt`(Entity.config var jsonb) · `ServerBasicInfoController.kt:74`(npcMode read) ·
`web/game/app/game/admin1/page.tsx` · 로비 `web/gateway/app/lobby/page.tsx`(바퀴 20 게이팅).
