# 커맨드 레지스트리 재감사 — PHP 93 커맨드 vs Kotlin CommandRegistry (2026-06-12)

> READ-ONLY 실측. Grand truth = `legacy/devsam-core/hwe/sammo/Command/{General,Nation}/`.
> 실측 대상 = `logic/src/main/kotlin/opensamguk/logic/actions/` + `CommandRegistry.kt` +
> `logic/src/test/**Golden*` + `app/game-api` 와이어링 + `docs/superpowers/GAP_AUDIT.md` /
> `docs/superpowers/PARITY_LEDGER.md` 클레임 대조.

## 판정 기준

- **PHP 모집단**: `Command/General/*.php` 55개 + `Command/Nation/*.php` 38개 = **93개** (휴식은 양쪽에 각 1개 — 파일시스템 실측, PARITY_LEDGER.md:27-31 동일).
- **PORTED+Golden**: Kotlin 구현이 `CommandRegistry.resolve()`에 등록돼 있고, PHP 캡처/인용 기반 byte/draw assert 골든 테스트(전용 또는 패밀리 골든)가 존재.
- **PORTED no golden**: 구현+등록은 완료, 골든 부재(유닛 테스트만 또는 무테스트).
- **STUB**: 등록 + argTest/constraints는 포팅됐으나 `resolve()` 본문이 빈 no-op(부수효과·로그·RNG 미실행).
- **MISSING**: Kotlin 구현 자체가 없음.
- 골든 매핑은 코드 문자열 grep + 그룹 골든(Develop/Military/Nation/Personnel/Trade/Founding) 내부 클래스 참조 직접 확인으로 판정. PersonnelGoldenTest는 등용·하야만, FoundingGoldenTest는 거병만 커버함을 본문 확인(PersonnelGoldenTest.kt:23,50 / FoundingGoldenTest.kt:29).

## 총괄

| 분류 | 수 |
|---|---|
| PORTED + Golden | **72** (General 39 + Nation 33) |
| PORTED no golden | **19** (General 14 + Nation 5) |
| STUB (resolve 빈 no-op) | **2** (che_견문, che_인재탐색) |
| MISSING | **0** |

레지스트리 등록률은 93/93 (CommandRegistry.kt:108-211 + RestAction fallback). GAP_AUDIT.md의 "19 PORT_MISSING"은 stale (아래 §닫힌 항목 검증).

## 전체 표 — General 55

