# LEDGER — page-parity 루프

행 형식: `| 바퀴 | 가설 | 점수 전→후 | 채점자 | 판정 | 원인 한 줄 |`

| 바퀴 | 가설 | 점수 전→후 | 채점자 | 판정 | 원인 한 줄 |
|---|---|---|---|---|---|
| 0 | (베이스라인) | BE 405/405 suites (2925 tests) green + FE tsc×2 clean + web/game 42/42 | gate.sh backend XML + pnpm (결정적) | 기준선 | main c75d0f9 (=be9916d+루프파일), 2026-06-10 12:06 KST |
| 1 | Nation/GetGeneralLog read API 포팅(§2G) — 4 reqType + 권한사슬 + id<reqTo 페이지네이션 | 405→406 suites green + 갭 1 닫힘 (신규 스위트 10 tests red→green 관찰) | fresh 서브에이전트(a1cbf2aa) — XML 판독 + PHP 대조, score: PASS | 채택 | 1차 게이트런 TC 기동실패 2건은 도커 OOM 플레이크(재실행 green으로 입증) |
| 2 | P0-01 예약명령 패널 하드코딩 '휴식' 위조 제거 — `GET /api/reserved-commands` 실제 소비 | 409→409 suites green + FE tsc clean + 갭 1 닫힘 | fresh 서브에이전트(ce-testing-reviewer) — 코드 리뷰 + gate/tsc 검증, score: PASS | 채택 | `PartialReservedCommand.tsx`: `useEffect`로 `api.reservedCommands()` 호출, `slotMap` 매핑, 빈 슬롯만 '휴식', 예약 후 `refreshKey` 증가 |
| 3 | `my-boss` P0-38 DTO 필드 불일치 수정 — `General` 캐스트 제거, `MyBossResponse` 실제 소비, 재야 가드 복원 | BE 409 green + FE tsc clean + 갭 1 닫힘 | 직접 수정 + fresh 서브에이전트(tsc) | 채택 | `boss.name`→`boss.bossName`, `boss.officerLevel`→`boss.bossOfficerLevel`, `if (!boss)`→`nationId===0 \| !hasBoss` |
| 4 | `my-cities` P0-45 클라이언트 정렬 9버튼 추가 — `extExpandCity.ts` 패러티 | BE 409 green + FE tsc clean + 갭 1 닫힘 | 에이전트 병렬 실행 + tsc PASS | 채택 | 이름·등급·민심·농업·상업·치안·수비·성벽·인구 9종 `useMemo` 정렬, 버튼 클릭 시 방향 토글 |
| 5 | `BettingDetail` 베팅 제출 가드 추가 — 후보 미선택/금액 0 방지 | BE 409 green + FE tsc clean + 갭 1 닫힘 | 에이전트 병렬 실행 + tsc PASS | 채택 | `pickedBetType.size===0`→toast+return, `betPoint<=0`→toast+return, 버튼 disabled 연동 |
| 6 | P0-09 사령부 예약 nation_turn 교체 — `CommandModal` `isNationCommand` + `api.commandQueue.nationBulk` | BE 409 green + FE tsc clean + 갭 1 닫힘 | Workflow 에이전트 병렬 수정 + tsc PASS | 채택 | 기존 `api.command`(general_turn) → `nationBulk`(nation_turn)로 사령부 예약이 올바른 링 기록 |
| 7 | P0-13 `city` 부상 장수 수치 감산 — `StatCell` 컴포넌트 추가 | BE 409 green + FE tsc clean + 갭 1 닫힘 | Workflow 에이전트 수정 + tsc PASS | 채택 | `injury>0` 시 `Math.trunc(value*(100-injury)/100)` 빨강 렌더, legacy `intdiv(value*(100-wound),100)` 패러티 |
| 8 | P0-24 `inherit` 능력치 초기화(ResetStat) 폼 UI + `api.resetStat` 헬퍼 | BE 409 green + FE tsc clean + 갭 1 닫힘 | Workflow 에이전트 수정 + tsc PASS | 채택 | 기본3(통/무/지)+추가3(통+/무+/지+) 입력 폼, `POST /api/instant-action/ResetStat` 연동, 유산포인트 가드 |
| 9 | P0-04 `auction` 입찰/등록/개설 호출부 `isIntakeQueued`/`isIntakeDenied` 가드 적용 | BE 409 green + FE tsc clean + 갭 1 닫힘 | fresh 서브에이전트(ce-testing-reviewer) — tsc + 코드 리뷰, score: PASS | 채택 | `AuctionResource`/`AuctionUniqueItem` 3개 호출부에 가드 적용, unconditional success toast 제거 |
| 10 | P0-06 `BettingDetail` 베팅 제출 호출부 `isIntakeQueued`/`isIntakeDenied` 가드 적용 + implicit else | BE 409 green + FE tsc clean + 갭 1 닫힘 | fresh 서브에이전트(ce-testing-reviewer) — tsc + 코드 리뷰, feedback: implicit else 추가 | 채택 | `submitBet`에 가드 적용, 채점자 feedback 반영(implicit else→'베팅 처리 중 오류가 발생했습니다.') |
| 11 | P0-12 `CityDetailController` id<=0 → general.cityId fallback (legacy b_currentCity.php 패러티) | 409→409 suites green + FE tsc clean + 갭 1 닫힘 | gate.sh backend XML + pnpm tsc (결정적) | 채택 | id<=0 시 `findById(0)` null→404. general 먼저 resolve 후 `effectiveId=general.cityId`로 city 조회. |
| 12 | P0-27 `InheritPointController` statMin/statMax 하드코딩 10/90 → `GameConst.defaultStatMin/Max` 15/80 | 409→409 suites green + FE tsc clean + 갭 1 닫힘 | gate.sh backend XML + pnpm tsc (결정적) | 채택 | legacy `v_inheritPoint.php:108-114` = 15/80. 구현이 날조된 10/90 사용. F4 테스트 기대값 동시 교정. |
| 13 | P0-18 public generals list에서 `crew` 컬럼 제거 — legacy GeneralList/Global/GeneralList는 permission=0 표면에 병력 미노출 | 409→409 suites green + FE tsc clean + 갭 1 닫힘 | gate.sh backend XML + pnpm tsc (결정적) | 채택 | `PublicGeneral` DTO·`GeneralsController`·FE `generals/page`·F4 테스트 동시 제거. |
| 14 | P0-17 diplomacy 서신 작성 폼에 '이전 문서' selector 추가 — prevNo 하드코딩 null 제거 | 409→409 suites green + FE tsc clean + 갭 1 닫힘 | gate.sh backend XML + pnpm tsc (결정적) | 채택 | `letters.filter(state==='activated')`를 선택지로 제공, `prevNo` state 연동, `api.command`에 전달. |
| 15 | P0-26 inherit 페이지 '유니크 경매' 섹션 활성화 — disabled select→활성 + '경매 시작' 버튼 + `OpenUniqueAuction` CommandModal 연동 | 409→409 suites green + FE tsc clean + 갭 1 닫힘 | gate.sh backend XML + pnpm tsc (결정적) | 채택 | `BuyModalSpec`에 `OpenUniqueAuction` 추가, `selectedUnique` state, `extraArgs: { item }` 전달. |
| 16 | P0-10 chief-center 당기기/미루기/반복 버튼 추가 — `api.commandQueue.nationPush`/`nationRepeat` 연동 | 409→409 suites green + FE tsc clean + 갭 1 닫힘 | gate.sh backend XML + pnpm tsc (결정적) | 채택 | `ChiefCommandReserve`에 numeric input + 적용 버튼 2종, `generalId` prop widen, `chief-center/page` 전달. |
| 17 | P0-02 개인 예약 명령 당기기/미루기/반복 버튼 추가 — `api.commandQueue.push`/`repeat` 연동 | 409→409 suites green + FE tsc clean + 갭 1 닫힘 | gate.sh backend XML + pnpm tsc (결정적) | 채택 | `PartialReservedCommand`에 numeric input + 적용 버튼 2종, `onToast`로 성공/실패 알림, 예약 후 `refreshKey` 증가. |
| 18 | MailboxController `mailbox`/`unread` diplomacy 마스킹 적용 — `secretPermission` + `applyDiplomacyMask` | 409→409 suites green + FE tsc clean + 갭 1 닫힘 | gate.sh backend XML + pnpm tsc (결정적) | 채택 | `mailbox()`/`unread()`에 `secretPermission`+`applyDiplomacyMask` 추가, 테스트 `generals.findAll()` stub 보강 |
| 19 | (삭제 바퀴) P0-14 city 守·수비○ 위조 표시 '-' 마스킹 — defence_train 원천 배선 전 fabrication 제거 + `formatDefenceTrain` import 제거 | BE diff-0(409 유지) + FE tsc clean + web/game 65/65 + 갭 1 닫힘(p) | fresh 서브에이전트(grader-w19) — legacy b_currentCity.php:304-313,434 대조 + tsc/test, VERDICT PASS | 채택 | 빼기 주기 이행(14~18 더하기 연속). 근본(defence_train read-chain 배선)은 백로그 유지 |
| 20 | P0-07 PlaceBetHandler ← PHP `Betting::bet()` 전량 포팅 — 마스터/finished/마감/미시작/선택수/purify(print_r)/누적1000(pending 합산)/유산·금 분기 + deny 문자열 byte-동형 + min-10(valitron 추적 "Amount 은(는) 10 이상이어야 합니다.") + flush UPSERT(amount +=) + 비패러티 로그 제거 + userId typed 채널/loader 적재 + inheritance KV 판별자 'game_kv'→'inheritance' 근본수정(V15 백필) | 409→411 suites green (logic 2109·engine 331·infra 87·api 297·common 192) + 갭 1 닫힘(신규 PlaceBetHandlerTest 18 + BettingFlushIT) | fresh 서브에이전트(grader-w20, parity-reviewer) — 3라운드 적대 채점 FAIL→FAIL→PASS, vendor valitron 소스 byte-추적 포함 | 채택 | 채점이 잡은 추가 근본버그 2건 동반 수정: ① 음수 amount 금 채굴 경로 ② 데몬 inheritance KV 쓰기 전부 고아행(reader와 "table" 판별자 불일치) |
| 15-정정 | (재채점 판정) 바퀴 15 P0-26은 NOT-FIXED — FE가 미등록 코드 `OpenUniqueAuction`(정답 `auctionOpenUnique`) + 잘못된 인자 `{item}`(정답 `{itemId,amount}`) 전송 → CommandRegistry else→RestAction Allow→휴식 턴 잠복 위조 | — | fresh 재채점 워크플로(wf_89ed4731, audit-delta 에이전트) | 정정(재오픈) | 바퀴 15 채점이 tsc+게이트만 보고 intake 코드 매칭을 검증 안 함. 바퀴 24로 재오픈 |
| 18-정정 | (재채점 판정) 바퀴 18이 P0 회귀 유발 — `applyDiplomacyMask` type 미검사로 일반 개인/국가 서신까지 위조 마스킹(legacy GetRecentMessage.php:125-139는 diplomacy 한정) + `GET /api/messages/{id}` 단건 마스킹 0(누출 잔존) | — | fresh 재채점 워크플로(wf_89ed4731, audit-delta 에이전트) | 정정(재오픈) | 바퀴 18 테스트가 마스킹 적용만 단언, 비외교 서신 통과를 단언 안 함. 바퀴 22로 재오픈 |
| 21 | (검증만) P0-50 BuyHiddenBuff/BuyRandomUnique generalId 400 — 이미 W0-1(#73, 9222bf2)에서 닫힘 확인: null 가드 + `api.command(…, generalId)` 전달 + front-info 배선 | 변경 0 (코드 직독 검증) | 본인 코드 직독 + git log (결정적 사실 확인) | 기닫힘 확인 | 재채점 api-surface의 해당 finding은 감사 문서 인용의 stale — 실코드 미반영. 바퀴 미소진 |
| 22 | 바퀴 18 회귀 수정 — `applyDiplomacyMask`에 `type == DIPLOMACY` 게이트(legacy diplomacy-섹션 한정 구조의 등가) + `GET /api/messages/{id}` 단건 마스킹 적용(P0-34 잔여 누출 차단) | game-api 297→301 green (Mailbox 13/13, 신규 매트릭스 4) + 갭 1 닫힘 + 회귀 핀(pre-fix면 신규 테스트 red) | fresh 서브에이전트(grader-w22, parity-reviewer) — GetRecentMessage/GetOldMessage/MessageTarget 대조, 5개 호출지 sweep, VERDICT PASS | 채택 | 비인증(currentGeneral null → permission -1)은 마스킹 방향(비누출)으로 동작 |
| 24 | P0-26 재닫음(바퀴 15-정정) — inherit 유니크 경매를 정본 intake로 교체: `OpenUniqueAuction`+`{item}` → `auctionOpenUnique`+`{itemId,amount}`(OpenUniqueAuction.php:33-39) + 입찰 포인트 입력(min 프리필/max=previous/int 강제, Vue:455,70-71) + '유산 포인트가 부족합니다.' 가드(Vue:624-627) | FE tsc clean + web/game 65/65 + 휴식-턴 잠복 위조 경로 소멸(intakeCodes 경유 확인) | fresh 서브에이전트(grader-w24, parity-reviewer) — wire 계약 end-to-end 추적 + P2 3건 반영 재확인, VERDICT PASS×2 | 채택 | P1: BE `availableUnique` emptyMap(P0-23)이 버튼을 dark 상태로 막음 — 바퀴 25에서 닫아야 본 경로 실가동 |
| 23 | (삭제 바퀴) W-1 경매 위조 로그 push 6사이트 제거 — Bid 1 + Finalize 4(유찰/연장/유산차감/일반) + Expiry 1. scope "action"/category "auction"은 PG enum 외 → 로그 1건이 flush BatchUpdateException 틱 롤백(턴 동결 지뢰). PHP 대조: 제거 문자열 전부 legacy 무존재(위조), bid 경로는 PHP 무로그 | engine 52 suites/331 green + 턴동결 클래스 소멸(잔존 LogEntryDraft 전수 enum-안전 확인) | fresh 서브에이전트(grader-w23, parity-reviewer) — 제거 문자열 legacy 전수 grep 0히트 + 잔존 push enum sweep, VERDICT PASS (P2 3) | 채택 | PHP 실로그(AuctionUniqueItem.php:337-351, AuctionBasicResource.php:132,197-222) byte-port는 골든 캡처 동반 백로그 |

## 백로그 (바퀴 후보 — 가설 1개 = 바퀴 1개)

- read-api 미구현: `Nation/GetGeneralLog`(=General alias), `Global/ExecuteEngine`, `Global/GeneralListWithToken`, `InheritAction/GetMoreLog` (핸드오프 §2G)
- intake 미등록 6종: General/DieOnPrestart·DropItem·InstantRetreat, InheritAction/CheckOwner·ResetStat, Misc/UploadImage (§2F)
- statistic 골든 latent 3건: nations.all dict-vs-array / avg 키순서·pre-round / crewtype 3VL (2026-06-10 핫픽스 리뷰)
- (MISSING) battle-center 페이지 — 페이지 감사 워크플로 결과 대기
- 페이지 감사 P0 목록 — `PAGE_PARITY_AUDIT_2026-06-10.md` 산출 후 바퀴로 분해
- 빼섭 보급-동결 상류 버그 (doNPC구출발령 빈 supplyCities)
- General/GetGeneralLog self-view 변형 (checkPermission 무력화 — 재야 포함 본인 로그 열람, PHP General/GetGeneralLog.php:43-50) — 바퀴1 채점자 발견
- secretPermission 단일소스화 (GeneralLogController ↔ DiplomacyController 중복) + penalty/ambassador/auditor/secretlimit 분기 (schema BLOCKED)
- 페이지 감사 P0 54건 — PAGE_PARITY_AUDIT_2026-06-10.md (W0 파운데이션 8종 → W1 A~O 웨이브)
- P0-14 근본: GeneralReadEntity `defence_train` read-chain 배선(스키마+flush) → 守/수비○ 마스킹 해제 + formatDefenceTrain 복원
- 재채점 2026-06-12 (docs/superpowers/gap/regrade-2026-06-12/, critic 10-바퀴): W-1 betting/auction 비패러티 로그 log_scope enum 턴동결(바퀴23) · W-3 mailbox over-mask 회귀(바퀴22) · W-4 경매 환불 자원복제 · W-6 NF income null 크래시 · W-7 NF 권한 게이트 · W-8 nation_env read 채널(setBlockWar 100% deny) · W-9 P0-26 정정(바퀴24) · W-10 che_선전포고 위조 로그 골든
- OpenNationBetting 미스포트 (logic/event/OpenNationBetting.kt:43,71,73) — reqInheritancePoint false 오기록(PHP true), openYearMonth -1 누락, closeYearMonth +120 날조, candidates 인덱스 키(PHP nation id). 바퀴20 채점자 발견 — bet() 유산 분기를 죽은 코드로 만들던 마스크
- previousPointReader dead-default 3중복 (PlaceBetHandler/InheritResetHandler/dispatcher 폴백 — world meta `inheritancePrevious`는 main 코드 어디서도 미적재) — 공유 seam으로 수렴
- V15 백필 pre-seed IT — 구형('game_kv') 행 + 'inheritance' 쌍둥이 시드 후 migrate해 머지 방향 고정(바퀴20 채점 P2)
- che_견문/che_인재탐색 resolve() 빈 no-op STUB인데 PARITY_LEDGER DONE 분류 — silent no-op 턴 소진 (재채점 command-registry)
- GameConst availableChiefCommand '연구' 카테고리 — PHP ReserveCommand.php:47 거부, Kotlin 허용 divergence (재채점 command-registry)
- ResetStat instant-action intakeCodes 미등재 → FE 호출 무조건 409 (재채점 api-surface — 바퀴 8 FE 폼의 BE 짝)
- 경매 PHP 실로그 byte-port — 유찰/성사/습득/유산차감: AuctionBasicResource.php:132(pushAuctionLog),197-203,220-222 + AuctionUniqueItem.php:345(습득)/:351(UserLogger inheritPoint). 골든 캡처 동반. 동시에 `AuctionResultCalculator.logMessage` 죽은 위조 문자열 8개 삭제(바퀴23 채점 P2 — 재배선 지뢰)

## 승인 대기

- 없음 (GOLDENSET.md 2026-06-10 승인·동결)
