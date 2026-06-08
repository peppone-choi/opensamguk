# SESSION HANDOFF — 2026-06-08 (세션6: B1-B3 완결 + Wave1 계략 + constants.ts + agent-system)

다음 세션은 이 문서부터. 핵심은 git log.

> **브랜치 `parity-final` = origin 동기화, main보다 20 ahead, 미배포**
> **HEAD**: `9ba9430` · **상태**: 전부 커밋+푸시 완료, working tree clean

---

## 1. 세션6 완료 (커밋 20개, `parity-final`)

### A. B1 장수생성 — 완결 (4커밋)
- `6954552` B1 RNG 코어 — MakeGeneral.draw() draw-for-draw + choiceUsingWeight insertion-order 커널 패러티 수정
- `89205f8` B1 write-seam foundation — ChangeRecorder.createdGenerals + JdbcFlushExecutor general-create flush + GeneralCreateFlushIT
- `d92a6db` B1 end-to-end — MakeGeneral variant + handler + dispatcher + game-api intake + FE PageJoin
- `5e4f045` B1 로그 정확성 — Join.php:502-528 9개 로그 byte-match

### B. B2/B3 장수빙의/선택 — 완결 (3커밋)
- `8532064` 빙의 토큰 검증 — legacy NPC selection tokens required before possession
- `63f88c8` 빙의 데몬 write path — NPC possession persist through daemon
- `8963773` 빙의 데몬 publish — NPC possession claims published to daemon

### C. Wave 1 계략 + misc — 완결 (6커밋)
- `91002bc`~`6b5eff5` Wave 1 A1 계략 5종(화계/파괴/탈취/선동/첩보) + 단련 + 접경귀환 골든 게이트
- `7f4a085` 계략 로직 포팅 + SabotageInjury
- `6b5eff5` 계략 골든 게이트 마감 + 포팅 버그 3종 수정

### D. FE 하드코딩 제거 + constants.ts — 완결 (1커밋)
- `f8ffef1` constants.ts 중앙화 + tournament-admin 미구현 명시

### E. 기타 완결 (세션4-5 미커밋 더미)
- `166351e` nation.tech 월틱 0-덮어쓰기 실버그 수정
- `c668443` 정보 카드 raw 코드→한글명 해석 (장수/국가/도시 + 세력순위)
- `d18388c` 9 event_*연구 chief research commands (turn-reserved, deterministic)
- `434a196` 게임 메인 재디자인 (로비-정적 지도 + 클릭→도시 + PHP 풀카드)

### F. Agent-system 강화 (2커밋)
- `245c357` parity work drift 방지
- `9ba9430` cross-agent critique 필수화

---

## 2. 백로그 (남은 항목만)

### A. 패러티 명령 (Tier4 #15 잔여)
- **14종 남음** (Wave1 10종 완료):
  - 계략: che_선동 (포팅됨, 골든 미확인)
  - General: che_강행, che_숙련전환, che_전투태세, che_모반시도
  - Reset: che_전투특기초기화, che_내정특기초기화
  - CR: cr_인구이동
  - (기타 5종 — PARITY_LEDGER.md 참조)
- **방법**: parity-wave 스크립트(ring/deterministic 보정)로 배치 처리

### B. FE 액션 서피스 (SILENT-NO-OP 위험)
- **chief-center nation-command 예약 에디터** — 전체 MISSING-ACTION. ReserveCommand/ReserveBulkCommand/RepeatCommand/PushCommand/clipboard/presets 전부 미구현. 현재 100% read-only.
- **diplomacy send/destroy/rollback letter** — send-letter(자유형식) 미구현, destroy-letter(파기 요청) 미구현, rollback-letter 미구현
- **board 게시물 작성/댓글** — article_add, comment_add 미구현
- **troop 부대** — 부대 생성/해산/가입/탈퇴/장수이동 미구현
- **battle 전투 예약** — 출병/방어/철수 등 전투 명령 예약 미구현

### C. 이민족/NPC 이벤트 (12종)
- RaiseInvader, InvaderEnding, AutoDeleteInvader, RaiseNPCNation, RegNPC, RegNeutralNPC, CreateManyNPC, CreateAdminNPC, BlockScoutAction, UnblockScoutAction, ChangeCity, LostUniqueItem
- WorldActions.register 미등록, 시나리오 후반부 핵심