| 커맨드 코드 | PHP 파일 | Kotlin 상태 | 근거 (impl / golden) |
|---|---|---|---|
| che_NPC능동 | Command/General/che_NPC능동.php | PORTED no golden | military/CheNpcNeungdong.kt (LOGIC_ONLY, NPC 전용·P5 게이트 간접) |
| che_강행 | che_강행.php | PORTED+Golden | military/CheGanghaeng.kt / CheGanghaengGoldenTest.kt |
| che_거병 | che_거병.php | PORTED+Golden | founding/CheGeobyeong.kt / FoundingGoldenTest.kt:29 |
| che_건국 | che_건국.php | PORTED no golden | founding/CheGeonguk.kt (GeongukTest.kt 유닛만) |
| che_견문 | che_견문.php | **STUB** | develop/CheGyeonmun.kt:39 — resolve 빈 no-op (finding F2) |
| che_군량매매 | che_군량매매.php | PORTED+Golden | develop/CheGunryangMaemae.kt / TradeGoldenTest.kt |
| che_귀환 | che_귀환.php | PORTED no golden | military/CheGwihwan.kt (unique-lottery seam :62, F6) |
| che_기술연구 | che_기술연구.php | PORTED+Golden | develop/CheGisulYeongu.kt / DevelopGoldenTest.kt |
| che_내정특기초기화 | che_내정특기초기화.php | PORTED+Golden | personnel/CheNaejeongTeukgiChogihwa.kt / CheNaejeongTeukgiChogihwaGoldenTest.kt |
| che_농지개간 | che_농지개간.php | PORTED+Golden | CheNongjigaegan.kt / DevelopGoldenTest+CommerceActionLogGoldenTest |
| che_단련 | che_단련.php | PORTED+Golden | develop/CheDanryeon.kt / CheDanryeonGoldenTest.kt (lottery TODO :152) |
| che_등용 | che_등용.php | PORTED+Golden | personnel/CheDeungyong.kt / PersonnelGoldenTest.kt:23 |
| che_등용수락 | che_등용수락.php | PORTED+Golden | personnel/CheDeungyongSurak.kt / CheDeungyongSurakGoldenTest.kt |
| che_랜덤임관 | che_랜덤임관.php | PORTED no golden | personnel/CheRandomImgwan.kt (Q1 ORDER BY RAND 격리 문서화) |
| che_모반시도 | che_모반시도.php | PORTED+Golden | nation/CheMobanSido.kt / CheMobanSidoGoldenTest.kt |
| che_모병 | che_모병.php | PORTED+Golden | military/RecruitAlgorithm.kt / MilitaryGoldenTest.kt |
| che_무작위건국 | che_무작위건국.php | PORTED no golden | founding/CheMujakwiGeonguk.kt (GeongukTest 유닛만) |
| che_물자조달 | che_물자조달.php | PORTED+Golden | develop/CheMuljaJodal.kt / DevelopGoldenTest.kt |
| che_방랑 | che_방랑.php | PORTED no golden | personnel/CheBangrang.kt (genfound-방랑군 격리 문서화) |
| che_사기진작 | che_사기진작.php | PORTED+Golden | military/CheSagiJinjak.kt / MilitaryGoldenTest.kt |
| che_상업투자 | che_상업투자.php | PORTED+Golden | CommerceInvestment.kt / DevelopGoldenTest+CommerceActionLog |
| che_선동 | che_선동.php | PORTED+Golden | military/CheSeondong.kt / CheSeondongGoldenTest.kt |
| che_선양 | che_선양.php | PORTED no golden | founding/CheSeonyang.kt (G4 ORDER BY RAND 격리, LOGIC_ONLY) |
| che_성벽보수 | che_성벽보수.php | PORTED+Golden | CommerceInvestment.kt / DevelopGoldenTest.kt |
| che_소집해제 | che_소집해제.php | PORTED+Golden | military/CheSojipHaeje.kt / MilitaryGoldenTest.kt |
| che_수비강화 | che_수비강화.php | PORTED+Golden | CommerceInvestment.kt / DevelopGoldenTest.kt |
| che_숙련전환 | che_숙련전환.php | PORTED+Golden | military/CheSukryeonJeonhwan.kt / CheSukryeonJeonhwanGoldenTest.kt |
| che_요양 | che_요양.php | PORTED no golden | personnel/CheYoyang.kt (G14 gate-exempt, resolve :41 실재) |
| che_은퇴 | che_은퇴.php | PORTED no golden | personnel/CheEuntwe.kt (EuntweTest 유닛만) |
| che_이동 | che_이동.php | PORTED+Golden | military/CheIdong.kt / MilitaryGoldenTest.kt |
| che_인재탐색 | che_인재탐색.php | **STUB** | personnel/CheInjaeTamsaek.kt:47 — resolve 빈 no-op (finding F3) |
| che_임관 | che_임관.php | PORTED no golden | personnel/CheImgwan.kt (JoinTest 유닛만) |
| che_장비매매 | che_장비매매.php | PORTED+Golden | trade/CheJangbiMaemae.kt / TradeGoldenTest.kt (lottery seam :195) |
| che_장수대상임관 | che_장수대상임관.php | PORTED no golden | personnel/CheJangsuDaesangImgwan.kt (JoinTest 유닛만) |
| che_전투태세 | che_전투태세.php | PORTED+Golden | military/CheJeontuTaese.kt / CheJeontuTaeseGoldenTest.kt |
| che_전투특기초기화 | che_전투특기초기화.php | PORTED+Golden | personnel/CheJeontuTeukgiChogihwa.kt / CheJeontuTeukgiChogihwaGoldenTest.kt |
| che_접경귀환 | che_접경귀환.php | PORTED+Golden | military/CheJeopgyeongGwihwan.kt / CheJeopgyeongGwihwanGoldenTest.kt |
| che_정착장려 | che_정착장려.php | PORTED+Golden | develop/CheJeongchakJangnyeo.kt / DevelopGoldenTest.kt |
| che_주민선정 | che_주민선정.php | PORTED+Golden | develop/CheJuminSeonjeong.kt / DevelopGoldenTest.kt |
| che_증여 | che_증여.php | PORTED+Golden | trade/CheJeungyeo.kt / TradeGoldenTest.kt |
| che_집합 | che_집합.php | PORTED no golden | military/CheJiphap.kt (MoveAndGatherTest 유닛만) |
| che_징병 | che_징병.php | PORTED+Golden | military/RecruitAlgorithm.kt / MilitaryGoldenTest.kt |
| che_첩보 | che_첩보.php | PORTED+Golden | military/CheCheobo.kt / CheCheoboGoldenTest.kt |
| che_출병 | che_출병.php | PORTED+Golden | war/CheChulbyeong.kt / BattleReplayGateTest.kt (G1 draw-for-draw) |
| che_치안강화 | che_치안강화.php | PORTED+Golden | CommerceInvestment.kt / DevelopGoldenTest.kt |
| che_탈취 | che_탈취.php | PORTED+Golden | military/CheTalchwi.kt / CheTalchwiGoldenTest.kt |
| che_파괴 | che_파괴.php | PORTED+Golden | military/ChePagoe.kt / ChePagoeGoldenTest.kt |
| che_하야 | che_하야.php | PORTED+Golden | personnel/CheHaya.kt / PersonnelGoldenTest.kt:50 |
| che_해산 | che_해산.php | PORTED+Golden | founding/CheHaesan.kt / Che해산GoldenTest.kt (history 버킷 미assert — F9) |
| che_헌납 | che_헌납.php | PORTED+Golden | trade/CheHeonnap.kt / TradeGoldenTest.kt |
| che_화계 | che_화계.php | PORTED+Golden | military/CheHwagye.kt / CheHwagyeGoldenTest.kt |
| che_훈련 | che_훈련.php | PORTED+Golden | military/CheHullyeon.kt / MilitaryGoldenTest.kt |
| cr_건국 | cr_건국.php | PORTED no golden | founding/CrGeonguk.kt (GeongukTest 유닛만) |
| cr_맹훈련 | cr_맹훈련.php | PORTED+Golden | military/CrMaenghullyeon.kt / MilitaryGoldenTest.kt |
| 휴식 | 휴식.php | PORTED no golden | CommandRegistry.kt:96-101 RestAction — **로그 누락 divergence (F4)** |

