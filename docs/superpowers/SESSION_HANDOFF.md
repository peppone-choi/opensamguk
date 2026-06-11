# SESSION HANDOFF — 2026-06-12 (세션8: page-parity 루프 바퀴 2~26 + 재채점 10-바퀴 + W-1/W-3/W-4/W-9 수정)

다음 세션은 이 문서 + `docs/loops/page-parity/LEDGER.md`(정본 원장)부터.

> **main 직커밋·직푸시 체제 (06-11부터 PR 없이 직행). 이 핸드오프 커밋 기준 main = origin 동기화.**

## 0. 세션8 완료 (06-11 ~ 06-12, 전부 main)

- **page-parity 루프 바퀴 2~19** (06-11): P0-01 예약명령 실소비 / P0-12 city fallback / P0-27 statMin·Max / P0-18 crew 제거 / P0-17 prevNo selector / P0-26 유니크 경매 1차(후일 정정) / P0-10·P0-02 당기기·미루기·반복 버튼 / P0-28 mailbox 마스킹 1차(후일 정정) / P0-14 守 위조 '-' 마스킹. 부수: Next.js 15.1.3→15.5.19 보안 패치(`ddb0b6d`).
- **바퀴 20** (`ca419fa`): P0-07 PlaceBetHandler ← PHP `Betting::bet()` 전량 포팅 + **inheritance KV 판별자 'game_kv'→'inheritance' 근본수정**(V15 백필) — 데몬 inheritance 쓰기 전부 고아행이던 실버그.
- **재채점 워크플로** (wf_89ed4731 + `docs/superpowers/gap/regrade-2026-06-12/`, critic 10-바퀴): 바퀴 15·18 판정 뒤집힘(정정·재오픈), W-1~W-10 신규 발견.
- **바퀴 22** (`19dba54`): W-3 — 바퀴 18 over-mask 회귀 수정(diplomacy type 게이트 + 단건 마스킹).
- **바퀴 23** (`de06cff`): W-1 — 경매 위조 로그 push 6사이트 제거. **log_scope enum 외 값 1건이 flush BatchUpdateException 틱 롤백 = 턴 동결 지뢰**였음.
- **바퀴 24** (`170a960`): W-9 — P0-26 재닫음. FE 미등록 코드 `OpenUniqueAuction`→정본 `auctionOpenUnique`+`{itemId,amount}` 교체(휴식 턴 잠복 위조 소멸).
- **바퀴 25** (`a38baa8`): P0-23 — `InheritCatalog` 신설(특기 20 + 유니크 100), 실PHP Docker 2회 byte-동일 추출.
- **바퀴 26** (`db80c05` + 후속 경계수정): W-4 — AuctionBidHandler 환불 복제/미달차감/유산포인트 미차감 3결함 근절(PHP `_bid`/`bidInheritPoint`/`refundBid` 정합). **적대 리뷰(grader-w26) FAIL→PASS 2라운드**: 유니크 래퍼 부위 가드 2종 + obfuscatedName 풀 디코드 + aux.ownerName + tryExtendCloseDate 경로별 고정 + wall-clock 차단 + 유니크 finished 메시지 + 부수효과 순서 + isunited 게이트 + 환불 현재-owner 재해석 + npc>=2 검증 + wire `extendCloseDate` 키. AuctionBidHandlerTest 33종. 아티팩트 `docs/superpowers/reviews/2026-06-12-w4-auction-bid-refund-review.md`.

**게이트 수치 (바퀴 26 시점)**: logic 2123 · engine 350 · game-api 301 · infra 87 · common 192, 전부 green.

## 0b. 다음 세션 우선순위