### D. 어드민 (10종)
- _119_b(시간조정), _admin1/2/5/7/8(게임관리/회원관리/일제정보/로그/외교), _admin_force_rehall
- 현재 admin/page.tsx는 placeholder 탭만

### E. 도메인/트리거/기타
- 장수 풀 추상화 (AbsGeneralPool 등)
- GeneralTriggerCaller + 4종 트리거 (che_도시치료/병력군량소모/부상경감/아이템치료)
- Constraint/AdhocCallback, ExistsAllowJoinNation
- ScoutMessage, RaiseInvaderMessage
- DTO: MenuItem/Line/Multi/Split, SelectItem, VoteInfo/VoteComment
- TextDecoration: DyingMessage, SightseeingMessage

### F. intake-api 미등록 (6종)
- General/DieOnPrestart, General/DropItem, General/InstantRetreat
- InheritAction/CheckOwner, InheritAction/ResetStat
- Misc/UploadImage

### G. read-api 미구현 (5종)
- Nation/GetGeneralLog (alias General/GetGeneralLog)
- Global/ExecuteEngine
- Global/GeneralListWithToken
- InheritAction/GetMoreLog

### H. 운영
- **빼섭 보급-동결 버그** — 미수정 (doNPC구출발령 빈 supplyCities→RandUtil.choice throw, 상류 1030 보급 발산). 가드=band-aid.
- **매 main 배포 = 턴 되감김** — 엔진 recreate→DB스냅샷 rehydrate. doc-only도 main push 금지.
- **nginx canonical** `infra/nginx/default.conf` — server-basic-info 블록 parity-final에만, main 미반영.

### I. 검증 미완
- **chief-center ChiefCommandReserve 제출 intake 왕복 end-to-end** — UI 배포됨, 실제 엔진 도달 미검증
- **Gateway-api JwtTokenProviderTest CI flaky** — 시계성, deploy.yml 비차단

---

## 3. 사용자 핵심 원칙

1. **하드코딩 금지.** 모든 표시값 = 실제 API + 기능 결과.
2. **PHP가 grand truth.** 모든 행동/로그/RNG는 devsam 충실 포팅.
3. 자율 머지+배포 OK (CI green 선결). 주석 한글, 식별자/wire/패러티로그 영문.
4. 커밋 끝 `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

---

## 4. dev 환경 상태

- **pnpm dev :3002** — web/game 핫리로드 (도커 백엔드 game-api:8081 프록시)
- **전체 docker 스택** — web-game:3001/gateway:8080/game-api:8081/engine:8082/nginx:80/pg:5433
- **gradle** — `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew ...`, 출력 tail+XML 검증
- **EC2** — `3.37.232.176`, ssh `-i ~/.ssh/id_ed25519 ubuntu@`

---

## 5. 감사 산출물 (docs/superpowers/gap/)

- `MASTER_GAP.md` — 전수 패러티 감사 (768단위, 457비교, 202미포팅, 100부분포팅)
- `FE_OUTPUT_ACTION_GAP.md` — FE 액션 페이지 갭 (chief-center/diplomacy/board/troop/battle)
- `FE_OUTPUT_READ_GAP.md` — FE read 페이지 갭
- `FE_STRUCTURE_GAP.md` — FE 구조 갭
- `LOGIC_GAP.md` — 로직 갭
- `API_GAP.md` — API 갭
- `READ_DTO_GAP.md` — read DTO 갭
- `HARDCODE_INVENTORY.md` — 하드코딩 인벤토리
- `PARITY_RECONCILED.md` — 패러티 조정 이력
- `WAVE_COVERAGE_REVIEW.md` — 웨이브 커버리지 리뷰
- `EXECUTION_PLAN.md` — 실행 계획
- `_full_audit_2026-06-07.raw.json` — 원시 감사 데이터 (5.1MB)

---

(이하 세션5/4/3 기록 — 아카이브 목적, 참조만)

# SESSION HANDOFF — 2026-06-07 (세션5: B1 코어+FE 재디자인 배포대기)
[아카이브 — 모든 미커밋 더미는 세션6에서 커밋 완료]

# SESSION HANDOFF — 2026-06-07 (세션4: B1 장수생성 RNG 코어 골든 게이트)
[아카이브 — B1 RNG 코어는 `6954552`에 커밋됨]

# SESSION HANDOFF — 2026-06-06 (세션3: 9 event_연구 + K1/K2 + 메인 크래시 근본수정)
[아카이브 — K1/K2/event_연구/메인재디자인 모두 커밋 완료]