## 전체 표 — Nation 38

| 커맨드 코드 | PHP 파일 | Kotlin 상태 | 근거 (impl / golden) |
|---|---|---|---|
| che_감축 | Command/Nation/che_감축.php | PORTED no golden | nation/CheGamchuk.kt (GamchukJeungchukTest 유닛만, UNGATED) |
| che_국기변경 | che_국기변경.php | PORTED+Golden | nation/CheGukgiByeongyeong.kt / NationGoldenTest.kt |
| che_국호변경 | che_국호변경.php | PORTED+Golden | nation/CheGukhoByeongyeong.kt / NationGoldenTest.kt |
| che_급습 | che_급습.php | PORTED+Golden | nation/CheGeupseup.kt / Che급습GoldenTest.kt |
| che_몰수 | che_몰수.php | PORTED+Golden | nation/CheMolsu.kt / Che몰수GoldenTest.kt |
| che_무작위수도이전 | che_무작위수도이전.php | PORTED no golden | nation/CheMujakwiSudoIjeon.kt (CheondoTest 유닛만, UNGATED) |
| che_물자원조 | che_물자원조.php | PORTED+Golden | nation/CheMuljaWonjo.kt / Che물자원조GoldenTest.kt |
| che_발령 | che_발령.php | PORTED+Golden | nation/CheBallyeong.kt / NationGoldenTest.kt |
| che_백성동원 | che_백성동원.php | PORTED+Golden | nation/CheBaekseongDongwon.kt / Che백성동원GoldenTest.kt |
| che_부대탈퇴지시 | che_부대탈퇴지시.php | PORTED+Golden | nation/CheBudaeTaltoejisi.kt / Che부대탈퇴지시GoldenTest.kt |
| che_불가침수락 | che_불가침수락.php | PORTED+Golden | nation/CheBulgachimSuak.kt / CheBulgachimSuakGoldenTest.kt |
| che_불가침제의 | che_불가침제의.php | PORTED+Golden | nation/CheBulgachimJeui.kt / CheBulgachimJeuiGoldenTest.kt (0-draw+byte assert) |
| che_불가침파기수락 | che_불가침파기수락.php | PORTED+Golden | nation/CheBulgachimPagiSuak.kt / CheBulgachimPagiSuakGoldenTest.kt |
| che_불가침파기제의 | che_불가침파기제의.php | PORTED+Golden | nation/CheBulgachimPagijeui.kt / CheBulgachimPagijeuiGoldenTest.kt |
| che_선전포고 | che_선전포고.php | **PORTED no golden (로그 위조 divergence — F1, P0)** | nation/CheSeonjeonpogo.kt:98-137 |
| che_수몰 | che_수몰.php | PORTED+Golden | nation/CheSumol.kt / Che수몰GoldenTest.kt |
| che_의병모집 | che_의병모집.php | PORTED+Golden | nation/CheUibyeongMojip.kt / Che의병모집GoldenTest.kt |
| che_이호경식 | che_이호경식.php | PORTED+Golden | nation/CheIhoGyeongsik.kt / Che이호경식GoldenTest.kt |
| che_종전수락 | che_종전수락.php | PORTED+Golden | nation/CheJongjeonSuak.kt / CheJongjeonSuakGoldenTest.kt |
| che_종전제의 | che_종전제의.php | PORTED+Golden | nation/CheJongjeonjeui.kt / CheJongjeonjeuiGoldenTest.kt |
| che_증축 | che_증축.php | PORTED no golden | nation/CheJeungchuk.kt (GamchukJeungchukTest 유닛만, UNGATED) |
| che_천도 | che_천도.php | PORTED+Golden | nation/CheCheondo.kt / NationGoldenTest.kt |
| che_초토화 | che_초토화.php | PORTED+Golden | nation/CheChotohwa.kt / Che초토화GoldenTest.kt |
| che_포상 | che_포상.php | PORTED+Golden | nation/ChePosang.kt / NationGoldenTest.kt |
| che_피장파장 | che_피장파장.php | PORTED+Golden | nation/ChePijangPajang.kt / Che피장파장GoldenTest.kt |
| che_필사즉생 | che_필사즉생.php | PORTED+Golden | nation/ChePilsaJeukSaeng.kt / Che필사즉생GoldenTest.kt |
| che_허보 | che_허보.php | PORTED+Golden | nation/CheHeobo.kt / Che허보GoldenTest.kt |
| cr_인구이동 | cr_인구이동.php | PORTED+Golden | nation/CrInguIdong.kt / CrInguIdongGoldenTest.kt |
| event_극병연구 | event_극병연구.php | PORTED+Golden | nation/EventGeukbyeongYeongu.kt / Event극병연구GoldenTest.kt |
| event_대검병연구 | event_대검병연구.php | PORTED+Golden | nation/EventDaegeombyeongYeongu.kt / Event대검병연구GoldenTest.kt |
| event_무희연구 | event_무희연구.php | PORTED+Golden | nation/EventMuhuiYeongu.kt / Event무희연구GoldenTest.kt |
| event_산저병연구 | event_산저병연구.php | PORTED+Golden | nation/EventSanjeobyeongYeongu.kt / Event산저병연구GoldenTest.kt |
| event_상병연구 | event_상병연구.php | PORTED+Golden | nation/EventSangbyeongYeongu.kt / Event상병연구GoldenTest.kt |
| event_원융노병연구 | event_원융노병연구.php | PORTED+Golden | nation/EventWonyungnobyeongYeongu.kt / Event원융노병연구GoldenTest.kt |
| event_음귀병연구 | event_음귀병연구.php | PORTED+Golden | nation/EventEumgwibyeongYeongu.kt / Event음귀병연구GoldenTest.kt |
| event_화륜차연구 | event_화륜차연구.php | PORTED+Golden | nation/EventHwaryunchaYeongu.kt / Event화륜차연구GoldenTest.kt |
| event_화시병연구 | event_화시병연구.php | PORTED+Golden | nation/EventHwasibyeongYeongu.kt / Event화시병연구GoldenTest.kt |
| 휴식 | 휴식.php | PORTED no golden | RestAction (Nation 휴식.php:35-38은 로그 없음 — 이 쪽은 패러티 OK) |