1. **재채점 잔여 4건** (LEDGER 백로그): W-6 NF income null 크래시 · W-7 NF 권한 게이트 · W-8 nation_env read 채널(setBlockWar 100% deny) · W-10 che_선전포고 위조 로그 골든.
2. LEDGER 백로그 나머지(read-api 4종, intake 6종, statistic 골든 latent 3건, OpenNationBetting 미스포트, 경매 PHP 실로그 byte-port, 유니크 1순위 가드 pending 경매 비가시, 빼섭 보급-동결 등) — 가설 1개 = 바퀴 1개.
3. 배포 후 실서버 검증(턴 전진 + 로그인 + 경매/베팅 경로) — main push = 자동 배포임을 항상 전제.

---

# SESSION HANDOFF — 2026-06-10 (세션7: 턴동결 핫픽스 + 페이지 패러티 W0/W1 + 루프 가동)

다음 세션은 이 문서부터. 핵심은 git log + `/workflows` + TaskList.

> **main = 전부 머지·배포됨 (PR #68~#79, 오늘 10+개). parity-final 브랜치는 폐기됨(전부 main에 흡수).**

## 0. 즉시 확인할 것 (재개 절차)

1. **W1 워크플로 살아있나**: run ID `wf_3fc9274f-9fc`, 스크립트
   `~/.claude/projects/-Users-apple-Desktop--------opensamguk/ee322d2d-d0c5-4ea8-a227-175a9874a611/workflows/scripts/w1-page-parity-wave-wf_3fc9274f-9fc.js`.
   죽었으면 `Workflow({scriptPath, resumeFromRunId: "wf_3fc9274f-9fc"})` — 완료 에이전트는 캐시 재개.
   구조: W0게이트워처 → 배치1보안(D board/L mailbox/C betting/H generals/O nation-finance) →
   배치2크래시(F city/G diplomacy/E chief/B auction/M map) → 배치3위조(A main/I history/J inherit/K join/N my-*) →
   LEDGER 웨이브기록 → s1/spep 바운스+실서버검증.
2. **W0 잔여 3종** (재개 에이전트가 기존 워크트리에서 작업): 워크트리 = `.claude/worktrees/agent-{aa9f04ab*,a43b4a83*,a521e815*}`
   = W0-7 wire(`w0/7-wire-contract`) / W0-4 결과채널(`w0/4-intake-result-channel`) / W0-8 infra(`w0/8-infra-flush-migrations`).
   푸시되면 워크플로 게이트워처가 자동 PR+머지. 에이전트 사망 시: 워크트리 잔존물 회수 절차(아래 §4) 후 재발사.
3. **세션 리밋 이력**: 두 번 충돌(13:2x, 15:2x — 15:30 리셋). 죽은 에이전트 = 출력파일 145바이트 헤더 동결 + 워크트리 무활동으로 판별.

## 1. 세션7 완료 (전부 main 머지)

- **프로드 턴동결 근본수정** (#69): 신규월드 첫 연경계에서 `Json.encodeToString(aux: Map<String,Any?>)` 런타임 직렬화 예외 + `statistic` 테이블 DDL 부재(2층). → `StatisticInsertColumns`+`MetaJson` / `V13__statistic_table.sql` / `StatisticFlushIT`(실DB) / 리버트가드. **s1/spep 바운스 후 턴 전진 검증 완료**. latent 3건(aux dict-vs-array 등)은 statistic 골든 백로그.
- **장수 등록 UI 패러티** (#68): npcmode 0/1/2 3서피스 + blockGeneralCreate.
- **하드코딩 단계3** (#70): OFFICER_LEVEL_TEXT→F4StateText / mapWidth·Height→map json 로더 / INHERIT_COSTS→API 소비. 잔여 = 단계5 mutation 3건(W1 합류) + 단계6 BLOCKED 4건.
- **루프 엔지니어링 가동**: `docs/loops/page-parity/` GOLDENSET(승인·동결)+LEDGER. **1바퀴 = Nation/GetGeneralLog 포팅(#71)** — 405→406 suites, fresh 채점 PASS. General alias self-view 변형은 백로그.
- **페이지 패러티 감사** (#71에 동봉): `docs/superpowers/gap/PAGE_PARITY_AUDIT_2026-06-10.md` — 20페이지 P0 54/P1 84+/P2 56+, W0 8종+W1 A~O 웨이브 계획. ⚠️ W0-3가 감사의 BLOCKED 주장 일부 반박(penalty 컬럼/meta 키 실존) — BLOCKED 주장은 실측 후 수용.
- **W0 파운데이션 6/8 머지**: W0-1 FE와이어(#73, IntakeOutcome=성공토스트위조 근원차단) / W0-2 DTO(#75+#76) / W0-3 권한 단일소스(#74, PHP 전분기+기존버그 3종 교정) / W0-5 log read(#72, SUMMARY+ACTION 합집합 발견) / W0-6 맵뷰어(#78, 두 맵뷰어 불변식).
- **운영**: 502 원인 실측(배포 경합→gateway-frontend Created 방치+nginx stale-DNS) → 즉시 복구 + deploy.yml concurrency 직렬화(#79). repo `allow_auto_merge=true` — **PR 생성 즉시 auto-merge가 표준**(사용자 지시). 묵력→무력 오타(#77).

## 2. 사용자 지시 (이 세션에서 추가된 것)

1. PR 올리면 자동 머지 (auto-merge 표준).
2. W1 끝나면: LEDGER 웨이브별 기록 + prod 배포 + 실서버 검증까지.
3. W1 후 빼섭 보급-동결 버그를 루프 바퀴로 마감 (Task #8).
4. 페이지 패러티 = 내용+기능+배치+백엔드+게임데이터 **전부**. 컴포넌트 사용·구조 = 레거시 Vue 정본, 모더나이즈는 스타일만. 무장(장수) 생성(join) 풀 패러티 강조.
5. 워크플로 도구로 오케스트레이션.

## 3. 남은 것 (TaskList와 동기)

- W0-4/7/8 마감 → W1 15페이지(워크플로가 자동 진행) → LEDGER → 배포검증 (Task #6,#7)
- 빼섭 보급-동결 바퀴 (Task #8) — doNPC구출발령 빈 supplyCities, 상류 보급계산 발산, 로컬 1030 재현
- gateway-api JwtTokenProviderTest flaky — 오늘 deploy 2회 실패 원인, 근본수정 필요
- 로컬 스택: web 3종 최신, **백엔드 3종 구이미지**(빌드 OOM) — `docker compose up -d --build` 재시도 (게이트와 동시 실행 금지, OOM 재발)
- nation-finance 감사 truncated 재감사(W1-O가 처리 예정) · battle-center 페이지 MISSING(백로그)
- prod statistic INSERT 실증(182.1 도달 시) · 어드민 표면 QA · Tier4 잔여 명령 등 기존 백로그(세션6 §2 유효)

## 4. 죽은 에이전트 회수 절차 (확립됨)

1. 워크트리 확인: `git -C <wt> log --oneline origin/main..HEAD` + `git status --short`
2. 미커밋 RED 테스트는 패치로 분리(`git diff > /tmp/x.patch && git checkout -- <f>`)
3. 게이트 재검증(XML) → 회수 크리틱 아티팩트 작성("Verdict: cleared") → strict check → push → PR
4. 잔여 단위는 백로그/후속 바퀴로 (W0-2→W0-2b 선례)

## 5. 환경/접속 (변경분)

- prod 멀티서버: 공유 스택 + s1(통일 서버)/spep 스택. 바운스: `docker compose -p opensamguk-{srv} -f ~/opensamguk/docker-compose.server.yml --env-file .env --env-file servers/{srv}.env up -d` (선행 docker pull). 502 시: Created 컨테이너 start + `nginx -s reload`.
- 어드민 peppone / (로컬 메모리 참조). EC2 `ssh -i ~/.ssh/id_ed25519 ubuntu@3.37.232.176`.

---

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
