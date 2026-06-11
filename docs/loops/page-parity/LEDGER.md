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

## 승인 대기

- 없음 (GOLDENSET.md 2026-06-10 승인·동결)