## Finding 목록

### F1 (P0) — che_선전포고 resolve() 로그 위조(fabrication) + 국메·로그 스코프 누락, 골든 부재
- legacy: `Command/Nation/che_선전포고.php:148-156` — 로그 6종이 정본:
  - `<D><b>{destNationName}</b></>에 선전 포고 했습니다.<1>$date</>` (generalActionLog, :148)
  - generalHistoryLog `…에 선전 포고` (:149), 자국 nationalHistoryLog `<Y>{generalName}</>{josaYi} …` (:150), **상대국** destLogger nationalHistoryLog (:151), globalActionLog `<Y>{generalName}</>{josaYi} <D><b>…</b></>에 <M>선전 포고</> 하였습니다.` (:153), globalHistoryLog `<R><b>【선포】</b></>…` (:154). 이어 :166-190 국가 메시지(국메) `【외교】{year}년 {month}월:{nationName}에서 {destNationName}에 선전포고` 송신.
- impl: `logic/.../actions/nation/CheSeonjeonpogo.kt:120-137` — 3개 로그를 **창작 포맷**으로 출력: `:127` `<D><b>$destName</b></>$josaEul 선전포고했습니다.`(조사·띄어쓰기·date 토큰 모두 PHP와 불일치), `:130-131` 국가로그, `:136` 글로벌로그 모두 PHP에 없는 문자열. destLogger(상대국 스코프)·국메 송신 없음. diplomacy state=1/term=24 자체는 일치(DiplomacyState.kt:16,30).
- 골든 부재(`grep` 결과 선전포고 골든 0건; PARITY_LEDGER.md:187도 "no dedicated golden (flagged)"로 자인). CLAUDE.md 규칙 5(절대 위조 금지) 위반 — 캡처 불가 시 격리+백로그가 정본 절차인데 창작 문자열이 메인에 들어가 있음. 플레이어 도달 가능(catalog + chief FE 제출 경로 존재).

### F2 (P1) — che_견문 resolve() 빈 STUB (RNG·보상·로그 전부 미실행)
- legacy: `Command/General/che_견문.php:55-122` — run()이 RNG draw(부상 `:103` `nextRangeInt(10,20)`, `:106` `nextRangeInt(20,50)`) + exp/gold/rice 증분 + `:111` `pushGeneralActionLog("{$text} <1>$date</>")` 수행.
- impl: `logic/.../actions/develop/CheGyeonmun.kt:39` — `override fun resolve(context) { /* downstream: sightseeing content */ }` 빈 no-op. 주석 스스로 "downstream seam" 선언.
- PARITY_LEDGER.md:80은 이를 **DONE**(intake ✓ / fe ✓)으로 분류 — 플레이어가 견문을 예약하면 아무 효과·로그 없이 턴 소진(silent no-op). DONE 분류는 과대평가.

### F3 (P1) — che_인재탐색 resolve() 빈 STUB
- legacy: `Command/General/che_인재탐색.php:113-…` — `:132` `nextBool`, `:139`/`:204` `choiceUsingWeight`, `:167` `nextRangeInt(20,25)`, `:179` `pickGeneralFromPool` 등 NPC 발굴 전체 로직.
- impl: `logic/.../actions/personnel/CheInjaeTamsaek.kt:47` — `resolve(context) { /* downstream: NPC-pool scouting */ }` 빈 no-op.
- PARITY_LEDGER.md:91 역시 **DONE**(fe ✓) — F2와 동일한 silent no-op 패턴.

### F4 (P1) — General 휴식 액션 로그 미출력 (매 휴식 턴 로그 패러티 깨짐)
- legacy: `Command/General/휴식.php:40` — `pushGeneralActionLog("아무것도 실행하지 않았습니다. <1>$date</>")` + StaticEventHandler 호출(:43).
- impl: `logic/.../actions/CommandRegistry.kt:96-101` RestAction.resolve = 완전 no-op("a rest turn produces no mutation/log in P1" 주석). 엔진 측에도 해당 문자열 부재(`grep "아무것도" app/game-engine/src/main logic/src/main` → 0건; ReservedTurnHandler는 deny-fallback 로그만 처리).
- 사람/NPC가 휴식할 때마다 PHP 대비 로그 1줄 누락 → 로그 게이트(규칙 3 "Log order = execution order") 상시 divergence. Nation 휴식은 PHP도 무로그(휴식.php Nation판 :35-38)라 영향 없음.

### F5 (P1) — availableChiefCommand에 PHP에 없는 "연구" 카테고리 추가 (예약 가능 범위 divergence)
- legacy: `hwe/sammo/GameConstBase.php:378-415` — `$availableChiefCommand`는 휴식/인사/외교/특수/전략/기타 **6개 카테고리뿐**, event_*연구 없음. `hwe/sammo/API/NationCommand/ReserveCommand.php:47`이 flatten 목록 밖 액션을 **거부** → PHP에선 플레이어가 event_*연구를 예약 불가(시나리오 이벤트 전용; scenario_912.json에만 등장, 1010에는 없음).
- impl: `common/.../constants/GameConst.kt:455,492-502` — "연구" 카테고리로 event_*연구 9종 추가. `app/game-api/.../read/F4StateText.kt:148-163` CHIEF_COMMAND_TABLE에도 동일 7번째 카테고리(KDoc :144-146은 "GameConstBase.php:378-415 byte-for-byte"라고 주장 — 사실과 다름). `CommandQueueService.kt:287-288` CHIEF_COMMAND_CODES 게이트가 이 집합 기준이라 FE(ChiefCommandReserve → api.ts:421-429 nationBulk)에서 플레이어가 연구를 예약 가능.
- 의도적 divergence라면 문서화 부재(주석은 오히려 byte-for-byte 주장). 패러티 정본대로면 6 카테고리 + 연구는 이벤트 경로 한정이어야 함.

### F6 (P1) — tryUniqueItemLottery 횡단 미포팅 (유니크 아이템 획득 경로 전면 부재)
- legacy: General 커맨드 **34/55**가 `tryUniqueItemLottery` 호출(`grep -l` 실측; 예 `che_귀환.php:100`, `che_장비매매.php:201`). 별도 'unique' RNG라 메인 draw 스트림은 안 깨지지만, 커맨드 경유 유니크 획득이 PHP의 정규 경로.
- impl: Kotlin 포팅은 Vote 경로 단 1곳(`logic/.../actions/vote/VoteLottery.kt:85`, func.php:1611-1703 미러). 커맨드 측은 전부 주석 seam(`CheGwihwan.kt:62`, `CheJangbiMaemae.kt:195`, `CheDanryeon.kt:152` "TODO(백로그)").
- 문서화된 백로그이므로 위조는 아니나, 34개 커맨드 공통의 플레이어 가시 기능 부재 — 단일 횡단 작업으로 닫는 게 효율적.

### F7 (P2) — GAP_AUDIT.md 커맨드 섹션 stale ("19 PORT_MISSING")
- 클레임: `docs/superpowers/GAP_AUDIT.md:26` 및 `:44` — "Command parity (93 total) | **52 DONE** / 19 PORT_MISSING / 20 FE_MISSING / 2 LOGIC_ONLY / 5 WIRING".
- 실측: PORT_MISSING **0** (CommandRegistry.kt:108-211에 93/93 등록, 본 감사 표). PARITY_LEDGER.md:22도 이미 0으로 갱신됨(DONE 71). GAP_AUDIT 헤더/요약표만 미갱신. `:60-64`의 "silent-no-op intake (auction_bid/bet/BuyHiddenBuff…)" 서술도 PARITY_LEDGER.md:43-47 (FIXED W2, 2026-06-08)과 모순.

### F8 (P2) — PARITY_LEDGER.md 자체 stale 2건 (실제보다 보수적/낙관적 혼재)
- (a) 골든 과소 기재: `:184-186` 불가침제의/종전제의/불가침파기제의 coverage를 "unit test (not draw-for-draw golden)"로 기재했으나 실제 전용 골든 존재 — `logic/src/test/.../nation/CheBulgachimJeuiGoldenTest.kt`(0-draw + actor log byte assert, che_불가침제의.php:182,202-212 인용), CheJongjeonjeuiGoldenTest.kt, CheBulgachimPagijeuiGoldenTest.kt.
- (b) FE_MISSING 20 과대 기재: `:139-168`은 "chief-center read-only" 전제이나, 현재 chief-center는 `web/game/components/game/ChiefCommandReserve.tsx`(명령 팔레트+슬롯 편집, page.tsx:111-115) + `api.ts:421-429`(nationBulk/push/repeat, P0-09/10/11) 로 제출 가능. CHIEF_COMMAND_TABLE에 포함된 18/20이 FE-wired 됨(잔여: cr_인구이동, che_무작위수도이전 — 단 이 둘은 PHP availableChiefCommand에도 없으므로 비노출이 패러티 정답). 총괄표(:21-25)의 "FE_MISSING 20 / WIRING 5"는 재산정 필요.

### F9 (P2) — 증축/감축/무작위수도이전 골든 UNGATED + 해산 history 버킷 미assert
- `nation/CheJeungchuk.kt`/`CheGamchuk.kt`/`CheMujakwiSudoIjeon.kt`: 골든 0건 실측(grep), 유닛(GamchukJeungchukTest/CheondoTest)만. PARITY_LEDGER.md:166-168도 UNGATED 자인 — 캡처-가능 항목이므로 P8 골든 웨이브 대상.
- `founding/CheHaesan.kt:49-73,135-168`: Che해산GoldenTest는 action 로그만 byte-assert, general/global **history 버킷 5종**(【멸망】 등)은 "GATE-RUNTIME seam(미assert)" — restart-rehydrate 게이트(P6 P8-coupled) 전에 history 채널 assert 경로 필요.

## 닫힌 항목 검증 결과

| 클레임 | 출처 | 검증 |
|---|---|---|
| PORT_MISSING 0 (General 14종 Wave1/A1/A4 마감) | PARITY_LEDGER.md:53-57 | ✅ 사실 — 화계/파괴/탈취/선동/첩보/단련/강행/접경귀환/숙련전환/등용수락/전투태세/모반시도/전투특기·내정특기초기화 모두 impl+전용 골든 실재 확인 |
| PORT_MISSING 0 (Nation: cr_인구이동 + event_*연구 9종) | PARITY_LEDGER.md:129-132 | ✅ 사실 — CrInguIdongGoldenTest + Event*연구GoldenTest 9건 실재 |
| F4-C3 12종 골든 게이트 | PARITY_LEDGER.md:148-159 | ✅ 사실 — Che급습~Che허보 GoldenTest 12건 실재 |
| W2 intake-code mismatch FIXED (auctionBid/placeBet/BuyHiddenBuff…) | PARITY_LEDGER.md:43-47 | ⚠️ 부분 — CommandWireMapper에 코드 존재 확인. 단 진행 중 작업(태스크 보드 P0-07 placeBet 검증 포팅, P0-50 BuyHiddenBuff 400)이 있어 "완전 종결" 단정 불가 |
| GAP_AUDIT "52 DONE / 19 PORT_MISSING" | GAP_AUDIT.md:26,:44 | ❌ stale — 실측 93/93 등록·72 golden (F7) |
| 디플로 제의 3종 = unit only | PARITY_LEDGER.md:184-186 | ❌ stale — 전용 골든 존재 (F8a) |
| chief-center 100% read-only | GAP_AUDIT.md:71-73 / PARITY_LEDGER.md:139-144 | ❌ stale — ChiefCommandReserve + nationBulk 제출 경로 라이브 (F8b) |
| che_선양/che_NPC능동 LOGIC_ONLY 격리 | PARITY_LEDGER.md:64-65 | ✅ 사실 — CheSeonyang.kt:24,42,77 G4 격리 마커, 레지스트리 주석과 일치 |

## 권고 우선순위

1. **F1 선전포고**: PHP 골든 캡처(`tools/php-golden`) → 로그 6종+국메 byte-port → 전용 골든. 캡처 불가 시 현행 창작 로그를 제거하고 격리 절차로 전환(위조 상태 유지 불가).
2. **F2/F3 견문·인재탐색**: catalog에서 일시 제외(silent no-op 차단)하거나 즉시 포팅. SightseeingMessage/pickGeneralFromPool 하위 시스템 포함.
3. **F4 휴식 로그** 1줄 포팅(저비용·상시 발생).
4. **F5 연구 카테고리**: PHP 6-카테고리로 환원 + event_*연구는 이벤트/시나리오 경로로 격리(또는 의도적 divergence로 MILESTONES 문서화).
5. **F7/F8 문서 동기화**: GAP_AUDIT 요약표·PARITY_LEDGER 골든/FE 컬럼 재산정.
